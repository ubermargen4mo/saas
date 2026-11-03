@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hrips.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val GROUP_ICONS get() = listOf(HripsIcons.GroupHome, HripsIcons.GroupFolder, HripsIcons.StarFilled, HripsIcons.GroupGlobe, HripsIcons.GroupLayers, HripsIcons.GroupGrid, HripsIcons.Person)

internal fun groupIcon(i: Int) = GROUP_ICONS[i.coerceIn(0, GROUP_ICONS.lastIndex)]

@Composable
internal fun GroupPill(privatePage: Boolean, store: Store, onGroup: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val active = store.activeTabGroup.takeIf { id -> id.isEmpty() || store.tabGroups.any { it.id == id } } ?: ""
    val activeGroup = store.tabGroups.firstOrNull { it.id == active }
    val title = if (privatePage) "Приватные" else activeGroup?.name ?: store.personalName
    val icon = if (privatePage) HripsIcons.Mask else groupIcon(activeGroup?.icon ?: store.personalIcon)
    Box {
        BarPill(rememberBarPalette(), Modifier.height(SWITCHER_TOP_H)) {
            BarButton(
                onClick = { menu = true },
                onLongClick = if (privatePage) null else {
                    { haptic.performHapticFeedback(HapticFeedbackType.LongPress); editing = active }
                },
                wrap = true,
            ) {
                Row(Modifier.padding(horizontal = 14.dp).widthIn(max = 220.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(4.dp))
                    Icon(HripsIcons.ChevronDown, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                }
            }
        }
        SegMenuPopup(
            visible = menu,
            onDismiss = { menu = false },
            position = rememberAnchorMenuPosition(),
            origin = TransformOrigin(0.5f, 0f),
        ) {
            val act: (() -> Unit) -> () -> Unit = { action -> { menu = false; action() } }
            val rows = listOf(TabGroupRow("", store.personalName, store.personalIcon)) +
                store.tabGroups.map { TabGroupRow(it.id, it.name, it.icon) }
            SegMenuColumn(
                listOf(
                    rows.map { g ->
                        MenuEntry(groupIcon(g.icon), g.name, checked = !privatePage && g.id == active, onClick = act { onGroup(g.id) })
                    },
                    buildList {
                        add(MenuEntry(HripsIcons.Add, "Новая группа", onClick = act { creating = true }))
                        if (!privatePage) add(MenuEntry(HripsIcons.Settings, "Изменить группу", onClick = act { editing = active }))
                    },
                ),
            )
        }
    }
    editing?.let { id ->
        val g = store.tabGroups.firstOrNull { it.id == id }
        GroupDialog(
            title = "Группа вкладок",
            initialName = g?.name ?: store.personalName,
            initialIcon = g?.icon ?: store.personalIcon,
            onSave = { n, i -> store.editTabGroup(id, n, i); editing = null },
            onDelete = if (id.isEmpty()) null else ({ store.deleteTabGroup(id); editing = null }),
            onDismiss = { editing = null },
        )
    }
    if (creating) {
        GroupDialog(
            title = "Новая группа",
            initialName = "",
            initialIcon = 1,
            onSave = { n, i -> val g = store.addTabGroup(n, i); creating = false; onGroup(g.id) },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
}

private class TabGroupRow(val id: String, val name: String, val icon: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupDialog(
    title: String,
    initialName: String,
    initialIcon: Int,
    onSave: (String, Int) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableIntStateOf(initialIcon) }
    val cs = MaterialTheme.colorScheme
    HripsDialog(
        onDismissRequest = onDismiss,
        icon = groupIcon(icon),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HripsField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    singleLine = true,
                    label = { Text("Название") },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GROUP_ICONS.forEachIndexed { i, ic ->
                        val sel = i == icon
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (sel) cs.primaryContainer else cs.surfaceContainerHighest)
                                .clickable { icon = i }
                                .semantics { selected = sel },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(ic, null, Modifier.size(22.dp), tint = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant)
                        }
                    }
                }
                if (onDelete != null) DangerPill("Удалить группу", height = 44.dp, onClick = onDelete)
            }
        },
        confirmButton = { HripsTextButton(onClick = { onSave(name, icon) }) { Text("Готово") } },
        dismissButton = { HripsTextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
