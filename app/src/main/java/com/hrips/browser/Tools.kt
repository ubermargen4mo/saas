package com.hrips.browser

import androidx.compose.runtime.Composable

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
