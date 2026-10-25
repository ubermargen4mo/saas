@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Общие элементы меню инструментов (шторка на телефоне и панель на планшете/ПК).
 * Правила: только primary + нейтральные поверхности (никаких secondary/tertiary), только круги и
 * скруглённые прямоугольники, группы кнопок «сегментные»: внешние углы большие, внутренние маленькие,
 * при нажатии кнопка округляется.
 */

@Immutable
internal class Corners(val tl: Dp, val tr: Dp, val br: Dp, val bl: Dp) {
    companion object {
        fun all(v: Dp) = Corners(v, v, v, v)
    }
}

/** Углы плитки `i` из `n` в сетке из `cols` колонок: большой радиус только на внешнем контуре группы. */
internal fun segCorners(n: Int, cols: Int, i: Int, big: Dp, small: Dp): Corners {
    val r = i / cols
    val c = i % cols
    val up = r > 0
    val left = c > 0
    val right = c < cols - 1 && i + 1 < n
    val down = i + cols < n
    return Corners(
        tl = if (!up && !left) big else small,
        tr = if (!up && !right) big else small,
        br = if (!down && !right) big else small,
        bl = if (!down && !left) big else small,
    )
}

@Composable
private fun morphShape(c: Corners, pressed: Boolean, to: Dp): RoundedCornerShape {
    val f by animateFloatAsState(
        if (pressed) 1f else 0f,
        MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
        label = "toolsMorph",
    )
    fun m(a: Dp): Dp = lerp(a, to, f).coerceAtLeast(0.dp)
    return RoundedCornerShape(topStart = m(c.tl), topEnd = m(c.tr), bottomEnd = m(c.br), bottomStart = m(c.bl))
}

/** Основа всех кнопок меню: морфинг углов и лёгкое «вдавливание» при нажатии, плавная смена цвета. */
@Composable
internal fun PressSurface(
    onClick: () -> Unit,
    corners: Corners,
    color: Color,
    contentColor: Color,
    pressedCorner: Dp,
    pressScale: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = morphShape(corners, pressed, pressedCorner)
    val scale by animateFloatAsState(if (pressed) pressScale else 1f, motion.fastSpatialSpec<Float>(), label = "toolsScale")
    val bg by animateColorAsState(color, motion.defaultEffectsSpec<Color>(), label = "toolsBg")
    val fg by animateColorAsState(contentColor, motion.defaultEffectsSpec<Color>(), label = "toolsFg")
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        shape = shape,
        color = bg,
        contentColor = fg,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        content = content,
    )
}

/** Каскадное появление блока: один слой на группу. */
@Composable
internal fun Modifier.toolsIn(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(Unit) {
        delay(40L + index.coerceAtMost(8) * 40L)
        progress.animateTo(1f, spec)
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        val s = 0.96f + 0.04f * p
        scaleX = s
        scaleY = s
        translationY = (1f - p) * rise
    }
}

