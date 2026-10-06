package com.hrips.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat

/**
 * Меню "три точки" в стиле Material 3 Expressive: плитки быстрых разделов сверху, дальше группы
 * действий страницы. «Добавить в…» открывает подменю на месте (без второго окна).
 */
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
    onFullscreen: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val store = browser.store
    val onPage = !tab.home
    var sub by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) sub = false }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = cs.surfaceContainer,
    ) {
        AnimatedContent(
            targetState = sub,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally { it / 5 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 5 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 5 } + fadeIn()) togetherWith (slideOutHorizontally { it / 5 } + fadeOut())
                }
            },
            label = "tools-menu",
        ) { isSub ->
            Column(Modifier.width(304.dp).padding(8.dp)) {
                if (isSub) {
                    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { sub = false }) { Icon(HripsIcons.Back, "Назад") }
                        Text("Добавить в…", style = MaterialTheme.typography.titleMedium)
                    }
                    MenuRow(HripsIcons.Home, "На начальную страницу", onPage) {
                        onDismiss(); PageActions.addToStartPage(context, store, tab)
                    }
                    MenuRow(
                        HripsIcons.Grid, "На главный экран",
                        onPage && tab.siteHost() != null && ShortcutManagerCompat.isRequestPinShortcutSupported(context),
                    ) { onDismiss(); PageActions.addToHomeScreen(context, tab) }
                } else {
                    // Быстрые разделы браузера
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickTile(HripsIcons.Star, "Закладки", Modifier.weight(1f)) { onDismiss(); onLibrary(0) }
                        QuickTile(HripsIcons.History, "История", Modifier.weight(1f)) { onDismiss(); onLibrary(1) }
                        QuickTile(HripsIcons.Download, "Загрузки", Modifier.weight(1f)) { onDismiss(); onDownloads() }
                        QuickTile(HripsIcons.Settings, "Настройки", Modifier.weight(1f)) { onDismiss(); onSettings() }
                    }

                    MenuRow(
                        HripsIcons.Share, "Поделиться", onPage,
                        onClick = { onDismiss(); PageActions.share(context, tab) },
                        trailing = {
                            IconButton(onClick = { onDismiss(); showQr = true }, enabled = onPage, modifier = Modifier.size(40.dp)) {
                                Icon(HripsIcons.Qr, "QR-код", Modifier.size(22.dp))
                            }
                        },
                    )
                    val saved = onPage && store.isBookmarked(tab.url)
                    MenuRow(
                        if (saved) HripsIcons.StarFilled else HripsIcons.Star,
                        if (saved) "Удалить из закладок" else "Добавить в закладки", onPage,
                    ) { onDismiss(); store.toggleBookmark(tab.url, tab.title) }
                    MenuRow(
                        HripsIcons.PlusCircle, "Добавить в…", onPage,
                        onClick = { sub = true },
                        trailing = { Icon(HripsIcons.ChevronRight, null, tint = cs.onSurfaceVariant) },
                    )

                    MenuDivider()

                    MenuRow(
                        HripsIcons.Desktop, "Версия для ПК",
                        // Режим привязан к сайту, поэтому на стартовой и служебных страницах включать его не к чему
                        tab.siteHost() != null,
                        onClick = { onDismiss(); tab.setDesktop(!tab.desktopMode) },
                        trailing = { Switch(checked = tab.desktopMode, onCheckedChange = null, enabled = tab.siteHost() != null) },
                    )
                    MenuRow(
                        HripsIcons.Block, "Блокировка рекламы", browser.adBlock.extension != null,
                        onClick = {
                            onDismiss()
                            browser.adBlock.setBlocking(!browser.adBlock.enabled)
                            tab.session.reload()
                        },
                        trailing = { Switch(checked = browser.adBlock.enabled, onCheckedChange = null, enabled = browser.adBlock.extension != null) },
                    )
                    MenuRow(HripsIcons.Open, "Открыть в приложении", onPage && tab.siteHost() != null) {
                        onDismiss(); PageActions.openInApp(context, tab.url)
                    }
                    MenuRow(HripsIcons.Translate, "Перевести", onPage && tab.siteHost() != null && !PageActions.isTranslated(tab)) {
                        onDismiss(); PageActions.translate(tab)
                    }
                    MenuRow(HripsIcons.Search, "Найти на странице", onPage) { onDismiss(); onFind() }
                    MenuRow(HripsIcons.FilePdf, "Сохранить как PDF", onPage) {
                        onDismiss(); PageActions.savePdf(tab, browser.downloads)
                    }
                    MenuRow(HripsIcons.Capture, "Сделать снимок", onPage) { onDismiss(); onScreenshot() }
                    MenuRow(HripsIcons.Fullscreen, "Полноэкранный режим", true) { onDismiss(); onFullscreen() }

                    MenuDivider()

                    MenuRow(HripsIcons.Add, "Новая вкладка", true) { onDismiss(); browser.newTab(incognito = tab.isPrivate) }
                    MenuRow(HripsIcons.Mask, "Новая приватная вкладка", true) { onDismiss(); browser.newTab(incognito = true) }
                    if (browser.privateCount > 0) {
                        MenuRow(HripsIcons.Trash, "Закрыть приватные вкладки (${browser.privateCount})", true) {
                            onDismiss(); browser.closePrivateTabs()
                        }
                    }
                }
            }
        }
    }

    if (showQr) QrDialog(tab.url) { showQr = false }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Плитка раздела: значок над подписью, скругление 20dp. */
@Composable
private fun QuickTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = cs.secondaryContainer, modifier = modifier) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(22.dp), tint = cs.onSecondaryContainer)
            Text(
                label, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer,
            )
        }
    }
}

/** Строка меню: значок, текст, необязательный элемент справа (переключатель, стрелка, кнопка). */
@Composable
private fun MenuRow(
    icon: ImageVector,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            trailing?.invoke()
        }
    }
}
