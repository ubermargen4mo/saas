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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.WebResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val CONTENT_RANGE_START = Regex("""bytes\s+(\d+)-""", RegexOption.IGNORE_CASE)
private val CONTENT_DISPOSITION_FILENAME_EXT = Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE)
private val CONTENT_DISPOSITION_FILENAME = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)

enum class DlStatus { QUEUED, RUNNING, PAUSED, WAITING, DONE, FAILED, CANCELLED }

class DownloadItem(val id: Int, val name: String, val mime: String) {
    var total by mutableLongStateOf(-1L)
    var done by mutableLongStateOf(0L)
    var status by mutableStateOf(DlStatus.QUEUED)
    var error by mutableStateOf<String?>(null)
    var waitReason by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    var uri: Uri? = null
    var speed by mutableLongStateOf(0L)
    var startedAt = System.currentTimeMillis()
    var finishedAt by mutableLongStateOf(0L)

    var url: String? = null
    var referrer: String? = null
    var validator: String? = null
    internal var partial: File? = null
    internal var opener: (() -> InputStream)? = null

    @Volatile var cancelled = false
    @Volatile var paused = false
    @Volatile internal var resumePending = false
    @Volatile internal var disk = 0L
    @Volatile internal var lastReadAt = 0L
    @Volatile internal var failures = 0
    @Volatile internal var lastProgressMark = 0L
    @Volatile internal var waitingForNet = false
    internal val running = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile internal var task: Future<*>? = null
    @Volatile internal var input: InputStream? = null
    var isPrivate = false
    internal var onDone: (() -> Unit)? = null
    internal val doneDelivered = java.util.concurrent.atomic.AtomicBoolean(false)

    val canPause: Boolean get() = url != null && total > 0 && !saving
    val canRetry: Boolean get() = url != null
}

class PendingDownload(
    val body: InputStream,
    val name: String,
    val mime: String,
    val total: Long,
    val host: String?,
    val onDone: (() -> Unit)?,
    val isPrivate: Boolean = false,
    val url: String? = null,
    val validator: String? = null,
    val referrer: String? = null,
)

class Downloads(private val context: Context) {
    val items = mutableStateListOf<DownloadItem>()
    val pending = mutableStateListOf<PendingDownload>()
    val fx = DownloadFx()
    var pageRequested by mutableStateOf(false)

    private val main = MainThread.handler
    private val prefs = context.getSharedPreferences("hrips", Context.MODE_PRIVATE)
    private val nextId = AtomicInteger(1000)
    private val io = ThreadPoolExecutor(
        3, 3,
        0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(24),
        { runnable ->
            Thread(runnable, "hrips-download").apply { isDaemon = true }
        },
    )

    private var privateDownloadsVisible = true
    private var watchdogOn = false
    private var persistQueued = false
    private val writeSeq = AtomicInteger()
    private val stateLock = Any()
    private var writtenSeq = 0

