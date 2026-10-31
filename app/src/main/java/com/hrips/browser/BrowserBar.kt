@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class BarPalette(
    val container: Color,
    val content: Color,
    val edge: Color,
)

@Composable
internal fun rememberBarPalette(): BarPalette {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(cs.surfaceContainerHigh, motion.defaultEffectsSpec<Color>(), label = "barContainer")
    val content by animateColorAsState(cs.onSurface, motion.defaultEffectsSpec<Color>(), label = "barContent")
    return BarPalette(container, content, content.copy(alpha = 0.10f))
}

@Composable
internal fun BarPill(
    palette: BarPalette,
    modifier: Modifier = Modifier,
    // Пилюля с кнопками сжимается целиком при нажатии кнопки; адресная строка управляет нажатием сама.
    pressHost: Boolean = true,
    content: @Composable () -> Unit,
) {
    val host = remember { PillPress() }
    Surface(
        modifier = if (pressHost) {
            modifier.graphicsLayer {
                val s = host.scale.value
                scaleX = s
                scaleY = s
            }
        } else {
            modifier
        },
        shape = CircleShape,
        color = palette.container,
        contentColor = palette.content,
        shadowElevation = 6.dp,
        border = BorderStroke(0.5.dp, palette.edge),
    ) {
        CompositionLocalProvider(LocalPillPress provides if (pressHost) host else null, content = content)
    }
}

/** Анимации сворачивания панели идут через layout/draw: `t` читается внутри лямбд, поэтому композиция за кадр не нужна. */
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

/** Ширина панели плавно меняется от «на всю ширину с полями» до ширины свёрнутой пилюли (ровно по тексту домена). */
private fun Modifier.barWidth(t: () -> Float, margin: Dp, compactPx: () -> Float): Modifier = layout { measurable, constraints ->
    val full = (constraints.maxWidth - margin.roundToPx() * 2).coerceAtLeast(0)
    val compact = compactPx().roundToInt().coerceIn(0, full)
    val w = lerpFloat(full.toFloat(), compact.toFloat(), t().coerceIn(0f, 1f)).roundToInt()
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative((constraints.maxWidth - w) / 2, 0) }
}

internal val NoSizeAnimation = SizeTransform(clip = false) { _, _ -> snap() }

/** Смена вкладки: подпись уезжает в сторону смаха ([dir] = +1 следующая вкладка, -1 предыдущая). */
internal fun AnimatedContentTransitionScope<*>.tabSlide(dir: Int): ContentTransform =
    (fadeIn(tween(200, delayMillis = 40)) +
        slideInHorizontally(tween(260, easing = HripsMotion.ChromeEasing)) { it / 3 * dir }) togetherWith
        (fadeOut(tween(100)) +
            slideOutHorizontally(tween(200, easing = HripsMotion.ChromeEasing)) { -it / 3 * dir }) using
        NoSizeAnimation

/** Запускает пружину от [from] к нулю, отдавая значения в [set]. */
internal fun CoroutineScope.springBack(from: Float, spec: SpringSpec<Float>, set: (Float) -> Unit) {
    launch { Animatable(from).animateTo(0f, spec) { set(value) } }
}

/** Заливка загрузки адресной строки: плавный прогресс и затухание, которое держится чуть дольше самой загрузки. */
internal class LoadFillState(
    val progress: Animatable<Float, AnimationVector1D>,
    val alpha: State<Float>,
    private val on: State<Boolean>,
) {
    val visible: Boolean get() = on.value || alpha.value > 0.01f
}

@Composable
internal fun rememberLoadFill(tab: Tab): LoadFillState {
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(tab) {
        snapshotFlow { if (tab.home) 0f else if (tab.loading) tab.progress / 100f else 1f }.collectLatest { v ->
            // Новая загрузка начинается с нуля сразу, без обратного «отъезда» заливки от прошлой страницы.
            if (v < progress.value && tab.loading) progress.snapTo(v) else progress.animateTo(v, spec)
        }
    }
    val on = remember { mutableStateOf(false) }
    LaunchedEffect(tab.loading, tab.home) {
        if (tab.loading && !tab.home) {
            on.value = true
        } else if (on.value) {
            delay(450)
            on.value = false
        }
    }
    val alpha = animateFloatAsState(if (on.value) 1f else 0f, spec, label = "barFillAlpha")
    return remember(progress, alpha, on) { LoadFillState(progress, alpha, on) }
}

