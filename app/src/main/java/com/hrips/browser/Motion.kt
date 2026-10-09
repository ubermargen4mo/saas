package com.hrips.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import kotlin.math.min
import androidx.compose.ui.geometry.lerp as lerpRect
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.util.lerp as lerpFloat

/*
 * Единый язык движения для полноэкранных экранов поверх браузера.
 *
 * Раньше каждый экран (поиск, вкладки, настройки, загрузки, расширения) выезжал одинаково: fade + scale из центра
 * с зашитыми tween/spring. Теперь экран «вырастает» из того элемента, на который нажали (контейнерный переход
 * Material 3), а скорость и упругость берутся из MotionScheme темы, а не из чисел в коде.
 */

/**
 * Помнит, откуда пошёл переход. Элементы-источники (пилюля поиска, счётчик вкладок) регистрируют свои границы
 * через [originAnchor]; последнее нажатие запоминает [trackTouches]. Если на экране несколько источников одной
 * группы (адресная строка и пилюля на главной), берётся тот, на который нажали.
 */
class OriginTracker {
    /** Где последний раз коснулись экрана (в координатах корня Compose). */
    var lastDown: Offset = Offset.Unspecified

    /** Экран уже «ушёл» своей анимацией (карточка вкладки раскрылась в страницу): закрывать мгновенно. */
    var instantClose: Boolean = false

    private val anchors = HashMap<Any, Pair<String, Rect>>()

    fun put(id: Any, group: String, rect: Rect) {
        anchors[id] = group to rect
    }

    fun remove(id: Any) {
        anchors.remove(id)
    }

    /** Границы источника группы [group], на который нажали; null, если такого нет. */
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

/** Отмечает элемент как возможный источник перехода группы [group]. */
@Composable
fun Modifier.originAnchor(group: String): Modifier {
    val tracker = LocalOrigins.current
    val id = remember { Any() }
    DisposableEffect(id, tracker) { onDispose { tracker.remove(id) } }
    return this.onGloballyPositioned { tracker.put(id, group, it.boundsInRoot()) }
}

/** Запоминает точку каждого нового касания, ничего не перехватывая. */
fun Modifier.trackTouches(tracker: OriginTracker): Modifier = pointerInput(tracker) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.firstOrNull { it.pressed && !it.previousPressed }
            if (down != null) tracker.lastDown = down.position
        }
    }
}

internal class RevealShape(val rect: Rect, val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(corner)))
}

/**
 * Показывает [content] на весь экран, «вырастая» из источника группы [group] (или из точки последнего касания,
 * если источника нет) и сжимаясь обратно при закрытии. Пока экран закрыт, в композиции его нет.
 *
 * [startColor] - цвет источника (пилюли): в начале перехода окно закрашено им, к концу плавно переходит в фон экрана.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RevealHost(
    visible: Boolean,
    group: String?,
    modifier: Modifier = Modifier,
    startColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val tracker = LocalOrigins.current
    val density = LocalDensity.current
    val progress = remember { Animatable(0f) }
    var origin by remember { mutableStateOf<Rect?>(null) }
    var shown by remember { mutableStateOf(false) }
    val spatial by rememberUpdatedState(MaterialTheme.motionScheme.defaultSpatialSpec<Float>())
    val endColor = MaterialTheme.colorScheme.surface
    val fromColor = if (startColor == Color.Unspecified) endColor else startColor

    LaunchedEffect(visible) {
        if (visible) {
            // Источник определяем в момент открытия и держим до закрытия: экран сожмётся туда же, откуда вырос
            origin = tracker.resolve(group)
                ?: tracker.lastDown.takeIf { it.isSpecified }?.let { Rect(it, with(density) { 28.dp.toPx() }) }
            shown = true
            progress.animateTo(1f, spatial)
        } else if (shown) {
            if (tracker.instantClose) {
                tracker.instantClose = false
                progress.snapTo(0f)
            } else {
                progress.animateTo(0f, spatial)
            }
            shown = false
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
                // Скругление источника: половина его меньшей стороны (пилюля -> полукруг, квадрат кнопки -> круг)
                val startCorner = if (from != null) min(from.width, from.height) / 2f else 0f
                shape = RevealShape(lerpRect(start, full, p), lerpFloat(startCorner, 0f, p.coerceIn(0f, 1f)))
                clip = true
            }
            .drawBehind {
                val t = (progress.value * 2f).coerceIn(0f, 1f)
                drawRect(lerpColor(fromColor, endColor, t))
            },
    ) {
        // Содержимое проявляется чуть позже, чем окно начинает расти, чтобы в начале читалась форма источника
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = ((progress.value - 0.12f) / 0.45f).coerceIn(0f, 1f) },
        ) { content() }
    }
}
