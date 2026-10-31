package com.hrips.browser

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Статусные элементы в духе «Безопасность» из Pixel: кольцо из сегментов вокруг круга с галочкой и строки
 * со значком состояния (зелёная галочка / жёлтый «!»). Используются на экране «Конфиденциальность» и в окне
 * «Информация о сайте».
 */

internal val StatusOk = Color(0xFF5DBB7B)
internal val StatusWarn = Color(0xFFF2C94C)
internal val StatusBad = Color(0xFFEF6B5F)
private val StatusInk = Color(0xFF14231A)

/**
 * Кольцо из [total] дуг: первые [filled] закрашены цветом [arc], остальные приглушены. В центре круг цвета [center]
 * со значком [icon].
 */
@Composable
internal fun StatusRing(
    filled: Int,
    total: Int,
    center: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    arc: Color = center,
    size: Dp = 112.dp,
) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = size.toPx() * 0.075f
            val inset = stroke / 2f
            val n = total.coerceAtLeast(1)
            val seg = 360f / n
            val gap = if (n == 1) 0f else 16f
            for (i in 0 until n) {
                drawArc(
                    color = if (i < filled) arc else dim,
                    startAngle = -90f + i * seg + gap / 2f,
                    sweepAngle = seg - gap,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Box(Modifier.size(size * 0.68f).clip(CircleShape).background(center), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(size * 0.34f), tint = StatusInk)
        }
    }
}

/** Строка состояния: значок (зелёная галочка или жёлтый «!»), название и пояснение. Нажатие необязательно. */
@Composable
internal fun StatusRow(
    ok: Boolean,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(HripsShapes.M)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (ok) StatusOk else StatusWarn), contentAlignment = Alignment.Center) {
            Icon(if (ok) HripsIcons.Check else HripsIcons.Alert, null, Modifier.size(16.dp), tint = StatusInk)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * Кнопка обновления на экране «О программе»: акцентная, на всю ширину; над ней строка состояния
 * («Установлена последняя версия», «Доступна версия …», ошибка). Действие зависит от состояния [Updater].
 */
@Composable
internal fun UpdateButton(app: HripsApp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val st = Updater.state
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val note: Pair<String, Boolean>? = when (st) {
            is Updater.State.UpToDate -> "Установлена последняя версия" to true
            is Updater.State.Failed -> st.message.ifBlank { "Не удалось проверить обновления" } to false
            is Updater.State.Available -> "Доступна версия ${st.info.versionName}" + (if (st.info.size > 0) " (${st.info.size / 1_000_000} МБ)" else "") to false
            is Updater.State.Ready -> "Файл ${st.info.versionName} загружен и проверен" to true
            else -> null
        }
        note?.let { (text, ok) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (ok) HripsIcons.Check else HripsIcons.Alert, null, Modifier.size(18.dp), tint = if (ok) StatusOk else StatusWarn)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
        }
        val (label, action) = when (st) {
            is Updater.State.Checking -> "Проверяю…" to null
            is Updater.State.Available -> "Скачать ${st.info.versionName}" to { Updater.download(app, st.info) }
            is Updater.State.Downloading -> "Скачиваю ${st.percent}%" to null
            is Updater.State.Ready -> "Установить ${st.info.versionName}" to { Updater.install(context) }
            else -> "Проверить обновления" to { Updater.check(app, manual = true) }
        }
        Surface(
            onClick = { action?.invoke() },
            enabled = action != null,
            shape = CircleShape,
            color = cs.primary,
            contentColor = cs.onPrimary,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Строка с ползунком: название и значение справа (акцентом), ползунок под ними. Сегмент группы, как у остальных строк. */
@Composable
internal fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    shape: Shape,
    onChange: (Float) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = shape, color = cs.surfaceContainerHigh, contentColor = cs.onSurface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(valueText, style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
            Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        }
    }
}
