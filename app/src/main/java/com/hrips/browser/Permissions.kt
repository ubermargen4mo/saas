package com.hrips.browser

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

class PermissionRequest(val origin: String, val what: String, val onResult: (Boolean) -> Unit)

/**
 * Разрешения сайтов: камера, микрофон, геолокация.
 * Сначала спрашиваем пользователя про сайт (диалог в BrowserScreen), потом при необходимости
 * просим системное разрешение Android (через launcher из MainActivity).
 */
class Permissions(private val context: Context) {
    val queue = mutableStateListOf<PermissionRequest>()

    /** Устанавливается из MainActivity: запускает системный запрос разрешений. */
    var requestAndroid: ((Array<String>) -> Unit)? = null
    private var pending: GeckoSession.PermissionDelegate.Callback? = null

    private fun host(uri: String?) = uri?.let { Uri.parse(it).host } ?: uri.orEmpty()

    private fun ask(origin: String, what: String, onResult: (Boolean) -> Unit) {
        queue.add(PermissionRequest(origin, what, onResult))
    }

    fun answer(request: PermissionRequest, allow: Boolean) {
        if (queue.remove(request)) request.onResult(allow)
    }

    fun onAndroidResult(result: Map<String, Boolean>) {
        val cb = pending
        pending = null
        if (result.isNotEmpty() && result.values.all { it }) cb?.grant() else cb?.reject()
    }

    val delegate = object : GeckoSession.PermissionDelegate {

        // Системные разрешения Android (камера, микрофон, местоположение)
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            val needed = permissions.orEmpty().filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            val launch = requestAndroid
            if (needed.isEmpty()) {
                callback.grant()
            } else if (launch == null) {
                callback.reject()
            } else {
                pending = callback
                launch(needed.toTypedArray())
            }
        }

        // Камера и микрофон (getUserMedia)
        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            val parts = mutableListOf<String>()
            if (!video.isNullOrEmpty()) parts.add("камеру")
            if (!audio.isNullOrEmpty()) parts.add("микрофон")
            ask(host(uri), "использовать " + parts.joinToString(" и ")) { allow ->
                if (allow) callback.grant(video?.firstOrNull(), audio?.firstOrNull()) else callback.reject()
            }
        }

        // Остальное: геолокация, уведомления, автовоспроизведение и т.д.
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? {
            val allow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            val deny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            return when (perm.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> {
                    val result = GeckoResult<Int>()
                    ask(host(perm.uri), "узнать ваше местоположение") { ok ->
                        result.complete(if (ok) allow else deny)
                    }
                    result
                }
                // Уведомления мы пока не умеем показывать, звук без действия пользователя блокируем
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION,
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> GeckoResult.fromValue(deny)
                else -> GeckoResult.fromValue(allow)
            }
        }
    }
}
