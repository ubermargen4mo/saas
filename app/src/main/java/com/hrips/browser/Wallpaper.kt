package com.hrips.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/** Обои главной страницы. Выбранная картинка уменьшается и копируется в файлы приложения. */
class Wallpaper(private val context: Context) {
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Устанавливается из MainActivity: открывает системный выбор фото. */
    var pick: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val file get() = File(context.filesDir, "wallpaper.jpg")

    init {
        Thread {
            val bmp = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            if (bmp != null) main.post { image = bmp.asImageBitmap() }
        }.start()
    }

    fun set(uri: Uri) {
        Thread {
            val bmp = try { decode(uri) } catch (e: Exception) { null }
            if (bmp == null) {
                main.post { Toast.makeText(context, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show() }
            } else {
                runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                main.post { image = bmp.asImageBitmap() }
            }
        }.start()
    }

    fun clear() {
        file.delete()
        image = null
    }

    private fun decode(uri: Uri): Bitmap {
        val max = 2048
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longest = maxOf(w, h)
                if (longest > max) {
                    val k = max.toFloat() / longest
                    decoder.setTargetSize((w * k).toInt(), (h * k).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > max) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("no stream")
        return stream.use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IllegalStateException("decode failed")
    }
}
