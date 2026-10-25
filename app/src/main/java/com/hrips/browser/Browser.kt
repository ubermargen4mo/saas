package com.hrips.browser

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.ComponentCallbacks2
import android.os.SystemClock
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

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

private const val TAG = "Browser"

private const val MAX_CLOSED_TABS = 15

private const val KEEP_ALIVE_MS = 20 * 60_000L

private const val SAVE_DELAY_MS = 400L

private const val SAVE_DELAY_IDLE_MS = 2500L

/**
 * Защита от петли падений при запуске (как StartupCrashActivity у Fenix, но без вопросов пользователю):
 * после стольких подряд упавших запусков вкладки восстанавливаются без сохранённого состояния страниц
 * (адреса и сами вкладки остаются), а после [STARTUP_FAILURES_DROP_PAGE] текущая вкладка открывается домашней.
 */
private const val STARTUP_FAILURES_DROP_STATE = 2
private const val STARTUP_FAILURES_DROP_PAGE = 4

/** Потолок ожидания сохранения с первого ожидающего изменения: без него непрерывные события откладывали бы запись бесконечно. */
private const val SAVE_MAX_WAIT_MS = 5_000L

/** Прокрутка, ввод в формы и т. п. (`onSessionStateChange`) приходят постоянно и нужны реже: пишем раз в несколько секунд. */
private const val SAVE_DELAY_SESSION_MS = 5_000L
private const val SAVE_MAX_WAIT_SESSION_MS = 30_000L

/** Сколько последних вкладок (кроме выбранной) не приостанавливаем при нехватке памяти (как в Firefox). */
private const val MIN_ACTIVE_TABS = 3

