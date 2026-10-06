package com.hrips.browser

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse


/** Настройки движка только для приватных вкладок: куки каждого сайта изолированы, защита от отпечатков включена. */
fun applyPrivateDefaults(runtime: GeckoRuntime) {
    runtime.settings.contentBlocking.setCookieBehaviorPrivateMode(
        ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
    )
    runtime.settings.setFingerprintingProtectionPrivateBrowsing(true)
}

fun applyTrackingProtection(runtime: GeckoRuntime, on: Boolean) {
    runtime.settings.contentBlocking.setAntiTracking(
        if (on) ContentBlocking.AntiTracking.DEFAULT else ContentBlocking.AntiTracking.NONE
    )
}

class SearchEngine(
    val name: String,
    val template: String,
    /** Адрес подсказок в формате OpenSearch: [запрос, [подсказки...]] */
    val suggest: String,
    /** Сайт движка: оттуда берётся логотип */
    val host: String,
    val color: Long,
)

object SearchEngines {
    val all = listOf(
        SearchEngine("Яндекс", "https://yandex.ru/search/?text=", "https://suggest.yandex.ru/suggest-ff.cgi?uil=ru&part=", "ya.ru", 0xFFFC3F1D),
        SearchEngine("Google", "https://www.google.com/search?q=", "https://suggestqueries.google.com/complete/search?client=firefox&q=", "www.google.com", 0xFF4285F4),
        SearchEngine("DuckDuckGo", "https://duckduckgo.com/?q=", "https://duckduckgo.com/ac/?type=list&q=", "duckduckgo.com", 0xFFDE5833),
        SearchEngine("Bing", "https://www.bing.com/search?q=", "https://api.bing.com/osjson.aspx?query=", "www.bing.com", 0xFF008373),
        SearchEngine("Ecosia", "https://www.ecosia.org/search?q=", "https://ac.ecosia.org/autocomplete?type=list&q=", "www.ecosia.org", 0xFF3F8F3B),
        SearchEngine("Qwant", "https://www.qwant.com/?q=", "https://api.qwant.com/api/suggest/?client=opensearch&q=", "www.qwant.com", 0xFF2B5BE0),
        SearchEngine("Startpage", "https://www.startpage.com/do/search?q=", "https://www.startpage.com/osuggestions?q=", "www.startpage.com", 0xFF4A5CE8),
        SearchEngine("Википедия", "https://ru.wikipedia.org/w/index.php?search=", "https://ru.wikipedia.org/w/api.php?action=opensearch&limit=8&search=", "ru.wikipedia.org", 0xFF5F6368),
    )
    var current by mutableStateOf(all[0])
}

/** Если [url] - страница результатов известного поисковика, возвращает движок и текст запроса. */
fun parseSearch(url: String): Pair<SearchEngine, String>? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    if (uri.scheme != "http" && uri.scheme != "https") return null
    for (e in SearchEngines.all) {
        val base = Uri.parse(e.template).host.orEmpty().split('.').takeLast(2).joinToString(".")
        if (host != base && !host.endsWith(".$base")) continue
        val q = listOf("text", "q", "query", "search").firstNotNullOfOrNull { p ->
            uri.getQueryParameter(p)?.takeIf { it.isNotBlank() }
        } ?: continue
        return e to q
    }
    return null
}

/** true, если ввод надо искать, а не открывать как адрес. */
fun isSearch(input: String): Boolean {
    val t = input.trim()
    return !(t.contains("://") || t.startsWith("about:") || (!t.contains(' ') && t.contains('.')))
}

/** Превращает ввод в адресной строке в URL или поисковый запрос. [engine] - разовый выбор, иначе поиск по умолчанию. */
fun toUrl(input: String, engine: SearchEngine = SearchEngines.current): String {
    val t = input.trim()
    return when {
        t.contains("://") || t.startsWith("about:") -> t
        !isSearch(t) -> "https://$t"
        else -> engine.template + Uri.encode(t)
    }
}

/** Как показывать защиту страницы в адресной строке. */
enum class Trust { NONE, SECURE, WARNING }

