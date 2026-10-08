package com.hrips.browser

import android.app.Activity
import android.view.MenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
 * Кружок «потяните, чтобы обновить» над страницей. [pullPx] - сколько пальцем оттянули страницу вниз,
 * [thresholdPx] - сколько нужно, чтобы обновление сработало при отпускании.
 */
@Composable
fun PullIndicator(pullPx: Float, thresholdPx: Float, modifier: Modifier = Modifier) {
    val armed = pullPx >= thresholdPx
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(armed) {
        if (armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    if (pullPx <= 0f) return

    val density = LocalDensity.current
    val cs = MaterialTheme.colorScheme
    val size = 44.dp
    val progress = (pullPx / thresholdPx).coerceIn(0f, 1f)
    // Кружок едет следом за пальцем с сопротивлением и не уезжает дальше этого расстояния
    val maxTravel = with(density) { 96.dp.toPx() }
    val y = (pullPx * 0.5f).coerceAtMost(maxTravel) - with(density) { size.toPx() }

    Surface(
        shape = CircleShape,
        color = if (armed) cs.primaryContainer else cs.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier.offset { IntOffset(0, y.roundToInt()) }.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                HripsIcons.Refresh,
                null,
                Modifier.size(22.dp).rotate(progress * 270f),
                tint = if (armed) cs.onPrimaryContainer else cs.onSurfaceVariant,
            )
        }
    }
}
