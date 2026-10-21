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
