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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class DlStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

class DownloadItem(val id: Int, val name: String, val mime: String) {
    var total by mutableLongStateOf(-1L)
    var done by mutableLongStateOf(0L)
    var status by mutableStateOf(DlStatus.QUEUED)
    /** Причина, если загрузка не удалась (показывается в списке загрузок) */
    var error by mutableStateOf<String?>(null)
    var uri: Uri? = null
    /** Текущая скорость, байт/с (сглаженная) */
    var speed by mutableLongStateOf(0L)
    val startedAt = System.currentTimeMillis()
    var finishedAt by mutableLongStateOf(0L)
    @Volatile var cancelled = false
    @Volatile internal var task: Future<*>? = null
    @Volatile internal var input: InputStream? = null
    /** Загрузка из приватной вкладки: пропадает из списка, когда закрыта последняя приватная вкладка */
    var isPrivate = false
    internal var onDone: (() -> Unit)? = null
    internal val doneDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
}

/** Ответ сервера, который ждёт решения пользователя ("Загрузить" / "Отмена"). Поток данных пока не читается. */
class PendingDownload(
    val body: InputStream,
    val name: String,
    val mime: String,
    val total: Long,
    val host: String?,
    val onDone: (() -> Unit)?,
    val isPrivate: Boolean = false,
)

