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
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission

class PermissionRequest(
    val origin: String,
    val what: String,
    val kinds: List<String>,
    val canRemember: Boolean,
    val note: String?,
    val contentPermission: ContentPermission? = null,
    /** Страница, которая спрашивает: диалог показывается только поверх неё. */
    val owner: GeckoSession? = null,
    val onResult: (Boolean) -> Unit,
) {
    var remember by mutableStateOf(canRemember)
}

class Permissions(private val context: Context) {
    val queue = mutableStateListOf<PermissionRequest>()

    var requestAndroid: ((Array<String>) -> Unit)? = null

    var requestFirstRun: ((Array<String>) -> Unit)? = null
    var firstRunStage by mutableIntStateOf(0)

    private fun runtimeList(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(): List<String> = runtimeList().filterNot { isGranted(it) }

    fun canInstallApks(): Boolean = context.packageManager.canRequestPackageInstalls()

    private data class AndroidPending(
        val permissions: Array<String>,
        val callback: GeckoSession.PermissionDelegate.Callback,
    )

    private val pendingAndroid = ArrayDeque<AndroidPending>()
    private companion object {
        const val MAX_ANDROID_QUEUE = 16
        const val NOTE_ENGINE = "Выбор запомнит движок для этого сайта. Сбросить его можно в разрешениях сайта."
    }
    private var activeAndroid: AndroidPending? = null
    private val main = MainThread.handler

    private fun origin(uri: String?): String =
        runCatching { Uri.parse(uri.orEmpty()).webOrigin() }.getOrNull() ?: uri.orEmpty()

    val sites = SitePermissions(context)

    private fun ask(
        session: GeckoSession,
        origin: String,
        what: String,
        kinds: List<String> = emptyList(),
        canRemember: Boolean = false,
        note: String? = null,
        contentPermission: ContentPermission? = null,
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
            if (!session.isOpen) {
                onResult(false)
                return@post
            }
            queue.add(PermissionRequest(origin, what, kinds, canRemember, note, contentPermission, session, onResult))
        }
    }

    private fun askContent(session: GeckoSession, perm: ContentPermission, what: String): GeckoResult<Int> {
        val result = GeckoResult<Int>()
        ask(session, origin(perm.uri), what, note = NOTE_ENGINE, contentPermission = perm) { ok ->
            result.complete(if (ok) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY)
        }
        return result
    }

    /** Запрос, который можно показать поверх страницы [current]; запросы фоновых вкладок ждут. */
    fun firstFor(current: GeckoSession?): PermissionRequest? =
        queue.firstOrNull { it.owner == null || it.owner === current }

    /** Страница закрыта или выгружена: её запросы снимаются отказом, который никуда не записывается. */
    fun forget(session: GeckoSession) {
        val gone = queue.filter { it.owner === session }
        if (gone.isEmpty()) return
        queue.removeAll(gone)
        gone.forEach { runCatching { it.onResult(false) } }
    }

    fun answer(request: PermissionRequest, allow: Boolean) {
        if (!queue.remove(request)) return

        if (request.contentPermission != null) {
            val value = if (allow) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY
            runCatching {
                (context.applicationContext as? HripsApp)?.runtime?.storageController
                    ?.setPermission(request.contentPermission, value)
            }
        } else if (request.canRemember && request.remember) {
            request.kinds.forEach { sites.set(request.origin, it, allow) }
        }

        request.onResult(allow)
    }

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
        val missing = next.permissions.filterNot { isGranted(it) }.toTypedArray()
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
                result[permission] == true || isGranted(permission)
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
            val needed = permissions.orEmpty().filterNot { isGranted(it) }
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
            ask(session, origin(uri), "использовать " + parts.joinToString(" и "), kinds, canRemember) { allow ->
                if (allow) callback.grant(video?.firstOrNull(), audio?.firstOrNull()) else callback.reject()
            }
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: ContentPermission,
        ): GeckoResult<Int>? {
            val allow = ContentPermission.VALUE_ALLOW
            val deny = ContentPermission.VALUE_DENY

            // Уведомления сайтов выключены целиком: не спрашиваем и не показываем, но сохранённые решения не трогаем.
            if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION &&
                (context.applicationContext as? HripsApp)?.store?.siteNotifications == false
            ) return GeckoResult.fromValue(deny)
            if (perm.value != ContentPermission.VALUE_PROMPT) return GeckoResult.fromValue(perm.value)

            return when (perm.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> askContent(session, perm, "узнать ваше местоположение")
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> askContent(session, perm, "показывать уведомления")
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> GeckoResult.fromValue(deny)
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> GeckoResult.fromValue(allow)
                GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS,
                GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS,
                GeckoSession.PermissionDelegate.PERMISSION_XR,
                GeckoSession.PermissionDelegate.PERMISSION_TRACKING,
                GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> GeckoResult.fromValue(deny)
                GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> GeckoResult.fromValue(allow)
                else -> GeckoResult.fromValue(deny)
            }
        }
    }
}
