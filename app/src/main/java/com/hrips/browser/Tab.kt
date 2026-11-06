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
private const val RECOVERY_WINDOW_MS = 8_000L
private const val REDIRECT_GESTURE_MS = 15_000L
private const val HOME_FALLBACK_STREAK = 3
private const val THUMBNAIL_RELEASE_DELAY_MS = 5_000L
private const val SESSION_RETRY_BACKOFF_MS = 1_500L
private const val RESTORE_COVER_MAX_MS = 8_000L
/** Сколько страница, с которой ушли кнопкой «Домой», живёт в памяти, чтобы «Назад» вернул её без перезагрузки. */
private const val PARKED_PAGE_MS = 10 * 60_000L

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
    sharedContextId: String? = null,
    private val isVisited: (String) -> Boolean = { false },
    /** Сессия закрыта или выгружена: её незаданные вопросы и запросы разрешений нужно снять. */
    private val onSessionGone: (GeckoSession) -> Unit = {},
) {
    val id: String = tabId

    val contextId: String? = if (isPrivate) sharedContextId ?: "private_" + tabId.filter { it.isLetterOrDigit() } else null

    private val startUrl = if (rawStartUrl == "about:blank") "" else rawStartUrl

    val popup = !openNow
    var parent: Tab? = null
    var returnTo: Tab? = null

    var fromApp = false

    var desktopMode by mutableStateOf(desktopFor(siteKey(rawStartUrl)))
        private set
    private var modeHost: String? = siteKey(rawStartUrl)
    private val localDesktop = HashMap<String, Boolean>()
    var themeColor by mutableStateOf<androidx.compose.ui.graphics.Color?>(null)
    private var themeHost: String? = null
    var thumbnail by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var home by mutableStateOf(!popup && startUrl.isBlank())

    private var returnState: GeckoSession.SessionState? = null
    private var returnUrl: String = ""
    var hasReturn by mutableStateOf(false)
        private set
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

    /** Состояние страницы, с которой ушли «Домой», чтобы вернуть его вместе с живой сессией. */
    private class ParkedPage(
        val session: GeckoSession,
        // Страница в ожидании живёт своей жизнью (SPA меняет адрес и заголовок), поэтому эти поля обновляются.
        var url: String,
        var title: String,
        var canGoBack: Boolean,
        var canGoForward: Boolean,
        val secure: Boolean,
        val secureException: Boolean,
        val certIssuer: String?,
        val errorPage: Boolean,
        val errorTarget: String?,
        val errorNetwork: Boolean,
        val themeColor: androidx.compose.ui.graphics.Color?,
        val themeHost: String?,
        val readerActive: Boolean,
        val translateLang: String?,
        val translated: Boolean,
        val webManifest: org.json.JSONObject?,
        val desktopMode: Boolean,
        val modeHost: String?,
        var state: GeckoSession.SessionState?,
    )

    private fun parkedOf(s: GeckoSession): ParkedPage? = parked?.takeIf { it.session === s }

    private var parked: ParkedPage? = null
    private val dropParkedRunnable = Runnable { dropParked("истёк срок") }
    private var visible = false
    private var focused = false
    private val releaseThumbnailRunnable = Runnable {
        if (!visible && !isPrivate) thumbnail = null
    }
    private var lastActivatedAt = SystemClock.elapsedRealtime()

    /** Страница восстанавливается после потери сессии: поверх неё показывается её последний снимок, пока не будет первой отрисовки. */
    var restoring by mutableStateOf(false)
        private set
    private val clearRestoring = Runnable { restoring = false }

    private fun markRestoring() {
        if (isPrivate || home) return
        restoring = true
        mainHandler.removeCallbacks(clearRestoring)
        mainHandler.postDelayed(clearRestoring, RESTORE_COVER_MAX_MS)
    }

    private fun clearRestoringNow() {
        mainHandler.removeCallbacks(clearRestoring)
        restoring = false
    }

    var isSuspended by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var audible by mutableStateOf(false)
    var muted by mutableStateOf(false)
        private set

    fun toggleMuted() {
        muted = !muted
        media.setMuted(session, muted)
    }

    var translateLang by mutableStateOf<String?>(null)
    var translateTo by mutableStateOf<String?>(null)
    /** Манифест веб-приложения текущей страницы (если сайт его отдал): имя и иконки для ярлыка на главный экран. */
    @Volatile var webManifest: org.json.JSONObject? = null
    var translated by mutableStateOf(false)
    var translating by mutableStateOf(false)

    private fun resetTranslation() {
        translateLang = null
        translated = false
        translating = false
    }

    var textScale by mutableStateOf(100)

    private var textScaleHost: String? = null

    var readerActive by mutableStateOf(false)

    var readerPrefs by mutableStateOf(ReaderPrefs())
        private set

    private fun resetReader() {
        readerActive = false
    }

    fun toggleReader() {
        if (!ReaderMode.toggle(session)) Notices.show(ReaderMode.unavailableReason())
    }

    fun closeReader() = ReaderMode.close(session)

    fun setReaderLook(font: String? = null, theme: String? = null) {
        if (!ReaderMode.setLook(session, font, theme)) {
            Notices.show("Читалка не отвечает, попробуйте ещё раз")
            return
        }
        readerPrefs = ReaderPrefs(font ?: readerPrefs.font, theme ?: readerPrefs.theme)
    }

    private var navStartedAt = 0L
    private var paintLogged = true

    var chromeHidden by mutableStateOf(false)
    private var lastScrollY = 0
    private var scrollAcc = 0

    private var chromePinned = false

    private var chromeChangedAt = 0L

    fun collapseChrome() {
        chromePinned = true
        chromeHidden = true
        chromeChangedAt = SystemClock.elapsedRealtime()
        scrollAcc = 0
    }

    fun expandChrome() {
        chromePinned = false
        chromeHidden = false
        chromeChangedAt = SystemClock.elapsedRealtime()
        scrollAcc = 0
    }

    private fun onPageScrolled(y: Int) {
        val dy = y - lastScrollY
        lastScrollY = y
        if (SystemClock.elapsedRealtime() - chromeChangedAt < 800L) return
        if (y <= 0) { scrollAcc = 0; if (!chromePinned) chromeHidden = false; return }
        if (dy == 0) return
        if ((scrollAcc > 0) != (dy > 0)) scrollAcc = 0
        scrollAcc += dy
        if (scrollAcc > 64) chromeHidden = true
        else if (scrollAcc < (if (chromePinned) -96 else -24)) expandChrome()
    }

    var session by mutableStateOf(newSession())
        private set

    init {
        if (openNow && deferOpen) {
            forgetSession(session)
            isSuspended = true
        } else if (openNow) {
            try {
                session.open(runtime)
                if (lazyState == null && startUrl.isNotBlank()) session.loadUri(startUrl)
            } catch (e: Exception) {
                forgetSession(session)
                runCatching { session.close() }
                isSuspended = true
                Log.w(TAG, "Не удалось открыть вкладку; она будет восстановлена при активации", e)
            }
        }
    }

    private enum class Restore { RESTORED, RELOADED, FAILED }

    private fun restoreOrReload(target: GeckoSession, state: GeckoSession.SessionState): Restore {
        try {
            target.restoreState(state)
            return Restore.RESTORED
        } catch (e: Exception) {
            val address = url
            if (address.isBlank()) {
                Log.w(TAG, "Не удалось восстановить состояние вкладки, а адреса нет", e)
                return Restore.FAILED
            }
            return try {
                target.loadUri(address)
                Log.w(TAG, "Не удалось восстановить состояние вкладки; открываю текущий URL", e)
                Restore.RELOADED
            } catch (fallbackError: Exception) {
                Log.w(TAG, "Не удалось восстановить сессию или открыть текущий URL", fallbackError)
                Restore.FAILED
            }
        }
    }

    fun ensureLoaded() {
        if (closed) return
        if (session.isOpen) {
            reapplyLazyState()
            return
        }
        if (!isSuspended) return
        val now = SystemClock.elapsedRealtime()
        if (lastEnsureLoadAttemptAt != 0L && now - lastEnsureLoadAttemptAt < SESSION_RETRY_BACKOFF_MS) return
        lastEnsureLoadAttemptAt = now
        reopenSession()
    }

    private fun reapplyLazyState() {
        val state = lazyState ?: return
        when (restoreOrReload(session, state)) {
            Restore.RESTORED -> lazyState = null
            Restore.RELOADED -> discardSavedSessionState()
            Restore.FAILED -> lazyState = state
        }
    }

    private fun reopenSession() {
        val state = lazyState ?: sessionState
        val target = url
        val fresh = try {
            newSession()
        } catch (e: Exception) {
            lazyState = state
            isSuspended = true
            Log.w(TAG, "Не удалось создать GeckoSession; попытка будет повторена позже", e)
            return
        }
        session = fresh
        resetNavState()
        try {
            fresh.open(runtime)
            if (!home && state != null) {
                when (restoreOrReload(fresh, state)) {
                    Restore.RESTORED -> {
                        lazyState = null
                        sessionState = state
                    }
                    Restore.RELOADED -> discardSavedSessionState()
                    Restore.FAILED -> throw IllegalStateException("Состояние вкладки не восстановить и открыть адрес не удалось")
                }
            } else if (!home && target.isNotBlank()) {
                fresh.loadUri(target)
            }
            isSuspended = false
            lastEnsureLoadAttemptAt = 0L
            if (!home) markRestoring()
            fresh.setFocused(visible && focused)
            applyPriority(fresh)
        } catch (e: Exception) {
            dispose(fresh)
            lazyState = state
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

    private fun viewportFor(desktop: Boolean) =
        if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE

    private fun uaMode(desktop: Boolean) =
        if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE

    private fun newSession(): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .userAgentMode(uaMode(desktopMode))
            .viewportMode(viewportFor(desktopMode))
            .usePrivateMode(isPrivate)
        contextId?.let { settings.contextId(it) }
        val s = GeckoSession(settings.build())
        ThemeColor.register(s, id) { c -> if (s === session) themeColor = c }
        ReaderMode.register(
            s,
            onState = { active -> if (s === session) readerActive = active },
            onFail = { if (s === session) Notices.show("Не удалось выделить статью на этой странице") },
            onUnavailable = { if (s === session) Notices.show("Режим чтения недоступен на этой странице") },
            onPrefs = { p -> if (s === session) readerPrefs = p },
        )
        // Как в Android Components: без HistoryDelegate Gecko не знает, какие ссылки посещены, и не красит их как посещённые.
        // Саму историю пишет onVisited выше по коду, этот делегат отвечает только за подсветку; в приватных вкладках она выключена.
        s.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onVisited(sess: GeckoSession, url: String, lastVisitedURL: String?, flags: Int): GeckoResult<Boolean>? {
                val topLevel = (flags and GeckoSession.HistoryDelegate.VISIT_TOP_LEVEL) != 0
                val failed = (flags and GeckoSession.HistoryDelegate.VISIT_UNRECOVERABLE_ERROR) != 0
                return GeckoResult.fromValue(!isPrivate && topLevel && !failed)
            }

            override fun getVisited(sess: GeckoSession, urls: Array<String>): GeckoResult<BooleanArray>? =
                GeckoResult.fromValue(BooleanArray(urls.size) { !isPrivate && isVisited(urls[it]) })
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(sess: GeckoSession, pageUrl: String) {
                if (sess !== session) return
                webManifest = null
                val host = runCatching { android.net.Uri.parse(pageUrl).host }.getOrNull()
                if (host == null || host != themeHost) themeColor = null
                themeHost = host
                AppLog.i("Page") { "начало загрузки ${AppLog.url(pageUrl, isPrivate)}" }
                navStartedAt = SystemClock.elapsedRealtime()
                paintLogged = false
                resetTranslation()
                resetReader()
                ReaderMode.reset(sess)
                expandChrome()
                loading = true
                progress = 0
                skipVisit = imageSearchPending && pageUrl.startsWith("data:text/html")
            }
            override fun onPageStop(sess: GeckoSession, success: Boolean) {
                if (sess !== session) return
                AppLog.i("Page") { "конец загрузки успех=$success за ${SystemClock.elapsedRealtime() - navStartedAt} мс ${AppLog.url(url, isPrivate)}" }
                loading = false
                clearRestoringNow()
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
                if (sess !== session) {
                    parkedOf(sess)?.state = state
                    return
                }
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
                if (sess !== session) {
                    val p = parkedOf(sess)
                    if (p != null && newUrl != null && newUrl != "about:blank" && !newUrl.startsWith("data:")) p.url = newUrl
                    return
                }
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
                    if (host != textScaleHost) {
                        textScaleHost = host
                        if (textScale != 100) textScale = 100
                    }
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
                return GeckoResult.fromValue(ErrorPages.dataUri(uri, error, offline = !Connectivity.online))
            }
            override fun onCanGoBack(sess: GeckoSession, value: Boolean) {
                if (sess === session) canGoBack = value else parkedOf(sess)?.canGoBack = value
            }
            override fun onCanGoForward(sess: GeckoSession, value: Boolean) {
                if (sess === session) canGoForward = value else parkedOf(sess)?.canGoForward = value
            }
            override fun onLoadRequest(
                sess: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                if (sess !== session) return null
                AppLog.i("Nav") { "запрос ${AppLog.url(request.uri, isPrivate)} жест=${request.hasUserGesture} редирект=${request.isRedirect}" }
                val now = SystemClock.elapsedRealtime()
                if (request.hasUserGesture) lastGestureAt = now
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
                if (sess === session) title = newTitle.orEmpty() else parkedOf(sess)?.title = newTitle.orEmpty()
            }
            override fun onWebAppManifest(sess: GeckoSession, manifest: org.json.JSONObject) {
                if (sess === session) webManifest = manifest
            }

            override fun onFirstContentfulPaint(sess: GeckoSession) {
                if (sess !== session || paintLogged) return
                paintLogged = true
                clearRestoringNow()
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
                if (sess === session && !closed) onPageClose(this@Tab)
                else if (sess === parked?.session) dropParked("страница закрыла себя")
            }
            override fun onKill(sess: GeckoSession) {
                AppLog.w("Tab", "onKill: процесс страницы убит системой, текущая сессия=${sess === session}, видимая=$visible")
                if (sess === session) onProcessGone() else if (sess === parked?.session) dropParked("процесс выгружен")
            }
            override fun onCrash(sess: GeckoSession) {
                AppLog.w("Tab", "onCrash: процесс страницы упал, текущая сессия=${sess === session}, видимая=$visible")
                if (sess === session) onProcessGone() else if (sess === parked?.session) dropParked("процесс упал")
            }
        }
        media.attach(s, isPrivate, { title }) { audible = it }
        if (muted) media.setMuted(s, true)
        return s
    }

    private fun onProcessGone() {
        if (closed) return
        if (visible) recover() else suspendAfterLoss()
    }

    fun restoreLostSession(foreground: Boolean) {
        if (closed || isSuspended || session.isOpen) return
        if (foreground) recover() else suspendAfterLoss()
    }

    private fun suspendAfterLoss() {
        val old = session
        pendingSuspendSession = null
        mainHandler.removeCallbacksAndMessages(old)
        lazyState = sessionState ?: lazyState
        forgetSession(old)
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

    private fun resetNavState() {
        loading = false
        fullscreen = false
        progress = 0
        canGoBack = false
        canGoForward = false
    }

    private fun resetLoadState() {
        resetNavState()
        secure = false
        secureException = false
        certIssuer = null
    }

    private fun resetToHome() {
        home = true
        clearErrorState()
        discardSavedSessionState()
    }

    private fun forgetSession(s: GeckoSession) {
        runCatching { onSessionGone(s) }.onFailure { AppLog.w("Tab", "onSessionGone упал", it) }
        media.forget(s)
        ThemeColor.forget(s)
        ReaderMode.forget(s)
    }

    private fun dispose(s: GeckoSession) {
        forgetSession(s)
        runCatching { s.close() }
    }

    private fun recoverInternal(old: GeckoSession) {
        if (closed || old !== session) return
        AppLog.w("Tab", "recoverInternal: пересоздаю сессию вкладки ${id.take(6)}")

        val target = url
        val now = SystemClock.elapsedRealtime()
        recoverStreak = if (lastRecoverAt != 0L && now - lastRecoverAt < RECOVERY_WINDOW_MS) recoverStreak + 1 else 1
        lastRecoverAt = now
        val giveUp = recoverStreak >= HOME_FALLBACK_STREAK
        val state = if (giveUp) null else sessionState ?: lazyState
        lazyState = null

        fun keepForRetry() {
            if (giveUp) resetToHome() else lazyState = state
        }

        val fresh = try {
            newSession()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось создать GeckoSession после сбоя; вкладка сохранена для повтора", e)
            dispose(old)
            resetLoadState()
            isSuspended = true
            keepForRetry()
            return
        }
        session = fresh
        isSuspended = false
        resetLoadState()
        lastScrollY = 0
        scrollAcc = 0
        expandChrome()

        try {
            if (runCatching { fresh.open(runtime) }.isFailure) {
                keepForRetry()
                dispose(fresh)
                isSuspended = true
                return
            }
            when {
                giveUp -> resetToHome()
                home -> Unit
                state != null && runCatching { fresh.restoreState(state) }.isSuccess -> Unit
                target.isNotBlank() -> {
                    clearErrorState()
                    discardSavedSessionState()
                    fresh.loadUri(target)
                }
                else -> home = true
            }
            if (!home && !giveUp) markRestoring()
            fresh.setFocused(visible && focused)
            applyPriority(fresh)
        } finally {
            dispose(old)
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

    fun setVisibility(active: Boolean, focused: Boolean = active): Boolean {
        mainHandler.removeCallbacks(releaseThumbnailRunnable)
        if (active) {
            pendingSuspendSession = null
            mainHandler.removeCallbacksAndMessages(session)
        } else if (!isPrivate) {
            mainHandler.postDelayed(releaseThumbnailRunnable, THUMBNAIL_RELEASE_DELAY_MS)
        }
        val nextFocused = focused && active
        val changed = visible != active || this.focused != nextFocused
        if (!changed) return false
        visible = active
        this.focused = nextFocused
        if (nextFocused) lastActivatedAt = SystemClock.elapsedRealtime()
        session.setFocused(nextFocused)
        applyPriority(session)
        return true
    }

    /** Вкладка сейчас на экране и в фокусе. */
    internal val isShown: Boolean get() = visible && focused

    private var priorityHold = false

    /** Удерживается ли сейчас высокий приоритет процесса, хотя вкладка не на экране (для журнала и тестов). */
    internal val priorityHeld: Boolean get() = priorityHold

    /**
     * Держать процесс страницы с высоким приоритетом, даже когда вкладка не на экране: недавние вкладки, вкладки
     * с набранным текстом и текущая вкладка свёрнутого браузера. Без этого Gecko отдаёт процессу неактивной вкладки
     * приоритет IDLE (BIND_WAIVE_PRIORITY), и система может выгрузить его в любой момент — страница перезагрузится.
     */
    fun setPriorityHold(on: Boolean) {
        if (priorityHold == on) return
        priorityHold = on
        applyPriority(session)
    }

    private fun applyPriority(target: GeckoSession) {
        if (closed) return
        val high = (visible && focused) || priorityHold
        runCatching { target.setPriorityHint(if (high) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT) }
    }

    /**
     * Просит движок сразу прислать свежее состояние страницы (onSessionStateChange), не дожидаясь своего
     * интервала (~10 с): набранный в формах текст попадёт в сохранённые вкладки до того, как процесс могут выгрузить.
     */
    fun flushSessionState() {
        if (closed || home || isSuspended || !session.isOpen) return
        runCatching { session.flushSessionState() }
    }

    /** Есть ли на странице введённые пользователем данные форм. Ответ приходит в главный поток. */
    fun checkFormData(onResult: (Boolean) -> Unit) {
        val target = session
        if (closed || home || isSuspended || !target.isOpen) {
            onResult(false)
            return
        }
        try {
            target.containsFormData().accept(
                { has -> if (target === session && !closed) onResult(has == true) },
                { onResult(false) },
            )
        } catch (e: Exception) {
            AppLog.w("Tab", "не удалось проверить данные форм", e)
            onResult(false)
        }
    }

    private var engineActiveSession: GeckoSession? = null
    private var engineActive = false

    // GeckoView гасит только показанную им сессию; открытые без показа (после сбоя процесса, расширением) стартуют
    // активными и тратят CPU и память. Вкладку с медиа не трогаем, чтобы не менять фоновое воспроизведение.
    fun syncEngineActive(keepActive: Boolean) {
        val s = session
        if (closed || !s.isOpen || s === pendingSuspendSession) return
        if (!visible && keepActive) return
        val want = visible
        if (engineActiveSession === s && engineActive == want) return
        engineActiveSession = s
        engineActive = want
        runCatching { s.setActive(want) }
    }

    fun updateThumbnail(image: androidx.compose.ui.graphics.ImageBitmap): Boolean {
        if (closed) return false
        mainHandler.removeCallbacks(releaseThumbnailRunnable)
        thumbnail = image
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
        forgetSession(target)
        target.setActive(false)
        target.setFocused(false)
        if (runCatching { target.close(); true }.getOrDefault(false)) {
            isSuspended = true
        }
    }

    fun suspend(): Boolean {
        if (closed || isSuspended || visible || focused || loading || fullscreen || !session.isOpen || pendingSuspendSession != null) return false
        dropParked("нехватка памяти")
        val target = session
        if (home) {
            forgetSession(target)
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
        engineActiveSession = target
        engineActive = false
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

    fun load(input: String, engine: SearchEngine = SearchEngines.current) {
        val target = toUrl(input, engine)
        if (home) {
            url = target
            title = ""
            errorPage = false
        }
        home = false
        session.loadUri(target)
    }

    fun loadImageSearch(page: String) {
        home = false
        loading = true
        imageSearchPending = true
        session.loadUri(page)
    }

    fun goHome() {
        if (home) return
        returnState = sessionState ?: lazyState
        returnUrl = url
        hasReturn = returnState != null || returnUrl.isNotBlank()
        val old = session
        val wasLive = old.isOpen && !isSuspended
        home = true
        if (!wasLive) {
            lazyState = null
            return
        }
        val fresh = try {
            newSession()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось создать пустую сессию для «домой»", e)
            return
        }
        try {
            fresh.open(runtime)
            fresh.setFocused(visible && focused)
            applyPriority(fresh)
        } catch (e: Exception) {
            dispose(fresh)
            Log.w(TAG, "Не удалось создать пустую сессию для «домой»", e)
            return
        }
        if (pendingSuspendSession === old) pendingSuspendSession = null
        mainHandler.removeCallbacksAndMessages(old)
        val keepAlive = !loading && !fullscreen && !media.isPlaying(old)
        val snapshot = if (keepAlive) snapshotPage(old) else null
        session = fresh
        old.setActive(false)
        old.setFocused(false)
        if (snapshot != null) {
            // Страница не закрывается, а ждёт в памяти: «Назад» с начальной страницы вернёт её мгновенно,
            // с прокруткой и набранным текстом. Если система её выгрузит или срок выйдет, сработает прежний путь
            // через сохранённое состояние (restoreState).
            dropParked(null)
            runCatching { old.setPriorityHint(GeckoSession.PRIORITY_DEFAULT) }
            runCatching { runtime.webExtensionController.setTabActive(old, false) }
            parked = snapshot
            mainHandler.postDelayed(dropParkedRunnable, PARKED_PAGE_MS)
        } else {
            forgetSession(old)
            runCatching { old.close() }
        }
        loading = false
        progress = 0
        canGoBack = false
        canGoForward = false
        fullscreen = false
        errorPage = false
        errorTarget = null
        themeColor = null
        themeHost = null
        resetReader()
        lazyState = null
        sessionState = null
        sessionStateVersion++
        onStateChanged()
    }

    fun returnToPage() {
        val st = returnState
        val target = returnUrl
        returnState = null
        returnUrl = ""
        hasReturn = false
        if (unpark()) return
        if (st == null && target.isBlank()) { home = false; return }
        if (target.isNotBlank()) { url = target; errorPage = false }
        val live = session.isOpen && !isSuspended
        if (live) {
            try {
                if (st != null) session.restoreState(st) else session.loadUri(target)
            } catch (e: Exception) {
                if (target.isNotBlank()) runCatching { session.loadUri(target) }
                Log.w(TAG, "Не удалось вернуть закрытую страницу", e)
            }
        } else {
            lazyState = st
        }
        home = false
    }

    private fun snapshotPage(s: GeckoSession) = ParkedPage(
        session = s, url = url, title = title, canGoBack = canGoBack, canGoForward = canGoForward,
        secure = secure, secureException = secureException, certIssuer = certIssuer,
        errorPage = errorPage, errorTarget = errorTarget, errorNetwork = errorNetwork,
        themeColor = themeColor, themeHost = themeHost, readerActive = readerActive,
        translateLang = translateLang, translated = translated, webManifest = webManifest,
        desktopMode = desktopMode, modeHost = modeHost, state = sessionState ?: lazyState,
    )

    /** Закрывает страницу, ждущую возврата с начальной. [reason] == null: без записи в журнал (замена новой). */
    private fun dropParked(reason: String?) {
        val p = parked ?: return
        parked = null
        mainHandler.removeCallbacks(dropParkedRunnable)
        if (reason != null) AppLog.i("Tab") { "вкладка ${id.take(6)}: отложенная страница закрыта ($reason)" }
        dispose(p.session)
    }

    /** Возвращает живую страницу, с которой ушли «Домой». false — её уже нет, нужен обычный путь. */
    private fun unpark(): Boolean {
        val p = parked ?: return false
        parked = null
        mainHandler.removeCallbacks(dropParkedRunnable)
        if (closed || !p.session.isOpen) {
            dispose(p.session)
            return false
        }
        val blank = session
        if (pendingSuspendSession === blank) pendingSuspendSession = null
        mainHandler.removeCallbacksAndMessages(blank)
        session = p.session
        dispose(blank)
        url = p.url
        title = p.title
        canGoBack = p.canGoBack
        canGoForward = p.canGoForward
        secure = p.secure
        secureException = p.secureException
        certIssuer = p.certIssuer
        errorPage = p.errorPage
        errorTarget = p.errorTarget
        errorNetwork = p.errorNetwork
        themeColor = p.themeColor
        themeHost = p.themeHost
        readerActive = p.readerActive
        translateLang = p.translateLang
        translated = p.translated
        translating = false
        webManifest = p.webManifest
        desktopMode = p.desktopMode
        modeHost = p.modeHost
        loading = false
        progress = 100
        fullscreen = false
        sessionState = p.state
        lazyState = null
        sessionStateVersion++
        isSuspended = false
        home = false
        p.session.setFocused(visible && focused)
        applyPriority(p.session)
        engineActiveSession = null
        syncEngineActive(keepActive = false)
        AppLog.i("Tab") { "вкладка ${id.take(6)}: страница возвращена без перезагрузки" }
        onStateChanged()
        return true
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
        val target = if (value) desktopVariantUrl(url) else null
        applyDesktop(value, reload = target == null)
        saveDesktop(host, value)
        if (target != null) {
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

    private fun desktopVariantUrl(url: String): String? {
        val uri = android.net.Uri.parse(url)
        val authority = uri.authority?.lowercase(java.util.Locale.ROOT) ?: return null
        val prefix = listOf("m.", "mobile.").firstOrNull { authority.startsWith(it) } ?: return null
        return uri.buildUpon().authority(authority.substring(prefix.length)).toString()
    }

    fun close() {
        if (closed) return
        dropParked(null)
        pendingSuspendSession = null
        mainHandler.removeCallbacksAndMessages(session)
        mainHandler.removeCallbacks(releaseThumbnailRunnable)
        thumbnail = null
        closed = true
        visible = false
        focused = false
        isSuspended = false
        forgetSession(session)
        runCatching { session.close() }
    }
}

