@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Условное оформление: слева - плоский список разделов (навигация), справа - содержимое в панелях,
// плитках и карточках. Так эти два экрана больше не похожи друг на друга.

/** Роль акцентного цвета: 0 - primary, 1 - secondary, 2 - tertiary. */
internal class Tone(val solid: Color, val onSolid: Color, val soft: Color, val onSoft: Color)

@Composable
internal fun toneOf(tone: Int): Tone {
    val cs = MaterialTheme.colorScheme
    return when (tone) {
        1 -> Tone(cs.secondary, cs.onSecondary, cs.secondaryContainer, cs.onSecondaryContainer)
        2 -> Tone(cs.tertiary, cs.onTertiary, cs.tertiaryContainer, cs.onTertiaryContainer)
        else -> Tone(cs.primary, cs.onPrimary, cs.primaryContainer, cs.onPrimaryContainer)
    }
}

// ----------------------------------------------------------------------------------------------
// Левая колонка: плоский список разделов
// ----------------------------------------------------------------------------------------------

/**
 * Строка раздела. Без карточки: значок - фигурка Material (у каждого раздела своя форма и цвет),
 * открытый раздел подсвечен «таблеткой» цвета темы, фигурка при этом заливается акцентом.
 */
@Composable
internal fun NavRow(
    icon: ImageVector,
    badge: Badge,
    tone: Int,
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    value: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val t = toneOf(tone)
    val bg = if (selected) cs.secondaryContainer else Color.Transparent
    PressSurface(
        onClick = onClick,
        corners = Corners.all(if (selected) 32.dp else 20.dp),
        color = bg,
        contentColor = if (selected) cs.onSecondaryContainer else cs.onSurface,
        pressedCorner = 14.dp,
        pressScale = 0.98f,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShapeBadge(
                icon, 44.dp, badge = badge,
                container = if (selected) t.solid else t.soft,
                content = if (selected) t.onSolid else t.onSoft,
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle, style = MaterialTheme.typography.bodySmall,
                        color = if (selected) cs.onSecondaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (value != null) {
                Text(value, style = MaterialTheme.typography.labelLarge, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
            }
        }
    }
}

/** Быстрый переключатель: квадратная плитка вроде тех, что в шторке Android. Включённая - «мягче» по форме и в цвете. */
@Composable
internal fun QuickTile(
    icon: ImageVector,
    title: String,
    state: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val radius by animateDpAsState(
        when {
            pressed -> 14.dp
            checked -> 32.dp
            else -> 20.dp
        },
        motion.fastSpatialSpec<Dp>(), label = "quickRadius",
    )
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, motion.fastSpatialSpec<Float>(), label = "quickScale")
    val bg by animateColorAsState(if (checked) cs.primaryContainer else cs.surfaceContainerHigh, motion.fastEffectsSpec<Color>(), label = "quickBg")
    val fg by animateColorAsState(if (checked) cs.onPrimaryContainer else cs.onSurfaceVariant, motion.fastEffectsSpec<Color>(), label = "quickFg")
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (enabled) 1f else 0.5f)
            .height(108.dp)
            .clip(RoundedCornerShape(radius))
            .background(bg)
            .selectable(
                selected = checked, enabled = enabled, interactionSource = source, indication = null, role = Role.Switch,
                onClick = {
                    haptic.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onToggle(!checked)
                },
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = fg)
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun QuickTilesRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

// ----------------------------------------------------------------------------------------------
// Правая часть: баннер, панели, карточки
// ----------------------------------------------------------------------------------------------

/** Баннер-шапка страницы или важная плашка: крупная фигурка, заголовок, пояснение и необязательное действие. */
@Composable
internal fun HeroBanner(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    badge: Badge = Badge.COOKIE9,
    tone: Int = 0,
    badgeSize: Dp = 72.dp,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val t = toneOf(tone)
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (leading != null) leading() else if (icon != null) ShapeBadge(icon, badgeSize, badge = badge, container = t.solid, content = t.onSolid)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = t.onSoft)
                    if (subtitle != null) {
                        Text(subtitle, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = t.onSoft.copy(alpha = 0.8f))
                    }
                }
                trailing?.invoke()
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
    if (onClick != null) {
        PressSurface(
            onClick = onClick, corners = Corners.all(32.dp), color = t.soft, contentColor = t.onSoft,
            pressedCorner = 20.dp, pressScale = 0.985f, modifier = modifier.fillMaxWidth(), content = body,
        )
    } else {
        Box(modifier.fillMaxWidth().clip(HripsShapes.XL).background(t.soft)) { body() }
    }
}

