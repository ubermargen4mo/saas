package com.hrips.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Кнопка-значок панели в духе Expressive: круг при нажатии «проседает» в скруглённый квадрат и чуть сжимается,
 * а недоступная плавно тускнеет (а не мигает), когда, например, нельзя пойти назад.
 * Размер задаёт вызывающий (по умолчанию 48dp). Значок берёт цвет из [LocalContentColor].
 */
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
    // 50% = круг; нажатая кнопка - скруглённый квадрат
    val percent by animateFloatAsState(if (pressed) 32f else 50f, motion.fastSpatialSpec<Float>(), label = "barBtnShape")
    val press by animateFloatAsState(if (pressed) HripsMotion.PressScale else 1f, motion.fastSpatialSpec<Float>(), label = "barBtnPress")
    val base = LocalContentColor.current
    val tint by animateColorAsState(if (enabled) base else base.copy(alpha = 0.38f), motion.defaultEffectsSpec(), label = "barBtnTint")
    val wash by animateColorAsState(
        if (pressed) base.copy(alpha = 0.14f) else base.copy(alpha = 0f),
        motion.fastEffectsSpec(),
        label = "barBtnWash",
    )
    val shape = RoundedCornerShape(percent.toInt().coerceIn(0, 50))
    Box(
        modifier
            .then(Modifier.size(48.dp))
            .graphicsLayer { scaleX = press; scaleY = press }
            .clip(shape)
            .background(wash)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) { content() }
    }
}