/** Одна вкладка = одна GeckoSession + наблюдаемое состояние для Compose. */
class Tab(
    private val runtime: GeckoRuntime,
    rawStartUrl: String,
    desktop: Boolean,
    private val onVisited: (url: String, title: String) -> Unit,
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
) {
    private val startUrl = if (rawStartUrl == "about:blank") "" else rawStartUrl

    /** Вкладку открыла страница (target=_blank, window.open), а не пользователь. */
    val popup = !openNow
    /** Вкладка-родитель: на неё возвращаемся, если эта оказалась пустой (например, только ради скачивания). */
    var parent: Tab? = null

    var desktopMode by mutableStateOf(desktop)
    /** Снимок страницы для карточки в переключателе вкладок (только в памяти) */
    var thumbnail by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
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
    /** Восстановленное состояние ждёт, пока вкладку не откроют (фоновые вкладки не грузятся при запуске). */
    private var lazyState: GeckoSession.SessionState? = GeckoSession.SessionState.fromString(savedState)
    private var lastRecover = 0L
    private var recoverStreak = 0
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    /** Страница (например, видео) попросила полноэкранный режим */
    var fullscreen by mutableStateOf(false)

    /** Сессию можно заменить (после падения или убийства процесса движка), поэтому это состояние. */
    var session by mutableStateOf(newSession())
        private set

    init {
        // openNow = false: вкладка создана страницей (target=_blank, window.open), её откроет сам движок
        if (openNow) {
            session.open(runtime)
            if (lazyState == null && startUrl.isNotBlank()) session.loadUri(startUrl)
        }
    }

    /** Загружает отложенное состояние (когда вкладка стала видимой). */
    fun ensureLoaded() {
        val st = lazyState ?: return
        lazyState = null
        session.restoreState(st)
    }

    /** Что сохранить о вкладке на диск. */
    fun snapshot(): TabSnap = TabSnap(
        if (home) "" else url,
        title,
        if (home) null else (sessionState ?: lazyState)?.toString(),
    )

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
                    ?.let { Regex("(?:^|,)CN=([^,]+)").find(it)?.groupValues?.get(1) }
            }
            override fun onSessionStateChange(sess: GeckoSession, state: GeckoSession.SessionState) {
                if (sess === session && !isPrivate) sessionState = state
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
        media.forget(old)
        runCatching { old.close() }
    }

    fun load(input: String, engine: SearchEngine = SearchEngines.current) {
        home = false
        session.loadUri(toUrl(input, engine))
    }

    fun goHome() {
        home = true
    }

    fun setDesktop(value: Boolean) {
        desktopMode = value
        session.settings.setUserAgentMode(uaMode(value))
        if (!home) session.reload()
    }

    fun close() {
        media.forget(session)
        session.close()
    }
}

