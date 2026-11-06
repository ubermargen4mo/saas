package com.hrips.browser

import android.content.Context
import android.net.Uri
import android.util.JsonWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Entry(val url: String, val title: String, val time: Long)

fun siteKey(url: String?): String? {
    val parsed = runCatching { url?.let(Uri::parse) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    return parsed.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
}

fun plainHost(url: String?): String? =
    runCatching { url?.let(Uri::parse)?.host }.getOrNull()?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }

class TabSnap(
    val url: String,
    val title: String,
    val state: String?,
    val id: String = java.util.UUID.randomUUID().toString(),
)

class TabRestore(val tabs: List<TabSnap>, val index: Int)

private const val TABS_FILE = "tabs.json"
private const val HISTORY_SAVE_DELAY_MS = 3_000L
private const val TAB_SCHEMA_VERSION = 1
private const val MAX_TAB_STATE_FILE_BYTES = 32L * 1024L * 1024L

private fun readTabRootFile(file: java.io.File): JSONObject? = runCatching {
    if (!file.isFile || file.length() !in 1L..MAX_TAB_STATE_FILE_BYTES) return@runCatching null
    JSONObject(file.readText(Charsets.UTF_8))
}.getOrNull()

private fun readBestTabRoot(dir: java.io.File): JSONObject? =
    listOf(java.io.File(dir, TABS_FILE), java.io.File(dir, "$TABS_FILE.tmp"), java.io.File(dir, "$TABS_FILE.bak"))
        .mapNotNull { f ->
            readTabRootFile(f)?.takeIf {
                it.optInt("schema", 0) in 1..TAB_SCHEMA_VERSION && it.optJSONArray("tabs") != null
            }
        }
        .maxByOrNull { it.optLong("savedAt", 0L) }

internal object TabRootPrefetch {
    private var task: java.util.concurrent.FutureTask<JSONObject?>? = null

    @Synchronized
    fun start(dir: java.io.File) {
        if (task != null) return
        val t = java.util.concurrent.FutureTask<JSONObject?> { readBestTabRoot(dir) }
        task = t
        Thread(t, "hrips-tabs-prefetch").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    @Synchronized
    fun take(): java.util.concurrent.FutureTask<JSONObject?>? = task.also { task = null }
}

const val PUSH_VIA_GOOGLE = 0
const val PUSH_VIA_APP = 1

const val TABS_STYLE_STRIP = 0
const val TABS_STYLE_INLINE = 1
const val TABS_BELOW = 0
const val TABS_ABOVE = 1
const val TABS_LEFT = 2
const val TABS_ALWAYS = 0
const val TABS_MORE_THAN_TWO = 1
const val TABS_NEVER = 2

const val THEME_AUTO = 0

const val THEME_MODE_SYSTEM = 0
const val THEME_MODE_LIGHT = 1
const val THEME_MODE_DARK = 2

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("hrips", Context.MODE_PRIVATE)

