package com.hrips.browser

import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Управление мышью. Правая кнопка = долгое нажатие: [secondaryClick] вызывает то же действие, что `onLongClick`,
 * без удержания. Средняя кнопка закрывает вкладку ([middleClick]). Колесо листает полосу вкладок ([wheelScroll]).
 * Нажатие мыши перехватывается на проходе Initial и гасится, вложенные кнопки обычного клика не получают.
 */
@Composable
internal fun Modifier.secondaryClick(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    mouseButton(enabled, onClick) { it.isSecondaryPressed }

@Composable
internal fun Modifier.middleClick(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    mouseButton(enabled, onClick) { it.isTertiaryPressed }

@Composable
private fun Modifier.mouseButton(
    enabled: Boolean,
    onClick: () -> Unit,
    pressed: (PointerButtons) -> Boolean,
): Modifier {
    if (!enabled) return this
    val now by rememberUpdatedState(onClick)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Press && e.changes.any { it.type == PointerType.Mouse } && pressed(e.buttons)) {
                    e.changes.forEach { it.consume() }
                    now()
                }
            }
        }
    }
}

/** Колесо мыши листает горизонтальный список (обычно колесо крутит только вертикальные). */
@Composable
internal fun Modifier.wheelScroll(state: ScrollableState): Modifier {
    val scope = rememberCoroutineScope()
    return this.pointerInput(state) {
        val step = 56.dp.toPx()
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type != PointerEventType.Scroll) continue
                val d = e.changes.fold(Offset.Zero) { acc, c -> acc + c.scrollDelta }
                val dx = if (d.x != 0f) d.x else d.y
                if (dx == 0f) continue
                e.changes.forEach { it.consume() }
                scope.launch { state.scrollBy(dx * step) }
            }
        }
    }
}

/** Сочетания клавиш: активность отдаёт сюда нажатия раньше страницы, обработчик возвращает true, если нажатие его. */
object KeyShortcuts {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null
}
