package com.hrips.browser

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    /** Можно ли запомнить выбор */
    val canRemember: Boolean,
    /** Пояснение под текстом */
    val note: String?,
    /** Gecko content permission, если решение должно сохраняться самим Gecko */
    val contentPermission: GeckoSession.PermissionDelegate.ContentPermission? = null,
    val onResult: (Boolean) -> Unit,
) {
    var remember by mutableStateOf(canRemember)
}

/**
 * Разрешения сайтов: камера, микрофон, геолокация, уведомления.
 * Сначала спрашиваем пользователя про сайт, потом при необходимости Android permission.
 *
 * Важно: content-permissions Gecko (геолокация/уведомления и т.п.) не дублируются в нашем
 * собственном хранилище. Gecko хранит их по principal/origin сам, а UI передаёт решение обратно
 * через StorageController.setPermission(). Это безопаснее, чем пытаться сопоставлять URL вручную.
 */
class Permissions(private val context: Context) {
    val queue = mutableStateListOf<PermissionRequest>()

    /** Устанавливается из MainActivity: запускает системный запрос разрешений. */
    var requestAndroid: ((Array<String>) -> Unit)? = null

    // ---- Первый запуск ----

    var requestFirstRun: ((Array<String>) -> Unit)? = null
    var firstRunStage by mutableIntStateOf(0)

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

    fun canInstallApks(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    private data class AndroidPending(
        val permissions: Array<String>,
        val callback: GeckoSession.PermissionDelegate.Callback,
    )

    /** Android может показать только один runtime-permission dialog одновременно. */
    private val pendingAndroid = ArrayDeque<AndroidPending>()
    private companion object {
        const val MAX_ANDROID_QUEUE = 16
        const val NOTE_ENGINE = "Выбор запомнит движок для этого сайта. Сбросить его можно в разрешениях сайта."
    }
    private var activeAndroid: AndroidPending? = null
    private val main = Handler(Looper.getMainLooper())

    private fun origin(uri: String?): String = runCatching {
        val u = Uri.parse(uri.orEmpty())
        val scheme = u.scheme?.lowercase().orEmpty()
        val host = u.host?.lowercase().orEmpty()
        if (scheme.isBlank() || host.isBlank()) return@runCatching uri.orEmpty()
        val port = when {
            u.port == -1 -> null
            scheme == "http" && u.port == 80 -> null
            scheme == "https" && u.port == 443 -> null
            else -> u.port
        }?.let { ":$it" }.orEmpty()
        "$scheme://$host$port"
    }.getOrDefault(uri.orEmpty())

    /** Запомненные решения для camera/mic, только потому что это media permission, а не Gecko ContentPermission. */
    val sites = SitePermissions(context)

    private fun ask(
        origin: String,
        what: String,
        kinds: List<String> = emptyList(),
        canRemember: Boolean = false,
        note: String? = null,
        contentPermission: GeckoSession.PermissionDelegate.ContentPermission? = null,
        onResult: (Boolean) -> Unit,
    ) {
        if (canRemember && kinds.isNotEmpty()) {
            val known = kinds.map { sites.get(origin, it) }
            if (known.all { it != null }) {
                onResult(known.all { it == true })
                return
            }
        }
        main.post {
            queue.add(PermissionRequest(origin, what, kinds, canRemember, note, contentPermission, onResult))
        }
    }

    /** Отображение dialog завершилось решением. Вызывать только из UI/Main thread. */
    fun answer(request: PermissionRequest, allow: Boolean) {
        if (!queue.remove(request)) return

        if (request.contentPermission != null) {
            val value = if (allow) {
                GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            } else {
                GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            }
            runCatching {
                (context.applicationContext as? HripsApp)?.runtime?.storageController
                    ?.setPermission(request.contentPermission, value)
            }
        } else if (request.canRemember && request.remember) {
            request.kinds.forEach { sites.set(request.origin, it, allow) }
        }

        request.onResult(allow)
    }

    /** Очередь системных Android permission dialogs: следующий запускаем только после результата предыдущего. */
    private fun enqueueAndroidPermissions(permissions: Array<String>, callback: GeckoSession.PermissionDelegate.Callback) {
        if (permissions.isEmpty()) {
            callback.grant()
            return
        }
        if (pendingAndroid.size >= MAX_ANDROID_QUEUE || activeAndroid != null && pendingAndroid.size >= MAX_ANDROID_QUEUE - 1) {
            callback.reject()
            return
        }
        pendingAndroid.addLast(AndroidPending(permissions, callback))
        drainAndroidPermissions()
    }

    private fun drainAndroidPermissions() {
        if (activeAndroid != null) return
        val next = pendingAndroid.removeFirstOrNull() ?: return
        val missing = next.permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isEmpty()) {
            next.callback.grant()
            drainAndroidPermissions()
            return
        }
        val launch = requestAndroid
        if (launch == null) {
            next.callback.reject()
            drainAndroidPermissions()
            return
        }
        activeAndroid = next.copy(permissions = missing)
        runCatching {
            launch(missing)
        }.onFailure {
            activeAndroid?.callback?.reject()
            activeAndroid = null
            drainAndroidPermissions()
        }
    }

