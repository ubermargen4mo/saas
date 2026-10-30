package com.hrips.browser

import androidx.compose.runtime.Composable

/**
 * Меню «⋯»: на телефоне и на планшете одна и та же нижняя шторка (`ToolsSheet`).
 * Действия над страницей (закладка, найти, QR, PDF и т. д.) живут в `PageOptionsMenu`, его открывает
 * значок «три строки» в адресной строке.
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
) {
    if (!expanded) return
    ToolsSheet(
        onDismiss, browser, tab, onLibrary, onDownloads, onSettings,
        onScanQr, onFullscreen, onTabs, onExtensions,
    )
}
