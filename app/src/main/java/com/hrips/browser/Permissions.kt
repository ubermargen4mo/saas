package com.hrips.browser

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

class PermissionRequest(
    val origin: String,
    val what: String,
    /** Что именно просит сайт (SitePermissions.CAMERA/MIC), нужно для запоминания выбора */
    val kinds: List<String>,
    /** Можно ли запомнить выбор (камера и микрофон вне приватных вкладок) */
    val canRemember: Boolean,
    /** Пояснение под текстом, когда выбор запоминает сам движок */
    val note: String?,
    val onResult: (Boolean) -> Unit,
) {
    var remember by mutableStateOf(canRemember)
}

/**
 * Разрешения сайтов: камера, микрофон, геолокация, уведомления.
 * Сначала спрашиваем пользователя про сайт (диалог в BrowserScreen), потом при необходимости
 * просим системное разрешение Android (через launcher из MainActivity).
 */
class Permissions(private val context: Context) {
    val queue = mutableStateListOf<PermissionRequest>()

    /** Устанавливается из MainActivity: запускает системный запрос разрешений. */
    var requestAndroid: ((Array<String>) -> Unit)? = null

    // ---- Первый запуск: все системные разрешения просим сразу, а не по одному посреди страницы ----

    /** Устанавливается из MainActivity: системный запрос для экрана первого запуска. */
    var requestFirstRun: ((Array<String>) -> Unit)? = null
    /** 0 = просим разрешения, 1 = последний шаг (установка приложений) */
    var firstRunStage by mutableIntStateOf(0)

    /** Все runtime-разрешения, которые использует браузер. */
    fun runtimeList(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun missing(): List<String> = runtimeList().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    /** Установка .apk из браузера: это особое разрешение, включается только на странице настроек Android. */
    fun canInstallApks(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    private var pending: GeckoSession.PermissionDelegate.Callback? = null

    private fun host(uri: String?) = uri?.let { Uri.parse(it).host } ?: uri.orEmpty()

    /** Запомненные решения по камере и микрофону. */
    val sites = SitePermissions(context)

    private fun ask(
        origin: String,
        what: String,
        kinds: List<String> = emptyList(),
        canRemember: Boolean = false,
        note: String? = null,
        onResult: (Boolean) -> Unit,
    ) {
        if (canRemember && kinds.isNotEmpty()) {
            // Если по всем пунктам решение уже есть, диалог не показываем
            val known = kinds.map { sites.get(origin, it) }
            if (known.all { it != null }) {
                onResult(known.all { it == true })
                return
            }
        }
        queue.add(PermissionRequest(origin, what, kinds, canRemember, note, onResult))
    }

    fun answer(request: PermissionRequest, allow: Boolean) {
        if (!queue.remove(request)) return
        if (request.canRemember && request.remember) {
            request.kinds.forEach { sites.set(request.origin, it, allow) }
        }
        request.onResult(allow)
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
            val kinds = buildList {
                if (!video.isNullOrEmpty()) add(SitePermissions.CAMERA)
                if (!audio.isNullOrEmpty()) add(SitePermissions.MIC)
            }
            // В приватной вкладке ничего не запоминаем
            val canRemember = !session.settings.usePrivateMode
            ask(host(uri), "использовать " + parts.joinToString(" и "), kinds, canRemember) { allow ->
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
                    ask(host(perm.uri), "узнать ваше местоположение", note = NOTE_ENGINE) { ok ->
                        result.complete(if (ok) allow else deny)
                    }
                    result
                }
                // Уведомления сайта: спрашиваем, как и про местоположение (показ делает SiteNotifications)
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> {
                    val result = GeckoResult<Int>()
                    ask(host(perm.uri), "показывать уведомления", note = NOTE_ENGINE) { ok ->
                        result.complete(if (ok) allow else deny)
                    }
                    result
                }
                // Звук без действия пользователя блокируем
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> GeckoResult.fromValue(deny)
                else -> GeckoResult.fromValue(allow)
            }
        }
    }

    private companion object {
        const val NOTE_ENGINE = "Выбор запомнится для этого сайта. Сбросить его можно в настройках."
    }
}
