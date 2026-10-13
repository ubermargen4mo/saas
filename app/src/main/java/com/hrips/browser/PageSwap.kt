package com.hrips.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
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

@Composable
fun TabSwapCover(tab: Tab, modifier: Modifier = Modifier) {
    val origins = LocalOrigins.current
    val stamp = origins.expandStamp
    val alpha = remember { Animatable(0f) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

    LaunchedEffect(tab.id, stamp) {
        // Если снимка нет в памяти, читаем с диска: страница не должна мигнуть пустой, пока восстанавливается.
        val img = if (tab.loading || tab.home) null
        else tab.thumbnail ?: if (tab.isPrivate) null else (TabThumbs.peek(tab.id, false) ?: TabThumbs.load(appContext, tab.id, small = false))
        image = img
        if (img == null) {
            alpha.snapTo(0f)
            return@LaunchedEffect
        }
        alpha.snapTo(1f)
        delay(40)
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

@Composable
fun FadeInBox(modifier: Modifier = Modifier, durationMs: Int = HripsMotion.PageSwapMs, content: @Composable () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(durationMs)) }
    Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) { content() }
}

/**
 * Картинка, которая плавно переходит из прямоугольника в прямоугольник. Размеры и раскладка не меняются:
 * кадр анимации - это только слой (обрезка по скруглённому прямоугольнику + масштаб и сдвиг картинки), без измерения.
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
            .graphicsLayer {
                shape = RevealShape(rect(size), cornerPx())
                clip = true
                this.alpha = alpha()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .background(background),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val r = rect(size)
                val k = maxOf(r.width / size.width, r.height / size.height)
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = k
                scaleY = k
                translationX = r.left + (r.width - size.width * k) / 2f
                translationY = r.top
            },
        )
    }
}

/** Снимок страницы просто гаснет, пока переключатель проявляется: одна прозрачность, без обрезки и масштаба. */
@Composable
fun TabsCollapseOverlay(thumb: ImageBitmap, modifier: Modifier = Modifier) {
    val fade = remember { Animatable(1f) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        fade.animateTo(0f, tween(HripsMotion.PageSwapMs, easing = FastOutSlowInEasing))
        done = true
    }
    if (done) return
    Image(
        bitmap = thumb,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize().graphicsLayer {
            alpha = fade.value
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    )
}
