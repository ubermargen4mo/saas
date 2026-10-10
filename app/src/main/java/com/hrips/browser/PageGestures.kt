@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.material3.LoadingIndicator
import android.app.Activity
import android.view.MenuItem
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoSession
import kotlin.math.roundToInt

/**
 * Меню над выделенным текстом: «Вырезать», «Копировать», «Вставить», «Выделить всё» и системные
 * действия других приложений (перевод и т.п.), плюс своё действие «Искать».
 */
class HripsSelectionDelegate(
    activity: Activity,
    private val onSearch: (String) -> Unit,
) : BasicSelectionActionDelegate(activity) {

    override fun getAllActions(): Array<String> {
        val list = super.getAllActions().toMutableList()
        val at = list.indexOf(GeckoSession.SelectionActionDelegate.ACTION_COPY)
        list.add(if (at >= 0) at + 1 else list.size, ACTION_SEARCH)
        return list.toTypedArray()
    }

    override fun isActionAvailable(id: String): Boolean =
        if (id == ACTION_SEARCH) mSelection?.text?.isNotBlank() == true else super.isActionAvailable(id)

    override fun prepareAction(id: String, item: MenuItem) {
        if (id == ACTION_SEARCH) item.title = "Искать" else super.prepareAction(id, item)
    }

    override fun performAction(id: String, item: MenuItem): Boolean {
        if (id != ACTION_SEARCH) return super.performAction(id, item)
        val text = mSelection?.text?.trim().orEmpty()
        clearSelection()
        if (text.isNotEmpty()) onSearch(text)
        return true
    }

    companion object {
        const val ACTION_SEARCH = "org.hrips.browser.SEARCH"
    }
}

/**
 * Кружок «потяните, чтобы обновить» над страницей (как в Опере).
 * [pullPx] - сколько пальцем оттянули страницу вниз, [thresholdPx] - сколько нужно, чтобы обновление
 * сработало при отпускании, [refreshing] - палец отпущен, страница обновляется: кружок замирает
 * на месте и крутится, потом уезжает вверх.
 */
@Composable
fun PullIndicator(pullPx: Float, thresholdPx: Float, refreshing: Boolean, modifier: Modifier = Modifier) {
    val armed = pullPx >= thresholdPx
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(armed) {
        if (armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val visible = pullPx > 0f || refreshing
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(180), label = "pullAlpha")
    if (alpha <= 0f) return

    val density = LocalDensity.current
    val cs = MaterialTheme.colorScheme
    val size = 44.dp
    val sizePx = with(density) { size.toPx() }
    val progress = (pullPx / thresholdPx).coerceIn(0f, 1f)

    // Палец ведёт кружок с сопротивлением; при обновлении он замирает чуть ниже верха страницы
    val maxTravel = with(density) { 96.dp.toPx() }
    val holdY = with(density) { 72.dp.toPx() } - sizePx
    val targetY = when {
        refreshing -> holdY
        pullPx > 0f -> (pullPx * 0.5f).coerceAtMost(maxTravel) - sizePx
        else -> -sizePx // отпустили, не дотянув: уезжает вверх
    }
    val y by animateFloatAsState(
        targetY,
        // За пальцем - без задержки, отпустили - пружинкой
        if (pullPx > 0f && !refreshing) snap() else MaterialTheme.motionScheme.defaultSpatialSpec<Float>(),
        label = "pullY",
    )

    val tint by animateColorAsState(
        if (armed || refreshing) cs.primary else cs.onSurfaceVariant,
        tween(150),
        label = "pullTint",
    )
    // Пока тянем - кружок растёт и проявляется; обновление идёт - он уже полный
    val grow = if (refreshing) 1f else 0.6f + 0.4f * progress

    Surface(
        shape = CircleShape,
        color = cs.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier
            .offset { IntOffset(0, y.roundToInt()) }
            .size(size)
            .graphicsLayer { this.alpha = alpha; scaleX = grow; scaleY = grow },
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Пока тянем, фигура морфится вслед за пальцем; при обновлении крутится и меняет формы сама
            if (refreshing) {
                LoadingIndicator(Modifier.size(32.dp), color = tint)
            } else {
                LoadingIndicator(progress = { progress }, modifier = Modifier.size(32.dp), color = tint)
            }
        }
    }
}
