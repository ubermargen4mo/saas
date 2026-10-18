@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.util.lerp as lerpFloat

internal class BarPalette(
    val container: Color,
    val content: Color,
    val edge: Color,
)

@Composable
private fun rememberBarPalette(): BarPalette {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(cs.surfaceContainerHigh, motion.defaultEffectsSpec<Color>(), label = "barContainer")
    val content by animateColorAsState(cs.onSurface, motion.defaultEffectsSpec<Color>(), label = "barContent")
    return BarPalette(container, content, content.copy(alpha = 0.10f))
}

@Composable
private fun BarPill(
    palette: BarPalette,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = palette.container,
        contentColor = palette.content,
        shadowElevation = 6.dp,
        border = BorderStroke(0.5.dp, palette.edge),
        content = content,
    )
}

/**
 * Все анимации сворачивания панели идут через layout/draw: значение `t` читается внутри лямбд,
 * поэтому за кадр пересчитывается только измерение и рисование, а не композиция.
 */
private fun Modifier.collapseWidth(t: () -> Float): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val keep = (1f - t()).coerceIn(0f, 1f)
        val w = (placeable.width * keep).roundToInt()
        val h = (placeable.height * keep).roundToInt()
        layout(w, h) { placeable.placeRelative((w - placeable.width) / 2, (h - placeable.height) / 2) }
    }
    .graphicsLayer {
        val keep = (1f - t()).coerceIn(0f, 1f)
        alpha = keep
        val s = 0.7f + 0.3f * keep
        scaleX = s
        scaleY = s
    }

private fun Modifier.shrinkWidth(t: () -> Float): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0))
        val keep = (1f - t()).coerceIn(0f, 1f)
        val w = (placeable.width * keep).roundToInt()
        layout(w, placeable.height) { placeable.placeRelative((w - placeable.width) / 2, 0) }
    }
    .graphicsLayer { alpha = (1f - t()).coerceIn(0f, 1f) }

private fun Modifier.barInset(t: () -> Float, from: Dp, to: Dp): Modifier = layout { measurable, constraints ->
    val inset = lerpFloat(from.toPx(), to.toPx(), t().coerceIn(0f, 1f)).roundToInt()
    val w = (constraints.maxWidth - inset * 2).coerceAtLeast(0)
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(inset, 0) }
}

