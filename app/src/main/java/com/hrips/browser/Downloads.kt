package com.hrips.browser

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

enum class DlStatus { RUNNING, DONE, FAILED, CANCELLED }

class DownloadItem(val id: Int, val name: String, val mime: String) {
    var total by mutableLongStateOf(-1L)
    var done by mutableLongStateOf(0L)
    var status by mutableStateOf(DlStatus.RUNNING)
    /** Причина, если загрузка не удалась (показывается в списке загрузок) */
    var error by mutableStateOf<String?>(null)
    var uri: Uri? = null
    /** Текущая скорость, байт/с (сглаженная) */
    var speed by mutableLongStateOf(0L)
    val startedAt = System.currentTimeMillis()
    var finishedAt by mutableLongStateOf(0L)
    @Volatile var cancelled = false
}

/** Ответ сервера, который ждёт решения пользователя ("Загрузить" / "Отмена"). Поток данных пока не читается. */
class PendingDownload(
    val body: InputStream,
    val name: String,
    val mime: String,
    val total: Long,
    val host: String?,
    val onDone: (() -> Unit)?,
)

/**
 * Загрузки. GeckoView отдаёт ответ, который нельзя показать как страницу (onExternalResponse),
 * мы читаем его поток и пишем файл в папку "Загрузки" через MediaStore.
 * Пока идёт хоть одна загрузка, работает [DownloadService] (foreground-сервис с уведомлением),
 * поэтому система не убивает процесс, когда браузер свёрнут.
 */
class Downloads(private val context: Context) {
    val items = mutableStateListOf<DownloadItem>()
    /** Очередь запросов "скачать файл?". Показывается первый. */
    val pending = mutableStateListOf<PendingDownload>()
    /** Состояние анимации "иконка летит к кнопке загрузок" */
    val fx = DownloadFx()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("hrips", Context.MODE_PRIVATE)
    private val nextId = AtomicInteger(1000)

    /** Устанавливается из MainActivity: просит у системы разрешение на уведомления (Android 13+). */
    var requestNotifications: (() -> Unit)? = null

    init {
        val channel = NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun toast(msg: String) = main.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }

    /** Шаг 1: страница отдала файл. Ничего не качаем, только спрашиваем (имя, размер). */
    fun request(response: WebResponse, onDone: (() -> Unit)? = null) {
        try {
            val body = response.body
            if (body == null) {
                toast("Не удалось начать загрузку: нет данных")
                onDone?.let { main.post(it) }
                return
            }
            val headers = response.headers.mapKeys { it.key.lowercase() }
            val headerMime = headers["content-type"]?.substringBefore(';')?.trim()
            val name = fileName(headers["content-disposition"], response.uri, headerMime)
            val mime = if (headerMime.isNullOrBlank() || headerMime == "application/octet-stream") {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
            } else headerMime
            val total = headers["content-length"]?.toLongOrNull() ?: -1L
            val host = Uri.parse(response.uri).host?.removePrefix("www.")
            pending.add(PendingDownload(body, name, mime, total, host, onDone))
        } catch (e: Throwable) {
            // Любая ошибка здесь раньше молча терялась ("ничего не происходит"). Теперь видно причину.
            toast("Загрузка не началась: ${e.message ?: e.javaClass.simpleName}")
            onDone?.let { main.post(it) }
        }
    }

    /** Шаг 2а: пользователь нажал "Загрузить" (имя могло быть изменено). */
    fun accept(p: PendingDownload, name: String) {
        pending.remove(p)
        try {
            val item = DownloadItem(nextId.getAndIncrement(), name, p.mime)
            item.total = p.total
            items.add(0, item)
            askNotificationPermissionOnce()
            startService()
            Thread { copy(item, p.body); p.onDone?.let { main.post(it) } }.start()
        } catch (e: Throwable) {
            toast("Загрузка не началась: ${e.message ?: e.javaClass.simpleName}")
            runCatching { p.body.close() }
            p.onDone?.let { main.post(it) }
        }
    }

    /** Шаг 2б: "Отмена". Поток закрываем, сервер перестаёт слать данные. */
    fun decline(p: PendingDownload) {
        pending.remove(p)
        runCatching { p.body.close() }
        p.onDone?.let { main.post(it) }
    }

