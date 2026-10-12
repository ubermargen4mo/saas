package com.hrips.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Общие детали оформления Material 3 Expressive. Все экраны берут диалоги, поля ввода,
 * значки-формы и пустые состояния отсюда, чтобы выглядело одинаково.
 */

/** Фигуры из MaterialShapes для значков. Хранятся как перечисление, чтобы не тянуть тип многоугольника в каждый файл. */
enum class Badge { COOKIE6, COOKIE9, COOKIE12, CLOVER, SUNNY, FLOWER, PENTAGON, BURST }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun Badge.polygon() = when (this) {
    Badge.COOKIE6 -> MaterialShapes.Cookie6Sided
    Badge.COOKIE9 -> MaterialShapes.Cookie9Sided
    Badge.COOKIE12 -> MaterialShapes.Cookie12Sided
    Badge.CLOVER -> MaterialShapes.Clover4Leaf
    Badge.SUNNY -> MaterialShapes.Sunny
    Badge.FLOWER -> MaterialShapes.Flower
    Badge.PENTAGON -> MaterialShapes.Pentagon
    Badge.BURST -> MaterialShapes.SoftBurst
}

/** Значок на цветной фигуре (печенье, клевер, цветок...). Общий приём Expressive для иконок-акцентов. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapeBadge(
    icon: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier,
    badge: Badge = Badge.COOKIE6,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    /** Фигура «вкатывается»: растёт и доворачивается пружиной (в диалогах). */
    entrance: Boolean = false,
) {
    // Форма не пересоздаётся при каждой перекомпоновке (значки стоят в каждой строке списков)
    val shape = remember(badge) { badge.polygon().toShape() }
    // Слой и анимация нужны только значкам с «вкатыванием»: остальные рисуются без отдельного слоя
    val enter = if (entrance) remember { Animatable(0f) } else null
    if (enter != null) {
        LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
    }
    Box(
        modifier.size(size)
            .then(
                if (enter == null) Modifier else Modifier.graphicsLayer {
                    val e = enter.value
                    scaleX = 0.5f + 0.5f * e
                    scaleY = 0.5f + 0.5f * e
                    rotationZ = (1f - e) * -50f
                    alpha = (e * 2f).coerceIn(0f, 1f)
                },
            )
            .background(container, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = content)
    }
}

/** Пустое состояние списка: большая фигура со значком и пояснение. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, badge: Badge = Badge.COOKIE9) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ShapeBadge(icon, 88.dp, badge = badge, container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(title, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

/** Диалог: крупное скругление, тональный фон, при желании значок на фигуре сверху. */
@Composable
fun HripsDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: ImageVector? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        icon = icon?.let { i -> { ShapeBadge(i, 56.dp, entrance = true) } },
        title = title,
        text = text,
        shape = HripsShapes.XL,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/**
 * Поле ввода: заливка вместо контура, скругление 20dp, без линии снизу.
 * Дополнительные параметры (подсказка, значок справа, ошибка, действие клавиши Enter) необязательные.
 */
@Composable
fun HripsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    placeholder: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = label,
        placeholder = placeholder,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = HripsShapes.M,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = cs.surfaceContainerHighest,
            unfocusedContainerColor = cs.surfaceContainerHighest,
            disabledContainerColor = cs.surfaceContainerHighest,
            errorContainerColor = cs.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * Текстовая кнопка с морфингом формы при нажатии (кнопки Expressive). Раньше `shapes` стоял только у части кнопок,
 * остальные 40+ в диалогах и панелях оставались обычными.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HripsTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, shapes = ButtonDefaults.shapes(), colors = colors, content = content)
}
