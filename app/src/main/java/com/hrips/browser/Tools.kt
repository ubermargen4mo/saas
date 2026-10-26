@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat

/** Телефон: нижняя шторка (`ToolsSheet`). Планшет и широкое окно: выпадающее меню (`ToolsPopup`). */
@Composable
fun ToolsMenu(
    expanded: Boolean,
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
) {
    val wide = isWideWindow()
    var showQr by remember { mutableStateOf(false) }
    if (wide) {
        ToolsPopup(
            expanded, onDismiss, browser, tab, onFind, onLibrary, onDownloads, onSettings,
            onScreenshot, onScanQr, onFullscreen, onTabs, onExtensions, onQr = { showQr = true },
        )
    } else if (expanded) {
        ToolsSheet(
            onDismiss, browser, tab, onFind, onLibrary, onDownloads, onSettings,
            onScreenshot, onScanQr, onFullscreen, onTabs, onExtensions, onQr = { showQr = true },
        )
    }
    if (showQr) QrDialog(tab.url) { showQr = false }
}

private class MenuAction(
    val icon: ImageVector,
    val label: String,
    val checked: Boolean = false,
    val trailing: ImageVector? = null,
    val onClick: () -> Unit,
)

private class SubItem(val icon: ImageVector, val title: String, val text: String, val onClick: () -> Unit)

private val PANEL_WIDTH = 332.dp

/**
 * Планшет и ПК: панель у кнопки «⋯». Тот же набор элементов, что в шторке телефона:
 * шапка страницы, сегментный ряд разделов, тумблеры, быстрые действия иконками, список в сегментной группе,
 * двойная кнопка «Вкладка / Приватная». «Добавить в…» открывает второй экран панели со слайдом.
 */
