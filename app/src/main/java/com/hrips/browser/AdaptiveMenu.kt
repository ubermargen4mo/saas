package com.hrips.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

private val MenuWidth = 380.dp
private val MenuTop = 100.dp
private val MenuMargin = 12.dp
private val MenuShadowPad = 12.dp

private class CornerMenuPosition(private val top: Int, private val inset: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (windowSize.width - popupContentSize.width - inset).coerceAtLeast(0)
        val y = top.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

@Composable
internal fun AdaptiveMenu(
    wide: Boolean,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    if (!wide) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = cs.surfaceContainerLow,
            shape = HripsShapes.XL,
            content = content,
        )
        return
    }
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val position = remember(density, statusTop) {
        with(density) {
            CornerMenuPosition(statusTop + MenuTop.roundToPx() - MenuShadowPad.roundToPx(), (MenuMargin - MenuShadowPad).roundToPx())
        }
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 900f)) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - MenuTop - 48.dp).coerceAtLeast(240.dp)
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            Modifier
                .padding(MenuShadowPad)
                .graphicsLayer {
                    val p = progress.value
                    transformOrigin = TransformOrigin(1f, 0f)
                    val sc = 0.85f + 0.15f * p
                    scaleX = sc
                    scaleY = sc
                    alpha = (p * 1.8f).coerceIn(0f, 1f)
                },
        ) {
            Surface(
                modifier = Modifier.width(MenuWidth).heightIn(max = maxHeight),
                shape = RoundedCornerShape(28.dp),
                color = cs.surfaceContainerLow,
                contentColor = cs.onSurface,
                shadowElevation = 8.dp,
                border = BorderStroke(0.5.dp, cs.onSurface.copy(alpha = 0.10f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), content = content)
            }
        }
    }
}
