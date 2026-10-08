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
import android.util.LruCache

/**
 * Иконки сайтов. Берём прямо с самого сайта (ссылки <link rel="icon"> в HTML, затем /favicon.ico),
 * кэшируем в памяти и на диске. Сторонние сервисы не используются.
 */
object Favicons {
    private const val MEMORY_CACHE_KB = 8 * 1024
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            maxOf(1, value.asAndroidBitmap().allocationByteCount / 1024)
    }
    private val failed = LruCache<String, Boolean>(256)
    private val cacheLock = Any()

    private fun dir(context: Context) = File(context.cacheDir, "favicons").apply { mkdirs() }

    suspend fun load(context: Context, host: String): ImageBitmap? {
        val key = host.trim().lowercase()
        if (key.isBlank()) return null
        synchronized(cacheLock) { memory.get(key) }?.let { return it }
        synchronized(cacheLock) { if (failed.get(key) == true) return null }
        val bmp = withContext(Dispatchers.IO) { fromDisk(context, key) ?: fromNetwork(context, key) }
        val img = bmp?.asImageBitmap()
        synchronized(cacheLock) {
            if (img != null) {
                memory.put(key, img)
                failed.remove(key)
            } else {
                failed.put(key, true)
            }
        }
        return img
    }

    private fun fromDisk(context: Context, host: String): Bitmap? {
        val f = File(dir(context), "$host.png")
        return if (f.exists()) decodeIconFile(f) else null
    }

    private fun decodeIconFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) > 96 || bounds.outHeight / (sample * 2) > 96) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun fromNetwork(context: Context, host: String): Bitmap? {
        val base0 = "https://$host/"
        val candidates = mutableListOf<String>()
        val page = downloadFinal(base0, 65536)
        // Относительные ссылки считаем от адреса, куда нас привёл редирект (например, https://www.site.com/)
        val base = page?.second ?: base0
        if (page != null) {
            val text = String(page.first, Charsets.UTF_8)
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
                runCatching { URL(URL(base), href) }.getOrNull()?.let { candidate ->
                    if (candidate.protocol.equals("https", true)) candidates.add(candidate.toString())
                }
            }
        }
        candidates.add(URL(URL(base), "/favicon.ico").toString())
        candidates.add(URL(URL(base), "/apple-touch-icon.png").toString())

        for (u in candidates.distinct().take(6)) {
            val data = download(u, 1_000_000) ?: continue
            val bmp = decodeIcon(data) ?: continue
            val scaled = if (bmp.width > 96) Bitmap.createScaledBitmap(bmp, 96, (96f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true) else bmp
            runCatching {
                File(dir(context), "$host.png").outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            return scaled
        }
        return null
    }


    private fun decodeIcon(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) > 96 || bounds.outHeight / (sample * 2) > 96) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    internal fun download(url: String, max: Int): ByteArray? = downloadFinal(url, max)?.first

    /**
     * Скачивает и возвращает данные вместе с конечным адресом. Редиректы (до 5) идут вручную и только на https:
     * большинство сайтов отвечают на https://site/ перенаправлением на www или на другой путь, раньше из-за этого
     * не находились ни иконки, ни картинки страниц.
     */
    internal fun downloadFinal(url: String, max: Int): Pair<ByteArray, String>? {
        var current = url
        repeat(6) {
            val c = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                return null
            }
            try {
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) hrips")
                val code = c.responseCode
                if (code in 301..308 && code != 304) {
                    val loc = c.getHeaderField("Location") ?: return null
                    val next = runCatching { URL(URL(current), loc) }.getOrNull() ?: return null
                    if (!next.protocol.equals("https", true)) return null
                    current = next.toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                val out = ByteArrayOutputStream(minOf(max, 8192))
                c.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    while (out.size() < max) {
                        val remaining = max - out.size()
                        val n = input.read(buf, 0, minOf(buf.size, remaining))
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                return out.toByteArray() to current
            } catch (e: Exception) {
                return null
            } finally {
                c.disconnect()
            }
        }
        return null
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
