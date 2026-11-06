package com.hrips.browser.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.hrips.browser.Browser
import com.hrips.browser.BrowserScreen
import com.hrips.browser.HripsApp
import com.hrips.browser.HripsTheme
import com.hrips.browser.THEME_MODE_DARK
import com.hrips.browser.THEME_MODE_LIGHT
import com.hrips.browser.Tab
import io.mockk.mockk
import org.junit.Rule
import org.mozilla.geckoview.GeckoRuntime
import java.io.File

/**
 * Прогон по экранам браузера: настоящий [BrowserScreen], движок заменён пустышкой (страницы не рисуются,
 * весь интерфейс вокруг них настоящий). Снимки: screenshots/<устройство>/<имя>.png.
 */
abstract class Tour(private val device: String) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    protected lateinit var app: HripsApp
    protected lateinit var browser: Browser
    private val out = File(System.getProperty("roborazzi.output.dir") ?: "screenshots")

    protected fun start(dark: Boolean = false, firstRun: Boolean = false, seed: (Browser) -> Unit = {}) {
        app = ApplicationProvider.getApplicationContext()
        org.mozilla.gecko.GeckoAppShell.setApplicationContext(app)
        val rt = mockk<GeckoRuntime>(relaxed = true)
        HripsApp::class.java.getDeclaredField("runtime\$delegate").apply { isAccessible = true }.set(app, lazyOf(rt))
        val store = app.store
        if (!firstRun) store.finishFirstRun()
        store.updateThemeMode(if (dark) THEME_MODE_DARK else THEME_MODE_LIGHT)
        browser = app.browser
        seedData()
        if (browser.tabs.isEmpty()) browser.newTab()
        seed(browser)
        compose.setContent {
            val incognito = browser.tabs.getOrNull(browser.currentIndex)?.isPrivate == true
            HripsTheme(incognito = incognito, seed = store.themeSeed, mode = store.themeMode) {
                Surface(Modifier.fillMaxSize()) { BrowserScreen(browser) }
            }
        }
        settle()
    }

    private fun seedData() {
        val s = app.store
        listOf(
            "https://www.wikipedia.org/" to "Википедия — свободная энциклопедия",
            "https://github.com/" to "GitHub: Let's build from here",
            "https://news.ycombinator.com/" to "Hacker News",
            "https://www.youtube.com/" to "YouTube",
            "https://habr.com/ru/" to "Хабр",
            "https://developer.android.com/" to "Android Developers",
        ).forEach { (u, t) -> s.addHistory(u, t) }
        s.toggleBookmark("https://www.wikipedia.org/", "Википедия")
        s.toggleBookmark("https://developer.android.com/", "Android Developers")
        s.toggleBookmark("https://kotlinlang.org/", "Kotlin Programming Language")
        listOf("погода москва", "kotlin coroutines", "рецепт борща").forEach(s::addSearch)
    }

    /** Вкладка «с открытой страницей»: движка нет, поэтому состояние задаётся напрямую. */
    protected fun pageTab(url: String, title: String, private: Boolean = false): Tab {
        val t = browser.newTab(incognito = private)
        t.home = false
        t.url = url
        t.title = title
        setState(t, "secure", url.startsWith("https://"))
        t.progress = 100
        return t
    }

    @Suppress("UNCHECKED_CAST")
    protected fun setState(target: Any, name: String, value: Any?) {
        val f = target.javaClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }
        (f.get(target) as androidx.compose.runtime.MutableState<Any?>).value = value
    }

    protected fun settle() {
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    protected fun shot(name: String) {
        settle()
        val f = File(out, "$device/$name.png")
        f.parentFile?.mkdirs()
        captureScreenRoboImage(f.absolutePath)
        dumpTree(name)
    }

    protected fun click(text: String, desc: Boolean = false, substring: Boolean = false) {
        val m = if (desc) hasContentDescription(text, substring = substring) else hasText(text, substring = substring)
        val nodes = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "нет узла «$text»" }
        node(m).also { runCatching { it.performScrollTo() } }.performClick()
        settle()
    }

    protected fun has(text: String, desc: Boolean = false): Boolean {
        val m = if (desc) hasContentDescription(text, substring = true) else hasText(text, substring = true)
        return compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun node(m: androidx.compose.ui.test.SemanticsMatcher): SemanticsNodeInteraction =
        // Последний найденный узел лежит в верхнем слое (открытый поверх экран, лист, диалог).
        compose.onAllNodes(m, useUnmergedTree = true).let { all -> all[all.fetchSemanticsNodes().size - 1] }

    private fun dumpTree(name: String) {
        val sb = StringBuilder()
        fun walk(n: SemanticsNode, depth: Int) {
            val t = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            val d = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            val b = n.boundsInRoot
            if (t != null || d != null) sb.append("  ".repeat(depth)).append(t ?: "").append(if (d != null) " [$d]" else "")
                .append(" @${b.left.toInt()},${b.top.toInt()} ${b.width.toInt()}x${b.height.toInt()}\n")
            n.children.forEach { walk(it, depth + 1) }
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach { walk(it, 0); sb.append("----\n") }
        File(out, "$device/trees/$name.txt").apply { parentFile?.mkdirs() }.writeText(sb.toString())
    }
}
