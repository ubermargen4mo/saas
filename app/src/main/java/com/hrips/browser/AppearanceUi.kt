@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Карточка выбора с предпросмотром. Выбранная обводится кольцом цвета темы с зазором,
 * в углу появляется галочка, подпись окрашивается. Нажатие слегка «вдавливает» карточку.
 */
@Composable
internal fun PreviewChoiceCard(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    previewRatio: Float = 1.6f,
    preview: @Composable BoxScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, motion.fastSpatialSpec<Float>(), label = "cardScale")
    val ring by animateColorAsState(if (selected) cs.primary else Color.Transparent, motion.fastEffectsSpec<Color>(), label = "cardRing")
    val labelColor by animateColorAsState(if (selected) cs.primary else cs.onSurface, motion.fastEffectsSpec<Color>(), label = "cardLabel")

    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(HripsShapes.L)
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = null,
                role = Role.RadioButton,
                onClick = {
                    if (!selected) haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onClick()
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .border(BorderStroke(3.dp, ring), HripsShapes.L)
                .padding(6.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(previewRatio)
                    .clip(RoundedCornerShape(22.dp)),
                content = preview,
            )
            CheckBadge(selected, Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
        Text(
            label,
            Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (supporting != null) {
            Text(
                supporting,
                Modifier.padding(top = 2.dp, start = 8.dp, end = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Галочка в углу выбранной карточки. Вынесена отдельно, чтобы не ловить неявный ColumnScope внешнего Column. */
@Composable
private fun CheckBadge(visible: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(motion.fastSpatialSpec<Float>(), initialScale = 0.3f) + fadeIn(motion.fastEffectsSpec<Float>()),
        exit = scaleOut(motion.fastSpatialSpec<Float>(), targetScale = 0.3f) + fadeOut(motion.fastEffectsSpec<Float>()),
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
            Icon(HripsIcons.Check, null, Modifier.size(18.dp), tint = cs.onPrimary)
        }
    }
}

/** Ряд карточек одинаковой ширины. */
@Composable
internal fun <T> PreviewChoiceRow(
    options: List<T>,
    isSelected: (T) -> Boolean,
    label: (T) -> String,
    supporting: ((T) -> String?)? = null,
    onSelect: (T) -> Unit,
    preview: @Composable BoxScope.(T) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        options.forEach { o ->
            PreviewChoiceCard(
                selected = isSelected(o),
                label = label(o),
                supporting = supporting?.invoke(o),
                onClick = { onSelect(o) },
                modifier = Modifier.weight(1f),
                preview = { preview(o) },
            )
        }
    }
}

/**
 * Переключатель из плиток (как «Стиль панели» в Telegram): выбранная плитка становится
 * «таблеткой» в цвет темы, остальные - скруглённые прямоугольники. Углы и цвет на пружинах темы,
 * в выбранной плитке выезжает галочка. Под плитками - пояснение к выбранному варианту.
 */
@Composable
internal fun <T> TileSwitcher(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    description: (T) -> String?,
    modifier: Modifier = Modifier,
    showCheck: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, title) ->
                Tile(title, value == selected, { onSelect(value) }, Modifier.weight(1f), showCheck = showCheck)
            }
        }
        AnimatedContent(
            targetState = description(selected),
            transitionSpec = {
                fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
            },
            label = "tileDescription",
        ) { text ->
            if (text != null) {
                Text(
                    text,
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(0.dp))
            }
        }
    }
}

