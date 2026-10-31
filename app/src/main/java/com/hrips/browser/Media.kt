package com.hrips.browser

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession as GeckoMediaSession

class MediaHub(private val app: Application) {
    class Now(
        val title: String,
        val subtitle: String?,
        val artwork: Bitmap?,
        val playing: Boolean,
        val isPrivate: Boolean,
        val hasNext: Boolean,
        val hasPrev: Boolean,
        val canSeek: Boolean,
        val durationMs: Long,
        val positionMs: Long,
        val rate: Float,
    )

    private class Track(val session: GeckoSession, val isPrivate: Boolean, val pageTitle: () -> String, val onAudible: (Boolean) -> Unit) {
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

    var listener: (() -> Unit)? = null
    var serviceRunning = false

    val isPlaying: Boolean get() = current?.playing == true

    fun isPlaying(session: GeckoSession): Boolean = tracks[session]?.playing == true

    /** [onAudible] вызывается на главном потоке, когда во вкладке начинается или заканчивается воспроизведение (значок звука). */
    fun attach(session: GeckoSession, isPrivate: Boolean, pageTitle: () -> String, onAudible: (Boolean) -> Unit = {}) {
        val t = Track(session, isPrivate, pageTitle, onAudible)
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

    fun forget(session: GeckoSession) {
        val t = tracks.remove(session) ?: return
        t.onAudible(false)
        if (current === t) current = fallback()
        listener?.invoke()
    }

    private fun fallback(): Track? =
        tracks.values.lastOrNull { it.playing } ?: tracks.values.lastOrNull { it.active }

    private fun changed(t: Track) {
        if (!MainThread.isCurrent) {
            MainThread.handler.post { changed(t) }
            return
        }
        t.onAudible(t.playing)
        if (t.playing) current = t
        val c = current
        if (c == null || (!c.active && !c.playing)) current = fallback()
        val hripsApp = app as? HripsApp
        val controlsOn = hripsApp?.store?.mediaControls != false
        if (current?.playing == true && !serviceRunning && controlsOn) {
            hripsApp?.downloads?.askNotificationPermissionOnce()
            try {
                ContextCompat.startForegroundService(app, Intent(app, MediaService::class.java))
            } catch (e: Exception) {
                Log.w("Media", "foreground service start refused", e)
            }
        }
        listener?.invoke()
    }

    fun now(): Now? {
        val c = current ?: return null
        if (!c.active && !c.playing) return null

        val durationMs = if (c.duration.isFinite() && c.duration > 0) (c.duration * 1000).toLong() else -1L
        val positionMs = when {
            durationMs < 0 || c.position < 0 -> -1L
            c.playing -> {
                val elapsed = SystemClock.elapsedRealtime() - c.positionAt
                (c.position * 1000 + elapsed * c.rate).toLong().coerceIn(0L, durationMs)
            }
            else -> (c.position * 1000).toLong().coerceIn(0L, durationMs)
        }
        val hasNext = c.features and GeckoMediaSession.Feature.NEXT_TRACK != 0L
        val hasPrev = c.features and GeckoMediaSession.Feature.PREVIOUS_TRACK != 0L
        val canSeek = c.features and GeckoMediaSession.Feature.SEEK_TO != 0L && durationMs > 0

        val title = if (c.isPrivate) "Приватная вкладка"
            else c.title?.takeIf { it.isNotBlank() } ?: c.pageTitle().ifBlank { "Воспроизведение" }
        val subtitle = if (c.isPrivate) null else c.artist?.takeIf { it.isNotBlank() }
        val art = if (c.isPrivate) null else c.artwork
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