class Browser(
    val runtime: GeckoRuntime,
    val store: Store,
    val downloads: Downloads,
    val permissions: Permissions,
    val prompts: Prompts,
    val adBlock: AdBlock,
    val external: ExternalLinks,
    private val media: MediaHub,
    val extensions: Extensions,
) {
    private val tabManager = TabManager()
    val tabs get() = tabManager.tabs
    val closedTabs get() = tabManager.closedTabs
    var contextMenu by mutableStateOf<ContextInfo?>(null)
    var currentIndex: Int
        get() = tabManager.currentIndex
        set(value) {
            val previous = tabManager.current
            tabManager.select(value)
            val next = tabManager.current
            if (appVisible && previous !== next) {
                previous?.let { setTabActive(it, false, false) }
                next?.let { setTabActive(it, true, true) }
            }
        }
    val current: Tab
        get() = tabManager.current ?: error("В браузере нет вкладок")

    private val engine = BrowserEngine(runtime)
    private val mainHandler = MainThread.handler
    private val saveRunnable = Runnable { saveState() }
    private var appVisible = false
    private var lowRamDevice = false
    // GeckoSession may be replaced during crash recovery while the tab remains selected.
    // Track the extension-facing active session by identity so recovery is reflected in WebExtensions.
    private var extensionActiveSession: GeckoSession? = null
    val isAppVisible: Boolean get() = appVisible

    fun saveUrl(uri: String, referrer: String?, incognito: Boolean = false) {
        fetch(uri, referrer, incognito) { r ->
            if (r != null && r.statusCode in 200..299) downloads.request(r, incognito, referrer) else downloads.toast("Не удалось скачать файл")
        }
    }

    fun fetch(uri: String, referrer: String?, incognito: Boolean = false, onDone: (WebResponse?) -> Unit) =
        engine.fetch(uri, referrer, incognito, onDone)

    fun fetchRange(
        uri: String,
        referrer: String?,
        incognito: Boolean,
        range: String?,
        ifRange: String?,
        onDone: (WebResponse?) -> Unit,
    ) = engine.fetchRange(uri, referrer, incognito, range, ifRange, onDone)

    fun clearData(cookies: Boolean, cache: Boolean, onDone: () -> Unit) =
        engine.clearData(cookies, cache, onDone)

    fun setTabActive(tab: Tab, active: Boolean, focused: Boolean = active) {
        val previousSession = tab.session
        if (active) tab.ensureLoaded()
        val currentSession = tab.session
        val visibilityChanged = tab.setVisibility(active, focused)
        AppLog.i("Tab") { "setActive вкладка=${tab.id.take(6)} active=$active focused=$focused изменилось=$visibilityChanged" }
        val sessionNeedsSync = active && extensionActiveSession !== currentSession
        if (visibilityChanged || sessionNeedsSync) {
            try {
                engine.setTabActive(currentSession, active)
            } catch (e: Exception) {
                Log.w(TAG, "setTabActive не удался", e)
            }
        }
        if (active) extensionActiveSession = currentSession
        else if (extensionActiveSession === currentSession || extensionActiveSession === previousSession) {
            extensionActiveSession = null
        }
    }

    fun warmHost(host: String?) {
        if (host.isNullOrBlank()) return
        MainThread.run { engine.warm(host) }
    }

    /** Полное предварительное соединение (DNS, TCP, TLS): только там, где сайт, скорее всего, откроют сразу. */
    fun connectHost(host: String?) {
        if (host.isNullOrBlank()) return
        MainThread.run { engine.connect(host) }
    }

    private fun warmStartHosts() {
        warmHost(runCatching { Uri.parse(SearchEngines.current.template).host }.getOrNull())
        store.speedDial.take(6).forEach { warmHost(runCatching { Uri.parse(it.url).host }.getOrNull()) }
    }

    private var lastNetRetry = 0L

    /** Следим за появлением сети: открытая страница с ошибкой связи перезагружается сама. */
    fun watchNetwork(context: Context) {
        lowRamDevice = runCatching {
            context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        }.getOrDefault(false)
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val main = MainThread.handler
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!appVisible || now - lastNetRetry < 3000L) return@post
                        if (current.retryIfNetworkError()) lastNetRetry = now
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось подписаться на сеть", e)
        }
    }

    private var keepAliveTab: Tab? = null
    private val releaseKeepAlive = Runnable {
        keepAliveTab?.setBackgroundPriority(false)
        keepAliveTab = null
    }

    fun onForeground() {
        AppLog.i("Browser") { "onForeground, текущая вкладка=${currentIndex + 1} из ${tabs.size}" }
        mainHandler.removeCallbacks(releaseKeepAlive)
        keepAliveTab = null
        appVisible = true
        warmStartHosts()
        downloads.setAppVisible(true)
        syncTabActivity()
    }

    /**
     * Расставляет видимость вкладок: текущая видима и в фокусе, остальные нет. Вызывается при любой смене текущей
     * вкладки (закрытие, возврат к родителю, восстановление). Раньше для этого дёргали `onForeground()`, который
     * каждый раз заодно прогревал соединения с поисковиком и плитками, сбрасывал таймер удержания страницы и
     * уведомлял загрузки.
     */
    private fun syncTabActivity() {
        val selected = tabManager.current
        tabs.forEach { tab ->
            val active = tab === selected
            setTabActive(tab, active, active)
        }
    }

    fun onBackground() {
        AppLog.i("Browser", "onBackground")
        flushPendingClose()
        // Как в Firefox (AutoSave.whenGoingToBackground): сохраняем сразу, не откладывая; процесс в фоне могут убить в любой момент.
        // Отложенное сохранение ниже подхватит состояние, которое GeckoView успеет сбросить при уничтожении поверхности.
        mainHandler.removeCallbacks(saveRunnable)
        saveState()
        appVisible = false
        downloads.setAppVisible(false)
        tabs.forEach { tab ->
            setTabActive(tab, false, false)
        }
        // Текущую страницу держим «важной» ещё некоторое время: иначе Android убивает процесс вкладки,
        // и при возврате страница грузится заново.
        if (store.keepPageInBackground) {
            tabManager.current?.let {
                it.setBackgroundPriority(true)
                keepAliveTab = it
                mainHandler.removeCallbacks(releaseKeepAlive)
                mainHandler.postDelayed(releaseKeepAlive, KEEP_ALIVE_MS)
            }
        }
        scheduleStateSave()
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        AppLog.i("Memory") { "Browser.onTrimMemory level=$level, приостановка при нехватке памяти=${store.suspendTabsOnMemoryPressure}" }
        if (!store.suspendTabsOnMemoryPressure) return
        // Как в Firefox (TrimMemoryMiddleware): только при настоящей нехватке памяти. Сравнение «>=» было ошибкой:
        // TRIM_MEMORY_UI_HIDDEN (20) приходит при каждом сворачивании приложения и выборе файла, а по «>=» он
        // попадал в RUNNING_CRITICAL (15) и приостанавливал вкладки. Остаются выбранная и MIN_ACTIVE_TABS последних.
        if (level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && level != ComponentCallbacks2.TRIM_MEMORY_COMPLETE) return
        val candidates = tabs
            .filter { it !== current && it.session.isOpen && !it.loading && !it.fullscreen && !media.isPlaying(it.session) }
            .sortedByDescending { it.lastActivatedAt() }
            .drop(MIN_ACTIVE_TABS)
        var suspendedCount = 0
        for (tab in candidates) {
            if (tab.suspend()) suspendedCount++
        }
        if (suspendedCount > 0) {
            scheduleStateSave()
            val live = tabs.count { it.session.isOpen }
            Log.d(TAG, "При давлении памяти приостановлено вкладок: $suspendedCount; открытых сессий осталось: $live")
            AppLog.w("Memory", "Приостановлено вкладок: $suspendedCount, открытых сессий осталось: $live")
        }
    }

    /** Запрос подтверждения скачивания языковых моделей перед первым переводом пары языков (показывается в BrowserScreen). */
    var translatePrompt by mutableStateOf<TranslatePrompt?>(null)

    private var saveWindowStart = 0L
    private var saveDueAt = 0L

    /**
     * Отложенное сохранение вкладок. [delayMs] - тишина после последнего изменения, [maxWaitMs] - потолок с первого
     * ожидающего изменения. [postpone] = false для частых второстепенных событий: они не отодвигают уже назначенный
     * более ранний срок (иначе состояние сразу после загрузки страницы ждало бы, пока уляжется прокрутка).
     */
    private fun scheduleStateSave(delayMs: Long = SAVE_DELAY_MS, maxWaitMs: Long = SAVE_MAX_WAIT_MS, postpone: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        if (saveDueAt == 0L) saveWindowStart = now
        val due = minOf(now + delayMs, saveWindowStart + maxWaitMs)
        if (!postpone && saveDueAt != 0L && saveDueAt <= due) return
        saveDueAt = due
        mainHandler.removeCallbacks(saveRunnable)
        mainHandler.postDelayed(saveRunnable, (due - now).coerceAtLeast(0L))
    }

    private fun scheduleStateSaveIdle() = scheduleStateSave(SAVE_DELAY_IDLE_MS)

    private fun create(
        url: String,
        openNow: Boolean = true,
        isPrivate: Boolean = false,
        savedState: String? = null,
        savedTitle: String = "",
        tabId: String? = null,
        deferOpen: Boolean = false,
    ) = Tab(
        runtime, url,
        tabId = tabId ?: java.util.UUID.randomUUID().toString(),
        desktopFor = { store.isDesktopSite(it) },
        onDesktopSaved = { host, on -> setSiteDesktop(host, on) },
        onVisited = { u, t -> if (!isPrivate) store.addHistory(u, t) },
        onStateChanged = { if (!isPrivate) scheduleStateSave() },
        onSessionStateChanged = {
            if (!isPrivate) scheduleStateSave(SAVE_DELAY_SESSION_MS, SAVE_MAX_WAIT_SESSION_MS, postpone = false)
        },
        onDownload = { tab, response -> handleDownload(tab, response) },
        permissionDelegate = permissions.delegate,
        promptDelegate = prompts.delegate,
        onNewWindow = { opener, u -> openPopup(opener, u) },
        onPageClose = { closeFromPage(it) },
        onMenu = { contextMenu = it },
        onExternal = { u, g, from -> external.handle(u, g, from) },
        media = media,
        savedState = savedState,
        savedTitle = savedTitle,
        isPrivate = isPrivate,
        openNow = openNow,
        deferOpen = deferOpen,
    )

    private fun handleDownload(tab: Tab, response: WebResponse) {
        val blankPopup = tab.popup && tabs.contains(tab) && !tab.canGoBack && tab.title.isBlank()
        if (blankPopup) {
            val old = tabs.indexOf(tab)
            val parentIndex = tab.parent?.let { tabs.indexOf(it) } ?: -1
            tabManager.removeAt(old)
            if (tabs.isEmpty()) {
                newTab()
            } else {
                currentIndex = if (parentIndex >= 0) parentIndex else (old - 1).coerceIn(0, tabs.lastIndex)
            }
            if (tab.isPrivate) privateTabRemoved()
        }
        downloads.request(response, tab.isPrivate) { if (blankPopup) tab.close() }
    }

    private fun openPopup(opener: Tab, uri: String): GeckoSession {
        val tab = create(uri, openNow = false, isPrivate = opener.isPrivate)
        tab.parent = opener
        tabManager.add(tab)
        return tab.session
    }

    fun openForExtension(url: String, active: Boolean, engineWillLoad: Boolean = false): GeckoSession {
        val tab = create(url, openNow = !engineWillLoad)
        tabManager.add(tab, select = active)
        if (appVisible) syncTabActivity()
        return tab.session
    }

    fun setSiteDesktop(host: String, on: Boolean) {
        store.setDesktopSite(host, on)
        tabs.forEach { if (!it.isPrivate && it.siteHost() == host && it.desktopMode != on) it.applyDesktop(on, reload = true) }
    }

    fun resetDesktopSites() {
        val hosts = store.desktopSites.toList()
        store.clearDesktopSites()
        hosts.forEach { h -> tabs.forEach { if (!it.isPrivate && it.siteHost() == h && it.desktopMode) it.applyDesktop(false, reload = true) } }
    }

    fun setTracking(on: Boolean) {
        store.updateTracking(on)
        applyTrackingProtection(runtime, on)
    }

    fun applyPrivacy() = PrivacyEngine.apply(runtime, store)

    fun newTab(url: String = "", incognito: Boolean = false, returnToPrevious: Boolean = true): Tab {
        val previous = tabManager.current
        val tab = create(url, isPrivate = incognito)
        if (returnToPrevious && url.isNotBlank()) tab.returnTo = previous
        tabManager.add(tab)
        if (appVisible) {
            previous?.let { setTabActive(it, false, false) }
            setTabActive(tab, true, true)
        }
        scheduleStateSave()
        return tab
    }

    fun moveTab(from: Int, to: Int) {
        if (from == to || from !in tabs.indices || to !in tabs.indices) return
        tabManager.move(from, to)
        scheduleStateSave()
    }

    fun focusSite(source: String?) {
        val host = plainHost(source)
            ?: source?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            ?: return
        val i = tabs.indexOfFirst { it.siteHost() == host }
        if (i >= 0) {
            currentIndex = i
            if (appVisible) syncTabActivity()
        }
    }

    fun reopen(c: ClosedTab) {
        if (!closedTabs.remove(c)) return
        val snap = c.snap()
        flushPendingClose { it.id == snap.id }
        val tab = create(snap.url, savedState = snap.state, savedTitle = snap.title, tabId = snap.id)
        val blank = tabs.singleOrNull()?.takeIf { it.home && !it.isPrivate }
        if (blank != null) tabManager.remove(blank)
        tabManager.add(tab)
        if (blank != null) blank.close()
        scheduleStateSave()
    }

    fun reopenLast() {
        closedTabs.firstOrNull()?.let { reopen(it) }
    }

    fun openerOf(tab: Tab): Tab? =
        (tab.returnTo ?: tab.parent)?.takeIf { it !== tab && it.isPrivate == tab.isPrivate && tabs.contains(it) }

    /** Страница сама просит закрыть вкладку (window.close): попап входа/оплаты завершил работу. */
    fun closeFromPage(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        if (tab === tabManager.current && openerOf(tab) != null) returnToOpener(tab) else closeTab(index)
    }

    fun returnToOpener(tab: Tab) {
        val opener = openerOf(tab) ?: return
        val index = tabs.indexOf(tab)
        if (index < 0) return
        closeTab(index)
        val back = tabs.indexOf(opener)
        if (back >= 0) {
            currentIndex = back
            if (appVisible) syncTabActivity()
        }
    }

    private val pendingClose = ArrayList<Tab>()

    fun closeTab(index: Int, deferEngine: Boolean = false) {
        val closed = tabs.getOrNull(index) ?: return
        if (!closed.isPrivate && !closed.home && closed.url.isNotBlank()) {
            closedTabs.add(0, ClosedTab(closed.saveParts()))
            while (closedTabs.size > MAX_CLOSED_TABS) closedTabs.removeAt(closedTabs.lastIndex)
        }
        if (deferEngine) pendingClose.add(closed) else closed.close()
        tabManager.removeAt(index)
        if (tabs.isEmpty()) newTab()
        if (closed.isPrivate) privateTabRemoved()
        if (!deferEngine && appVisible) syncTabActivity()
        if (deferEngine) scheduleStateSaveIdle() else scheduleStateSave()
    }

    fun flushPendingClose(only: ((Tab) -> Boolean)? = null) {
        if (pendingClose.isEmpty()) return
        val doomed = if (only == null) pendingClose.toList() else pendingClose.filter(only)
        if (doomed.isEmpty()) return
        pendingClose.removeAll(doomed)
        doomed.forEach { it.close() }
        scheduleStateSave()
    }

    val privateCount: Int get() = tabs.count { it.isPrivate }

    fun closePrivateTabs(deferEngine: Boolean = false) {
        val doomed = tabs.filter { it.isPrivate }
        if (doomed.isEmpty()) return
        val keep = tabManager.current?.takeIf { !it.isPrivate }
        if (deferEngine) pendingClose.addAll(doomed) else doomed.forEach { it.close() }
        tabManager.removeAll { it.isPrivate }
        if (tabs.isEmpty()) {
            newTab()
        } else {
            val target = keep?.let { tabs.indexOf(it) }?.takeIf { it >= 0 } ?: tabs.lastIndex
            if (deferEngine) tabManager.select(target) else currentIndex = target
        }
        privateTabRemoved()
        if (!deferEngine && appVisible) syncTabActivity()
        if (deferEngine) scheduleStateSaveIdle() else scheduleStateSave()
    }

    /** Закрывает все вкладки и открывает одну пустую. Обычные вкладки попадают в список закрытых. */
    fun closeAllTabs() {
        flushPendingClose()
        val doomed = tabs.toList()
        if (doomed.isEmpty()) return
        doomed.forEach { t ->
            if (!t.isPrivate && !t.home && t.url.isNotBlank()) closedTabs.add(0, ClosedTab(t.saveParts()))
        }
        while (closedTabs.size > MAX_CLOSED_TABS) closedTabs.removeAt(closedTabs.lastIndex)
        doomed.forEach { it.close() }
        tabManager.removeAll { true }
        newTab()
        privateTabRemoved()
        if (appVisible) syncTabActivity()
        scheduleStateSave()
    }

    private fun privateTabRemoved() {
        if (tabs.none { it.isPrivate }) downloads.clearPrivate()
    }

    fun restore(): Boolean {
        val restored = store.loadTabRestore()
        val snaps = restored.tabs
        if (snaps.isEmpty()) return false
        val selected = restored.index.coerceIn(0, snaps.lastIndex)
        val failures = store.startupFailures
        val dropStates = failures >= STARTUP_FAILURES_DROP_STATE
        val dropPage = failures >= STARTUP_FAILURES_DROP_PAGE
        if (dropStates) {
            AppLog.w("Restore", "подряд упавших запусков: $failures, вкладки восстанавливаются без состояния страниц, текущая заменена домашней=$dropPage")
            Notices.show(
                if (dropPage) "Приложение много раз упало при запуске: текущая вкладка заменена домашней страницей"
                else "Приложение упало при запуске: вкладки открыты без сохранённого состояния страниц",
            )
        }
        snaps.forEachIndexed { i, snap ->
            val replaced = dropPage && i == selected
            val tab = create(
                if (replaced) "" else snap.url,
                savedState = if (dropStates) null else snap.state,
                savedTitle = if (replaced) "" else snap.title,
                tabId = snap.id,
                deferOpen = i != selected,
            )
            tabManager.add(tab, select = false)
        }
        currentIndex = selected
        if (appVisible) syncTabActivity()
        return true
    }

    fun saveState() {
        mainHandler.removeCallbacks(saveRunnable)
        saveDueAt = 0L
        saveWindowStart = 0L
        if (tabs.isEmpty()) return
        val regular = tabs.filter { !it.isPrivate }
        val cur = tabManager.current
        val idx = regular.indexOf(cur).takeIf { it >= 0 } ?: regular.lastIndex.coerceAtLeast(0)
        val parts = regular.map { it.saveParts() }
        store.saveTabSnapsDeferred({ parts.map { it.build() } }, idx)
    }
}

/** Перевод [from] -> [to] требует скачать [bytes] байт языковых моделей. */
class TranslatePrompt(val tab: Tab, val from: String, val to: String, val bytes: Long)
