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
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * шапка страницы, двойная кнопка «Новая вкладка / Приватная», сегментный ряд разделов, два тумблера,
 * сетка действий страницы (сегментная, большие внешние углы), пара «Расширения / Настройки».
 */
@Composable
internal fun ToolsSheet(
    onDismiss: () -> Unit,
    browser: Browser,
    tab: Tab,
    onFind: () -> Unit,
    onLibrary: (page: Int) -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    onScreenshot: () -> Unit,
    onScanQr: () -> Unit,
    onFullscreen: () -> Unit,
    onTabs: () -> Unit,
    onExtensions: () -> Unit,
    onQr: () -> Unit,
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

    val saved = onPage && store.isBookmarked(tab.url)
    val actions = buildList {
        if (onPage) {
            add(SheetAction(if (saved) HripsIcons.StarFilled else HripsIcons.Star, if (saved) "В закладках" else "Закладка", saved, act {
                toggleBookmarkWithNotice(store, tab.url, tab.title)
            }))
            add(SheetAction(HripsIcons.Share, "Поделиться", onClick = act { PageActions.share(context, tab) }))
            add(SheetAction(HripsIcons.FindInPage, "Найти", onClick = act { onFind() }))
            add(SheetAction(HripsIcons.Qr, "QR-код", onClick = act { onQr() }))
            if (hasSite && !PageActions.isTranslated(tab)) {
                add(SheetAction(HripsIcons.Translate, "Перевести", onClick = act { PageActions.translate(tab) }))
            }
            if (hasSite) add(SheetAction(HripsIcons.Open, "В приложении", onClick = act { PageActions.openInApp(context, tab.url) }))
            add(SheetAction(HripsIcons.FilePdf, "PDF", onClick = act { PageActions.savePdf(tab, browser.downloads) }))
            add(SheetAction(HripsIcons.Capture, "Снимок", onClick = act { onScreenshot() }))
            add(SheetAction(HripsIcons.Home, "На старт", onClick = act { PageActions.addToStartPage(context, store, tab) }))
            if (hasSite && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                add(SheetAction(HripsIcons.Phone, "Ярлык", onClick = act { PageActions.addToHomeScreen(context, tab) }))
            }
        }
        add(SheetAction(HripsIcons.QrScan, "Сканер QR", onClick = act { onScanQr() }))
        add(SheetAction(HripsIcons.Fullscreen, "Весь экран", onClick = act { onFullscreen() }))
    }

    val nav = listOf(
        SheetNav(HripsIcons.Layers, "Вкладки", browser.tabs.size, act { onTabs() }),
        SheetNav(HripsIcons.Star, "Закладки", onClick = act { onLibrary(0) }),
        SheetNav(HripsIcons.History, "История", onClick = act { onLibrary(1) }),
        SheetNav(HripsIcons.Download, "Загрузки", onClick = act { onDownloads() }),
    )

    val (letter, title, subtitle) = toolsHeaderOf(tab)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolsHeader(letter, title, subtitle, Modifier.toolsIn(0))

            // Двойная кнопка: внешние углы — таблетка, внутренние — маленькие.
            Row(Modifier.fillMaxWidth().toolsIn(1), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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

            Row(Modifier.fillMaxWidth().toolsIn(2), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                nav.forEachIndexed { i, n ->
                    SegTile(
                        n.icon, n.label, segCorners(nav.size, nav.size, i, 28.dp, 8.dp), Modifier.weight(1f),
                        height = 80.dp, badge = n.badge, onClick = n.onClick,
                    )
                }
            }

            Row(Modifier.fillMaxWidth().toolsIn(3), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (hasSite) {
                    ToolToggle(HripsIcons.Desktop, "ПК-версия", tab.desktopMode, true, Modifier.weight(1f)) {
                        val next = !tab.desktopMode
                        haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        tab.setDesktop(next)
                    }
                }
                ToolToggle(HripsIcons.Block, "Без рекламы", browser.adBlock.enabled, browser.adBlock.extension != null, Modifier.weight(1f)) {
                    val next = !browser.adBlock.enabled
                    haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    browser.adBlock.setBlocking(next) { tab.reload() }
                }
            }

            Column(Modifier.fillMaxWidth().toolsIn(4)) {
                Text(
                    if (onPage) "Эта страница" else "Инструменты",
                    Modifier.padding(start = 12.dp, top = 6.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    actions.chunked(SHEET_COLS).forEachIndexed { r, line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            line.forEachIndexed { c, a ->
                                SegTile(
                                    a.icon, a.label, segCorners(actions.size, SHEET_COLS, r * SHEET_COLS + c, 28.dp, 8.dp),
                                    Modifier.weight(1f), on = a.active, onClick = a.onClick,
                                )
                            }
                            repeat(SHEET_COLS - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().toolsIn(5), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ToolButton(
                    "Расширения", HripsIcons.Puzzle, cs.surfaceContainerHigh, cs.onSurface,
                    Corners(28.dp, 8.dp, 8.dp, 28.dp), Modifier.weight(1f),
                    onClick = act { onExtensions() },
                )
                ToolButton(
                    "Настройки", HripsIcons.Settings, cs.surfaceContainerHigh, cs.onSurface,
                    Corners(8.dp, 28.dp, 28.dp, 8.dp), Modifier.weight(1f),
                    onClick = act { onSettings() },
                )
            }

            if (browser.privateCount > 0) {
                DangerPill("Закрыть приватные (${browser.privateCount})", Modifier.toolsIn(6), onClick = act { browser.closePrivateTabs() })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private const val SHEET_COLS = 4
