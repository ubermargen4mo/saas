package com.hrips.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.system.Os
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URL
import java.security.MessageDigest

object PageImages {
    var enabled by mutableStateOf(true)

    private const val MAX_FILES = 100
    private const val FOUND_TTL = 30L * 24 * 3600 * 1000
    private const val MISSING_TTL = 24L * 3600 * 1000
    private const val ERROR_TTL = 5L * 60 * 1000
    private const val OUT_WIDTH = 480
    private const val MEMORY_CACHE_KB = 24 * 1024

    private val gate = Semaphore(2)
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class SharedRequest(val task: Deferred<ImageBitmap?>, var consumers: Int, val generation: Long)
    private val requestLock = Any()
    private val inFlight = HashMap<String, SharedRequest>()
    private var generation = 0L // Под requestLock. Делает устаревшую работу недействительной при очистке кэша.
    private val errors = LruCache<String, Long>(256)
    private val errorsLock = Any()
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            maxOf(1, value.asAndroidBitmap().allocationByteCount / 1024)
    }

    private fun dir(c: Context) = File(c.cacheDir, "pageimages").apply { mkdirs() }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun cacheFile(c: Context, url: String, ext: String) = File(dir(c), "${hash(url)}.$ext")

    fun eligible(url: String): Boolean {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = u.host ?: return false
        if (u.userInfo != null) return false
        val searchHost = runCatching { Uri.parse(SearchEngines.current.template).host }.getOrNull()
        if (searchHost != null && host.equals(searchHost, ignoreCase = true) && !u.query.isNullOrEmpty()) return false
        return true
    }

    suspend fun load(context: Context, pageUrl: String): ImageBitmap? {
        if (!enabled || !eligible(pageUrl)) return null
        synchronized(memory) { memory[pageUrl] }?.let { return it }
        val app = context.applicationContext
        val request = synchronized(requestLock) {
            val requestGeneration = generation
            inFlight[pageUrl]?.takeIf { it.generation == requestGeneration && it.task.isActive }
                ?.also { it.consumers++ } ?: run {
                lateinit var task: Deferred<ImageBitmap?>
                task = requestScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val bmp = fromDisk(app, pageUrl, requestGeneration)
                            ?: (if (isMissing(app, pageUrl, requestGeneration)) null else fromNetwork(app, pageUrl, requestGeneration))
                            ?: return@async null
                        val image = bmp.asImageBitmap()
                        val accepted = synchronized(requestLock) {
                            if (requestGeneration != generation) false
                            else {
                                synchronized(memory) { memory.put(pageUrl, image) }
                                true
                            }
                        }
                        if (accepted) image else {
                            if (!bmp.isRecycled) bmp.recycle()
                            null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } finally {
                        synchronized(requestLock) {
                            if (inFlight[pageUrl]?.task === task) inFlight.remove(pageUrl)
                        }
                    }
                }
                SharedRequest(task, 1, requestGeneration).also {
                    inFlight[pageUrl] = it
                    task.start()
                }
            }
        }
        return try {
            request.task.await()
        } finally {
            synchronized(requestLock) {
                request.consumers--
                if (request.consumers <= 0 && request.task.isActive) {
                    request.task.cancel()
                    if (inFlight[pageUrl] === request) inFlight.remove(pageUrl)
                }
            }
        }
    }

    private fun fromDisk(c: Context, url: String, expectedGeneration: Long): Bitmap? {
        val f = cacheFile(c, url, "jpg")
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > FOUND_TTL) { deleteIfCurrent(f, expectedGeneration); return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L
        ) {
            deleteIfCurrent(f, expectedGeneration)
            return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) > OUT_WIDTH || bounds.outHeight / (sample * 2) > 960) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: run { deleteIfCurrent(f, expectedGeneration); null }
    }

    private fun deleteIfCurrent(file: File, expectedGeneration: Long) {
        synchronized(requestLock) { if (generation == expectedGeneration) file.delete() }
    }

    private fun isMissing(c: Context, url: String, expectedGeneration: Long): Boolean {
        val f = cacheFile(c, url, "none")
        if (!f.exists()) return false
        if (System.currentTimeMillis() - f.lastModified() > MISSING_TTL) { deleteIfCurrent(f, expectedGeneration); return false }
        return true
    }

    private suspend fun fromNetwork(c: Context, url: String, expectedGeneration: Long): Bitmap? {
        val failedAt = synchronized(errorsLock) { errors.get(url) }
        if (failedAt != null && System.currentTimeMillis() - failedAt < ERROR_TTL) return null
        return gate.withPermit {
            val html = Favicons.download(url, 131072)
            if (html == null) {
                recordError(url, expectedGeneration)
                return@withPermit null
            }
            val imageUrl = findImageUrl(String(html, Charsets.UTF_8), url)
            if (imageUrl == null) {
                synchronized(requestLock) {
                    if (generation == expectedGeneration) runCatching { cacheFile(c, url, "none").writeText(url) }
                }
                prune(c, expectedGeneration)
                return@withPermit null
            }
            val data = Favicons.download(imageUrl, 2_000_000)
            val bmp = data?.let { decodeFlat(it, OUT_WIDTH) }
            if (bmp == null) {
                recordError(url, expectedGeneration)
                return@withPermit null
            }
            val tmp = runCatching { File.createTempFile("${hash(url)}.", ".tmp", dir(c)) }.getOrNull()
            if (tmp == null) {
                bmp.recycle()
                return@withPermit null
            }
            val committed = try {
                val encoded = tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                encoded && synchronized(requestLock) {
                    if (generation != expectedGeneration) false
                    else {
                        val moved = runCatching {
                            Os.rename(tmp.absolutePath, cacheFile(c, url, "jpg").absolutePath)
                            true
                        }.getOrDefault(false)
                        if (moved) cacheFile(c, url, "none").delete()
                        moved
                    }
                }
            } catch (_: Exception) {
                false
            } finally {
                tmp.delete()
            }
            if (!committed) {
                bmp.recycle()
                return@withPermit null
            }
            prune(c, expectedGeneration)
            bmp
        }
    }

    private fun recordError(url: String, expectedGeneration: Long) {
        synchronized(requestLock) {
            if (generation == expectedGeneration) {
                synchronized(errorsLock) { errors.put(url, System.currentTimeMillis()) }
            }
        }
    }

    private val metaTag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attr = Regex("([a-zA-Z:_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val headEnd = Regex("</head>", RegexOption.IGNORE_CASE)
    private val order = listOf("og:image:secure_url", "og:image:url", "og:image", "twitter:image", "thumbnail")

    internal fun findImageUrl(html: String, pageUrl: String): String? {
        val head = headEnd.find(html)?.let { html.substring(0, it.range.first) } ?: html
        val metas = HashMap<String, String>()
        metaTag.findAll(head).forEach { m ->
            var prop: String? = null
            var name: String? = null
            var content: String? = null
            attr.findAll(m.value).forEach { a ->
                val v = a.groupValues[2].ifEmpty { a.groupValues[3] }
                when (a.groupValues[1].lowercase()) {
                    "property" -> prop = v
                    "name" -> name = v
                    "content" -> content = v
                }
            }
            val key = (prop ?: name)?.trim()?.lowercase() ?: return@forEach
            val value = content?.trim()
            if (!value.isNullOrEmpty()) metas.putIfAbsent(key, value)
        }
        val pageScheme = runCatching { URL(pageUrl).protocol.lowercase() }.getOrNull()
        for (key in order) {
            val raw = metas[key] ?: continue
            val abs = runCatching { URL(URL(pageUrl), raw.replace("&amp;", "&")) }.getOrNull() ?: continue
            if (!abs.protocol.equals("http", true) && !abs.protocol.equals("https", true)) continue
            if (pageScheme == "https" && !abs.protocol.equals("https", true)) continue
            return abs.toString()
        }
        return null
    }

    private fun decodeFlat(data: ByteArray, width: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxHeight = 960
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width || bounds.outHeight / (sample * 2) > maxHeight) sample *= 2
        val src = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = minOf(1f, width / src.width.toFloat(), maxHeight / src.height.toFloat())
        val scaled = if (scale < 0.999f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
        } else src
        val flat = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.RGB_565)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        if (scaled !== src) src.recycle()
        if (scaled !== flat) scaled.recycle()
        return flat
    }

    private fun prune(c: Context, expectedGeneration: Long) {
        synchronized(requestLock) {
            if (generation != expectedGeneration) return
            val files = dir(c).listFiles { f -> f.name.endsWith(".jpg") || f.name.endsWith(".none") } ?: return
            if (files.size > MAX_FILES) files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
        }
    }

    fun clear(c: Context) {
        val pending = synchronized(requestLock) {
            generation++
            val tasks = inFlight.values.map { it.task }
            inFlight.clear()
            dir(c.applicationContext).listFiles()?.forEach { it.delete() }
            synchronized(memory) { memory.evictAll() }
            synchronized(errorsLock) { errors.evictAll() }
            tasks
        }
        pending.forEach { it.cancel() }
    }
}
