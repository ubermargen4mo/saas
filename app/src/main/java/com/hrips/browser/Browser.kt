package com.hrips.browser

import android.content.ComponentCallbacks2
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse
import java.util.UUID

fun applyPrivateDefaults(runtime: GeckoRuntime) {
    runtime.settings.contentBlocking.setCookieBehaviorPrivateMode(
        ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
    )
    runtime.settings.setFingerprintingProtectionPrivateBrowsing(true)
    runtime.settings.contentBlocking.setEmailTrackerBlockingPrivateBrowsing(true)
}

/**
 * Защита от слежения для обычных вкладок. Включённая приведена к «Стандартной» защите Firefox (как в Android Components):
 * списки рекламы, аналитики и соцсетей, раздельные cookie по сайтам (Total Cookie Protection), очистка cookie трекеров
 * без недавнего взаимодействия.
 * [strict] включает «Строгую» защиту Firefox: ещё и скрытые трекеры (content), майнеры, сборщики отпечатков, почтовые
 * трекеры, строгий список соцсетей и защиту от отпечатков (FPP) во всех вкладках, а не только в приватных. Она может
 * ломать вход и оплату на части сайтов, поэтому по умолчанию выключена.
 * Уровень и очистка cookie работают только при таких режимах cookie, поэтому они заданы вместе.
 * Автозакрытие баннеров cookie (CookieBannerMode) удалено из API GeckoView (157+), поэтому здесь его нет.
 */
fun applyTrackingProtection(runtime: GeckoRuntime, on: Boolean, strict: Boolean = false) {
    val cb = runtime.settings.contentBlocking
    val useStrict = on && strict
    if (on) {
        cb.setAntiTracking(if (useStrict) ContentBlocking.AntiTracking.STRICT else ContentBlocking.AntiTracking.DEFAULT)
        cb.setEnhancedTrackingProtectionLevel(if (useStrict) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.DEFAULT)
        cb.setCookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
        cb.setCookiePurging(true)
        cb.setStrictSocialTrackingProtection(useStrict)
    } else {
        cb.setAntiTracking(ContentBlocking.AntiTracking.NONE)
        cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE)
        cb.setCookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
        cb.setCookiePurging(false)
        cb.setStrictSocialTrackingProtection(false)
    }
    // В приватных вкладках защита от отпечатков включена всегда (applyPrivateDefaults), здесь только обычные.
    runtime.settings.setFingerprintingProtection(useStrict)
}

/** Защита от фишинга и вредоносных сайтов не зависит от защиты от слежения (в Android Components это отдельная настройка). */
fun applySafeBrowsing(runtime: GeckoRuntime) {
    runtime.settings.contentBlocking.setSafeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
}

private const val TAG = "Browser"
private const val MAX_CLOSED_TABS = 15
private const val NET_RETRY_GAP_MS = 3_000L
private const val EXIT_DELAY_MS = 300L
private const val EXIT_CLEAR_TIMEOUT_MS = 8_000L

private const val SAVE_DELAY_MS = 400L
private const val SAVE_DELAY_IDLE_MS = 2_500L

private const val SAVE_MAX_WAIT_MS = 5_000L

private const val SAVE_DELAY_SESSION_MS = 5_000L
private const val SAVE_MAX_WAIT_SESSION_MS = 30_000L

private const val STARTUP_FAILURES_DROP_STATE = 2
private const val STARTUP_FAILURES_DROP_PAGE = 4

private const val MIN_ACTIVE_TABS = 3

