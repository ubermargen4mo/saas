package com.hrips.browser

import android.content.Context
import android.net.Uri
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.FileOutputStream
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

/**
 * Хост адреса в нижнем регистре и без «www.»; null, если адрес не разобрать или хоста нет.
 * Схема не проверяется (в отличие от [siteKey], который принимает только http и https).
 */
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

const val TABS_STYLE_STRIP = 0
const val TABS_STYLE_INLINE = 1
const val TABS_BELOW = 0
const val TABS_ABOVE = 1
const val TABS_LEFT = 2
const val TABS_ALWAYS = 0
const val TABS_MORE_THAN_TWO = 1
const val TABS_NEVER = 2

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("hrips", Context.MODE_PRIVATE)
    private val tabsFile = java.io.File(context.filesDir, "tabs.json")
    private val tabsTmpFile = java.io.File(context.filesDir, "tabs.json.tmp")
    private val tabsBackupFile = java.io.File(context.filesDir, "tabs.json.bak")
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hrips-store").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private class TabSaveJob(val build: () -> List<TabSnap>, val index: Int)
    private val pendingTabSave = AtomicReference<TabSaveJob?>(null)
    private val tabSaveWorkerRunning = AtomicBoolean(false)
    private val entrySaveLock = Any()
    private val pendingEntrySaves = LinkedHashMap<String, List<Entry>>()
    private val entrySaveWorkerRunning = AtomicBoolean(false)

    val desktopSites = mutableStateListOf<String>().apply {
        runCatching {
            val arr = JSONArray(prefs.getString("desktopSites", "[]"))
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
    }

    fun isDesktopSite(host: String?) = host != null && host in desktopSites

    fun setDesktopSite(host: String, on: Boolean) {
        if (on) { if (host !in desktopSites) desktopSites.add(0, host) } else desktopSites.remove(host)
        prefs.edit().putString("desktopSites", JSONArray(desktopSites.toList()).toString()).apply()
    }

    fun clearDesktopSites() {
        desktopSites.clear()
        prefs.edit().remove("desktopSites").apply()
    }

    val bookmarks = mutableStateListOf<Entry>()
    val history = mutableStateListOf<Entry>()
    val speedDial = mutableStateListOf<Entry>()
    val searches = mutableStateListOf<String>()

    var suggestionsOn by mutableStateOf(prefs.getBoolean("suggest", true))
        private set

    var keepPageInBackground by mutableStateOf(prefs.getBoolean("keep_page_bg", true))
        private set

    fun updateKeepPageInBackground(on: Boolean) {
        keepPageInBackground = on
        prefs.edit().putBoolean("keep_page_bg", on).apply()
    }

    var suspendTabsOnMemoryPressure by mutableStateOf(prefs.getBoolean("suspend_tabs_pressure", false))
        private set

    fun updateSuspendTabsOnMemoryPressure(on: Boolean) {
        suspendTabsOnMemoryPressure = on
        prefs.edit().putBoolean("suspend_tabs_pressure", on).apply()
    }

    fun updateSuggestions(on: Boolean) {
        suggestionsOn = on
        prefs.edit().putBoolean("suggest", on).apply()
    }

    fun addSearch(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        searches.removeAll { it.equals(t, ignoreCase = true) }
        searches.add(0, t)
        while (searches.size > 50) searches.removeAt(searches.lastIndex)
        prefs.edit().putString("searches", JSONArray(searches.toList()).toString()).apply()
    }

    fun clearSearches() {
        searches.clear()
        prefs.edit().remove("searches").apply()
    }

    var firstRunDone by mutableStateOf(prefs.getBoolean("firstrun", false))
        private set

    fun finishFirstRun() {
        firstRunDone = true
        prefs.edit().putBoolean("firstrun", true).apply()
    }

    var pipEnabled by mutableStateOf(prefs.getBoolean("pip", true))
        private set

    fun updatePip(on: Boolean) {
        pipEnabled = on
        prefs.edit().putBoolean("pip", on).apply()
    }

    var mediaControls by mutableStateOf(prefs.getBoolean("mediactl", true))
        private set

    fun updateMediaControls(on: Boolean) {
        mediaControls = on
        prefs.edit().putBoolean("mediactl", on).apply()
    }

    var askBeforeDownload by mutableStateOf(prefs.getBoolean("dlask", true))
        private set

    fun updateAskBeforeDownload(on: Boolean) {
        askBeforeDownload = on
        prefs.edit().putBoolean("dlask", on).apply()
    }

    /** Папка загрузок, выбранная пользователем (адрес дерева SAF); null - системная «Загрузки». */
    var dlTree by mutableStateOf(prefs.getString("dl_tree", null))
        private set

    fun updateDlTree(uri: String?) {
        dlTree = uri
        prefs.edit().apply { if (uri == null) remove("dl_tree") else putString("dl_tree", uri) }.apply()
    }

    /** Кольцо загрузки: толщина (dp), волнистое ли и высота волны (dp). */
    var dlRingThick by androidx.compose.runtime.mutableFloatStateOf(prefs.getFloat("dl_ring_w", 2.5f))
        private set
    var dlRingWavy by mutableStateOf(prefs.getBoolean("dl_ring_wavy", false))
        private set
    var dlRingAmp by androidx.compose.runtime.mutableFloatStateOf(prefs.getFloat("dl_ring_amp", 1.5f))
        private set

    fun updateDlRingThick(v: Float) { dlRingThick = v; prefs.edit().putFloat("dl_ring_w", v).apply() }
    fun updateDlRingWavy(on: Boolean) { dlRingWavy = on; prefs.edit().putBoolean("dl_ring_wavy", on).apply() }
    fun updateDlRingAmp(v: Float) { dlRingAmp = v; prefs.edit().putFloat("dl_ring_amp", v).apply() }

    /** Вид вкладок на планшете: [TABS_STYLE_STRIP] - отдельная панель, [TABS_STYLE_INLINE] - в одной строке с адресом (компактно). */
    var tabsStyle by mutableIntStateOf(prefs.getInt("tabs_style", TABS_STYLE_STRIP).coerceIn(TABS_STYLE_STRIP, TABS_STYLE_INLINE))
        private set

    fun updateTabsStyle(v: Int) {
        tabsStyle = v
        tabsOverride = null
        prefs.edit().putInt("tabs_style", v).apply()
    }

    /** Вкладки на планшете: где стоит панель ([TABS_BELOW], [TABS_ABOVE], [TABS_LEFT]). */
    var tabsPos by mutableIntStateOf(prefs.getInt("tabs_pos", TABS_BELOW).coerceIn(TABS_BELOW, TABS_LEFT))
        private set

    /** Когда панель вкладок видна: [TABS_ALWAYS], [TABS_MORE_THAN_TWO], [TABS_NEVER]. */
    var tabsShow by mutableIntStateOf(prefs.getInt("tabs_show", TABS_MORE_THAN_TWO).coerceIn(TABS_ALWAYS, TABS_NEVER))
        private set

    /** Ручное скрытие и показ кнопкой в шапке; null - решает [tabsShow]. Только на время работы, сбрасывается сменой настройки. */
    var tabsOverride by mutableStateOf<Boolean?>(null)
        private set

    fun updateTabsPos(v: Int) {
        tabsPos = v
        prefs.edit().putInt("tabs_pos", v).apply()
    }

    fun updateTabsShow(v: Int) {
        tabsShow = v
        tabsOverride = null
        prefs.edit().putInt("tabs_show", v).apply()
    }

    fun overrideTabs(visible: Boolean?) {
        tabsOverride = visible
    }

    var allowPrivateShots by mutableStateOf(prefs.getBoolean("pshots", false))
        private set

    fun updatePrivateShots(on: Boolean) {
        allowPrivateShots = on
        prefs.edit().putBoolean("pshots", on).apply()
    }

    var trackingProtection by mutableStateOf(prefs.getBoolean("tp", true))
        private set

    fun updateTracking(on: Boolean) {
        trackingProtection = on
        prefs.edit().putBoolean("tp", on).apply()
    }

    /** Закреплённые значки панели планшета (id из [ToolbarPin]), по порядку закрепления в меню не важны: рисуются в порядке enum. */
    val toolbarPins = mutableStateListOf<String>().apply {
        addAll(prefs.getString("toolbar_pins", "").orEmpty().split(',').filter { it.isNotBlank() })
    }

    fun toggleToolbarPin(id: String) {
        if (!toolbarPins.remove(id)) toolbarPins.add(id)
        prefs.edit().putString("toolbar_pins", toolbarPins.joinToString(",")).apply()
    }

    var autoUpdate by mutableStateOf(prefs.getBoolean("auto_update", true))
        private set

    fun updateAutoUpdate(on: Boolean) {
        autoUpdate = on
        prefs.edit().putBoolean("auto_update", on).apply()
    }

    var logging by mutableStateOf(prefs.getBoolean("logging", false))
        private set

    fun updateLogging(on: Boolean) {
        logging = on
        prefs.edit().putBoolean("logging", on).apply()
    }

    /**
     * Сколько запусков подряд завершились сбоем в первые секунды (значение на момент старта этого процесса).
     * Запуск считается упавшим, если за [markStartupStable] до чистой остановки или 15 с работы флаг не сброшен.
     */
    val startupFailures: Int = prefs.getInt("startup_failures", 0)

    fun markStartupBegin() {
        prefs.edit().putInt("startup_failures", startupFailures + 1).commit()
    }

    fun markStartupStable() {
        prefs.edit().putInt("startup_failures", 0).apply()
    }

    var lastUpdateCheck: Long = prefs.getLong("last_update_check", 0L)
        private set

    fun markUpdateChecked() {
        lastUpdateCheck = System.currentTimeMillis()
        prefs.edit().putLong("last_update_check", lastUpdateCheck).apply()
    }

    var gpc by mutableStateOf(prefs.getBoolean("gpc", true))
        private set

    fun updateGpc(on: Boolean) {
        gpc = on
        prefs.edit().putBoolean("gpc", on).apply()
    }

    var openInApps by mutableIntStateOf(prefs.getInt("open_in_apps", ExternalLinks.MODE_ASK))
        private set

    fun updateOpenInApps(mode: Int) {
        openInApps = mode
        prefs.edit().putInt("open_in_apps", mode).apply()
    }

    var stripTrackingParams by mutableStateOf(prefs.getBoolean("strip_tracking_params", true))
        private set

    fun updateStripTrackingParams(on: Boolean) {
        stripTrackingParams = on
        prefs.edit().putBoolean("strip_tracking_params", on).apply()
    }

    var httpsMode by mutableIntStateOf(prefs.getInt("https", PrivacyEngine.HTTPS_OFF))
        private set

    fun updateHttpsMode(mode: Int) {
        httpsMode = mode
        prefs.edit().putInt("https", mode).apply()
    }

    var dohMode by mutableIntStateOf(prefs.getInt("doh", PrivacyEngine.DOH_OFF))
        private set
    var dohProvider by mutableStateOf(prefs.getString("doh_provider", "cloudflare") ?: "cloudflare")
        private set
    var dohCustom by mutableStateOf(prefs.getString("doh_custom", "") ?: "")
        private set

    fun updateDohMode(mode: Int) {
        dohMode = mode
        prefs.edit().putInt("doh", mode).apply()
    }

    fun updateDohProvider(id: String) {
        dohProvider = id
        prefs.edit().putString("doh_provider", id).apply()
    }

    fun updateDohCustom(uri: String) {
        dohCustom = uri
        prefs.edit().putString("doh_custom", uri).apply()
    }

    fun dohUri(): String? =
        if (dohProvider == PrivacyEngine.CUSTOM) dohCustom.takeIf { PrivacyEngine.isValidDoh(it) }
        else PrivacyEngine.dohProviders.firstOrNull { it.id == dohProvider }?.uri

    var pageImages by mutableStateOf(prefs.getBoolean("page_images", true))
        private set

    /** Вид переключателя вкладок: 0 - список, 1 - сетка, 2 - крупные карточки. Старая настройка `tabs_grid` переносится один раз. */
    // По умолчанию сетка (1). Вид, выбранный вручную в меню «⋯», сохраняется и дальше имеет приоритет.
    var tabsView by mutableIntStateOf(prefs.getInt("tabs_view", 1).coerceIn(0, 2))
        private set

    fun updateTabsView(v: Int) {
        tabsView = v.coerceIn(0, 2)
        prefs.edit().putInt("tabs_view", tabsView).apply()
    }

    // ---- Группы вкладок. «Личные» есть всегда (id пустой), остальные создаёт пользователь. ----
    class TabGroup(val id: String, name: String, icon: Int) {
        var name by mutableStateOf(name)
        var icon by mutableIntStateOf(icon)
    }

    var personalName by mutableStateOf(prefs.getString("tab_group_personal_name", null) ?: "Личные")
        private set
    // 6 - значок «человек» (последний в списке значков групп), как у группы «Личные» в образце.
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
    /** Какой группе принадлежит вкладка (id вкладки к id группы); нет записи значит «Личные». */
    val tabGroupOf = androidx.compose.runtime.mutableStateMapOf<String, String>().also { map ->
        runCatching {
            val o = JSONObject(prefs.getString("tab_group_of", "{}"))
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

    /** Закреплённые вкладки (только обычные: id приватных вкладок на диск не пишем). В обзоре они идут первыми. */
    val pinnedTabs = mutableStateListOf<String>().also { list ->
        runCatching {
            val arr = JSONArray(prefs.getString("tabs_pinned", "[]"))
            for (i in 0 until arr.length()) list.add(arr.getString(i))
        }
    }

    fun isPinned(tabId: String): Boolean = tabId in pinnedTabs

    fun setPinned(tabId: String, on: Boolean) {
        if (on) {
            if (tabId !in pinnedTabs) pinnedTabs.add(tabId)
        } else {
            pinnedTabs.remove(tabId)
        }
        val arr = JSONArray()
        pinnedTabs.forEach { arr.put(it) }
        prefs.edit().putString("tabs_pinned", arr.toString()).apply()
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

    /** Вкладки удаляемой группы переезжают в «Личные». */
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
        prefs.edit().putBoolean("page_images", on).apply()
    }

    init {
        PageImages.enabled = pageImages
        SearchEngines.current = SearchEngines.all.firstOrNull { it.name == prefs.getString("engine", null) }
            ?: SearchEngines.all[0]
        runCatching {
            val arr = JSONArray(prefs.getString("searches", "[]"))
            searches.addAll((0 until arr.length()).map { arr.getString(it) })
        }
        bookmarks.addAll(load("bookmarks"))
        history.addAll(load("history"))
        if (prefs.contains("dial")) {
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

    private fun load(key: String): List<Entry> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
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
        while (true) {
            val batch = synchronized(entrySaveLock) {
                if (pendingEntrySaves.isEmpty()) null
                else pendingEntrySaves.toMap().also { pendingEntrySaves.clear() }
            } ?: break
            batch.forEach { (key, safe) ->
                val arr = JSONArray()
                safe.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("d", it.time)) }
                prefs.edit().putString(key, arr.toString()).apply()
            }
        }
        entrySaveWorkerRunning.set(false)
        synchronized(entrySaveLock) {
            if (pendingEntrySaves.isNotEmpty() && entrySaveWorkerRunning.compareAndSet(false, true)) {
                io.execute { drainEntrySaves() }
            }
        }
    }

    fun setSearchEngine(e: SearchEngine) {
        SearchEngines.current = e
        prefs.edit().putString("engine", e.name).apply()
    }

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    fun toggleBookmark(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:", ignoreCase = true)) return
        val i = bookmarks.indexOfFirst { it.url == url }
        if (i >= 0) bookmarks.removeAt(i) else bookmarks.add(0, Entry(url, title, System.currentTimeMillis()))
        save("bookmarks", bookmarks)
    }

    fun removeBookmark(e: Entry) {
        bookmarks.remove(e)
        save("bookmarks", bookmarks)
    }

    fun addHistory(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:", ignoreCase = true)) return
        history.removeAll { it.url == url }
        history.add(0, Entry(url, title, System.currentTimeMillis()))
        while (history.size > 500) history.removeAt(history.lastIndex)
        save("history", history)
    }

    fun removeHistory(e: Entry) {
        history.remove(e)
        save("history", history)
    }

    fun clearHistory() {
        history.clear()
        save("history", history)
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

    fun saveTabSnapsDeferred(build: () -> List<TabSnap>, index: Int) {
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
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(JSONObject().put("u", t.url).put("t", t.title).apply { if (t.state != null) put("s", t.state); put("id", t.id) })
            }
            val root = JSONObject()
                .put("schema", TAB_SCHEMA)
                .put("savedAt", System.currentTimeMillis())
                .put("index", index)
                .put("tabs", arr)

            FileOutputStream(tabsTmpFile).use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (tabsFile.exists()) {
                tabsBackupFile.delete()
                tabsFile.renameTo(tabsBackupFile)
            }
            if (!tabsTmpFile.renameTo(tabsFile)) {
                if (!tabsFile.exists() && tabsBackupFile.exists()) tabsBackupFile.renameTo(tabsFile)
                error("Не удалось сохранить состояние вкладок")
            }
        }.onFailure { AppLog.w("Store", "не удалось сохранить вкладки", it) }
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

    private companion object {
        const val TAB_SCHEMA = TAB_SCHEMA_VERSION
    }

}
