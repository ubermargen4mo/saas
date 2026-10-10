package com.hrips.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay

/*
 * Плавная смена содержимого страницы. Живой вид движка (GeckoView) нельзя перекрёстно растворить обычным alpha,
 * поэтому поверх него на короткое время кладётся снимок вкладки, который тает: переключение вкладки и раскрытие
 * карточки из переключателя больше не «щёлкают» и не показывают пустой кадр, пока движок рисует страницу.
 */

/**
 * Снимок вкладки поверх страницы: появляется сразу и растворяется за [HripsMotion.PageSwapMs].
 * Запускается при смене вкладки и когда карточка раскрылась в страницу ([OriginTracker.expandStamp]).
 * Не перехватывает нажатия. Если снимка нет или вкладка ещё грузится (снимок мог быть от прошлой страницы), ничего не рисует.
 */
@Composable
fun TabSwapCover(tab: Tab, modifier: Modifier = Modifier) {
    val origins = LocalOrigins.current
    val stamp = origins.expandStamp
    val alpha = remember { Animatable(0f) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(tab.id, stamp) {
        val img = if (tab.loading || tab.home) null
        else tab.thumbnail ?: if (tab.isPrivate) null else TabThumbs.peek(tab.url, false)
        image = img
        if (img == null) {
            alpha.snapTo(0f)
            return@LaunchedEffect
        }
        alpha.snapTo(1f)
        delay(40) // движку нужен кадр-другой, чтобы нарисовать страницу под снимком
        alpha.animateTo(0f, tween(HripsMotion.PageSwapMs))
        image = null
    }

    val img = image
    if (img != null && alpha.value > 0.01f) {
        Image(
            bitmap = img,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
        )
    }
}

/** Содержимое проявляется при появлении в композиции (начальная страница при переходе со страницы сайта). */
@Composable
fun FadeInBox(modifier: Modifier = Modifier, durationMs: Int = HripsMotion.PageSwapMs, content: @Composable () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(durationMs)) }
    Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) { content() }
}

/**
 * Снимок, который морфится между прямоугольниками: размер и положение берутся из [rect] (получает размер экрана),
 * скругление из [cornerPx], прозрачность из [alpha]. Картинка растягивается вместе с рамкой (Crop сверху).
 * Не перехватывает нажатия.
 */
@Composable
fun RectMorph(
    bitmap: ImageBitmap,
    rect: (Size) -> Rect,
    cornerPx: () -> Float,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
    background: Color = Color.Transparent,
) {
    Box(
        modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val r = rect(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
                val placeable = measurable.measure(
                    Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
            }
            .graphicsLayer {
                shape = RoundedCornerShape(CornerSize(cornerPx()))
                clip = true
                this.alpha = alpha()
            }
            .background(background),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Открытие переключателя вкладок: снимок страницы на весь экран сжимается в карточку этой вкладки
 * (обратное тому, как карточка раскрывается в страницу). Сам переключатель в это время проявляется под ним.
 * Положение карточки приходит из [OriginTracker.currentCardRect], когда переключатель её разметил; если карточки
 * нет в видимой части экрана (длинная сетка) или она не появилась за полсекунды, снимок просто растворяется.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TabsCollapseOverlay(thumb: ImageBitmap, modifier: Modifier = Modifier) {
    val origins = LocalOrigins.current
    val progress = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var done by remember { mutableStateOf(false) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val density = LocalDensity.current
    val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val endCorner = with(density) { 36.dp.toPx() }

    LaunchedEffect(Unit) {
        origins.currentCardRect = null
        val target = withTimeoutOrNull(500) { snapshotFlow { origins.currentCardRect }.filterNotNull().first() }
        if (target == null || target.top < -1f || target.bottom > screenH + 1f) {
            fade.animateTo(0f, tween(HripsMotion.PageSwapMs))
        } else {
            progress.animateTo(1f, spec)
        }
        done = true
    }
    if (done) return

    RectMorph(
        bitmap = thumb,
        modifier = modifier,
        rect = { full ->
            val to = origins.currentCardRect
            if (to == null) Rect(Offset.Zero, full) else lerp(Rect(Offset.Zero, full), to, progress.value)
        },
        cornerPx = { endCorner * progress.value.coerceIn(0f, 1f) },
        // Последняя четверть пути: снимок тает, под ним уже настоящая карточка
        alpha = { fade.value * (1f - ((progress.value - 0.75f) / 0.25f).coerceIn(0f, 1f)) },
    )
}
