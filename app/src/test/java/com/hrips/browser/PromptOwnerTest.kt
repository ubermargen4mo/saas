package com.hrips.browser

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Вопросы страниц (alert/confirm/prompt, сохранение пароля) и запросы разрешений привязаны к своей вкладке:
 * фоновая вкладка не может показать диалог поверх чужой страницы, а вопросы закрытой страницы не остаются висеть.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptOwnerTest {
    private lateinit var browser: Browser

    private class Alert(id: String) :
        PromptDelegate.AlertPrompt(id, "Заголовок", "Текст", object : PromptDelegate.BasePrompt.Observer {})

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<HripsApp>()
        org.mozilla.gecko.GeckoAppShell.setApplicationContext(app)
        val rt = mockk<GeckoRuntime>(relaxed = true)
        HripsApp::class.java.getDeclaredField("runtime\$delegate").apply { isAccessible = true }.set(app, lazyOf(rt))
        app.store.finishFirstRun()
        browser = app.browser
        browser.onForeground()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun page(url: String): Tab = browser.newTab(url)

    private fun alertFrom(tab: Tab, id: String): Alert {
        val prompt = Alert(id)
        browser.prompts.delegate.onAlertPrompt(tab.session, prompt)
        idle()
        return prompt
    }

    @Test
    fun backgroundTabDialogWaitsForItsTab() {
        val a = page("https://a.example/")
        val b = page("https://b.example/")
        assertSame(b, browser.current)

        alertFrom(a, "a1")
        assertNull("вопрос фоновой вкладки не показывается поверх текущей", browser.prompts.firstFor(browser.current.session))

        browser.currentIndex = browser.tabs.indexOf(a)
        val shown = browser.prompts.firstFor(browser.current.session)
        assertTrue("на своей вкладке вопрос появляется", shown is PromptRequest.Alert)
        assertSame(a.session, shown?.owner)
    }

    @Test
    fun engineDismissRemovesStaleDialog() {
        val a = page("https://a.example/")
        val prompt = alertFrom(a, "a1")
        assertEquals(1, browser.prompts.queue.size)

        // Так GeckoView сообщает, что страница ушла и вопрос больше не актуален.
        prompt.delegate!!.onPromptDismiss(prompt)
        idle()
        assertTrue(browser.prompts.queue.isEmpty())
    }

    @Test
    fun closingTabDropsItsDialogsAndAnswersEngine() {
        val a = page("https://a.example/")
        page("https://b.example/")
        val prompt = alertFrom(a, "a1")
        assertFalse(prompt.isComplete)

        browser.closeTab(browser.tabs.indexOf(a))
        idle()
        assertTrue("вопрос закрытой вкладки снят", browser.prompts.queue.isEmpty())
        assertTrue("движок получил ответ, страница не ждёт вечно", prompt.isComplete)
    }

    @Test
    fun permissionRequestsAreTiedToTheirTab() {
        val a = page("https://a.example/")
        val b = page("https://b.example/")
        var answer: Boolean? = null
        browser.permissions.queue.add(
            PermissionRequest("https://a.example", "использовать камеру", emptyList(), false, null, owner = a.session) { answer = it },
        )
        assertNull(browser.permissions.firstFor(b.session))
        assertSame(a.session, browser.permissions.firstFor(a.session)?.owner)

        browser.closeTab(browser.tabs.indexOf(a))
        assertTrue(browser.permissions.queue.isEmpty())
        assertEquals("закрытая страница получает отказ", false, answer)
    }

    @Test
    fun unownedRequestsShowAnywhere() {
        browser.permissions.queue.add(PermissionRequest("x", "y", emptyList(), false, null) { })
        assertTrue(browser.permissions.firstFor(null) != null)
    }
}
