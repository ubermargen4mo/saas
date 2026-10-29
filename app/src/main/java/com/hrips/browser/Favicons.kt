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
import androidx.compose.ui.graphics.Shape
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.util.LruCache

object Favicons {
    private const val MEMORY_CACHE_KB = 8 * 1024
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            maxOf(1, value.asAndroidBitmap().allocationByteCount / 1024)
    }
    private val failed = LruCache<String, Boolean>(256)
    private val cacheLock = Any()
    private val networkGate = Semaphore(3)
    // Fixed stripes avoid retaining one Mutex per hostname while still coalescing most duplicate loads.
    private val hostLocks = Array(16) { Mutex() }

    private fun dir(context: Context) = File(context.cacheDir, "favicons").apply { mkdirs() }

    suspend fun load(context: Context, host: String): ImageBitmap? {
        val key = host.trim().lowercase()
        if (key.isBlank()) return null
        synchronized(cacheLock) { memory.get(key) }?.let { return it }
        synchronized(cacheLock) { if (failed.get(key) == true) return null }
        val bmp = withContext(Dispatchers.IO) {
            fromDisk(context, key) ?: run {
                val lock = hostLocks[(key.hashCode() and Int.MAX_VALUE) % hostLocks.size]
                lock.withLock {
                    fromDisk(context, key) ?: run {
                        if (synchronized(cacheLock) { failed.get(key) == true }) null
                        else networkGate.withPermit { fromNetwork(context, key) }.also { result ->
                            if (result == null) synchronized(cacheLock) { failed.put(key, true) }
                        }
                    }
                }
            }
        }
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
        val base = page?.second ?: base0
        if (page != null) {
            val text = String(page.first, Charsets.UTF_8)
            candidates.addAll(resolveIconUrls(base, parseIconLinks(text)))
        }
        candidates.add(URL(URL(base), "/favicon.ico").toString())
        candidates.add(URL(URL(base), "/apple-touch-icon.png").toString())

        var best: Bitmap? = null
        for (u in candidates.distinct().take(6)) {
            val data = download(u, 1_000_000) ?: continue
            val bmp = decodeIcon(data) ?: continue
            val previous = best
            if (previous == null) {
                best = bmp
                if (hasOpaqueCorners(bmp)) break
            } else if (hasOpaqueCorners(bmp) && bmp.width >= 120) {
                previous.recycle()
                best = bmp
                break
            } else {
                bmp.recycle()
            }
        }
        val bmp = best ?: return null
        val scale = minOf(1f, 96f / bmp.width, 96f / bmp.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        } else bmp
        if (scaled !== bmp) bmp.recycle()
        runCatching {
            File(dir(context), "$host.png").outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return scaled
    }

    private fun hasOpaqueCorners(b: Bitmap): Boolean {
        val right = b.width - 1
        val bottom = b.height - 1
        return android.graphics.Color.alpha(b.getPixel(0, 0)) >= 200 &&
            android.graphics.Color.alpha(b.getPixel(right, 0)) >= 200 &&
            android.graphics.Color.alpha(b.getPixel(0, bottom)) >= 200 &&
            android.graphics.Color.alpha(b.getPixel(right, bottom)) >= 200
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

    internal fun downloadFinal(url: String, max: Int): Pair<ByteArray, String>? {
        var current = url
        repeat(6) {
            val c = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                return null
            }
            var reusable = false
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
                        if (n < 0) { reusable = true; break }
                        out.write(buf, 0, n)
                    }
                }
                return out.toByteArray() to current
            } catch (e: Exception) {
                return null
            } finally {
                // Дочитали до конца: соединение возвращается в пул и подходит для следующей иконки того же сайта.
                if (!reusable) c.disconnect()
            }
        }
        return null
    }
}

@Composable
fun Favicon(url: String, size: Dp, fill: Boolean = false, shape: Shape? = null, fallback: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val host = remember(url) { if (url.isBlank()) null else Uri.parse(url).host }
    val bitmap by produceState<ImageBitmap?>(null, host) {
        value = if (host == null) null else Favicons.load(context, host)
    }
    val neutral = MaterialTheme.colorScheme.surfaceContainerHighest
    val b = bitmap
    if (b != null && fill) {
        val bg = remember(b, neutral) { cornerColor(b) ?: neutral }
        Box(Modifier.size(size).then(if (shape != null) Modifier.clip(shape) else Modifier).background(bg)) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    } else if (b != null) {
        Image(b, null, Modifier.size(size).clip(shape ?: RoundedCornerShape(size / 5)))
    } else {
        fallback()
    }
}

private fun cornerColor(img: ImageBitmap): Color? = try {
    val bmp = img.asAndroidBitmap()
    val corner = bmp.getPixel(minOf(1, bmp.width - 1), minOf(1, bmp.height - 1))
    if (android.graphics.Color.alpha(corner) >= 200) Color(corner) else dominantColor(bmp)
} catch (e: Exception) {
    null
}

private fun dominantColor(bmp: Bitmap): Color? {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val buckets = HashMap<Int, IntArray>()
    var opaque = 0
    for (c in px) {
        if (android.graphics.Color.alpha(c) < 200) continue
        opaque++
        val r = android.graphics.Color.red(c)
        val g = android.graphics.Color.green(c)
        val b = android.graphics.Color.blue(c)
        val e = buckets.getOrPut(((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)) { IntArray(4) }
        e[0]++; e[1] += r; e[2] += g; e[3] += b
    }
    if (opaque < px.size * 0.6) return null
    val top = buckets.values.maxByOrNull { it[0] } ?: return null
    return Color(top[1] / top[0], top[2] / top[0], top[3] / top[0])
}