    private val partialDir: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "partial").also { it.mkdirs() }
    }
    private val stateFile: File by lazy { File(context.filesDir, "downloads.json") }

    var requestNotifications: (() -> Unit)? = null

    init {
        val channel = NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        restore()
        watchNetwork()
    }

    fun toast(msg: String) = Notices.show(msg)

    fun isActive(item: DownloadItem): Boolean =
        item.status == DlStatus.QUEUED || item.status == DlStatus.RUNNING || item.status == DlStatus.WAITING

    fun isOngoing(item: DownloadItem): Boolean = isActive(item) || item.status == DlStatus.PAUSED

    fun aggregateProgress(): Float {
        val active = items.filter(::isActive)
        if (active.isEmpty()) return 1f
        if (active.any { it.total <= 0 }) return -1f
        val total = active.sumOf { it.total }
        if (total <= 0L) return -1f
        val done = active.sumOf { it.done }
        return (done.toFloat() / total).coerceIn(0f, 1f)
    }

    fun request(response: WebResponse, isPrivate: Boolean = false, referrer: String? = null, onDone: (() -> Unit)? = null) {
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
            val encoding = headers["content-encoding"]?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
            val encoded = encoding.isNotEmpty() && encoding != "identity"
            val total = if (encoded) -1L else (headers["content-length"]?.toLongOrNull() ?: -1L)
            val etag = headers["etag"]?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
            val validator = etag ?: headers["last-modified"]?.trim()?.takeIf { it.isNotEmpty() }
            val host = plainHost(response.uri)
            val p = PendingDownload(body, name, mime, total, host, onDone, isPrivate, response.uri, validator, referrer)
            main.post {
                if (pending.size >= MAX_PENDING) {
                    runCatching { p.body.close() }
                    toast("Слишком много ожидающих загрузок")
                    p.onDone?.let { it() }
                    return@post
                }
                if (isPrivate) privateDownloadsVisible = true
                pending.add(p)
                if ((context.applicationContext as? HripsApp)?.store?.askBeforeDownload == false) accept(p, name)
            }
        } catch (e: Exception) {
            android.util.Log.w("Downloads", "download failed to start", e)
            toast("Не удалось начать загрузку. Проверьте соединение и повторите")
            onDone?.let { main.post(it) }
        }
    }

    fun accept(p: PendingDownload, name: String) {
        if (!pending.remove(p)) return
        val safeName = sanitizeFilename(name, p.mime)
        val item = DownloadItem(nextId.getAndIncrement(), safeName, p.mime)
        item.onDone = p.onDone
        try {
            item.total = p.total
            item.isPrivate = p.isPrivate
            item.url = p.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            item.referrer = p.referrer
            item.validator = p.validator
            item.partial = newPartial(item)
            items.add(0, item)
            askNotificationPermissionOnce()
            item.input = p.body
            startWorker(item, p.body)
            persist()
        } catch (e: Exception) {
            android.util.Log.w("Downloads", "download failed to start", e)
            toast("Не удалось начать загрузку. Проверьте соединение и повторите")
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

    fun saveStream(name: String, mime: String, isPrivate: Boolean = false, open: () -> InputStream) {
        val safeMime = mime.trim().lowercase(java.util.Locale.ROOT).takeIf { it.contains('/') } ?: "application/octet-stream"
        val safeName = sanitizeFilename(name, safeMime)
        val item = DownloadItem(nextId.getAndIncrement(), safeName, safeMime)
        item.isPrivate = isPrivate
        item.opener = open
        main.post {
            try {
                item.partial = newPartial(item)
                items.add(0, item)
                startWorker(item, null)
            } catch (e: Exception) {
                item.error = e.message ?: e.javaClass.simpleName
                item.status = DlStatus.FAILED
                item.finishedAt = System.currentTimeMillis()
                notifyFinished(item)
            }
        }
    }

    fun decline(p: PendingDownload) {
        if (!pending.remove(p)) return
        runCatching { p.body.close() }
        p.onDone?.let { main.post(it) }
    }

    private fun newPartial(item: DownloadItem): File {
        val f = File(partialDir, "${item.id}.part")
        if (f.exists()) f.delete()
        return f
    }

    fun pause(item: DownloadItem) {
        if (!isActive(item) || !item.canPause) return
        item.paused = true
        item.resumePending = false
        main.removeCallbacksAndMessages(item)
        item.status = DlStatus.PAUSED
        item.speed = 0L
        item.waitReason = null
        closeAsync(item.input)
        persist()
    }

    fun pauseAll() {
        items.filter { isActive(it) && it.canPause }.forEach { pause(it) }
    }

    fun resume(item: DownloadItem) {
        val st = item.status
        if (st != DlStatus.PAUSED && st != DlStatus.WAITING && st != DlStatus.FAILED && st != DlStatus.CANCELLED) return
        if (item.url == null) return
        main.removeCallbacksAndMessages(item)
        val p = item.partial ?: newPartial(item).also { item.partial = it }
        if (st == DlStatus.CANCELLED && !item.running.get()) runCatching { p.delete() }
        item.cancelled = false
        item.paused = false
        item.error = null
        item.waitReason = null
        item.waitingForNet = false
        item.failures = 0
        item.finishedAt = 0L
        item.done = if (p.exists()) p.length() else 0L
        item.disk = item.done
        item.lastProgressMark = item.done
        item.status = DlStatus.QUEUED
        askNotificationPermissionOnce()
        startWorker(item, null)
        persist()
    }

    fun resumeAll() {
        items.filter { it.status == DlStatus.PAUSED || it.status == DlStatus.WAITING }.forEach { resume(it) }
    }

    fun cancel(item: DownloadItem) {
        if (!isOngoing(item)) return
        item.cancelled = true
        item.paused = false
        item.resumePending = false
        main.removeCallbacksAndMessages(item)
        closeAsync(item.input)
        item.task?.cancel(true)
        item.status = DlStatus.CANCELLED
        item.speed = 0L
        item.waitReason = null
        item.finishedAt = System.currentTimeMillis()
        if (!item.running.get()) item.partial?.let { deleteQuiet(it) }
        deliverDone(item)
        io.purge()
        persist()
    }

    internal fun cancelAll() {
        items.filter(::isOngoing).forEach { cancel(it) }
    }

    internal fun pauseFromServiceTimeout() {
        main.post {
            val active = items.filter(::isActive)
            if (active.isEmpty()) return@post
            active.forEach { if (it.canPause) pause(it) else cancel(it) }
            Notices.show("Система остановила загрузки в фоне. Продолжите их в списке загрузок")
        }
    }

    fun remove(item: DownloadItem, deleteFile: Boolean) {
        if (isOngoing(item)) cancel(item)
        item.partial?.let { if (!item.running.get()) deleteQuiet(it) }
        if (deleteFile) item.uri?.let { delete(it) }
        items.remove(item)
        persist()
    }

    fun clearPrivate() {
        privateDownloadsVisible = false
        items.filter { it.isPrivate && !isActive(it) }.forEach { remove(it, false) }
    }

    fun clearFinished() {
        items.filter { !isOngoing(it) }.forEach { remove(it, false) }
    }

    internal fun setAppVisible(visible: Boolean) {
    }

    private enum class Pump { COMPLETE, STOPPED, SHORT }

    private class PermanentFailure(message: String) : IOException(message)

    private class Remote(val stream: InputStream?, val startAt: Long)

    private fun startWorker(item: DownloadItem, first: InputStream?) {
        if (item.cancelled) {
            runCatching { first?.close() }
            return
        }
        if (!item.running.compareAndSet(false, true)) {
            item.resumePending = true
            return
        }
        item.paused = false
        item.resumePending = false
        item.lastReadAt = System.currentTimeMillis()
        try {
            item.task = io.submit { worker(item, first) }
        } catch (e: RejectedExecutionException) {
            item.running.set(false)
            runCatching { first?.close() }
            item.input = null
            if (item.url == null) {
                failItem(item, "Слишком много загрузок сразу. Повторите чуть позже", keepPartial = false)
                return
            }
            item.paused = true
            item.status = DlStatus.PAUSED
            toast("Слишком много загрузок сразу. Продолжите эту чуть позже")
            persist()
            return
        }
        startService()
        ensureWatchdog()
    }

    private fun worker(item: DownloadItem, first: InputStream?) {
        var stream: InputStream? = first
        try {
            if (item.cancelled || item.paused) return
            ui {
                if (!item.cancelled && !item.paused && (item.status == DlStatus.QUEUED || item.status == DlStatus.WAITING)) {
                    item.status = DlStatus.RUNNING
                    item.waitReason = null
                    item.error = null
                }
            }
            val partial = item.partial ?: throw PermanentFailure("Нет места для временного файла")
            var startAt = 0L
            if (stream == null) {
                val opener = item.opener
                if (opener != null) {
                    stream = opener()
                } else {
                    val have = if (partial.exists()) partial.length() else 0L
                    val remote = openRemote(item, have)
                    stream = remote.stream
                    startAt = remote.startAt
                    if (stream == null) {
                        finish(item, partial)
                        return
                    }
                }
            }
            if (item.cancelled || item.paused || item.resumePending) return
            val input = stream ?: throw IOException("Не удалось открыть поток загрузки")
            item.input = input
            item.lastReadAt = System.currentTimeMillis()
            when (pump(item, input, partial, startAt)) {
                Pump.STOPPED -> Unit
                Pump.SHORT -> throw IOException("Соединение закрыто раньше времени")
                Pump.COMPLETE -> finish(item, partial)
            }
        } catch (e: PermanentFailure) {
            if (!item.cancelled && !item.paused) {
                val msg = e.message ?: "Ошибка"
                ui { if (!item.cancelled && !item.paused) failItem(item, msg, keepPartial = item.url != null) }
            }
        } catch (e: Exception) {
            if (!item.cancelled && !item.paused && !item.resumePending) onInterrupted(item, e)
        } finally {
            runCatching { stream?.close() }
            item.input = null
            item.task = null
            if (item.cancelled) item.partial?.let { deleteQuiet(it) }
            item.running.set(false)
            deliverDone(item)
            ui {
                if (item.resumePending) {
                    item.resumePending = false
                    if (!item.cancelled && !item.paused) startWorker(item, null)
                }
            }
        }
    }

    private fun pump(item: DownloadItem, stream: InputStream, partial: File, startAt: Long): Pump {
        return RandomAccessFile(partial, "rw").use { raf ->
            raf.setLength(startAt)
            raf.seek(startAt)
            var written = startAt
            item.disk = written
            ui { if (item.status == DlStatus.RUNNING) item.done = startAt }
            val buf = ByteArray(BUFFER_SIZE)
            var tickT = System.nanoTime()
            var tickB = written
            var speed = 0L
            while (true) {
                if (item.cancelled || item.paused || item.resumePending) {
                    val d = written
                    ui { if (item.status == DlStatus.RUNNING) { item.done = d; item.speed = 0L } }
                    return@use Pump.STOPPED
                }
                val n = stream.read(buf)
                if (n < 0) break
                item.lastReadAt = System.currentTimeMillis()
                raf.write(buf, 0, n)
                written += n
                item.disk = written
                val now = System.nanoTime()
                if (now - tickT >= UI_TICK_NS) {
                    val inst = (written - tickB) * 1_000_000_000.0 / (now - tickT)
                    speed = if (speed == 0L) inst.toLong() else (speed * 0.6 + inst * 0.4).toLong()
                    tickT = now
                    tickB = written
                    val d = written
                    val sp = speed
                    ui { if (item.status == DlStatus.RUNNING) { item.done = d; item.speed = sp } }
                }
            }
            val d = written
            ui { if (item.status == DlStatus.RUNNING) { item.done = d; item.speed = 0L } }
            val t = item.total
            if (t > 0 && written < t) Pump.SHORT else Pump.COMPLETE
        }
    }

    private fun openRemote(item: DownloadItem, have: Long): Remote {
        val url = item.url ?: throw PermanentFailure("Нет адреса, чтобы продолжить загрузку")
        val resp = fetchBlocking(item, url, have) ?: throw IOException("Нет ответа от сервера")
        val code = resp.statusCode
        val h = resp.headers.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }
        when {
            code == 206 -> {
                if (have > 0) {
                    val start = CONTENT_RANGE_START.find(h["content-range"].orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    if (start != have) {
                        runCatching { resp.body?.close() }
                        truncate(item)
                        throw IOException("Сервер вернул не тот участок файла")
                    }
                }
                return Remote(resp.body ?: throw IOException("Пустой ответ сервера"), have)
            }
            code in 200..299 -> {
                val type = h["content-type"]?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
                if (type == "text/html" && !item.mime.startsWith("text/")) {
                    runCatching { resp.body?.close() }
                    throw PermanentFailure("Сервер вместо файла вернул страницу. Скачайте файл заново со страницы")
                }
                if (have > 0) {
                    ui { Notices.show("Сервер не поддерживает докачку: «${item.name}» качается заново") }
                }
                val encoding = h["content-encoding"]?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
                val len = h["content-length"]?.toLongOrNull()
                if (len != null && len > 0 && (encoding.isEmpty() || encoding == "identity")) ui { item.total = len }
                return Remote(resp.body ?: throw IOException("Пустой ответ сервера"), 0L)
            }
            code == 416 -> {
                runCatching { resp.body?.close() }
                if (item.total > 0 && have == item.total) return Remote(null, have)
                truncate(item)
                throw IOException("Продолжить не удалось, файл будет загружен заново")
            }
            code in 400..499 && code != 408 && code != 429 -> {
                runCatching { resp.body?.close() }
                throw PermanentFailure(
                    when (code) {
                        401, 403 -> "Доступ закрыт (код $code). Ссылка могла устареть, скачайте файл заново"
                        404, 410 -> "Файл больше не доступен на сервере (код $code)"
                        else -> "Сервер ответил: $code"
                    },
                )
            }
            else -> {
                runCatching { resp.body?.close() }
                throw IOException("Сервер ответил: $code")
            }
        }
    }

    private fun truncate(item: DownloadItem) {
        item.partial?.let { f -> runCatching { RandomAccessFile(f, "rw").use { it.setLength(0) } } }
    }

    private fun fetchBlocking(item: DownloadItem, url: String, from: Long): WebResponse? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<WebResponse?>(null)
        val abandoned = AtomicBoolean(false)
        val app = context.applicationContext as HripsApp
        main.post {
            try {
                app.browser.fetchRange(
                    url, item.referrer, item.isPrivate,
                    range = if (from > 0) "bytes=$from-" else null,
                    ifRange = if (from > 0) item.validator else null,
                ) { r ->
                    if (abandoned.get()) runCatching { r?.body?.close() }
                    else result.set(r)
                    latch.countDown()
                }
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        if (!latch.await(FETCH_TIMEOUT_S, TimeUnit.SECONDS)) {
            abandoned.set(true)
            result.getAndSet(null)?.let { r -> runCatching { r.body?.close() } }
            return null
        }
        return result.get()
    }

    private fun finish(item: DownloadItem, partial: File) {
        ui { item.saving = true; item.speed = 0L }
        val uri = try {
            saveToDownloads(item, partial)
        } catch (e: Exception) {
            if (item.cancelled) return
            android.util.Log.w("Downloads", "save failed", e)
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            ui { item.saving = false; failItem(item, msg, keepPartial = true) }
            return
        }
        if (item.cancelled) {
            delete(uri)
            return
        }
        val size = item.disk
        deleteQuiet(partial)
        ui {
            if (item.cancelled || !items.contains(item)) {
                delete(uri)
                return@ui
            }
            item.saving = false
            item.uri = uri
            item.done = size
            if (item.total <= 0) item.total = size
            item.status = DlStatus.DONE
            item.speed = 0L
            item.waitReason = null
            item.opener = null
            item.finishedAt = System.currentTimeMillis()
            Notices.show(
                if (item.isPrivate) "Приватная загрузка завершена" else "Загружено: ${item.name}",
                "Открыть",
            ) { open(item) }
            notifyFinished(item)
            if (item.isPrivate && !privateDownloadsVisible) items.remove(item)
            persist()
        }
    }

    private fun saveToDownloads(item: DownloadItem, partial: File): Uri {
        val (out, uri) = openOutput(item)
        try {
            out.use { o ->
                FileInputStream(partial).use { input ->
                    // Копирование ядром без прогона через память приложения: у файла на сотни мегабайт это заметно быстрее.
                    if (o is FileOutputStream) {
                        val src = input.channel
                        val dst = o.channel
                        val size = src.size()
                        var pos = 0L
                        while (pos < size) {
                            if (item.cancelled) throw IOException("Отменено")
                            val n = src.transferTo(pos, minOf(COPY_CHUNK, size - pos), dst)
                            if (n <= 0L) break
                            pos += n
                        }
                        src.position(pos)
                    }
                    // Обычный путь, он же запасной: если быстрый не сработал или не дошёл до конца.
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (item.cancelled) throw IOException("Отменено")
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                }
            }
            markFinished(uri)
            return uri
        } catch (t: Throwable) {
            delete(uri)
            throw t
        }
    }

    private fun onInterrupted(item: DownloadItem, e: Exception) {
        android.util.Log.w("Downloads", "interrupted: ${item.name}", e)
        val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val have = item.partial?.takeIf { it.exists() }?.length() ?: 0L
        val online = isOnline()
        ui {
            if (item.cancelled || item.paused || item.resumePending) return@ui
            item.speed = 0L
            item.done = have
            if (item.url == null) {
                failItem(item, msg, keepPartial = false)
                return@ui
            }
            if (have > item.lastProgressMark) item.failures = 0 else if (online) item.failures++
            item.lastProgressMark = have
            if (item.failures > MAX_FAILURES) {
                failItem(item, msg, keepPartial = true)
                return@ui
            }
            item.status = DlStatus.WAITING
            scheduleRetry(item)
            persist()
        }
    }

    private fun failItem(item: DownloadItem, msg: String, keepPartial: Boolean) {
        item.error = msg
        item.status = DlStatus.FAILED
        item.speed = 0L
        item.saving = false
        item.waitReason = null
        item.finishedAt = System.currentTimeMillis()
        if (!keepPartial && !item.running.get()) item.partial?.let { deleteQuiet(it) }
        notifyFinished(item)
        if (item.isPrivate && !privateDownloadsVisible) items.remove(item)
        persist()
    }

    private fun scheduleRetry(item: DownloadItem) {
        main.removeCallbacksAndMessages(item)
        if (!isOnline()) {
            item.waitingForNet = true
            item.waitReason = "Ожидание сети"
            return
        }
        item.waitingForNet = false
        val delay = (2_000L shl minOf(item.failures, 4)).coerceAtMost(30_000L)
        item.waitReason = "Повторная попытка…"
        main.postAtTime(
            Runnable {
                if (item.status == DlStatus.WAITING && !item.cancelled && !item.paused) startWorker(item, null)
            },
            item,
            SystemClock.uptimeMillis() + delay,
        )
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post { resumeWaitingForNet() }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) main.post { resumeWaitingForNet() }
                }
            })
        } catch (e: Exception) {
            android.util.Log.w("Downloads", "network callback not registered", e)
        }
    }

    private fun resumeWaitingForNet() {
        for (item in items.toList()) {
            if (item.status == DlStatus.WAITING && item.waitingForNet && !item.cancelled && !item.paused) {
                main.removeCallbacksAndMessages(item)
                item.waitingForNet = false
                item.waitReason = "Повторная попытка…"
                startWorker(item, null)
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            var any = false
            for (item in items.toList()) {
                if (item.status != DlStatus.RUNNING || !item.running.get()) continue
                any = true
                val s = item.input ?: continue
                if (item.lastReadAt > 0 && now - item.lastReadAt > STALL_MS) {
                    item.lastReadAt = now
                    closeAsync(s)
                }
            }
            if (any) main.postDelayed(this, 5_000) else watchdogOn = false
        }
    }

    private fun ensureWatchdog() {
        if (watchdogOn) return
        watchdogOn = true
        main.postDelayed(watchdog, 5_000)
    }

    private fun persist() {
        if (persistQueued) return
        persistQueued = true
        main.postDelayed({
            persistQueued = false
            writeState()
        }, 800)
    }

    private fun writeState() {
        val text = try {
            val arr = JSONArray()
            for (it in items.toList()) {
                if (it.isPrivate || it.status == DlStatus.CANCELLED) continue
                if (arr.length() >= MAX_SAVED) break
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("name", it.name)
                        .put("mime", it.mime)
                        .put("status", it.status.name)
                        .put("total", it.total)
                        .put("done", it.done)
                        .put("url", it.url ?: "")
                        .put("referrer", it.referrer ?: "")
                        .put("validator", it.validator ?: "")
                        .put("uri", it.uri?.toString() ?: "")
                        .put("error", it.error ?: "")
                        .put("startedAt", it.startedAt)
                        .put("finishedAt", it.finishedAt),
                )
            }
            arr.toString()
        } catch (e: Exception) {
            android.util.Log.w("Downloads", "state not saved", e)
            return
        }
        val seq = writeSeq.incrementAndGet()
        if (!AppExecutors.tryExecute { writeStateFile(text, seq) }) writeStateFile(text, seq)
    }

    private fun writeStateFile(text: String, seq: Int) {
        synchronized(stateLock) {
            if (seq < writtenSeq) return
            try {
                val tmp = File(stateFile.parentFile, "downloads.json.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(stateFile)) {
                    stateFile.delete()
                    tmp.renameTo(stateFile)
                }
                writtenSeq = seq
            } catch (e: Exception) {
                android.util.Log.w("Downloads", "state not saved", e)
            }
        }
    }

    private fun restore() {
        val keep = HashSet<String>()
        var maxId = nextId.get() - 1
        try {
            if (stateFile.exists()) {
                val arr = JSONArray(stateFile.readText())
                for (i in 0 until arr.length()) {
                    try {
                        val o = arr.getJSONObject(i)
                        val id = o.getInt("id")
                        val item = DownloadItem(id, o.getString("name"), o.getString("mime"))
                        item.total = o.optLong("total", -1L)
                        item.url = o.optString("url").ifEmpty { null }
                        item.referrer = o.optString("referrer").ifEmpty { null }
                        item.validator = o.optString("validator").ifEmpty { null }
                        item.uri = o.optString("uri").ifEmpty { null }?.let { Uri.parse(it) }
                        item.error = o.optString("error").ifEmpty { null }
                        item.startedAt = o.optLong("startedAt", System.currentTimeMillis())
                        item.finishedAt = o.optLong("finishedAt", 0L)
                        val part = File(partialDir, "$id.part")
                        when (DlStatus.valueOf(o.getString("status"))) {
                            DlStatus.DONE -> {
                                item.status = DlStatus.DONE
                                item.done = o.optLong("done", 0L)
                            }
                            DlStatus.FAILED -> {
                                item.status = DlStatus.FAILED
                                if (item.url != null) {
                                    item.partial = part
                                    item.done = if (part.exists()) part.length() else 0L
                                    keep.add(part.name)
                                }
                            }
                            DlStatus.PAUSED, DlStatus.QUEUED, DlStatus.RUNNING, DlStatus.WAITING -> {
                                if (item.url == null) continue
                                val wasPaused = o.getString("status") == DlStatus.PAUSED.name
                                item.partial = part
                                item.done = if (part.exists()) part.length() else 0L
                                item.disk = item.done
                                item.lastProgressMark = item.done
                                keep.add(part.name)
                                if (wasPaused) {
                                    item.paused = true
                                    item.status = DlStatus.PAUSED
                                } else {
                                    item.status = DlStatus.WAITING
                                    item.waitReason = "Продолжится автоматически"
                                }
                            }
                            DlStatus.CANCELLED -> continue
                        }
                        if (id > maxId) maxId = id
                        items.add(item)
                    } catch (e: Exception) {
                        android.util.Log.w("Downloads", "skipped broken saved item #$i", e)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("Downloads", "state not restored", e)
        }
        nextId.set(maxId + 1)
        runCatching { partialDir.listFiles()?.forEach { if (it.name !in keep) it.delete() } }
        main.post { items.filter { it.status == DlStatus.WAITING }.forEach { scheduleRetry(it) } }
    }

    fun openPageIntent(): android.app.PendingIntent = android.app.PendingIntent.getActivity(
        context, REQUEST_OPEN_PAGE,
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_PAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

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
        } catch (e: Exception) {
            // На Android 12+ из фона это может быть запрещено: без записи загрузка тихо осталась бы без уведомления.
            AppLog.w("Downloads", "не удалось запустить службу загрузок", e)
        }
    }

    private fun ui(block: () -> Unit) = MainThread.run(block)

    private fun closeAsync(stream: InputStream?) {
        if (stream == null) return
        Thread({ runCatching { stream.close() } }, "hrips-download-close").apply { isDaemon = true }.start()
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
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(if (ok) "Загрузка завершена" else "Ошибка загрузки")
            .setContentIntent(openPageIntent())
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(item.id, n)
    }

    /** Файл в папке, выбранной в настройках (дерево SAF). Нет папки или она недоступна: null, сохраняем в системную. */
    private fun openInChosenFolder(item: DownloadItem): Pair<OutputStream, Uri>? {
        val tree = prefs.getString("dl_tree", null)?.let { Uri.parse(it) } ?: return null
        return try {
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val doc = android.provider.DocumentsContract.createDocument(
                context.contentResolver, parent, item.mime.ifBlank { "application/octet-stream" }, item.name,
            ) ?: return null
            val out = context.contentResolver.openOutputStream(doc)
            if (out == null) {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, doc) }
                null
            } else out to doc
        } catch (e: Exception) {
            android.util.Log.w("Downloads", "chosen folder unavailable", e)
            null
        }
    }

    private fun openOutput(item: DownloadItem): Pair<OutputStream, Uri> {
        openInChosenFolder(item)?.let { return it }
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
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val base = item.name.substringBeforeLast('.')
        val ext = item.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var f = File(dir, item.name)
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return FileOutputStream(f) to Uri.fromFile(f)
    }

    private fun markFinished(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
            val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            val updated = context.contentResolver.update(uri, v, null, null)
            if (updated != 1) error("Не удалось завершить файл в Загрузках")
        }
    }

    private fun delete(uri: Uri) {
        runCatching {
            if (uri.scheme == "content" && uri.authority != MediaStore.AUTHORITY) {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else if (uri.scheme == "content") context.contentResolver.delete(uri, null, null)
            else uri.path?.let { File(it).delete() }
        }
    }

    private fun deleteQuiet(f: File) {
        runCatching { f.delete() }
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
            try {
                context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: ActivityNotFoundException) {
                toast("Файл сохранён в папке «Загрузки»")
            }
        }
    }

    private fun fileName(disposition: String?, uri: String, mime: String?): String {
        val fromHeader = disposition?.let { d ->
            CONTENT_DISPOSITION_FILENAME_EXT.find(d)
                ?.let { runCatching { Uri.decode(it.groupValues[1].trim()) }.getOrNull() }
                ?: CONTENT_DISPOSITION_FILENAME.find(d)?.groupValues?.get(1)?.trim()
        }
        val raw = listOf(fromHeader, Uri.parse(uri).lastPathSegment).firstOrNull { !it.isNullOrBlank() } ?: "download"
        return sanitizeFilename(raw, mime)
    }

    private fun sanitizeFilename(raw: String, mime: String?): String {
        val ext = if (raw.contains('.') || mime.isNullOrBlank()) null
        else MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return sanitizeFileName(raw, ext)
    }

    companion object {
        const val CHANNEL = "downloads"
        const val ACTION_OPEN_PAGE = "com.hrips.browser.OPEN_DOWNLOADS"
        private const val REQUEST_OPEN_PAGE = 7
        private const val BUFFER_SIZE = 256 * 1024
        private const val COPY_CHUNK = 8L * 1024 * 1024
        private const val UI_TICK_NS = 250_000_000L
        private const val PENDING_EXPIRY_SECONDS = 24L * 60L * 60L
        private const val MAX_PENDING = 16
        private const val MAX_SAVED = 200
        private const val MAX_FAILURES = 8
        private const val STALL_MS = 30_000L
        private const val FETCH_TIMEOUT_S = 45L
    }
}