@Composable
private fun ToolsPopup(
    expanded: Boolean,
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
    var sub by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) sub = false }

    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val menuResize = androidx.compose.animation.SizeTransform(clip = true) { _, _ ->
        tween(HripsMotion.ChromeMs, easing = HripsMotion.ChromeEasing)
    }

    val panelWidth = minOf(PANEL_WIDTH, (LocalConfiguration.current.screenWidthDp - 32).dp)
    val (letter, title, subtitle) = toolsHeaderOf(tab)

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = HripsShapes.XL,
        containerColor = cs.surfaceContainer,
    ) {
        AnimatedContent(
            targetState = sub,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(slideSpec) { it / 5 } + fadeIn(fadeInSpec)) togetherWith
                        (slideOutHorizontally(slideSpec) { -it / 5 } + fadeOut(fadeOutSpec)) using menuResize
                } else {
                    (slideInHorizontally(slideSpec) { -it / 5 } + fadeIn(fadeInSpec)) togetherWith
                        (slideOutHorizontally(slideSpec) { it / 5 } + fadeOut(fadeOutSpec)) using menuResize
                }
            },
            label = "tools-menu",
        ) { isSub ->
            Column(
                Modifier.width(panelWidth).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isSub) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { sub = false }) { Icon(HripsIcons.Back, "Назад") }
                        Text("Добавить в…", style = MaterialTheme.typography.titleMedium)
                    }
                    val items = buildList {
                        add(SubItem(HripsIcons.Home, "На начальную страницу", "Плитка среди ваших сайтов") {
                            onDismiss(); PageActions.addToStartPage(context, store, tab)
                        })
                        if (hasSite && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                            add(SubItem(HripsIcons.Phone, "На главный экран", "Ярлык рядом с приложениями") {
                                onDismiss(); PageActions.addToHomeScreen(context, tab)
                            })
                        }
                    }
                    Column(Modifier.fillMaxWidth().toolsIn(0), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items.forEachIndexed { i, item ->
                            SubRow(item.icon, item.title, item.text, segCorners(items.size, 1, i, 24.dp, 6.dp), item.onClick)
                        }
                    }
                } else {
                    ToolsHeader(letter, title, subtitle, Modifier.toolsIn(0), size = 40.dp, tab = tab)

                    val nav = listOf(
                        MenuAction(HripsIcons.Star, "Закладки") { onDismiss(); onLibrary(0) },
                        MenuAction(HripsIcons.History, "История") { onDismiss(); onLibrary(1) },
                        MenuAction(HripsIcons.Download, "Загрузки") { onDismiss(); onDownloads() },
                        MenuAction(HripsIcons.Settings, "Настройки") { onDismiss(); onSettings() },
                    )
                    Row(Modifier.fillMaxWidth().toolsIn(1), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        nav.forEachIndexed { i, a ->
                            SegTile(a.icon, a.label, segCorners(nav.size, nav.size, i, 26.dp, 8.dp), Modifier.weight(1f), onClick = a.onClick)
                        }
                    }

                    Row(Modifier.fillMaxWidth().toolsIn(2), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (hasSite) {
                            ToolToggle(HripsIcons.Desktop, "ПК-версия", tab.desktopMode, true, Modifier.weight(1f), height = 56.dp) {
                                val next = !tab.desktopMode
                                haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                tab.setDesktop(next)
                            }
                        }
                        ToolToggle(
                            HripsIcons.Block, "Без рекламы", browser.adBlock.enabled, browser.adBlock.extension != null,
                            Modifier.weight(1f), height = 56.dp,
                        ) {
                            val next = !browser.adBlock.enabled
                            haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            browser.adBlock.setBlocking(next) { tab.reload() }
                        }
                    }

                    val saved = onPage && store.isBookmarked(tab.url)
                    var index = 3
                    if (onPage) {
                        val quick = listOf(
                            MenuAction(if (saved) HripsIcons.StarFilled else HripsIcons.Star, if (saved) "В закладках" else "В закладки", saved) {
                                onDismiss(); toggleBookmarkWithNotice(store, tab.url, tab.title)
                            },
                            MenuAction(HripsIcons.Share, "Поделиться") { onDismiss(); PageActions.share(context, tab) },
                            MenuAction(HripsIcons.FindInPage, "Найти на странице") { onDismiss(); onFind() },
                            MenuAction(HripsIcons.Qr, "QR этой страницы") { onDismiss(); onQr() },
                        )
                        Row(Modifier.fillMaxWidth().toolsIn(index++), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            quick.forEachIndexed { i, a ->
                                SegIcon(a.icon, a.label, segCorners(quick.size, quick.size, i, 26.dp, 8.dp), Modifier.weight(1f), on = a.checked, onClick = a.onClick)
                            }
                        }
                    }

                    val rows = buildList {
                        if (onPage) {
                            if (hasSite && !PageActions.isTranslated(tab)) {
                                val back = tab.translated || tab.translating
                                add(MenuAction(HripsIcons.Translate, if (back) "Показать оригинал" else "Перевести", back) { onDismiss(); PageActions.translate(tab, browser) })
                            }
                            if (hasSite) add(MenuAction(HripsIcons.Open, "В приложении") { onDismiss(); PageActions.openInApp(context, tab.url) })
                            add(MenuAction(HripsIcons.FilePdf, "Сохранить PDF") { onDismiss(); PageActions.savePdf(tab, browser.downloads) })
                            add(MenuAction(HripsIcons.Capture, "Снимок") { onDismiss(); onScreenshot() })
                            add(MenuAction(HripsIcons.PlusCircle, "Добавить в…", trailing = HripsIcons.ChevronRight) { sub = true })
                        }
                        add(MenuAction(HripsIcons.QrScan, "Сканер QR") { onDismiss(); onScanQr() })
                        add(MenuAction(HripsIcons.Fullscreen, "На весь экран") { onDismiss(); onFullscreen() })
                    }
                    Column(Modifier.fillMaxWidth().toolsIn(index++), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        rows.forEachIndexed { i, a ->
                            ToolRow(a.icon, a.label, segCorners(rows.size, 1, i, 22.dp, 6.dp), trailing = a.trailing, onClick = a.onClick)
                        }
                    }

                    Row(Modifier.fillMaxWidth().toolsIn(index++), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        ToolButton(
                            "Вкладка", HripsIcons.Add, cs.primary, cs.onPrimary,
                            Corners(26.dp, 8.dp, 8.dp, 26.dp), Modifier.weight(1f), height = 52.dp,
                            onClick = { onDismiss(); browser.newTab(incognito = tab.isPrivate) },
                        )
                        ToolButton(
                            "Приватная", HripsIcons.Mask, cs.primaryContainer, cs.onPrimaryContainer,
                            Corners(8.dp, 26.dp, 26.dp, 8.dp), Modifier.weight(1f), height = 52.dp,
                            onClick = { onDismiss(); browser.newTab(incognito = true) },
                        )
                    }
                    if (browser.privateCount > 0) {
                        DangerPill(
                            "Закрыть приватные (${browser.privateCount})", Modifier.toolsIn(index), height = 48.dp,
                            onClick = { onDismiss(); browser.closePrivateTabs() },
                        )
                    }
                }
            }
        }
    }
}
