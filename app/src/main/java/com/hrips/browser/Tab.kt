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

/** Компилируется один раз: onSecurityChange приходит на каждую загрузку каждой вкладки. */
private val ISSUER_CN = Regex("(?:^|,)CN=([^,]+)")

/** Как показывать защиту страницы в адресной строке. */
enum class Trust { NONE, SECURE, WARNING }

/** Одна вкладка = одна GeckoSession + наблюдаемое состояние для Compose. */
class Tab(
    private val runtime: GeckoRuntime,
    rawStartUrl: String,
    tabId: String = UUID.randomUUID().toString(),
    /** Нужна ли версия для ПК этому сайту (ключ - siteKey). Решает Store, не вкладка. */
    private val desktopFor: (host: String?) -> Boolean,
    /** Пользователь переключил режим на сайте: запомнить (в обычной вкладке). */
    private val onDesktopSaved: (host: String, on: Boolean) -> Unit,
    private val onVisited: (url: String, title: String) -> Unit,
    /** Called after Gecko publishes a newer session state so Browser can persist it. */
    private val onStateChanged: () -> Unit = {},
    private val onDownload: (Tab, WebResponse) -> Unit,
    private val permissionDelegate: GeckoSession.PermissionDelegate,
    private val promptDelegate: GeckoSession.PromptDelegate,
    private val onNewWindow: (String) -> GeckoSession,
    private val onMenu: (ContextInfo) -> Unit,
    private val onExternal: (uri: String, hasUserGesture: Boolean) -> Boolean,
    private val media: MediaHub,
    /** Состояние сессии с прошлого запуска (история вкладки, прокрутка). Загружается, когда вкладку откроют. */
    savedState: String? = null,
    savedTitle: String = "",
    /** Приватная вкладка: отдельная сессия движка, всё хранится только в памяти */
    val isPrivate: Boolean = false,
    openNow: Boolean = true,
    /**
     * Восстановленная фоновая вкладка: сессию движка не открываем, пока вкладку не выберут
     * (так же ведёт себя выгруженная вкладка, см. [ensureLoaded]). Сохранённое состояние не разбирается заранее.
     */
    deferOpen: Boolean = false,
) {
    /** Stable identity for Compose/UI state and session persistence. */
    val id: String = tabId

    private val startUrl = if (rawStartUrl == "about:blank") "" else rawStartUrl

    /** Вкладку открыла страница (target=_blank, window.open), а не пользователь. */
    val popup = !openNow
    /** Вкладка-родитель: на неё возвращаемся, если эта оказалась пустой (например, только ради скачивания). */
    var parent: Tab? = null
    /** Вкладка, из которой пользователь открыл эту: жест «назад» без истории закрывает эту вкладку и возвращает на неё. */
    var returnTo: Tab? = null

    /** Режим текущего сайта. Не глобальный: при переходе на другой сайт выбирается заново (см. onLocationChange). */
    var desktopMode by mutableStateOf(desktopFor(siteKey(rawStartUrl)))
        private set
    /** Сайт, для которого сейчас выставлен режим. Нужен, чтобы менять режим только при смене сайта. */
    private var modeHost: String? = siteKey(rawStartUrl)
    /** Приватные вкладки ничего не запоминают: ручной выбор живёт только в этой вкладке. */
    private val localDesktop = HashMap<String, Boolean>()
    /** Снимок страницы для карточки в переключателе вкладок (только в памяти) */
    var thumbnail by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    /** id группы вкладок (см. Browser.groups); null - вкладка вне групп. Приватные вкладки в группы не входят. */
    var group by mutableStateOf<String?>(null)
    /** true = показываем нативную стартовую страницу вместо веб-страницы */
    var home by mutableStateOf(!popup && startUrl.isBlank())
    var url by mutableStateOf(startUrl)
    var title by mutableStateOf(savedTitle)
    /** Показана страница ошибки (нет сети, плохой сертификат и т.п.), а url - адрес, который не открылся */
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

    /** Последнее состояние сессии от движка. Нужно, чтобы сохранить вкладку целиком и поднять её после сбоя. */
    private var sessionState: GeckoSession.SessionState? = null
    /** Растёт при каждом новом состоянии от движка: по нему кэш строки состояния понимает, что устарел. */
    private var sessionStateVersion = 0
    /**
     * Строка состояния с прошлого запуска. Разбирается только когда вкладка действительно нужна:
     * при старте с десятками вкладок разбор JSON всех состояний на главном потоке заметно задерживал первый кадр.
     */
    private var savedStateRaw: String? = savedState?.takeIf { it.isNotBlank() }
    private var lazyStateParsed: GeckoSession.SessionState? = null
    /** Восстановленное состояние ждёт, пока вкладку не откроют (фоновые вкладки не грузятся при запуске). */
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
    /** Кэш сериализованного состояния для [snapshot]: toString() у SessionState дорогой и вызывался для каждой вкладки при каждом сохранении. */
    private var cachedStateText: String? = null
    private var cachedStateVersion = -1
    private var cachedStateOwner: GeckoSession.SessionState? = null
    private var lastRecover = 0L
    private var recoverStreak = 0
    private var recovering = false
    private var closed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSuspendSession: GeckoSession? = null
    private var visible = false
    private var focused = false
    private var lastActivatedAt = SystemClock.elapsedRealtime()

    /** true, если движок вкладки временно выгружен из памяти, но состояние сохранено. */
    var isSuspended by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    /** Страница (например, видео) попросила полноэкранный режим */
    var fullscreen by mutableStateOf(false)

    /** Телефон: нижняя панель спрятана, пока страницу листают вниз; листнули вверх или вернулись наверх - снова видна */
    var chromeHidden by mutableStateOf(false)
    private var lastScrollY = 0
    private var scrollAcc = 0

    private fun onPageScrolled(y: Int) {
        val dy = y - lastScrollY
        lastScrollY = y
        if (y <= 0) { scrollAcc = 0; chromeHidden = false; return }
        if (dy == 0) return
        if ((scrollAcc > 0) != (dy > 0)) scrollAcc = 0 // сменили направление: считаем заново
        scrollAcc += dy
        if (scrollAcc > 64) chromeHidden = true else if (scrollAcc < -24) chromeHidden = false
    }

    /** Сессию можно заменить (после падения или убийства процесса движка), поэтому это состояние. */
    var session by mutableStateOf(newSession())
        private set

    init {
        // openNow = false: вкладка создана страницей (target=_blank, window.open), её откроет сам движок
        if (openNow && deferOpen) {
            // Сессия остаётся неоткрытой: поднимется в ensureLoaded() при первом выборе вкладки.
            // Пустую сессию из newSession() отвязываем от медиа-хаба, чтобы он её не держал.
            media.forget(session)
            isSuspended = true
        } else if (openNow) {
            session.open(runtime)
            if (lazyState == null && startUrl.isNotBlank()) session.loadUri(startUrl)
        }
    }

    /** Загружает отложенное или выгруженное состояние, когда вкладка стала видимой. */
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

    /** Что сохранить о вкладке на диск. */
    fun snapshot(): TabSnap = TabSnap(
        if (home) "" else url,
        title,
        if (home) null else stateText(),
        group,
        id,
    )

    /** Строка состояния для записи на диск. Не пересобирается, пока движок не прислал новое состояние. */
    private fun stateText(): String? {
        val live = sessionState
        if (live == null) {
            // Вкладка ещё не открывалась в этом запуске: отдаём сохранённую строку как есть, без разбора и повторной сериализации
            savedStateRaw?.let { return it }
        }
        val st = live ?: lazyStateParsed ?: return null
        if (cachedStateText == null || cachedStateOwner !== st || cachedStateVersion != sessionStateVersion) {
            cachedStateText = st.toString()
            cachedStateOwner = st
            cachedStateVersion = sessionStateVersion
        }
        return cachedStateText
    }

    private fun uaMode(desktop: Boolean) =
        if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE

    private fun newSession(): GeckoSession {
        // Меняем только User-Agent; вьюпорт остаётся мобильным, чтобы страницы масштабировались под экран
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
            }
            override fun onPageStop(sess: GeckoSession, success: Boolean) {
                if (sess !== session) return
                loading = false
                if (success && !errorPage) onVisited(url, title)
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
                // Keep private state in memory too. It is never persisted to disk, but it lets us
                // recover history/scroll position after a Gecko content-process kill.
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
                // about:blank появляется у пустой вкладки, это не страница пользователя
                if (newUrl == null || newUrl == "about:blank") return
                if (newUrl.startsWith("data:text/html") && errorTarget != null) {
                    // Наша страница ошибки: в адресной строке остаётся адрес, который не открылся
                    errorPage = true
                    errorTarget?.let { if (it.isNotBlank()) url = it }
                } else {
                    errorPage = false
                    errorTarget = null
                    url = newUrl
                    // Другой сайт: берём его собственный режим (мобильный, если не включали версию для ПК)
                    val host = siteKey(newUrl)
                    if (host != null && host != modeHost) {
                        modeHost = host
                        val want = if (isPrivate) localDesktop[host] ?: desktopFor(host) else desktopFor(host)
                        if (want != desktopMode) applyDesktop(want, reload = true)
                    }
                }
            }
            // Движок не смог загрузить страницу: показываем свою страницу ошибки вместо пустой вкладки
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
            // Ссылки не для браузера (tel:, mailto:, intent:, схемы приложений) уходят в ExternalLinks
            override fun onLoadRequest(
                sess: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                if (sess !== session) return null
                return if (onExternal(request.uri, request.hasUserGesture)) GeckoResult.fromValue(AllowOrDeny.DENY) else null
            }
            // Ссылки с target="_blank" и window.open: открываем в новой вкладке
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
            // Долгое нажатие или правая кнопка мыши на ссылке / картинке / видео / аудио
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
            // Ответ, который нельзя показать как страницу (файл для скачивания)
            override fun onExternalResponse(sess: GeckoSession, response: WebResponse) {
                onDownload(this@Tab, response)
            }
            // Процесс страницы убит системой (например, в фоне) или упал: поднимаем вкладку заново
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

    /** Создаёт новую сессию и загружает ту же страницу. */
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
        // Если вкладка падает снова и снова (страница убивает движок), не зацикливаемся: открываем стартовую
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

    /** UI-safe browser navigation methods. Compose should not depend on GeckoSession directly. */
    fun goBack() = session.goBack()

    fun goForward() = session.goForward()

    fun stopLoading() = session.stop()

    fun reload() = session.reload()

    fun saveAsPdf() = session.saveAsPdf()

    fun reloadOrStop() {
        if (loading) stopLoading() else reload()
    }

    fun exitFullscreen() = session.exitFullScreen()

    /** Marks whether this tab is currently visible/focused in the browser UI. Returns true when state changed. */
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

    /** Последняя активность используется менеджером памяти для выбора самой старой фоновой вкладки. */
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

    /**
     * Выгружает движок вкладки из памяти, сохраняя SessionState. Невидимые, не загружающиеся
     * вкладки можно поднять обратно через [ensureLoaded].
     */
    fun suspend(): Boolean {
        if (closed || isSuspended || visible || focused || loading || fullscreen || !session.isOpen || pendingSuspendSession != null) return false
        val target = session
        // A blank tab has no useful Gecko state to persist. Closing it directly is both safe and
        // cheaper than waiting for a state callback that may never arrive.
        if (home) {
            media.forget(target)
            target.setActive(false)
            target.setFocused(false)
            return if (runCatching { target.close(); true }.getOrDefault(false)) {
                isSuspended = true
                true
            } else false
        }
        // Never evict a tab for which we cannot reconstruct a session after process death.
        if (lazyState == null && sessionState == null) return false
        pendingSuspendSession = target
        // setActive(false) triggers Gecko's asynchronous state flush. We finalize only after the
        // matching onSessionStateChange callback. The short fallback prevents a stuck pending state.
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

    fun load(input: String, engine: SearchEngine = SearchEngines.current) {
        home = false
        session.loadUri(toUrl(input, engine))
    }

    fun goHome() {
        home = true
    }

    /** Сайт текущей страницы (null на стартовой и служебных страницах). */
    fun siteHost(): String? = if (home) null else siteKey(url)

    /** Меняет режим сессии. Перезагрузка не нужна, если страницы ещё нет или вкладка не загружена. */
    fun applyDesktop(value: Boolean, reload: Boolean) {
        desktopMode = value
        session.settings.setUserAgentMode(uaMode(value))
        if (reload && !home && session.isOpen && lazyState == null) session.reload()
    }

    /** Выбор пользователя: действует только на этот сайт и запоминается навсегда (кроме приватной вкладки). */
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

