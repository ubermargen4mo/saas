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

/**
 * Ключ сайта для настроек "на сайт": хост в нижнем регистре без "www.".
 * Для не-http адресов (about:, data:, стартовая страница) возвращает null.
 */
fun siteKey(url: String?): String? {
    val parsed = runCatching { url?.let(Uri::parse) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    return parsed.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
}

/** Закладки, история и список вкладок. Пока в SharedPreferences (JSON), позже можно заменить на Room. */
/** Что хранится о вкладке между запусками: адрес, заголовок и состояние сессии движка (история, прокрутка). */
class TabSnap(
    val url: String,
    val title: String,
    val state: String?,
    val group: String? = null,
    val id: String = java.util.UUID.randomUUID().toString(),
)

/** Всё, что нужно для восстановления вкладок за один проход по файлу. */
class TabRestore(val tabs: List<TabSnap>, val index: Int, val groups: List<TabGroup>)

private const val TABS_FILE = "tabs.json"
private const val TAB_SCHEMA_VERSION = 1
private const val MAX_TAB_STATE_FILE_BYTES = 32L * 1024L * 1024L

private fun readTabRootFile(file: java.io.File): JSONObject? = runCatching {
    if (!file.isFile || file.length() !in 1L..MAX_TAB_STATE_FILE_BYTES) return@runCatching null
    JSONObject(file.readText(Charsets.UTF_8))
}.getOrNull()

/** Самый свежий валидный root среди основного файла, tmp и backup. */
private fun readBestTabRoot(dir: java.io.File): JSONObject? =
    listOf(java.io.File(dir, TABS_FILE), java.io.File(dir, "$TABS_FILE.tmp"), java.io.File(dir, "$TABS_FILE.bak"))
        .mapNotNull { f ->
            readTabRootFile(f)?.takeIf {
                it.optInt("schema", 0) in 1..TAB_SCHEMA_VERSION && it.optJSONArray("tabs") != null
            }
        }
        .maxByOrNull { it.optLong("savedAt", 0L) }

/**
 * Чтение и разбор файла вкладок (может быть десятки МБ) стартует в фоне из Application.onCreate и идёт параллельно
 * с созданием движка на главном потоке. Результат забирается один раз при восстановлении; если его нет
 * или он не получился, файл читается заново обычным путём.
 */
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

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("hrips", Context.MODE_PRIVATE)
    private val tabsFile = java.io.File(context.filesDir, "tabs.json")
    private val tabsTmpFile = java.io.File(context.filesDir, "tabs.json.tmp")
    private val tabsBackupFile = java.io.File(context.filesDir, "tabs.json.bak")
    /** Последовательная очередь записи: onStop() не должен сериализовать крупные session states на UI thread. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hrips-store").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private data class TabSaveJob(val list: List<TabSnap>, val index: Int, val groups: List<TabGroup>)
    private val pendingTabSave = AtomicReference<TabSaveJob?>(null)
    private val tabSaveWorkerRunning = AtomicBoolean(false)
    private val entrySaveLock = Any()
    private val pendingEntrySaves = LinkedHashMap<String, List<Entry>>()
    private val entrySaveWorkerRunning = AtomicBoolean(false)

    /**
     * Сайты, где включена версия для ПК. Везде остальное - мобильная версия (по умолчанию выключено).
     * Хранится навсегда, пока пользователь сам не выключит. В приватных вкладках сюда ничего не пишется.
     */
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
    /** Поисковые запросы (отдельно от истории страниц), новые сверху. */
    val searches = mutableStateListOf<String>()

    /** Подсказки при вводе уходят поисковику, поэтому их можно выключить. */
    var suggestionsOn by mutableStateOf(prefs.getBoolean("suggest", true))
        private set

    /** Выгружать фоновые вкладки при серьёзном давлении на память. По умолчанию выключено. */
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

    /** Разрешить скриншоты и миниатюру в списке приложений, пока открыта приватная вкладка. По умолчанию запрещено. */
    /** Экран с разрешениями показывается один раз при первом запуске. */
    var firstRunDone by mutableStateOf(prefs.getBoolean("firstrun", false))
        private set

    fun finishFirstRun() {
        firstRunDone = true
        prefs.edit().putBoolean("firstrun", true).apply()
    }

    /** Картинка в картинке при выходе на главный экран с видео на весь экран. */
    var pipEnabled by mutableStateOf(prefs.getBoolean("pip", true))
        private set

    fun updatePip(on: Boolean) {
        pipEnabled = on
        prefs.edit().putBoolean("pip", on).apply()
    }

    /** Уведомление с кнопками и фоновое воспроизведение. */
    var mediaControls by mutableStateOf(prefs.getBoolean("mediactl", true))
        private set

    fun updateMediaControls(on: Boolean) {
        mediaControls = on
        prefs.edit().putBoolean("mediactl", on).apply()
    }

    /** Показывать окно подтверждения перед загрузкой файла. */
    var askBeforeDownload by mutableStateOf(prefs.getBoolean("dlask", true))
        private set

    fun updateAskBeforeDownload(on: Boolean) {
        askBeforeDownload = on
        prefs.edit().putBoolean("dlask", on).apply()
    }

    var allowPrivateShots by mutableStateOf(prefs.getBoolean("pshots", false))
        private set

    fun updatePrivateShots(on: Boolean) {
        allowPrivateShots = on
        prefs.edit().putBoolean("pshots", on).apply()
    }

    /** Защита от трекеров движка. Если какой-то сайт ломается, её можно выключить в настройках. */
    var trackingProtection by mutableStateOf(prefs.getBoolean("tp", true))
        private set

    fun updateTracking(on: Boolean) {
        trackingProtection = on
        prefs.edit().putBoolean("tp", on).apply()
    }

    // ---- Сеть и приватность движка: значения применяет PrivacyEngine ----

    /** Global Privacy Control. Безвредный сигнал, поэтому включён по умолчанию. */
    var gpc by mutableStateOf(prefs.getBoolean("gpc", true))
        private set

    fun updateGpc(on: Boolean) {
        gpc = on
        prefs.edit().putBoolean("gpc", on).apply()
    }

    /** Удалять известные tracking-параметры из URL. По умолчанию включено. */
    var stripTrackingParams by mutableStateOf(prefs.getBoolean("strip_tracking_params", true))
        private set

    fun updateStripTrackingParams(on: Boolean) {
        stripTrackingParams = on
        prefs.edit().putBoolean("strip_tracking_params", on).apply()
    }

    /** HTTPS-only: 0 выкл (как раньше), 1 только приватные вкладки, 2 все вкладки (PrivacyEngine.HTTPS_*). */
    var httpsMode by mutableIntStateOf(prefs.getInt("https", PrivacyEngine.HTTPS_OFF))
        private set

    fun updateHttpsMode(mode: Int) {
        httpsMode = mode
        prefs.edit().putInt("https", mode).apply()
    }

    /** DoH: 0 выкл, 1 автоматически (с запасным обычным DNS), 2 строго (PrivacyEngine.DOH_*). */
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

    /** Адрес выбранного DoH-сервера или null, если свой сервер не задан или адрес негодный. */
    fun dohUri(): String? =
        if (dohProvider == PrivacyEngine.CUSTOM) dohCustom.takeIf { PrivacyEngine.isValidDoh(it) }
        else PrivacyEngine.dohProviders.firstOrNull { it.id == dohProvider }?.uri

    /** Картинки страниц (og:image) в карточках вкладок. Выключите, чтобы браузер не запрашивал страницы в фоне. */
    var pageImages by mutableStateOf(prefs.getBoolean("page_images", true))
        private set

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
        // Keep only the newest snapshot per collection. History can receive many writes while
        // navigating, and serializing the same 500 rows repeatedly just creates IO backlog.
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

    /** Меняет адрес и название плитки, место в списке остаётся прежним. */
    fun updateDial(old: Entry, url: String, title: String) {
        val i = speedDial.indexOf(old)
        if (i < 0) return
        speedDial[i] = old.copy(url = url, title = title)
        save("dial", speedDial)
    }

    /** Переставляет плитку при перетаскивании. Пишем на диск не сразу, а по отпусканию ([saveDial]). */
    fun moveDial(from: Int, to: Int) {
        if (from !in speedDial.indices || to !in speedDial.indices || from == to) return
        speedDial.add(to, speedDial.removeAt(from))
    }

    fun saveDial() = save("dial", speedDial)

    /** Состояние вкладок может быть большим, поэтому пишем его в файл, а не в SharedPreferences. */
    fun saveTabSnaps(list: List<TabSnap>, index: Int, groups: List<TabGroup> = emptyList()) {
        writeTabSnaps(list, index, groups)
    }

    /** Планирует запись снапшота последовательно, не блокируя Activity.onStop(). */
    fun saveTabSnapsAsync(list: List<TabSnap>, index: Int, groups: List<TabGroup> = emptyList()) {
        // Копии нужны: Compose-backed lists могут измениться сразу после onStop().
        val safeList = list.map { TabSnap(it.url, it.title, it.state, it.group, it.id) }
        val safeGroups = groups.map { TabGroup(it.id, it.name, it.color) }
        pendingTabSave.set(TabSaveJob(safeList, index, safeGroups))
        if (tabSaveWorkerRunning.compareAndSet(false, true)) {
            io.execute { drainTabSaves() }
        }
    }

    private fun drainTabSaves() {
        try {
            while (true) {
                val job = pendingTabSave.getAndSet(null) ?: return
                writeTabSnaps(job.list, job.index, job.groups)
            }
        } finally {
            tabSaveWorkerRunning.set(false)
            if (pendingTabSave.get() != null && tabSaveWorkerRunning.compareAndSet(false, true)) {
                io.execute { drainTabSaves() }
            }
        }
    }

    private fun writeTabSnaps(list: List<TabSnap>, index: Int, groups: List<TabGroup>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(JSONObject().put("u", t.url).put("t", t.title).apply { if (t.state != null) put("s", t.state); if (t.group != null) put("g", t.group); put("id", t.id) })
            }
            val root = JSONObject()
                .put("schema", TAB_SCHEMA)
                .put("savedAt", System.currentTimeMillis())
                .put("index", index)
                .put("tabs", arr)
                .put("groups", JSONArray().apply { groups.forEach { put(JSONObject().put("id", it.id).put("n", it.name).put("c", it.color)) } })

            // Write -> fsync -> temporary file -> rotate current to backup -> publish new file.
            // If the process dies anywhere in this sequence, at least one complete JSON document remains.
            FileOutputStream(tabsTmpFile).use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (tabsFile.exists()) {
                tabsBackupFile.delete()
                tabsFile.renameTo(tabsBackupFile)
            }
            if (!tabsTmpFile.renameTo(tabsFile)) {
                // Restore the previous valid file when publishing the new file failed.
                if (!tabsFile.exists() && tabsBackupFile.exists()) tabsBackupFile.renameTo(tabsFile)
                error("Не удалось сохранить состояние вкладок")
            }
        }
    }

    private val filesDir = context.filesDir

    /** Корень файла вкладок: сначала результат фонового чтения со старта (однократно), иначе чтение с диска. */
    private fun loadBestTabRoot(): JSONObject? {
        TabRootPrefetch.take()?.let { task ->
            val prefetched = runCatching { task.get() }.getOrNull()
            if (prefetched != null) return prefetched
        }
        return readBestTabRoot(filesDir)
    }

    private fun parseGroups(root: JSONObject): List<TabGroup> = runCatching {
        val arr = root.optJSONArray("groups") ?: return emptyList()
        (0 until arr.length()).mapNotNull {
            runCatching {
                val o = arr.getJSONObject(it)
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@runCatching null
                TabGroup(id, o.optString("n", "Группа").take(80).ifBlank { "Группа" }, o.optInt("c", 0))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun parseTabs(root: JSONObject): List<TabSnap> {
        val arr = root.optJSONArray("tabs") ?: throw IllegalStateException("missing tabs")
        return (0 until arr.length()).mapNotNull {
            runCatching {
                val o = arr.getJSONObject(it)
                val url = o.optString("u")
                val title = o.optString("t").take(200)
                TabSnap(url, title, if (o.has("s")) o.optString("s").takeIf { it.isNotBlank() } else null, if (o.has("g")) o.optString("g").takeIf { it.isNotBlank() } else null, o.optString("id").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString())
            }.getOrNull()
        }
    }

    /** Вкладки, индекс выбранной и группы с прошлого запуска; файл читается один раз. Старый формат (только адреса) тоже читается. */
    fun loadTabRestore(): TabRestore {
        runCatching {
            val root = loadBestTabRoot()
            if (root != null) {
                return TabRestore(parseTabs(root), root.optInt("index", 0), parseGroups(root))
            }
        }
        val (urls, index) = loadTabs()
        return TabRestore(urls.map { TabSnap(it, "", null) }, index, emptyList())
    }

    /** Группы вкладок с прошлого запуска. */
    fun loadGroups(): List<TabGroup> = runCatching { loadBestTabRoot()?.let { parseGroups(it) } }.getOrNull().orEmpty()

    /** Вкладки с прошлого запуска. */
    fun loadTabSnaps(): Pair<List<TabSnap>, Int> = loadTabRestore().let { it.tabs to it.index }

    fun saveTabs(urls: List<String>, index: Int) {
        prefs.edit().putString("tabs", JSONArray(urls).toString()).putInt("tabIndex", index).apply()
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
