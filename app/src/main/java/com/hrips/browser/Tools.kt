@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.delay

/**
 * Меню "три точки" в стиле Material 3 Expressive. Не список, а панель из четырёх уровней, от самого частого к редкому:
 *  1. разделы браузера (закладки, история, загрузки, настройки): у каждого своя фигура и цвет;
 *  2. переключатели («Версия для ПК», «Блокировка рекламы»): плитки, которые меняют форму и цвет, меню при этом не закрывается;
 *  3. действия страницы: список (закладка «закрашивается» звёздой, остальное по нажатию);
 *  4. две крупные кнопки: новая вкладка и приватная.
 * Плитки появляются каскадом, при нажатии «проседают» и меняют скругление. Действия, которых на этой странице
 * быть не может (перевод на стартовой, например), не серые, а скрыты. «Добавить в…» открывается на месте.
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
    onScanQr: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val store = browser.store
    val onPage = !tab.home
    val hasSite = tab.siteHost() != null
    var sub by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) sub = false }

    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(32.dp),
        containerColor = cs.surfaceContainer,
    ) {
        AnimatedContent(
            targetState = sub,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(slideSpec) { it / 5 } + fadeIn(fadeInSpec)) togetherWith
                        (slideOutHorizontally(slideSpec) { -it / 5 } + fadeOut(fadeOutSpec))
                } else {
                    (slideInHorizontally(slideSpec) { -it / 5 } + fadeIn(fadeInSpec)) togetherWith
                        (slideOutHorizontally(slideSpec) { it / 5 } + fadeOut(fadeOutSpec))
                }
            },
            label = "tools-menu",
        ) { isSub ->
            Column(
                Modifier.width(PANEL_WIDTH).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isSub) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { sub = false }) { Icon(HripsIcons.Back, "Назад") }
                        Text("Добавить в…", style = MaterialTheme.typography.titleMedium)
                    }
                    WideRow(HripsIcons.Home, "На начальную страницу", "Плитка среди ваших сайтов", Badge.COOKIE6, 0) {
                        onDismiss(); PageActions.addToStartPage(context, store, tab)
                    }
                    if (hasSite && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                        WideRow(HripsIcons.Grid, "На главный экран", "Ярлык рядом с приложениями", Badge.CLOVER, 1) {
                            onDismiss(); PageActions.addToHomeScreen(context, tab)
                        }
                    }
                } else {
                    // 1. Разделы: фигуры разной формы и цвета, чтобы их узнавали не читая
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionTile(HripsIcons.Star, "Закладки", Badge.COOKIE9, cs.primaryContainer, cs.onPrimaryContainer, 0, Modifier.weight(1f)) { onDismiss(); onLibrary(0) }
                        SectionTile(HripsIcons.History, "История", Badge.CLOVER, cs.secondaryContainer, cs.onSecondaryContainer, 1, Modifier.weight(1f)) { onDismiss(); onLibrary(1) }
                        SectionTile(HripsIcons.Download, "Загрузки", Badge.SUNNY, cs.tertiaryContainer, cs.onTertiaryContainer, 2, Modifier.weight(1f)) { onDismiss(); onDownloads() }
                        SectionTile(HripsIcons.Settings, "Настройки", Badge.FLOWER, cs.surfaceVariant, cs.onSurfaceVariant, 3, Modifier.weight(1f)) { onDismiss(); onSettings() }
                    }

                    // 2. Переключатели: остаются открытыми, видно, как плитка меняет форму и цвет
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hasSite) {
                            ToggleTile(
                                HripsIcons.Desktop, "Версия для ПК", tab.desktopMode, true, 4, Modifier.weight(1f),
                            ) {
                                val next = !tab.desktopMode
                                haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                tab.setDesktop(next)
                            }
                        }
                        ToggleTile(
                            HripsIcons.Block, "Блокировка рекламы", browser.adBlock.enabled, browser.adBlock.extension != null, 5, Modifier.weight(1f),
                        ) {
                            val next = !browser.adBlock.enabled
                            haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            browser.adBlock.setBlocking(next)
                            tab.reload()
                        }
                    }

                    // 3. Действия страницы. Недоступные не показываем
                    val saved = onPage && store.isBookmarked(tab.url)
                    val actions = buildList {
                        if (onPage) {
                            add(MenuAction(if (saved) HripsIcons.StarFilled else HripsIcons.Star, if (saved) "В закладках" else "В закладки", checked = saved) {
                                onDismiss(); toggleBookmarkWithNotice(store, tab.url, tab.title)
                            })
                            add(MenuAction(HripsIcons.Share, "Поделиться") { onDismiss(); PageActions.share(context, tab) })
                            add(MenuAction(HripsIcons.Search, "Найти на странице") { onDismiss(); onFind() })
                            add(MenuAction(HripsIcons.Qr, "QR этой страницы") { onDismiss(); showQr = true })
                            if (hasSite && !PageActions.isTranslated(tab)) {
                                add(MenuAction(HripsIcons.Translate, "Перевести") { onDismiss(); PageActions.translate(tab) })
                            }
                            if (hasSite) add(MenuAction(HripsIcons.Open, "В приложении") { onDismiss(); PageActions.openInApp(context, tab.url) })
                            add(MenuAction(HripsIcons.FilePdf, "Сохранить PDF") { onDismiss(); PageActions.savePdf(tab, browser.downloads) })
                            add(MenuAction(HripsIcons.Capture, "Снимок") { onDismiss(); onScreenshot() })
                            add(MenuAction(HripsIcons.PlusCircle, "Добавить в…") { sub = true })
                        }
                        add(MenuAction(HripsIcons.Qr, "Сканер QR") { onDismiss(); onScanQr() })
                        add(MenuAction(HripsIcons.Fullscreen, "На весь экран") { onDismiss(); onFullscreen() })
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        actions.forEachIndexed { i, a ->
                            ActionRow(a.icon, a.label, a.checked, 6 + i, a.onClick)
                        }
                    }

                    // 4. Новая вкладка / приватная
                    val firstBottom = 6 + actions.size + 2
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            shapes = ButtonDefaults.shapes(),
                            onClick = { onDismiss(); browser.newTab(incognito = tab.isPrivate) },
                            modifier = Modifier.weight(1f).height(52.dp).staggerIn(firstBottom),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(HripsIcons.Add, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Вкладка", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledTonalButton(
                            shapes = ButtonDefaults.shapes(),
                            onClick = { onDismiss(); browser.newTab(incognito = true) },
                            modifier = Modifier.weight(1f).height(52.dp).staggerIn(firstBottom + 1),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(HripsIcons.Mask, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Приватная", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (browser.privateCount > 0) {
                        TextButton(
                            shapes = ButtonDefaults.shapes(),
                            onClick = { onDismiss(); browser.closePrivateTabs() },
                            modifier = Modifier.fillMaxWidth().staggerIn(firstBottom + 2),
                            colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
                        ) {
                            Icon(HripsIcons.Trash, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Закрыть приватные вкладки (${browser.privateCount})")
                        }
                    }
                }
            }
        }
    }

    if (showQr) QrDialog(tab.url) { showQr = false }
}

private val PANEL_WIDTH = 320.dp

private class MenuAction(
    val icon: ImageVector,
    val label: String,
    val checked: Boolean = false,
    val onClick: () -> Unit,
)

/** Каскадное появление: плитка проявляется и «вырастает» с небольшой задержкой по номеру. */
@Composable
private fun Modifier.staggerIn(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val rise = with(LocalDensity.current) { 14.dp.toPx() }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(14) * 22L)
        progress.animateTo(1f, spec)
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        val s = 0.86f + 0.14f * p
        scaleX = s
        scaleY = s
        translationY = (1f - p) * rise
    }
}

