package com.hrips.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest

object TabThumbs {
    private const val MAX_PAGES = 80
    private const val MINI_WIDTH = 440

    // noBackupFilesDir, а не cacheDir: система вправе чистить кэш в любой момент, и превью пропадали бы целиком.
    private fun dir(c: Context) = File(c.noBackupFilesDir, "tab_previews_v3").apply { mkdirs() }

    private val hex = "0123456789abcdef".toCharArray()

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val n = byte.toInt() and 0xff
            out[index * 2] = hex[n ushr 4]
            out[index * 2 + 1] = hex[n and 0x0f]
        }
        return String(out)
    }

    private fun medium(c: Context, h: String) = File(dir(c), "$h.jpg")
    private fun mini(c: Context, h: String) = File(dir(c), "$h.s.jpg")

    /** Снимки хранятся по id вкладки: URL может меняться, а идентификатор вкладки остаётся прежним. */
    fun save(c: Context, id: String, bmp: Bitmap) {
        val ticket = issueSaveTicket(id)
        save(c, id, bmp, ticket)
    }

    private data class SaveTicket(val generation: Long, val revision: Long)
    private val saveRevisions = HashMap<String, SaveTicket>() // Guarded by diskLock.
    private val saveSequence = java.util.concurrent.atomic.AtomicLong()

    private fun issueSaveTicket(id: String): SaveTicket = synchronized(diskLock) {
        SaveTicket(cacheGeneration, saveSequence.incrementAndGet()).also { saveRevisions[id] = it }
    }

    private fun isCurrentSave(id: String, ticket: SaveTicket) =
        cacheGeneration == ticket.generation && saveRevisions[id] === ticket

    private fun save(c: Context, id: String, bmp: Bitmap, ticket: SaveTicket) {
        if (!synchronized(diskLock) { isCurrentSave(id, ticket) }) return
        val h = hash(id)
        val targetDir = dir(c)
        val tmpMedium = runCatching { File.createTempFile("$h.", ".medium.tmp", targetDir) }.getOrNull()
        if (tmpMedium == null) {
            synchronized(diskLock) { if (saveRevisions[id] === ticket) saveRevisions.remove(id) }
            return
        }
        var tmpSmall: File? = null
        var smallBitmap: Bitmap? = null
        try {
            val tmpSmallFile = File.createTempFile("$h.", ".small.tmp", targetDir)
            tmpSmall = tmpSmallFile
            check(tmpMedium.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) })
            val height = (MINI_WIDTH * bmp.height / bmp.width.toFloat()).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, MINI_WIDTH, height, true)
            smallBitmap = scaled
            check(tmpSmallFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) })

            // Like browser thumbnail stores, commits are versioned: an older capture must never
            // overwrite a newer capture (or resurrect a tab after its preview was deleted).
            val saved = synchronized(diskLock) {
                if (!isCurrentSave(id, ticket)) return@synchronized false to false
                val mediumSaved = runCatching { Os.rename(tmpMedium.absolutePath, medium(c, h).absolutePath); true }
                    .getOrDefault(false)
                val smallSaved = if (mediumSaved) {
                    runCatching { Os.rename(tmpSmallFile.absolutePath, mini(c, h).absolutePath); true }
                        .getOrDefault(false)
                } else false
                if (isCurrentSave(id, ticket)) synchronized(cacheLock) {
                    if (mediumSaved) mediumCache.put(id, bmp.asImageBitmap())
                    if (smallSaved) {
                        smallCache.put(id, scaled.asImageBitmap())
                        smallBitmap = null // The cache now owns the bitmap reference.
                    }
                }
                mediumSaved to smallSaved
            }
            val (mediumSaved, smallSaved) = saved
            if (mediumSaved || smallSaved) trim(c, ticket.generation)
        } catch (_: Exception) {
            // Keep the previous valid preview on failed compression or I/O.
        } finally {
            tmpMedium.delete()
            tmpSmall?.delete()
            if (smallBitmap != null && smallBitmap !== bmp && !smallBitmap.isRecycled) smallBitmap.recycle()
            synchronized(diskLock) {
                if (saveRevisions[id] === ticket) saveRevisions.remove(id)
            }
        }
    }

    private const val MAX_PENDING_SAVES = 2
    private val pendingSaves = java.util.concurrent.atomic.AtomicInteger()
    private val capturedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Кладёт крупный снимок в память сразу, запись на диск идёт в фоне. Очередь ограничена: лишний снимок просто не пишем. */
    fun saveAsync(c: Context, id: String, bmp: Bitmap) {
        if (pendingSaves.incrementAndGet() > MAX_PENDING_SAVES) { pendingSaves.decrementAndGet(); return }
        val ticket = synchronized(diskLock) {
            SaveTicket(cacheGeneration, saveSequence.incrementAndGet()).also { issued ->
                saveRevisions[id] = issued
                synchronized(cacheLock) {
                    mediumCache.put(id, bmp.asImageBitmap())
                    capturedAt[id] = android.os.SystemClock.elapsedRealtime()
                }
            }
        }
        val app = c.applicationContext
        val ran = AppExecutors.tryExecute {
            try { save(app, id, bmp, ticket) } finally { pendingSaves.decrementAndGet() }
        }
        if (!ran) {
            pendingSaves.decrementAndGet()
            synchronized(diskLock) { if (saveRevisions[id] === ticket) saveRevisions.remove(id) }
        }
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
            synchronized(diskLock) {
                dir(app).listFiles()?.forEach { f ->
                    val h = f.name.substringBefore('.')
                    // Serialize pruning with commits so an old listing cannot delete a fresh capture.
                    if (h !in keep && System.currentTimeMillis() - f.lastModified() > 60_000L) f.delete()
                }
            }
            File(app.cacheDir, "tabthumbs").takeIf { it.isDirectory }?.let { old -> old.listFiles()?.forEach { it.delete() }; old.delete() }
        }
    }

    fun delete(c: Context, id: String) {
        val app = c.applicationContext
        val ticket = synchronized(diskLock) {
            SaveTicket(cacheGeneration, saveSequence.incrementAndGet()).also { deletion ->
                saveRevisions[id] = deletion
                synchronized(cacheLock) {
                    smallCache.remove(id)
                    mediumCache.remove(id)
                    capturedAt.remove(id)
                }
            }
        }
        val submitted = AppExecutors.tryExecute {
            val h = hash(id)
            synchronized(diskLock) {
                if (isCurrentSave(id, ticket)) {
                    medium(app, h).delete()
                    mini(app, h).delete()
                    saveRevisions.remove(id)
                }
            }
        }
        if (!submitted) synchronized(diskLock) {
            if (saveRevisions[id] === ticket) saveRevisions.remove(id)
        }
    }

    /** Реакция на нехватку памяти: крупные снимки выбрасываем первыми. */
    fun onTrimMemory(level: Int, tabs: Iterable<Tab> = emptyList()) {
        synchronized(cacheLock) {
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mediumCache.evictAll()
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) smallCache.evictAll()
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val includePrivate = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            tabs.forEach { it.releaseThumbnailMemory(includePrivate) }
        }
    }

    private fun trim(c: Context, generation: Long) = synchronized(diskLock) {
        if (generation != cacheGeneration) return@synchronized
        val pages = dir(c).listFiles { f -> f.name.endsWith(".jpg") && !f.name.endsWith(".s.jpg") } ?: return@synchronized
        if (pages.size <= MAX_PAGES) return@synchronized
        pages.sortedBy { it.lastModified() }.take(pages.size - MAX_PAGES).forEach {
            it.delete()
            File(it.parentFile, it.name.removeSuffix(".jpg") + ".s.jpg").delete()
        }
    }

    // Два отдельных кэша: крупные снимки не должны вытеснять мелкие (из-за этого карточки списка и сетки «моргали»).
    private val smallCache = object : android.util.LruCache<String, ImageBitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }
    private val mediumCache = object : android.util.LruCache<String, ImageBitmap>(14 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }
    private val cacheLock = Any()
    private val diskLock = Any()
    @Volatile private var cacheGeneration = 0L

    private fun cacheFor(small: Boolean) = if (small) smallCache else mediumCache

    fun peek(id: String, small: Boolean): ImageBitmap? = synchronized(cacheLock) { cacheFor(small).get(id) }

    suspend fun load(c: Context, id: String, small: Boolean = false): ImageBitmap? {
        synchronized(cacheLock) { cacheFor(small).get(id) }?.let { return it }
        val app = c.applicationContext
        val key = LoadKey(id, small)
        val request = synchronized(requestsLock) {
            val generation = synchronized(cacheLock) { cacheGeneration }
            inFlight[key]?.takeIf { it.task.isActive && it.generation == generation }?.also { it.consumers++ } ?: run {
                lateinit var task: Deferred<ImageBitmap?>
                task = prefetchScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val image = decodeSlots.withPermit { loadFromDisk(app, id, small, generation) } ?: return@async null
                        val accepted = synchronized(cacheLock) {
                            if (generation != cacheGeneration) false
                            else { cacheFor(small).put(id, image); true }
                        }
                        if (accepted) image else {
                            runCatching { image.asAndroidBitmap().recycle() }
                            null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } finally {
                        synchronized(requestsLock) {
                            if (inFlight[key]?.task === task) inFlight.remove(key)
                        }
                    }
                }
                SharedLoad(task, 1, generation).also {
                    inFlight[key] = it
                    task.start()
                }
            }
        }
        return try {
            request.task.await()
        } finally {
            synchronized(requestsLock) {
                request.consumers--
                if (request.consumers <= 0 && request.task.isActive) {
                    request.task.cancel()
                    if (inFlight[key] === request) inFlight.remove(key)
                }
            }
        }
    }

    private data class LoadKey(val id: String, val small: Boolean)
    private class SharedLoad(val task: Deferred<ImageBitmap?>, var consumers: Int, val generation: Long)

    private const val LOAD_PARALLELISM = 3
    private const val PREFETCH_WORKERS = 2
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decodeSlots = Semaphore(LOAD_PARALLELISM)
    private val requestsLock = Any()
    private val inFlight = HashMap<LoadKey, SharedLoad>()
    private val prefetchLock = Any()
    private val prefetchJobs = arrayOfNulls<Job>(2)

    /** Предзагрузка ограничена по параллельности; новая видимая область заменяет старую очередь.
     * Повторные запросы одного снимка используют общий decode. Порядок ids задаёт приоритет.
     */
    fun prefetch(c: Context, ids: List<String>, small: Boolean) {
        val ordered = ids.asSequence().filter { it.isNotBlank() }.distinct().toList()
        val slot = if (small) 0 else 1
        if (ordered.isEmpty()) {
            synchronized(prefetchLock) {
                prefetchJobs[slot]?.cancel()
                prefetchJobs[slot] = null
            }
            return
        }
        val app = c.applicationContext
        synchronized(prefetchLock) {
            prefetchJobs[slot]?.cancel()
            prefetchJobs[slot] = prefetchScope.launch {
                val cursor = java.util.concurrent.atomic.AtomicInteger()
                coroutineScope {
                    repeat(minOf(PREFETCH_WORKERS, ordered.size)) {
                        launch {
                            while (isActive) {
                                val index = cursor.getAndIncrement()
                                if (index >= ordered.size) break
                                val id = ordered[index]
                                if (peek(id, small) == null) load(app, id, small)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadFromDisk(c: Context, id: String, small: Boolean, expectedGeneration: Long): ImageBitmap? {
        val h = hash(id)
        val mediumFile = medium(c, h)
        val smallFile = mini(c, h)
        val preferred = if (small && smallFile.isFile) smallFile else mediumFile

        fun decodeValid(file: File): Bitmap? {
            if (!file.isFile) return null
            val sample = when {
                file == smallFile -> 1
                small -> 3
                else -> 1
            }
            val bmp = decode(file, sample)
            if (bmp == null) {
                deleteIfCurrent(file, expectedGeneration)
                return null
            }
            if (isLikelyFailed(bmp)) {
                bmp.recycle()
                deleteIfCurrent(file, expectedGeneration)
                return null
            }
            return bmp
        }

        // If one size is damaged, try the other one instead of deleting both previews.
        val bmp = decodeValid(preferred)
            ?: decodeValid(if (preferred == mediumFile) smallFile else mediumFile)
            ?: return null
        return bmp.asImageBitmap()
    }

    private fun deleteIfCurrent(file: File, expectedGeneration: Long) {
        synchronized(diskLock) {
            if (cacheGeneration == expectedGeneration) file.delete()
        }
    }

    private fun decode(mediumOrMini: File, fallbackSample: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(mediumOrMini.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L
        ) return null
        var sample = fallbackSample.coerceAtLeast(1)
        while (bounds.outWidth / (sample * 2) > 800 || bounds.outHeight / (sample * 2) > 1800) sample *= 2
        return BitmapFactory.decodeFile(mediumOrMini.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    fun clear(c: Context) {
        synchronized(prefetchLock) {
            prefetchJobs.indices.forEach { index ->
                prefetchJobs[index]?.cancel()
                prefetchJobs[index] = null
            }
        }
        val pending = synchronized(requestsLock) {
            val tasks = inFlight.values.map { it.task }
            inFlight.clear()
            synchronized(diskLock) {
                saveRevisions.clear()
                synchronized(cacheLock) {
                    cacheGeneration++
                    smallCache.evictAll()
                    mediumCache.evictAll()
                    capturedAt.clear()
                    dir(c).listFiles()?.forEach { it.delete() }
                }
            }
            tasks
        }
        pending.forEach { it.cancel() }
    }
}
