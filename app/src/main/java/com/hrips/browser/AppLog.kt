package com.hrips.browser

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Журнал для диагностики без компьютера (Настройки -> О программе -> Диагностика).
 *
 * Пока выключен: строки уходят только в logcat, файл не пишется, фоновых потоков нет.
 * Когда включён:
 *  - события пишутся в `Android/data/<пакет>/files/logs/hrips-log.txt` (при ротации старый файл становится `.1.txt`);
 *  - необработанное исключение дописывается в файл до падения процесса;
 *  - сторожевой поток раз в секунду проверяет главный поток; если он не отвечает дольше 3 с, в журнал
 *    попадает стек главного потока (где именно он завис) и хвост logcat (там же логи самого Gecko);
 *  - раз в 10 с пишется снимок памяти.
 * Адреса из приватных вкладок не записываются, у остальных отрезаются параметры запроса.
 */
object AppLog {
    private const val TAG = "AppLog"
    private const val MAX_BYTES = 3L * 1024L * 1024L
    private const val HANG_MS = 3_000L
    private const val MEM_EVERY_MS = 10_000L
    private const val QUEUE_LIMIT = 4_000

    @Volatile
    var enabled = false
        private set

    /** Строка с текущей конфигурацией (какие защиты включены): пишется при каждом включении журнала. */
    @Volatile
    var configProvider: (() -> String)? = null

    private var appContext: Context? = null
    private val main = MainThread.handler
    private val startedAt = SystemClock.elapsedRealtime()

    private val queue = LinkedBlockingQueue<String>()
    private var writer: Thread? = null
    private var watchdog: Thread? = null
    private val running = AtomicBoolean(false)
    private val ioLock = Any()
    private var out: FileOutputStream? = null
    private var written = 0L

    @Volatile
    private var lastTick = SystemClock.elapsedRealtime()

    // DateTimeFormatter потокобезопасен и создаётся один раз (SimpleDateFormat создавался на каждую строку).
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun stamp(): String = LocalDateTime.now().format(timeFormat)

