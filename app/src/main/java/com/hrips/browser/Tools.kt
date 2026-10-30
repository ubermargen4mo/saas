package com.hrips.browser

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.TransformOrigin

/**
 * Меню «⋯» в стиле iOS 26: компактные группы сегментов без подложки (`SegMenuPopup` + `SegMenuColumn`), как меню по
 * удержанию адресной строки. Выпадает от кнопки «⋯»: на телефоне вверх от нижней панели, на планшете ([below]) вниз.
 * Группы: новые вкладки; разделы (вкладки, закладки, история, загрузки); инструменты; «Закрыть приватные», если они есть.
 * Действия над страницей (закладка, найти, QR, PDF и т. д.) живут в `PageOptionsMenu`, его открывает значок «три строки».
 */
@Composable
fun ToolsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    browser: Browser,
    tab: Tab,
    onLibrary: (page: Int) -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    onScanQr: () -> Unit,
    onFullscreen: () -> Unit,
    onTabs: () -> Unit,
    onExtensions: () -> Unit,
    below: Boolean = false,
) {
    SegMenuPopup(
        visible = expanded,
        onDismiss = onDismiss,
        position = rememberEndAnchorMenuPosition(),
        origin = TransformOrigin(1f, if (below) 0f else 1f),
    ) {
        val act: (() -> Unit) -> () -> Unit = { action -> { onDismiss(); action() } }
        val create = listOf(
            MenuEntry(HripsIcons.Add, "Новая вкладка", onClick = act { browser.newTab(incognito = tab.isPrivate) }),
            MenuEntry(HripsIcons.Mask, "Приватная вкладка", onClick = act { browser.newTab(incognito = true) }),
        )
        val sections = listOf(
            MenuEntry(HripsIcons.Layers, "Вкладки (${browser.tabs.size})", onClick = act { onTabs() }),
            MenuEntry(HripsIcons.Star, "Закладки", onClick = act { onLibrary(0) }),
            MenuEntry(HripsIcons.History, "История", onClick = act { onLibrary(1) }),
            MenuEntry(HripsIcons.Download, "Загрузки", onClick = act { onDownloads() }),
        )
        val tools = listOf(
            MenuEntry(HripsIcons.QrScan, "QR-сканер", onClick = act { onScanQr() }),
            MenuEntry(HripsIcons.Fullscreen, "Весь экран", onClick = act { onFullscreen() }),
            MenuEntry(HripsIcons.Puzzle, "Расширения", onClick = act { onExtensions() }),
            MenuEntry(HripsIcons.Settings, "Настройки", onClick = act { onSettings() }),
        )
        val danger = if (browser.privateCount > 0) {
            listOf(MenuEntry(HripsIcons.Close, "Закрыть приватные (${browser.privateCount})", danger = true, onClick = act { browser.closePrivateTabs() }))
        } else emptyList()
        SegMenuColumn(listOf(create, sections, tools, danger))
    }
}
