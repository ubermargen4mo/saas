package com.hrips.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object TabThumbs {
    private const val MAX_PAGES = 80
    private const val MINI_WIDTH = 440

    // noBackupFilesDir, а не cacheDir: система вправе чистить кэш в любой момент, и превью пропадали бы целиком.
    private fun dir(c: Context) = File(c.noBackupFilesDir, "tab_previews_v3").apply { mkdirs() }

    private fun hash(url: String) =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun medium(c: Context, h: String) = File(dir(c), "$h.jpg")
    private fun mini(c: Context, h: String) = File(dir(c), "$h.s.jpg")

    /** Снимки хранятся по id вкладки, а не по адресу: адрес меняется (редиректы, переходы внутри страницы), id нет. */
    fun save(c: Context, id: String, bmp: Bitmap) {
        val h = hash(id)
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
            } else {
                // Сразу кладём в память: карточка не ждёт чтения с диска.
                mediumCache.put(id, bmp.asImageBitmap())
                smallCache.put(id, scaled.asImageBitmap())
                small = null
            }
            trim(c)
        } catch (e: Exception) {
            medium(c, h).delete(); mini(c, h).delete()
        } finally {
            tmpM.delete(); tmpS.delete()
            if (small != null && small !== bmp) small.recycle()
        }
    }

    private const val MAX_PENDING_SAVES = 2
    private val pendingSaves = java.util.concurrent.atomic.AtomicInteger()
    private val capturedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Кладёт крупный снимок в память сразу, запись на диск идёт в фоне. Очередь ограничена: лишний снимок просто не пишем. */
    fun saveAsync(c: Context, id: String, bmp: Bitmap) {
        mediumCache.put(id, bmp.asImageBitmap())
        capturedAt[id] = android.os.SystemClock.elapsedRealtime()
        if (pendingSaves.incrementAndGet() > MAX_PENDING_SAVES) { pendingSaves.decrementAndGet(); return }
        val app = c.applicationContext
        val ran = AppExecutors.tryExecute {
            try { save(app, id, bmp) } finally { pendingSaves.decrementAndGet() }
        }
        if (!ran) pendingSaves.decrementAndGet()
    }

    /** Снимок вкладки сделан совсем недавно: переключатель можно открывать сразу, без нового захвата. */
    fun isFresh(id: String, withinMs: Long = 2500L): Boolean {
        val t = capturedAt[id] ?: return false
        return android.os.SystemClock.elapsedRealtime() - t <= withinMs
    }

    /**
     * Захват похож на неудачный (чёрный или пустой кадр): такой снимок не сохраняем.
     * Проверка та же, что и в проекте-образце: 12x18 точек, малый разброс цвета и почти все точки чёрные.
     */
    fun isLikelyFailed(bmp: Bitmap): Boolean {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return true
        var minR = 255; var minG = 255; var minB = 255
        var maxR = 0; var maxG = 0; var maxB = 0
        var dark = 0
        val cols = 12; val rows = 18
        for (cx in 0 until cols) {
            val x = ((cx + 0.5f) * bmp.width / cols).toInt().coerceIn(0, bmp.width - 1)
            for (ry in 0 until rows) {
                val y = (bmp.height * 0.1f + (ry + 0.5f) * bmp.height * 0.72f / rows).toInt().coerceIn(0, bmp.height - 1)
                val col = bmp.getPixel(x, y)
                val r = android.graphics.Color.red(col); val g = android.graphics.Color.green(col); val b = android.graphics.Color.blue(col)
                if (r <= 16 && g <= 16 && b <= 16) dark++
                if (r < minR) minR = r; if (g < minG) minG = g; if (b < minB) minB = b
                if (r > maxR) maxR = r; if (g > maxG) maxG = g; if (b > maxB) maxB = b
            }
        }
        val range = maxOf(maxR - minR, maxG - minG, maxB - minB)
        return range < 12 && dark.toFloat() / (cols * rows) >= 0.95f
    }

    /** Удаляет файлы вкладок, которых больше нет, и старый каталог в cacheDir. */
    fun prune(c: Context, validIds: Set<String>) {
        val app = c.applicationContext
        AppExecutors.tryExecute {
            val keep = validIds.mapTo(HashSet()) { hash(it) }
            dir(app).listFiles()?.forEach { f ->
                val h = f.name.substringBefore('.')
                // Свежие файлы не трогаем: вкладка могла появиться уже после того, как собрали список.
                if (h !in keep && System.currentTimeMillis() - f.lastModified() > 60_000L) f.delete()
            }
            File(app.cacheDir, "tabthumbs").takeIf { it.isDirectory }?.let { old -> old.listFiles()?.forEach { it.delete() }; old.delete() }
        }
    }

    fun delete(c: Context, id: String) {
        smallCache.remove(id); mediumCache.remove(id); capturedAt.remove(id)
        val app = c.applicationContext
        AppExecutors.tryExecute { val h = hash(id); medium(app, h).delete(); mini(app, h).delete() }
    }

    /** Реакция на нехватку памяти: крупные снимки выбрасываем первыми. */
    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mediumCache.evictAll()
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) smallCache.evictAll()
    }

    private fun trim(c: Context) {
        val pages = dir(c).listFiles { f -> f.name.endsWith(".jpg") && !f.name.endsWith(".s.jpg") } ?: return
        if (pages.size <= MAX_PAGES) return
        pages.sortedBy { it.lastModified() }.take(pages.size - MAX_PAGES).forEach {
            it.delete()
            File(it.parentFile, it.name.removeSuffix(".jpg") + ".s.jpg").delete()
        }
    }

    // Два отдельных кэша: крупные снимки не должны вытеснять мелкие (из-за этого карточки списка и сетки «моргали»).
    private val smallCache = object : android.util.LruCache<String, ImageBitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val mediumCache = object : android.util.LruCache<String, ImageBitmap>(14 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private fun cacheFor(small: Boolean) = if (small) smallCache else mediumCache

    fun peek(id: String, small: Boolean): ImageBitmap? = cacheFor(small).get(id)

    suspend fun load(c: Context, id: String, small: Boolean = false): ImageBitmap? {
        cacheFor(small).get(id)?.let { return it }
        val bmp = loadFromDisk(c, id, small)
        if (bmp != null) cacheFor(small).put(id, bmp)
        return bmp
    }

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Заранее читает снимки с диска в память, чтобы при открытии переключателя ничего не подгружалось на глазах. */
    fun prefetch(c: Context, ids: List<String>, small: Boolean) {
        val app = c.applicationContext
        prefetchScope.launch {
            for (id in ids) if (peek(id, small) == null) load(app, id, small)
        }
    }

    private suspend fun loadFromDisk(c: Context, id: String, small: Boolean): ImageBitmap? = withContext(Dispatchers.IO) {
        val h = hash(id)
        val s = mini(c, h)
        val file = if (small && s.exists()) s else medium(c, h)
        if (!file.exists()) return@withContext null
        val bmp = decode(file, if (file === s) 1 else if (small) 3 else 1) ?: return@withContext null
        if (isLikelyFailed(bmp)) {
            // Старый неудачный кадр (чёрный экран): удаляем, пусть вкладка получит новый снимок.
            bmp.recycle(); medium(c, h).delete(); mini(c, h).delete()
            return@withContext null
        }
        bmp.asImageBitmap()
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
        smallCache.evictAll(); mediumCache.evictAll(); capturedAt.clear()
        dir(c).listFiles()?.forEach { it.delete() }
    }
}