class Browser(
    val runtime: GeckoRuntime,
    val store: Store,
    private val desktopByDefault: Boolean,
    val downloads: Downloads,
    val permissions: Permissions,
    val prompts: Prompts,
    val adBlock: AdBlock,
    val external: ExternalLinks,
    private val media: MediaHub,
) {
    val tabs = mutableStateListOf<Tab>()
    /** Открытое контекстное меню (null = нет). Рисуется в BrowserScreen. */
    var contextMenu by mutableStateOf<ContextInfo?>(null)
    private val executor by lazy { GeckoWebExecutor(runtime) }
    var currentIndex by mutableIntStateOf(0)
    val current: Tab get() = tabs[currentIndex]

    /** Скачивание по адресу (из контекстного меню): запрос идёт через движок, дальше обычная плашка "Скачать файл?". */
    fun saveUrl(uri: String, referrer: String?, incognito: Boolean = false) {
        fetch(uri, referrer, incognito) { r ->
            if (r != null && r.statusCode in 200..299) downloads.request(r, incognito) else downloads.toast("Не удалось скачать файл")
        }
    }

    /** Запрос через движок (с cookies и referer страницы). Поток ответа читать не в главном потоке. */
    fun fetch(uri: String, referrer: String?, incognito: Boolean = false, onDone: (WebResponse?) -> Unit) {
        val req = WebRequest.Builder(uri).apply { if (!referrer.isNullOrBlank()) referrer(referrer) }.build()
        val flags = if (incognito) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        val result = try { executor.fetch(req, flags) } catch (e: Throwable) { null }
        if (result == null) {
            onDone(null)
            return
        }
        result.accept({ onDone(it) }, { onDone(null) })
    }

    /** Очистка данных сайтов. История браузера чистится отдельно, в Store. */
    fun clearData(cookies: Boolean, cache: Boolean, onDone: () -> Unit) {
        var flags = 0L
        if (cookies) flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES
        if (cache) flags = flags or StorageController.ClearFlags.NETWORK_CACHE or StorageController.ClearFlags.IMAGE_CACHE
        if (flags == 0L) {
            onDone()
            return
        }
        runtime.storageController.clearData(flags).accept({ onDone() }, { onDone() })
    }

    /** Сообщает расширениям, какая вкладка сейчас видна. */
    fun setTabActive(session: GeckoSession, active: Boolean) {
        runtime.webExtensionController.setTabActive(session, active)
    }

    private fun create(
        url: String,
        openNow: Boolean = true,
        isPrivate: Boolean = false,
        savedState: String? = null,
        savedTitle: String = "",
    ) = Tab(
        runtime, url, desktopByDefault,
        // В приватной вкладке история не пишется
        onVisited = { u, t -> if (!isPrivate) store.addHistory(u, t) },
        onDownload = { tab, response -> handleDownload(tab, response) },
        permissionDelegate = permissions.delegate,
        promptDelegate = prompts.delegate,
        onNewWindow = { openPopup(it) },
        onMenu = { contextMenu = it },
        onExternal = { u, g -> external.handle(u, g) },
        media = media,
        savedState = savedState,
        savedTitle = savedTitle,
        isPrivate = isPrivate,
        openNow = openNow,
    )

    /**
     * Кнопка "Скачать" часто открывает новую пустую вкладку, а файл приходит уже в неё.
     * Раньше такая вкладка превращалась в стартовую страницу и оставалась висеть.
     * Теперь пустую вкладку сразу убираем из списка и возвращаемся на ту, откуда пришли.
     * Саму сессию закрываем только когда файл дочитан до конца: поток данных принадлежит ей.
     */
    private fun handleDownload(tab: Tab, response: WebResponse) {
        val blankPopup = tab.popup && !tab.canGoBack && tab.title.isBlank() && tabs.contains(tab)
        if (blankPopup) {
            val old = tabs.indexOf(tab)
            val parentIndex = tab.parent?.let { tabs.indexOf(it) } ?: -1
            tabs.removeAt(old)
            if (tabs.isEmpty()) {
                newTab()
            } else {
                currentIndex = if (parentIndex >= 0) parentIndex else (old - 1).coerceIn(0, tabs.lastIndex)
            }
            if (tab.isPrivate) privateTabRemoved()
        }
        downloads.request(response, tab.isPrivate) { if (blankPopup) tab.close() }
    }

    /** Вкладка, которую просит открыть страница. Сессию возвращаем неоткрытой, движок откроет её сам. */
    private fun openPopup(uri: String): GeckoSession {
        val opener = tabs.getOrNull(currentIndex)
        // Окно, открытое из приватной вкладки, тоже приватное
        val tab = create(uri, openNow = false, isPrivate = opener?.isPrivate == true)
        tab.parent = opener
        tabs.add(tab)
        currentIndex = tabs.lastIndex
        return tab.session
    }

    fun setTracking(on: Boolean) {
        store.updateTracking(on)
        applyTrackingProtection(runtime, on)
    }

    fun newTab(url: String = "", incognito: Boolean = false) {
        tabs.add(create(url, isPrivate = incognito))
        currentIndex = tabs.lastIndex
    }

    /** Переставляет вкладку (перетаскивание в полосе вкладок), выбранная остаётся выбранной. */
    fun moveTab(from: Int, to: Int) {
        if (from == to || from !in tabs.indices || to !in tabs.indices) return
        val cur = tabs[currentIndex]
        tabs.add(to, tabs.removeAt(from))
        currentIndex = tabs.indexOf(cur)
    }

    /** Переключается на вкладку сайта (нужно, когда нажали на его уведомление). */
    fun focusSite(source: String?) {
        val host = source?.let { Uri.parse(it).host ?: it } ?: return
        val i = tabs.indexOfFirst { it.url.contains(host) }
        if (i >= 0) currentIndex = i
    }

    fun closeTab(index: Int) {
        val closed = tabs[index]
        closed.close()
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            newTab()
        } else {
            if (index < currentIndex) currentIndex--
            currentIndex = currentIndex.coerceIn(0, tabs.lastIndex)
        }
        if (closed.isPrivate) privateTabRemoved()
    }

    val privateCount: Int get() = tabs.count { it.isPrivate }

    /** Закрыть все приватные вкладки сразу. Выбранной остаётся прежняя обычная вкладка, если она была. */
    fun closePrivateTabs() {
        val doomed = tabs.filter { it.isPrivate }
        if (doomed.isEmpty()) return
        val keep = tabs.getOrNull(currentIndex)?.takeIf { !it.isPrivate }
        doomed.forEach { it.close() }
        tabs.removeAll(doomed)
        if (tabs.isEmpty()) {
            newTab()
        } else {
            currentIndex = keep?.let { tabs.indexOf(it) }?.takeIf { it >= 0 } ?: tabs.lastIndex
        }
        privateTabRemoved()
    }

    /** Если приватных вкладок не осталось, убираем из приложения всё, что о них помнили (список загрузок). */
    private fun privateTabRemoved() {
        if (tabs.none { it.isPrivate }) downloads.clearPrivate()
    }

    /** Восстанавливает вкладки с прошлого запуска. Возвращает true, если было что восстанавливать. */
    fun restore(): Boolean {
        val (snaps, index) = store.loadTabSnaps()
        if (snaps.isEmpty()) return false
        snaps.forEach { tabs.add(create(it.url, savedState = it.state, savedTitle = it.title)) }
        currentIndex = index.coerceIn(0, tabs.lastIndex)
        return true
    }

    fun saveState() {
        if (tabs.isEmpty()) return
        // Приватные вкладки на диск не пишутся: после перезапуска их не будет
        val regular = tabs.filter { !it.isPrivate }
        val cur = tabs.getOrNull(currentIndex)
        val idx = regular.indexOf(cur).takeIf { it >= 0 } ?: regular.lastIndex.coerceAtLeast(0)
        store.saveTabSnaps(regular.map { it.snapshot() }, idx)
    }
}
