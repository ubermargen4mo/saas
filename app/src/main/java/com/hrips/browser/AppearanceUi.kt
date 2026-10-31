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

/** Выбор без волны нажатия; при смене значения лёгкая вибрация. Источник нажатий общий с анимацией «вдавливания». */
@Composable
private fun Modifier.choice(selected: Boolean, source: MutableInteractionSource, role: Role, onClick: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    return selectable(selected = selected, interactionSource = source, indication = null, role = role) {
        if (!selected) haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
        onClick()
    }
}

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
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, motion.fastSpatialSpec<Float>(), label = "cardScale")
    val ring by animateColorAsState(if (selected) cs.primary else Color.Transparent, motion.fastEffectsSpec<Color>(), label = "cardRing")
    val labelColor by animateColorAsState(if (selected) cs.primary else cs.onSurface, motion.fastEffectsSpec<Color>(), label = "cardLabel")

    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(HripsShapes.L)
            .choice(selected, source, Role.RadioButton, onClick),
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
            .choice(selected, source, role, onClick)
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
// Схематичные предпросмотры вкладок. Без фона: только окно браузера, нарисованное блоками.
// Размеры сняты со скриншота планшета (2000 px в ширину) и заданы в долях ширины (u = 1%), поэтому
// макет одинаково выглядит и в узкой карточке, и в широкой. Цвета берутся из темы.
//   панель инструментов: высота 3.1u, кнопки-кружки 3.1u, адресная строка 43.6u
//   вкладка: 19u x 2.4u в полосе; 17u x 3.1u в боковой панели шириной 18u
//   плитки главной страницы: 5.6u
// ---------------------------------------------------------------------------------------------

private class MockColors(val on: Color, val off: Color, val tile: Color)

private const val BAR_H = 3.1f
private const val STRIP_H = 2.4f
private const val SIDE_W = 18f

/**
 * Макет окна браузера. [style] - TABS_STYLE_*, [pos] - TABS_* (расположение полосы, для режима «в строке» не важно),
 * [showTabs] = false рисует окно без панели вкладок.
 */
@Composable
internal fun TabsMock(style: Int, pos: Int, modifier: Modifier = Modifier, showTabs: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val c = MockColors(
        on = cs.primary,
        off = cs.onSurface.copy(alpha = 0.12f),
        tile = cs.primaryContainer,
    )
    BoxWithConstraints(modifier.fillMaxSize()) {
        val u = maxWidth / 100
        Column(Modifier.fillMaxSize().padding(horizontal = u * 1.5f, vertical = u * 1.5f)) {
            when {
                style == TABS_STYLE_INLINE -> {
                    if (showTabs) InlineRow(u, c) else ToolbarRow(u, c)
                    PageMock(u, c)
                }
                !showTabs -> {
                    ToolbarRow(u, c)
                    PageMock(u, c)
                }
                pos == TABS_LEFT -> {
                    ToolbarRow(u, c)
                    Spacer(Modifier.height(u * 1f))
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        SideTabs(u, c)
                        Column(Modifier.weight(1f).fillMaxHeight()) { PageMock(u, c) }
                    }
                }
                pos == TABS_ABOVE -> {
                    TabsStrip(u, c)
                    Spacer(Modifier.height(u * 1f))
                    ToolbarRow(u, c)
                    PageMock(u, c)
                }
                else -> {
                    ToolbarRow(u, c)
                    Spacer(Modifier.height(u * 1f))
                    TabsStrip(u, c)
                    PageMock(u, c)
                }
            }
        }
    }
}

@Composable
private fun Dot(size: Dp, color: Color) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
private fun Pill(width: Dp, height: Dp, color: Color) = Box(Modifier.width(width).height(height).clip(CircleShape).background(color))

/** Левая группа кнопок: два кружка и пилюля (боковая панель, домой, назад и вперёд). */
@Composable
private fun LeftButtons(u: Dp, c: MockColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(u * 0.8f), verticalAlignment = Alignment.CenterVertically) {
        Dot(u * BAR_H, c.off)
        Dot(u * BAR_H, c.off)
        Pill(u * 5.9f, u * BAR_H, c.off)
    }
}

/** Правая группа кнопок: две пилюли (поиск по картинке и QR, затем «+», вкладки, расширения и меню). */
@Composable
private fun RightButtons(u: Dp, c: MockColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(u * 0.8f), verticalAlignment = Alignment.CenterVertically) {
        Pill(u * 5.9f, u * BAR_H, c.off)
        Pill(u * 11.5f, u * BAR_H, c.off)
    }
}

@Composable
private fun ToolbarRow(u: Dp, c: MockColors) {
    Row(Modifier.fillMaxWidth().height(u * BAR_H), verticalAlignment = Alignment.CenterVertically) {
        LeftButtons(u, c)
        Spacer(Modifier.weight(1f))
        Pill(u * 43.6f, u * BAR_H, c.off)
        Spacer(Modifier.weight(1f))
        RightButtons(u, c)
    }
}

@Composable
private fun TabsStrip(u: Dp, c: MockColors) {
    Row(Modifier.fillMaxWidth().height(u * STRIP_H), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 0.8f)) {
        Box(Modifier.width(u * 19f).fillMaxHeight().clip(RoundedCornerShape(u * 0.9f)).background(c.on))
        Box(Modifier.width(u * 19f).fillMaxHeight().clip(RoundedCornerShape(u * 0.9f)).background(c.off))
        Dot(u * STRIP_H, c.off)
    }
}

/** Боковая панель вкладок: две вкладки сверху, «Новая вкладка» внизу. */
@Composable
private fun SideTabs(u: Dp, c: MockColors) {
    Column(Modifier.width(u * SIDE_W).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(u * 0.8f)) {
        Box(Modifier.width(u * 17f).height(u * BAR_H).clip(RoundedCornerShape(u * 1f)).background(c.on))
        Box(Modifier.width(u * 17f).height(u * BAR_H).clip(RoundedCornerShape(u * 1f)).background(c.off))
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(start = u * 1f, bottom = u * 0.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 1f)) {
            Dot(u * 1.4f, c.off)
            Pill(u * 8f, u * 0.8f, c.off)
        }
    }
}

/** Вкладки в строке адреса: обычные - маленькие плашки, активная вытянута и играет роль адреса. */
@Composable
private fun InlineRow(u: Dp, c: MockColors) {
    Row(Modifier.fillMaxWidth().height(u * BAR_H), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 0.8f)) {
        LeftButtons(u, c)
        Pill(u * 7f, u * BAR_H, c.off)
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(c.tile),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.padding(start = u * 2f).width(u * 12f).height(u * 0.9f).clip(CircleShape).background(c.on))
        }
        Pill(u * 7f, u * BAR_H, c.off)
        RightButtons(u, c)
    }
}

/** Главная страница: логотип и три плитки сайтов, по центру оставшегося места. */
@Composable
private fun ColumnScope.PageMock(u: Dp, c: MockColors) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Pill(u * 20f, u * 4.5f, c.off)
            Spacer(Modifier.height(u * 4f))
            Row(horizontalArrangement = Arrangement.spacedBy(u * 3f)) {
                repeat(3) { i ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(u * 1f)) {
                        Box(Modifier.size(u * 5.6f).clip(RoundedCornerShape(u * 1.8f)).background(if (i == 0) c.tile else c.off))
                        Pill(u * 4f, u * 0.7f, c.off)
                    }
                }
            }
        }
    }
}
