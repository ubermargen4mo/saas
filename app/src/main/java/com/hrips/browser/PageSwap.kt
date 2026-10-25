package com.hrips.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.CompositingStrategy
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
import androidx.compose.ui.util.lerp as lerpF
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding

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
 * Снимок страницы сжимается в карточку текущей вкладки и в конце растворяется, пока карточка проявляется.
 * Карточка своей формы (как у экрана) совпадает по пропорциям со снимком, поэтому движение выглядит цельным.
 * Если карточка не успела сообщить прямоугольник, снимок просто гаснет.
 */
@Composable
fun TabsCollapseOverlay(thumb: ImageBitmap, modifier: Modifier = Modifier) {
    val origins = LocalOrigins.current
    val p = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(Unit) {
        // Прямоугольник с прошлого открытия не подходит: сбрасываем и ждём новый от переключателя.
        origins.currentCardRect = null
        withTimeoutOrNull(180) { snapshotFlow { origins.currentCardRect }.first { it != null } }
        p.animateTo(1f, tween(HripsMotion.PageSwapMs + 160, easing = FastOutSlowInEasing))
        done = true
    }
    if (done) return
    Image(
        bitmap = thumb,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            // Центр читаем до собственного слоя: так в него не попадает анимация этого же слоя.
            .onGloballyPositioned {
                val c = it.boundsInRoot().center
                if (c != base) base = c
            }
            .graphicsLayer {
                val e = p.value
                val r = origins.currentCardRect
                if (r != null && base.isSpecified && size.width > 0f && size.height > 0f) {
                    val sx = lerpF(1f, r.width / size.width, e)
                    val sy = lerpF(1f, r.height / size.height, e)
                    scaleX = sx
                    scaleY = sy
                    translationX = lerpF(0f, r.center.x - base.x, e)
                    translationY = lerpF(0f, r.center.y - base.y, e)
                    // Радиус делим на масштаб: после сжатия углы получаются 28 dp, как у карточки.
                    shape = RoundedCornerShape((28.dp.toPx() * e / sx.coerceAtLeast(0.05f)).coerceAtLeast(0f))
                    clip = e > 0f
                    alpha = 1f - ((e - 0.7f) / 0.3f).coerceIn(0f, 1f)
                } else {
                    alpha = 1f - e
                }
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    )
}