/** Раздел браузера: большая фигура со значком и подпись. При нажатии проседает. */
@Composable
private fun SectionTile(
    icon: ImageVector,
    label: String,
    badge: Badge,
    container: Color,
    content: Color,
    index: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, MaterialTheme.motionScheme.fastSpatialSpec<Float>(), label = "sectionPress")
    Surface(
        onClick = onClick,
        interactionSource = source,
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        modifier = modifier.staggerIn(index),
    ) {
        Column(
            Modifier.padding(vertical = 6.dp).graphicsLayer { scaleX = scale; scaleY = scale },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShapeBadge(icon, 52.dp, badge = badge, container = container, content = content)
            Text(
                label, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Переключатель-плитка: включённая заливается цветом и скругляется сильнее, значок уходит в круг. */
@Composable
private fun ToggleTile(
    icon: ImageVector,
    title: String,
    on: Boolean,
    enabled: Boolean,
    index: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 12.dp else if (on) 32.dp else 20.dp, motion.fastSpatialSpec<androidx.compose.ui.unit.Dp>(), label = "toggleCorner")
    val bg by animateColorAsState(if (on) cs.primaryContainer else cs.surfaceContainerHigh, motion.defaultEffectsSpec<Color>(), label = "toggleBg")
    val dot by animateColorAsState(if (on) cs.primary else cs.surfaceContainerHighest, motion.defaultEffectsSpec<Color>(), label = "toggleDot")
    val dotIcon by animateColorAsState(if (on) cs.onPrimary else cs.onSurfaceVariant, motion.defaultEffectsSpec<Color>(), label = "toggleDotIcon")
    val fg = if (on) cs.onPrimaryContainer else cs.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        shape = RoundedCornerShape(corner),
        color = bg,
        modifier = modifier.height(72.dp).alpha(if (enabled) 1f else 0.4f).staggerIn(index),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(38.dp).background(dot, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = dotIcon)
            }
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, color = fg)
                Text(
                    if (on) "Включено" else "Выключено",
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** Строка списка действий. [checked] подкрашивает её (закладка добавлена). */
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    index: Int,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 10.dp else 18.dp, motion.fastSpatialSpec<androidx.compose.ui.unit.Dp>(), label = "rowCorner")
    val bg by animateColorAsState(if (checked) cs.primaryContainer else Color.Transparent, motion.defaultEffectsSpec<Color>(), label = "rowBg")
    val fg by animateColorAsState(if (checked) cs.onPrimaryContainer else cs.onSurfaceVariant, motion.defaultEffectsSpec<Color>(), label = "rowFg")
    Surface(
        onClick = onClick,
        interactionSource = source,
        shape = RoundedCornerShape(corner),
        color = bg,
        modifier = Modifier.fillMaxWidth().staggerIn(index),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = fg)
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
        }
    }
}

/** Широкая строка с фигурой слева (подменю «Добавить в…»). */
@Composable
private fun WideRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: Badge,
    index: Int,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().staggerIn(index),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShapeBadge(icon, 44.dp, badge = badge, container = cs.secondaryContainer, content = cs.onSecondaryContainer)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
