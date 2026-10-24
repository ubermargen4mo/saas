package com.hrips.browser

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.geometry.lerp as lerpRect
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.util.lerp as lerpFloat

class OriginTracker {
    var lastDown: Offset = Offset.Unspecified

    var instantClose: Boolean = false

    var currentCardRect by mutableStateOf<Rect?>(null)

    var expandStamp by mutableIntStateOf(0)

    private val anchors = HashMap<Any, Pair<String, Rect>>()

    fun put(id: Any, group: String, rect: Rect) {
        anchors[id] = group to rect
    }

    fun remove(id: Any) {
        anchors.remove(id)
    }

    fun resolve(group: String?): Rect? {
        if (group == null) return null
        val list = anchors.values.filter { it.first == group && !it.second.isEmpty }.map { it.second }
        if (list.isEmpty()) return null
        val p = lastDown
        if (!p.isSpecified) return list.first()
        return list.firstOrNull { it.contains(p) } ?: list.minByOrNull { (it.center - p).getDistance() }
    }
}

val LocalOrigins = staticCompositionLocalOf { OriginTracker() }

@Composable
fun Modifier.originAnchor(group: String): Modifier {
    val tracker = LocalOrigins.current
    val id = remember { Any() }
    DisposableEffect(id, tracker) { onDispose { tracker.remove(id) } }
    return this.onGloballyPositioned { tracker.put(id, group, it.boundsInRoot()) }
}

fun Modifier.trackTouches(tracker: OriginTracker): Modifier = pointerInput(tracker) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.firstOrNull { it.pressed && !it.previousPressed }
            if (down != null) tracker.lastDown = down.position
        }
    }
}

private const val OPEN_MS = 420
private const val CLOSE_MS = 300
private const val FADE_OPEN_MS = 200
private const val FADE_CLOSE_MS = 160

object HripsMotion {
    const val ChromeMs = 220

    val ChromeEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val PageSwapMs = 180

    const val PressScale = 0.92f

    const val FlightMs = 600

    fun <T> fling() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)

    /** Сворачивание/разворачивание нижней панели и подстройка страницы под неё: без перелёта, мягко. */
    fun <T> barCollapse() = spring<T>(dampingRatio = 1f, stiffness = 420f)

    /** Появление частей панели: лёгкий перелёт, как будто пилюли «встают» на место. */
    fun <T> barAppear() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)

    /** Подпрыгивание панели, когда страница догрузилась: заметно, но затухает за ~400 мс. */
    fun <T> barBounce() = spring<T>(dampingRatio = 0.42f, stiffness = 480f)

    /** Нажатие и отпускание кнопок: отпускание чуть пружинит. */
    fun <T> pressRelease() = spring<T>(dampingRatio = 0.5f, stiffness = 520f)
    fun <T> pressDown() = spring<T>(dampingRatio = 0.9f, stiffness = 1200f)
}
private val OpenEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val CloseEasing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

internal class RevealShape(val rect: Rect, val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(corner)))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RevealHost(
    visible: Boolean,
    group: String?,
    modifier: Modifier = Modifier,
    startColor: Color = Color.Unspecified,
    onDismiss: (() -> Unit)? = null,
    fadeOnly: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tracker = LocalOrigins.current
    val density = LocalDensity.current
    val progress = remember { Animatable(0f) }
    var origin by remember { mutableStateOf<Rect?>(null) }
    var shown by remember { mutableStateOf(false) }
    val endColor = MaterialTheme.colorScheme.surface
    val fromColor = if (startColor == Color.Unspecified) endColor else startColor

    val back = remember { Animatable(0f) }
    var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val scope = rememberCoroutineScope()
    val dismiss = rememberUpdatedState(onDismiss)
    PredictiveBackHandler(enabled = visible && shown && onDismiss != null) { events ->
        try {
            events.collect { e ->
                backEdge = e.swipeEdge
                back.snapTo(e.progress)
            }
            dismiss.value?.invoke()
        } catch (c: CancellationException) {
            scope.launch { back.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
            throw c
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            back.snapTo(0f)
            origin = tracker.resolve(group)
                ?: tracker.lastDown.takeIf { it.isSpecified }?.let { Rect(it, with(density) { 28.dp.toPx() }) }
            progress.snapTo(0f)
            shown = true
            withFrameNanos { }
            withFrameNanos { }
            progress.animateTo(1f, if (fadeOnly) tween(FADE_OPEN_MS, easing = FastOutSlowInEasing) else tween(OPEN_MS, easing = OpenEasing))
        } else if (shown) {
            if (tracker.instantClose) {
                tracker.instantClose = false
                progress.snapTo(0f)
            } else {
                progress.animateTo(0f, if (fadeOnly) tween(FADE_CLOSE_MS, easing = FastOutSlowInEasing) else tween(CLOSE_MS, easing = CloseEasing))
            }
            shown = false
            back.snapTo(0f)
        }
    }

    if (!shown) return

    val from = origin
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress.value
                val full = Rect(0f, 0f, size.width, size.height)
                val start = from ?: Rect(size.center, 1f)
                val startCorner = if (from != null) min(from.width, from.height) / 2f else 0f
                val b = back.value.coerceIn(0f, 1f)
                val bs = 1f - 0.1f * b
                scaleX = bs
                scaleY = bs
                translationX = (if (backEdge == BackEventCompat.EDGE_LEFT) 1f else -1f) * b * 12.dp.toPx()
                if (fadeOnly) {
                    alpha = p.coerceIn(0f, 1f)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    shape = RevealShape(full, b * 32.dp.toPx())
                    clip = b > 0f
                } else {
                    val corner = max(lerpFloat(startCorner, 0f, p.coerceIn(0f, 1f)), b * 32.dp.toPx())
                    shape = RevealShape(lerpRect(start, full, p), corner)
                    clip = p < 1f || b > 0f
                }
            }
            .drawBehind {
                val t = (progress.value * 3f).coerceIn(0f, 1f)
                drawRect(lerpColor(fromColor, endColor, t))
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value
                    alpha = if (fadeOnly) 1f else (p / 0.3f).coerceIn(0f, 1f)
                    val sc = if (fadeOnly) 1f else 0.94f + 0.06f * p.coerceIn(0f, 1f)
                    scaleX = sc
                    scaleY = sc
                },
        ) { content() }
    }
}
