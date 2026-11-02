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

private data class Edges(val left: Int, val top: Int, val right: Int, val bottom: Int)

private class FixedInsets(private val e: Edges) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection) = e.left
    override fun getTop(density: Density) = e.top
    override fun getRight(density: Density, layoutDirection: LayoutDirection) = e.right
    override fun getBottom(density: Density) = e.bottom
}

private const val RETURN_WINDOW_MS = 600L

private const val SETTLE_MS = 150L

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
