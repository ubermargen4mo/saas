package com.hrips.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Одно системное право для сводки: название, значок и выдано ли оно. */
internal class SystemPerm(val title: String, val icon: ImageVector, val granted: Boolean)

/**
 * Сводка системных прав браузера: заголовок со счётом и ряд колец со значками (как индикаторы в Pixel).
 * Зелёное кольцо - право выдано, жёлтое - нет. Нажатие на кольцо открывает системные настройки приложения.
 */
@Composable
internal fun PermissionsSummary(perms: List<SystemPerm>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val ok = perms.count { it.granted }
    Column(
        modifier.fillMaxWidth().clip(HripsShapes.XL).background(cs.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Права браузера в Android", style = MaterialTheme.typography.titleMedium)
            Text(
                if (ok == perms.size) "Все права выданы" else "Выдано $ok из ${perms.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (ok == perms.size) StatusOk else cs.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            perms.forEach { p -> PermissionRing(p, onClick, Modifier.weight(1f)) }
        }
        Text(
            "Без системного права сайт не получит доступ, даже если вы разрешили его здесь. Нажмите на кольцо, чтобы изменить.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun PermissionRing(p: SystemPerm, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tone = if (p.granted) StatusOk else StatusWarn
    val track = cs.onSurface.copy(alpha = 0.10f)
    PressSurface(
        onClick = onClick,
        corners = Corners.all(20.dp),
        color = cs.surfaceContainerHigh,
        contentColor = cs.onSurface,
        pressedCorner = 12.dp,
        pressScale = 0.96f,
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(64.dp)) {
                    val w = 4.dp.toPx()
                    val tl = Offset(w / 2, w / 2)
                    val arc = Size(size.width - w, size.height - w)
                    drawArc(track, 0f, 360f, false, tl, arc, style = Stroke(w))
                    // У выданного права кольцо замкнуто, у невыданного остаётся дуга в четверть: видно, что «не хватает».
                    drawArc(tone, -90f, if (p.granted) 360f else 90f, false, tl, arc, style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
                Box(Modifier.size(46.dp).background(cs.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(p.icon, null, Modifier.size(24.dp), tint = cs.onSurface)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    if (p.granted) "Разрешено" else "Нет доступа",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (p.granted) StatusOk else StatusWarn,
                    maxLines = 1,
                )
            }
        }
    }
}
