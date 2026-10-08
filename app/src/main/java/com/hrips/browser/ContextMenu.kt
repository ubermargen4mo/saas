package com.hrips.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Что под пальцем (долгое нажатие) или курсором (правая кнопка): ссылка и/или картинка, видео, аудио. */
class ContextInfo(
    val linkUri: String?,
    val srcUri: String?,
    val type: Int,
    val title: String?,
    val altText: String?,
    val baseUri: String?,
)

private class Act(val icon: ImageVector, val label: String, val run: () -> Unit)

private fun isHttp(u: String) = u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)

/** Контекстное меню страницы в стиле Material 3 Expressive: шапка с превью и сегментированные группы действий. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContextMenuSheet(info: ContextInfo, browser: Browser, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val link = info.linkUri?.takeIf { it.isNotBlank() }
    val media = info.srcUri?.takeIf { it.isNotBlank() && info.type != ContextElement.TYPE_NONE }
    if (link == null && media == null) return
    val isImage = info.type == ContextElement.TYPE_IMAGE
    val (noun, nounOf) = when (info.type) {
        ContextElement.TYPE_IMAGE -> "изображение" to "изображения"
        ContextElement.TYPE_VIDEO -> "видео" to "видео"
        else -> "аудио" to "аудио"
    }

    fun copy(text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
        Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
    }
    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    val linkActs = buildList {
        if (link != null) {
            if (!link.startsWith("javascript:")) {
                add(Act(HripsIcons.Add, "Открыть ссылку в новой вкладке") { browser.newTab(link, incognito = browser.current.isPrivate) })
                if (!browser.current.isPrivate) {
                    add(Act(HripsIcons.Mask, "Открыть ссылку в приватной вкладке") { browser.newTab(link, incognito = true) })
                }
            }
            add(Act(HripsIcons.Share, "Поделиться ссылкой") { share(link) })
            add(Act(HripsIcons.Link, "Копировать адрес ссылки") { copy(link) })
            if (isHttp(link)) add(Act(HripsIcons.Download, "Скачать по ссылке") { browser.saveUrl(link, info.baseUri, browser.current.isPrivate) })
        }
    }
    val mediaActs = buildList {
        if (media != null) {
            if (isImage && isHttp(media)) {
                add(Act(HripsIcons.Search, "Найти это изображение") {
                    browser.newTab("https://yandex.ru/images/search?rpt=imageview&url=" + Uri.encode(media), incognito = browser.current.isPrivate)
                })
            }
            if (isHttp(media)) add(Act(HripsIcons.Add, "Открыть $noun в новой вкладке") { browser.newTab(media, incognito = browser.current.isPrivate) })
            if (isHttp(media)) add(Act(HripsIcons.Download, "Сохранить $noun…") { browser.saveUrl(media, info.baseUri, browser.current.isPrivate) })
            if (isImage && isHttp(media)) {
                add(Act(HripsIcons.Copy, "Скопировать изображение") { copyImage(ctx.applicationContext, browser, media, info.baseUri) })
            }
            add(Act(HripsIcons.Share, "Поделиться адресом $nounOf") { share(media) })
            add(Act(HripsIcons.Link, "Копировать адрес $nounOf") { copy(media) })
        }
    }

    val title = if (link != null) {
        info.title?.takeIf { it.isNotBlank() } ?: Uri.parse(link).host ?: link
    } else {
        info.altText?.takeIf { it.isNotBlank() } ?: info.title?.takeIf { it.isNotBlank() }
            ?: Uri.parse(media).lastPathSegment ?: media.orEmpty()
    }
    val sub = link ?: media.orEmpty()
    val thumb by produceState<ImageBitmap?>(null, media) {
        value = if (media != null && isImage && isHttp(media)) withContext(Dispatchers.IO) { loadThumb(media, info.baseUri) } else null
    }
    val badge = remember { MaterialShapes.Cookie6Sided }
    // Превью видео: только если есть прямой адрес (http/https). У blob: (YouTube и подобные) файла для показа нет
    val previewUrl = media?.takeIf { info.type == ContextElement.TYPE_VIDEO && isHttp(it) }
    var previewFailed by remember(previewUrl) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            if (previewUrl != null && !previewFailed) {
                VideoPreview(previewUrl, info.baseUri) { previewFailed = true }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    val t = thumb
                    if (t != null) {
                        Image(t, null, Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.fillMaxSize().background(cs.primaryContainer, badge.toShape()), contentAlignment = Alignment.Center) {
                            Icon(
                when {
                    link != null -> HripsIcons.Link
                    info.type == ContextElement.TYPE_VIDEO -> HripsIcons.Video
                    else -> HripsIcons.Image
                },
                null, tint = cs.onPrimaryContainer,
            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (linkActs.isNotEmpty()) ActGroup(linkActs, onDismiss)
            if (linkActs.isNotEmpty() && mediaActs.isNotEmpty()) Spacer(Modifier.height(10.dp))
            if (mediaActs.isNotEmpty()) ActGroup(mediaActs, onDismiss)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private class PreviewHolder {
    var player: MediaPlayer? = null
    var surface: Surface? = null
    fun release() {
        runCatching { player?.release() }
        runCatching { surface?.release() }
        player = null
        surface = null
    }
}

/**
 * Беззвучное зацикленное превью видео над действиями меню. Нажатие ставит на паузу и снимает с неё.
 * MediaPlayer + TextureView (не SurfaceView): картинка обрезается скруглением и нормально живёт внутри шторки.
 * Играет прямые файлы и HLS; если не получилось (защита от хотлинка, неподдерживаемый формат), вызывает [onFail].
 */
