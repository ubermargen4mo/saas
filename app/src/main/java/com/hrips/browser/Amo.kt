package com.hrips.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Расширение из каталога Mozilla Add-ons (addons.mozilla.org). */
data class AmoAddon(
    val guid: String,
    val name: String,
    val summary: String,
    val iconUrl: String?,
    val xpiUrl: String?,
    val users: Long,
    val rating: Double,
    val ratingCount: Int,
    val author: String?,
)

/**
 * Клиент публичного API каталога Mozilla (v5). Каталог отдаёт только подписанные Mozilla расширения,
 * установку и проверку подписи делает движок.
 */
object Amo {
    private const val SEARCH = "https://addons.mozilla.org/api/v5/addons/search/"
    private const val MAX_RESPONSE_BYTES = 2_000_000

    /** Пустой запрос даёт самые популярные расширения для Android. */
    suspend fun search(query: String): Result<List<AmoAddon>> = withContext(Dispatchers.IO) {
        try {
            val b = Uri.parse(SEARCH).buildUpon()
                .appendQueryParameter("app", "android")
                .appendQueryParameter("type", "extension")
                .appendQueryParameter("page_size", "30")
                // Только язык ("ru"): полный тег вроде "ru-RU" или "und" каталог принимает не всегда
                .appendQueryParameter("lang", Locale.getDefault().language.ifBlank { "en" })
            if (query.isBlank()) b.appendQueryParameter("sort", "users") else b.appendQueryParameter("q", query)
            val json = JSONObject(get(b.build().toString()))
            val arr = json.optJSONArray("results")
            // Запрос уже не нужен (человек продолжил печатать): не разбираем ответ
            coroutineContext.ensureActive()
            Result.success(if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it)) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Строка из JSON без ловушки Android: у org.json optString() для JSON-null возвращает текст "null",
     * а не пустую строку, и проверка на пустоту его пропускает (имя «null», иконка «null»).
     */
    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }

    private fun parse(o: JSONObject): AmoAddon? {
        val guid = o.str("guid") ?: return null
        val file = o.optJSONObject("current_version")?.optJSONObject("file")
        val ratings = o.optJSONObject("ratings")
        return AmoAddon(
            guid = guid,
            name = loc(o, "name").ifBlank { guid },
            summary = loc(o, "summary"),
            iconUrl = o.str("icon_url"),
            xpiUrl = file?.str("url"),
            users = o.optLong("average_daily_users"),
            // У новых расширений average бывает null: optDouble тогда даёт NaN
            rating = ratings?.optDouble("average", 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
            ratingCount = ratings?.optInt("count") ?: 0,
            author = o.optJSONArray("authors")?.optJSONObject(0)?.str("name"),
        )
    }

    /**
     * Поле бывает строкой (когда указан язык) или словарём язык -> текст.
     * Из словаря берём язык системы, потом английский, и только потом любой.
     */
    private fun loc(o: JSONObject, key: String): String {
        val v: Any? = o.opt(key)
        if (v is String) return v.takeIf { !it.equals("null", true) } ?: ""
        if (v !is JSONObject) return ""
        val lang = Locale.getDefault().toLanguageTag()
        val order = sequenceOf(lang, Locale.getDefault().language, "en-US", "en") + v.keys().asSequence()
        return order.mapNotNull { v.str(it) }.firstOrNull() ?: ""
    }

    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.useCaches = false
            c.setRequestProperty("User-Agent", "HripsBrowser")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBounded(MAX_RESPONSE_BYTES).toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}

/** Иконки из каталога: скачиваются один раз и держатся в памяти, на диск не пишутся. */
object RemoteImages {
    private const val MEMORY_CACHE_KB = 32 * 1024
    private const val MAX_BYTES = 2_000_000
    private const val MAX_PIXELS = 16_000_000L
    private const val MAX_SIDE = 512

    /** Адрес, который не загрузился, не пробуем снова столько времени (иначе без сети каждая прокрутка списка = новые запросы). */
    private const val RETRY_AFTER_MS = 60_000L

    private val cache = object : LruCache<String, Bitmap>(MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.allocationByteCount / 1024)
    }
    private val failedAt = HashMap<String, Long>()

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        synchronized(failedAt) {
            val t = failedAt[url]
            if (t != null && System.currentTimeMillis() - t < RETRY_AFTER_MS) return null
        }
        val bmp = withContext(Dispatchers.IO) {
            try {
                download(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        if (bmp != null) cache.put(url, bmp) else synchronized(failedAt) {
            if (failedAt.size > 200) failedAt.clear()
            failedAt[url] = System.currentTimeMillis()
        }
        return bmp
    }

    private fun download(url: String): Bitmap? {
        val parsed = Uri.parse(url)
        if (!parsed.scheme.equals("https", true) || parsed.host.isNullOrBlank()) return null
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = false
            c.useCaches = false
            if (c.responseCode !in 200..299) return null
            val data = c.inputStream.use { it.readBounded(MAX_BYTES) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_PIXELS
            ) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) > MAX_SIDE || bounds.outHeight / (sample * 2) > MAX_SIDE) sample *= 2
            return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            c.disconnect()
        }
    }
}

@Composable
fun RemoteIcon(url: String?, size: Dp, fallback: @Composable () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, url) {
        value = url?.let { RemoteImages.load(it)?.asImageBitmap() }
    }
    val b = bmp
    if (b != null) {
        Image(b, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)), contentScale = ContentScale.Crop)
    } else {
        fallback()
    }
}
