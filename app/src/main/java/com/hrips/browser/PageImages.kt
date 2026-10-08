package com.hrips.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest
import android.util.LruCache

/**
 * Картинка страницы для карточки вкладки, когда снимка экрана нет (вкладка ещё не показывалась после перезапуска).
 * Схема из Opera: фоном запрашиваем начало страницы, читаем <meta> и берём картинку в порядке
 * og:image:secure_url, og:image:url, og:image, twitter:image, thumbnail.
 *
 * - Не больше двух запросов одновременно, таймаут 5 секунд (в [Favicons.download]).
 * - Найденная картинка хранится 30 дней, «картинки нет» помнится сутки, сетевая ошибка - 5 минут (и только в памяти).
 * - Приватные вкладки сюда не попадают: для них ничего не запрашивается и не сохраняется.
 * - Выключается в Настройки -> Конфиденциальность: тогда лишних запросов к сайтам нет совсем.
 */
object PageImages {
    /** Включено ли (значение из Store; обновляется при изменении настройки). */
    var enabled by mutableStateOf(true)

    private const val MAX_FILES = 100
    private const val FOUND_TTL = 30L * 24 * 3600 * 1000
    private const val MISSING_TTL = 24L * 3600 * 1000
    private const val ERROR_TTL = 5L * 60 * 1000
    private const val OUT_WIDTH = 480

    private val gate = Semaphore(2)
    private val errors = LruCache<String, Long>(256)
    private val errorsLock = Any()
    private const val MEMORY_CACHE_KB = 24 * 1024
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            maxOf(1, value.asAndroidBitmap().allocationByteCount / 1024)
    }

    private fun dir(c: Context) = File(c.cacheDir, "pageimages").apply { mkdirs() }

    private fun hash(url: String) =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Можно ли вообще запрашивать эту страницу: только http(s), без логина в адресе и не выдача поиска (запрос не повторяем). */
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
        val bmp = withContext(Dispatchers.IO) {
            fromDisk(app, pageUrl) ?: if (isMissing(app, pageUrl)) null else fromNetwork(app, pageUrl)
        }
        val img = bmp?.asImageBitmap()
        if (img != null) synchronized(memory) { memory[pageUrl] = img }
        return img
    }

    private fun fromDisk(c: Context, url: String): Bitmap? {
        val f = File(dir(c), "${hash(url)}.jpg")
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > FOUND_TTL) { f.delete(); return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L
        ) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) > OUT_WIDTH || bounds.outHeight / (sample * 2) > 960) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Есть свежая отметка «у этой страницы нет картинки»: сеть не трогаем. */
    private fun isMissing(c: Context, url: String): Boolean {
        val f = File(dir(c), "${hash(url)}.none")
        if (!f.exists()) return false
        if (System.currentTimeMillis() - f.lastModified() > MISSING_TTL) { f.delete(); return false }
        return true
    }

    private suspend fun fromNetwork(c: Context, url: String): Bitmap? {
        val failedAt = synchronized(errorsLock) { errors.get(url) }
        if (failedAt != null && System.currentTimeMillis() - failedAt < ERROR_TTL) return null
        return gate.withPermit {
            val html = Favicons.download(url, 131072)
            if (html == null) {
                synchronized(errorsLock) { errors.put(url, System.currentTimeMillis()) }
                return@withPermit null
            }
            val imageUrl = findImageUrl(String(html, Charsets.UTF_8), url)
            if (imageUrl == null) {
                runCatching { File(dir(c), "${hash(url)}.none").writeText(url) }
                prune(c)
                return@withPermit null
            }
            val data = Favicons.download(imageUrl, 2_000_000)
            val bmp = data?.let { decodeFlat(it, OUT_WIDTH) }
            if (bmp == null) {
                synchronized(errorsLock) { errors.put(url, System.currentTimeMillis()) }
                return@withPermit null
            }
            runCatching {
                val tmp = File(dir(c), "${hash(url)}.tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                if (!tmp.renameTo(File(dir(c), "${hash(url)}.jpg"))) tmp.delete()
            }
            prune(c)
            bmp
        }
    }

    private val metaTag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attr = Regex("([a-zA-Z:_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val headEnd = Regex("</head>", RegexOption.IGNORE_CASE)
    private val order = listOf("og:image:secure_url", "og:image:url", "og:image", "twitter:image", "thumbnail")

    /** Адрес картинки из <meta> в начале страницы или null. Относительные адреса достраиваются от адреса страницы. */
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

    /** Декодирует с понижением размера и кладёт на белый фон: в JPEG нет прозрачности. */
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
        val flat = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        if (scaled !== src) src.recycle()
        if (scaled !== flat) scaled.recycle()
        return flat
    }

    private fun prune(c: Context) {
        val files = dir(c).listFiles { f -> f.name.endsWith(".jpg") || f.name.endsWith(".none") } ?: return
        if (files.size > MAX_FILES) files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    fun clear(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
        synchronized(memory) { memory.clear() }
        synchronized(errorsLock) { errors.evictAll() }
    }
}
