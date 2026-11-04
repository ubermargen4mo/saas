package com.hrips.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class Wallpaper(private val context: Context) {
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    var pick: (() -> Unit)? = null

    private val main = MainThread.handler
    private val file get() = File(context.filesDir, "wallpaper.jpg")
    private val generation = AtomicLong(0L)
    private val fileLock = Any()

    init {
        val token = generation.get()
        AppExecutors.tryExecute {
            val bmp = synchronized(fileLock) { if (file.exists()) BitmapFactory.decodeFile(file.path) else null }
            if (bmp != null && generation.get() == token) main.post {
                if (generation.get() == token) image = bmp.asImageBitmap()
            }
        }
    }

    fun set(uri: Uri) {
        val token = generation.incrementAndGet()
        if (!AppExecutors.tryExecute {
            val bmp = try { decode(uri) } catch (e: Exception) { null }
            if (bmp == null) {
                main.post { if (generation.get() == token) Notices.show("Не удалось открыть изображение") }
            } else {
                synchronized(fileLock) {
                    runCatching {
                        val tmp = File(context.filesDir, "wallpaper.jpg.tmp")
                        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        if (!tmp.renameTo(file)) tmp.delete()
                    }
                }
                main.post { if (generation.get() == token) image = bmp.asImageBitmap() }
            }
        }) {
            Notices.show("Приложение занято. Повторите через пару секунд")
        }
    }

    fun clear() {
        generation.incrementAndGet()
        image = null
        AppExecutors.tryExecute { synchronized(fileLock) { file.delete(); File(context.filesDir, "wallpaper.jpg.tmp").delete() } }
    }

    private fun decode(uri: Uri): Bitmap {
        val max = 2048
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
}
