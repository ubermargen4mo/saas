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

/**
 * Превью страниц для карточек вкладок на диске (кэш приложения), чтобы после перезапуска карточки не были пустыми.
 * Ключ - адрес страницы. Приватные вкладки сюда не попадают. Чистится вместе с историей и кэшем (Настройки -> Конфиденциальность).
 */
object TabThumbs {
    private const val MAX_FILES = 80

    private fun dir(c: Context) = File(c.cacheDir, "tabthumbs").apply { mkdirs() }

    private fun file(c: Context, url: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(c), "$h.jpg")
    }

    /** Вызывать не из главного потока. */
    fun save(c: Context, url: String, bmp: Bitmap) {
        try {
            file(c, url).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            val all = dir(c).listFiles() ?: return
            if (all.size > MAX_FILES) all.sortedBy { it.lastModified() }.take(all.size - MAX_FILES).forEach { it.delete() }
        } catch (e: Exception) {
            // нет места или файл занят: карточка просто останется со значком
        }
    }

    suspend fun load(c: Context, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = file(c, url)
        if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
    }

    fun clear(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }
}
