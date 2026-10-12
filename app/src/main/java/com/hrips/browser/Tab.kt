package com.hrips.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.util.UUID

private val ISSUER_CN = Regex("(?:^|,)CN=([^,]+)")

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
    private val onDownload: (Tab, WebResponse) -> Unit,
    private val permissionDelegate: GeckoSession.PermissionDelegate,
    private val promptDelegate: GeckoSession.PromptDelegate,
    private val onNewWindow: (String) -> GeckoSession,
    private val onMenu: (ContextInfo) -> Unit,
    private val onExternal: (uri: String, hasUserGesture: Boolean) -> Boolean,
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

    var desktopMode by mutableStateOf(desktopFor(siteKey(rawStartUrl)))
        private set
    private var modeHost: String? = siteKey(rawStartUrl)
    private val localDesktop = HashMap<String, Boolean>()
    var thumbnail by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var home by mutableStateOf(!popup && startUrl.isBlank())
    var url by mutableStateOf(startUrl)
    var title by mutableStateOf(savedTitle)
    var errorPage by mutableStateOf(false)
        private set
    private var errorTarget: String? = null
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
                lazyStateParsed = GeckoSession.SessionState.fromString(raw)
            }
            return lazyStateParsed
        }
        set(value) {
            savedStateRaw = null
            lazyStateParsed = value
        }
    private val stateCache = StateCache()
    private var lastRecover = 0L
    private var recoverStreak = 0
    private var recovering = false
    private var closed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSuspendSession: GeckoSession? = null
    private var visible = false
    private var focused = false
    private var lastActivatedAt = SystemClock.elapsedRealtime()

    var isSuspended by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)

    var chromeHidden by mutableStateOf(false)
    private var lastScrollY = 0
    private var scrollAcc = 0

    private fun onPageScrolled(y: Int) {
        val dy = y - lastScrollY
        lastScrollY = y
        if (y <= 0) { scrollAcc = 0; chromeHidden = false; return }
        if (dy == 0) return
        if ((scrollAcc > 0) != (dy > 0)) scrollAcc = 0
        scrollAcc += dy
        if (scrollAcc > 64) chromeHidden = true else if (scrollAcc < -24) chromeHidden = false
    }

    var session by mutableStateOf(newSession())
        private set

    init {
        if (openNow && deferOpen) {
            media.forget(session)
            isSuspended = true
        } else if (openNow) {
            session.open(runtime)
            if (lazyState == null && startUrl.isNotBlank()) session.loadUri(startUrl)
        }
    }

    fun ensureLoaded() {
        if (closed) return
        if (session.isOpen) {
            val st = lazyState ?: return
            lazyState = null
            session.restoreState(st)
            return
        }
        if (!isSuspended) return

        val st = lazyState ?: sessionState
        val target = url
        val fresh = newSession()
        session = fresh
        isSuspended = false
        loading = false
        progress = 0
        canGoBack = false
        canGoForward = false
        fullscreen = false
        fresh.open(runtime)
        if (!home && st != null) {
            lazyState = null
            sessionState = st
            fresh.restoreState(st)
        } else if (!home && target.isNotBlank()) {
            fresh.loadUri(target)
        }
        fresh.setActive(visible)
        fresh.setFocused(visible && focused)
        fresh.setPriorityHint(if (visible && focused) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
    }

    fun snapshot(): TabSnap = saveParts().build()

    fun saveParts(): TabSaveParts {
        val live = sessionState
        val raw = if (!home && live == null) savedStateRaw else null
        val st = if (home || raw != null) null else (live ?: lazyStateParsed)
        return TabSaveParts(if (home) "" else url, title, id, raw, st, sessionStateVersion, stateCache)
    }

    private fun uaMode(desktop: Boolean) =
        if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE

    private fun newSession(): GeckoSession {
        val s = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(uaMode(desktopMode))
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .usePrivateMode(isPrivate)
                .build()
        )
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(sess: GeckoSession, pageUrl: String) {
                if (sess !== session) return
                loading = true
                progress = 0
                skipVisit = imageSearchPending && pageUrl.startsWith("data:text/html")
            }
            override fun onPageStop(sess: GeckoSession, success: Boolean) {
                if (sess !== session) return
                loading = false
                if (success && !errorPage && !skipVisit) onVisited(url, title)
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
                onStateChanged()
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
                return if (onExternal(request.uri, request.hasUserGesture)) GeckoResult.fromValue(AllowOrDeny.DENY) else null
            }
            override fun onNewSession(sess: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                return GeckoResult.fromValue(onNewWindow(uri))
            }
        }
        s.permissionDelegate = permissionDelegate
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
            override fun onFullScreen(sess: GeckoSession, fullScreen: Boolean) {
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
                onDownload(this@Tab, response)
            }
            override fun onKill(sess: GeckoSession) {
                if (sess === session) recover()
            }
            override fun onCrash(sess: GeckoSession) {
                if (sess === session) recover()
            }
        }
        media.attach(s, isPrivate) { title }
        return s
    }

    fun recover() {
        if (closed || recovering) return
        pendingSuspendSession = null
        mainHandler.removeCallbacksAndMessages(session)
        recovering = true
        try {
            recoverInternal()
        } finally {
            recovering = false
        }
    }

    private fun recoverInternal() {
        val old = session
        val target = url
        val now = System.currentTimeMillis()
        recoverStreak = if (now - lastRecover < 8000) recoverStreak + 1 else 0
        lastRecover = now
        val state = if (recoverStreak < 2) (sessionState ?: lazyState) else null
        lazyState = null
        val fresh = newSession()
        session = fresh
        isSuspended = false
        fresh.open(runtime)
        loading = false
        fullscreen = false
        progress = 0
        canGoBack = false
        canGoForward = false
        if (recoverStreak >= 3) {
            home = true
        } else if (!home && state != null) {
            fresh.restoreState(state)
        } else if (!home && target.isNotBlank()) {
            fresh.loadUri(target)
        }
        fresh.setActive(visible)
        fresh.setFocused(visible && focused)
        fresh.setPriorityHint(if (visible && focused) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
        media.forget(old)
        runCatching { old.close() }
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

    fun saveAsPdf() = session.saveAsPdf()

    fun reloadOrStop() {
        if (loading) stopLoading() else reload()
    }

    fun exitFullscreen() = session.exitFullScreen()

    fun setVisibility(active: Boolean, focused: Boolean = active): Boolean {
        if (active) {
            pendingSuspendSession = null
            mainHandler.removeCallbacksAndMessages(session)
        }
        val nextFocused = focused && active
        val changed = visible != active || this.focused != nextFocused
        if (!changed) return false
        visible = active
        this.focused = nextFocused
        if (nextFocused) lastActivatedAt = SystemClock.elapsedRealtime()
        session.setActive(active)
        session.setFocused(nextFocused)
        session.setPriorityHint(if (active && nextFocused) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
        return true
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

    fun load(input: String, engine: SearchEngine = SearchEngines.current) {
        home = false
        session.loadUri(toUrl(input, engine))
    }

    fun loadImageSearch(page: String) {
        home = false
        loading = true
        imageSearchPending = true
        session.loadUri(page)
    }

    fun goHome() {
        home = true
    }

    fun siteHost(): String? = if (home) null else siteKey(url)

    fun applyDesktop(value: Boolean, reload: Boolean) {
        desktopMode = value
        session.settings.setUserAgentMode(uaMode(value))
        if (reload && !home && session.isOpen && lazyState == null) session.reload()
    }

    fun setDesktop(value: Boolean) {
        val host = siteHost() ?: return
        modeHost = host
        applyDesktop(value, reload = true)
        if (isPrivate) localDesktop[host] = value else onDesktopSaved(host, value)
    }

    fun close() {
        if (closed) return
        pendingSuspendSession = null
        mainHandler.removeCallbacksAndMessages(session)
        closed = true
        visible = false
        focused = false
        isSuspended = false
        media.forget(session)
        runCatching { session.close() }
    }
}

