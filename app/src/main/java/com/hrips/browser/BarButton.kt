package com.hrips.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Общее состояние нажатия пилюли: кнопка внутри сжимает пилюлю целиком, а не только себя.
 * Значение читается в graphicsLayer пилюли, поэтому без перекомпозиции.
 */
internal class PillPress {
    val scale = Animatable(1f)
}

internal val LocalPillPress = compositionLocalOf<PillPress?> { null }

private const val PILL_PRESSED = 0.88f

/** Кнопка панели без подложки: при нажатии сжимается и тускнеет иконка (или вся пилюля), всё через graphicsLayer. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun BarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    // По размеру содержимого (не меньше 44 dp): для широких кнопок вроде счётчика вкладок.
    wrap: Boolean = false,
    // Не принимает нажатия: касание проходит к тому, что под кнопкой (у свёрнутой панели это «развернуть»).
    inert: Boolean = false,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Быстрый тап короче пружины: держим «нажато» не меньше 90 мс.
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) held = true else { delay(90); held = false }
    }
    val motion = MaterialTheme.motionScheme
    val host = LocalPillPress.current
    val pressSpec = if (held) HripsMotion.pressDown<Float>() else HripsMotion.pressRelease<Float>()
    if (host != null) {
        LaunchedEffect(host, held) { host.scale.animateTo(if (held) PILL_PRESSED else 1f, pressSpec) }
    }
    // Внутри пилюли сжимается она вся, без пилюли (адресная строка) - иконка.
    val press by animateFloatAsState(if (held && host == null) 0.7f else 1f, pressSpec, label = "barBtnPress")
    val dim by animateFloatAsState(if (held) (if (host != null) 0.7f else 0.5f) else 1f, motion.fastEffectsSpec(), label = "barBtnDim")
    val hovered by source.collectIsHoveredAsState()
    val hoverAlpha by animateFloatAsState(if (hovered && enabled && !inert) 1f else 0f, motion.fastEffectsSpec(), label = "barBtnHover")
    val hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    val base = LocalContentColor.current
    val tint by animateColorAsState(if (enabled) base else base.copy(alpha = 0.38f), motion.defaultEffectsSpec(), label = "barBtnTint")
    Box(
        modifier
            .then(if (wrap) Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp) else Modifier.size(48.dp))
            .drawBehind { if (hoverAlpha > 0.01f) drawRoundRect(hoverColor, cornerRadius = CornerRadius(size.minDimension / 2f), alpha = hoverAlpha) }
            .then(if (inert) Modifier else Modifier.hoverable(source).pointerHoverIcon(PointerIcon.Hand))
            .secondaryClick(enabled = !inert && onLongClick != null) { onLongClick?.invoke() }
            .then(
                if (inert) Modifier else Modifier.combinedClickable(
                    interactionSource = source,
                    indication = null,
                    // Долгое нажатие работает и у неактивной кнопки («назад» без истории открывает список вкладок).
                    enabled = enabled || onLongClick != null,
                    role = Role.Button,
                    onClick = { if (enabled) onClick() },
                    onLongClick = onLongClick,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer {
                scaleX = press
                scaleY = press
                alpha = dim
            },
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides tint) { content() }
        }
    }
}
