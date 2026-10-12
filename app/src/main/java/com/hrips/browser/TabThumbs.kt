package com.hrips.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object TabThumbs {
    private const val MAX_PAGES = 80
    private const val MINI_WIDTH = 360

    private fun dir(c: Context) = File(c.cacheDir, "tabthumbs").apply { mkdirs() }

    private fun hash(url: String) =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun medium(c: Context, h: String) = File(dir(c), "$h.jpg")
    private fun mini(c: Context, h: String) = File(dir(c), "$h.s.jpg")

    fun save(c: Context, url: String, bmp: Bitmap) {
        cache.remove("s:$url"); cache.remove("m:$url")
        val h = hash(url)
        val tmpM = File(dir(c), "$h.tmp")
        val tmpS = File(dir(c), "$h.s.tmp")
        var small: Bitmap? = null
        try {
            tmpM.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            val sh = (MINI_WIDTH * bmp.height / bmp.width.toFloat()).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, MINI_WIDTH, sh, true)
            small = scaled
            tmpS.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmpM.renameTo(medium(c, h)) || !tmpS.renameTo(mini(c, h))) {
                medium(c, h).delete(); mini(c, h).delete()
            }
            trim(c)
        } catch (e: Exception) {
            medium(c, h).delete(); mini(c, h).delete()
        } finally {
            tmpM.delete(); tmpS.delete()
            if (small != null && small !== bmp) small.recycle()
        }
    }

    private fun trim(c: Context) {
        val pages = dir(c).listFiles { f -> f.name.endsWith(".jpg") && !f.name.endsWith(".s.jpg") } ?: return
        if (pages.size <= MAX_PAGES) return
        pages.sortedBy { it.lastModified() }.take(pages.size - MAX_PAGES).forEach {
            it.delete()
            File(it.parentFile, it.name.removeSuffix(".jpg") + ".s.jpg").delete()
        }
    }

    private val cache = object : android.util.LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun peek(url: String, small: Boolean): ImageBitmap? = cache.get((if (small) "s:" else "m:") + url)

    suspend fun load(c: Context, url: String, small: Boolean = false): ImageBitmap? {
        val key = (if (small) "s:" else "m:") + url
        cache.get(key)?.let { return it }
        val bmp = loadFromDisk(c, url, small)
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    private suspend fun loadFromDisk(c: Context, url: String, small: Boolean): ImageBitmap? = withContext(Dispatchers.IO) {
        val h = hash(url)
        val s = mini(c, h)
        if (small && s.exists()) return@withContext decode(mediumOrMini = s, fallbackSample = 1)?.asImageBitmap()
        val m = medium(c, h)
        if (!m.exists()) return@withContext null
        decode(m, if (small) 3 else 1)?.asImageBitmap()
    }

    private fun decode(mediumOrMini: File, fallbackSample: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(mediumOrMini.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L
        ) return null
        var sample = fallbackSample.coerceAtLeast(1)
        while (bounds.outWidth / (sample * 2) > 800 || bounds.outHeight / (sample * 2) > 1800) sample *= 2
        return BitmapFactory.decodeFile(mediumOrMini.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun clear(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }
}
