@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.hrips.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private class SheetTile(val icon: ImageVector, val label: String, val badge: Int? = null, val onClick: () -> Unit)

@Composable
internal fun ToolsSheet(
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
    val cs = MaterialTheme.colorScheme
    val activity = androidx.activity.compose.LocalActivity.current

    val wide = isWideWindow()
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        if (wide) onDismiss() else scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val act: (() -> Unit) -> () -> Unit = { action -> { close(); action() } }

    val tiles = listOf(
        SheetTile(HripsIcons.Layers, "Вкладки", browser.tabs.size, act { onTabs() }),
        SheetTile(HripsIcons.Star, "Закладки", onClick = act { onLibrary(0) }),
        SheetTile(HripsIcons.History, "История", onClick = act { onLibrary(1) }),
        SheetTile(HripsIcons.Download, "Загрузки", onClick = act { onDownloads() }),
        SheetTile(HripsIcons.QrScan, "QR-сканер", onClick = act { onScanQr() }),
        SheetTile(HripsIcons.Fullscreen, "Весь экран", onClick = act { onFullscreen() }),
        SheetTile(HripsIcons.Puzzle, "Расширения", onClick = act { onExtensions() }),
        SheetTile(HripsIcons.Settings, "Настройки", onClick = act { onSettings() }),
    )
    val cols = 4

    AdaptiveMenu(wide, onDismiss, sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().toolsIn(0), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ToolButton(
                    "Новая вкладка", HripsIcons.Add, cs.primary, cs.onPrimary,
                    Corners(28.dp, 8.dp, 8.dp, 28.dp), Modifier.weight(1.4f),
                    onClick = act { browser.newTab(incognito = tab.isPrivate) },
                )
                ToolButton(
                    "Приватная", HripsIcons.Mask, cs.primaryContainer, cs.onPrimaryContainer,
                    Corners(8.dp, 28.dp, 28.dp, 8.dp), Modifier.weight(1f),
                    onClick = act { browser.newTab(incognito = true) },
                )
            }

            Column(Modifier.fillMaxWidth().toolsIn(1), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                tiles.chunked(cols).forEachIndexed { r, rowTiles ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        rowTiles.forEachIndexed { c, t ->
                            SegTile(
                                t.icon, t.label, segCorners(tiles.size, cols, r * cols + c, 28.dp, 8.dp), Modifier.weight(1f),
                                height = 84.dp, badge = t.badge, labelSize = 12.sp, labelLines = 1,
                                iconTint = cs.onSurface, onClick = t.onClick,
                            )
                        }
                    }
                }
            }

            if (browser.privateCount > 0) {
                DangerPill("Закрыть приватные (${browser.privateCount})", Modifier.toolsIn(2), onClick = act { browser.closePrivateTabs() })
            }
            if (browser.store.clearOnExit) {
                val label = if (ExitClear.sanitize(browser.store.clearOnExitMask) == 0) "Выйти" else "Выйти и удалить данные"
                DangerPill(label, Modifier.toolsIn(3), onClick = act { activity?.let { browser.quit(it) } })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