private fun Modifier.barHeight(t: () -> Float): Modifier = layout { measurable, constraints ->
    val h = lerpFloat(HripsLayout.BarHeight.toPx(), HripsLayout.BarCompactHeight.toPx(), t().coerceIn(0f, 1f))
        .roundToInt()
        .coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

private data class BarLabel(val text: String, val tab: Int)

private const val MAX_DOTS = 8

private fun DrawScope.drawTabDots(count: Int, index: Float, color: Color, alpha: Float) {
    if (alpha <= 0.01f || count < 2 || count > MAX_DOTS) return
    val dot = 3.dp.toPx()
    val wide = 10.dp.toPx()
    val gap = 4.dp.toPx()
    var total = gap * (count - 1)
    for (i in 0 until count) total += lerpFloat(dot, wide, (1f - abs(i - index)).coerceIn(0f, 1f))
    var x = (size.width - total) / 2f
    val y = size.height - 6.dp.toPx() - dot / 2f
    for (i in 0 until count) {
        val k = (1f - abs(i - index)).coerceIn(0f, 1f)
        val w = lerpFloat(dot, wide, k)
        drawRoundRect(
            color = color.copy(alpha = (0.26f + 0.5f * k) * alpha),
            topLeft = Offset(x, y),
            size = Size(w, dot),
            cornerRadius = CornerRadius(dot / 2f),
        )
        x += w + gap
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BottomBar(
    tab: Tab,
    shown: String,
    collapsed: Boolean,
    backEnabled: Boolean,
    tabCount: Int,
    tabIndex: Int,
    engine: SearchEngine,
    onPickEngine: (SearchEngine) -> Unit,
    onSearchImage: () -> Unit,
    onScanQr: () -> Unit,
    downloads: Downloads,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onAddress: () -> Unit,
    onMore: () -> Unit,
    onDownloads: () -> Unit,
    onExpand: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeSide: (Int) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onPasteGo: () -> Unit,
    onCloseTab: () -> Unit,
    onCloseAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val palette = rememberBarPalette()

    // Размеры панели меняются без перелёта: пружина с критическим затуханием, иначе панель «дышит».
    val calm = remember { spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow) }
    val collapseState = animateFloatAsState(
        if (collapsed) 1f else 0f,
        calm,
        label = "barCollapse",
    )
    val t: () -> Float = remember(collapseState) { { collapseState.value.coerceIn(0f, 1f) } }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) }
    val rise = with(density) { 36.dp.toPx() }

    val progressSpec = motion.defaultEffectsSpec<Float>()
    val progressState = remember { Animatable(0f) }
    LaunchedEffect(tab) {
        snapshotFlow { tab.progress / 100f }.collectLatest { progressState.animateTo(it, progressSpec) }
    }
    var fillOn by remember { mutableStateOf(false) }
    LaunchedEffect(tab.loading, tab.home) {
        if (tab.loading && !tab.home) {
            fillOn = true
        } else if (fillOn) {
            delay(450)
            fillOn = false
        }
    }
    val fillAlpha = animateFloatAsState(if (fillOn) 1f else 0f, motion.defaultEffectsSpec<Float>(), label = "barFillAlpha")


    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressScale = animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium), label = "barPress")
    val dotIndex = animateFloatAsState(tabIndex.toFloat(), calm, label = "barDots")

    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val pullMax = with(density) { 10.dp.toPx() }
    val swipeSide = with(density) { 72.dp.toPx() }
    val swipeUp = with(density) { 36.dp.toPx() }
    var showSecurity by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val canForward = tab.canGoForward && !tab.home
    // На главной «назад/вперёд» не нужны: прячем пару, поиску достаётся вся ширина.
    val homeHide = animateFloatAsState(if (tab.home) 1f else 0f, calm, label = "barHomeHide")
    val leftT: () -> Float = remember(collapseState, homeHide) { { maxOf(collapseState.value, homeHide.value).coerceIn(0f, 1f) } }

    Row(
        modifier
            .fillMaxWidth()
            .barInset(t, HripsLayout.BarMargin, 112.dp)
            .graphicsLayer {
                alpha = appear.value.coerceIn(0f, 1f)
                translationY = (1f - appear.value) * rise
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.collapseWidth(leftT)) {
            BarPill(palette) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarButton(onClick = onBack, enabled = backEnabled, modifier = Modifier.size(width = 34.dp, height = 40.dp)) {
                        Icon(HripsIcons.BarChevronBack, "Назад", Modifier.size(26.dp))
                    }
                    // «Вперёд» появляется только когда есть куда идти: адресу достаётся больше места.
                    AnimatedVisibility(
                        visible = canForward,
                        enter = fadeIn(motion.defaultEffectsSpec<Float>()) +
                            expandHorizontally(motion.defaultSpatialSpec<IntSize>()) +
                            scaleIn(motion.fastSpatialSpec<Float>(), initialScale = 0.6f),
                        exit = fadeOut(motion.fastEffectsSpec<Float>()) +
                            shrinkHorizontally(motion.fastSpatialSpec<IntSize>()) +
                            scaleOut(motion.fastSpatialSpec<Float>(), targetScale = 0.6f),
                    ) {
                        BarButton(onClick = onForward, modifier = Modifier.size(width = 34.dp, height = 40.dp)) {
                            Icon(HripsIcons.BarChevronForward, "Вперёд", Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
        Box(Modifier.collapseWidth(leftT).width(8.dp))

        BarPill(
            palette,
            Modifier
                .weight(1f)
                .barHeight(t)
                .originAnchor("tabs")
                .originAnchor("search")
                .graphicsLayer {
                    val s = pressScale.value
                    scaleX = s
                    scaleY = s
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawTabDots(tabCount, dotIndex.value, palette.content, 1f - t()) }
                    .pointerInput(collapsed) {
                        var dx = 0f
                        var dy = 0f
                        val settle: () -> Unit = {
                            scope.launch {
                                Animatable(dragX).animateTo(0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) { dragX = value }
                            }
                            scope.launch {
                                Animatable(dragY).animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) { dragY = value }
                            }
                        }
                        detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dx += amount.x
                                dy += amount.y
                                if (abs(dx) > abs(dy)) dragX = (dx * 0.5f).coerceIn(-swipeSide, swipeSide)
                                else dragY = (dy * 0.3f).coerceIn(-pullMax, pullMax)
                            },
                            onDragEnd = {
                                if (abs(dy) > abs(dx) && dy < -swipeUp) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipeUp()
                                } else if (abs(dy) > abs(dx) && dy > swipeUp) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipeDown()
                                } else if (abs(dx) > abs(dy) && abs(dx) > swipeSide) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipeSide(if (dx < 0) 1 else -1)
                                }
                                settle()
                            },
                            onDragCancel = { settle() },
                        )
                    }
                    .combinedClickable(
                        interactionSource = source,
                        indication = null,
                        onClickLabel = if (collapsed) "Показать панель" else "Ввести запрос или адрес",
                        onLongClickLabel = "Действия со ссылкой",
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        },
                        onClick = { if (collapsed) onExpand() else onAddress() },
                    ),
            ) {
                if (fillOn || fillAlpha.value > 0.01f) {
                    LoadFill(progressState.asState(), fillAlpha, tab.loading, palette.content)
                }
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = dragX; translationY = dragY }) {
                Crossfade(targetState = tab.home, animationSpec = tween(160), label = "barMode") { home ->
                    if (home) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.padding(start = 6.dp)) { EnginePicker(engine, onPickEngine) }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                Text(
                                    "Поиск",
                                    Modifier.padding(start = 12.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = cs.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                )
                            }
                            BarButton(onClick = onSearchImage, modifier = Modifier.size(40.dp)) {
                                Icon(HripsIcons.Image, "Поиск по фото", Modifier.size(22.dp))
                            }
                            BarButton(onClick = onScanQr, modifier = Modifier.size(40.dp)) {
                                Icon(HripsIcons.Qr, "Сканировать QR-код", Modifier.size(22.dp))
                            }
                            Box(Modifier.width(4.dp))
                        }
                    } else {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.shrinkWidth(t).width(40.dp).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            BarButton(
                                onClick = { if (tab.home) onAddress() else showSecurity = true },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    when {
                                        tab.home -> HripsIcons.Search
                                        tab.trust == Trust.SECURE -> HripsIcons.BarShield
                                        else -> HripsIcons.BarInfo
                                    },
                                    if (tab.home) "Поиск" else "Безопасность соединения",
                                    Modifier.size(22.dp),
                                    tint = if (!tab.home && tab.trust == Trust.WARNING) cs.error else LocalContentColor.current,
                                )
                            }
                        }

                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            AnimatedContent(
                                targetState = BarLabel(shown, tabIndex),
                                transitionSpec = {
                                    val d = targetState.tab - initialState.tab
                                    if (d != 0) {
                                        // Смена вкладки: подпись уезжает в сторону свайпа.
                                        val dir = if (d > 0) 1 else -1
                                        (fadeIn(tween(200, delayMillis = 40)) +
                                            slideInHorizontally(tween(260, easing = HripsMotion.ChromeEasing)) { it / 3 * dir }) togetherWith
                                            (fadeOut(tween(100)) +
                                                slideOutHorizontally(tween(200, easing = HripsMotion.ChromeEasing)) { -it / 3 * dir }) using
                                            SizeTransform(clip = false) { _, _ -> snap() }
                                    } else {
                                        (fadeIn(tween(180, delayMillis = 60)) + slideInVertically(tween(220)) { it / 3 }) togetherWith
                                            (fadeOut(tween(90)) + slideOutVertically(tween(160)) { -it / 3 }) using
                                            SizeTransform(clip = false) { _, _ -> snap() }
                                    }
                                },
                                contentAlignment = Alignment.Center,
                                label = "barText",
                            ) { label ->
                                Text(
                                    label.text.ifEmpty { "Поиск" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                )
                            }
                        }

                        Box(
                            Modifier.shrinkWidth(t).width(40.dp).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!tab.home) {
                                BarButton(onClick = { tab.reloadOrStop() }, modifier = Modifier.size(36.dp)) {
                                    AnimatedContent(
                                        targetState = tab.loading,
                                        transitionSpec = {
                                            (fadeIn(motion.defaultEffectsSpec<Float>()) + scaleIn(motion.fastSpatialSpec<Float>(), initialScale = 0.6f)) togetherWith
                                                (fadeOut(motion.fastEffectsSpec<Float>()) + scaleOut(motion.fastSpatialSpec<Float>(), targetScale = 0.6f)) using
                                                SizeTransform(clip = false) { _, _ -> snap() }
                                        },
                                        contentAlignment = Alignment.Center,
                                        label = "barReload",
                                    ) { loading ->
                                        if (loading) LoadRing(tab.progress / 100f, LocalContentColor.current)
                                        else Icon(HripsIcons.BarRefresh, "Обновить", Modifier.size(22.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                }
                }
                PillMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    hasPage = !tab.home,
                    onCopy = onCopy,
                    onShare = onShare,
                    onPasteGo = onPasteGo,
                    onCloseTab = onCloseTab,
                    onCloseAll = onCloseAll,
                )
            }
        }

        Box(Modifier.collapseWidth(t).width(8.dp))
        Box(Modifier.collapseWidth(t)) {
            BarPill(palette) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedDownloadsSlot(downloads, onClick = onDownloads, size = 40.dp, centered = true)
                    BarButton(onClick = onMore, modifier = Modifier.size(40.dp)) {
                        Icon(HripsIcons.BarMore, "Меню", Modifier.size(22.dp))
                    }
                }
            }
        }
    }

    if (showSecurity) SecurityDialog(tab) { showSecurity = false }
}

