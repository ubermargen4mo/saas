package com.hrips.browser.screens

import com.hrips.browser.ContextInfo
import com.hrips.browser.PendingDownload
import com.hrips.browser.PermissionRequest
import org.junit.Test
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import java.io.ByteArrayInputStream
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Сценарии: каждый открывает экран так же, как пользователь, и снимает его. */
abstract class Screens(device: String, private val wide: Boolean) : Tour(device) {
    private fun menu() = if (wide) click("Инструменты", desc = true) else click("Меню", desc = true)

    private fun openTabs() = if (wide) click("Вкладки", desc = true, substring = true) else { menu(); click("Вкладки") }

    private fun settings(page: String? = null) {
        start()
        menu()
        click("Настройки")
        if (page != null) click(page)
    }

    @Test fun s01_onboarding() { start(firstRun = true); shot("01_onboarding") }

    @Test fun s02_start() { start(); shot("02_start") }

    @Test fun s03_start_dark() { start(dark = true); shot("03_start_dark") }

    @Test fun s04_search_panel() {
        start()
        if (wide) click("Искать или задать вопрос") else click("Поиск")
        shot("04_search_panel")
    }

    @Test fun s05_page() {
        start { b -> pageTab("https://ru.wikipedia.org/wiki/Android", "Android — Википедия") }
        shot("05_page")
    }

    @Test fun s06_tools() { start(); menu(); shot("06_tools") }

    @Test fun s07_tools_page() {
        start { pageTab("https://ru.wikipedia.org/wiki/Android", "Android — Википедия") }
        menu()
        shot("07_tools_page")
    }

    @Test fun s08_tabs() {
        start { b ->
            pageTab("https://github.com/mozilla/gecko-dev", "mozilla/gecko-dev: Read-only Git mirror")
            pageTab("https://habr.com/ru/articles/", "Статьи / Хабр")
            pageTab("https://www.youtube.com/watch?v=1", "Lo-fi beats to relax/study to")
            pageTab("https://ru.wikipedia.org/wiki/Android", "Android — Википедия")
        }
        openTabs()
        shot("08_tabs")
    }

    @Test fun s09_tabs_private() {
        start { b ->
            pageTab("https://duckduckgo.com/", "DuckDuckGo", private = true)
        }
        openTabs()
        shot("09_tabs_private")
    }

    @Test fun s10_private_start() {
        start { b -> b.newTab(incognito = true) }
        shot("10_private_start")
    }

    @Test fun s11b_page_security() {
        start { pageTab("https://ru.wikipedia.org/wiki/Android", "Android — Википедия") }
        click("Меню сайта", desc = true)
        click("Безопасность соединения", desc = true)
        shot("11b_page_security")
    }

    @Test fun s11_page_options() {
        start { pageTab("https://ru.wikipedia.org/wiki/Android", "Android — Википедия") }
        click("Меню сайта", desc = true)
        shot("11_page_options")
    }

    @Test fun s16_find() {
        start { pageTab("https://ru.wikipedia.org/wiki/Android", "Android — Википедия") }
        click("Меню сайта", desc = true)
        click("Найти на странице", desc = true)
        shot("16_find")
    }

    @Test fun s17_context_link() {
        start { b ->
            pageTab("https://habr.com/ru/articles/", "Статьи / Хабр")
            b.contextMenu = ContextInfo("https://habr.com/ru/articles/912345/", null, ContextElement.TYPE_NONE, "Как устроен GeckoView", null, "https://habr.com/ru/articles/")
        }
        shot("17_context_link")
    }

    @Test fun s18_context_image() {
        start { b ->
            pageTab("https://habr.com/ru/articles/", "Статьи / Хабр")
            b.contextMenu = ContextInfo(null, "https://habrastorage.org/cover.png", ContextElement.TYPE_IMAGE, null, "Обложка статьи", "https://habr.com/ru/articles/")
        }
        shot("18_context_image")
    }

    @Test fun s19_download_prompt() {
        start { b ->
            pageTab("https://example.com/files", "Файлы")
            b.downloads.pending.add(PendingDownload(ByteArrayInputStream(ByteArray(0)), "Отчёт за 2026 год.pdf", "application/pdf", 2_450_000, "example.com", null))
        }
        shot("19_download_prompt")
    }

