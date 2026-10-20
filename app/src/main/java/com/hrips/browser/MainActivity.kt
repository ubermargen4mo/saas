package com.hrips.browser

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.feature.app.links.AppLinksFeature
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.feature.webauthn.WebAuthnFeature
import mozilla.components.support.base.feature.LifecycleAwareFeature

/**
 * Единственная Activity. Наследует AppCompatActivity: на этом основаны диалоги, выбор файлов,
 * запросы входа и прочие окна из модулей Mozilla (PromptFeature, AppLinksFeature, WebAuthnFeature).
 */
class MainActivity : AppCompatActivity() {
    private val c: Components get() = (application as HripsApp).components

    private lateinit var prompts: PromptFeature
    private lateinit var webAuthn: WebAuthnFeature
    private lateinit var features: List<LifecycleAwareFeature>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        prompts = PromptFeature(
            activity = this,
            store = c.store,
            fragmentManager = supportFragmentManager,
            tabsUseCases = c.tabsUseCases,
            fileUploadsDirCleaner = c.fileUploadsDirCleaner,
            isSuggestEmailMaskEnabled = { false },
            isEmailMaskFeatureEnabled = { false },
            onNeedToRequestPermissions = { permissions -> requestPermissions(permissions, REQUEST_PROMPTS) },
        )
        webAuthn = WebAuthnFeature(
            engine = c.engine,
            activity = this,
            exitFullScreen = { tabId -> c.sessionUseCases.exitFullscreen(tabId) },
            currentTab = { c.store.state.selectedTabId },
        )
        val appLinks = AppLinksFeature(
            context = this,
            store = c.store,
            fragmentManager = supportFragmentManager,
            launchInApp = { false },
            loadUrlUseCase = c.sessionUseCases.loadUrl,
            failedToLaunchAction = { fallback -> fallback?.let { c.load(it) } },
        )
        val windows = WindowFeature(c.store, c.tabsUseCases)
        features = listOf(prompts, webAuthn, appLinks, windows)

        setContent { BrowserScreen(this, c) }

        // Ссылку из другого приложения открываем только после восстановления вкладок,
        // иначе восстановленный выбор перебьёт новую вкладку.
        val startIntent = if (savedInstanceState == null) intent else null
        lifecycleScope.launch {
            c.restored.await()
            val opened = startIntent?.let { Intents.handle(this@MainActivity, c, it) } ?: false
            if (!opened && c.store.state.tabs.isEmpty()) c.newTab(private = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch {
            c.restored.await()
            Intents.handle(this@MainActivity, c, intent)
        }
    }

    override fun onStart() {
        super.onStart()
        features.forEach { it.start() }
    }

    override fun onStop() {
        features.forEach { it.stop() }
        super.onStop()
    }

    /** «Назад» сначала отдаём открытому диалогу Mozilla, затем остальной логике экрана. */
    fun handlePromptBack(): Boolean = prompts.onBackPressed()

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (prompts.onActivityResult(requestCode, data, resultCode)) return
        webAuthn.onActivityResult(requestCode, data, resultCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PROMPTS) {
            prompts.onPermissionsResult(permissions.map { it }.toTypedArray(), grantResults)
        }
    }

    /** Закрывает вкладку, пришедшую из другого приложения, и возвращает пользователя туда. */
    fun closeExternalTab(tabId: String) {
        c.externalTabs.remove(tabId)
        c.tabsUseCases.removeTab(tabId)
        if (c.store.state.selectedTab == null) c.newTab(private = false)
        moveTaskToBack(true)
    }

    private companion object {
        const val REQUEST_PROMPTS = 1001
    }
}