/** Меню по удержанию пилюли: действия с адресом и вкладками. Только телефон. */
@Composable
private fun PillMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    hasPage: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onPasteGo: () -> Unit,
    onCloseTab: () -> Unit,
    onCloseAll: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = cs.surfaceContainerHigh,
    ) {
        val run: (() -> Unit) -> () -> Unit = { action -> { onDismiss(); action() } }
        if (hasPage) {
            DropdownMenuItem(
                text = { Text("Скопировать") },
                leadingIcon = { Icon(HripsIcons.Copy, null) },
                onClick = run(onCopy),
            )
            DropdownMenuItem(
                text = { Text("Поделиться") },
                leadingIcon = { Icon(HripsIcons.Share, null) },
                onClick = run(onShare),
            )
        }
        DropdownMenuItem(
            text = { Text("Вставить и перейти") },
            leadingIcon = { Icon(HripsIcons.Link, null) },
            onClick = run(onPasteGo),
        )
        DropdownMenuItem(
            text = { Text("Закрыть вкладку") },
            leadingIcon = { Icon(HripsIcons.Close, null) },
            onClick = run(onCloseTab),
        )
        DropdownMenuItem(
            text = { Text("Закрыть все вкладки", color = cs.error) },
            leadingIcon = { Icon(HripsIcons.Trash, null, tint = cs.error) },
            onClick = run(onCloseAll),
        )
    }
}

