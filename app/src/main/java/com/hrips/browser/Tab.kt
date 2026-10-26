package com.hrips.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.SystemClock
import android.util.Log
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.util.UUID

private val ISSUER_CN = Regex("(?:^|,)CN=([^,]+)")
private const val TAG = "HripsTab"
// Three crashes in this window trigger a clean home-page fallback.
private const val RECOVERY_WINDOW_MS = 8_000L
private const val REDIRECT_GESTURE_MS = 15_000L
private const val HOME_FALLBACK_STREAK = 3
private const val THUMBNAIL_RELEASE_DELAY_MS = 5_000L
private const val SESSION_RETRY_BACKOFF_MS = 1_500L

enum class Trust { NONE, SECURE, WARNING }

class StateCache {
    private var text: String? = null
    private var owner: GeckoSession.SessionState? = null
    private var version = -1

    fun serialize(st: GeckoSession.SessionState, v: Int): String {
        synchronized(this) {
            val cached = text
            if (cached != null && owner === st && version == v) return cached
        }
        val result = st.toString()
        synchronized(this) {
            text = result
            owner = st
            version = v
        }
        return result
    }
}

class TabSaveParts internal constructor(
    val url: String,
    val title: String,
    private val id: String,
    private val raw: String?,
    private val state: GeckoSession.SessionState?,
    private val version: Int,
    private val cache: StateCache,
) {
    fun build(): TabSnap = TabSnap(url, title, raw ?: state?.let { cache.serialize(it, version) }, id)
}