/** Кнопка «обновить», во время загрузки превращается в кольцо прогресса с крестиком. */
@Composable
internal fun ReloadGlyph(tab: Tab) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = tab.loading,
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec<Float>()) + scaleIn(motion.fastSpatialSpec<Float>(), initialScale = 0.6f)) togetherWith
                (fadeOut(motion.fastEffectsSpec<Float>()) + scaleOut(motion.fastSpatialSpec<Float>(), targetScale = 0.6f)) using
                NoSizeAnimation
        },
        contentAlignment = Alignment.Center,
        label = "barReload",
    ) { loading ->
        if (loading) LoadRing(tab.progress / 100f, LocalContentColor.current)
        else Icon(HripsIcons.Refresh, "Обновить", Modifier.size(24.dp))
    }
}

/** Значок вкладки в списках: звук, затем маска (приватная), «домой» или значок сайта. */
@Composable
internal fun TabGlyph(t: Tab, size: Dp, siteSize: Dp = size, audioSize: Dp = size, audioGap: Dp = 6.dp) {
    val cs = MaterialTheme.colorScheme
    if (t.audible) {
        Icon(HripsIcons.Volume, "Играет звук", Modifier.size(audioSize), tint = cs.primary)
        Spacer(Modifier.width(audioGap))
    }
    when {
        t.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(size), tint = cs.primary)
        t.home -> Icon(HripsIcons.Home, null, Modifier.size(size), tint = cs.onSurfaceVariant)
        else -> TabSiteIcon(t, siteSize, shape = CircleShape)
    }
}

/** Каждая пилюля появляется со своей задержкой и лёгким перелётом: панель «собирается», а не выезжает куском. */
@Composable
private fun rememberAppear(delayMs: Int): Animatable<Float, AnimationVector1D> {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        a.animateTo(1f, HripsMotion.barAppear())
    }
    return a
}

private fun Modifier.appearLayer(a: Animatable<Float, AnimationVector1D>, rise: Float): Modifier = graphicsLayer {
    val v = a.value
    alpha = (v * 1.4f).coerceIn(0f, 1f)
    translationY = (1f - v) * rise
    val s = 0.9f + 0.1f * v
    scaleX = s
    scaleY = s
}

