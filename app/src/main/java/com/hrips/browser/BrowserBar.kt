@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.util.lerp as lerpFloat

internal class BarPalette(
    val container: Color,
    val content: Color,
    val edge: Color,
)

@Composable
private fun rememberBarPalette(site: Color?): BarPalette {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val base = site ?: cs.surfaceContainerHigh
    val onBase = when {
        site == null -> cs.onSurface
        site.luminance() > 0.5f -> Color(0xFF15161B)
        else -> Color.White
    }
    val tinted = if (site != null) lerp(base, onBase, 0.07f) else base
    val container by animateColorAsState(tinted.copy(alpha = 0.92f), motion.defaultEffectsSpec<Color>(), label = "barContainer")
    val content by animateColorAsState(onBase, motion.defaultEffectsSpec<Color>(), label = "barContent")
    return BarPalette(container, content, content.copy(alpha = 0.10f))
}

@Composable
private fun GlassPill(
    palette: BarPalette,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = palette.container,
        contentColor = palette.content,
        shadowElevation = 6.dp,
        border = BorderStroke(0.6.dp, palette.edge),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

private fun Modifier.collapseWidth(t: () -> Float): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val keep = 1f - t()
        val w = (placeable.width * keep).roundToInt()
        val h = (placeable.height * keep).roundToInt()
        layout(w, h) { placeable.placeRelative(0, (h - placeable.height) / 2) }
    }
    .graphicsLayer {
        val keep = 1f - t()
        alpha = keep
        val s = 0.7f + 0.3f * keep
        scaleX = s
        scaleY = s
    }

@Composable
internal fun SafariBar(
    tab: Tab,
    shown: String,
    collapsed: Boolean,
    backEnabled: Boolean,
    downloads: Downloads,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onAddress: () -> Unit,
    onMore: () -> Unit,
    onDownloads: () -> Unit,
    onExpand: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeSide: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val palette = rememberBarPalette(if (tab.home || tab.errorPage) null else tab.siteColor)

    val t by animateFloatAsState(
        if (collapsed) 1f else 0f,
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "barCollapse",
    )

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
    val rise = with(density) { 36.dp.toPx() }

    val shownProgress by animateFloatAsState(tab.progress / 100f, motion.defaultEffectsSpec<Float>(), label = "barProgress")
    val finishing = !tab.loading && tab.progress >= 100 && shownProgress < 0.99f
    val fillAlpha by animateFloatAsState(
        if ((tab.loading || finishing) && !tab.home) 1f else 0f,
        motion.defaultEffectsSpec<Float>(),
        label = "barFillAlpha",
    )
    val sweep by rememberInfiniteTransition(label = "barSweep").animateFloat(
        initialValue = -0.4f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)),
        label = "barSweepX",
    )

    val pop = remember { Animatable(1f) }
    var wasLoading by remember { mutableStateOf(false) }
    LaunchedEffect(tab.loading) {
        if (wasLoading && !tab.loading && !tab.home) {
            pop.animateTo(1.035f, tween(90))
            pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
        }
        wasLoading = tab.loading
    }

    var dragX by remember { mutableFloatStateOf(0f) }
    val swipeSide = with(density) { 72.dp.toPx() }
    val swipeUp = with(density) { 36.dp.toPx() }
    var showSecurity by remember { mutableStateOf(false) }

    val height = lerpDp(HripsLayout.BarHeight, HripsLayout.BarCompactHeight, t)
    val sideInset = lerpDp(HripsLayout.BarMargin, 72.dp, t)
    val iconSlot = lerpDp(40.dp, 0.dp, t)

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = sideInset)
            .graphicsLayer {
                alpha = appear.value.coerceIn(0f, 1f)
                translationY = (1f - appear.value) * rise
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.collapseWidth { t }) {
            GlassPill(palette) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarButton(onClick = onBack, enabled = backEnabled, modifier = Modifier.size(44.dp)) {
                        Icon(HripsIcons.BarBack, "Назад", Modifier.size(24.dp))
                    }
                    BarButton(onClick = onForward, enabled = tab.canGoForward && !tab.home, modifier = Modifier.size(44.dp)) {
                        Icon(HripsIcons.BarForward, "Вперёд", Modifier.size(24.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(lerpDp(8.dp, 0.dp, t)))

        GlassPill(
            palette,
            Modifier
                .weight(1f)
                .height(height)
                .originAnchor("tabs")
                .graphicsLayer {
                    translationX = dragX
                    scaleX = pop.value
                    scaleY = pop.value
                },
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val a = fillAlpha
                        if (a > 0.01f) {
                            val ink = palette.content
                            val w = size.width * shownProgress.coerceIn(0f, 1f)
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
                            if (tab.loading) {
                                val band = size.width * 0.35f
                                val x = sweep * size.width
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
                        }
                    }
                    .pointerInput(collapsed) {
                        var dx = 0f
                        var dy = 0f
                        val settle: () -> Unit = {
                            scope.launch {
                                Animatable(dragX).animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) { dragX = value }
                            }
                        }
                        detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dx += amount.x
                                dy += amount.y
                                if (abs(dx) > abs(dy)) dragX = (dx * 0.5f).coerceIn(-swipeSide, swipeSide)
                            },
                            onDragEnd = {
                                if (abs(dy) > abs(dx) && dy < -swipeUp) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipeUp()
                                } else if (abs(dx) > abs(dy) && abs(dx) > swipeSide) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipeSide(if (dx < 0) 1 else -1)
                                }
                                settle()
                            },
                            onDragCancel = { settle() },
                        )
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = if (collapsed) "Показать панель" else "Ввести запрос или адрес",
                    ) { if (collapsed) onExpand() else onAddress() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.width(iconSlot).fillMaxHeight().graphicsLayer { alpha = 1f - t }.clipToBounds(),
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
                        targetState = shown,
                        transitionSpec = {
                            (fadeIn(tween(180, delayMillis = 60)) + slideInVertically(tween(220)) { it / 3 }) togetherWith
                                (fadeOut(tween(90)) + slideOutVertically(tween(160)) { -it / 3 }) using
                                SizeTransform(clip = false) { _, _ -> snap() }
                        },
                        contentAlignment = Alignment.Center,
                        label = "barText",
                    ) { text ->
                        Text(
                            text.ifEmpty { "Поиск" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = lerpFloat(16f, 13.5f, t).sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }

                Box(
                    Modifier.width(iconSlot).fillMaxHeight().graphicsLayer { alpha = 1f - t }.clipToBounds(),
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

        Spacer(Modifier.width(lerpDp(8.dp, 0.dp, t)))
        Box(Modifier.collapseWidth { t }) {
            GlassPill(palette) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedDownloadsSlot(downloads, onClick = onDownloads, size = 44.dp, centered = true)
                    BarButton(onClick = onMore, modifier = Modifier.size(44.dp)) {
                        Icon(HripsIcons.BarMore, "Меню", Modifier.size(24.dp))
                    }
                }
            }
        }
    }

    if (showSecurity) SecurityDialog(tab) { showSecurity = false }
}

@Composable
private fun LoadRing(progress: Float, color: Color) {
    val shown by animateFloatAsState(progress, MaterialTheme.motionScheme.defaultEffectsSpec<Float>(), label = "ring")
    val spin by rememberInfiniteTransition(label = "ringSpin").animateFloat(
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
                startAngle = if (indeterminate) spin else -90f,
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
