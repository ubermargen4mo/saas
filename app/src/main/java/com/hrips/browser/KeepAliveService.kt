package com.hrips.browser

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Date

/**
 * Держит свёрнутый браузер и процесс текущей страницы в памяти, чтобы при возврате страница не загружалась заново.
 *
 * Почему без службы это не работает: GeckoView поднимает процесс страницы с PRIORITY_HIGH (BIND_IMPORTANT) только
 * до уровня самого приложения. Свёрнутое приложение без службы переднего плана Android считает кэшированным
 * (oom_adj 900+), и при нехватке памяти или по правилам энергосбережения производителя процесс страницы убивается
 * первым — страница перезагружается, набранный текст и состояние чатов теряются. Так же ведёт себя Firefox для
 * Android (Mozilla bug 1752594). Служба переднего плана делает приложение «заметным» (perceptible), а с ним и
 * процесс текущей страницы. Фоновые вкладки (BIND_WAIVE_PRIORITY) она не держит.
 *
 * Служба запускается из [MainActivity.onStop] — в момент ухода с экрана это разрешено и на Android 12+.
 * Останавливается при возврате, по таймеру из настроек, кнопкой в уведомлении и при смахивании приложения.
 */
class KeepAliveService : Service() {
    private var alarm: AlarmManager.OnAlarmListener? = null
    private var holdUntil = 0L
    private var title = ""
    private var isPrivate = false
    private var foreground = false
    private var lastStartId = 0
    private val addActionRunnable = Runnable { if (foreground) notifySafely(build(withAction = true)) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_HOLD -> hold(intent)
            ACTION_RELEASE -> {
                AppLog.i(TAG, "страницу отпустили из уведомления")
                PageKeepAlive.onReleasedByUser()
                stopNow()
            }
            else -> stopNow()
        }
        return START_NOT_STICKY
    }

    private fun hold(intent: Intent) {
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        isPrivate = intent.getBooleanExtra(EXTRA_PRIVATE, false)
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0).coerceAtLeast(0)
        holdUntil = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
        ensureChannel(this)
        try {
            // startForeground обязателен в ответ на startForegroundService, иначе система завершит приложение.
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, build(withAction = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, build(withAction = false))
            }
            foreground = true
        } catch (e: Exception) {
            AppLog.w(TAG, "служба удержания страницы не перешла на передний план", e)
            PageKeepAlive.onServiceFailed()
            stopNow()
            return
        }
        scheduleExpiry()
        // Без кнопок система откладывает показ уведомления службы на ~10 с (Android 12+): при быстром возврате,
        // например чтобы скопировать код, оно не мелькает. Кнопка «Освободить память» добавляется после этой паузы.
        MainThread.handler.removeCallbacks(addActionRunnable)
        MainThread.handler.postDelayed(addActionRunnable, ACTION_DELAY_MS)
        AppLog.i(TAG) { "держу страницу в фоне, минут=${if (minutes == 0) "без ограничения" else minutes}" }
    }

    private fun scheduleExpiry() {
        cancelExpiry()
        if (holdUntil == 0L) return
        val am = getSystemService(AlarmManager::class.java) ?: return
        val listener = AlarmManager.OnAlarmListener {
            AppLog.i(TAG, "время удержания страницы вышло")
            PageKeepAlive.onExpired()
            stopNow()
        }
        alarm = listener
        // Не будит устройство: по истечении срока сработает при ближайшем пробуждении. Часы ELAPSED_REALTIME, в отличие
        // от Handler.postDelayed, идут и во сне, поэтому «1 час» не растягивается на всю ночь.
        runCatching { am.set(AlarmManager.ELAPSED_REALTIME, holdUntil, "hrips:keep-page", listener, MainThread.handler) }
            .onFailure { AppLog.w(TAG, "не удалось поставить таймер удержания", it) }
    }

    private fun cancelExpiry() {
        val listener = alarm ?: return
        alarm = null
        runCatching { getSystemService(AlarmManager::class.java)?.cancel(listener) }
    }

    private fun stopNow() {
        MainThread.handler.removeCallbacks(addActionRunnable)
        cancelExpiry()
        foreground = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        // stopSelf(id), а не stopSelf(): если за это время пришла новая команда запуска (быстро свернули снова),
        // служба не остановится и выполнит её. Иначе она была бы остановлена до startForeground, и система
        // завершила бы приложение с ошибкой «startForegroundService() did not then call startForeground()».
        stopSelf(lastStartId)
    }

    private fun notifySafely(n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(ID, n) }
    }

    private fun build(withAction: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (holdUntil > 0L) {
            val wallClock = System.currentTimeMillis() + (holdUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            "Страница не перезагрузится при возврате до ${DateFormat.getTimeFormat(this).format(Date(wallClock))}"
        } else {
            "Страница не перезагрузится, когда вы вернётесь"
        }
        val heading = if (isPrivate) "Приватная вкладка" else title.ifBlank { "Страница открыта" }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_keep_page)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(if (isPrivate) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_keep_page)
                    .setContentTitle("Страница открыта в фоне")
                    .build(),
            )
        if (withAction) {
            val release = PendingIntent.getService(
                this, REQUEST_RELEASE,
                Intent(this, KeepAliveService::class.java).setAction(ACTION_RELEASE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Освободить память", release)
        }
        return b.build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Приложение смахнули из недавних: пользователь его закрыл, держать страницу больше незачем.
        AppLog.i(TAG, "задачу смахнули, отпускаю страницу")
        PageKeepAlive.onReleasedByUser()
        stopNow()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        MainThread.handler.removeCallbacks(addActionRunnable)
        cancelExpiry()
        foreground = false
        PageKeepAlive.onServiceGone()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeepAlive"
        // 1 и 2 у загрузок (DownloadService), 3 у медиа (MediaService).
        private const val ID = 4
        private const val CHANNEL = "keep_page"
        private const val REQUEST_OPEN = 40
        private const val REQUEST_RELEASE = 41
        private const val ACTION_DELAY_MS = 11_000L
        internal const val ACTION_HOLD = "com.hrips.browser.KEEP_PAGE_HOLD"
        internal const val ACTION_STOP = "com.hrips.browser.KEEP_PAGE_STOP"
        internal const val ACTION_RELEASE = "com.hrips.browser.KEEP_PAGE_RELEASE"
        internal const val EXTRA_TITLE = "title"
        internal const val EXTRA_PRIVATE = "private"
        internal const val EXTRA_MINUTES = "minutes"

        internal fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            val channel = NotificationChannel(CHANNEL, "Страница в фоне", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показывается, пока свёрнутый браузер держит открытую страницу в памяти"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}

/** Когда при сворачивании держать текущую страницу в памяти. */
internal object KeepPagePolicy {
    fun shouldKeep(enabled: Boolean, quitting: Boolean, home: Boolean, url: String): Boolean =
        enabled && !quitting && !home && url.isNotBlank() && !url.startsWith("about:", ignoreCase = true)
}

/** Что браузер держит в фоне: заголовок для уведомления и срок. */
interface BackgroundKeeper {
    /** Вызывается при сворачивании. Возвращает false, если служба не запустилась (тогда держится только приоритет). */
    fun hold(title: String, isPrivate: Boolean, minutes: Int, onReleased: () -> Unit): Boolean

    /** Вызывается при возврате в приложение и при выходе. */
    fun release()
}

/** Запуск и остановка [KeepAliveService]. Все методы вызываются из главного потока. */
internal class PageKeepAlive(private val app: Context) : BackgroundKeeper {
    override fun hold(title: String, isPrivate: Boolean, minutes: Int, onReleased: () -> Unit): Boolean {
        callback = onReleased
        val intent = Intent(app, KeepAliveService::class.java)
            .setAction(KeepAliveService.ACTION_HOLD)
            .putExtra(KeepAliveService.EXTRA_TITLE, if (isPrivate) "" else title.take(MAX_TITLE))
            .putExtra(KeepAliveService.EXTRA_PRIVATE, isPrivate)
            .putExtra(KeepAliveService.EXTRA_MINUTES, minutes)
        return try {
            ContextCompat.startForegroundService(app, intent)
            requested = true
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException и т. п.: остаётся прежняя защита одним приоритетом.
            AppLog.w("KeepAlive", "не удалось запустить службу удержания страницы", e)
            callback = null
            false
        }
    }

    override fun release() {
        callback = null
        if (!requested) return
        requested = false
        // Не stopService: если служба ещё не успела вызвать startForeground, система сочла бы это нарушением и
        // завершила приложение. Команда остановки придёт в onStartCommand строго после команды запуска.
        try {
            app.startService(Intent(app, KeepAliveService::class.java).setAction(KeepAliveService.ACTION_STOP))
        } catch (e: Exception) {
            AppLog.w("KeepAlive", "не удалось остановить службу удержания страницы", e)
        }
    }

    companion object {
        private const val MAX_TITLE = 120
        private var callback: (() -> Unit)? = null
        private var requested = false

        private fun finish() {
            val cb = callback
            callback = null
            requested = false
            cb?.invoke()
        }

        internal fun onReleasedByUser() = finish()

        internal fun onExpired() = finish()

        internal fun onServiceFailed() = finish()

        internal fun onServiceGone() {
            requested = false
        }
    }
}
