package com.hrips.browser

import android.app.ActivityManager
import android.app.Application
import android.content.res.Configuration
import android.os.StrictMode
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtensionController

private const val STARTUP_STABLE_MS = 15_000L

private const val DEFERRED_EXTENSIONS_MS = 2_500L

class HripsApp : Application() {
    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                .extensionsProcessEnabled(true)
                .preferredColorScheme(initialColorScheme())
                .build(),
        ).also { gecko ->
            gecko.webExtensionController.setExtensionProcessDelegate(object : WebExtensionController.ExtensionProcessDelegate {
                override fun onDisabledProcessSpawning() {
                    AppLog.w("Extensions", "процесс расширений отключён после повторных сбоев")
                    Notices.show("Расширения отключены после сбоев до перезапуска приложения")
                }
            })
            applyTrackingProtection(gecko, store.trackingProtection, store.strictProtection)
            applyPrivateDefaults(gecko)
            applySafeBrowsing(gecko)
            // Эффективные значения, а не заданные нами: так в журнале видно, что движок реально применил.
            AppLog.i("Gecko") {
                val cb = gecko.settings.contentBlocking
                "защита: ETP-уровень=${cb.enhancedTrackingProtectionLevel}, режим cookie=${cb.cookieBehavior}, " +
                    "очистка cookie=${cb.cookiePurging}, SafeBrowsing=${cb.safeBrowsingCategories}"
            }
            PrivacyEngine.apply(gecko, store)
            GeckoColors.attach()
            NetworkTuning.apply()
            gecko.webNotificationDelegate = siteNotifications.delegate
            gecko.webPushController.setDelegate(webPush.delegate)
            gecko.setAutocompleteStorageDelegate(passwords.delegate)
            applyPasswordSettings(gecko)
            passwords.onSettingsChanged = { applyPasswordSettings(gecko) }
            AppExecutors.startThread("hrips-vault") { passwords.ensureLoaded() }
        }
    }

    // Та же схема, что потом выставит HripsTheme: иначе её смена при первой отрисовке перестраивает стили уже грузящейся страницы.
    private fun initialColorScheme(): Int {
        val dark = when (store.themeMode) {
            THEME_MODE_LIGHT -> false
            THEME_MODE_DARK -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        return if (dark) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
    }

    private fun applyPasswordSettings(r: GeckoRuntime) {
        r.settings.setLoginAutofillEnabled(passwords.isBuiltin && passwords.autofill)
    }

    val passwords: PasswordVault by lazy { PasswordVault(this) }
    val store: Store by lazy { Store(this) }
    val downloads: Downloads by lazy { Downloads(this) }
    val permissions: Permissions by lazy { Permissions(this) }
    val prompts: Prompts by lazy { Prompts(this) }
    val adBlock: AdBlock by lazy { AdBlock(runtime) }
    val extensions: Extensions by lazy { Extensions(runtime) }
    val external: ExternalLinks by lazy { ExternalLinks(this) { store.openInApps } }
    val wallpaper: Wallpaper by lazy { Wallpaper(this) }
    val media: MediaHub by lazy { MediaHub(this) }
    val siteNotifications: SiteNotifications by lazy { SiteNotifications(this) }
    val webPush: WebPush by lazy { WebPush(this) }

    private val browserDelegate = lazy {
        adBlock.onInstalled = { extensions.refresh() }
        adBlock.install()
        if (resources.configuration.smallestScreenWidthDp >= 600) ThemeColor.install(runtime)
        MainThread.handler.postDelayed({
            removeLegacyExtensions()
            ReaderMode.install(runtime)
            webPush.refresh()
        }, DEFERRED_EXTENSIONS_MS)
        Browser(runtime, store, downloads, permissions, prompts, adBlock, external, media, extensions).also { b ->
            extensions.openTab = { url, active, engineWillLoad -> b.openForExtension(url, active, engineWillLoad) }
            b.onSiteDataCleared = { webPush.clearSubscriptions() }
            // Сайт из service worker просит открыть окно (clients.openWindow, обычно при нажатии на веб-уведомление).
            // Без этого делегата такое нажатие ничего не открывало.
            runtime.serviceWorkerDelegate = GeckoRuntime.ServiceWorkerDelegate { url ->
                AppLog.i("SW") { "service worker открывает окно: ${AppLog.url(url, false)}" }
                GeckoResult.fromValue(b.openForExtension(url, active = true, engineWillLoad = true))
            }
            extensions.init()
            b.watchNetwork(this)
        }
    }

    private fun removeLegacyExtensions() {
        val legacy = setOf("hover-preview@hrips.app", "pagetext@hrips.app")
        val controller = runtime.webExtensionController
        runCatching {
            controller.list().accept({ all ->
                all?.filter { it.id in legacy }?.forEach { runCatching { controller.uninstall(it) } }
            }, { })
        }
    }

    val browser: Browser
        get() = browserDelegate.value

    val browserIfReady: Browser?
        get() = if (browserDelegate.isInitialized()) browserDelegate.value else null

    @Volatile
    var uiOwner: Any? = null

    private fun isMainProcess(): Boolean = Application.getProcessName() == packageName

    override fun onCreate() {
        super.onCreate()
        if (isMainProcess()) {
            AppLog.configProvider = {
                "защита от слежения=${store.trackingProtection} (строгая=${store.strictProtection}), GPC=${store.gpc}, HTTPS-режим=${store.httpsMode}, " +
                    "DoH-режим=${store.dohMode}, обрезка параметров=${store.stripTrackingParams}, " +
                    "держать страницу в фоне=${store.keepPageInBackground}, " +
                    "приостановка вкладок при нехватке памяти=${store.suspendTabsOnMemoryPressure}, " +
                    "очистка при выходе=${if (store.clearOnExit) ExitClear.describe(store.clearOnExitMask) else "выкл"}, веб-push=${store.webPush}"
            }
            AppLog.init(this, store.logging)
            AppLog.i("App", "Запуск процесса, подряд упавших запусков до этого: ${store.startupFailures}")
            AppExecutors.startThread("hrips-startup-mark") { store.markStartupBegin() }
            MainThread.handler.postDelayed({ store.markStartupStable() }, STARTUP_STABLE_MS)
            if (BuildConfig.DEBUG) enableStrictMode()
            TabRootPrefetch.start(filesDir)
            AppExecutors.startThread("hrips-cleanup") { UploadFiles.cleanup(this) }
        }
        Notices.init(this)
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectNetwork().detectDiskWrites().detectCustomSlowCalls().penaltyLog().build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().detectActivityLeaks().detectLeakedClosableObjects().detectLeakedRegistrationObjects().penaltyLog().build(),
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (AppLog.enabled) AppLog.i("Memory", "onTrimMemory level=$level ${AppLog.memorySnapshot(this)}")
        val existingBrowser = browserIfReady
        TabThumbs.onTrimMemory(level, existingBrowser?.tabs.orEmpty())
        Favicons.onTrimMemory(level)
        PageImages.onTrimMemory(level)
        existingBrowser?.onTrimMemory(level) { isDeviceLowOnMemory() }
    }

    private fun isDeviceLowOnMemory(): Boolean {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java)?.getMemoryInfo(info) ?: return false
        return info.lowMemory || info.availMem < info.threshold * 2
    }
}
