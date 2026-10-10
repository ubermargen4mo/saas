package com.hrips.browser

import android.app.Application
import android.os.Build
import android.os.StrictMode
import org.mozilla.geckoview.GeckoRuntime

class HripsApp : Application() {
    // Один движок на всё приложение. Создаётся при первом обращении (главный поток).
    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(this).also {
            applyTrackingProtection(it, store.trackingProtection)
            applyPrivateDefaults(it)
            PrivacyEngine.apply(it, store)
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

    // Версия для ПК включается только вручную и только для конкретного сайта (см. Store.desktopSites)
    private val browserDelegate = lazy {
        adBlock.onInstalled = { extensions.refresh() }
        adBlock.install() // расширение ставим до открытия первых вкладок
        hoverPreview.install()
        Browser(runtime, store, downloads, permissions, prompts, adBlock, external, media, extensions).also { b ->
            extensions.openTab = { url, active, engineWillLoad -> b.openForExtension(url, active, engineWillLoad) }
            extensions.init()
        }
    }

    val browser: Browser
        get() = browserDelegate.value

    /** Браузер, если он уже создан; не запускает движок, в отличие от [browser]. */
    val browserIfReady: Browser?
        get() = if (browserDelegate.isInitialized()) browserDelegate.value else null

    /** Activity, которая сейчас владеет UI-колбэками (выбор файлов, запрос разрешений). Нужна, чтобы старая не сбросила колбэки новой. */
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
        // Application создаётся и в дочерних процессах движка (:tab, :gpu...): файл вкладок нужен только основному.
        // Читаем его в фоне, пока главный поток создаёт движок: к восстановлению он уже разобран
        if (isMainProcess()) {
            if (BuildConfig.DEBUG) enableStrictMode()
            TabRootPrefetch.start(filesDir)
        }
        Notices.init(this)
    }

    /**
     * Только для debug-сборок: пишет в logcat (тег StrictMode) обращения к диску и сети на главном потоке
     * и утечки Activity/Closeable. penaltyLog без penaltyDeath: приложение не роняет, только показывает узкие места.
     */
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