@Composable
private fun VideoPreview(url: String, referrer: String?, onFail: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var ready by remember(url) { mutableStateOf(false) }
    var paused by remember(url) { mutableStateOf(false) }
    var aspect by remember(url) { mutableFloatStateOf(16f / 9f) }
    val holder = remember(url) { PreviewHolder() }
    val fail by rememberUpdatedState(onFail)
    DisposableEffect(holder) { onDispose { holder.release() } }

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black)
            .clickable {
                holder.player?.let { p ->
                    runCatching { if (p.isPlaying) { p.pause(); paused = true } else { p.start(); paused = false } }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.aspectRatio(aspect),
            factory = { c ->
                TextureView(c).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            if (holder.player != null) return
                            try {
                                val surface = Surface(st)
                                holder.surface = surface
                                val p = MediaPlayer()
                                holder.player = p
                                p.setSurface(surface)
                                p.setVolume(0f, 0f)
                                p.isLooping = true
                                val headers = HashMap<String, String>()
                                headers["User-Agent"] = "Mozilla/5.0 (Linux; Android 14) hrips"
                                if (!referrer.isNullOrBlank()) headers["Referer"] = referrer
                                p.setDataSource(c, Uri.parse(url), headers)
                                p.setOnVideoSizeChangedListener { _, vw, vh -> if (vw > 0 && vh > 0) aspect = vw.toFloat() / vh }
                                p.setOnPreparedListener { it.start(); ready = true }
                                p.setOnErrorListener { _, _, _ -> fail(); true }
                                p.prepareAsync()
                            } catch (e: Exception) {
                                fail()
                            }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            holder.release()
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
        )
        if (!ready) CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
        if (paused) {
            Text(
                "Пауза",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onPrimaryContainer,
                modifier = Modifier.background(cs.primaryContainer, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ActGroup(acts: List<Act>, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        acts.forEachIndexed { i, a ->
            Surface(
                onClick = { a.run(); onDone() },
                shape = segShape(i, acts.size),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    headlineContent = { Text(a.label) },
                    leadingContent = { Icon(a.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

/**
 * Кладёт саму картинку в буфер обмена: качаем через движок во временный файл в кэше и отдаём
 * через FileProvider как content://-ссылку (так же копируют картинки Chrome и Fenix).
 * JPEG, PNG и GIF кладём как есть, остальное (WebP, AVIF и т.п.) перекодируем в PNG: так вставляется везде.
 */
private fun copyImage(app: Context, browser: Browser, url: String, referrer: String?) {
    val main = Handler(Looper.getMainLooper())
    fun say(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    browser.fetch(url, referrer, browser.current.isPrivate) { resp ->
        val body = resp?.body
        if (resp == null || body == null || resp.statusCode !in 200..299) {
            say("Не удалось скопировать изображение")
            return@fetch
        }
        if (!AppExecutors.tryExecute {
            try {
                val out = ByteArrayOutputStream()
                body.use { input ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > 25_000_000) error("too big")
                    }
                }
                val data = out.toByteArray()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                    bounds.outWidth.toLong() * bounds.outHeight.toLong() > 64_000_000L
                ) error("image too large")
                val raw = when (bounds.outMimeType) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    else -> null
                }
                val dir = File(app.cacheDir, "copied").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val ext = raw ?: "png"
                val file = File(dir, "image_${System.currentTimeMillis()}.$ext")
                if (raw != null) {
                    file.writeBytes(data)
                } else {
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) > 4096 || bounds.outHeight / (sample * 2) > 4096) sample *= 2
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: error("not an image")
                    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
                val mime = when (ext) { "jpg" -> "image/jpeg"; "gif" -> "image/gif"; else -> "image/png" }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
                main.post {
                    val clip = ClipData.newUri(app.contentResolver, "image", uri)
                    app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    Toast.makeText(app, "Изображение скопировано", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                say("Не удалось скопировать изображение")
            }
        }) {
            say("Слишком много фоновых операций")
        }
    }
}

/** Превью картинки для шапки меню. Не получилось (защита от хотлинка, большой файл) - рисуем значок. */
private fun loadThumb(url: String, referrer: String?): ImageBitmap? = try {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) hrips")
        if (!referrer.isNullOrBlank()) c.setRequestProperty("Referer", referrer)
        if (c.responseCode !in 200..299) {
            null
        } else {
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (out.size() < 3_000_000) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            val data = out.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight.toLong() > 64_000_000L
            ) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) > 512 || bounds.outHeight / (sample * 2) > 512) sample *= 2
                BitmapFactory.decodeByteArray(
                    data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }
                )?.asImageBitmap()
            }
        }
    } finally {
        c.disconnect()
    }
} catch (e: Exception) {
    null
}
