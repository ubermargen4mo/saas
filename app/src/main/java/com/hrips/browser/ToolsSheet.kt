@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.hrips.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class SheetAction(
    val icon: ImageVector,
    val label: String,
    val active: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Меню «⋯» на телефоне: нижняя шторка вместо выпадающего списка.
 * Всё помещается в 3-4 экрана-«сетки» с короткими подписями (никаких длинных строк, которые не влезали),
 * а длинный список действий страницы превращён в сетку плиток 4 в ряд.
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
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val cols = if (screenWidth >= 330) 4 else 3
    val labelSize = if (screenWidth >= 340) 12.sp else 11.sp

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
            Surface(
                shape = HripsShapes.XL,
                color = cs.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().sheetIn(0),
            ) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        NavTile(HripsIcons.Layers, "Вкладки", Badge.BURST, cs.primary, cs.onPrimary, labelSize, Modifier.weight(1f), count = browser.tabs.size, onClick = act { onTabs() })
                        NavTile(HripsIcons.Add, "Новая", Badge.PENTAGON, cs.secondaryContainer, cs.onSecondaryContainer, labelSize, Modifier.weight(1f), onClick = act { browser.newTab(incognito = tab.isPrivate) })
                        NavTile(HripsIcons.Mask, "Приватная", Badge.COOKIE12, cs.tertiaryContainer, cs.onTertiaryContainer, labelSize, Modifier.weight(1f), onClick = act { browser.newTab(incognito = true) })
                        NavTile(HripsIcons.Puzzle, "Расширения", Badge.CLOVER, cs.surfaceVariant, cs.onSurfaceVariant, labelSize, Modifier.weight(1f), onClick = act { onExtensions() })
                    }
                    Row(Modifier.fillMaxWidth()) {
                        NavTile(HripsIcons.Star, "Закладки", Badge.COOKIE9, cs.primaryContainer, cs.onPrimaryContainer, labelSize, Modifier.weight(1f), onClick = act { onLibrary(0) })
                        NavTile(HripsIcons.History, "История", Badge.CLOVER, cs.secondaryContainer, cs.onSecondaryContainer, labelSize, Modifier.weight(1f), onClick = act { onLibrary(1) })
                        NavTile(HripsIcons.Download, "Загрузки", Badge.SUNNY, cs.tertiaryContainer, cs.onTertiaryContainer, labelSize, Modifier.weight(1f), onClick = act { onDownloads() })
                        NavTile(HripsIcons.Settings, "Настройки", Badge.FLOWER, cs.surfaceVariant, cs.onSurfaceVariant, labelSize, Modifier.weight(1f), onClick = act { onSettings() })
                    }
                }
            }

            Row(Modifier.fillMaxWidth().sheetIn(1), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasSite) {
                    ToggleChip(HripsIcons.Desktop, "ПК-версия", tab.desktopMode, true, Modifier.weight(1f)) {
                        val next = !tab.desktopMode
                        haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        tab.setDesktop(next)
                    }
                }
                ToggleChip(HripsIcons.Block, "Без рекламы", browser.adBlock.enabled, browser.adBlock.extension != null, Modifier.weight(1f)) {
                    val next = !browser.adBlock.enabled
                    haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    browser.adBlock.setBlocking(next) { tab.reload() }
                }
            }

            Column(Modifier.fillMaxWidth().sheetIn(2), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.chunked(cols).forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.forEach { a -> ActionTile(a, Modifier.weight(1f)) }
                        repeat(cols - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            if (browser.privateCount > 0) {
                Surface(
                    onClick = act { browser.closePrivateTabs() },
                    shape = HripsShapes.L,
                    color = cs.errorContainer,
                    contentColor = cs.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().sheetIn(3),
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    ) {
                        Icon(HripsIcons.Trash, null, Modifier.size(20.dp))
                        Text(
                            "Закрыть приватные (${browser.privateCount})",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Плавное появление блока: один слой на группу, а не на каждую плитку. */
@Composable
private fun Modifier.sheetIn(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(Unit) {
        delay(40L + index * 45L)
        progress.animateTo(1f, spec)
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * rise
    }
}

@Composable
private fun NavTile(
    icon: ImageVector,
    label: String,
    badge: Badge,
    container: Color,
    content: Color,
    labelSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier,
    count: Int? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, MaterialTheme.motionScheme.fastSpatialSpec<Float>(), label = "navPress")
    Surface(
        onClick = onClick,
        interactionSource = source,
        shape = HripsShapes.L,
        color = Color.Transparent,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 8.dp).graphicsLayer { scaleX = scale; scaleY = scale },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                ShapeBadge(icon, 48.dp, badge = badge, container = container, content = content)
                if (count != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-4).dp)
                            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                            .background(cs.tertiary, CircleShape)
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (count > 99) "99+" else count.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = cs.onTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                label,
                Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = labelSize, letterSpacing = 0.sp),
                color = cs.onSurface,
            )
        }
    }
}

@Composable
private fun ToggleChip(
    icon: ImageVector,
    title: String,
    on: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 14.dp else if (on) 28.dp else 18.dp, motion.fastSpatialSpec<androidx.compose.ui.unit.Dp>(), label = "chipCorner")
    val bg by animateColorAsState(if (on) cs.primaryContainer else cs.surfaceContainerHigh, motion.defaultEffectsSpec<Color>(), label = "chipBg")
    val dot by animateColorAsState(if (on) cs.primary else cs.surfaceContainerHighest, motion.defaultEffectsSpec<Color>(), label = "chipDot")
    val dotIcon by animateColorAsState(if (on) cs.onPrimary else cs.onSurfaceVariant, motion.defaultEffectsSpec<Color>(), label = "chipDotIcon")
    val fg = if (on) cs.onPrimaryContainer else cs.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        shape = RoundedCornerShape(corner),
        color = bg,
        modifier = modifier.height(56.dp).alpha(if (enabled) 1f else 0.4f),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(32.dp).background(dot, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = dotIcon)
            }
            Text(
                title,
                Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
            )
        }
    }
}

@Composable
private fun ActionTile(action: SheetAction, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 10.dp else if (action.active) 28.dp else 20.dp, motion.fastSpatialSpec<androidx.compose.ui.unit.Dp>(), label = "actionCorner")
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, motion.fastSpatialSpec<Float>(), label = "actionPress")
    val bg by animateColorAsState(if (action.active) cs.primaryContainer else cs.surfaceContainerHigh, motion.defaultEffectsSpec<Color>(), label = "actionBg")
    val fg by animateColorAsState(if (action.active) cs.onPrimaryContainer else cs.onSurfaceVariant, motion.defaultEffectsSpec<Color>(), label = "actionFg")
    Surface(
        onClick = action.onClick,
        interactionSource = source,
        shape = RoundedCornerShape(corner),
        color = bg,
        modifier = modifier.height(72.dp).graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(action.icon, null, Modifier.size(24.dp), tint = fg)
            Text(
                action.label,
                Modifier.padding(top = 6.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.sp),
                color = cs.onSurface,
            )
        }
    }
}