/**
 * Загрузки. GeckoView отдаёт ответ, который нельзя показать как страницу (onExternalResponse),
 * мы читаем его поток и пишем файл в папку "Загрузки" через MediaStore.
 * Пока есть активные загрузки, после пользовательского запуска может работать [DownloadService]
 * (foreground-сервис с уведомлением), поэтому система не убивает процесс, когда браузер свёрнут.
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
    /**
     * Bounded pool: максимум 3 одновременных копирования и до 24 задач в очереди. Остальные
     * отклоняются контролируемо, вместо создания бесконечного количества очередных задач.
     */
    private val io = ThreadPoolExecutor(
        3, 3,
        0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(24),
        { runnable ->
            Thread(runnable, "hrips-download").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        },
    )

    private var privateDownloadsVisible = true

    /** Устанавливается из MainActivity: просит у системы разрешение на уведомления (Android 13+). */
    var requestNotifications: (() -> Unit)? = null

    init {
        val channel = NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun toast(msg: String) = Notices.show(msg)

    /** Шаг 1: страница отдала файл. Ничего не качаем, только спрашиваем (имя, размер). */
    fun request(response: WebResponse, isPrivate: Boolean = false, onDone: (() -> Unit)? = null) {
        try {
            val body = response.body
            if (body == null) {
                toast("Не удалось начать загрузку: нет данных")
                onDone?.let { main.post(it) }
                return
            }
            val headers = response.headers.mapKeys { it.key.lowercase() }
            val headerMime = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
            val name = fileName(headers["content-disposition"], response.uri, headerMime)
            val mime = if (headerMime.isNullOrBlank() || headerMime.equals("application/octet-stream", ignoreCase = true)) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
            } else headerMime
            val total = headers["content-length"]?.toLongOrNull() ?: -1L
            val host = Uri.parse(response.uri).host?.removePrefix("www.")
            val p = PendingDownload(body, name, mime, total, host, onDone, isPrivate)
            main.post {
                if (pending.size >= MAX_PENDING) {
                    runCatching { p.body.close() }
                    toast("Слишком много ожидающих загрузок")
                    p.onDone?.let { it() }
                    return@post
                }
                if (isPrivate) privateDownloadsVisible = true
                pending.add(p)
                // Подтверждение выключено в настройках: качаем сразу
                if ((context.applicationContext as? HripsApp)?.store?.askBeforeDownload == false) accept(p, name)
            }
        } catch (e: Throwable) {
            // Любая ошибка здесь раньше молча терялась ("ничего не происходит"). Теперь видно причину.
            run {
                android.util.Log.w("Downloads", "download failed to start", e)
                toast("Не удалось начать загрузку. Проверьте соединение и повторите")
            }
            onDone?.let { main.post(it) }
        }
    }

    /** Шаг 2а: пользователь нажал "Загрузить" (имя могло быть изменено). */
    fun accept(p: PendingDownload, name: String) {
        if (!pending.remove(p)) return
        val safeName = sanitizeFilename(name, p.mime)
        val item = DownloadItem(nextId.getAndIncrement(), safeName, p.mime)
        item.onDone = p.onDone
        try {
            item.total = p.total
            item.isPrivate = p.isPrivate
            items.add(0, item)
            askNotificationPermissionOnce()
            item.input = p.body
            item.task = io.submit {
                main.post { if (!item.cancelled && item.status == DlStatus.QUEUED) item.status = DlStatus.RUNNING }
                try {
                    if (!item.cancelled) copy(item, p.body)
                } finally {
                    item.input = null
                    deliverDone(item)
                    item.task = null
                }
            }
            // Сервис стартует после непосредственного действия пользователя, пока Activity ещё foreground.
            // Это безопаснее для Android 12+ и даёт FGS пережить сворачивание приложения.
            startService()
        } catch (e: Throwable) {
            run {
                android.util.Log.w("Downloads", "download failed to start", e)
                toast("Не удалось начать загрузку. Проверьте соединение и повторите")
            }
            runCatching { p.body.close() }
            item.task = null
            item.input = null
            item.status = DlStatus.FAILED
            item.error = e.message ?: e.javaClass.simpleName
            item.finishedAt = System.currentTimeMillis()
            notifyFinished(item)
            deliverDone(item)
        }
    }

    /**
     * Сохраняет то, что браузер сделал сам (PDF страницы, снимок экрана), как обычную загрузку:
     * та же запись в списке, тот же файл в «Загрузках». [open] вызывается уже в фоновом потоке.
     */
    fun saveStream(name: String, mime: String, isPrivate: Boolean = false, open: () -> InputStream) {
        val safeMime = mime.trim().lowercase(java.util.Locale.ROOT).takeIf { it.contains('/') } ?: "application/octet-stream"
        val safeName = sanitizeFilename(name, safeMime)
        val item = DownloadItem(nextId.getAndIncrement(), safeName, safeMime)
        item.isPrivate = isPrivate
        main.post {
            items.add(0, item)
            try {
                item.task = io.submit {
                    main.post { if (!item.cancelled && item.status == DlStatus.QUEUED) item.status = DlStatus.RUNNING }
                    try {
                        if (item.cancelled) return@submit
                        val stream = open()
                        if (item.cancelled) {
                            runCatching { stream.close() }
                            return@submit
                        }
                        item.input = stream
                        copy(item, stream)
                    } catch (e: Throwable) {
                        main.post {
                            if (item.cancelled) {
                                item.status = DlStatus.CANCELLED
                            } else {
                                item.error = e.message ?: e.javaClass.simpleName
                                item.status = DlStatus.FAILED
                            }
                            item.finishedAt = System.currentTimeMillis()
                            notifyFinished(item)
                        }
                    } finally {
                        item.input = null
                        item.task = null
                    }
                }
                startService()
            } catch (e: Throwable) {
                item.error = e.message ?: e.javaClass.simpleName
                item.status = DlStatus.FAILED
                item.finishedAt = System.currentTimeMillis()
                notifyFinished(item)
            }
        }
    }

    /** Шаг 2б: "Отмена". Поток закрываем, сервер перестаёт слать данные. */
    fun decline(p: PendingDownload) {
        if (!pending.remove(p)) return
        runCatching { p.body.close() }
        p.onDone?.let { main.post(it) }
    }

    /** Активна ли загрузка (реально читается или уже стоит в очереди). */
    fun isActive(item: DownloadItem): Boolean = item.status == DlStatus.QUEUED || item.status == DlStatus.RUNNING

    /** Общий прогресс всех активных загрузок: queued учитываются как 0 байт. */
    fun aggregateProgress(): Float {
        val active = items.filter(::isActive)
        if (active.isEmpty()) return 1f
        if (active.any { it.total <= 0 }) return -1f
        val total = active.sumOf { it.total }
        if (total <= 0L) return -1f
        val done = active.sumOf { it.done }
        return (done.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Activity visibility does not stop the download FGS: the same service must survive backgrounding. */
    internal fun setAppVisible(visible: Boolean) {
        // Deliberately no-op. A download that starts while the Activity is visible may be moved to
        // the background milliseconds later; stopping the FGS here would make the next background
        // start subject to Android's background-start restrictions. The service stops itself when idle.
    }

    /** Один раз просит разрешение на уведомления (Android 13+). Нужно и загрузкам, и медиа-уведомлению. */
    fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("notif_asked", false)) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        requestNotifications?.let { launch -> main.post { launch() } }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        } catch (_: Exception) {
            // Например, Android 12+ не даёт запускать foreground-сервис из фона.
            // Загрузка всё равно идёт, просто без уведомления.
        }
    }

    private fun copy(item: DownloadItem, body: InputStream) {
        var uri: Uri? = null
        try {
            val (out, u) = openOutput(item)
            uri = u
            val completed = BufferedOutputStream(out, BUFFER_SIZE).use { o ->
                BufferedInputStream(body, BUFFER_SIZE).use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var ok = true
                    var written = 0L
                    var uiLastT = System.nanoTime()
                    var uiLastB = 0L
                    while (true) {
                        if (item.cancelled) { ok = false; break }
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        written += n
                        val now = System.nanoTime()
                        if (now - uiLastT >= 200_000_000L) {
                            val snapshotDone = written
                            val inst = (snapshotDone - uiLastB) * 1_000_000_000.0 / (now - uiLastT)
                            val nextSpeed = if (item.speed == 0L) inst.toLong() else (item.speed * 0.6 + inst * 0.4).toLong()
                            uiLastT = now
                            uiLastB = snapshotDone
                            main.post {
                                if (isActive(item)) {
                                    item.done = snapshotDone
                                    item.speed = nextSpeed
                                }
                            }
                        }
                    }
                    main.post { item.done = written }
                    ok
                }
            }
            if (completed && !item.cancelled) {
                markFinished(u)
                main.post {
                    if (item.cancelled || !items.contains(item)) return@post
                    item.uri = u
                    item.status = DlStatus.DONE
                    item.speed = 0L
                    item.finishedAt = System.currentTimeMillis()
                    Notices.show(
                        if (item.isPrivate) "Приватная загрузка завершена" else "Загружено: ${item.name}",
                        "Открыть",
                    ) { open(item) }
                    notifyFinished(item)
                    if (item.isPrivate && !privateDownloadsVisible) items.remove(item)
                }
            } else {
                delete(u)
                main.post {
                    item.status = DlStatus.CANCELLED
                    item.speed = 0L
                    item.finishedAt = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            uri?.let { delete(it) }
            main.post {
                if (items.contains(item)) {
                    if (item.cancelled) {
                        item.status = DlStatus.CANCELLED
                    } else {
                        item.error = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                        item.status = DlStatus.FAILED
                    }
                    item.speed = 0L
                    item.finishedAt = System.currentTimeMillis()
                    notifyFinished(item)
                    if (item.isPrivate && !privateDownloadsVisible) items.remove(item)
                }
            }
        }
    }

    private fun deliverDone(item: DownloadItem) {
        val callback = item.onDone ?: return
        if (item.doneDelivered.compareAndSet(false, true)) main.post(callback)
    }

    private fun notifyFinished(item: DownloadItem) {
        if (item.status == DlStatus.CANCELLED) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val ok = item.status == DlStatus.DONE
        val title = if (item.isPrivate) "Приватная загрузка" else item.name
        val tap = android.app.PendingIntent.getActivity(
            context, item.id, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
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
                put(MediaStore.Downloads.DATE_EXPIRES, (System.currentTimeMillis() / 1000L) + PENDING_EXPIRY_SECONDS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Система не создала файл в Загрузках")
            return try {
                val out = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Не удалось открыть файл для записи")
                out to uri
            } catch (t: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw t
            }
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
            val updated = context.contentResolver.update(uri, v, null, null)
            if (updated != 1) error("Не удалось завершить файл в Загрузках")
        }
    }

    private fun delete(uri: Uri) {
        runCatching {
            if (uri.scheme == "content") context.contentResolver.delete(uri, null, null)
            else uri.path?.let { File(it).delete() }
        }
    }

    fun cancel(item: DownloadItem) {
        if (!isActive(item)) return
        item.cancelled = true
        runCatching { item.input?.close() }
        item.task?.cancel(true)
        main.post {
            if (item.status == DlStatus.QUEUED) {
                item.status = DlStatus.CANCELLED
                item.finishedAt = System.currentTimeMillis()
                item.speed = 0L
                deliverDone(item)
            }
        }
        io.purge()
    }

    /** Cancels every queued/running download, used by the notification's global Cancel action. */
    internal fun cancelAll() {
        items.filter(::isActive).forEach { cancel(it) }
    }

    /**
     * Called when Android's dataSync foreground-service budget expires. The service is only the
     * process-keepalive/notification layer; the actual copy workers must be told to stop too.
     */
    internal fun cancelRunningFromServiceTimeout() {
        main.post {
            items.filter(::isActive).forEach { item ->
                item.cancelled = true
                runCatching { item.input?.close() }
                val wasQueued = item.status == DlStatus.QUEUED
                item.task?.cancel(true)
                item.status = DlStatus.CANCELLED
                item.finishedAt = System.currentTimeMillis()
                item.speed = 0L
                if (wasQueued) deliverDone(item)
            }
        }
        io.purge()
    }

    /** Убирает запись из списка; [deleteFile] удаляет и сам файл. Активную загрузку сначала отменяет. */
    fun remove(item: DownloadItem, deleteFile: Boolean) {
        val wasQueued = item.status == DlStatus.QUEUED
        if (isActive(item)) {
            item.cancelled = true
            runCatching { item.input?.close() }
            item.task?.cancel(true)
            io.purge()
        }
        if (deleteFile) item.uri?.let { delete(it) }
        items.remove(item)
        if (wasQueued) deliverDone(item)
    }

    private fun shareUri(uri: Uri): Uri = if (uri.scheme == "file") {
        androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".files", File(requireNotNull(uri.path))
        )
    } else uri

    fun share(item: DownloadItem) {
        val uri = item.uri ?: return
        val safeUri = runCatching { shareUri(uri) }.getOrElse {
            toast("Не удалось подготовить файл для передачи")
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mime)
            .putExtra(Intent.EXTRA_STREAM, safeUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast("Нет приложения, чтобы поделиться файлом")
        }
    }

    /** Закрылась последняя приватная вкладка: убираем завершённые приватные загрузки из списка (файлы остаются). */
    fun clearPrivate() {
        privateDownloadsVisible = false
        items.removeAll { it.isPrivate && !isActive(it) }
    }

    fun clearFinished() {
        items.removeAll { !isActive(it) }
    }

    fun open(item: DownloadItem) {
        val uri = item.uri ?: return
        val safeUri = if (uri.scheme == "file") {
            runCatching { shareUri(uri) }.getOrElse {
                toast("Файл сохранён, но его не удалось открыть")
                return
            }
        } else uri
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(safeUri, item.mime)
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
                ?.let { runCatching { Uri.decode(it.groupValues[1].trim()) }.getOrNull() }
                ?: Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(d)
                    ?.groupValues?.get(1)?.trim()
        }
        val raw = listOf(fromHeader, Uri.parse(uri).lastPathSegment).firstOrNull { !it.isNullOrBlank() } ?: "download"
        return sanitizeFilename(raw, mime)
    }

    private fun sanitizeFilename(raw: String, mime: String?): String {
        var name = raw
            .replace(Regex("[\\/:*?\"<>|]"), "_")
            .replace(Regex("[\u0000-\u001F\u007F]"), "_")
            .trim()
            .take(150)
        if (name.isBlank() || name == "." || name == "..") name = "download"
        if (!name.contains('.') && !mime.isNullOrBlank()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
        }
        return name
    }

    companion object {
        const val CHANNEL = "downloads"
        private const val BUFFER_SIZE = 128 * 1024
        private const val PENDING_EXPIRY_SECONDS = 24L * 60L * 60L
        private const val MAX_PENDING = 16
    }
}
