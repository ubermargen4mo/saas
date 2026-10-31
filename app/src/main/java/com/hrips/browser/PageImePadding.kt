package com.hrips.browser

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Отступ под клавиатуру без покадровых пересчётов: обычный imePadding() меняет размер GeckoView на каждом кадре
 * анимации клавиатуры, страница дёргается. Здесь размер меняется один раз, когда клавиатура остановилась; когда
 * она уезжает, страница сразу занимает всё место. Пока [frozen] (поверх открыт экран со своим полем ввода) и пока
 * клавиатура не ушла после его закрытия, страница не трогается.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.pageImePadding(frozen: Boolean): Modifier {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeVisible = WindowInsets.isImeVisible

    var latched by remember { mutableStateOf(false) }
    LaunchedEffect(frozen, imeVisible) {
        if (frozen) latched = true else if (!imeVisible) latched = false
    }
    val hold = frozen || latched

    var settled by remember { mutableIntStateOf(0) }
    val jumpPx = with(density) { 48.dp.roundToPx() }
    LaunchedEffect(hold, density) {
        if (hold) {
            settled = 0
            return@LaunchedEffect
        }
        snapshotFlow { ime.getBottom(density) }.collectLatest { raw ->
            if (raw == 0 || raw < settled - jumpPx) {
                settled = 0
            } else if (raw != settled) {
                delay(90)
                settled = raw
            }
        }
    }

    return this.layout { measurable, constraints ->
        val bottom = if (hold) 0 else settled
        val h = (constraints.maxHeight - bottom).coerceAtLeast(0)
        val placeable = measurable.measure(constraints.copy(minHeight = h.coerceAtMost(constraints.minHeight), maxHeight = h))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
    }
}
