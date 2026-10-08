package com.hrips.browser

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate

/**
 * Уведомления сайтов (Notification API): мессенджеры, почта и т.п.
 * Важно: это не push. Уведомления приходят, пока браузер запущен (открыт или работает в фоне
 * вместе с загрузкой/воспроизведением). Закрытый браузер ничего получить не может.
 * Разрешение для сайта запрашивает [Permissions], тут только показ.
 */
class SiteNotifications(private val app: Application) {
    private val main = Handler(Looper.getMainLooper())
    private val shown = HashMap<Int, WebNotification>()
    private val byKey = HashMap<String, Int>()
    private var nextId = 5000
    private var channelReady = false

    // Делегат движок вызывает не из главного потока
    val delegate = object : WebNotificationDelegate {
        override fun onShowNotification(notification: WebNotification) {
            main.post { show(notification) }
        }

        override fun onCloseNotification(notification: WebNotification) {
            main.post { close(notification) }
        }
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (channelReady) return
        app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Уведомления сайтов", NotificationManager.IMPORTANCE_DEFAULT))
        channelReady = true
    }

    private fun show(n: WebNotification) {
        if (!canNotify()) {
            n.dismiss()
            return
        }
        ensureChannel()
        // Одинаковые source+tag заменяют друг друга, как в обычных браузерах
        val key = n.source.orEmpty() + "|" + n.tag.orEmpty()
        val id = if (!n.tag.isNullOrEmpty()) byKey.getOrPut(key) { nextId++ } else nextId++
        shown[id]?.let { old -> if (old !== n) old.dismiss() }
        shown[id] = n

        val tap = PendingIntent.getActivity(
            app, id,
            Intent(app, MainActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val host = n.source?.let { Uri.parse(it).host ?: it }
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(n.title.orEmpty().ifBlank { host ?: "Сайт" })
            .setContentText(n.text)
            .setSubText(host)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun close(n: WebNotification) {
        val id = shown.entries.firstOrNull { it.value === n }?.key ?: return
        shown.remove(id)
        byKey.entries.removeAll { it.value == id }
        app.getSystemService(NotificationManager::class.java).cancel(id)
        n.dismiss()
    }

    /** Нажатие на уведомление: открываем вкладку сайта и сообщаем странице о клике. */
    fun clicked(id: Int, browser: Browser) {
        val n = shown.remove(id) ?: return
        byKey.entries.removeAll { it.value == id }
        app.getSystemService(NotificationManager::class.java).cancel(id)
        browser.focusSite(n.source)
        n.click()
        n.dismiss()
    }

    companion object {
        const val EXTRA_ID = "site_notification_id"
        private const val CHANNEL = "site_notifications"
    }
}
