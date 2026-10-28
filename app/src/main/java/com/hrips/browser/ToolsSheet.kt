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

private class SheetAction(
    val icon: ImageVector,
    val label: String,
    val active: Boolean = false,
    val onClick: () -> Unit,
)

private class SheetNav(val icon: ImageVector, val label: String, val badge: Int? = null, val onClick: () -> Unit)

/**
 * Меню «⋯» на телефоне: нижняя шторка.
 * Одна тема (primary + нейтральные поверхности), без разноцветных плиток и необычных форм:
 * шапка, двойная кнопка «Новая вкладка / Приватная», сегментный ряд разделов, общие инструменты.
 * Действия над страницей (закладка, найти, перевод, PDF, ПК-версия, без рекламы и т. д.) переехали в `PageOptionsMenu`.
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
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val store = browser.store
    val onPage = !tab.home
    val hasSite = tab.siteHost() != null

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val act: (() -> Unit) -> () -> Unit = { action -> { close(); action() } }

    // Общие инструменты: список с полными подписями, без тесных плиток.
    val tools = listOf(
        SheetAction(HripsIcons.QrScan, "Сканер QR-кодов", onClick = act { onScanQr() }),
        SheetAction(HripsIcons.Fullscreen, "На весь экран", onClick = act { onFullscreen() }),
        SheetAction(HripsIcons.Puzzle, "Расширения", onClick = act { onExtensions() }),
        SheetAction(HripsIcons.Settings, "Настройки", onClick = act { onSettings() }),
    )

    val nav = listOf(
        SheetNav(HripsIcons.Layers, "Вкладки", browser.tabs.size, act { onTabs() }),
        SheetNav(HripsIcons.Star, "Закладки", onClick = act { onLibrary(0) }),
        SheetNav(HripsIcons.History, "История", onClick = act { onLibrary(1) }),
        SheetNav(HripsIcons.Download, "Загрузки", onClick = act { onDownloads() }),
    )

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

            Row(Modifier.fillMaxWidth().toolsIn(1), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                nav.forEachIndexed { i, n ->
                    SegTile(
                        n.icon, n.label, segCorners(nav.size, nav.size, i, 28.dp, 8.dp), Modifier.weight(1f),
                        height = 80.dp, badge = n.badge, onClick = n.onClick,
                    )
                }
            }

            Column(Modifier.fillMaxWidth().toolsIn(2), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                tools.forEachIndexed { i, t ->
                    ToolRow(t.icon, t.label, segCorners(tools.size, 1, i, 24.dp, 8.dp), onClick = t.onClick)
                }
            }

            if (browser.privateCount > 0) {
                DangerPill("Закрыть приватные (${browser.privateCount})", Modifier.toolsIn(3), onClick = act { browser.closePrivateTabs() })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

