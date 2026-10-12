package com.hrips.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private const val SHAPE_IDLE = 50f
private const val SHAPE_PRESSED = 32f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val motion = MaterialTheme.motionScheme
    val percent by animateFloatAsState(if (pressed) SHAPE_PRESSED else SHAPE_IDLE, motion.fastSpatialSpec<Float>(), label = "barBtnShape")
    val press by animateFloatAsState(if (pressed) HripsMotion.PressScale else 1f, motion.fastSpatialSpec<Float>(), label = "barBtnPress")
    val base = LocalContentColor.current
    val tint by animateColorAsState(if (enabled) base else base.copy(alpha = 0.38f), motion.defaultEffectsSpec(), label = "barBtnTint")
    val wash by animateColorAsState(
        if (pressed) base.copy(alpha = 0.14f) else base.copy(alpha = 0f),
        motion.fastEffectsSpec(),
        label = "barBtnWash",
    )
    Box(
        modifier
            .size(48.dp)
            .graphicsLayer { scaleX = press; scaleY = press }
            .drawBehind {
                val color = wash
                if (color.alpha > 0f) {
                    val radius = size.minDimension * percent.coerceIn(0f, SHAPE_IDLE) / 100f
                    drawRoundRect(color, cornerRadius = CornerRadius(radius))
                }
            }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) { content() }
    }
}

@Composable
fun HripsIsland(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = HripsLayout.PhoneBarInset),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
