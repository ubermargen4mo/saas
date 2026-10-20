package com.hrips.browser

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.engine.middleware.SessionPrioritizationMiddleware
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.feature.app.links.AppLinksInterceptor
import mozilla.components.feature.customtabs.store.CustomTabsServiceStore
import mozilla.components.feature.prompts.PromptMiddleware
import mozilla.components.feature.prompts.file.FileUploadsDirCleaner
import mozilla.components.feature.prompts.file.FileUploadsDirCleanerMiddleware
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.session.middleware.LastAccessMiddleware
import mozilla.components.feature.session.middleware.undo.UndoMiddleware
import mozilla.components.feature.tabs.TabsUseCases
import org.mozilla.geckoview.GeckoRuntime
import java.util.concurrent.TimeUnit

/**
 * Единая точка сборки ядра браузера из готовых модулей Mozilla Android Components.
 * Здесь нет собственной логики вкладок, загрузки страниц или жизненного цикла движка:
 * всё это делают BrowserStore, EngineMiddleware и GeckoEngineView из Mozilla.
 */
class Components(private val context: Context) {
    val applicationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Вкладки, открытые из других приложений: «Назад» с них возвращает пользователя в источник. */
    val externalTabs: MutableSet<String> = HashSet()

    /** Завершается, когда сохранённые вкладки загружены в хранилище. */
    val restored = CompletableDeferred<Unit>()

    private val runtime: GeckoRuntime by lazy { GeckoRuntime.create(context) }

    val appLinksInterceptor: AppLinksInterceptor by lazy {
        AppLinksInterceptor(
            context = context,
            // https-ссылки остаются в браузере; приложения запускаются для своих схем (intent://, market:// и т.п.)
            // и для входа через приложение (OAuth), как в Firefox.
            launchInApp = { false },
            launchFromInterceptor = false,
            store = store,
        )
    }

    val engine: Engine by lazy {
        GeckoEngine(
            context = context,
            defaultSettings = DefaultSettings(
                requestInterceptor = AppRequestInterceptor(context) { appLinksInterceptor },
                trackingProtectionPolicy = EngineSession.TrackingProtectionPolicy.recommended(),
                supportMultipleWindows = true,
                javaScriptCanOpenWindowsAutomatically = false,
                remoteDebuggingEnabled = false,
            ),
            runtime = runtime,
        )
    }

    val fileUploadsDirCleaner: FileUploadsDirCleaner by lazy {
        FileUploadsDirCleaner(scope = applicationScope) { context.cacheDir }
    }

    val store: BrowserStore by lazy {
        BrowserStore(
            middleware = listOf(
                LastAccessMiddleware(),
                UndoMiddleware(),
                PromptMiddleware(),
                SessionPrioritizationMiddleware(),
                FileUploadsDirCleanerMiddleware(fileUploadsDirCleaner),
            ) + EngineMiddleware.create(engine),
        )
    }

    val sessionStorage: SessionStorage by lazy {
        SessionStorage(context, engine, crashReporting = null, applicationScope = applicationScope)
    }

    val sessionUseCases: SessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases: TabsUseCases by lazy { TabsUseCases(store) }

    val customTabsServiceStore: CustomTabsServiceStore by lazy { CustomTabsServiceStore() }

    /** Открывает новую вкладку. Пустая вкладка ничего не грузит и показывает стартовую страницу. */
    fun newTab(private: Boolean, url: String = BLANK): String =
        tabsUseCases.addTab(
            url = url,
            selectTab = true,
            startLoading = url != BLANK,
            private = private,
        )

    /** Загружает адрес в выбранной вкладке; если вкладок нет — открывает новую. */
    fun open(url: String, private: Boolean = false) {
        if (store.state.selectedTab == null) {
            newTab(private, url)
        } else {
            load(url)
        }
    }

    /** Загружает адрес в указанной (по умолчанию выбранной) вкладке. */
    fun load(url: String, tabId: String? = store.state.selectedTabId) {
        sessionUseCases.loadUrl(url = url, sessionId = tabId)
    }

    fun start() {
        engine.warmUp()
        applicationScope.launch {
            // Восстановление и автосохранение вкладок — штатный механизм Mozilla (SessionStorage).
            tabsUseCases.restore(sessionStorage)
            sessionStorage
                .autoSave(store)
                .periodicallyInForeground(interval = 30, unit = TimeUnit.SECONDS)
                .whenGoingToBackground()
                .whenSessionsChange()
            restored.complete(Unit)
        }
        applicationScope.launch(Dispatchers.IO) {
            fileUploadsDirCleaner.cleanUploadsDirectory()
        }
    }

    companion object {
        const val BLANK = "about:blank"
    }
}
