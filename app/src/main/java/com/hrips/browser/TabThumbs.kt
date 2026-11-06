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

object TabThumbs {
    private const val MAX_PAGES = 80
    private const val MINI_WIDTH = 440

    private fun dir(c: Context) = File(c.noBackupFilesDir, "tab_previews_v3").apply { mkdirs() }

    private fun medium(c: Context, h: String) = File(dir(c), "$h.jpg")
    private fun mini(c: Context, h: String) = File(dir(c), "$h.s.jpg")

    fun save(c: Context, id: String, bmp: Bitmap) {
        val ticket = issueSaveTicket(id)
        save(c, id, bmp, ticket)
    }

    private data class SaveTicket(val generation: Long, val revision: Long)
    private val saveRevisions = HashMap<String, SaveTicket>()
    private val saveSequence = java.util.concurrent.atomic.AtomicLong()

    private inline fun issueSaveTicket(id: String, whileLocked: () -> Unit = {}): SaveTicket = synchronized(diskLock) {
        SaveTicket(cacheGeneration, saveSequence.incrementAndGet()).also {
            saveRevisions[id] = it
            whileLocked()
        }
    }

    private fun releaseTicket(id: String, ticket: SaveTicket) {
        synchronized(diskLock) {
            if (saveRevisions[id] === ticket) saveRevisions.remove(id)
        }
    }

    private fun isCurrentSave(id: String, ticket: SaveTicket) =
        cacheGeneration == ticket.generation && saveRevisions[id] === ticket

    private fun save(c: Context, id: String, bmp: Bitmap, ticket: SaveTicket) {
        if (!synchronized(diskLock) { isCurrentSave(id, ticket) }) return
        val h = sha1Hex(id)
        val targetDir = dir(c)
        val tmpMedium = runCatching { File.createTempFile("$h.", ".medium.tmp", targetDir) }.getOrNull()
        if (tmpMedium == null) {
            releaseTicket(id, ticket)
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
                        smallBitmap = null
                    }
                }
                mediumSaved to smallSaved
            }
            val (mediumSaved, smallSaved) = saved
            if (mediumSaved || smallSaved) trim(c, ticket.generation)
        } catch (e: Exception) {
            AppLog.w("TabThumbs", "не удалось сохранить превью вкладки", e)
        } finally {
            tmpMedium.delete()
            tmpSmall?.delete()
            if (smallBitmap != null && smallBitmap !== bmp && !smallBitmap.isRecycled) smallBitmap.recycle()
            releaseTicket(id, ticket)
        }
    }

    private const val MAX_PENDING_SAVES = 2
    private val pendingSaves = java.util.concurrent.atomic.AtomicInteger()
    private val capturedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun saveAsync(c: Context, id: String, bmp: Bitmap) {
        if (pendingSaves.incrementAndGet() > MAX_PENDING_SAVES) { pendingSaves.decrementAndGet(); return }
        val ticket = issueSaveTicket(id) {
            synchronized(cacheLock) {
                mediumCache.put(id, bmp.asImageBitmap())
                capturedAt[id] = android.os.SystemClock.elapsedRealtime()
            }
        }
        val app = c.applicationContext
        val ran = AppExecutors.tryExecute {
            try { save(app, id, bmp, ticket) } finally { pendingSaves.decrementAndGet() }
        }
        if (!ran) {
            pendingSaves.decrementAndGet()
            releaseTicket(id, ticket)
        }
    }

    fun capturedAgoMs(id: String): Long? = capturedAt[id]?.let { android.os.SystemClock.elapsedRealtime() - it }

    fun isFresh(id: String, withinMs: Long = 2500L): Boolean {
        val t = capturedAt[id] ?: return false
        return android.os.SystemClock.elapsedRealtime() - t <= withinMs
    }

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

    fun prune(c: Context, validIds: Set<String>) {
        val app = c.applicationContext
        AppExecutors.tryExecute {
            val keep = validIds.mapTo(HashSet()) { sha1Hex(it) }
            synchronized(diskLock) {
                dir(app).listFiles()?.forEach { f ->
                    val h = f.name.substringBefore('.')
                    if (h !in keep && System.currentTimeMillis() - f.lastModified() > 60_000L) f.delete()
                }
            }
            File(app.cacheDir, "tabthumbs").takeIf { it.isDirectory }?.let { old -> old.listFiles()?.forEach { it.delete() }; old.delete() }
        }
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int, tabs: Iterable<Tab> = emptyList()) {
        synchronized(cacheLock) {
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mediumCache.evictAll()
            // С API 34 система не присылает TRIM_MEMORY_COMPLETE: в фоне мини-превью освобождаются уже на TRIM_MEMORY_BACKGROUND,
            // иначе процесс держит до 28 МБ картинок и раньше попадает под low memory killer.
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) smallCache.evictAll()
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

    private val heapLimit = Runtime.getRuntime().maxMemory()
    private val smallCache = object : android.util.LruCache<String, ImageBitmap>(cacheBytes(28L * 1024 * 1024, heapLimit / 8)) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }
    private val mediumCache = object : android.util.LruCache<String, ImageBitmap>(cacheBytes(14L * 1024 * 1024, heapLimit / 16)) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }
    private fun cacheBytes(cap: Long, share: Long): Int = minOf(cap, share).coerceAtLeast(4L * 1024 * 1024).toInt()
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
        val h = sha1Hex(id)
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
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, 800, 1800, start = fallbackSample.coerceAtLeast(1))
        return BitmapFactory.decodeFile(mediumOrMini.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    /** Сколько превью вкладок лежит на диске: байты и число файлов (без временных). */
    fun diskUsage(c: Context): Pair<Long, Int> {
        val files = dir(c.applicationContext).listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return 0L to 0
        return files.sumOf { it.length() } to files.size
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
