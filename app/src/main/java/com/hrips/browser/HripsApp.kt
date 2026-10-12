package com.hrips.browser

import android.app.Application
import android.os.Build
import android.os.StrictMode
import org.mozilla.geckoview.GeckoRuntime

class HripsApp : Application() {
    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(this).also {
            applyTrackingProtection(it, store.trackingProtection)
            applyPrivateDefaults(it)
            PrivacyEngine.apply(it, store)
            GeckoColors.attach()
            it.webNotificationDelegate = siteNotifications.delegate
        }
    }
    val store: Store by lazy { Store(this) }
    val downloads: Downloads by lazy { Downloads(this) }
    val permissions: Permissions by lazy { Permissions(this) }
    val prompts: Prompts by lazy { Prompts(this) }
    val adBlock: AdBlock by lazy { AdBlock(runtime) }
    val hoverPreview: HoverPreview by lazy { HoverPreview(runtime) }
    val extensions: Extensions by lazy { Extensions(runtime) }
    val external: ExternalLinks by lazy { ExternalLinks(this) }
    val wallpaper: Wallpaper by lazy { Wallpaper(this) }
    val media: MediaHub by lazy { MediaHub(this) }
    val siteNotifications: SiteNotifications by lazy { SiteNotifications(this) }

    private val browserDelegate = lazy {
        adBlock.onInstalled = { extensions.refresh() }
        adBlock.install()
        hoverPreview.install()
        SiteColors.install(runtime)
        Browser(runtime, store, downloads, permissions, prompts, adBlock, external, media, extensions).also { b ->
            extensions.openTab = { url, active, engineWillLoad -> b.openForExtension(url, active, engineWillLoad) }
            extensions.init()
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
            if (BuildConfig.DEBUG) enableStrictMode()
            TabRootPrefetch.start(filesDir)
        }
        Notices.init(this)
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectNetwork().detectDiskWrites().detectCustomSlowCalls().penaltyLog().build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().detectActivityLeaks().detectLeakedClosableObjects().detectLeakedRegistrationObjects().penaltyLog().build()
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (browserDelegate.isInitialized()) browser.onTrimMemory(level)
    }
}