    fun onAndroidResult(result: Map<String, Boolean>) {
        main.post {
            val current = activeAndroid ?: return@post
            activeAndroid = null
            val granted = current.permissions.all { permission ->
                result[permission] == true || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) current.callback.grant() else current.callback.reject()
            drainAndroidPermissions()
        }
    }

    val delegate = object : GeckoSession.PermissionDelegate {

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            val needed = permissions.orEmpty().filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isEmpty()) callback.grant()
            else main.post { enqueueAndroidPermissions(needed.toTypedArray(), callback) }
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            val parts = buildList {
                if (!video.isNullOrEmpty()) add("камеру")
                if (!audio.isNullOrEmpty()) add("микрофон")
            }
            val kinds = buildList {
                if (!video.isNullOrEmpty()) add(SitePermissions.CAMERA)
                if (!audio.isNullOrEmpty()) add(SitePermissions.MIC)
            }
            val canRemember = !session.settings.usePrivateMode
            ask(origin(uri), "использовать " + parts.joinToString(" и "), kinds, canRemember) { allow ->
                if (allow) callback.grant(video?.firstOrNull(), audio?.firstOrNull()) else callback.reject()
            }
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? {
            val allow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            val deny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            val prompt = GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT

            // Gecko уже знает решение для principal — не показываем второй собственный dialog.
            if (perm.value != prompt) return GeckoResult.fromValue(perm.value)

            return when (perm.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> {
                    val result = GeckoResult<Int>()
                    ask(
                        origin(perm.uri),
                        "узнать ваше местоположение",
                        note = NOTE_ENGINE,
                        contentPermission = perm,
                    ) { ok -> result.complete(if (ok) allow else deny) }
                    result
                }
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> {
                    val result = GeckoResult<Int>()
                    ask(
                        origin(perm.uri),
                        "показывать уведомления",
                        note = NOTE_ENGINE,
                        contentPermission = perm,
                    ) { ok -> result.complete(if (ok) allow else deny) }
                    result
                }
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> GeckoResult.fromValue(deny)
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> GeckoResult.fromValue(allow)
                // Эти permissions не имеют безопасного UI в hrips пока: не разрешаем молча.
                GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS,
                GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS,
                GeckoSession.PermissionDelegate.PERMISSION_XR,
                GeckoSession.PermissionDelegate.PERMISSION_TRACKING,
                GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> GeckoResult.fromValue(deny)
                // Storage permission не раскрывает новый пользовательский ресурс и нужен ряду web-apps.
                GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> GeckoResult.fromValue(allow)
                else -> GeckoResult.fromValue(deny)
            }
        }
    }

}