    @Test fun s32_permission_dialog() {
        start { b ->
            pageTab("https://meet.jit.si/hrips", "Jitsi Meet")
            b.permissions.queue.add(PermissionRequest("meet.jit.si", "камеру и микрофон", listOf("camera", "microphone"), true, null) { })
        }
        shot("32_permission_dialog")
    }

    @Test fun s33_site_permissions() { settings("Разрешения"); click("Разрешения сайтов"); shot("33_site_permissions") }

    @Test fun s34_passwords() { settings("Пароли и автозаполнение"); click("Открыть сохранённые пароли"); shot("34_passwords") }

    @Test fun s35_clear_data() { settings("Конфиденциальность"); click("Очистить данные"); shot("35_clear_data") }

    @Test fun s36_tabs_list() {
        start { b ->
            b.store.updateTabsView(0)
            pageTab("https://github.com/mozilla/gecko-dev", "mozilla/gecko-dev: Read-only Git mirror")
            pageTab("https://habr.com/ru/articles/", "Статьи / Хабр")
            pageTab("https://ru.wikipedia.org/wiki/Android", "Android — Википедия")
        }
        openTabs()
        shot("36_tabs_list")
    }

    @Test fun s37_tabs_cards() {
        start { b ->
            b.store.updateTabsView(2)
            pageTab("https://github.com/mozilla/gecko-dev", "mozilla/gecko-dev: Read-only Git mirror")
            pageTab("https://habr.com/ru/articles/", "Статьи / Хабр")
            pageTab("https://ru.wikipedia.org/wiki/Android", "Android — Википедия")
        }
        openTabs()
        shot("37_tabs_cards")
    }

    @Test fun s38_tools_dark() { start(dark = true); menu(); shot("38_tools_dark") }

    @Test fun s39_tabs_dark() {
        start(dark = true) { b ->
            pageTab("https://habr.com/ru/articles/", "Статьи / Хабр")
            pageTab("https://ru.wikipedia.org/wiki/Android", "Android — Википедия")
        }
        openTabs()
        shot("39_tabs_dark")
    }

    @Test fun s40_page_dark() {
        start(dark = true) { pageTab("https://ru.wikipedia.org/wiki/Android", "Android — Википедия") }
        shot("40_page_dark")
    }

    @Test fun s12_history() { start(); menu(); click("История"); shot("12_history") }

    @Test fun s13_bookmarks() { start(); menu(); click("Закладки"); shot("13_bookmarks") }

    @Test fun s14_downloads() { start(); menu(); click("Загрузки"); shot("14_downloads") }

    @Test fun s15_extensions() { start(); menu(); click("Расширения"); shot("15_extensions") }

    @Test fun s20_settings() { settings(); shot("20_settings") }
    @Test fun s21_settings_search() { settings("Поиск"); shot("21_settings_search") }
    @Test fun s22_settings_look() { settings("Внешний вид"); shot("22_settings_look") }
    @Test fun s23_settings_privacy() { settings("Конфиденциальность"); shot("23_settings_privacy") }
    @Test fun s24_settings_passwords() { settings("Пароли и автозаполнение"); shot("24_settings_passwords") }
    @Test fun s25_settings_permissions() { settings("Разрешения"); shot("25_settings_permissions") }
    @Test fun s26_settings_desktop() { settings("Версия для ПК"); shot("26_settings_desktop") }
    @Test fun s27_settings_media() { settings("Медиа"); shot("27_settings_media") }
    @Test fun s28_settings_downloads() { settings("Загрузки"); shot("28_settings_downloads") }
    @Test fun s29_settings_performance() { settings("Производительность"); shot("29_settings_performance") }
    @Test fun s30_settings_about() { settings("О программе"); shot("30_settings_about") }
    @Test fun s31_settings_dark() { start(dark = true); menu(); click("Настройки"); shot("31_settings_dark") }
}

private const val PHONE = "ru-rRU-w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav"
private const val TABLET = "ru-rRU-w1280dp-h800dp-xlarge-long-notround-any-240dpi-keyshidden-nonav"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE)
class PhoneScreens : Screens("phone", wide = false)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = TABLET)
class TabletScreens : Screens("tablet", wide = true)
