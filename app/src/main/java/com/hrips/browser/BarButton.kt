package com.hrips.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Общее состояние нажатия пилюли. Кнопка внутри пилюли не сжимает себя, а сжимает пилюлю целиком
 * (кружок вместе с иконкой): значение читается в graphicsLayer пилюли, поэтому без перекомпозиции.
 */
internal class PillPress {
    val scale = Animatable(1f)
}

internal val LocalPillPress = compositionLocalOf<PillPress?> { null }

private const val PILL_PRESSED = 0.88f

/**
 * Кнопка панели без подложки: на нажатие реагирует сама иконка. Вниз она быстро сжимается и тускнеет,
 * вверх возвращается с лёгким пружинным перелётом. Всё идёт в graphicsLayer, то есть без перекомпозиции за кадр.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun BarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Быстрый тап длится десятки миллисекунд, пружина бы не успела показаться: держим «нажато» не меньше 90 мс.
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) held = true else { delay(90); held = false }
    }
    val motion = MaterialTheme.motionScheme
    val host = LocalPillPress.current
    if (host != null) {
        LaunchedEffect(host, held) {
            host.scale.animateTo(
                if (held) PILL_PRESSED else 1f,
                if (held) HripsMotion.pressDown() else HripsMotion.pressRelease(),
            )
        }
    }
    // Внутри пилюли сжимается она вся; без пилюли (кнопки в адресной строке) сжимается иконка.
    val press by animateFloatAsState(
        if (held && host == null) 0.7f else 1f,
        if (held) HripsMotion.pressDown() else HripsMotion.pressRelease(),
        label = "barBtnPress",
    )
    val dim by animateFloatAsState(if (held) (if (host != null) 0.7f else 0.5f) else 1f, motion.fastEffectsSpec(), label = "barBtnDim")
    val base = LocalContentColor.current
    val tint by animateColorAsState(if (enabled) base else base.copy(alpha = 0.38f), motion.defaultEffectsSpec(), label = "barBtnTint")
    Box(
        modifier
            .size(48.dp)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                // Долгое нажатие работает и у неактивной кнопки (например, «назад» без истории открывает список вкладок).
                enabled = enabled || onLongClick != null,
                role = Role.Button,
                onClick = { if (enabled) onClick() },
                onLongClick = onLongClick,
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
