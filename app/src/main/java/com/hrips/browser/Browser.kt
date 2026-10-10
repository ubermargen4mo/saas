package com.hrips.browser

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import android.content.ComponentCallbacks2
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
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
    /** Группы вкладок (цвет и название). Пустые группы убираются сами, см. [pruneGroups]. */
    val groups get() = tabManager.groups
    /** Недавно закрытые обычные вкладки (до 15), только в памяти. Приватные сюда не попадают. */
    val closedTabs get() = tabManager.closedTabs
    /** Открытое контекстное меню (null = нет). Рисуется в BrowserScreen. */
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
        get() = tabManager.current ?: error("Browser has no tabs")

    private val engine = BrowserEngine(runtime)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveState() }
    private var appVisible = false
    val isAppVisible: Boolean get() = appVisible

    /** Скачивание по адресу (из контекстного меню): запрос идёт через движок, дальше обычная плашка "Скачать файл?". */
    fun saveUrl(uri: String, referrer: String?, incognito: Boolean = false) {
        fetch(uri, referrer, incognito) { r ->
            if (r != null && r.statusCode in 200..299) downloads.request(r, incognito, referrer) else downloads.toast("Не удалось скачать файл")
        }
    }

    /** Запрос через движок (с cookies и referer страницы). Поток ответа читать не в главном потоке. */
    fun fetch(uri: String, referrer: String?, incognito: Boolean = false, onDone: (WebResponse?) -> Unit) =
        engine.fetch(uri, referrer, incognito, onDone)

    /** Запрос для докачки файла: с Range / If-Range. Ответ приходит в главном потоке, читать его надо в фоновом. */
    fun fetchRange(
        uri: String,
        referrer: String?,
        incognito: Boolean,
        range: String?,
        ifRange: String?,
        onDone: (WebResponse?) -> Unit,
    ) = engine.fetchRange(uri, referrer, incognito, range, ifRange, onDone)

    /** Очистка данных сайтов. История браузера чистится отдельно, в Store. */
    fun clearData(cookies: Boolean, cache: Boolean, onDone: () -> Unit) =
        engine.clearData(cookies, cache, onDone)

    /** Обновляет visible/focused state GeckoSession и уведомляет WebExtensions о выбранной вкладке. */
    fun setTabActive(tab: Tab, active: Boolean, focused: Boolean = active) {
        if (active) tab.ensureLoaded()
        if (tab.setVisibility(active, focused)) {
            runCatching { engine.setTabActive(tab.session, active) }
        }
    }

    /** Activity lifecycle boundary for Gecko sessions. Inactive sessions use substantially less memory. */
    fun onForeground() {
        appVisible = true
        downloads.setAppVisible(true)
        tabs.forEach { tab ->
            val active = tab === current
            setTabActive(tab, active, active)
        }
    }

    fun onBackground() {
        appVisible = false
        downloads.setAppVisible(false)
        tabs.forEach { tab ->
            setTabActive(tab, false, false)
        }
        scheduleStateSave()
    }

    /**
     * Optional memory-pressure policy. Inspired by Chromium Android/Cobalt: background tabs are
     * expendable resources, but we only evict them when the user explicitly enables this setting.
     */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        if (!store.suspendTabsOnMemoryPressure) return
        val keepLive = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> 1
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 3
            else -> return
        }
        var live = tabs.count { it.session.isOpen }
        if (live <= keepLive) return
        var suspendedCount = 0
        val candidates = tabs
            .filter { it !== current && it.session.isOpen && !it.loading && !it.fullscreen && !media.isPlaying(it.session) }
            .sortedBy { it.lastActivatedAt() }
        for (tab in candidates) {
            if (live <= keepLive) break
            if (tab.suspend()) {
                live--
                suspendedCount++
            }
        }
        if (suspendedCount > 0) scheduleStateSave()
    }

    /** Session state changes are frequent enough that persistence is debounced.
     * Every new state event resets the timer, so a Gecko flush triggered by setActive(false)
     * gets a chance to publish its newest SessionState before we serialize the tabs.
     */
    private fun scheduleStateSave() {
        mainHandler.removeCallbacks(saveRunnable)
        mainHandler.postDelayed(saveRunnable, 400L)
    }

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
        // В приватной вкладке история не пишется
        onVisited = { u, t -> if (!isPrivate) store.addHistory(u, t) },
        onStateChanged = { if (!isPrivate) scheduleStateSave() },
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
        deferOpen = deferOpen,
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

    /** Вкладка, которую просит открыть страница. Сессию возвращаем неоткрытой, движок откроет её сам. */
    private fun openPopup(uri: String): GeckoSession {
        val opener = tabs.getOrNull(currentIndex)
        // Окно, открытое из приватной вкладки, тоже приватное
        val tab = create(uri, openNow = false, isPrivate = opener?.isPrivate == true)
        tab.parent = opener
        tabManager.add(tab)
        return tab.session
    }

    /** Включает или выключает версию для ПК для сайта: запоминает и применяет во всех открытых вкладках этого сайта. */
    /** Вкладка для расширения (его страница настроек, tabs.create). Сессия уже открыта. */
    fun openForExtension(url: String, active: Boolean, engineWillLoad: Boolean = false): GeckoSession {
        val tab = create(url, openNow = !engineWillLoad)
        tabManager.add(tab, select = active)
        if (appVisible) onForeground()
        return tab.session
    }

    fun setSiteDesktop(host: String, on: Boolean) {
        store.setDesktopSite(host, on)
        tabs.forEach { if (!it.isPrivate && it.siteHost() == host && it.desktopMode != on) it.applyDesktop(on, reload = true) }
    }

    /** Сброс версии для ПК на всех сайтах. */
    fun resetDesktopSites() {
        val hosts = store.desktopSites.toList()
        store.clearDesktopSites()
        hosts.forEach { h -> tabs.forEach { if (!it.isPrivate && it.siteHost() == h && it.desktopMode) it.applyDesktop(false, reload = true) } }
    }

    fun setTracking(on: Boolean) {
        store.updateTracking(on)
        applyTrackingProtection(runtime, on)
    }

    /** Применяет HTTPS-only, DoH, GPC и удаление tracking-параметров из настроек; новые значения действуют на следующие загрузки. */
    fun applyPrivacy() = PrivacyEngine.apply(runtime, store)

    /**
     * [returnToPrevious]: «назад» на первой странице новой вкладки закрывает её и возвращает на ту, откуда открыли.
     * Выключено там, где вкладку создали не из страницы (переключатель вкладок, внешняя ссылка).
     */
    fun newTab(url: String = "", incognito: Boolean = false, returnToPrevious: Boolean = true) {
        val previous = tabManager.current
        val tab = create(url, isPrivate = incognito)
        // Пустую вкладку («+») не привязываем: возврат нужен, когда ссылку открыли из другой вкладки
        if (returnToPrevious && url.isNotBlank()) tab.returnTo = previous
        tabManager.add(tab)
        if (appVisible) {
            previous?.let { setTabActive(it, false, false) }
            setTabActive(tab, true, true)
        }
        scheduleStateSave()
    }

    /** Переставляет вкладку (перетаскивание в полосе вкладок), выбранная остаётся выбранной. */
    fun moveTab(from: Int, to: Int) {
        if (from == to || from !in tabs.indices || to !in tabs.indices) return
        tabManager.move(from, to)
        scheduleStateSave()
    }

    /** Переключается на вкладку сайта (нужно, когда нажали на его уведомление). */
    fun focusSite(source: String?) {
        val host = runCatching { Uri.parse(source.orEmpty()).host }.getOrNull()
            ?.lowercase()
            ?.removePrefix("www.")
            ?: source?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            ?: return
        val i = tabs.indexOfFirst { it.siteHost() == host }
        if (i >= 0) {
            currentIndex = i
            if (appVisible) onForeground()
        }
    }

    fun groupOf(tab: Tab): TabGroup? = tab.group?.let { id -> groups.firstOrNull { it.id == id } }

    /** Создаёт группу и кладёт в неё вкладку. */
    fun createGroup(tab: Tab, name: String, color: Int): TabGroup {
        val g = TabGroup(java.util.UUID.randomUUID().toString(), name, color)
        groups.add(g)
        tab.group = g.id
        pruneGroups()
        scheduleStateSave()
        return g
    }

    /** Переносит вкладку в группу; null - убрать из группы. */
    fun setGroup(tab: Tab, group: TabGroup?) {
        if (tab.isPrivate) return
        tab.group = group?.id
        pruneGroups()
        scheduleStateSave()
    }

    /** Убирает группы, в которых не осталось вкладок. */
    fun pruneGroups() {
        groups.removeAll { g -> tabs.none { it.group == g.id } }
    }

    /** Расформировать группу: вкладки остаются, группа исчезает. */
    fun ungroup(g: TabGroup) {
        tabs.forEach { if (it.group == g.id) it.group = null }
        groups.remove(g)
        scheduleStateSave()
    }

    /** Закрыть все вкладки группы (их можно вернуть из «Недавно закрытых»). */
    fun closeGroup(g: TabGroup) {
        val doomed = tabs.filter { it.group == g.id }
        if (doomed.isEmpty()) return
        // Добавляем в том же порядке, что и последовательное закрытие справа налево: последняя
        // вкладка группы остаётся первой в «Недавно закрытых». При этом lifecycle обновляем один раз.
        doomed.asReversed().forEach { tab ->
            if (!tab.isPrivate && !tab.home && tab.url.isNotBlank()) {
                closedTabs.add(0, ClosedTab(tab.snapshot(), groupOf(tab)))
            }
            tab.close()
        }
        while (closedTabs.size > 15) closedTabs.removeAt(closedTabs.lastIndex)
        tabManager.removeAll { it.group == g.id }
        groups.remove(g)
        if (tabs.isEmpty()) newTab()
        privateTabRemoved()
        if (appVisible) onForeground()
        scheduleStateSave()
    }

    /** Возвращает закрытую вкладку вместе с историей страницы и группой (если группы уже нет, она создаётся заново). */
    fun reopen(c: ClosedTab) {
        closedTabs.remove(c)
        val tab = create(c.snap.url, savedState = c.snap.state, savedTitle = c.snap.title, tabId = c.snap.id)
        c.group?.let { g ->
            if (groups.none { it.id == g.id }) groups.add(g)
            tab.group = g.id
        }
        // Если открыта только пустая вкладка (после закрытия последней), она не нужна
        val blank = tabs.singleOrNull()?.takeIf { it.home && !it.isPrivate }
        if (blank != null) tabManager.remove(blank)
        tabManager.add(tab)
        if (blank != null) blank.close()
        scheduleStateSave()
    }

    fun reopenLast() {
        closedTabs.firstOrNull()?.let { reopen(it) }
    }

    /** Откуда открыта вкладка (если та ещё жива и режим тот же: из приватной в обычную не возвращаем). */
    fun openerOf(tab: Tab): Tab? =
        (tab.returnTo ?: tab.parent)?.takeIf { it !== tab && it.isPrivate == tab.isPrivate && tabs.contains(it) }

    /** Жест «назад» на первой странице вкладки: закрыть её и вернуться на вкладку, из которой её открыли. */
    fun returnToOpener(tab: Tab) {
        val opener = openerOf(tab) ?: return
        val index = tabs.indexOf(tab)
        if (index < 0) return
        closeTab(index)
        val back = tabs.indexOf(opener)
        if (back >= 0) {
            currentIndex = back
            if (appVisible) onForeground()
        }
    }

    fun closeTab(index: Int) {
        val closed = tabs.getOrNull(index) ?: return
        if (!closed.isPrivate && !closed.home && closed.url.isNotBlank()) {
            closedTabs.add(0, ClosedTab(closed.snapshot(), groupOf(closed)))
            while (closedTabs.size > 15) closedTabs.removeAt(closedTabs.lastIndex)
        }
        closed.close()
        tabManager.removeAt(index)
        pruneGroups()
        if (tabs.isEmpty()) newTab()
        if (closed.isPrivate) privateTabRemoved()
        if (appVisible) onForeground()
        scheduleStateSave()
    }

    val privateCount: Int get() = tabs.count { it.isPrivate }

    /** Закрыть все приватные вкладки сразу. Выбранной остаётся прежняя обычная вкладка, если она была. */
    fun closePrivateTabs() {
        val doomed = tabs.filter { it.isPrivate }
        if (doomed.isEmpty()) return
        val keep = tabs.getOrNull(currentIndex)?.takeIf { !it.isPrivate }
        doomed.forEach { it.close() }
        tabManager.removeAll { it.isPrivate }
        if (tabs.isEmpty()) {
            newTab()
        } else {
            currentIndex = keep?.let { tabs.indexOf(it) }?.takeIf { it >= 0 } ?: tabs.lastIndex
        }
        privateTabRemoved()
        if (appVisible) onForeground()
        scheduleStateSave()
    }

    /** Если приватных вкладок не осталось, убираем из приложения всё, что о них помнили (список загрузок). */
    private fun privateTabRemoved() {
        if (tabs.none { it.isPrivate }) downloads.clearPrivate()
    }

    /** Восстанавливает вкладки с прошлого запуска. Возвращает true, если было что восстанавливать. */
    fun restore(): Boolean {
        val restored = store.loadTabRestore()
        val snaps = restored.tabs
        if (snaps.isEmpty()) return false
        groups.clear()
        groups.addAll(restored.groups)
        val selected = restored.index.coerceIn(0, snaps.lastIndex)
        // Открываем движок только для выбранной вкладки; остальные поднимутся при первом выборе (см. Tab.ensureLoaded)
        snaps.forEachIndexed { i, snap ->
            val tab = create(snap.url, savedState = snap.state, savedTitle = snap.title, tabId = snap.id, deferOpen = i != selected)
            tab.group = snap.group
            tabManager.add(tab, select = false)
        }
        pruneGroups()
        currentIndex = selected
        if (appVisible) onForeground()
        return true
    }

    fun saveState() {
        if (tabs.isEmpty()) return
        // Приватные вкладки на диск не пишутся: после перезапуска их не будет
        val regular = tabs.filter { !it.isPrivate }
        val cur = tabs.getOrNull(currentIndex)
        val idx = regular.indexOf(cur).takeIf { it >= 0 } ?: regular.lastIndex.coerceAtLeast(0)
        pruneGroups()
        store.saveTabSnapsAsync(regular.map { it.snapshot() }, idx, groups.toList())
    }
}