private fun Modifier.barHeight(t: () -> Float): Modifier = layout { measurable, constraints ->
    val h = lerpFloat(HripsLayout.BarHeight.toPx(), HripsLayout.BarCompactHeight.toPx(), t().coerceIn(0f, 1f))
        .roundToInt()
        .coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

private data class BarLabel(val text: String, val tab: Int, val compact: Boolean)

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
    browser: Browser,
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
    onPageOptions: () -> Unit,
    onMore: () -> Unit,
    onDownloads: () -> Unit,
    onExpand: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeSide: (Int) -> Unit,
    quickTabs: () -> List<Tab>,
    onPickTab: (Tab) -> Unit,
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

    // Размеры панели меняются без перелёта (критическое затухание, иначе панель «дышит»); та же пружина двигает страницу в BrowserScreen.
    val calm = remember { spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow) }
    val collapseState = animateFloatAsState(
        if (collapsed) 1f else 0f,
        HripsMotion.barCollapse(),
        label = "barCollapse",
    )
    val t: () -> Float = remember(collapseState) { { collapseState.value.coerceIn(0f, 1f) } }

    val rise = with(density) { 36.dp.toPx() }
    val appearLeft = rememberAppear(0)
    val appearMid = rememberAppear(50)
    val appearRight = rememberAppear(100)

    val bounce = remember { Animatable(0f) }
    val bounceKick = with(density) { -180.dp.toPx() }

    // Свёрнутая панель показывает только домен; ширину считаем по тексту, чтобы пилюля обнимала его без запаса.
    val domain = remember(tab.url, tab.home, shown) { tab.siteHost() ?: plainHost(tab.url) ?: shown }
    val shownText = if (collapsed) domain else shown
    val compactStyle = MaterialTheme.typography.labelLarge.copy(
        fontSize = HripsLayout.BarCompactTextSp.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val measurer = rememberTextMeasurer()
    val compactWidthPx = remember(domain, compactStyle, density) {
        measurer.measure(domain, compactStyle, maxLines = 1, softWrap = false).size.width +
            with(density) { HripsLayout.BarCompactPadding.toPx() } * 2f
    }

    val fill = rememberLoadFill(tab)

    // Строка подпрыгивает, когда заливка загрузки дошла до конца (а не когда Gecko сказал «стоп»), затем заливка гаснет.
    LaunchedEffect(tab) {
        var was = tab.loading && !tab.home
        snapshotFlow { tab.loading && !tab.home }.collect { now ->
            if (was && !now) {
                withTimeoutOrNull(700) { snapshotFlow { fill.progress.value }.first { it >= 0.985f } }
                bounce.snapTo(0f)
                bounce.animateTo(0f, HripsMotion.barBounce(), initialVelocity = bounceKick)
            }
            was = now
        }
    }

    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressScale = animateFloatAsState(
        if (pressed) 0.96f else 1f,
        if (pressed) HripsMotion.pressDown() else HripsMotion.pressRelease(),
        label = "barPress",
    )
    val dotIndex = animateFloatAsState(tabIndex.toFloat(), calm, label = "barDots")

    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    // Свайп вверх тянет пилюлю за пальцем (lift ≤ 0) и вытягивает её по высоте (swipeP от 0 до 1 к порогу).
    var lift by remember { mutableFloatStateOf(0f) }
    var swipeP by remember { mutableFloatStateOf(0f) }
    val liftMax = with(density) { 30.dp.toPx() }
    val launchLift = with(density) { 44.dp.toPx() }
    val pullMax = with(density) { 10.dp.toPx() }
    val swipeSide = with(density) { 72.dp.toPx() }
    val swipeUp = with(density) { 36.dp.toPx() }
    // Свайп вниз срабатывает сразу, не дожидаясь отпускания: у нижнего края системный жест «домой» отменяет касание.
    val swipeDown = with(density) { 20.dp.toPx() }
    var menuOpen by remember { mutableStateOf(false) }
    var tabsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(collapsed, tab.home) { if (collapsed || tab.home) tabsOpen = false }
    val canForward = tab.canGoForward && !tab.home
    // На главной «назад» и «вперёд» не нужны: поиску достаётся вся ширина.
    val homeHide = animateFloatAsState(if (tab.home) 1f else 0f, calm, label = "barHomeHide")
    val leftT: () -> Float = remember(collapseState, homeHide) { { maxOf(collapseState.value, homeHide.value).coerceIn(0f, 1f) } }

    Row(
        modifier
            .fillMaxWidth()
            .barWidth(t, HripsLayout.BarSideMargin) { compactWidthPx },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.collapseWidth(leftT)) {
            TabQuickList(
                visible = tabsOpen,
                onDismiss = { tabsOpen = false },
                tabs = quickTabs,
                current = tab,
                onPick = onPickTab,
            )
            BarPill(palette, Modifier.appearLayer(appearLeft, rise)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BarButton(
                        onClick = onBack,
                        enabled = backEnabled,
                        inert = collapsed,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            tabsOpen = true
                        },
                        modifier = Modifier.size(HripsLayout.BarHeight),
                    ) {
                        Icon(HripsIcons.ChevronBack.variant(2.4f), "Назад", Modifier.size(26.dp))
                    }
                    AnimatedVisibility(
                        visible = canForward,
                        enter = fadeIn(motion.defaultEffectsSpec<Float>()) +
                            expandHorizontally(motion.defaultSpatialSpec<IntSize>()) +
                            scaleIn(motion.fastSpatialSpec<Float>(), initialScale = 0.6f),
                        exit = fadeOut(motion.fastEffectsSpec<Float>()) +
                            shrinkHorizontally(motion.fastSpatialSpec<IntSize>()) +
                            scaleOut(motion.fastSpatialSpec<Float>(), targetScale = 0.6f),
                    ) {
                        BarButton(onClick = onForward, inert = collapsed, modifier = Modifier.size(HripsLayout.BarHeight)) {
                            Icon(HripsIcons.ChevronForward.variant(2.4f), "Вперёд", Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
        Box(Modifier.collapseWidth(leftT).width(8.dp))

        BarPill(
            palette,
            pressHost = false,
            modifier = Modifier
                .weight(1f)
                .barHeight(t)
                .originAnchor("tabs")
                .originAnchor("search")
                .graphicsLayer {
                    val s = pressScale.value
                    val p = swipeP
                    // Вытягивание при свайпе вверх: чуть выше и уже, как резинка.
                    scaleX = s * (1f - 0.03f * p)
                    scaleY = s * (1f + 0.10f * p)
                            translationY = bounce.value + lift
                }
                .appearLayer(appearMid, rise),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawTabDots(tabCount, dotIndex.value, palette.content, 1f - t()) }
                    .pointerInput(collapsed) {
                        var dx = 0f
                        var dy = 0f
                        var armed = false
                        var firedDown = false
                        val settle: () -> Unit = {
                            scope.springBack(dragX, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) { dragX = it }
                            scope.springBack(dragY, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) { dragY = it }
                            scope.springBack(lift, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) { lift = it }
                            scope.springBack(swipeP, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) { swipeP = it }
                        }
                        detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f; armed = false; firedDown = false },
                            onDrag = { change, amount ->
                                change.consume()
                                dx += amount.x
                                dy += amount.y
                                if (abs(dx) > abs(dy)) {
                                    dragX = (dx * 0.5f).coerceIn(-swipeSide, swipeSide)
                                    dragY = 0f
                                    lift = 0f
                                    swipeP = 0f
                                } else if (dy < 0f) {
                                    // Резиновая тяга вверх: чем дальше, тем туже.
                                    lift = -liftMax * (1f - kotlin.math.exp(-(-dy) / (liftMax * 1.6f)))
                                    swipeP = (-dy / swipeUp).coerceIn(0f, 1f)
                                    dragY = 0f
                                    dragX = 0f
                                    // Щелчок на пороге: отпускание откроет вкладки.
                                    val over = -dy >= swipeUp
                                    if (over && !armed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    armed = over
                                } else {
                                    dragY = (dy * 0.3f).coerceIn(0f, pullMax)
                                    lift = 0f
                                    swipeP = 0f
                                    dragX = 0f
                                    if (!firedDown && !collapsed && dy > swipeDown && dy > abs(dx) * 1.2f) {
                                        firedDown = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSwipeDown()
                                        // collapsed изменится, обработчик перезапустится без onDragEnd: капсулу возвращаем здесь.
                                        settle()
                                    }
                                }
                            },
                            onDragEnd = {
                                var launched = false
                                if (abs(dy) > abs(dx) && dy < -swipeUp) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipeUp()
                                    // «Взлёт»: пилюля улетает вверх за вкладками и пружинит назад.
                                    if (!collapsed) {
                                        launched = true
                                        scope.launch {
                                            Animatable(lift).animateTo(-launchLift, tween(120, easing = FastOutSlowInEasing)) { lift = value }
                                            Animatable(lift).animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) { lift = value }
                                        }
                                        scope.springBack(swipeP, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) { swipeP = it }
                                    }
                                } else if (!firedDown && !collapsed && abs(dy) > abs(dx) && dy > swipeDown) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipeDown()
                                } else if (abs(dx) > abs(dy) && abs(dx) > swipeSide) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipeSide(if (dx < 0) 1 else -1)
                                }
                                if (launched) {
                                    scope.springBack(dragX, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) { dragX = it }
                                    dragY = 0f
                                } else {
                                    settle()
                                }
                            },
                            onDragCancel = { settle() },
                        )
                    }
                    .secondaryClick { menuOpen = true }
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
                if (fill.visible) LoadFill(fill, tab.loading, palette.content)
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
                                        onClick = { if (tab.home) onAddress() else onPageOptions() },
                                        inert = collapsed,
                                        modifier = Modifier.size(36.dp),
                                    ) { PageMenuGlyph(tab) }
                                }

                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    AnimatedContent(
                                        targetState = BarLabel(shownText, tabIndex, collapsed),
                                        transitionSpec = {
                                            val d = targetState.tab - initialState.tab
                                            when {
                                                d != 0 -> tabSlide(if (d > 0) 1 else -1)
                                                targetState.compact != initialState.compact ->
                                                    // Сворачивание и разворачивание: адрес «стягивается» в домен и обратно.
                                                    (fadeIn(tween(220, delayMillis = 60)) +
                                                        scaleIn(tween(280, easing = HripsMotion.ChromeEasing), initialScale = 0.86f)) togetherWith
                                                        (fadeOut(tween(110)) +
                                                            scaleOut(tween(180, easing = HripsMotion.ChromeEasing), targetScale = 0.86f)) using
                                                        NoSizeAnimation
                                                else ->
                                                    (fadeIn(tween(180, delayMillis = 60)) + slideInVertically(tween(220)) { it / 3 }) togetherWith
                                                        (fadeOut(tween(90)) + slideOutVertically(tween(160)) { -it / 3 }) using
                                                        NoSizeAnimation
                                            }
                                        },
                                        contentAlignment = Alignment.Center,
                                        label = "barText",
                                    ) { label ->
                                        if (label.compact) {
                                            Text(
                                                label.text,
                                                Modifier.wrapContentWidth(unbounded = true),
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Clip,
                                                style = compactStyle,
                                            )
                                        } else {
                                            Text(
                                                label.text.ifEmpty { "Поиск" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = HripsLayout.BarTextSp.sp, fontWeight = FontWeight.Medium),
                                            )
                                        }
                                    }
                                }

                                val audioW by animateDpAsState(if (tab.audible && !tab.home) 66.dp else 40.dp, tween(200), label = "barAudioW")
                                Box(
                                    Modifier.shrinkWidth(t).width(audioW).fillMaxHeight(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (!tab.home) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (tab.audible) Icon(HripsIcons.Volume, "Во вкладке играет звук", Modifier.size(22.dp))
                                            BarButton(onClick = { tab.reloadOrStop() }, inert = collapsed, modifier = Modifier.size(36.dp)) {
                                                ReloadGlyph(tab)
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
                    browser = browser,
                    tab = tab,
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
            BarPill(palette, Modifier.appearLayer(appearRight, rise)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedDownloadsSlot(downloads, onClick = { if (!collapsed) onDownloads() }, size = HripsLayout.BarHeight, centered = true, barStyle = true)
                    BarButton(onClick = onMore, inert = collapsed, modifier = Modifier.size(HripsLayout.BarHeight)) {
                        Icon(HripsIcons.MoreVert, "Меню", Modifier.size(24.dp))
                    }
                }
            }
        }
    }

}

