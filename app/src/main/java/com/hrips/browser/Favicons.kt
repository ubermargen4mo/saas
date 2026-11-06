package com.hrips.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import android.graphics.Color as AndroidColor

private const val ICON_MAX_PX = 96
private const val OPAQUE_ALPHA = 200
private const val FAIL_RETRY_MS = 10 * 60 * 1000L
private const val DISK_CAP_BYTES = 6L * 1024 * 1024
private const val PRUNE_EVERY_WRITES = 40

object Favicons {
    private val memory = object : LruCache<String, ImageBitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            maxOf(1, value.asAndroidBitmap().allocationByteCount / 1024)
    }
    private val failedAt = LruCache<String, Long>(256)
    private val cacheLock = Any()
    private val networkGate = Semaphore(3)
    private val hostLocks = Array(16) { Mutex() }
    private val writes = java.util.concurrent.atomic.AtomicInteger()

    private fun iconFile(context: Context, host: String) =
        File(File(context.cacheDir, "favicons").apply { mkdirs() }, "$host.png")

    // RUNNING_LOW (10) устарел, но порог по-прежнему верный: с Android 14 сюда приходят UI_HIDDEN (20) и BACKGROUND (40).
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) synchronized(cacheLock) { memory.evictAll() }
    }

    private fun isFailed(key: String) = synchronized(cacheLock) {
        failedAt.get(key)?.let { SystemClock.elapsedRealtime() - it < FAIL_RETRY_MS } == true
    }

    suspend fun load(context: Context, host: String, network: Boolean = true): ImageBitmap? {
        val key = host.trim().lowercase()
        if (key.isBlank()) return null
        synchronized(cacheLock) { memory.get(key) }?.let { return it }
        if (isFailed(key)) return null
        val bmp = withContext(Dispatchers.IO) {
            fromDisk(context, key) ?: if (!network) null else hostLocks[(key.hashCode() and Int.MAX_VALUE) % hostLocks.size].withLock {
                fromDisk(context, key)
                    ?: if (isFailed(key)) null else networkGate.withPermit { fromNetwork(context, key) }
            }
        }
        if (bmp == null && !network) return null
        val img = bmp?.asImageBitmap()
        synchronized(cacheLock) {
            if (img != null) {
                memory.put(key, img)
                failedAt.remove(key)
            } else {
                failedAt.put(key, SystemClock.elapsedRealtime())
            }
        }
        return img
    }

    private fun fromDisk(context: Context, host: String): Bitmap? {
        val f = iconFile(context, host)
        if (!f.exists()) return null
        val bmp = decodeSampled { BitmapFactory.decodeFile(f.path, it) }
        if (bmp == null) f.delete() else f.setLastModified(System.currentTimeMillis())
        return bmp
    }

    private fun writeToDisk(context: Context, host: String, bmp: Bitmap) {
        val target = iconFile(context, host)
        val tmp = File(target.parentFile, "$host.tmp")
        try {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            AppLog.w("Favicons", "не удалось сохранить иконку $host", e)
            return
        }
        if (writes.incrementAndGet() % PRUNE_EVERY_WRITES == 0) prune(target.parentFile ?: return)
    }

    private fun prune(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CAP_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CAP_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    private fun fromNetwork(context: Context, host: String): Bitmap? {
        val root = "https://$host/"
        val page = downloadFinal(root, 65536)
        val base = page?.second ?: root
        val candidates = buildList {
            if (page != null) addAll(resolveIconUrls(base, parseIconLinks(String(page.first, Charsets.UTF_8))))
            add(URL(URL(base), "/favicon.ico").toString())
            add(URL(URL(base), "/apple-touch-icon.png").toString())
        }

        var best: Bitmap? = null
        for (u in candidates.distinct().take(6)) {
            val data = download(u, 1_000_000) ?: continue
            val bmp = decodeSampled { BitmapFactory.decodeByteArray(data, 0, data.size, it) } ?: continue
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
        val scale = minOf(1f, ICON_MAX_PX.toFloat() / bmp.width, ICON_MAX_PX.toFloat() / bmp.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        } else bmp
        if (scaled !== bmp) bmp.recycle()
        writeToDisk(context, host, scaled)
        return scaled
    }

    private fun hasOpaqueCorners(b: Bitmap): Boolean {
        val right = b.width - 1
        val bottom = b.height - 1
        return listOf(0 to 0, right to 0, 0 to bottom, right to bottom)
            .all { (x, y) -> AndroidColor.alpha(b.getPixel(x, y)) >= OPAQUE_ALPHA }
    }

    private fun decodeSampled(decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, ICON_MAX_PX)
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    internal fun download(url: String, max: Int): ByteArray? = downloadFinal(url, max)?.first

    private fun downloadFinal(url: String, max: Int): Pair<ByteArray, String>? {
        val c = try {
            openFollowingRedirects(url) {
                connectTimeout = 5000
                readTimeout = 5000
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) hrips")
            }
        } catch (e: Exception) {
            return null
        }
        var drained = false
        try {
            val out = ByteArrayOutputStream(minOf(max, 8192))
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (out.size() < max) {
                    val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                    if (n < 0) {
                        drained = true
                        break
                    }
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray() to c.url.toString()
        } catch (e: Exception) {
            return null
        } finally {
            if (!drained) c.disconnect()
        }
    }
}

@Composable
fun Favicon(
    url: String,
    size: Dp,
    fill: Boolean = false,
    shape: Shape? = null,
    network: Boolean = true,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val host = remember(url) { if (url.isBlank()) null else Uri.parse(url).host }
    val bitmap by produceState<ImageBitmap?>(null, host, network) {
        value = if (host == null) null else Favicons.load(context, host, network)
    }
    val neutral = MaterialTheme.colorScheme.surfaceContainerHighest
    val b = bitmap
    if (b != null && fill) {
        val backdrop = remember(b, neutral) { backdropFor(b) ?: Backdrop(neutral, false) }
        Box(Modifier.size(size).then(if (shape != null) Modifier.clip(shape) else Modifier).background(backdrop.color)) {
            if (backdrop.inset) {
                Image(b, null, Modifier.fillMaxSize().padding(size * 0.12f), contentScale = ContentScale.Fit)
            } else {
                Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    } else if (b != null) {
        Image(b, null, Modifier.size(size).clip(shape ?: RoundedCornerShape(size / 5)))
    } else {
        fallback()
    }
}

private class Backdrop(val color: Color, val inset: Boolean)

private fun backdropFor(img: ImageBitmap): Backdrop? = try {
    val bmp = img.asAndroidBitmap()
    val corner = bmp.getPixel(minOf(1, bmp.width - 1), minOf(1, bmp.height - 1))
    when {
        AndroidColor.alpha(corner) >= 200 -> Backdrop(Color(corner), false)
        isElongated(bmp) -> Backdrop(Color.White, true)
        else -> dominantColor(bmp)?.let { Backdrop(it, false) }
    }
} catch (e: Exception) {
    null
}

private fun isElongated(bmp: Bitmap): Boolean {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    var minX = w
    var maxX = -1
    var minY = h
    var maxY = -1
    for (y in 0 until h) for (x in 0 until w) {
        if (AndroidColor.alpha(px[y * w + x]) < 128) continue
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }
    if (maxX < 0) return false
    val ratio = (maxX - minX + 1).toFloat() / (maxY - minY + 1)
    return ratio >= 1.3f || ratio <= 0.77f
}

private fun dominantColor(bmp: Bitmap): Color? {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val buckets = HashMap<Int, IntArray>()
    var opaque = 0
    for (c in px) {
        if (AndroidColor.alpha(c) < 200) continue
        opaque++
        val r = AndroidColor.red(c)
        val g = AndroidColor.green(c)
        val b = AndroidColor.blue(c)
        val e = buckets.getOrPut(((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)) { IntArray(4) }
        e[0]++; e[1] += r; e[2] += g; e[3] += b
    }
    if (opaque < px.size * 0.6) return null
    val top = buckets.values.maxByOrNull { it[0] } ?: return null
    return Color(top[1] / top[0], top[2] / top[0], top[3] / top[0])
}
