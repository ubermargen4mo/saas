package com.hrips.browser

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground-сервис на время загрузок: держит процесс живым в фоне и показывает общий прогресс.
 * Сами файлы пишет [Downloads], сервис только следит за списком и сам останавливается, когда всё скачано.
 */
class DownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val downloads = (application as HripsApp).downloads
            val running = downloads.items.filter(downloads::isActive)
            if (running.isEmpty()) {
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            getSystemService(NotificationManager::class.java).notify(ID, build(running))
            handler.postDelayed(this, 700)
        }
    }

    private fun build(running: List<DownloadItem>): Notification {
        // Имена файлов из приватных вкладок не показываем в шторке уведомлений
        val title = when {
            running.isNotEmpty() && running.all { it.isPrivate } ->
                if (running.size == 1) "Приватная загрузка" else "Приватные загрузки: ${running.size}"
            running.size == 1 && !running[0].isPrivate -> running[0].name
            running.size == 1 -> "Приватная загрузка"
            else -> "Загрузки: ${running.size}"
        }
        val b = NotificationCompat.Builder(this, Downloads.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отменить",
                PendingIntent.getService(
                    this, 1001,
                    Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val known = running.filter { it.total > 0 }
        if (known.isNotEmpty() && known.size == running.size) {
            val total = known.sumOf { it.total }
            val done = known.sumOf { it.done }
            b.setProgress(100, (done * 100 / total).toInt().coerceIn(0, 100), false)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HripsApp
        if (intent?.action == ACTION_CANCEL_ALL) {
            app.downloads.cancelAll()
            handler.removeCallbacks(tick)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val running = app.downloads.items.filter(app.downloads::isActive)
        // startForeground нужно вызвать сразу, иначе система остановит сервис с ошибкой
        ServiceCompat.startForeground(this, ID, build(running), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    /**
     * Android 15+ may time-limit dataSync foreground services to 6h/24h for apps
     * targeting API 35+. Stop work cleanly instead of letting the system crash the process.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as? HripsApp)?.downloads?.cancelRunningFromServiceTimeout()
        handler.removeCallbacks(tick)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val ID = 1
        const val ACTION_CANCEL_ALL = "com.hrips.browser.DOWNLOAD_CANCEL_ALL"
    }
}