/** Шапка: значок сайта (круг), название и адрес. */
@Composable
internal fun ToolsHeader(letter: String, title: String, subtitle: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(size).background(cs.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                letter,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = cs.onPrimaryContainer,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/** Кнопка двойной группы («Новая вкладка» + «Приватная»). */
@Composable
internal fun ToolButton(
    label: String,
    icon: ImageVector?,
    container: Color,
    content: Color,
    corners: Corners,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    onClick: () -> Unit,
) {
    PressSurface(onClick, corners, container, content, pressedCorner = height / 2, pressScale = 0.97f, modifier = modifier.height(height)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (icon != null) 14.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(22.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Плитка «иконка над подписью» в сегментной сетке. [badge] показывает счётчик (например, число вкладок). */
@Composable
internal fun SegTile(
    icon: ImageVector,
    label: String,
    corners: Corners,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
    on: Boolean = false,
    badge: Int? = null,
    labelSize: androidx.compose.ui.unit.TextUnit = 11.5.sp,
    labelLines: Int = 2,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    PressSurface(
        onClick,
        corners,
        color = if (on) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = cs.onSurface,
        pressedCorner = 24.dp,
        pressScale = 0.95f,
        modifier = modifier.height(height),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box {
                Icon(icon, null, Modifier.size(24.dp), tint = if (on) cs.primary else cs.onSurfaceVariant)
                if (badge != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 12.dp, y = (-8).dp)
                            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                            .background(cs.primary, CircleShape)
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (badge > 99) "99+" else badge.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = cs.onPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                label,
                Modifier.padding(top = 6.dp),
                maxLines = labelLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = labelSize, lineHeight = labelSize * 1.15f, letterSpacing = 0.sp),
                color = if (on) cs.onPrimaryContainer else cs.onSurface,
            )
        }
    }
}

/** Кнопка только с иконкой в сегментной группе (быстрые действия на планшете/ПК). */
@Composable
internal fun SegIcon(
    icon: ImageVector,
    description: String,
    corners: Corners,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    PressSurface(
        onClick,
        corners,
        color = if (on) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = if (on) cs.primary else cs.onSurfaceVariant,
        pressedCorner = 26.dp,
        pressScale = 0.95f,
        modifier = modifier.height(52.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(24.dp))
        }
    }
}

/** Тумблер: выключенный — скруглённый прямоугольник, включённый превращается в таблетку и заливается primary (как в M3E и в плеере). */
@Composable
internal fun ToolToggle(
    icon: ImageVector,
    title: String,
    on: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 60.dp,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val corner by animateDpAsState(if (on) height / 2 else 18.dp, motion.defaultSpatialSpec<Dp>(), label = "toggleCorner")
    val dot by animateColorAsState(
        if (on) cs.onPrimary.copy(alpha = 0.18f) else cs.surfaceContainerHighest,
        motion.defaultEffectsSpec<Color>(),
        label = "toggleDot",
    )
    val fg = if (on) cs.onPrimary else cs.onSurface
    PressSurface(
        onClick,
        Corners.all(corner),
        color = if (on) cs.primary else cs.surfaceContainerHigh,
        contentColor = fg,
        pressedCorner = 14.dp,
        pressScale = 0.96f,
        modifier = modifier.height(height).alpha(if (enabled) 1f else 0.4f),
        enabled = enabled,
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(38.dp).background(dot, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = if (on) cs.onPrimary else cs.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(
                    if (on) "Включено" else "Выключено",
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.74f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Строка списка на планшете/ПК. [selected] подсвечивает пункт с открытым подменю. */
@Composable
internal fun ToolRow(
    icon: ImageVector,
    label: String,
    corners: Corners,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    PressSurface(
        onClick,
        corners,
        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = when {
            danger -> cs.error
            selected -> cs.onPrimaryContainer
            else -> cs.onSurface
        },
        pressedCorner = 18.dp,
        pressScale = 0.985f,
        modifier = modifier.fillMaxWidth().height(46.dp).alpha(if (enabled) 1f else 0.4f),
        enabled = enabled,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, Modifier.size(21.dp), tint = if (danger) cs.error else if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant)
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (trailing != null) Icon(trailing, null, Modifier.size(18.dp), tint = if (selected) cs.primary else cs.onSurfaceVariant)
        }
    }
}

/** Строка подменю «Добавить в…»: круглая иконка, заголовок и пояснение. */
@Composable
internal fun SubRow(icon: ImageVector, title: String, subtitle: String, corners: Corners, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    PressSurface(
        onClick,
        corners,
        color = cs.surfaceContainerHigh,
        contentColor = cs.onSurface,
        pressedCorner = 18.dp,
        pressScale = 0.985f,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).background(cs.primaryContainer, HripsShapes.S), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = cs.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** Кнопка «Закрыть приватные»: нейтральная, только текст и значок цвета error. */
@Composable
internal fun DangerPill(label: String, modifier: Modifier = Modifier, height: Dp = 52.dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    PressSurface(
        onClick,
        Corners.all(height / 2),
        color = cs.surfaceContainerHigh,
        contentColor = cs.error,
        pressedCorner = 14.dp,
        pressScale = 0.97f,
        modifier = modifier.fillMaxWidth().height(height),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            Icon(HripsIcons.Trash, null, Modifier.size(20.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Данные шапки меню по вкладке. */
internal fun toolsHeaderOf(tab: Tab): Triple<String, String, String> {
    val host = tab.siteHost()
    return if (host == null) {
        Triple("H", "Новая вкладка", if (tab.isPrivate) "Приватная вкладка" else "Начальная страница")
    } else {
        val title = tab.title.ifBlank { host }
        Triple(host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•", title, if (tab.isPrivate) "$host · приватная" else host)
    }
}