/** Сколько недавно открытых вкладок держать «тёплыми» (высокий приоритет процесса) после переключения. */
internal const val MAX_WARM_TABS = 2
/** Недавняя вкладка держится столько после ухода с неё. */
internal const val WARM_TAB_MS = 10 * 60_000L
/** Вкладка с набранным в формах текстом держится дольше: регистрация, письмо, запрос в чат. */
internal const val WARM_FORM_TAB_MS = 30 * 60_000L

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
    private val engine = BrowserEngine(runtime)
    private val mainHandler = MainThread.handler
    private val saveRunnable = Runnable { saveState() }
    private val pendingClose = ArrayList<Tab>()
    private val releaseKeepAlive = Runnable { releaseBackgroundHold() }
    private val warmExpiry = Runnable { refreshPriorityHolds() }

    var isAppVisible = false
        private set

    /** Запускает и останавливает службу, которая не даёт системе выгрузить свёрнутую страницу. Задаёт [HripsApp]. */
    var backgroundKeeper: BackgroundKeeper? = null

    /** Текущая вкладка свёрнутого браузера, которую держим в памяти («Держать страницу при сворачивании»). */
    private var keepAliveTab: Tab? = null
    /** Недавно покинутые вкладки и срок, до которого их процесс держится с высоким приоритетом. */
    private val warmTabs = LinkedHashMap<Tab, Long>()
    /** Из [warmTabs]: вкладки, где пользователь что-то ввёл. Их держим и в свёрнутом браузере. */
    private val formTabs = HashSet<Tab>()
    private var lastNetRetry = 0L
    private var appliedTextScale = 100
    private var saveWindowStart = 0L
    private var saveDueAt = 0L

    private var extensionActiveSession: GeckoSession? = null

    val tabs get() = tabManager.tabs
    val closedTabs get() = tabManager.closedTabs
    var contextMenu by mutableStateOf<ContextInfo?>(null)

    var translatePrompt by mutableStateOf<TranslatePrompt?>(null)

    var currentIndex: Int
        get() = tabManager.currentIndex
        set(value) {
            val previous = tabManager.current
            tabManager.select(value)
            val next = tabManager.current
            if (isAppVisible && previous !== next) {
                previous?.let { setTabActive(it, false, false) }
                next?.let { setTabActive(it, true, true) }
            }
        }
    val current: Tab
        get() = tabManager.current ?: error("В браузере нет вкладок")

    val privateCount: Int get() = tabs.count { it.isPrivate }

    fun saveUrl(uri: String, referrer: String?, incognito: Boolean = false) {
        fetch(uri, referrer, incognito) { r ->
            if (r != null && r.statusCode in 200..299) downloads.request(r, incognito, referrer) else downloads.toast("Не удалось скачать файл")
        }
    }

    fun fetch(uri: String, referrer: String?, incognito: Boolean = false, onDone: (WebResponse?) -> Unit) =
        engine.fetch(uri, referrer, incognito, onDone = onDone)

    fun fetchRange(
        uri: String,
        referrer: String?,
        incognito: Boolean,
        range: String?,
        ifRange: String?,
        onDone: (WebResponse?) -> Unit,
    ) = engine.fetch(uri, referrer, incognito, range, ifRange, onDone)

    /** Вызывается, когда удаляются cookies и данные сайтов: вместе с ними уходят и push-подписки сайтов. */
    var onSiteDataCleared: (() -> Unit)? = null

    fun clearData(cookies: Boolean, cache: Boolean, onDone: () -> Unit) {
        if (cookies) onSiteDataCleared?.invoke()
        engine.clearData(cookies, cache, onDone)
    }

    fun setTabActive(tab: Tab, active: Boolean, focused: Boolean = active) {
        val previousSession = tab.session
        if (active) tab.ensureLoaded()
        val currentSession = tab.session
        // Уход с вкладки при открытом браузере (переключение вкладок): её процесс ещё какое-то время держится,
        // чтобы при возврате, например после копирования кода из соседней вкладки, страница не перезагрузилась.
        // Отметка ставится до смены видимости, чтобы приоритет не успел упасть.
        if (!active && isAppVisible && tab.isShown && tabs.contains(tab)) keepWarm(tab)
        val visibilityChanged = tab.setVisibility(active, focused)
        if (active && warmTabs.remove(tab) != null) {
            formTabs.remove(tab)
            tab.setPriorityHold(tab === keepAliveTab)
        }
        tab.syncEngineActive(keepActive = media.isPlaying(currentSession))
        AppLog.i("Tab") { "setActive вкладка=${tab.id.take(6)} active=$active focused=$focused изменилось=$visibilityChanged" }
        if (visibilityChanged || (active && extensionActiveSession !== currentSession)) {
            try {
                engine.setTabActive(currentSession, active)
            } catch (e: Exception) {
                AppLog.w(TAG, "setTabActive не удался", e)
            }
        }
        if (active) extensionActiveSession = currentSession
        else if (extensionActiveSession === currentSession || extensionActiveSession === previousSession) {
            extensionActiveSession = null
        }
    }

    private fun warmHost(host: String?) {
        if (!host.isNullOrBlank()) MainThread.run { engine.warm(host) }
    }

    fun connectHost(host: String?) {
        if (!host.isNullOrBlank()) MainThread.run { engine.connect(host) }
    }

    private fun warmStartHosts() {
        warmHost(runCatching { Uri.parse(SearchEngines.current.template).host }.getOrNull())
        store.speedDial.take(6).forEach { warmHost(runCatching { Uri.parse(it.url).host }.getOrNull()) }
    }

    fun watchNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            Connectivity.online = cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    Connectivity.online = false
                }

                override fun onAvailable(network: Network) {
                    Connectivity.online = true
                    mainHandler.post {
                        val now = SystemClock.elapsedRealtime()
                        if (!isAppVisible || now - lastNetRetry < NET_RETRY_GAP_MS) return@post
                        if (tabManager.current?.retryIfNetworkError() == true) lastNetRetry = now
                    }
                }
            })
        } catch (e: Exception) {
            AppLog.w(TAG, "Не удалось подписаться на сеть", e)
        }
    }

    fun onForeground() {
        AppLog.i("Browser") { "onForeground, текущая вкладка=${currentIndex + 1} из ${tabs.size}" }
        mainHandler.removeCallbacks(releaseKeepAlive)
        keepAliveTab = null
        backgroundKeeper?.release()
        isAppVisible = true
        warmStartHosts()
        syncTabActivity()
        refreshPriorityHolds()
        // Сеть могла вернуться, пока браузер был свёрнут: колбэк сети в фоне повтор не делает.
        val now = SystemClock.elapsedRealtime()
        val shown = tabManager.current
        if (shown != null && Connectivity.online && now - lastNetRetry >= NET_RETRY_GAP_MS && shown.retryIfNetworkError()) lastNetRetry = now
    }

    private fun syncTabActivity() {
        val selected = tabManager.current
        tabs.forEach { setTabActive(it, it === selected, it === selected) }
    }

    private fun syncIfVisible() {
        if (isAppVisible) syncTabActivity()
    }

    fun onBackground() {
        AppLog.i("Browser", "onBackground")
        flushPendingClose()
        mainHandler.removeCallbacks(saveRunnable)
        // Свежее состояние страниц (с набранным текстом) придёт в onSessionStateChange и сразу запишется на диск:
        // если систему всё же выгрузит процесс, вкладки восстановятся с ним, а не с состоянием десятисекундной давности.
        tabs.forEach { if (!it.isPrivate) it.flushSessionState() }
        saveState()
        isAppVisible = false
        tabs.forEach { setTabActive(it, false, false) }
        mainHandler.removeCallbacks(releaseKeepAlive)
        val keep = tabManager.current?.takeIf { KeepPagePolicy.shouldKeep(store.keepPageInBackground, quitting, it.home, it.url) }
        keepAliveTab = keep
        refreshPriorityHolds()
        if (keep != null) {
            val minutes = store.keepAliveMinutes
            // Таймер на случай, если служба не запустилась; у службы свой таймер, который идёт и во сне устройства.
            if (minutes > 0) mainHandler.postDelayed(releaseKeepAlive, minutes * 60_000L)
            val started = backgroundKeeper?.hold(keep.label(), keep.isPrivate, minutes) { releaseBackgroundHold() } ?: false
            AppLog.i("Browser") { "держу текущую страницу в фоне: служба=${started}, минут=$minutes, вкладок с формами=${formTabs.size}" }
        }
        scheduleStateSave()
    }

    /** Срок удержания вышел или пользователь отпустил страницу из уведомления: приоритет возвращается обычный. */
    fun releaseBackgroundHold() {
        mainHandler.removeCallbacks(releaseKeepAlive)
        if (keepAliveTab == null) return
        AppLog.i("Browser", "страница в фоне больше не удерживается")
        keepAliveTab = null
        if (!isAppVisible) backgroundKeeper?.release()
        refreshPriorityHolds()
    }

    private fun keepWarm(tab: Tab) {
        warmTabs.remove(tab)
        warmTabs[tab] = SystemClock.elapsedRealtime() + WARM_TAB_MS
        formTabs.remove(tab)
        while (warmTabs.size > MAX_WARM_TABS) {
            val eldest = warmTabs.keys.first()
            warmTabs.remove(eldest)
            formTabs.remove(eldest)
        }
        refreshPriorityHolds()
        tab.checkFormData { has ->
            if (has && warmTabs.containsKey(tab)) {
                AppLog.i("Tab") { "на вкладке ${tab.id.take(6)} есть введённые данные, держу её дольше" }
                formTabs.add(tab)
                warmTabs[tab] = SystemClock.elapsedRealtime() + WARM_FORM_TAB_MS
                refreshPriorityHolds()
            }
        }
    }

    /**
     * Раздаёт вкладкам удержание приоритета. На экране: недавние вкладки ([warmTabs]) до своего срока.
     * В свёрнутом браузере: текущая вкладка и вкладки с введёнными данными, пока действует удержание страницы.
     */
    private fun refreshPriorityHolds() {
        mainHandler.removeCallbacks(warmExpiry)
        val now = SystemClock.elapsedRealtime()
        val alive = tabs.toHashSet()
        val expired = warmTabs.entries.filter { (t, until) -> t !in alive || (isAppVisible && until <= now) }.map { it.key }
        expired.forEach {
            warmTabs.remove(it)
            formTabs.remove(it)
            it.setPriorityHold(false)
        }
        keepAliveTab?.let { if (it !in alive) { it.setPriorityHold(false); keepAliveTab = null } }
        val held = keepAliveTab
        tabs.forEach { t ->
            val hold = if (isAppVisible) t in warmTabs else held != null && (t === held || t in formTabs)
            t.setPriorityHold(hold)
        }
        if (isAppVisible) warmTabs.values.minOrNull()?.let { mainHandler.postDelayed(warmExpiry, (it - now).coerceAtLeast(1_000L)) }
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int, lowMemory: () -> Boolean) {
        AppLog.i("Memory") { "Browser.onTrimMemory level=$level, приостановка при нехватке памяти=${store.suspendTabsOnMemoryPressure}" }
        if (!store.suspendTabsOnMemoryPressure) return
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        // С Android 14 приходят только UI_HIDDEN и BACKGROUND, поэтому для них нехватка памяти проверяется отдельно.
        val hiddenAndLow = (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) && lowMemory()
        if (!critical && !hiddenAndLow) return
        val suspended = tabs
            .filter { it !== current && it !== keepAliveTab && it !in formTabs && it.session.isOpen && !it.loading && !it.fullscreen && !media.isPlaying(it.session) }
            .sortedByDescending { it.lastActivatedAt() }
            .drop(MIN_ACTIVE_TABS)
            .count { it.suspend() }
        if (suspended > 0) {
            scheduleStateSave()
            AppLog.w("Memory", "Приостановлено вкладок: $suspended, открытых сессий осталось: ${tabs.count { it.session.isOpen }}")
        }
    }

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
        sharedContextId: String? = null,
    ) = Tab(
        runtime, url,
        tabId = tabId ?: UUID.randomUUID().toString(),
        desktopFor = { store.isDesktopSite(it) },
        onDesktopSaved = { host, on -> setSiteDesktop(host, on) },
        onVisited = { u, t -> if (!isPrivate) store.addHistory(u, t) },
        onStateChanged = { if (!isPrivate) scheduleStateSave() },
        onSessionStateChanged = {
            // В свёрнутом браузере процесс могут выгрузить в любой момент, поэтому состояние пишется сразу.
            if (!isPrivate) {
                if (isAppVisible) scheduleStateSave(SAVE_DELAY_SESSION_MS, SAVE_MAX_WAIT_SESSION_MS, postpone = false)
                else scheduleStateSave(SAVE_DELAY_MS, SAVE_MAX_WAIT_MS, postpone = false)
            }
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
        sharedContextId = sharedContextId,
        isVisited = { store.isVisited(it) },
        onSessionGone = { s ->
            prompts.forget(s)
            permissions.forget(s)
        },
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
            dropLinksToRemovedTabs()
        }
        downloads.request(response, tab.isPrivate) { if (blankPopup) tab.close() }
    }

    private fun openPopup(opener: Tab, uri: String): GeckoSession {
        val tab = create(uri, openNow = false, isPrivate = opener.isPrivate, sharedContextId = opener.contextId)
        tab.parent = opener
        tabManager.add(tab)
        return tab.session
    }

    fun openForExtension(url: String, active: Boolean, engineWillLoad: Boolean = false): GeckoSession {
        val tab = create(url, openNow = !engineWillLoad)
        tabManager.add(tab, select = active)
        syncIfVisible()
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

    fun updateTextScale(tab: Tab, percent: Int) {
        tab.textScale = percent
        syncTextScale(tab)
    }

    fun syncTextScale(tab: Tab?) {
        val percent = if (tab == null || tab.home) 100 else tab.textScale
        if (percent == appliedTextScale) return
        appliedTextScale = percent
        runtime.settings.setFontSizeFactor(percent / 100f)
    }

    fun setTracking(on: Boolean) {
        store.updateTracking(on)
        applyTrackingProtection(runtime, on, store.strictProtection)
    }

    fun setStrictProtection(on: Boolean) {
        store.updateStrictProtection(on)
        applyTrackingProtection(runtime, store.trackingProtection, on)
    }

    fun applyPrivacy() = PrivacyEngine.apply(runtime, store)

    fun newTab(url: String = "", incognito: Boolean = false, returnToPrevious: Boolean = true): Tab {
        val previous = tabManager.current
        val tab = create(url, isPrivate = incognito)
        if (returnToPrevious && url.isNotBlank()) tab.returnTo = previous
        tabManager.add(tab)
        if (isAppVisible) {
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
            syncIfVisible()
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
        blank?.close()
        scheduleStateSave()
    }

    fun reopenLast() {
        closedTabs.firstOrNull()?.let { reopen(it) }
    }

    fun openerOf(tab: Tab): Tab? =
        (tab.returnTo ?: tab.parent)?.takeIf { it !== tab && it.isPrivate == tab.isPrivate && tabs.contains(it) }

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
            syncIfVisible()
        }
    }

    private fun rememberClosed(tab: Tab) {
        if (tab.isPrivate || tab.home || tab.url.isBlank()) return
        closedTabs.add(0, ClosedTab(tab.saveParts()))
        while (closedTabs.size > MAX_CLOSED_TABS) closedTabs.removeAt(closedTabs.lastIndex)
    }

    private fun afterClose(deferEngine: Boolean) {
        dropLinksToRemovedTabs()
        if (deferEngine) {
            scheduleStateSaveIdle()
        } else {
            syncIfVisible()
            scheduleStateSave()
        }
    }

    fun closeTab(index: Int, deferEngine: Boolean = false) {
        val closed = tabs.getOrNull(index) ?: return
        rememberClosed(closed)
        if (deferEngine) pendingClose.add(closed) else closed.close()
        tabManager.removeAt(index)
        if (tabs.isEmpty()) newTab()
        if (closed.isPrivate) privateTabRemoved()
        if (!deferEngine) clearOrphanContexts(listOf(closed))
        afterClose(deferEngine)
    }

    private fun dropLinksToRemovedTabs() {
        val alive = tabs.toHashSet()
        tabs.forEach { t ->
            t.parent?.let { if (it !in alive) t.parent = null }
            t.returnTo?.let { if (it !in alive) t.returnTo = null }
        }
        if (warmTabs.keys.any { it !in alive } || keepAliveTab?.let { it !in alive } == true) refreshPriorityHolds()
    }

    private fun clearOrphanContexts(closed: Collection<Tab>) {
        closed.mapNotNull { it.contextId }.distinct().forEach { ctx ->
            if (tabs.none { it.contextId == ctx } && pendingClose.none { it.contextId == ctx }) engine.clearSessionContext(ctx)
        }
    }

    fun flushPendingClose(only: ((Tab) -> Boolean)? = null) {
        val doomed = if (only == null) pendingClose.toList() else pendingClose.filter(only)
        if (doomed.isEmpty()) return
        pendingClose.removeAll(doomed)
        doomed.forEach { it.close() }
        clearOrphanContexts(doomed)
        scheduleStateSave()
    }

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
        if (!deferEngine) clearOrphanContexts(doomed)
        afterClose(deferEngine)
    }

    fun closeAllTabs() {
        flushPendingClose()
        val doomed = tabs.toList()
        if (doomed.isEmpty()) return
        doomed.forEach { rememberClosed(it); it.close() }
        tabManager.removeAll { true }
        newTab()
        privateTabRemoved()
        clearOrphanContexts(doomed)
        afterClose(deferEngine = false)
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
                // Выбранная вкладка откроется при показе (onStart). Если запуск открыл ссылку в новой вкладке, старая не грузится зря.
                deferOpen = true,
            )
            tabManager.add(tab, select = false)
        }
        currentIndex = selected
        syncIfVisible()
        return true
    }

    fun saveState() {
        if (quitting) return
        mainHandler.removeCallbacks(saveRunnable)
        saveDueAt = 0L
        saveWindowStart = 0L
        if (tabs.isEmpty()) return
        val regular = tabs.filter { !it.isPrivate }
        val idx = regular.indexOf(tabManager.current).takeIf { it >= 0 } ?: regular.lastIndex.coerceAtLeast(0)
        val parts = regular.map { it.saveParts() }
        store.saveTabSnapsDeferred({ parts.map { it.build() } }, idx)
    }

    /** Идёт выход через «Выйти»: состояние больше не сохраняется, чтобы не вернуть удалённые вкладки. */
    var quitting = false
        private set

    /**
     * Выход из приложения из меню. Если включено «Удалять данные при выходе», сначала удаляет выбранное.
     * Отметка о начатой очистке пишется до удаления: если процесс умрёт посередине, очистка доделается при запуске
     * ([resumePendingExitClear]). Процесс завершается целиком, как в Firefox: иначе cookies и страницы
     * оставались бы в памяти движка.
     */
    fun quit(activity: android.app.Activity) {
        if (quitting) return
        flushPendingClose()
        val mask = if (store.clearOnExit) ExitClear.sanitize(store.clearOnExitMask) else 0
        AppLog.i("Exit") { "выход из приложения, удалить: ${ExitClear.describe(mask)}" }
        if (mask == 0) saveState()
        quitting = true
        keepAliveTab = null
        backgroundKeeper?.release()
        downloads.pauseAll()
        var finished = false
        val finish = finish@{
            if (finished) return@finish
            finished = true
            store.flushPendingSaves()
            AppExecutors.startThread("hrips-exit") {
                store.flushToDiskBlocking()
                if (mask != 0) store.finishExitClear()
                mainHandler.post {
                    activity.finishAndRemoveTask()
                    mainHandler.postDelayed({ kotlin.system.exitProcess(0) }, EXIT_DELAY_MS)
                }
            }
        }
        if (mask == 0) {
            finish()
            return
        }
        store.markExitClearPending(mask)
        // Если движок так и не ответит, выходим всё равно: отметка останется, и очистка доделается при запуске.
        mainHandler.postDelayed({ finish() }, EXIT_CLEAR_TIMEOUT_MS)
        runExitClear(activity, mask, blockTabSaves = true) { mainHandler.post { finish() } }
    }

    /** Доделывает очистку при выходе, если в прошлый раз её прервали. Вызывать до [restore]. */
    fun resumePendingExitClear(context: Context) {
        val mask = ExitClear.sanitize(store.pendingExitClear)
        if (mask == 0) return
        AppLog.w("Exit", "прошлая очистка при выходе не закончилась, доделываю: ${ExitClear.describe(mask)}")
        if (ExitClear.has(mask, ExitClear.TABS)) TabRootPrefetch.take()?.cancel(true)
        runExitClear(context, mask, blockTabSaves = false) { mainHandler.post { store.finishExitClear() } }
    }

    private fun runExitClear(context: Context, mask: Int, blockTabSaves: Boolean, onDone: () -> Unit) {
        if (ExitClear.has(mask, ExitClear.TABS)) {
            if (blockTabSaves) store.deleteSavedTabs() else store.deleteSavedTabsKeepSaving()
            closedTabs.clear()
        }
        if (ExitClear.has(mask, ExitClear.HISTORY)) {
            store.clearHistory()
            store.clearSearches()
            closedTabs.clear()
        }
        if (mask and (ExitClear.TABS or ExitClear.HISTORY or ExitClear.CACHE) != 0) {
            runCatching { TabThumbs.clear(context) }
            runCatching { PageImages.clear(context) }
        }
        clearData(ExitClear.has(mask, ExitClear.COOKIES), ExitClear.has(mask, ExitClear.CACHE), onDone)
    }
}

class TranslatePrompt(val tab: Tab, val from: String, val to: String, val bytes: Long)

object Connectivity {
    @Volatile
    var online = true
}
