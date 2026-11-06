package com.hrips.browser

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoRuntime
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Удержание страниц в памяти: текущая страница свёрнутого браузера (служба + высокий приоритет процесса)
 * и «тёплые» недавние вкладки при переключении. Движок заменён пустышкой, проверяется решение браузера:
 * какой вкладке держать приоритет и когда просить службу.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeepPageTest {
    private lateinit var browser: Browser
    private val keeper = FakeKeeper()

    private class FakeKeeper : BackgroundKeeper {
        var holds = 0
        var releases = 0
        var title: String? = null
        var isPrivate = false
        var minutes = -1
        var onReleased: (() -> Unit)? = null

        override fun hold(title: String, isPrivate: Boolean, minutes: Int, onReleased: () -> Unit): Boolean {
            holds++
            this.title = title
            this.isPrivate = isPrivate
            this.minutes = minutes
            this.onReleased = onReleased
            return true
        }

        override fun release() {
            releases++
        }
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<HripsApp>()
        org.mozilla.gecko.GeckoAppShell.setApplicationContext(app)
        val rt = mockk<GeckoRuntime>(relaxed = true)
        HripsApp::class.java.getDeclaredField("runtime\$delegate").apply { isAccessible = true }.set(app, lazyOf(rt))
        app.store.finishFirstRun()
        browser = app.browser
        browser.backgroundKeeper = keeper
        browser.onForeground()
    }

    private fun page(url: String, title: String, private: Boolean = false): Tab =
        browser.newTab(url, incognito = private).also { it.title = title }

    @Test
    fun leavingAppKeepsCurrentPageAndReturningReleasesIt() {
        val chat = page("https://chat.example/c/1", "Чат")
        val releasesBefore = keeper.releases
        browser.onBackground()
        assertEquals(1, keeper.holds)
        assertEquals("Чат", keeper.title)
        assertEquals(60, keeper.minutes)
        assertTrue("текущая страница свёрнутого браузера держится", chat.priorityHeld)

        browser.onForeground()
        assertEquals("при возврате служба останавливается", releasesBefore + 1, keeper.releases)
        assertFalse(chat.priorityHeld)
    }

    @Test
    fun keepTimeComesFromSettings() {
        browser.store.updateKeepAliveMinutes(0)
        page("https://example.com/", "Example")
        browser.onBackground()
        assertEquals(0, keeper.minutes)
    }

    @Test
    fun privatePageIsKeptWithoutLeakingTitleToKeeperFlag() {
        page("https://secret.example/", "Секрет", private = true)
        browser.onBackground()
        assertEquals(1, keeper.holds)
        assertTrue(keeper.isPrivate)
    }

    @Test
    fun startPageAndDisabledSettingDoNotStartService() {
        browser.newTab()
        browser.onBackground()
        assertEquals("на начальной странице держать нечего", 0, keeper.holds)

        browser.onForeground()
        page("https://example.com/", "Example")
        browser.store.updateKeepPageInBackground(false)
        browser.onBackground()
        assertEquals(0, keeper.holds)
    }

    @Test
    fun releaseFromNotificationDropsPriorityButAppStaysUsable() {
        val chat = page("https://chat.example/", "Чат")
        browser.onBackground()
        keeper.onReleased!!.invoke()
        assertFalse(chat.priorityHeld)

        browser.onForeground()
        browser.onBackground()
        assertEquals("при следующем сворачивании страница снова держится", 2, keeper.holds)
        assertTrue(chat.priorityHeld)
    }

    @Test
    fun recentTabsStayWarmWithLimitAndExpire() {
        val a = page("https://a.example/", "A")
        val b = page("https://b.example/", "B")
        assertTrue("покинутая вкладка остаётся тёплой", a.priorityHeld)
        val c = page("https://c.example/", "C")
        assertTrue(a.priorityHeld && b.priorityHeld)
        val d = page("https://d.example/", "D")
        assertFalse("тёплых вкладок не больше $MAX_WARM_TABS", a.priorityHeld)
        assertTrue(b.priorityHeld && c.priorityHeld)
        assertFalse("у текущей вкладки и так высокий приоритет", d.priorityHeld)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WARM_TAB_MS + 1_000L))
        assertFalse(b.priorityHeld)
        assertFalse(c.priorityHeld)
    }

    @Test
    fun returningToWarmTabMakesItCurrentAndWarmsThePreviousOne() {
        val a = page("https://a.example/", "A")
        val b = page("https://b.example/", "B")
        browser.currentIndex = browser.tabs.indexOf(a)
        assertFalse(a.priorityHeld)
        assertTrue(b.priorityHeld)
    }

    @Test
    fun warmTabsAreNotHeldInBackgroundUnlessTheyHaveForms() {
        val a = page("https://a.example/", "A")
        val b = page("https://b.example/", "B")
        assertTrue(a.priorityHeld)
        browser.onBackground()
        assertFalse("в свёрнутом браузере держится только текущая и вкладки с формами", a.priorityHeld)
        assertTrue(b.priorityHeld)
        browser.onForeground()
        assertTrue("после возврата недавняя вкладка снова тёплая", a.priorityHeld)
    }

    @Test
    fun closedTabsAreForgotten() {
        val a = page("https://a.example/", "A")
        page("https://b.example/", "B")
        browser.closeTab(browser.tabs.indexOf(a))
        browser.onBackground()
        browser.onForeground()
        assertFalse(a.priorityHeld)
    }

    @Test
    fun policy() {
        assertTrue(KeepPagePolicy.shouldKeep(enabled = true, quitting = false, home = false, url = "https://x.ru/"))
        assertFalse(KeepPagePolicy.shouldKeep(enabled = false, quitting = false, home = false, url = "https://x.ru/"))
        assertFalse(KeepPagePolicy.shouldKeep(enabled = true, quitting = true, home = false, url = "https://x.ru/"))
        assertFalse(KeepPagePolicy.shouldKeep(enabled = true, quitting = false, home = true, url = ""))
        assertFalse(KeepPagePolicy.shouldKeep(enabled = true, quitting = false, home = false, url = "about:blank"))
    }
}