class Tab(
    private val runtime: GeckoRuntime,
    rawStartUrl: String,
    tabId: String = UUID.randomUUID().toString(),
    private val desktopFor: (host: String?) -> Boolean,
    private val onDesktopSaved: (host: String, on: Boolean) -> Unit,
    private val onVisited: (url: String, title: String) -> Unit,
    private val onStateChanged: () -> Unit = {},
    /** Изменилось только состояние сессии (прокрутка, формы): сохранять нужно реже, чем при смене адреса или вкладок. */
    private val onSessionStateChanged: () -> Unit = onStateChanged,
    private val onDownload: (Tab, WebResponse) -> Unit,
    private val permissionDelegate: GeckoSession.PermissionDelegate,
    private val promptDelegate: GeckoSession.PromptDelegate,
    private val onNewWindow: (opener: Tab, uri: String) -> GeckoSession,
    private val onPageClose: (Tab) -> Unit,
    private val onMenu: (ContextInfo) -> Unit,
    private val onExternal: (uri: String, hasUserGesture: Boolean, fromUrl: String?) -> Boolean,
    private val media: MediaHub,
    savedState: String? = null,
    savedTitle: String = "",
    val isPrivate: Boolean = false,
    openNow: Boolean = true,
    deferOpen: Boolean = false,
) {
    val id: String = tabId

    private val startUrl = if (rawStartUrl == "about:blank") "" else rawStartUrl

    val popup = !openNow
    var parent: Tab? = null
    var returnTo: Tab? = null

    /** Открыта ссылкой из другого приложения (в т.ч. Custom Tab): «назад» с первой страницы возвращает в то приложение. */
    var fromApp = false

    var desktopMode by mutableStateOf(desktopFor(siteKey(rawStartUrl)))
        private set
    private var modeHost: String? = siteKey(rawStartUrl)
    private val localDesktop = HashMap<String, Boolean>()
    /** Цвет сайта (theme-color или фон страницы) для шапки на планшете; null, если цвета нет. */
    var themeColor by mutableStateOf<androidx.compose.ui.graphics.Color?>(null)
    var thumbnail by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var home by mutableStateOf(!popup && startUrl.isBlank())

    /**
     * После «домой» под начальным экраном остаётся прежняя страница. Когда из дома открывают новый адрес, она бы
     * просвечивала до первой отрисовки. Пока `coverStale` включён, поверх вида показывается пустая заслонка.
     */
    var coverStale by mutableStateOf(false)
    /** Прежняя страница ещё лежит под начальным экраном (после «домой»). */
    var stalePage = false
    private var coverStarted = false
    var url by mutableStateOf(startUrl)
    var title by mutableStateOf(savedTitle)
    var errorPage by mutableStateOf(false)
        private set
    private var errorTarget: String? = null
    private var errorNetwork = false
    var secure by mutableStateOf(false)
        private set
    var secureException by mutableStateOf(false)
        private set
    var certIssuer by mutableStateOf<String?>(null)
        private set
    val trust: Trust
        get() = when {
            home -> Trust.NONE
            errorPage -> Trust.WARNING
            secure && !secureException -> Trust.SECURE
            else -> Trust.WARNING
        }

    private var sessionState: GeckoSession.SessionState? = null
    private var sessionStateVersion = 0
    private var savedStateRaw: String? = savedState?.takeIf { it.isNotBlank() }
    private var lazyStateParsed: GeckoSession.SessionState? = null
    private var lazyState: GeckoSession.SessionState?
        get() {
            val raw = savedStateRaw
            if (raw != null) {
                savedStateRaw = null
                lazyStateParsed = runCatching { GeckoSession.SessionState.fromString(raw) }.getOrNull()
            }
            return lazyStateParsed
        }
        set(value) {
            savedStateRaw = null
            lazyStateParsed = value
        }
    private val stateCache = StateCache()
    private var lastRecoverAt = 0L
    private var lastGestureAt = 0L
    private var lastEnsureLoadAttemptAt = 0L
    private var recoverStreak = 0
    private var recovering = false
    private var closed = false
    private val mainHandler = MainThread.handler
    private var pendingSuspendSession: GeckoSession? = null
    private var visible = false
    private var focused = false
    private val releaseThumbnailRunnable = Runnable {
        if (!visible && !isPrivate) thumbnail = null
    }
    private var lastActivatedAt = SystemClock.elapsedRealtime()

    var isSuspended by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)

    /** Встроенный перевод Gecko (на устройстве): язык страницы, если его можно перевести, язык пользователя и состояние. */
    var translateLang by mutableStateOf<String?>(null)
    var translateTo by mutableStateOf<String?>(null)
    var translated by mutableStateOf(false)
    var translating by mutableStateOf(false)

    private fun resetTranslation() {
        translateLang = null
        translated = false
        translating = false
    }

    /** Начало загрузки основной страницы и признак, что первая отрисовка уже учтена (только для журнала замеров). */
    private var navStartedAt = 0L
    private var paintLogged = true

    var chromeHidden by mutableStateOf(false)
    private var lastScrollY = 0
    private var scrollAcc = 0

    /**
     * Панель свёрнута жестом, а не прокруткой. На непрокручиваемой странице Gecko всё равно присылает
     * onScrollChanged(0) (например, при смене клиппинга), и прокрутка в ноль разворачивала бы панель сразу.
     * Закреплённое сворачивание держится, пока пользователь сам не развернёт панель, не прокрутит вверх
     * или не уйдёт на другую страницу.
     */
    private var chromePinned = false

    fun collapseChrome() {
        chromePinned = true
        chromeHidden = true
    }

    fun expandChrome() {
        chromePinned = false
        chromeHidden = false
    }

    private fun onPageScrolled(y: Int) {
        val dy = y - lastScrollY
        lastScrollY = y
        if (y <= 0) { scrollAcc = 0; if (!chromePinned) chromeHidden = false; return }
        if (dy == 0) return
        if ((scrollAcc > 0) != (dy > 0)) scrollAcc = 0
        scrollAcc += dy
        if (scrollAcc > 64) chromeHidden = true else if (scrollAcc < -24) expandChrome()
    }

    var session by mutableStateOf(newSession())
        private set

    init {
        if (openNow && deferOpen) {
            media.forget(session)
            isSuspended = true
        } else if (openNow) {
            try {
                session.open(runtime)
                if (lazyState == null && startUrl.isNotBlank()) session.loadUri(startUrl)
            } catch (e: Exception) {
                media.forget(session)
                runCatching { session.close() }
                isSuspended = true
                Log.w(TAG, "Не удалось открыть вкладку; она будет восстановлена при активации", e)
            }
        }
    }

    fun ensureLoaded() {
        if (closed) return
        if (session.isOpen) {
            val st = lazyState ?: return
            try {
                session.restoreState(st)
                lazyState = null
            } catch (e: Exception) {
                try {
                    val target = url
                    if (target.isBlank()) throw e
                    session.loadUri(target)
                    lazyState = null
                    sessionState = null
                    sessionStateVersion++
                    onStateChanged()
                    Log.w(TAG, "Не удалось восстановить состояние вкладки; открываю текущий URL", e)
                } catch (fallbackError: Exception) {
                    lazyState = st
                    Log.w(TAG, "Не удалось восстановить сессию или открыть текущий URL", fallbackError)
                }
            }
            return
        }
        if (!isSuspended) return
        val now = SystemClock.elapsedRealtime()
        if (lastEnsureLoadAttemptAt != 0L && now - lastEnsureLoadAttemptAt < SESSION_RETRY_BACKOFF_MS) return
        lastEnsureLoadAttemptAt = now

        val st = lazyState ?: sessionState
        val target = url
        val fresh = try {
            newSession()
        } catch (e: Exception) {
            lazyState = st
            isSuspended = true
            Log.w(TAG, "Не удалось создать GeckoSession; попытка будет повторена позже", e)
            return
        }
        session = fresh
        loading = false
        progress = 0
        canGoBack = false
        canGoForward = false
        fullscreen = false
        try {
            fresh.open(runtime)
            if (!home && st != null) {
                try {
                    fresh.restoreState(st)
                    lazyState = null
                    sessionState = st
                } catch (restoreError: Exception) {
                    // Follow Firefox's session-restore fallback pattern: use the current URL when
                    // Gecko rejects a stale/incompatible serialized state instead of crashing UI.
                    if (target.isBlank()) throw restoreError
                    fresh.loadUri(target)
                    lazyState = null
                    sessionState = null
                    sessionStateVersion++
                    onStateChanged()
                    Log.w(TAG, "Состояние вкладки не восстановилось; загружен текущий URL", restoreError)
                }
            } else if (!home && target.isNotBlank()) {
                fresh.loadUri(target)
            }
            isSuspended = false
            lastEnsureLoadAttemptAt = 0L
            fresh.setFocused(visible && focused)
            fresh.setPriorityHint(if (visible && focused) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
        } catch (e: Exception) {
            media.forget(fresh)
            runCatching { fresh.close() }
            lazyState = st
            isSuspended = true
            Log.w(TAG, "Не удалось восстановить GeckoSession; повторная попытка будет при следующей активации", e)
        }
    }

    fun saveParts(): TabSaveParts {
        val live = sessionState
        val raw = if (!home && live == null) savedStateRaw else null
        val st = if (home || raw != null) null else (live ?: lazyStateParsed)
        return TabSaveParts(if (home) "" else url, title, id, raw, st, sessionStateVersion, stateCache)
    }

    /** Версия для ПК: не только десктопный User-Agent, но и широкий макет (~980 px), `<meta viewport>` игнорируется, как в Chrome и Firefox. */
    private fun viewportFor(desktop: Boolean) =
        if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE

    private fun uaMode(desktop: Boolean) =
        if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE

    private fun newSession(): GeckoSession {
        val s = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(uaMode(desktopMode))
                .viewportMode(viewportFor(desktopMode))
                .usePrivateMode(isPrivate)
                .build()
        )
        PageText.register(s, id)
        ThemeColor.register(s, id) { c -> if (s === session) themeColor = c }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(sess: GeckoSession, pageUrl: String) {
                if (sess !== session) return
                PageText.forget(id)
                themeColor = null
                AppLog.i("Page") { "начало загрузки ${AppLog.url(pageUrl, isPrivate)}" }
                navStartedAt = SystemClock.elapsedRealtime()
                paintLogged = false
                if (coverStale) coverStarted = true
                resetTranslation()
                loading = true
                progress = 0
                skipVisit = imageSearchPending && pageUrl.startsWith("data:text/html")
            }
            override fun onPageStop(sess: GeckoSession, success: Boolean) {
                if (sess !== session) return
                AppLog.i("Page") { "конец загрузки успех=$success за ${SystemClock.elapsedRealtime() - navStartedAt} мс ${AppLog.url(url, isPrivate)}" }
                loading = false
                if (coverStarted) coverStale = false
                if (success && !errorPage) {
                    recoverStreak = 0
                    lastRecoverAt = 0L
                    if (!skipVisit) onVisited(url, title)
                }
            }
            override fun onProgressChange(sess: GeckoSession, value: Int) {
                if (sess !== session) return
                progress = value
            }
            override fun onSecurityChange(sess: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) {
                if (sess !== session) return
                secure = info.isSecure
                secureException = info.isException
                certIssuer = info.certificate?.issuerX500Principal?.name
                    ?.let { ISSUER_CN.find(it)?.groupValues?.get(1) }
            }
            override fun onSessionStateChange(sess: GeckoSession, state: GeckoSession.SessionState) {
                if (sess !== session) return
                sessionState = state
                sessionStateVersion++
                onSessionStateChanged()
                if (pendingSuspendSession === sess) finalizeSuspend(sess, state)
            }
        }
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                sess: GeckoSession,
                newUrl: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (sess !== session) return
                if (newUrl == null || newUrl == "about:blank") return
                if (imageSearchPending) {
                    if (newUrl.startsWith("data:text/html")) return
                    imageSearchPending = false
                }
                if (newUrl.startsWith("data:text/html") && errorTarget != null) {
                    errorPage = true
                    errorTarget?.let { if (it.isNotBlank()) url = it }
                } else {
                    errorPage = false
                    errorTarget = null
                    url = newUrl
                    val host = siteKey(newUrl)
                    if (host != null && host != modeHost) {
                        modeHost = host
                        val want = if (isPrivate) localDesktop[host] ?: desktopFor(host) else desktopFor(host)
                        if (want != desktopMode) applyDesktop(want, reload = true)
                    }
                }
            }
            override fun onLoadError(
                sess: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                if (sess !== session) return null
                errorTarget = uri
                errorNetwork = when (error.code) {
                    WebRequestError.ERROR_OFFLINE, WebRequestError.ERROR_UNKNOWN_HOST, WebRequestError.ERROR_NET_TIMEOUT,
                    WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT, WebRequestError.ERROR_CONNECTION_REFUSED -> true
                    else -> false
                }
                return GeckoResult.fromValue(ErrorPages.dataUri(uri, error))
            }
            override fun onCanGoBack(sess: GeckoSession, value: Boolean) {
                if (sess === session) canGoBack = value
            }
            override fun onCanGoForward(sess: GeckoSession, value: Boolean) {
                if (sess === session) canGoForward = value
            }
            override fun onLoadRequest(
                sess: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                if (sess !== session) return null
                AppLog.i("Nav") { "запрос ${AppLog.url(request.uri, isPrivate)} жест=${request.hasUserGesture} редирект=${request.isRedirect}" }
                val now = SystemClock.elapsedRealtime()
                if (request.hasUserGesture) lastGestureAt = now
                // Редирект после недавнего нажатия (вход через Google/OAuth -> возврат в приложение)
                // считается действием пользователя; фоновые переходы без нажатия по-прежнему блокируются.
                val gesture = request.hasUserGesture ||
                    (request.isRedirect && lastGestureAt != 0L && now - lastGestureAt < REDIRECT_GESTURE_MS)
                return if (onExternal(request.uri, gesture, request.triggerUri.takeIf { !request.isDirectNavigation })) GeckoResult.fromValue(AllowOrDeny.DENY) else null
            }
            override fun onNewSession(sess: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                AppLog.i("Nav") { "новое окно ${AppLog.url(uri, isPrivate)}" }
                return GeckoResult.fromValue(onNewWindow(this@Tab, uri))
            }
        }
        s.permissionDelegate = permissionDelegate
        s.translationsSessionDelegate = object : TranslationsController.SessionTranslation.Delegate {
            override fun onTranslationStateChange(sess: GeckoSession, state: TranslationsController.SessionTranslation.TranslationState?) {
                if (sess !== session) return
                val detected = state?.detectedLanguages
                val pair = state?.requestedTranslationPair
                val failed = state?.error != null
                translateLang = detected?.docLangTag?.takeIf { detected.isDocLangTagSupported }
                translateTo = detected?.userLangTag
                translated = state != null && pair != null && !failed && state.hasVisibleChange
                translating = state != null && pair != null && !failed && !state.hasVisibleChange
                AppLog.i("Translate") {
                    "состояние: язык=${detected?.docLangTag} поддержан=${detected?.isDocLangTagSupported} " +
                        "пара=${pair?.fromLanguage}->${pair?.toLanguage} ошибка=${state?.error} видно=${state?.hasVisibleChange}"
                }
            }
        }
        s.promptDelegate = promptDelegate
        s.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(sess: GeckoSession, scrollX: Int, scrollY: Int) {
                if (sess === session) onPageScrolled(scrollY)
            }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(sess: GeckoSession, newTitle: String?) {
                if (sess === session) title = newTitle.orEmpty()
            }
            override fun onFirstContentfulPaint(sess: GeckoSession) {
                if (sess === session && coverStarted) coverStale = false
                if (sess !== session || paintLogged) return
                paintLogged = true
                AppLog.i("Page") { "первая отрисовка через ${SystemClock.elapsedRealtime() - navStartedAt} мс после начала загрузки" }
            }

            override fun onFullScreen(sess: GeckoSession, fullScreen: Boolean) {
                AppLog.i("Tab") { "полный экран=$fullScreen" }
                if (sess === session) fullscreen = fullScreen
            }
            override fun onContextMenu(
                sess: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                if (sess !== session) return
                val hasMedia = element.srcUri != null && element.type != GeckoSession.ContentDelegate.ContextElement.TYPE_NONE
                if (element.linkUri == null && !hasMedia) return
                onMenu(ContextInfo(element.linkUri, element.srcUri, element.type, element.title, element.altText, element.baseUri))
            }
            override fun onExternalResponse(sess: GeckoSession, response: WebResponse) {
                AppLog.i("Download") { "внешний ответ для скачивания ${AppLog.url(response.uri, isPrivate)}" }
                onDownload(this@Tab, response)
            }
            override fun onCloseRequest(sess: GeckoSession) {
                // window.close() из попапа (вход через Google, оплата, OAuth): закрываем вкладку и возвращаемся к opener.
                if (sess === session && !closed) onPageClose(this@Tab)
            }
            override fun onKill(sess: GeckoSession) {
                AppLog.w("Tab", "onKill: процесс страницы убит системой, текущая сессия=${sess === session}, видимая=$visible")
                if (sess === session) onProcessGone()
            }
            override fun onCrash(sess: GeckoSession) {
                AppLog.w("Tab", "onCrash: процесс страницы упал, текущая сессия=${sess === session}, видимая=$visible")
                if (sess === session) onProcessGone()
            }
        }
        media.attach(s, isPrivate) { title }
        return s
    }

    /**
     * Процесс страницы убит или упал. Как в Firefox (`KillEngineSessionAction` -> `SuspendMiddleware`): у невидимой
     * вкладки сессию сразу не пересоздаём (систему только что заставили освободить память, а свежий процесс
     * вернёт нагрузку и снова будет убит), а приостанавливаем её с сохранённым состоянием; восстановится в
     * `ensureLoaded()`, когда вкладка снова понадобится. Видимую вкладку восстанавливаем сразу.
     * Пока приложение в фоне, все вкладки считаются невидимыми, и текущая вернётся при `onForeground`.
     */
    private fun onProcessGone() {
        if (closed) return
        if (visible) {
            recover()
            return
        }
        val old = session
        pendingSuspendSession = null
        mainHandler.removeCallbacksAndMessages(old)
        lazyState = sessionState ?: lazyState
        media.forget(old)
        runCatching { old.close() }
        isSuspended = true
        loading = false
        fullscreen = false
        progress = 0
        lastEnsureLoadAttemptAt = 0L
        AppLog.i("Tab") { "вкладка ${id.take(6)} приостановлена после потери процесса, состояние сохранено=${lazyState != null}" }
    }

    fun recover() {
        val failed = session
        if (closed || recovering) return
        pendingSuspendSession = null
        mainHandler.removeCallbacksAndMessages(failed)
        recovering = true
        try {
            recoverInternal(failed)
        } finally {
            recovering = false
        }
    }

    private fun recoverInternal(old: GeckoSession) {
        AppLog.w("Tab", "recoverInternal: пересоздаю сессию вкладки ${id.take(6)}")
        if (closed || old !== session) return

        val target = url
        val now = SystemClock.elapsedRealtime()
        recoverStreak = if (lastRecoverAt != 0L && now - lastRecoverAt < RECOVERY_WINDOW_MS) {
            recoverStreak + 1
        } else {
            1
        }
        lastRecoverAt = now
        val useHomeFallback = recoverStreak >= HOME_FALLBACK_STREAK
        val state = if (useHomeFallback) null else sessionState ?: lazyState
        lazyState = null

        val fresh = try {
            newSession()
        } catch (e: Exception) {
            media.forget(old)
            runCatching { old.close() }
            loading = false
            fullscreen = false
            progress = 0
            canGoBack = false
            canGoForward = false
            secure = false
            secureException = false
            certIssuer = null
            isSuspended = true
            if (useHomeFallback) {
                home = true
                clearErrorState()
                discardSavedSessionState()
            } else {
                lazyState = state
            }
            Log.w(TAG, "Не удалось создать GeckoSession после сбоя; вкладка сохранена для повтора", e)
            return
        }
        session = fresh
        isSuspended = false
        loading = false
        fullscreen = false
        progress = 0
        canGoBack = false
        canGoForward = false
        secure = false
        secureException = false
        certIssuer = null
        lastScrollY = 0
        scrollAcc = 0
        expandChrome()

        try {
            val opened = runCatching { fresh.open(runtime) }.isSuccess
            if (!opened) {
                if (useHomeFallback) {
                    home = true
                    clearErrorState()
                    discardSavedSessionState()
                } else {
                    lazyState = state
                }
                media.forget(fresh)
                runCatching { fresh.close() }
                isSuspended = true
                return
            }
            when {
                useHomeFallback -> {
                    home = true
                    clearErrorState()
                    discardSavedSessionState()
                }
                home -> Unit
                state != null && runCatching { fresh.restoreState(state); true }.getOrDefault(false) -> Unit
                target.isNotBlank() -> {
                    clearErrorState()
                    discardSavedSessionState()
                    fresh.loadUri(target)
                }
                else -> home = true
            }
            fresh.setFocused(visible && focused)
            fresh.setPriorityHint(if (visible && focused) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
        } finally {
            media.forget(old)
            runCatching { old.close() }
        }
    }

    private fun clearErrorState() {
        errorPage = false
        errorTarget = null
        errorNetwork = false
    }

    private fun discardSavedSessionState() {
        sessionState = null
        lazyState = null
        sessionStateVersion++
        onStateChanged()
    }


    fun finderClear() = session.finder.clear()

    fun finderFlags(backwards: Boolean): Int =
        if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD

    fun finderFind(text: String, flags: Int) = session.finder.find(text, flags)

    fun finderSetHighlights() {
        session.finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
    }

    fun goBack() = session.goBack()

    fun goForward() = session.goForward()

    fun stopLoading() = session.stop()

    fun reload() = session.reload()

    /** Сеть вернулась: если страница не открылась из-за связи, пробуем ещё раз. Возвращает true, если повторили. */
    fun retryIfNetworkError(): Boolean {
        if (!errorPage || !errorNetwork || loading) return false
        val target = errorTarget?.takeIf { it.isNotBlank() } ?: return false
        session.loadUri(target)
        return true
    }

    fun saveAsPdf() = session.saveAsPdf()

    fun reloadOrStop() {
        if (loading) stopLoading() else reload()
    }

    fun exitFullscreen() = session.exitFullScreen()

    /**
     * Обновляет наши флаги видимости, фокус и приоритет. Активностью сессии (`GeckoSession.setActive`) отсюда НЕ управляем:
     * как и в Firefox (android-components вообще не вызывает `setActive`), это делает сам GeckoView по состоянию
     * поверхности: `setActive(true)` после `surfaceChanged` (когда compositor уже возобновлён) и `setActive(false)`
     * при уничтожении поверхности или подмене сессии. Наш вызов раньше создания поверхности вешал главный поток
     * в `syncResumeResizeCompositor` на Android 16 (Xiaomi). Сессия, не подключённая к GeckoView, и так неактивна.
     */
    fun setVisibility(active: Boolean, focused: Boolean = active): Boolean {
        mainHandler.removeCallbacks(releaseThumbnailRunnable)
        if (active) {
            pendingSuspendSession = null
            mainHandler.removeCallbacksAndMessages(session)
        } else if (!isPrivate) {
            // Non-private previews are already backed by the shared LRU/disk cache.
            // Don't let every visited tab retain a full ImageBitmap indefinitely.
            mainHandler.postDelayed(releaseThumbnailRunnable, THUMBNAIL_RELEASE_DELAY_MS)
        }
        val nextFocused = focused && active
        val changed = visible != active || this.focused != nextFocused
        if (!changed) return false
        visible = active
        this.focused = nextFocused
        if (nextFocused) lastActivatedAt = SystemClock.elapsedRealtime()
        session.setFocused(nextFocused)
        session.setPriorityHint(if (active && nextFocused) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
        return true
    }

    /**
     * Приложение свёрнуто: страница не рисуется, но процесс вкладки остаётся «важным» для Android,
     * чтобы система не убивала его первым и страница не перезагружалась при возврате.
     */
    fun setBackgroundPriority(high: Boolean) {
        if (closed || isSuspended || !session.isOpen) return
        runCatching {
            session.setPriorityHint(if (high) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
        }
    }

    /** Drops only the in-memory reference; persisted previews can still be loaded by the tab tray. */
    fun updateThumbnail(image: androidx.compose.ui.graphics.ImageBitmap): Boolean {
        if (closed) return false
        mainHandler.removeCallbacks(releaseThumbnailRunnable)
        thumbnail = image
        // A capture can finish after this tab became inactive; start the release timer again.
        if (!visible && !isPrivate) {
            mainHandler.postDelayed(releaseThumbnailRunnable, THUMBNAIL_RELEASE_DELAY_MS)
        }
        return true
    }

    fun releaseThumbnailMemory(includePrivate: Boolean = false) {
        mainHandler.removeCallbacks(releaseThumbnailRunnable)
        if (!visible && (includePrivate || !isPrivate)) thumbnail = null
    }

    internal fun lastActivatedAt(): Long = lastActivatedAt

    private fun finalizeSuspend(target: GeckoSession, freshState: GeckoSession.SessionState? = sessionState) {
        if (pendingSuspendSession !== target) return
        if (closed || visible || focused || loading || fullscreen || session !== target || !target.isOpen) {
            pendingSuspendSession = null
            return
        }
        val state = freshState ?: lazyState ?: return
        pendingSuspendSession = null
        mainHandler.removeCallbacksAndMessages(target)
        lazyState = state
        sessionState = state
        media.forget(target)
        target.setActive(false)
        target.setFocused(false)
        if (runCatching { target.close(); true }.getOrDefault(false)) {
            isSuspended = true
        }
    }

    fun suspend(): Boolean {
        if (closed || isSuspended || visible || focused || loading || fullscreen || !session.isOpen || pendingSuspendSession != null) return false
        val target = session
        if (home) {
            media.forget(target)
            target.setActive(false)
            target.setFocused(false)
            return if (runCatching { target.close(); true }.getOrDefault(false)) {
                isSuspended = true
                true
            } else false
        }
        if (lazyState == null && sessionState == null) return false
        pendingSuspendSession = target
        target.setFocused(false)
        target.setActive(false)
        mainHandler.postAtTime({
            if (pendingSuspendSession === target && (sessionState != null || lazyState != null)) {
                finalizeSuspend(target)
            } else if (pendingSuspendSession === target) {
                pendingSuspendSession = null
            }
        }, target, SystemClock.uptimeMillis() + 500L)
        return true
    }

    private var skipVisit = false
    private var imageSearchPending = false

    private fun armCover() {
        if (!stalePage) return
        stalePage = false
        coverStarted = false
        coverStale = true
        // Предохранитель: заслонка не должна остаться, если страница не сообщила ни о старте, ни об отрисовке.
        mainHandler.postDelayed({ coverStale = false }, 6000L)
    }

    fun load(input: String, engine: SearchEngine = SearchEngines.current) {
        armCover()
        home = false
        session.loadUri(toUrl(input, engine))
    }

    fun loadImageSearch(page: String) {
        armCover()
        home = false
        loading = true
        imageSearchPending = true
        session.loadUri(page)
    }

    fun goHome() {
        home = true
        stalePage = true
    }

    fun siteHost(): String? = if (home) null else siteKey(url)

    fun applyDesktop(value: Boolean, reload: Boolean) {
        desktopMode = value
        session.settings.setUserAgentMode(uaMode(value))
        session.settings.setViewportMode(viewportFor(value))
        if (reload && !home && session.isOpen && lazyState == null) session.reload()
    }

    fun setDesktop(value: Boolean) {
        val host = siteHost() ?: return
        modeHost = host
        // Как в Fenix (checkForMobileSite): у адреса вида «m.сайт» или «mobile.сайт» с ПК-версией открываем обычный адрес
        // с заменой записи в истории: такие поддомены часто остаются мобильными даже с десктопным User-Agent.
        val target = if (value) desktopVariantUrl(url) else null
        applyDesktop(value, reload = target == null)
        saveDesktop(host, value)
        if (target != null) {
            // Предпочтение нужно и для нового домена, иначе при переходе на него режим тут же сбросился бы.
            siteKey(target)?.let { newHost ->
                modeHost = newHost
                saveDesktop(newHost, true)
            }
            session.load(GeckoSession.Loader().uri(target).flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY))
        }
    }

    private fun saveDesktop(host: String, value: Boolean) {
        if (isPrivate) localDesktop[host] = value else onDesktopSaved(host, value)
    }

    /** Адрес без префикса «m.» или «mobile.» в имени сайта, либо null, если префикса нет. */
    private fun desktopVariantUrl(url: String): String? {
        val uri = android.net.Uri.parse(url)
        val authority = uri.authority?.lowercase(java.util.Locale.ROOT) ?: return null
        val prefix = listOf("m.", "mobile.").firstOrNull { authority.startsWith(it) } ?: return null
        return uri.buildUpon().authority(authority.substring(prefix.length)).toString()
    }

    fun close() {
        if (closed) return
        pendingSuspendSession = null
        mainHandler.removeCallbacksAndMessages(session)
        mainHandler.removeCallbacks(releaseThumbnailRunnable)
        thumbnail = null
        closed = true
        visible = false
        focused = false
        isSuspended = false
        media.forget(session)
        runCatching { session.close() }
    }
}