    /** Общий прогресс всех активных загрузок: 0..1, -1 если размер хотя бы одной неизвестен, 1 если активных нет. */
    fun aggregateProgress(): Float {
        val running = items.filter { it.status == DlStatus.RUNNING }
        if (running.isEmpty()) return 1f
        if (running.any { it.total <= 0 }) return -1f
        val total = running.sumOf { it.total }
        val done = running.sumOf { it.done }
        return (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("notif_asked", false)) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        requestNotifications?.invoke()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        } catch (e: Exception) {
            // Например, Android 12+ не даёт запускать foreground-сервис из фона.
            // Загрузка всё равно идёт, просто без уведомления.
        }
    }

    private fun copy(item: DownloadItem, body: InputStream) {
        var uri: Uri? = null
        try {
            val (out, u) = openOutput(item)
            uri = u
            val completed = out.use { o ->
                body.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var ok = true
                    var lastT = System.nanoTime()
                    var lastB = 0L
                    while (true) {
                        if (item.cancelled) { ok = false; break }
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        item.done += n
                        val now = System.nanoTime()
                        if (now - lastT >= 500_000_000L) {
                            val inst = (item.done - lastB) * 1_000_000_000.0 / (now - lastT)
                            item.speed = if (item.speed == 0L) inst.toLong() else (item.speed * 0.6 + inst * 0.4).toLong()
                            lastT = now
                            lastB = item.done
                        }
                    }
                    ok
                }
            }
            if (completed) {
                markFinished(u)
                item.uri = u
                item.status = DlStatus.DONE
                toast("Загружено: ${item.name}")
            } else {
                delete(u)
                item.status = DlStatus.CANCELLED
            }
        } catch (e: Exception) {
            uri?.let { delete(it) }
            if (item.cancelled) {
                item.status = DlStatus.CANCELLED
            } else {
                item.error = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                item.status = DlStatus.FAILED
            }
        }
        item.speed = 0L
        item.finishedAt = System.currentTimeMillis()
        notifyFinished(item)
    }

    private fun notifyFinished(item: DownloadItem) {
        if (item.status == DlStatus.CANCELLED) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val ok = item.status == DlStatus.DONE
        val tap = android.app.PendingIntent.getActivity(
            context, item.id, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(item.name)
            .setContentText(if (ok) "Загрузка завершена" else "Ошибка загрузки")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(item.id, n)
    }

    private fun openOutput(item: DownloadItem): Pair<OutputStream, Uri> {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.name)
                put(MediaStore.Downloads.MIME_TYPE, item.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Система не создала файл в Загрузках")
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Не удалось открыть файл для записи")
            return out to uri
        }
        // Android 8-9: сохраняем в папку приложения (без запроса прав)
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val base = item.name.substringBeforeLast('.')
        val ext = item.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var f = File(dir, item.name)
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return FileOutputStream(f) to Uri.fromFile(f)
    }

    private fun markFinished(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") {
            val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(uri, v, null, null)
        }
    }

    private fun delete(uri: Uri) {
        runCatching {
            if (uri.scheme == "content") context.contentResolver.delete(uri, null, null)
            else uri.path?.let { File(it).delete() }
        }
    }

    fun cancel(item: DownloadItem) {
        item.cancelled = true
    }

    /** Убирает запись из списка; [deleteFile] удаляет и сам файл. Активную загрузку сначала отменяет. */
    fun remove(item: DownloadItem, deleteFile: Boolean) {
        if (item.status == DlStatus.RUNNING) item.cancelled = true
        if (deleteFile) item.uri?.let { delete(it) }
        items.remove(item)
    }

    fun share(item: DownloadItem) {
        val uri = item.uri ?: return
        if (uri.scheme != "content") {
            toast("Поделиться можно только файлами из «Загрузок»")
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast("Нет приложения, чтобы поделиться файлом")
        }
    }

    fun clearFinished() {
        items.removeAll { it.status != DlStatus.RUNNING }
    }

    fun open(item: DownloadItem) {
        val uri = item.uri ?: return
        if (uri.scheme == "file") {
            toast("Сохранено: ${uri.path}")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Нет приложения для этого типа файла (zip, например): показываем системный список загрузок
            try {
                context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: ActivityNotFoundException) {
                toast("Файл сохранён в папке «Загрузки»")
            }
        }
    }

    private fun fileName(disposition: String?, uri: String, mime: String?): String {
        val fromHeader = disposition?.let { d ->
            Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(d)
                ?.let { Uri.decode(it.groupValues[1].trim()) }
                ?: Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(d)
                    ?.groupValues?.get(1)?.trim()
        }
        val raw = listOf(fromHeader, Uri.parse(uri).lastPathSegment).firstOrNull { !it.isNullOrBlank() } ?: "download"
        var name = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(150)
        if (!name.contains('.') && !mime.isNullOrBlank()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
        }
        return name
    }

    companion object {
        const val CHANNEL = "downloads"
    }
}
