package com.hrips.browser

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime

class HripsApp : Application() {
    // Один движок на всё приложение. Создаётся при первом обращении (главный поток).
    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(this).also {
            applyTrackingProtection(it, store.trackingProtection)
            applyPrivateDefaults(it)
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
    val browser: Browser by lazy {
        adBlock.onInstalled = { extensions.refresh() }
        adBlock.install() // расширение ставим до открытия первых вкладок
        hoverPreview.install()
        Browser(runtime, store, downloads, permissions, prompts, adBlock, external, media, extensions).also { b ->
            extensions.openTab = { url, active -> b.openForExtension(url, active) }
            extensions.init()
        }
    }
}
