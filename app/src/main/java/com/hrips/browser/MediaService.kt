package com.hrips.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat

/**
 * Foreground-сервис воспроизведения: пока на какой-то вкладке играет или стоит на паузе медиа,
 * показывает уведомление (пауза/пуск, предыдущий/следующий трек, стоп, ползунок перемотки)
 * и подключает кнопки гарнитуры, Bluetooth и экрана блокировки.
 * Состояние берёт из [MediaHub], сам ничего не воспроизводит.
 */
class MediaService : Service() {
    private lateinit var hub: MediaHub
    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        hub = (application as HripsApp).media
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Воспроизведение", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "hrips").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = hub.play()
                override fun onPause() = hub.pause()
                override fun onStop() = hub.stop()
                override fun onSkipToNext() = hub.next()
                override fun onSkipToPrevious() = hub.previous()
                override fun onSeekTo(pos: Long) = hub.seekTo(pos)
            })
            isActive = true
        }
        hub.serviceRunning = true
        hub.listener = { refresh() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> hub.toggle()
            ACTION_NEXT -> hub.next()
            ACTION_PREV -> hub.previous()
            ACTION_STOP -> hub.stop()
        }
        val now = hub.now()
        // После startForegroundService startForeground нужно вызвать сразу, иначе система убьёт приложение
        ServiceCompat.startForeground(
            this, ID, build(now), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        if (now == null) {
            stop()
            return START_NOT_STICKY
        }
        publish(now)
        return START_NOT_STICKY
    }

    private fun refresh() {
        val now = hub.now()
        if (now == null) {
            stop()
            return
        }
        publish(now)
        getSystemService(NotificationManager::class.java).notify(ID, build(now))
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Данные для системных элементов управления (экран блокировки, гарнитура, Bluetooth). */
    private fun publish(now: MediaHub.Now) {
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, now.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, now.subtitle ?: "")
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, now.artwork)
        if (now.durationMs > 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, now.durationMs)
        session.setMetadata(meta.build())

        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (now.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (now.hasPrev) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (now.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO

        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (now.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    if (now.positionMs >= 0) now.positionMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (now.playing) now.rate else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build()
        )
    }

    private fun action(icon: Int, label: String, act: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(), Intent(this, MediaService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun build(now: MediaHub.Now?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val playing = now?.playing == true
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(now?.title ?: "Воспроизведение")
            .setContentText(now?.subtitle)
            .setLargeIcon(now?.artwork)
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(if (now?.isPrivate == true) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PUBLIC)

        // Кнопки для Android до 13 (на 13+ шторка рисует кнопки по состоянию MediaSession, см. publish)
        val compact = mutableListOf<Int>()
        var index = 0
        if (now?.hasPrev == true) {
            b.addAction(action(android.R.drawable.ic_media_previous, "Назад", ACTION_PREV)); compact.add(index++)
        }
        b.addAction(
            if (playing) action(android.R.drawable.ic_media_pause, "Пауза", ACTION_TOGGLE)
            else action(android.R.drawable.ic_media_play, "Играть", ACTION_TOGGLE)
        )
        compact.add(index++)
        if (now?.hasNext == true) {
            b.addAction(action(android.R.drawable.ic_media_next, "Дальше", ACTION_NEXT)); compact.add(index++)
        }
        b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", ACTION_STOP))

        return b.setStyle(
            Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(*compact.toIntArray())
        ).build()
    }

    // Пользователь смахнул приложение из списка недавних: останавливаем воспроизведение
    override fun onTaskRemoved(rootIntent: Intent?) {
        hub.stop()
        stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        hub.listener = null
        hub.serviceRunning = false
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val ID = 2
        const val CHANNEL = "media"
        const val ACTION_TOGGLE = "com.hrips.browser.MEDIA_TOGGLE"
        const val ACTION_NEXT = "com.hrips.browser.MEDIA_NEXT"
        const val ACTION_PREV = "com.hrips.browser.MEDIA_PREV"
        const val ACTION_STOP = "com.hrips.browser.MEDIA_STOP"
    }
}
