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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.launch

private class SheetTile(val icon: ImageVector, val label: String, val badge: Int? = null, val onClick: () -> Unit)

/**
 * Меню «⋯» на телефоне: нижняя шторка.
 * Одна тема (primary + нейтральные поверхности), без разноцветных плиток и необычных форм. Три блока сверху вниз:
 * двойная кнопка «Новая вкладка / Приватная»; одна сетка 4×2 из восьми разделов и инструментов (значок над короткой
 * подписью, одна высота, один цвет значков); «Закрыть приватные», если они есть. Шторка вдвое ниже прежней,
 * все цели нажатия крупные и стоят у нижнего края, где достаёт большой палец.
 * Действия над страницей (закладка, найти, перевод, PDF, ПК-версия, без рекламы и т. д.) живут в `PageOptionsMenu`.
 */
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

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val act: (() -> Unit) -> () -> Unit = { action -> { close(); action() } }

    // Верхний ряд: разделы браузера. Нижний ряд: общие инструменты. Подписи короткие, чтобы влезали в одну строку.
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surfaceContainerLow,
        shape = HripsShapes.XL,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Двойная кнопка: внешние углы — таблетка, внутренние — маленькие.
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

            // Одна сетка 4×2: большие углы только на внешнем контуре, между плитками зазор 2 dp.
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
            Spacer(Modifier.height(16.dp))
        }
    }
}
