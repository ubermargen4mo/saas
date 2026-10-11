package com.hrips.browser

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession as GeckoMediaSession

/**
 * Состояние медиа всех вкладок. Движок сообщает, что на странице играет видео или звук
 * (обычный <video>/<audio> или MediaSession API сайта), а [MediaService] показывает
 * уведомление с кнопками и держит процесс живым, пока экран выключен или браузер свёрнут.
 *
 * Управляем одной вкладкой: той, где воспроизведение началось последним.
 * Всё вызывается из главного потока (так работают делегаты GeckoView).
 */
class MediaHub(private val app: Application) {
    private val main = Handler(Looper.getMainLooper())

    /** Что показать в уведомлении и на экране блокировки. */
    class Now(
        val title: String,
        val subtitle: String?,
        val artwork: Bitmap?,
        val playing: Boolean,
        val isPrivate: Boolean,
        val hasNext: Boolean,
        val hasPrev: Boolean,
        val canSeek: Boolean,
        /** Длительность в мс, -1 если неизвестна или это прямой эфир */
        val durationMs: Long,
        /** Текущая позиция в мс, -1 если неизвестна */
        val positionMs: Long,
        val rate: Float,
    )

    private class Track(val session: GeckoSession, val isPrivate: Boolean, val pageTitle: () -> String) {
        var media: GeckoMediaSession? = null
        var title: String? = null
        var artist: String? = null
        var artwork: Bitmap? = null
        var playing = false
        var active = false
        var features = 0L
        var duration = -1.0
        var position = -1.0
        var rate = 1.0
        var positionAt = 0L
    }

    private val tracks = LinkedHashMap<GeckoSession, Track>()
    private var current: Track? = null

    /** Сервис подписывается, чтобы обновлять уведомление. */
    var listener: (() -> Unit)? = null
    /** Выставляет сам сервис в onCreate/onDestroy. */
    var serviceRunning = false

    /** Сейчас что-то играет (нужно для автоматической "картинки в картинке"). */
    val isPlaying: Boolean get() = current?.playing == true

    /** True, если конкретная GeckoSession сейчас воспроизводит медиа. */
    fun isPlaying(session: GeckoSession): Boolean = tracks[session]?.playing == true

    /** Подключает отслеживание медиа к сессии вкладки. */
    fun attach(session: GeckoSession, isPrivate: Boolean, pageTitle: () -> String) {
        val t = Track(session, isPrivate, pageTitle)
        tracks[session] = t
        session.setMediaSessionDelegate(object : GeckoMediaSession.Delegate {
            override fun onActivated(session: GeckoSession, mediaSession: GeckoMediaSession) {
                t.media = mediaSession
                t.active = true
                changed(t)
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: GeckoMediaSession) {
                t.active = false
                t.playing = false
                changed(t)
            }

            override fun onMetadata(session: GeckoSession, mediaSession: GeckoMediaSession, meta: GeckoMediaSession.Metadata) {
                t.title = meta.title
                t.artist = meta.artist
                // Обложку в приватной вкладке не запрашиваем: это лишний сетевой запрос и след в уведомлении
                if (!t.isPrivate) {
                    meta.artwork?.getBitmap(256)?.accept({ bmp ->
                        if (bmp != null) {
                            t.artwork = bmp
                            changed(t)
                        }
                    }, { })
                }
                changed(t)
            }

            // Какие действия умеет страница: перемотка, следующий и предыдущий трек
            override fun onFeatures(session: GeckoSession, mediaSession: GeckoMediaSession, features: Long) {
                t.features = features
                changed(t)
            }

            override fun onPositionState(session: GeckoSession, mediaSession: GeckoMediaSession, state: GeckoMediaSession.PositionState) {
                t.duration = state.duration
                t.position = state.position
                t.rate = state.playbackRate
                t.positionAt = SystemClock.elapsedRealtime()
                changed(t)
            }

            override fun onPlay(session: GeckoSession, mediaSession: GeckoMediaSession) {
                t.media = mediaSession
                t.active = true
                t.playing = true
                current = t
                changed(t)
            }

            override fun onPause(session: GeckoSession, mediaSession: GeckoMediaSession) {
                t.playing = false
                changed(t)
            }

            override fun onStop(session: GeckoSession, mediaSession: GeckoMediaSession) {
                t.playing = false
                t.active = false
                changed(t)
            }
        })
    }

    /** Вкладка закрыта или сессия заменена. */
    fun forget(session: GeckoSession) {
        val t = tracks.remove(session) ?: return
        if (current === t) current = fallback()
        listener?.invoke()
    }

    private fun fallback(): Track? =
        tracks.values.lastOrNull { it.playing } ?: tracks.values.lastOrNull { it.active }

    private fun changed(t: Track) {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            main.post { changed(t) }
            return
        }
        if (t.playing) current = t
        val c = current
        if (c == null || (!c.active && !c.playing)) current = fallback()
        val now = current
        val controlsOn = (app as? HripsApp)?.store?.mediaControls != false
        if (now != null && now.playing && !serviceRunning && controlsOn) {
            // Без разрешения на уведомления (Android 13+) воспроизведение не сломается,
            // но кнопок в шторке не будет. Обычно оно уже выдано при первом запуске.
            (app as? HripsApp)?.downloads?.askNotificationPermissionOnce()
            try {
                ContextCompat.startForegroundService(app, Intent(app, MediaService::class.java))
            } catch (e: Exception) {
                // Android не разрешил запуск из фона: звук всё равно играет, пока жив процесс
                android.util.Log.w("Media", "foreground service start refused", e)
            }
        }
        listener?.invoke()
    }

    /** null = показывать нечего, сервис должен остановиться. */
    fun now(): Now? {
        val c = current ?: return null
        if (!c.active && !c.playing) return null

        val durationMs = if (c.duration.isFinite() && c.duration > 0) (c.duration * 1000).toLong() else -1L
        val positionMs = when {
            durationMs < 0 || c.position < 0 -> -1L
            c.playing -> {
                // Между событиями движка позиция идёт сама: дорисовываем её по часам
                val elapsed = SystemClock.elapsedRealtime() - c.positionAt
                (c.position * 1000 + elapsed * c.rate).toLong().coerceIn(0L, durationMs)
            }
            else -> (c.position * 1000).toLong().coerceIn(0L, durationMs)
        }
        val hasNext = c.features and GeckoMediaSession.Feature.NEXT_TRACK != 0L
        val hasPrev = c.features and GeckoMediaSession.Feature.PREVIOUS_TRACK != 0L
        val canSeek = c.features and GeckoMediaSession.Feature.SEEK_TO != 0L && durationMs > 0

        val title: String
        val subtitle: String?
        val art: Bitmap?
        if (c.isPrivate) {
            // Название и обложку приватной вкладки в шторку не выносим
            title = "Приватная вкладка"; subtitle = null; art = null
        } else {
            title = c.title?.takeIf { it.isNotBlank() } ?: c.pageTitle().ifBlank { "Воспроизведение" }
            subtitle = c.artist?.takeIf { it.isNotBlank() }
            art = c.artwork
        }
        return Now(title, subtitle, art, c.playing, c.isPrivate, hasNext, hasPrev, canSeek, durationMs, positionMs, c.rate.toFloat())
    }

    fun play() { current?.media?.play() }
    fun pause() { current?.media?.pause() }
    fun toggle() { if (current?.playing == true) pause() else play() }
    fun stop() { current?.media?.stop() }
    fun next() { current?.media?.nextTrack() }
    fun previous() { current?.media?.previousTrack() }
    fun seekTo(ms: Long) { current?.media?.seekTo(ms / 1000.0, false) }
}