/** Плитка переключателя. Для одиночного выбора роль RadioButton, для флажков - Checkbox (см. [ToggleChip]). */
@Composable
internal fun Tile(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCheck: Boolean = true,
    role: Role = Role.RadioButton,
    selectedBg: Color = MaterialTheme.colorScheme.primary,
    selectedFg: Color = MaterialTheme.colorScheme.onPrimary,
    height: Dp = 56.dp,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val radius by animateDpAsState(
        when {
            pressed -> 12.dp
            selected -> 28.dp
            else -> 16.dp
        },
        motion.fastSpatialSpec<Dp>(), label = "tileRadius",
    )
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, motion.fastSpatialSpec<Float>(), label = "tileScale")
    val bg by animateColorAsState(if (selected) selectedBg else cs.surfaceContainerHigh, motion.fastEffectsSpec<Color>(), label = "tileBg")
    val fg by animateColorAsState(if (selected) selectedFg else cs.onSurfaceVariant, motion.fastEffectsSpec<Color>(), label = "tileFg")
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(bg)
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = null,
                role = role,
                onClick = {
                    if (!selected) haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onClick()
                },
            )
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = selected && showCheck,
            enter = expandHorizontally(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
            exit = shrinkHorizontally(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Icon(HripsIcons.Check, null, Modifier.padding(end = 6.dp).size(18.dp), tint = fg)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Схематичные предпросмотры вкладок. Всё нарисовано из блоков, размеры в долях ширины (u = 1%),
// поэтому макет одинаково выглядит и в узкой карточке, и в широкой. Цвета берутся из темы.
// ---------------------------------------------------------------------------------------------

private class MockColors(val on: Color, val off: Color, val bar: Color, val tile: Color, val accentSoft: Color)

/**
 * Макет окна браузера. [style] - TABS_STYLE_*, [pos] - TABS_* (расположение полосы, для режима «в строке» не важно),
 * [showTabs] = false рисует окно без панели вкладок.
 */
@Composable
internal fun TabsMock(style: Int, pos: Int, modifier: Modifier = Modifier, showTabs: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val c = MockColors(
        on = cs.primary,
        off = cs.onSurface.copy(alpha = 0.14f),
        bar = cs.surfaceContainerHighest,
        tile = cs.primaryContainer,
        accentSoft = cs.primaryContainer,
    )
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.primaryContainer, cs.tertiaryContainer))),
    ) {
        val u = maxWidth / 100
        Column(
            Modifier
                .padding(start = u * 8f, end = u * 8f, top = u * 8f)
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = u * 4f, topEnd = u * 4f))
                .background(cs.surface)
                .padding(u * 3f),
        ) {
            when {
                style == TABS_STYLE_INLINE -> {
                    if (showTabs) {
                        InlineRow(u, c)
                    } else {
                        AddressRow(u, c)
                    }
                    Spacer(Modifier.height(u * 3f))
                    PageMock(u, c)
                }
                !showTabs -> {
                    AddressRow(u, c)
                    Spacer(Modifier.height(u * 3f))
                    PageMock(u, c)
                }
                pos == TABS_LEFT -> Row(Modifier.fillMaxSize()) {
                    SideTabs(u, c)
                    Spacer(Modifier.width(u * 3f))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        AddressRow(u, c)
                        Spacer(Modifier.height(u * 3f))
                        PageMock(u, c)
                    }
                }
                pos == TABS_ABOVE -> {
                    TabsStrip(u, c)
                    Spacer(Modifier.height(u * 2f))
                    AddressRow(u, c)
                    Spacer(Modifier.height(u * 3f))
                    PageMock(u, c)
                }
                else -> {
                    AddressRow(u, c)
                    Spacer(Modifier.height(u * 2f))
                    TabsStrip(u, c)
                    Spacer(Modifier.height(u * 3f))
                    PageMock(u, c)
                }
            }
        }
    }
}

@Composable
private fun Dot(size: Dp, color: Color) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
private fun AddressRow(u: Dp, c: MockColors) {
    Row(Modifier.fillMaxWidth().height(u * 7f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 2f)) {
        Dot(u * 4f, c.off)
        Dot(u * 4f, c.off)
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(percent = 50)).background(c.bar))
        Dot(u * 4f, c.off)
    }
}

@Composable
private fun TabsStrip(u: Dp, c: MockColors) {
    Row(Modifier.fillMaxWidth().height(u * 6f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 1.5f)) {
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(u * 2f)).background(c.on))
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(u * 2f)).background(c.off))
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(u * 2f)).background(c.off))
        Dot(u * 4f, c.off)
    }
}

@Composable
private fun SideTabs(u: Dp, c: MockColors) {
    Column(Modifier.width(u * 16f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(u * 1.5f)) {
        Box(Modifier.fillMaxWidth().height(u * 6f).clip(RoundedCornerShape(u * 2f)).background(c.on))
        Box(Modifier.fillMaxWidth().height(u * 6f).clip(RoundedCornerShape(u * 2f)).background(c.off))
        Box(Modifier.fillMaxWidth().height(u * 6f).clip(RoundedCornerShape(u * 2f)).background(c.off))
    }
}

/** Вкладки в строке адреса: обычные - маленькие плашки, активная вытянута и играет роль адреса. */
@Composable
private fun InlineRow(u: Dp, c: MockColors) {
    Row(Modifier.fillMaxWidth().height(u * 7f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 1.5f)) {
        Box(Modifier.width(u * 9f).fillMaxHeight().clip(RoundedCornerShape(percent = 50)).background(c.off))
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(percent = 50)).background(c.accentSoft),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.padding(start = u * 3f).width(u * 18f).height(u * 2f).clip(CircleShape).background(c.on))
        }
        Box(Modifier.width(u * 9f).fillMaxHeight().clip(RoundedCornerShape(percent = 50)).background(c.off))
        Dot(u * 4f, c.off)
    }
}

@Composable
private fun ColumnScope.PageMock(u: Dp, c: MockColors) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(u * 3f)) {
            repeat(4) { Box(Modifier.size(u * 10f).clip(RoundedCornerShape(u * 3f)).background(c.tile)) }
        }
    }
}