    fun dir(context: Context): File =
        (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")).apply { mkdirs() }

    fun file(context: Context) = File(dir(context), "hrips-log.txt")

    private fun oldFile(context: Context) = File(dir(context), "hrips-log.1.txt")

    /** Вызывать один раз из Application.onCreate. */
    fun init(context: Context, on: Boolean) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (enabled) {
                writeNow("F", "Crash", "Необработанное исключение в потоке ${thread.name}", error)
            }
            previous?.uncaughtException(thread, error)
        }
        setEnabled(on)
    }

    fun setEnabled(on: Boolean) {
        val ctx = appContext ?: return
        if (on == enabled) return
        if (on) {
            enabled = true
            startWriter(ctx)
            i("AppLog", "Журнал включён. ${deviceInfo(ctx)}")
            configProvider?.let { provider ->
                runCatching { i("Config", provider()) }.onFailure { w("Config", "не удалось получить конфигурацию", it) }
            }
            startWatchdog(ctx)
        } else {
            writeNow("I", "AppLog", "Журнал выключен", null)
            enabled = false
            running.set(false)
            watchdog?.interrupt()
            watchdog = null
            writer?.interrupt()
            writer = null
        }
    }

    // ---- запись событий ---------------------------------------------------------------------

    fun i(tag: String, msg: String) = log("I", tag, msg, null)

    /**
     * Ленивая версия для горячих мест: пока журнал выключен (по умолчанию), текст сообщения не собирается.
     * Побочный эффект: у такой строки нет дубля в logcat, когда журнал выключен.
     */
    inline fun i(tag: String, msg: () -> String) {
        if (enabled) i(tag, msg())
    }

    fun w(tag: String, msg: String, error: Throwable? = null) = log("W", tag, msg, error)

    fun e(tag: String, msg: String, error: Throwable? = null) = log("E", tag, msg, error)

    private fun log(level: String, tag: String, msg: String, error: Throwable?) {
        when (level) {
            "E" -> Log.e(tag, msg, error)
            "W" -> Log.w(tag, msg, error)
            else -> Log.i(tag, msg)
        }
        if (!enabled) return
        if (queue.size > QUEUE_LIMIT) return
        queue.offer(format(level, tag, msg, error))
    }

    private fun format(level: String, tag: String, msg: String, error: Throwable?): String {
        val sb = StringBuilder(stamp()).append(" [").append(Thread.currentThread().name).append("] ")
            .append(level).append('/').append(tag).append(": ").append(msg)
        if (error != null) sb.append('\n').append(Log.getStackTraceString(error).trimEnd())
        return sb.append('\n').toString()
    }

    /** Синхронная запись (перед падением процесса очередь уже не успеет разобраться). */
    private fun writeNow(level: String, tag: String, msg: String, error: Throwable?) {
        val ctx = appContext ?: return
        try {
            FileOutputStream(file(ctx), true).use { it.write(format(level, tag, msg, error).toByteArray()) }
        } catch (_: Exception) {
        }
    }

    private fun startWriter(ctx: Context) {
        if (!running.compareAndSet(false, true)) return
        writer = Thread({
            try {
                openOut(ctx)
                while (running.get()) {
                    val line = queue.take()
                    safeAppend(ctx, line)
                    // Пачкой: забираем всё, что накопилось.
                    while (true) {
                        safeAppend(ctx, queue.poll() ?: break)
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.w(TAG, "Запись журнала остановлена", e)
            } finally {
                runCatching { out?.close() }
                out = null
            }
        }, "hrips-log").apply { isDaemon = true; start() }
    }

    private fun openOut(ctx: Context) = synchronized(ioLock) {
        val f = file(ctx)
        written = f.length()
        out = FileOutputStream(f, true)
    }

    private fun safeAppend(ctx: Context, line: String) {
        try {
            append(ctx, line)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось записать строку журнала", e)
        }
    }

    private fun append(ctx: Context, line: String) = synchronized(ioLock) {
        val bytes = line.toByteArray()
        if (out == null) out = FileOutputStream(file(ctx), true)
        if (written + bytes.size > MAX_BYTES) rotate(ctx)
        val stream = out ?: return@synchronized
        stream.write(bytes)
        written += bytes.size
    }

    private fun rotate(ctx: Context) {
        runCatching { out?.close() }
        val f = file(ctx)
        val old = oldFile(ctx)
        old.delete()
        f.renameTo(old)
        written = 0L
        out = FileOutputStream(f, true)
    }

    // ---- сторож главного потока и память ------------------------------------------------------

    private fun startWatchdog(ctx: Context) {
        lastTick = SystemClock.elapsedRealtime()
        val ticker = Runnable { lastTick = SystemClock.elapsedRealtime() }
        watchdog = Thread({
            var hangStart = 0L
            var lastStack = 0L
            var lastMem = 0L
            try {
                while (running.get()) {
                    main.post(ticker)
                    Thread.sleep(1_000L)
                    val now = SystemClock.elapsedRealtime()
                    val silent = now - lastTick
                    if (silent > HANG_MS) {
                        if (hangStart == 0L) {
                            hangStart = lastTick
                            lastStack = 0L
                            w("Watchdog", "ГЛАВНЫЙ ПОТОК НЕ ОТВЕЧАЕТ уже $silent мс")
                            dumpLogcat(ctx, 400)
                        }
                        if (now - lastStack >= 5_000L) {
                            lastStack = now
                            val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "    at $it" }
                            w("Watchdog", "Стек главного потока (молчит $silent мс):\n$stack")
                        }
                    } else if (hangStart != 0L) {
                        i("Watchdog", "Главный поток ожил, простой был около ${now - hangStart} мс")
                        hangStart = 0L
                    }
                    if (now - lastMem >= MEM_EVERY_MS) {
                        lastMem = now
                        i("Mem", memorySnapshot(ctx))
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "hrips-watchdog").apply { isDaemon = true; start() }
    }

    fun memorySnapshot(ctx: Context): String = try {
        val rt = Runtime.getRuntime()
        val heap = (rt.totalMemory() - rt.freeMemory()) shr 20
        val max = rt.maxMemory() shr 20
        val native = Debug.getNativeHeapAllocatedSize() shr 20
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss shr 10
        "java=$heap/$max МБ, native=$native МБ, pss=$pss МБ, свободно в системе=${info.availMem shr 20} МБ" +
            (if (info.lowMemory) ", СИСТЕМА НА ГРАНИ ПАМЯТИ" else "") +
            ", потоков=${Thread.activeCount()}"
    } catch (e: Exception) {
        "нет данных (${e.javaClass.simpleName})"
    }

    private fun deviceInfo(ctx: Context): String {
        val v = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)})"
        }.getOrDefault("?")
        return "Версия $v, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "ABI ${Build.SUPPORTED_ABIS.firstOrNull()}"
    }

    // ---- logcat (там же пишет сам Gecko) -----------------------------------------------------

    /** Хвост logcat только нашего приложения (системе разрешено отдавать приложению его собственные строки). */
    fun dumpLogcat(ctx: Context, lines: Int) {
        val collected = ArrayList<String>()
        val reader = Thread({
            try {
                val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", lines.toString())
                    .redirectErrorStream(true).start()
                p.inputStream.bufferedReader().useLines { seq -> seq.forEach { synchronized(collected) { collected.add(it) } } }
            } catch (_: Exception) {
            }
        }, "hrips-logcat").apply { isDaemon = true; start() }
        runCatching { reader.join(3_000L) }
        val text = synchronized(collected) { collected.joinToString("\n") }
        if (text.isNotBlank()) {
            queue.offer("${stamp()} [logcat] I/Logcat: хвост logcat (${collected.size} строк):\n$text\n")
        } else {
            queue.offer("${stamp()} [logcat] W/Logcat: logcat недоступен\n")
        }
    }

    // ---- работа с файлом из настроек --------------------------------------------------------

    // File.length() у несуществующего файла равен 0.
    fun sizeBytes(ctx: Context): Long = file(ctx).length() + oldFile(ctx).length()

    fun clear(ctx: Context) {
        synchronized(ioLock) {
            runCatching { out?.close() }
            out = null
            file(ctx).delete()
            oldFile(ctx).delete()
            written = 0L
        }
        if (enabled) i("AppLog", "Журнал очищен")
    }

    /**
     * Собирает один файл для отправки: старая часть, новая часть и свежий хвост logcat.
     * Вызывать НЕ из главного потока. Возвращает null, если журнала нет.
     */
    fun buildShareFile(ctx: Context): File? {
        if (enabled) {
            i("AppLog", "Выгрузка журнала. ${memorySnapshot(ctx)}")
            dumpLogcat(ctx, 800)
            // Даём писателю дописать очередь.
            val deadline = SystemClock.elapsedRealtime() + 1_500L
            while (queue.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20L)
        }
        val parts = listOf(oldFile(ctx), file(ctx)).filter { it.exists() && it.length() > 0L }
        if (parts.isEmpty()) return null
        val share = File(ctx.cacheDir, "share").apply { mkdirs() }
        val target = File(share, "hrips-log.txt")
        target.delete()
        target.outputStream().use { o -> parts.forEach { p -> p.inputStream().use { it.copyTo(o) } } }
        return target
    }

    // ---- безопасные описания для строк журнала ---------------------------------------------------

    /** Адрес для журнала: приватные вкладки не раскрываются, у остальных нет параметров запроса и фрагмента. */
    fun url(raw: String?, isPrivate: Boolean): String {
        if (isPrivate) return "(приватная вкладка)"
        if (raw.isNullOrBlank()) return "(пусто)"
        return try {
            val u = Uri.parse(raw)
            if (u.scheme == "data" || u.scheme == "blob" || u.scheme == "javascript") "${u.scheme}:…"
            else "${u.scheme}://${u.host ?: ""}${u.path.orEmpty()}".take(200)
        } catch (_: Exception) {
            "(не разобран)"
        }
    }
}