/**
 * Заливка прогресса и бегущий блик. Существует в композиции только пока идёт загрузка, поэтому
 * бесконечная анимация блика не тратит кадры, когда страница уже загружена.
 */
@Composable
private fun LoadFill(
    progress: State<Float>,
    alpha: State<Float>,
    loading: Boolean,
    ink: Color,
) {
    val sweep = rememberInfiniteTransition(label = "barSweep").animateFloat(
        initialValue = -0.4f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)),
        label = "barSweepX",
    )
    Box(
        Modifier.fillMaxSize().drawBehind {
            val a = alpha.value
            if (a <= 0.01f) return@drawBehind
            val w = size.width * progress.value.coerceIn(0f, 1f)
            drawRect(ink.copy(alpha = 0.13f * a), size = Size(w, size.height))
            val edge = min(48.dp.toPx(), w)
            if (edge > 1f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, ink.copy(alpha = 0.16f * a)),
                        startX = w - edge,
                        endX = w,
                    ),
                    topLeft = Offset(w - edge, 0f),
                    size = Size(edge, size.height),
                )
            }
            if (loading) {
                val band = size.width * 0.35f
                val x = sweep.value * size.width
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, ink.copy(alpha = 0.10f * a), Color.Transparent),
                        startX = x,
                        endX = x + band,
                    ),
                    topLeft = Offset(x, 0f),
                    size = Size(band, size.height),
                )
            }
        },
    )
}

@Composable
private fun LoadRing(progress: Float, color: Color) {
    val shown by animateFloatAsState(progress, MaterialTheme.motionScheme.defaultEffectsSpec<Float>(), label = "ring")
    val spin = rememberInfiniteTransition(label = "ringSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "ringAngle",
    )
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2f
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = color.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arc,
                style = Stroke(stroke),
            )
            val indeterminate = shown < 0.04f
            drawArc(
                color = color,
                startAngle = if (indeterminate) spin.value else -90f,
                sweepAngle = if (indeterminate) 90f else 360f * shown.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arc,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Icon(HripsIcons.BarClose, "Остановить", Modifier.size(13.dp))
    }
}