/** Контейнер для группы строк: одна крупная карточка, строки разделены тонкой линией. */
@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(HripsShapes.XL).background(MaterialTheme.colorScheme.surfaceContainerHigh), content = content)
}

@Composable
internal fun PanelDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

/** Строка панели: без значка в кружке, название обычным кеглем, значение акцентом справа. */
@Composable
internal fun PanelItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    value: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = cs.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            if (subtitle != null) {
                Text(subtitle, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.labelLarge, color = cs.primary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
        trailing?.invoke()
    }
}

@Composable
internal fun PanelSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val haptic = LocalHapticFeedback.current
    PanelItem(
        title, modifier, subtitle, enabled = enabled, icon = icon,
        onClick = {
            haptic.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onChange(!checked)
        },
        trailing = { HripsSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** Строка с выпадающим меню (как «Эффект удаления» в образце): значение справа, меню по нажатию, у выбранного галочка. */
@Composable
internal fun DropdownRow(
    title: String,
    subtitle: String?,
    value: String,
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box {
        PanelItem(
            title, subtitle = subtitle, onClick = { open = true },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value, style = MaterialTheme.typography.labelLarge, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                    Icon(HripsIcons.ChevronDown, null, Modifier.size(18.dp), tint = cs.primary)
                }
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = HripsShapes.ML) {
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                    leadingIcon = {
                        if (id == selectedId) Icon(HripsIcons.Check, null, Modifier.size(20.dp), tint = cs.primary) else Spacer(Modifier.width(20.dp))
                    },
                    onClick = { open = false; onSelect(id) },
                )
            }
        }
    }
}

/** Пояснение: тонкая рамка без заливки, значок слева. Заменяет безликий текст-примечание под группами. */
@Composable
internal fun Callout(text: String, modifier: Modifier = Modifier, icon: ImageVector = HripsIcons.Info) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, cs.outlineVariant), HripsShapes.ML)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = cs.primary)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

/** Карточка выбора со своим содержимым (поисковые системы): рамка и заливка меняются, при нажатии пружинит. */
@Composable
internal fun SelectCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, motion.fastSpatialSpec<Float>(), label = "selScale")
    val bg by animateColorAsState(if (selected) cs.primaryContainer else cs.surfaceContainerHigh, motion.fastEffectsSpec<Color>(), label = "selBg")
    val ring by animateColorAsState(if (selected) cs.primary else Color.Transparent, motion.fastEffectsSpec<Color>(), label = "selRing")
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(HripsShapes.ML)
            .background(bg)
            .border(BorderStroke(2.dp, ring), HripsShapes.ML)
            .selectable(
                selected = selected, interactionSource = source, indication = null, role = Role.RadioButton,
                onClick = { if (!selected) haptic.performHapticFeedback(HapticFeedbackType.ToggleOn); onClick() },
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Сетка из двух колонок для карточек. */
@Composable
internal fun <T> TwoColumnGrid(items: List<T>, item: @Composable (T, Modifier) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Плитка состояния (права Android): значок, название, «Разрешено» или «Не разрешено». Не разрешённая подсвечена красным. */
@Composable
internal fun StatusTile(icon: ImageVector, title: String, ok: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val bg = if (ok) cs.secondaryContainer else cs.errorContainer
    val fg = if (ok) cs.onSecondaryContainer else cs.onErrorContainer
    PressSurface(
        onClick = onClick, corners = Corners.all(24.dp), color = bg, contentColor = fg,
        pressedCorner = 14.dp, pressScale = 0.97f, modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(26.dp), tint = fg)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (ok) "Разрешено" else "Не разрешено", style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.8f))
            }
        }
    }
}

/** Плитка действия (загрузки): значок сверху, название и пояснение снизу. */
@Composable
internal fun ActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    PressSurface(
        onClick = onClick, corners = Corners.all(24.dp), color = cs.surfaceContainerHigh, contentColor = cs.onSurface,
        pressedCorner = 14.dp, pressScale = 0.97f, modifier = modifier.alpha(if (enabled) 1f else 0.5f), enabled = enabled,
    ) {
        Column(Modifier.fillMaxWidth().height(136.dp).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            ShapeBadge(icon, 40.dp, badge = Badge.COOKIE6)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Флажки-плитки для множественного выбора («Очистить данные»). */
@Composable
internal fun ToggleChip(title: String, checked: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Tile(
        title = title, selected = checked, onClick = { onToggle(!checked) }, modifier = modifier,
        role = Role.Checkbox, selectedBg = cs.secondaryContainer, selectedFg = cs.onSecondaryContainer, height = 52.dp,
    )
}

/** Появляющийся и исчезающий блок под плиткой (например, предупреждение). */
@Composable
internal fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
    ) { content() }
}
