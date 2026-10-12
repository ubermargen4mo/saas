package com.hrips.browser

import android.net.Uri
import android.util.Log
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

private const val TAG = "Browser"

/** Сколько недавно закрытых вкладок помнить. */
private const val MAX_CLOSED_TABS = 15

/** Пауза перед сохранением вкладок: каждое новое событие сдвигает её, пока состояние не «затихнет». */
private const val SAVE_DELAY_MS = 400L

/** То же, но с запасом для моментов, когда рядом идёт анимация (см. [Browser.closeTab] с deferEngine). */
private const val SAVE_DELAY_IDLE_MS = 2500L

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
    /** Недавно закрытые обычные вкладки (до [MAX_CLOSED_TABS]), только в памяти. Приватные сюда не попадают. */
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
        get() = tabManager.current ?: error("В браузере нет вкладок")

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
            // Расширениям не удалось сообщить о выбранной вкладке: вкладка при этом работает, поэтому только лог
            try {
                engine.setTabActive(tab.session, active)
            } catch (e: Exception) {
                Log.w(TAG, "setTabActive не удался", e)
            }
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
        // Приложение уходит с экрана: отложенные сессии закрываем сейчас, жеста уже нет
        flushPendingClose()
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

    /**
     * Состояние вкладок сохраняется с задержкой: каждое новое событие сдвигает таймер, так что сброс состояния
     * движком после setActive(false) успевает опубликовать самое свежее [GeckoSession.SessionState] до сериализации.
     */
    private fun scheduleStateSave(delayMs: Long = SAVE_DELAY_MS) {
        mainHandler.removeCallbacks(saveRunnable)
        mainHandler.postDelayed(saveRunnable, delayMs)
    }

    /** То же, но с запасом: сохранение собирает данные всех вкладок в главном потоке и не должно попасть на анимацию переключателя. */
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

    /** Вкладка, которую просит открыть страница. Сессию возвращаем неоткрытой, движок откроет её сам. */
    private fun openPopup(uri: String): GeckoSession {
        val opener = tabManager.current
        // Окно, открытое из приватной вкладки, тоже приватное
        val tab = create(uri, openNow = false, isPrivate = opener?.isPrivate == true)
        tab.parent = opener
        tabManager.add(tab)
        return tab.session
    }

    /** Вкладка для расширения (его страница настроек, tabs.create). Сессия уже открыта. */
    fun openForExtension(url: String, active: Boolean, engineWillLoad: Boolean = false): GeckoSession {
        val tab = create(url, openNow = !engineWillLoad)
        tabManager.add(tab, select = active)
        if (appVisible) onForeground()
        return tab.session
    }

    /** Включает или выключает версию для ПК для сайта: запоминает и применяет во всех открытых вкладках этого сайта. */
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

    /** Возвращает закрытую вкладку вместе с историей страницы. */
    fun reopen(c: ClosedTab) {
        // Вкладку уже вернули (двойное нажатие, «Вернуть» и пункт списка): второй раз её не создаём, иначе будет дубль с тем же id
        if (!closedTabs.remove(c)) return
        val snap = c.snap()
        // Вкладка с тем же id могла быть закрыта в переключателе, а её сессия ещё не отпущена: отпускаем только её.
        // Остальные отложенные не трогаем: закрытие сессии в главном потоке стоит десятки миллисекунд, а «Вернуть» часто
        // жмут при открытом переключателе, посреди анимации.
        flushPendingClose { it.id == snap.id }
        val tab = create(snap.url, savedState = snap.state, savedTitle = snap.title, tabId = snap.id)
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

    /**
     * Вкладки, убранные из списка переключателем, но чьи сессии движка ещё живы. Закрытие сессии и переключение движка на
     * соседнюю вкладку идут в главном потоке и стоят десятки миллисекунд: посреди жеста смахивания это был заметный рывок.
     * Пока переключатель открыт, карточка пропадает из списка сразу, а движок трогаем позже, когда всё затихло
     * ([flushPendingClose]).
     */
    private val pendingClose = ArrayList<Tab>()

    /**
     * [deferEngine]: только убрать вкладку из списка (и записать в закрытые), а сессию закрыть позже через
     * [flushPendingClose]. Включается из переключателя вкладок, где закрытие не должно трогать движок посреди анимации.
     */
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
        // При отложенном закрытии активные сессии не пересчитываем: если закрыли текущую вкладку, страница под
        // переключателем остаётся прежней (см. BrowserScreen), а новую текущую включит сам экран при выходе
        if (!deferEngine && appVisible) onForeground()
        if (deferEngine) scheduleStateSaveIdle() else scheduleStateSave()
    }

    /**
     * Закрывает сессии вкладок, убранных с [closeTab] (deferEngine = true). Вызывать, когда жест и анимации затихли.
     * [only] ограничивает набор (например, одной вкладкой с нужным id); по умолчанию закрываются все.
     */
    fun flushPendingClose(only: ((Tab) -> Boolean)? = null) {
        if (pendingClose.isEmpty()) return
        val doomed = if (only == null) pendingClose.toList() else pendingClose.filter(only)
        if (doomed.isEmpty()) return
        pendingClose.removeAll(doomed)
        doomed.forEach { it.close() }
        scheduleStateSave()
    }

    val privateCount: Int get() = tabs.count { it.isPrivate }

    /** Закрыть все приватные вкладки сразу. Выбранной остаётся прежняя обычная вкладка, если она была. */
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
            // Отложенно: только выбор в списке, без включения и выключения сессий (их пересчитает экран при выходе)
            if (deferEngine) tabManager.select(target) else currentIndex = target
        }
        privateTabRemoved()
        if (!deferEngine && appVisible) onForeground()
        if (deferEngine) scheduleStateSaveIdle() else scheduleStateSave()
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
        val selected = restored.index.coerceIn(0, snaps.lastIndex)
        // Открываем движок только для выбранной вкладки; остальные поднимутся при первом выборе (см. Tab.ensureLoaded)
        snaps.forEachIndexed { i, snap ->
            val tab = create(snap.url, savedState = snap.state, savedTitle = snap.title, tabId = snap.id, deferOpen = i != selected)
            tabManager.add(tab, select = false)
        }
        currentIndex = selected
        if (appVisible) onForeground()
        return true
    }

    fun saveState() {
        if (tabs.isEmpty()) return
        // Приватные вкладки на диск не пишутся: после перезапуска их не будет
        val regular = tabs.filter { !it.isPrivate }
        val cur = tabManager.current
        val idx = regular.indexOf(cur).takeIf { it >= 0 } ?: regular.lastIndex.coerceAtLeast(0)
        // В главном потоке только собираем данные; сериализация состояний вкладок идёт в потоке записи
        val parts = regular.map { it.saveParts() }
        store.saveTabSnapsDeferred({ parts.map { it.build() } }, idx)
    }
}
