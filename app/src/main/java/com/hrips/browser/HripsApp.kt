package com.hrips.browser

import android.app.Application
import android.os.Build
import android.os.StrictMode
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtensionController

/** Сколько работы без сбоя после старта считаем доказательством, что запуск прошёл штатно. */
private const val STARTUP_STABLE_MS = 15_000L

class HripsApp : Application() {
    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                // Как в Fenix: расширения (uBlock Origin) в отдельном процессе, чтобы их сбой не ронял приложение.
                .extensionsProcessEnabled(true)
                .build(),
        ).also { gecko ->
            gecko.webExtensionController.setExtensionProcessDelegate(object : WebExtensionController.ExtensionProcessDelegate {
                override fun onDisabledProcessSpawning() {
                    AppLog.w("Extensions", "процесс расширений отключён после повторных сбоев")
                    Notices.show("Расширения отключены после сбоев до перезапуска приложения")
                }
            })
            applyTrackingProtection(gecko, store.trackingProtection)
            applyPrivateDefaults(gecko)
            PrivacyEngine.apply(gecko, store)
            GeckoColors.attach()
            NetworkTuning.apply()
            gecko.webNotificationDelegate = siteNotifications.delegate
            gecko.setAutocompleteStorageDelegate(passwords.delegate)
            applyPasswordSettings(gecko)
            passwords.onSettingsChanged = { applyPasswordSettings(gecko) }
            AppExecutors.startThread("hrips-vault") { passwords.ensureLoaded() }
        }
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

    private val browserDelegate = lazy {
        adBlock.onInstalled = { extensions.refresh() }
        adBlock.install()
        removeLegacyExtensions()
        // Цвет сайта нужен только шапке планшета.
        if (resources.configuration.smallestScreenWidthDp >= 600) ThemeColor.install(runtime)
        ReaderMode.install(runtime)
        Browser(runtime, store, downloads, permissions, prompts, adBlock, external, media, extensions).also { b ->
            extensions.openTab = { url, active, engineWillLoad -> b.openForExtension(url, active, engineWillLoad) }
            extensions.init()
            b.watchNetwork(this)
        }
    }

    /** Удаляет встроенные расширения, которых в приложении больше нет, но которые ставились раньше. */
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

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrNull()
        }
        return name == null || name == packageName
    }

    override fun onCreate() {
        super.onCreate()
        if (isMainProcess()) {
            AppLog.configProvider = {
                "защита от слежения=${store.trackingProtection}, GPC=${store.gpc}, HTTPS-режим=${store.httpsMode}, " +
                    "DoH-режим=${store.dohMode}, обрезка параметров=${store.stripTrackingParams}, " +
                    "держать страницу в фоне=${store.keepPageInBackground}, " +
                    "приостановка вкладок при нехватке памяти=${store.suspendTabsOnMemoryPressure}"
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
        existingBrowser?.onTrimMemory(level)
    }
}
