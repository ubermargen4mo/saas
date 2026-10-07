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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

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

    /** Пустой запрос даёт самые популярные расширения для Android. */
    suspend fun search(query: String): Result<List<AmoAddon>> = withContext(Dispatchers.IO) {
        runCatching {
            val b = Uri.parse(SEARCH).buildUpon()
                .appendQueryParameter("app", "android")
                .appendQueryParameter("type", "extension")
                .appendQueryParameter("page_size", "30")
                .appendQueryParameter("lang", Locale.getDefault().toLanguageTag())
            if (query.isBlank()) b.appendQueryParameter("sort", "users") else b.appendQueryParameter("q", query)
            val json = JSONObject(get(b.build().toString()))
            val arr = json.optJSONArray("results") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it)) }
        }
    }

    private fun parse(o: JSONObject): AmoAddon? {
        val guid = o.optString("guid").takeIf { it.isNotBlank() } ?: return null
        val file = o.optJSONObject("current_version")?.optJSONObject("file")
        val ratings = o.optJSONObject("ratings")
        return AmoAddon(
            guid = guid,
            name = loc(o, "name").ifBlank { guid },
            summary = loc(o, "summary"),
            iconUrl = o.optString("icon_url").takeIf { it.isNotBlank() },
            xpiUrl = file?.optString("url")?.takeIf { it.isNotBlank() },
            users = o.optLong("average_daily_users"),
            rating = ratings?.optDouble("average") ?: 0.0,
            ratingCount = ratings?.optInt("count") ?: 0,
            author = o.optJSONArray("authors")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() },
        )
    }

    /** Поле бывает строкой (когда указан язык) или словарём язык -> текст. */
    private fun loc(o: JSONObject, key: String): String = when (val v = o.opt(key)) {
        is String -> v
        is JSONObject -> v.keys().asSequence().firstOrNull()?.let { v.optString(it) } ?: ""
        else -> ""
    }

    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "HripsBrowser")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Иконки из каталога: скачиваются один раз и держатся в памяти, на диск не пишутся. */
object RemoteImages {
    private val cache = LruCache<String, Bitmap>(80)

    suspend fun load(url: String): Bitmap? = cache.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                c.disconnect()
            }
        }.getOrNull()?.also { cache.put(url, it) }
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
