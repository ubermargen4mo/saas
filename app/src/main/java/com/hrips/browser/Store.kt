package com.hrips.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Entry(val url: String, val title: String, val time: Long)

/** Закладки, история и список вкладок. Пока в SharedPreferences (JSON), позже можно заменить на Room. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("hrips", Context.MODE_PRIVATE)

    val bookmarks = mutableStateListOf<Entry>()
    val history = mutableStateListOf<Entry>()
    val speedDial = mutableStateListOf<Entry>()
    /** Поисковые запросы (отдельно от истории страниц), новые сверху. */
    val searches = mutableStateListOf<String>()

    /** Подсказки при вводе уходят поисковику, поэтому их можно выключить. */
    var suggestionsOn by mutableStateOf(prefs.getBoolean("suggest", true))
        private set

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

    init {
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

    private fun load(key: String): List<Entry> = try {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getString("u"), o.optString("t"), o.optLong("d"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(key: String, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("d", it.time)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun setSearchEngine(e: SearchEngine) {
        SearchEngines.current = e
        prefs.edit().putString("engine", e.name).apply()
    }

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    fun toggleBookmark(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        val i = bookmarks.indexOfFirst { it.url == url }
        if (i >= 0) bookmarks.removeAt(i) else bookmarks.add(0, Entry(url, title, System.currentTimeMillis()))
        save("bookmarks", bookmarks)
    }

    fun removeBookmark(e: Entry) {
        bookmarks.remove(e)
        save("bookmarks", bookmarks)
    }

    fun addHistory(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:")) return
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

    fun saveTabs(urls: List<String>, index: Int) {
        prefs.edit().putString("tabs", JSONArray(urls).toString()).putInt("tabIndex", index).apply()
    }

    fun loadTabs(): Pair<List<String>, Int> = try {
        val arr = JSONArray(prefs.getString("tabs", "[]"))
        (0 until arr.length()).map { arr.getString(it) } to prefs.getInt("tabIndex", 0)
    } catch (e: Exception) {
        emptyList<String>() to 0
    }
}
