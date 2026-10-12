package com.hrips.browser

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class DownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastStartId = 0

    private val tick = object : Runnable {
        override fun run() {
            val downloads = (application as HripsApp).downloads
            val running = downloads.items.filter(downloads::isActive)
            if (running.isEmpty()) {
                releaseWake()
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(lastStartId)
                return
            }
            if (running.any { it.status != DlStatus.WAITING }) acquireWake() else releaseWake()
            getSystemService(NotificationManager::class.java).notify(ID, build(running))
            handler.postDelayed(this, 700)
        }
    }

    private fun acquireWake() {
        val wl = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hrips:download")
            ?.also { it.setReferenceCounted(false); wakeLock = it }
            ?: return
        runCatching { wl.acquire(WAKE_TIMEOUT_MS) }
    }

    private fun releaseWake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    private fun action(code: Int, action: String): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, DownloadService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(running: List<DownloadItem>): Notification {
        val downloads = (application as HripsApp).downloads
        val title = when {
            running.isNotEmpty() && running.all { it.isPrivate } ->
                if (running.size == 1) "Приватная загрузка" else "Приватные загрузки: ${running.size}"
            running.size == 1 && !running[0].isPrivate -> running[0].name
            running.size == 1 -> "Приватная загрузка"
            else -> "Загрузки: ${running.size}"
        }
        val allWaiting = running.all { it.status == DlStatus.WAITING }
        val canPause = running.any { it.canPause }
        val b = NotificationCompat.Builder(this, Downloads.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(downloads.openPageIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (canPause) b.addAction(android.R.drawable.ic_media_pause, "Пауза", action(1002, ACTION_PAUSE_ALL))
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", action(1001, ACTION_CANCEL_ALL))

        val known = running.filter { it.total > 0 }
        val totalKnown = known.size == running.size && known.isNotEmpty()
        val doneBytes = running.sumOf { it.done }
        val speed = running.sumOf { it.speed }
        b.setContentText(
            when {
                allWaiting -> running.firstOrNull { it.waitReason != null }?.waitReason ?: "Ожидание"
                totalKnown -> {
                    val s = Formatter.formatShortFileSize(this, doneBytes) + " из " +
                        Formatter.formatShortFileSize(this, known.sumOf { it.total })
                    if (speed > 0) s + " • " + Formatter.formatShortFileSize(this, speed) + "/с" else s
                }
                else -> Formatter.formatShortFileSize(this, doneBytes)
            },
        )
        if (totalKnown) {
            val total = known.sumOf { it.total }
            b.setProgress(100, (doneBytes * 100 / total).toInt().coerceIn(0, 100), allWaiting)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun showPaused(downloads: Downloads) {
        val paused = downloads.items.filter { it.status == DlStatus.PAUSED }
        val nm = getSystemService(NotificationManager::class.java)
        if (paused.isEmpty()) {
            nm.cancel(PAUSED_ID)
            return
        }
        val title = if (paused.all { it.isPrivate }) "Загрузки приостановлены"
        else if (paused.size == 1 && !paused[0].isPrivate) paused[0].name
        else "Загрузки приостановлены: ${paused.size}"
        val n = NotificationCompat.Builder(this, Downloads.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("Приостановлено. Прогресс сохранён")
            .setContentIntent(downloads.openPageIntent())
            .addAction(android.R.drawable.ic_media_play, "Продолжить", action(1003, ACTION_RESUME_ALL))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", action(1001, ACTION_CANCEL_ALL))
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build()
        runCatching { nm.notify(PAUSED_ID, n) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val app = application as HripsApp
        val downloads = app.downloads
        val nm = getSystemService(NotificationManager::class.java)
        when (intent?.action) {
            ACTION_CANCEL_ALL -> {
                downloads.cancelAll()
                nm.cancel(PAUSED_ID)
                handler.removeCallbacks(tick)
                releaseWake()
                ServiceCompat.startForeground(this, ID, build(emptyList()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_PAUSE_ALL -> {
                downloads.pauseAll()
                handler.removeCallbacks(tick)
                releaseWake()
                ServiceCompat.startForeground(this, ID, build(emptyList()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                showPaused(downloads)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_RESUME_ALL -> {
                nm.cancel(PAUSED_ID)
                downloads.resumeAll()
            }
            else -> nm.cancel(PAUSED_ID)
        }
        val running = downloads.items.filter(downloads::isActive)
        ServiceCompat.startForeground(this, ID, build(running), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as? HripsApp)?.downloads?.pauseFromServiceTimeout()
        handler.removeCallbacks(tick)
        releaseWake()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        releaseWake()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val ID = 1
        const val PAUSED_ID = 2
        const val WAKE_TIMEOUT_MS = 2L * 60L * 1000L
        const val ACTION_CANCEL_ALL = "com.hrips.browser.DOWNLOAD_CANCEL_ALL"
        const val ACTION_PAUSE_ALL = "com.hrips.browser.DOWNLOAD_PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.hrips.browser.DOWNLOAD_RESUME_ALL"
    }
}
