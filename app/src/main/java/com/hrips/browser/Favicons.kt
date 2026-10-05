package com.hrips.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Иконки сайтов. Берём прямо с самого сайта (ссылки <link rel="icon"> в HTML, затем /favicon.ico),
 * кэшируем в памяти и на диске. Сторонние сервисы не используются.
 */
object Favicons {
    private val memory = ConcurrentHashMap<String, ImageBitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private fun dir(context: Context) = File(context.cacheDir, "favicons").apply { mkdirs() }

    suspend fun load(context: Context, host: String): ImageBitmap? {
        memory[host]?.let { return it }
        if (host in failed) return null
        val bmp = withContext(Dispatchers.IO) { fromDisk(context, host) ?: fromNetwork(context, host) }
        val img = bmp?.asImageBitmap()
        if (img != null) memory[host] = img else failed.add(host)
        return img
    }

    private fun fromDisk(context: Context, host: String): Bitmap? {
        val f = File(dir(context), "$host.png")
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    private fun fromNetwork(context: Context, host: String): Bitmap? {
        val base = "https://$host/"
        val candidates = mutableListOf<String>()
        val html = download(base, 65536)
        if (html != null) {
            val text = String(html, Charsets.UTF_8)
            val relRe = Regex("rel\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            val hrefRe = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            val found = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { m ->
                val tag = m.value
                val rel = relRe.find(tag)?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
                if (!rel.contains("icon")) return@mapNotNull null
                val href = hrefRe.find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                if (href.startsWith("data:") || href.substringBefore('?').endsWith(".svg", ignoreCase = true)) return@mapNotNull null
                rel to href
            }.toList()
            // apple-touch-icon обычно крупнее и чётче обычного favicon
            found.sortedByDescending { it.first.contains("apple-touch") }.forEach { (_, href) ->
                runCatching { URL(URL(base), href).toString() }.getOrNull()?.let { candidates.add(it) }
            }
        }
        candidates.add(base + "favicon.ico")

        for (u in candidates.take(4)) {
            val data = download(u, 1_000_000) ?: continue
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: continue
            val scaled = if (bmp.width > 96) Bitmap.createScaledBitmap(bmp, 96, 96 * bmp.height / bmp.width, true) else bmp
            runCatching {
                File(dir(context), "$host.png").outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            return scaled
        }
        return null
    }

    private fun download(url: String, max: Int): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) hrips")
        if (c.responseCode !in 200..299) {
            null
        } else {
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (out.size() < max) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Показывает иконку сайта, а пока она грузится (или её нет), запасной вариант [fallback].
 * fill = true: иконка растягивается на весь квадрат, а фон под ней берётся из цвета её угла.
 */
@Composable
fun Favicon(url: String, size: Dp, fill: Boolean = false, fallback: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val host = remember(url) { if (url.isBlank()) null else Uri.parse(url).host }
    val bitmap by produceState<ImageBitmap?>(null, host) {
        value = if (host == null) null else Favicons.load(context, host)
    }
    val neutral = MaterialTheme.colorScheme.surfaceContainerHighest
    val b = bitmap
    if (b != null && fill) {
        val bg = remember(b, neutral) { cornerColor(b) ?: neutral }
        Box(Modifier.size(size).background(bg)) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    } else if (b != null) {
        Image(b, null, Modifier.size(size).clip(RoundedCornerShape(size / 5)))
    } else {
        fallback()
    }
}

private fun cornerColor(img: ImageBitmap): Color? = try {
    val bmp = img.asAndroidBitmap()
    val px = bmp.getPixel(minOf(1, bmp.width - 1), minOf(1, bmp.height - 1))
    if (android.graphics.Color.alpha(px) >= 200) Color(px) else null
} catch (e: Exception) {
    null
}
