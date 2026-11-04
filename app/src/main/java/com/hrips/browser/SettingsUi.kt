@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal class Tone(val solid: Color, val onSolid: Color, val soft: Color, val onSoft: Color)

@Composable
internal fun toneOf(tone: Int): Tone {
    val cs = MaterialTheme.colorScheme
    return when (tone) {
        1 -> Tone(cs.secondary, cs.onSecondary, cs.secondaryContainer, cs.onSecondaryContainer)
        2 -> Tone(cs.tertiary, cs.onTertiary, cs.tertiaryContainer, cs.onTertiaryContainer)
        3 -> Tone(cs.primary, cs.onPrimary, cs.surfaceContainerHigh, cs.onSurface)
        else -> Tone(cs.primary, cs.onPrimary, cs.primaryContainer, cs.onPrimaryContainer)
    }
}

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

@Composable
internal fun PanelItem(
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = HripsShapes.XL,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    value: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
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
    val m = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f)
    if (onClick != null) {
        PressSurface(
            onClick = onClick, corners = shape.asCorners(), color = cs.surfaceContainerHigh, contentColor = cs.onSurface,
            pressedCorner = 20.dp, pressScale = 0.985f, modifier = m, enabled = enabled, content = content,
        )
    } else {
        Surface(shape = shape, color = cs.surfaceContainerHigh, contentColor = cs.onSurface, modifier = m, content = content)
    }
}

@Composable
internal fun PanelSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = HripsShapes.XL,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val haptic = LocalHapticFeedback.current
    PanelItem(
        title, modifier, shape, subtitle, enabled = enabled, icon = icon,
        onClick = {
            haptic.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onChange(!checked)
        },
        trailing = { HripsSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
internal fun DropdownRow(
    title: String,
    subtitle: String?,
    value: String,
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    shape: Shape = HripsShapes.XL,
) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box {
        PanelItem(
            title, shape = shape, subtitle = subtitle, onClick = { open = true },
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
    // Выбранная карточка: полупрозрачная заливка акцентом, обводка и кружок с галочкой в конце.
    val bg by animateColorAsState(
        if (selected) cs.primary.copy(alpha = 0.14f) else cs.surfaceContainerHigh,
        motion.fastEffectsSpec<Color>(), label = "selBg",
    )
    val ring by animateColorAsState(
        if (selected) cs.primary.copy(alpha = 0.75f) else Color.Transparent,
        motion.fastEffectsSpec<Color>(), label = "selRing",
    )
    val checkScale by animateFloatAsState(if (selected) 1f else 0f, motion.fastSpatialSpec<Float>(), label = "selCheck")
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(HripsShapes.ML)
            .background(bg)
            .border(BorderStroke(1.5.dp, ring), HripsShapes.ML)
            .selectable(
                selected = selected, interactionSource = source, indication = null, role = Role.RadioButton,
                onClick = { if (!selected) haptic.performHapticFeedback(HapticFeedbackType.ToggleOn); onClick() },
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(24.dp)
                    .graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkScale }
                    .clip(CircleShape)
                    .background(cs.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(HripsIcons.Check, null, Modifier.size(16.dp), tint = cs.onPrimary)
            }
        }
    }
}

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

@Composable
internal fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
    ) { content() }
}
