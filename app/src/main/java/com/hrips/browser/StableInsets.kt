package com.hrips.browser

import android.os.SystemClock
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.LayoutDirection.Ltr
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Четыре отступа в пикселях. */
private data class Edges(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Фиксированные отступы: отдают ровно то, что в них положили, и не зависят от текущих insets окна. */
private class FixedInsets(private val e: Edges) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection) = e.left
    override fun getTop(density: Density) = e.top
    override fun getRight(density: Density, layoutDirection: LayoutDirection) = e.right
    override fun getBottom(density: Density) = e.bottom
}

/** Сколько после возврата фокуса окну даём системным панелям «устаканиться». */
private const val RETURN_WINDOW_MS = 600L

/** Сколько ждём, не вернётся ли значение обратно, прежде чем принять изменение в окне возврата. */
private const val SETTLE_MS = 150L

/**
 * Системные отступы, которые не прыгают, пока поверх нас системное окно (выбор файлов, шторка и т. п.).
 *
 * Зачем. Пока окно приложения не в фокусе, Android на время убирает inset строки состояния, а при возврате
 * возвращает его через несколько десятков мс. Размер GeckoView при этом меняется туда-обратно
 * (в журнале 2560x1404 -> 1464 -> 1404), и каждое изменение размера это синхронный `surfaceChanged`
 * -> `syncResumeResizeCompositor`, в котором главный поток ждёт GPU-процесс Gecko. Если GPU-процесс в этот момент
 * пересоздаётся (в фоне Android нередко его убивает), главный поток зависает, и систему приходится ждать ANR.
 *
 * Что делаем.
 *  - Пока окно не в фокусе, отдаём последнее известное значение и не следим за живыми insets.
 *  - Первое значение после старта берём как есть (без задержки), чтобы запуск выглядел как раньше.
 *  - В первые [RETURN_WINDOW_MS] мс после возврата фокуса принимаем изменение только если оно продержалось
 *    [SETTLE_MS] мс: кратковременный «ноль» перед восстановлением панели отсекается.
 *  - Всё остальное время (поворот, вход и выход из полноэкранного режима) отступы меняются сразу, как раньше.
 */
@Composable
internal fun rememberStableInsets(live: WindowInsets, name: String): WindowInsets {
    val density = LocalDensity.current
    val focused = LocalWindowInfo.current.isWindowFocused

    fun read() = Edges(
        live.getLeft(density, Ltr),
        live.getTop(density),
        live.getRight(density, Ltr),
        live.getBottom(density),
    )

    // null: ещё ни разу не было окна в фокусе, отдаём живые значения.
    var stable by remember { mutableStateOf<Edges?>(null) }

    LaunchedEffect(focused, density) {
        if (!focused) return@LaunchedEffect
        val since = SystemClock.uptimeMillis()
        snapshotFlow { read() }.collectLatest { now ->
            val current = stable
            when {
                current == null -> stable = now
                now == current -> Unit
                SystemClock.uptimeMillis() - since < RETURN_WINDOW_MS -> {
                    delay(SETTLE_MS)
                    AppLog.i("Insets") { "$name: $current -> $now (после возврата фокуса, устоялось)" }
                    stable = now
                }
                else -> {
                    AppLog.i("Insets") { "$name: $current -> $now" }
                    stable = now
                }
            }
        }
    }

    val shown = stable
    return if (shown == null) live else remember(shown) { FixedInsets(shown) }
}
