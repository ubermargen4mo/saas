package com.hrips.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
) {
    val polygon = when (badge) {
        Badge.COOKIE6 -> MaterialShapes.Cookie6Sided
        Badge.COOKIE9 -> MaterialShapes.Cookie9Sided
        Badge.COOKIE12 -> MaterialShapes.Cookie12Sided
        Badge.CLOVER -> MaterialShapes.Clover4Leaf
        Badge.SUNNY -> MaterialShapes.Sunny
        Badge.FLOWER -> MaterialShapes.Flower
        Badge.PENTAGON -> MaterialShapes.Pentagon
        Badge.BURST -> MaterialShapes.SoftBurst
    }
    Box(modifier.size(size).background(container, polygon.toShape()), contentAlignment = Alignment.Center) {
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
        icon = icon?.let { i -> { ShapeBadge(i, 56.dp) } },
        title = title,
        text = text,
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Поле ввода: заливка вместо контура, скругление 20dp, без линии снизу. */
@Composable
fun HripsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val cs = MaterialTheme.colorScheme
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        shape = RoundedCornerShape(20.dp),
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