/** Список над кнопкой «назад» (телефон) или под ней (планшет), по её левому краю; запас [shadowPad] нужен, иначе окно обрежет тень. */
private class AboveStartPosition(
    private val gap: Int,
    private val margin: Int,
    private val shadowPad: Int,
    private val below: Boolean = false,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left - shadowPad).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        if (below) {
            val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
            return IntOffset(x, (anchorBounds.bottom + gap - shadowPad).coerceIn(margin, maxY))
        }
        val y = (anchorBounds.top - popupContentSize.height - gap + shadowPad).coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}

/**
 * Быстрый список вкладок по долгому нажатию «назад». Окно живёт на время анимации закрытия, поэтому видимость и
 * монтирование разведены. Список ленивый и без превью: открывается мгновенно даже со сотней вкладок.
 */
@Composable
internal fun TabQuickList(
    visible: Boolean,
    onDismiss: () -> Unit,
    tabs: () -> List<Tab>,
    current: Tab,
    onPick: (Tab) -> Unit,
    // Планшет: кнопка «назад» наверху, список раскрывается вниз.
    below: Boolean = false,
) {
    val progress = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 900f))
        } else if (mounted) {
            progress.animateTo(0f, tween(120, easing = FastOutSlowInEasing))
            mounted = false
        }
    }
    if (!mounted) return

    val density = LocalDensity.current
    val shadowPad = 12.dp
    val position = remember(density, below) {
        with(density) { AboveStartPosition(8.dp.roundToPx(), 12.dp.roundToPx(), shadowPad.roundToPx(), below) }
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val list = tabs()
        val currentIndex = list.indexOf(current).coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
        val cs = MaterialTheme.colorScheme
        Box(
            Modifier
                .padding(shadowPad)
                .graphicsLayer {
                    val p = progress.value
                    // Растёт из левого нижнего угла, откуда пришёл палец.
                    transformOrigin = TransformOrigin(0.08f, if (below) 0f else 1f)
                    val sc = 0.55f + 0.45f * p
                    scaleX = sc
                    scaleY = sc
                    alpha = (p * 1.8f).coerceIn(0f, 1f)
                },
        ) {
            Surface(
                modifier = Modifier.widthIn(min = 248.dp, max = 320.dp),
                shape = RoundedCornerShape(28.dp),
                color = cs.surfaceContainerHigh,
                contentColor = cs.onSurface,
                shadowElevation = 8.dp,
                border = BorderStroke(0.5.dp, cs.onSurface.copy(alpha = 0.10f)),
            ) {
                LazyColumn(
                    Modifier.heightIn(max = 340.dp),
                    state = listState,
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(list, key = { it.id }) { t ->
                        QuickTabRow(t, selected = t === current) {
                            onPick(t)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTabRow(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) cs.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab.home) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(HripsIcons.BarHome, null, Modifier.size(22.dp), tint = cs.onSurfaceVariant)
            }
        } else {
            TabSiteIcon(tab, 28.dp, shape = CircleShape)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tab.label(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            )
            if (!tab.home) {
                Text(
                    tab.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/'),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(7.dp).clip(CircleShape).background(cs.primary))
        }
    }
}

/**
 * Меню по удержанию адресной строки (как в Safari на iOS 26): три группы сегментов («Поделиться / Скопировать /
 * Вставить и перейти», «Добавить в группу / Закрепить вкладку», «Закрыть все / Закрыть вкладку»). У приватной вкладки
 * групп и закрепления нет. Окно над строкой (нижняя панель) или под ней (планшет).
 */
@Composable
internal fun PillMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    browser: Browser,
    tab: Tab,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onPasteGo: () -> Unit,
    onCloseTab: () -> Unit,
    onCloseAll: () -> Unit,
    fromBottom: Boolean = true,
) {
    val store = browser.store
    // Подменю «Добавить в группу» открывается в том же окне; при каждом новом открытии меню начинаем с главного.
    var groups by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (expanded) groups = false }

    SegMenuPopup(
        visible = expanded,
        onDismiss = onDismiss,
        position = rememberAnchorMenuPosition(),
        origin = TransformOrigin(0.5f, if (fromBottom) 1f else 0f),
    ) {
        val act: (() -> Unit) -> () -> Unit = { action -> { onDismiss(); action() } }
        val entries = if (groups) {
            groupPickerEntries(
                store = store,
                current = store.groupOf(tab.id),
                onBack = { groups = false },
                onPick = { g ->
                    onDismiss()
                    store.assignTab(tab.id, g)
                    Notices.show("Вкладка добавлена в группу: ${store.groupName(g)}")
                },
                onNew = { onDismiss(); creating = true },
            )
        } else {
            val pinned = store.isPinned(tab.id)
            val first = buildList {
                if (!tab.home) {
                    add(MenuEntry(HripsIcons.Share, "Поделиться", onClick = act(onShare)))
                    add(MenuEntry(HripsIcons.Copy, "Скопировать", onClick = act(onCopy)))
                }
                add(MenuEntry(HripsIcons.Link, "Вставить и перейти", onClick = act(onPasteGo)))
            }
            val second: List<MenuEntry> = if (tab.isPrivate) {
                emptyList()
            } else {
                listOf(
                    MenuEntry(HripsIcons.Folder, "Добавить в группу", submenu = true, onClick = { groups = true }),
                    MenuEntry(
                        if (pinned) HripsIcons.TabUnpin else HripsIcons.TabPin,
                        if (pinned) "Открепить вкладку" else "Закрепить вкладку",
                        onClick = act { store.setPinned(tab.id, !pinned) },
                    ),
                )
            }
            val third = listOf(
                MenuEntry(HripsIcons.Close, "Закрыть все (${browser.tabs.size})", danger = true, onClick = act(onCloseAll)),
                MenuEntry(HripsIcons.Close, "Закрыть вкладку", danger = true, onClick = act(onCloseTab)),
            )
            listOf(first, second, third)
        }
        SegMenuColumn(entries)
    }

    if (creating) {
        GroupDialog(
            title = "Новая группа",
            initialName = "",
            initialIcon = 1,
            onSave = { n, i ->
                val g = store.addTabGroup(n, i)
                store.assignTab(tab.id, g.id)
                creating = false
                Notices.show("Вкладка добавлена в группу: ${g.name}")
            },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
}

/** Заливка прогресса и бегущий блик. Живёт в композиции, пока идёт загрузка, поэтому бесконечная анимация блика зря не тратит кадры. */
@Composable
internal fun LoadFill(fill: LoadFillState, loading: Boolean, ink: Color) {
    val sweep = rememberInfiniteTransition(label = "barSweep").animateFloat(
        initialValue = -0.4f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)),
        label = "barSweepX",
    )
    Box(
        Modifier.fillMaxSize().drawBehind {
            val a = fill.alpha.value
            if (a <= 0.01f) return@drawBehind
            val w = size.width * fill.progress.value.coerceIn(0f, 1f)
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
internal fun LoadRing(progress: Float, color: Color) {
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
        Icon(HripsIcons.Close.variant(2f, 14f / 12f), "Остановить", Modifier.size(13.dp))
    }
}

/**
 * Значок «меню сайта» в адресной строке. Если включено действие (чтение, перевод, ПК-версия), вместо него стоит
 * подсвеченный значок действия. Красным он бывает только у загруженной небезопасной страницы: пока страница грузится,
 * защита неизвестна, и красный был бы ложной тревогой.
 */
@Composable
internal fun PageMenuGlyph(tab: Tab, iconSize: Dp = 24.dp, ringSize: Dp = 32.dp) {
    val cs = MaterialTheme.colorScheme
    val active = when {
        tab.home -> null
        tab.readerActive -> HripsIcons.Reader
        tab.translated || tab.translating -> HripsIcons.Translate
        tab.desktopMode -> HripsIcons.Desktop
        else -> null
    }
    val warn = !tab.home && !tab.loading && tab.trust == Trust.WARNING
    if (active != null && !warn) {
        Box(
            Modifier.size(ringSize).background(cs.primary.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(active, "Включено действие над страницей", Modifier.size(iconSize - 2.dp), tint = cs.primary) }
    } else {
        Icon(
            if (tab.home) HripsIcons.Search else active ?: HripsIcons.SiteMenu,
            if (tab.home) "Поиск" else "Меню сайта",
            Modifier.size(iconSize),
            tint = if (warn) cs.error else LocalContentColor.current,
        )
    }
}
