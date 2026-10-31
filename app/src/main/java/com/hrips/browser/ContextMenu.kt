@file:Suppress("DEPRECATION")

package com.hrips.browser

import androidx.compose.material3.toShape
import androidx.compose.material3.MaterialShapes
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
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ContextInfo(
    val linkUri: String?,
    val srcUri: String?,
    val type: Int,
    val title: String?,
    val altText: String?,
    val baseUri: String?,
)

private class Act(val icon: ImageVector, val label: String, val run: () -> Unit)

private const val UA = "Mozilla/5.0 (Linux; Android 14) hrips"
private const val MAX_PIXELS = 64_000_000L

private fun isHttp(u: String) = u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContextMenuSheet(info: ContextInfo, browser: Browser, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val link = info.linkUri?.takeIf { it.isNotBlank() }
    val media = info.srcUri?.takeIf { it.isNotBlank() && info.type != ContextElement.TYPE_NONE }
    if (link == null && media == null) return
    val isImage = info.type == ContextElement.TYPE_IMAGE
    val mediaHttp = media != null && isHttp(media)
    val priv = browser.current.isPrivate
    val (noun, nounOf) = when (info.type) {
        ContextElement.TYPE_IMAGE -> "изображение" to "изображения"
        ContextElement.TYPE_VIDEO -> "видео" to "видео"
        else -> "аудио" to "аудио"
    }

    fun copy(text: String, done: String = "Скопировано") {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
        Notices.show(done)
    }
    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    val linkActs = buildList {
        if (link != null) {
            if (!link.startsWith("javascript:")) {
                add(Act(HripsIcons.Add, "Открыть ссылку в новой вкладке") { browser.newTab(link, incognito = priv) })
                if (!priv) {
                    add(Act(HripsIcons.Mask, "Открыть ссылку в приватной вкладке") { browser.newTab(link, incognito = true) })
                }
            }
            add(Act(HripsIcons.Share, "Поделиться ссылкой") { share(link) })
            add(Act(HripsIcons.Link, "Копировать адрес ссылки") { copy(link, "Адрес ссылки скопирован") })
            if (isHttp(link)) add(Act(HripsIcons.Download, "Скачать по ссылке") { browser.saveUrl(link, info.baseUri, priv) })
        }
    }
    val mediaActs = buildList {
        if (media != null) {
            if (isImage && mediaHttp) {
                val searchEngine = ImageSearch.effectiveEngine(SearchEngines.current)
                val name = if (searchEngine.host == "www.google.com") "Google Lens" else searchEngine.name
                add(Act(HripsIcons.Search, "Найти это изображение в $name") {
                    browser.newTab(ImageSearch.byUrl(SearchEngines.current, media), incognito = priv)
                })
            }
            if (mediaHttp) {
                add(Act(HripsIcons.Add, "Открыть $noun в новой вкладке") { browser.newTab(media, incognito = priv) })
                add(Act(HripsIcons.Download, "Сохранить $noun…") { browser.saveUrl(media, info.baseUri, priv) })
                if (isImage) add(Act(HripsIcons.Copy, "Скопировать изображение") { copyImage(ctx.applicationContext, browser, media, info.baseUri) })
            }
            add(Act(HripsIcons.Share, "Поделиться адресом $nounOf") { share(media) })
            add(Act(HripsIcons.Link, "Копировать адрес $nounOf") { copy(media, "Адрес скопирован") })
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
        value = if (media != null && isImage && mediaHttp && !priv) withContext(Dispatchers.IO) { loadThumb(media, info.baseUri) } else null
    }
    val previewUrl = media?.takeIf { info.type == ContextElement.TYPE_VIDEO && isHttp(it) && !priv }
    var previewFailed by remember(previewUrl) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainerLow,
        shape = HripsShapes.XL,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            if (previewUrl != null && !previewFailed) {
                VideoPreview(previewUrl, info.baseUri) { previewFailed = true }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    val t = thumb
                    if (t != null) {
                        Image(t, null, Modifier.fillMaxSize().clip(HripsShapes.M), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.fillMaxSize().background(cs.primaryContainer, MaterialShapes.Cookie6Sided.toShape()), contentAlignment = Alignment.Center) {
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
            .clip(HripsShapes.L)
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
                                val headers = hashMapOf("User-Agent" to UA)
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
        if (!ready) LoadingIndicator(Modifier.size(32.dp), color = Color.White)
        if (paused) {
            Text(
                "Пауза",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onPrimaryContainer,
                modifier = Modifier.background(cs.primaryContainer, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
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

private fun readLimited(input: InputStream, limit: Int, truncate: Boolean): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16384)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > limit) {
            if (truncate) break else error("too big")
        }
    }
    return out.toByteArray()
}

/** Размеры картинки или null, если это не картинка либо она слишком большая для декодирования. */
private fun boundsOf(data: ByteArray): BitmapFactory.Options? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, o)
    return o.takeIf { it.outWidth > 0 && it.outHeight > 0 && it.outWidth.toLong() * it.outHeight <= MAX_PIXELS }
}

private fun decodeSampled(data: ByteArray, bounds: BitmapFactory.Options, maxSide: Int): Bitmap? {
    var sample = 1
    while (bounds.outWidth / (sample * 2) > maxSide || bounds.outHeight / (sample * 2) > maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun copyImage(app: Context, browser: Browser, url: String, referrer: String?) {
    val failed = "Не удалось скопировать изображение"
    browser.fetch(url, referrer, browser.current.isPrivate) { resp ->
        val body = resp?.body
        if (resp == null || body == null || resp.statusCode !in 200..299) {
            runCatching { body?.close() }
            Notices.show(failed)
            return@fetch
        }
        val queued = AppExecutors.tryExecute {
            try {
                val data = body.use { readLimited(it, 25_000_000, truncate = false) }
                val bounds = boundsOf(data) ?: error("image too large")
                val ext = when (bounds.outMimeType) {
                    "image/jpeg" -> "jpg"
                    "image/gif" -> "gif"
                    else -> "png"
                }
                val dir = File(app.cacheDir, "copied").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val file = File(dir, "image_${System.currentTimeMillis()}.$ext")
                // JPEG, PNG и GIF копируем как есть, остальные форматы перекодируем в PNG.
                if (bounds.outMimeType in setOf("image/jpeg", "image/png", "image/gif")) {
                    file.writeBytes(data)
                } else {
                    val bmp = decodeSampled(data, bounds, 4096) ?: error("not an image")
                    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
                MainThread.handler.post {
                    app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(app.contentResolver, "image", uri))
                    Notices.show("Изображение скопировано")
                }
            } catch (_: Exception) {
                Notices.show(failed)
            }
        }
        if (!queued) {
            runCatching { body.close() }
            Notices.show("Приложение занято. Повторите через пару секунд")
        }
    }
}

private fun loadThumb(url: String, referrer: String?): ImageBitmap? = try {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.setRequestProperty("User-Agent", UA)
        if (!referrer.isNullOrBlank()) c.setRequestProperty("Referer", referrer)
        if (c.responseCode !in 200..299) {
            null
        } else {
            val data = c.inputStream.use { readLimited(it, 3_000_000, truncate = true) }
            boundsOf(data)?.let { decodeSampled(data, it, 512)?.asImageBitmap() }
        }
    } finally {
        c.disconnect()
    }
} catch (e: Exception) {
    null
}