    private fun put(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    private fun put(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    private fun put(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    private fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    private fun removePref(key: String) = prefs.edit().remove(key).apply()
    private fun putStrings(key: String, list: List<String>) = put(key, JSONArray(list.toList()).toString())
    private fun readStrings(key: String): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

    // История, закладки и плитки лежат отдельно от настроек: SharedPreferences переписывает файл целиком,
    // и раньше каждое переключение настройки заново сохраняло сотни записей истории (и ждало этого в onStop).
    private val listPrefs = context.getSharedPreferences("hrips_lists", Context.MODE_PRIVATE)
    private val tabsFile = java.io.File(context.filesDir, TABS_FILE)
    private val tabsTmpFile = java.io.File(context.filesDir, "$TABS_FILE.tmp")
    private val tabsBackupFile = java.io.File(context.filesDir, "$TABS_FILE.bak")
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hrips-store").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private class TabSaveJob(val build: () -> List<TabSnap>, val index: Int)
    private val pendingTabSave = AtomicReference<TabSaveJob?>(null)
    private val tabSaveWorkerRunning = AtomicBoolean(false)
    private val entrySaveLock = Any()
    private val pendingEntrySaves = LinkedHashMap<String, List<Entry>>()
    private val entrySaveWorkerRunning = AtomicBoolean(false)

    val desktopSites = mutableStateListOf<String>().apply { addAll(readStrings("desktopSites")) }

    fun isDesktopSite(host: String?) = host != null && host in desktopSites

    fun setDesktopSite(host: String, on: Boolean) {
        if (on) { if (host !in desktopSites) desktopSites.add(0, host) } else desktopSites.remove(host)
        putStrings("desktopSites", desktopSites)
    }

    fun clearDesktopSites() {
        desktopSites.clear()
        removePref("desktopSites")
    }

    val bookmarks = mutableStateListOf<Entry>()
    val history = mutableStateListOf<Entry>()
    val speedDial = mutableStateListOf<Entry>()
    val searches = mutableStateListOf<String>()

    var suggestionsOn by mutableStateOf(prefs.getBoolean("suggest", true))
        private set

    var themeSeed by mutableIntStateOf(prefs.getInt("theme_seed", THEME_AUTO))
        private set

    fun updateThemeSeed(argb: Int) {
        if (argb == themeSeed) return
        themeSeed = argb
        put("theme_seed", argb)
    }

    var themeMode by mutableIntStateOf(prefs.getInt("theme_mode", THEME_MODE_SYSTEM))
        private set

    fun updateThemeMode(mode: Int) {
        if (mode == themeMode) return
        themeMode = mode
        put("theme_mode", mode)
    }

    var keepPageInBackground by mutableStateOf(prefs.getBoolean("keep_page_bg", true))
        private set

    fun updateKeepPageInBackground(on: Boolean) {
        keepPageInBackground = on
        put("keep_page_bg", on)
    }

    /** Сколько минут после сворачивания страница держится в памяти с высоким приоритетом; 0 = пока системе хватает памяти. */
    var keepAliveMinutes by mutableIntStateOf(prefs.getInt("keep_page_minutes", 60))
        private set

    fun updateKeepAliveMinutes(minutes: Int) {
        keepAliveMinutes = minutes
        put("keep_page_minutes", minutes)
    }

    var suspendTabsOnMemoryPressure by mutableStateOf(prefs.getBoolean("suspend_tabs_pressure", false))
        private set

    fun updateSuspendTabsOnMemoryPressure(on: Boolean) {
        suspendTabsOnMemoryPressure = on
        put("suspend_tabs_pressure", on)
    }

    fun updateSuggestions(on: Boolean) {
        suggestionsOn = on
        put("suggest", on)
    }

    fun addSearch(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        searches.removeAll { it.equals(t, ignoreCase = true) }
        searches.add(0, t)
        while (searches.size > 50) searches.removeAt(searches.lastIndex)
        putStrings("searches", searches)
    }

    fun removeSearch(text: String) {
        if (!searches.removeAll { it.equals(text, ignoreCase = true) }) return
        if (searches.isEmpty()) removePref("searches")
        else putStrings("searches", searches)
    }

    fun clearSearches() {
        searches.clear()
        removePref("searches")
    }

    var firstRunDone by mutableStateOf(prefs.getBoolean("firstrun", false))
        private set

    fun finishFirstRun() {
        firstRunDone = true
        put("firstrun", true)
    }

    var pipEnabled by mutableStateOf(prefs.getBoolean("pip", true))
        private set

    fun updatePip(on: Boolean) {
        pipEnabled = on
        put("pip", on)
    }

    var mediaControls by mutableStateOf(prefs.getBoolean("mediactl", true))
        private set

    fun updateMediaControls(on: Boolean) {
        mediaControls = on
        put("mediactl", on)
    }

    var askBeforeDownload by mutableStateOf(prefs.getBoolean("dlask", true))
        private set

    fun updateAskBeforeDownload(on: Boolean) {
        askBeforeDownload = on
        put("dlask", on)
    }

    var dlTree by mutableStateOf(prefs.getString("dl_tree", null))
        private set

    fun updateDlTree(uri: String?) {
        dlTree = uri
        prefs.edit().apply { if (uri == null) remove("dl_tree") else putString("dl_tree", uri) }.apply()
    }

    var tabsStyle by mutableIntStateOf(prefs.getInt("tabs_style", TABS_STYLE_STRIP).coerceIn(TABS_STYLE_STRIP, TABS_STYLE_INLINE))
        private set

    fun updateTabsStyle(v: Int) {
        tabsStyle = v
        tabsOverride = null
        put("tabs_style", v)
    }

    var tabsPos by mutableIntStateOf(prefs.getInt("tabs_pos", TABS_BELOW).coerceIn(TABS_BELOW, TABS_LEFT))
        private set

    var tabsShow by mutableIntStateOf(prefs.getInt("tabs_show", TABS_MORE_THAN_TWO).coerceIn(TABS_ALWAYS, TABS_NEVER))
        private set

    var tabsOverride by mutableStateOf<Boolean?>(null)
        private set

    fun updateTabsPos(v: Int) {
        tabsPos = v
        put("tabs_pos", v)
    }

    fun updateTabsShow(v: Int) {
        tabsShow = v
        tabsOverride = null
        put("tabs_show", v)
    }

    fun overrideTabs(visible: Boolean?) {
        tabsOverride = visible
    }

    var allowPrivateShots by mutableStateOf(prefs.getBoolean("pshots", false))
        private set

    fun updatePrivateShots(on: Boolean) {
        allowPrivateShots = on
        put("pshots", on)
    }

    var trackingProtection by mutableStateOf(prefs.getBoolean("tp", true))
        private set

    fun updateTracking(on: Boolean) {
        trackingProtection = on
        put("tp", on)
    }

    /** Строгая защита: действует только при включённой защите от трекеров (см. [applyTrackingProtection]). */
    var strictProtection by mutableStateOf(prefs.getBoolean("tp_strict", false))
        private set

    fun updateStrictProtection(on: Boolean) {
        strictProtection = on
        put("tp_strict", on)
    }

    /** Удалять данные при выходе через «Выйти» в меню. Что именно удалять, задаёт [clearOnExitMask] (биты [ExitClear]). */
    var clearOnExit by mutableStateOf(prefs.getBoolean("exit_clear", false))
        private set
    var clearOnExitMask by mutableIntStateOf(prefs.getInt("exit_clear_mask", ExitClear.DEFAULT))
        private set

    fun updateClearOnExit(on: Boolean) {
        clearOnExit = on
        put("exit_clear", on)
    }

    fun updateClearOnExitMask(mask: Int) {
        clearOnExitMask = ExitClear.sanitize(mask)
        put("exit_clear_mask", clearOnExitMask)
    }

    /** Очистка при выходе, которая началась, но не успела закончиться (процесс убили или он упал). 0, если такой нет. */
    val pendingExitClear: Int get() = prefs.getInt("exit_clear_pending", 0)

    // commit, а не apply: отметка должна лечь на диск до того, как процесс завершится.
    @android.annotation.SuppressLint("ApplySharedPref")
    fun markExitClearPending(mask: Int) {
        prefs.edit().putInt("exit_clear_pending", mask).commit()
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    fun finishExitClear() {
        prefs.edit().remove("exit_clear_pending").commit()
    }

    /** Главный выключатель уведомлений сайтов: выключен — сайты не спрашивают разрешение, уведомления и push не показываются. */
    var siteNotifications by mutableStateOf(prefs.getBoolean("site_notifications", true))
        private set

    fun updateSiteNotifications(on: Boolean) {
        siteNotifications = on
        put("site_notifications", on)
    }

    /** Веб-push. По умолчанию включён: доставка через Google (FCM), если на телефоне есть сервисы Google. */
    var webPush by mutableStateOf(prefs.getBoolean("webpush", true))
        private set

    /** Через что доставлять push: [PUSH_VIA_GOOGLE] (встроенный FCM) или [PUSH_VIA_APP] (приложение UnifiedPush, например ntfy). */
    var pushVia by mutableIntStateOf(prefs.getInt("push_via", PUSH_VIA_GOOGLE))
        private set

    fun updatePushVia(mode: Int) {
        pushVia = mode
        put("push_via", mode)
    }

    fun updateWebPush(on: Boolean) {
        webPush = on
        put("webpush", on)
    }

    val toolbarPins = mutableStateListOf<String>().apply {
        addAll(prefs.getString("toolbar_pins", "").orEmpty().split(',').filter { it.isNotBlank() })
    }

    fun toggleToolbarPin(id: String) {
        if (!toolbarPins.remove(id)) toolbarPins.add(id)
        put("toolbar_pins", toolbarPins.joinToString(","))
    }

    var autoUpdate by mutableStateOf(prefs.getBoolean("auto_update", true))
        private set

    fun updateAutoUpdate(on: Boolean) {
        autoUpdate = on
        put("auto_update", on)
    }

    var logging by mutableStateOf(prefs.getBoolean("logging", false))
        private set

    fun updateLogging(on: Boolean) {
        logging = on
        put("logging", on)
    }

    val startupFailures: Int = prefs.getInt("startup_failures", 0)

    fun markStartupBegin() {
        prefs.edit().putInt("startup_failures", startupFailures + 1).commit()
    }

    fun markStartupStable() {
        put("startup_failures", 0)
    }

    var lastUpdateCheck: Long = prefs.getLong("last_update_check", 0L)
        private set

    fun markUpdateChecked() {
        lastUpdateCheck = System.currentTimeMillis()
        put("last_update_check", lastUpdateCheck)
    }

    var gpc by mutableStateOf(prefs.getBoolean("gpc", true))
        private set

    fun updateGpc(on: Boolean) {
        gpc = on
        put("gpc", on)
    }

    var openInApps by mutableIntStateOf(prefs.getInt("open_in_apps", ExternalLinks.MODE_ASK))
        private set

    fun updateOpenInApps(mode: Int) {
        openInApps = mode
        put("open_in_apps", mode)
    }

    var stripTrackingParams by mutableStateOf(prefs.getBoolean("strip_tracking_params", true))
        private set

    fun updateStripTrackingParams(on: Boolean) {
        stripTrackingParams = on
        put("strip_tracking_params", on)
    }

    var httpsMode by mutableIntStateOf(prefs.getInt("https", PrivacyEngine.HTTPS_OFF))
        private set

    fun updateHttpsMode(mode: Int) {
        httpsMode = mode
        put("https", mode)
    }

    var dohMode by mutableIntStateOf(prefs.getInt("doh", PrivacyEngine.DOH_OFF))
        private set
    var dohProvider by mutableStateOf(prefs.getString("doh_provider", "cloudflare") ?: "cloudflare")
        private set
    var dohCustom by mutableStateOf(prefs.getString("doh_custom", "") ?: "")
        private set

    fun updateDohMode(mode: Int) {
        dohMode = mode
        put("doh", mode)
    }

    fun updateDohProvider(id: String) {
        dohProvider = id
        put("doh_provider", id)
    }

    fun updateDohCustom(uri: String) {
        dohCustom = uri
        put("doh_custom", uri)
    }

    fun dohUri(): String? =
        if (dohProvider == PrivacyEngine.CUSTOM) dohCustom.takeIf { PrivacyEngine.isValidDoh(it) }
        else PrivacyEngine.dohProviders.firstOrNull { it.id == dohProvider }?.uri

    var pageImages by mutableStateOf(prefs.getBoolean("page_images", true))
        private set

    var tabsView by mutableIntStateOf(prefs.getInt("tabs_view", 1).coerceIn(0, 2))
        private set

    fun updateTabsView(v: Int) {
        tabsView = v.coerceIn(0, 2)
        put("tabs_view", tabsView)
    }

    class TabGroup(val id: String, name: String, icon: Int) {
        var name by mutableStateOf(name)
        var icon by mutableIntStateOf(icon)
    }

    var personalName by mutableStateOf(prefs.getString("tab_group_personal_name", null) ?: "Личные")
        private set
    var personalIcon by mutableIntStateOf(prefs.getInt("tab_group_personal_icon", 6))
        private set
    val tabGroups = mutableStateListOf<TabGroup>().also { list ->
        runCatching {
            val arr = JSONArray(prefs.getString("tab_groups", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(TabGroup(o.getString("id"), o.getString("name"), o.optInt("icon", 1)))
            }
        }
    }
    val tabGroupOf = androidx.compose.runtime.mutableStateMapOf<String, String>().also { map ->
        runCatching {
            val o = JSONObject(prefs.getString("tab_group_of", "{}") ?: "{}")
            o.keys().forEach { k -> map[k] = o.getString(k) }
        }
    }
    var activeTabGroup by mutableStateOf("")

    private fun saveGroups() {
        val arr = JSONArray()
        tabGroups.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon)) }
        val of = JSONObject()
        tabGroupOf.forEach { (k, v) -> of.put(k, v) }
        prefs.edit()
            .putString("tab_groups", arr.toString())
            .putString("tab_group_of", of.toString())
            .putString("tab_group_personal_name", personalName)
            .putInt("tab_group_personal_icon", personalIcon)
            .apply()
    }

    val pinnedTabs = mutableStateListOf<String>().apply { addAll(readStrings("tabs_pinned")) }

    fun isPinned(tabId: String): Boolean = tabId in pinnedTabs

    fun setPinned(tabId: String, on: Boolean) {
        if (on) {
            if (tabId !in pinnedTabs) pinnedTabs.add(tabId)
        } else {
            pinnedTabs.remove(tabId)
        }
        putStrings("tabs_pinned", pinnedTabs)
    }

    fun groupOf(tabId: String): String = tabGroupOf[tabId]?.takeIf { id -> tabGroups.any { it.id == id } } ?: ""

    fun assignTab(tabId: String, group: String) {
        if (group.isEmpty()) tabGroupOf.remove(tabId) else tabGroupOf[tabId] = group
        saveGroups()
    }

    fun addTabGroup(name: String, icon: Int): TabGroup {
        val g = TabGroup(java.util.UUID.randomUUID().toString(), name.trim().ifEmpty { "Группа" }, icon)
        tabGroups.add(g)
        saveGroups()
        return g
    }

    fun editTabGroup(id: String, name: String, icon: Int) {
        val n = name.trim()
        if (id.isEmpty()) {
            if (n.isNotEmpty()) personalName = n
            personalIcon = icon
        } else tabGroups.firstOrNull { it.id == id }?.let { if (n.isNotEmpty()) it.name = n; it.icon = icon }
        saveGroups()
    }

    fun deleteTabGroup(id: String) {
        if (id.isEmpty()) return
        tabGroups.removeAll { it.id == id }
        tabGroupOf.entries.removeAll { it.value == id }
        if (activeTabGroup == id) activeTabGroup = ""
        saveGroups()
    }

    fun updatePageImages(on: Boolean) {
        pageImages = on
        PageImages.enabled = on
        put("page_images", on)
    }

    init {
        PageImages.enabled = pageImages
        SearchEngines.current = SearchEngines.all.firstOrNull { it.name == prefs.getString("engine", null) }
            ?: SearchEngines.all[0]
        searches.addAll(readStrings("searches"))
        migrateLists()
        bookmarks.addAll(load("bookmarks"))
        history.addAll(load("history"))
        if (listPrefs.contains("dial")) {
            speedDial.addAll(load("dial"))
        } else {
            speedDial.addAll(
                listOf(
                    Entry("https://ya.ru", "Яндекс", 0),
                    Entry("https://www.youtube.com", "YouTube", 0),
                    Entry("https://github.com", "GitHub", 0),
                )
            )
            save("dial", speedDial)
        }
    }

    private fun migrateLists() {
        val keys = listOf("bookmarks", "history", "dial").filter { prefs.contains(it) }
        if (keys.isEmpty()) return
        val toLists = listPrefs.edit()
        keys.forEach { k -> if (!listPrefs.contains(k)) toLists.putString(k, prefs.getString(k, "[]")) }
        if (!toLists.commit()) return
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
    }

    private fun load(key: String): List<Entry> = runCatching {
        val arr = JSONArray(listPrefs.getString(key, "[]"))
        (0 until arr.length()).mapNotNull {
            runCatching {
                val o = arr.getJSONObject(it)
                val url = o.optString("u").trim().takeIf { it.isNotBlank() } ?: return@runCatching null
                Entry(url, o.optString("t"), o.optLong("d"))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun save(key: String, list: List<Entry>) {
        synchronized(entrySaveLock) { pendingEntrySaves[key] = list.toList() }
        if (entrySaveWorkerRunning.compareAndSet(false, true)) {
            io.execute { drainEntrySaves() }
        }
    }

    private fun drainEntrySaves() {
        try {
            while (true) {
                val batch = synchronized(entrySaveLock) {
                    if (pendingEntrySaves.isEmpty()) null
                    else pendingEntrySaves.toMap().also { pendingEntrySaves.clear() }
                } ?: break
                val edit = listPrefs.edit()
                batch.forEach { (key, safe) ->
                    val arr = JSONArray()
                    safe.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("d", it.time)) }
                    edit.putString(key, arr.toString())
                }
                edit.apply()
            }
        } catch (e: Exception) {
            AppLog.w("Store", "не удалось сохранить списки", e)
        } finally {
            entrySaveWorkerRunning.set(false)
            synchronized(entrySaveLock) {
                if (pendingEntrySaves.isNotEmpty() && entrySaveWorkerRunning.compareAndSet(false, true)) {
                    io.execute { drainEntrySaves() }
                }
            }
        }
    }

    private val historySaveTask = Runnable { saveHistoryNow() }
    private var historyDirty = false

    private fun scheduleHistorySave() {
        historyDirty = true
        val handler = MainThread.handler
        handler.removeCallbacks(historySaveTask)
        handler.postDelayed(historySaveTask, HISTORY_SAVE_DELAY_MS)
    }

    private fun saveHistoryNow() {
        MainThread.handler.removeCallbacks(historySaveTask)
        historyDirty = false
        save("history", history)
    }

    fun flushPendingSaves() {
        if (historyDirty) saveHistoryNow()
    }

    fun setSearchEngine(e: SearchEngine) {
        SearchEngines.current = e
        put("engine", e.name)
    }

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    fun toggleBookmark(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:", ignoreCase = true)) return
        val i = bookmarks.indexOfFirst { it.url == url }
        if (i >= 0) bookmarks.removeAt(i) else bookmarks.add(0, Entry(url, title, System.currentTimeMillis()))
        save("bookmarks", bookmarks)
    }

    fun importBookmarks(entries: List<Entry>): Int {
        val known = bookmarks.mapTo(HashSet()) { it.url }
        val fresh = entries.filter { known.add(it.url) }
        if (fresh.isEmpty()) return 0
        bookmarks.addAll(fresh)
        save("bookmarks", bookmarks)
        return fresh.size
    }

    fun removeBookmark(e: Entry) {
        bookmarks.remove(e)
        save("bookmarks", bookmarks)
    }

    // Для подсветки посещённых ссылок: набор адресов из истории, пересобирается только когда история изменилась.
    private var visitedStamp = Long.MIN_VALUE
    private var visitedSet: HashSet<String> = HashSet()

    private fun visitKey(url: String): String {
        val hash = url.indexOf('#')
        return (if (hash >= 0) url.substring(0, hash) else url).trimEnd('/')
    }

    /** Была ли эта страница в истории (без учёта якоря и закрывающего слэша). Вызывать из главного потока. */
    fun isVisited(url: String): Boolean {
        val stamp = history.size * 31L + (history.firstOrNull()?.time ?: 0L)
        if (stamp != visitedStamp) {
            visitedSet = history.mapTo(HashSet()) { visitKey(it.url) }
            visitedStamp = stamp
        }
        return visitKey(url) in visitedSet
    }

    fun addHistory(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:", ignoreCase = true)) return
        history.removeAll { it.url == url }
        history.add(0, Entry(url, title, System.currentTimeMillis()))
        while (history.size > 500) history.removeAt(history.lastIndex)
        scheduleHistorySave()
    }

    fun removeHistory(e: Entry) {
        history.remove(e)
        saveHistoryNow()
    }

    fun clearHistory() {
        history.clear()
        saveHistoryNow()
    }

    fun addDial(url: String, title: String) {
        speedDial.add(Entry(url, title, System.currentTimeMillis()))
        save("dial", speedDial)
    }

    fun removeDial(e: Entry) {
        speedDial.remove(e)
        save("dial", speedDial)
    }

    fun updateDial(old: Entry, url: String, title: String) {
        val i = speedDial.indexOf(old)
        if (i < 0) return
        speedDial[i] = old.copy(url = url, title = title)
        save("dial", speedDial)
    }

    fun moveDial(from: Int, to: Int) {
        if (from !in speedDial.indices || to !in speedDial.indices || from == to) return
        speedDial.add(to, speedDial.removeAt(from))
    }

    fun saveDial() = save("dial", speedDial)

    @Volatile
    private var tabSavesBlocked = false

    /**
     * Удаляет сохранённые вкладки и запрещает новые сохранения до конца жизни процесса: иначе onStop
     * при выходе успел бы записать вкладки заново. Удаление идёт в той же очереди, что и запись, то есть после неё.
     */
    fun deleteSavedTabs() {
        tabSavesBlocked = true
        pendingTabSave.set(null)
        io.execute {
            listOf(tabsFile, tabsTmpFile, tabsBackupFile).forEach { runCatching { it.delete() } }
        }
        prefs.edit().remove("tabs").remove("tabIndex").apply()
    }

    /** Удаляет сохранённые вкладки сразу, в этом потоке (при запуске, до чтения вкладок). Дальше вкладки сохраняются как обычно. */
    fun deleteSavedTabsKeepSaving() {
        pendingTabSave.set(null)
        listOf(tabsFile, tabsTmpFile, tabsBackupFile).forEach { runCatching { it.delete() } }
        prefs.edit().remove("tabs").remove("tabIndex").apply()
    }

    /**
     * Дожидается, пока все отложенные записи (вкладки, история, настройки) лягут на диск. Блокирует поток,
     * поэтому вызывать только вне главного. Пустой commit ждёт и все ранее поставленные apply этого файла.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun flushToDiskBlocking() {
        runCatching { io.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        prefs.edit().commit()
        listPrefs.edit().commit()
    }

    fun saveTabSnapsDeferred(build: () -> List<TabSnap>, index: Int) {
        if (tabSavesBlocked) return
        pendingTabSave.set(TabSaveJob(build, index))
        if (tabSaveWorkerRunning.compareAndSet(false, true)) {
            io.execute { drainTabSaves() }
        }
    }

    private fun drainTabSaves() {
        try {
            while (true) {
                val job = pendingTabSave.getAndSet(null) ?: return
                writeTabSnaps(job.build(), job.index)
            }
        } finally {
            tabSaveWorkerRunning.set(false)
            if (pendingTabSave.get() != null && tabSaveWorkerRunning.compareAndSet(false, true)) {
                io.execute { drainTabSaves() }
            }
        }
    }

    private fun writeTabSnaps(list: List<TabSnap>, index: Int) {
        runCatching {
            val states = fitStatesToBudget(list)
            FileOutputStream(tabsTmpFile).use { out ->
                val w = JsonWriter(out.bufferedWriter(Charsets.UTF_8))
                w.beginObject()
                w.name("schema").value(TAB_SCHEMA_VERSION.toLong())
                w.name("savedAt").value(System.currentTimeMillis())
                w.name("index").value(index.toLong())
                w.name("tabs").beginArray()
                list.forEachIndexed { i, t ->
                    w.beginObject()
                    w.name("u").value(t.url)
                    w.name("t").value(t.title)
                    states[i]?.let { w.name("s").value(it) }
                    w.name("id").value(t.id)
                    w.endObject()
                }
                w.endArray()
                w.endObject()
                w.flush()
                out.fd.sync()
            }
            if (tabsFile.exists()) Files.move(tabsFile.toPath(), tabsBackupFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Files.move(tabsTmpFile.toPath(), tabsFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { AppLog.w("Store", "не удалось сохранить вкладки", it) }
    }

    private fun fitStatesToBudget(list: List<TabSnap>): List<String?> {
        val budget = MAX_TAB_STATE_FILE_BYTES / 2
        val states = list.mapTo(ArrayList()) { it.state }
        var total = states.sumOf { it?.length?.toLong() ?: 0L }
        if (total <= budget) return states
        var dropped = 0
        for (i in states.indices.sortedByDescending { states[it]?.length ?: 0 }) {
            if (total <= budget) break
            total -= states[i]?.length ?: 0
            states[i] = null
            dropped++
        }
        AppLog.w("Store", "состояния вкладок не влезли в лимит файла, у $dropped вкладок сохранены только адрес и название")
        return states
    }

    private val filesDir = context.filesDir

    private fun loadBestTabRoot(): JSONObject? {
        TabRootPrefetch.take()?.let { task ->
            val prefetched = runCatching { task.get() }.getOrNull()
            if (prefetched != null) return prefetched
        }
        return readBestTabRoot(filesDir)
    }

    private fun parseTabs(root: JSONObject): List<TabSnap> {
        val arr = root.optJSONArray("tabs") ?: throw IllegalStateException("missing tabs")
        return (0 until arr.length()).mapNotNull {
            runCatching {
                val o = arr.getJSONObject(it)
                val url = o.optString("u")
                val title = o.optString("t").take(200)
                TabSnap(url, title, if (o.has("s")) o.optString("s").takeIf { it.isNotBlank() } else null, o.optString("id").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString())
            }.getOrNull()
        }
    }

    fun loadTabRestore(): TabRestore {
        runCatching {
            val root = loadBestTabRoot()
            if (root != null) {
                return TabRestore(parseTabs(root), root.optInt("index", 0))
            }
        }
        val (urls, index) = loadTabs()
        return TabRestore(urls.map { TabSnap(it, "", null) }, index)
    }

    fun loadTabs(): Pair<List<String>, Int> = try {
        val arr = JSONArray(prefs.getString("tabs", "[]"))
        (0 until arr.length()).map { arr.getString(it) } to prefs.getInt("tabIndex", 0)
    } catch (e: Exception) {
        emptyList<String>() to 0
    }

}
