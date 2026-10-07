package com.hrips.browser

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Цвета групп вкладок. В группе хранится индекс, а не сам цвет. */
val GroupColors = listOf(
    Color(0xFF6750A4), Color(0xFF1E88E5), Color(0xFF2E7D32),
    Color(0xFFF9A825), Color(0xFFE53935), Color(0xFF00897B),
)

/** Группа вкладок: название и цвет можно менять, id остаётся прежним (по нему вкладки привязаны к группе). */
class TabGroup(val id: String, name: String, color: Int) {
    var name by mutableStateOf(name)
    var color by mutableIntStateOf(color)
    val tint: Color get() = GroupColors[color.coerceIn(0, GroupColors.lastIndex)]
}

/** Закрытая вкладка: снимок (адрес, заголовок, история страницы) и группа, в которой она была. */
class ClosedTab(val snap: TabSnap, val group: TabGroup?)

private fun hostOf(url: String) = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url

/** Полоса фильтра по группам над списком вкладок: «Все» и по чипу на группу. Повторное нажатие или удержание - изменить группу. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupChips(
    groups: List<TabGroup>,
    counts: Map<String, Int>,
    total: Int,
    selected: String?,
    onSelect: (String?) -> Unit,
    onEdit: (TabGroup) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "all") {
            Surface(
                onClick = { onSelect(null) },
                shape = CircleShape,
                color = if (selected == null) cs.secondaryContainer else cs.surfaceContainerHigh,
                contentColor = if (selected == null) cs.onSecondaryContainer else cs.onSurfaceVariant,
            ) {
                Text("Все · $total", Modifier.padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        items(groups, key = { it.id }) { g ->
            val sel = selected == g.id
            Surface(
                shape = CircleShape,
                color = if (sel) g.tint.copy(alpha = 0.28f) else cs.surfaceContainerHigh,
                contentColor = cs.onSurface,
                border = if (sel) BorderStroke(2.dp, g.tint) else null,
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(onClick = { if (sel) onEdit(g) else onSelect(g.id) }, onLongClick = { onEdit(g) }),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(g.tint, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(g.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                    Spacer(Modifier.width(6.dp))
                    Text((counts[g.id] ?: 0).toString(), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Диалог группы: название и цвет. Для существующей группы ещё «Разгруппировать» и «Закрыть вкладки группы».
 */
@Composable
fun GroupDialog(
    title: String,
    initialName: String,
    initialColor: Int,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Int) -> Unit,
    onUngroup: (() -> Unit)? = null,
    onCloseAll: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableIntStateOf(initialColor) }
    HripsDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                HripsField(name, { name = it.take(40) }, singleLine = true, label = { Text("Название") })
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupColors.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(32.dp).background(c, CircleShape).clip(CircleShape).clickable { color = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i == color) Icon(HripsIcons.Check, "Выбран", Modifier.size(18.dp), tint = Color.White)
                        }
                    }
                }
                if (onUngroup != null) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onUngroup) { Text("Разгруппировать") }
                }
                if (onCloseAll != null) {
                    TextButton(onClick = onCloseAll) { Text("Закрыть вкладки группы", color = cs.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim().ifEmpty { "Группа" }, color) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Шторка «Недавно закрытые»: нажатие возвращает вкладку вместе с историей страницы и группой. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosedTabsSheet(browser: Browser, onDismiss: () -> Unit, onRestored: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val list = browser.closedTabs.toList()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Недавно закрытые", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (list.isNotEmpty()) {
                    TextButton(onClick = { browser.closedTabs.clear(); onDismiss() }) { Text("Очистить") }
                }
            }
            if (list.isEmpty()) {
                EmptyState(HripsIcons.History, "Пока пусто", "Закрытые вкладки появятся здесь, их можно будет вернуть")
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list) { c ->
                        val label = c.snap.title.ifBlank { hostOf(c.snap.url) }
                        Surface(
                            onClick = { browser.reopen(c); onRestored() },
                            shape = RoundedCornerShape(22.dp),
                            color = cs.surfaceContainerHigh,
                            border = c.group?.let { BorderStroke(2.dp, it.tint.copy(alpha = 0.8f)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Favicon(c.snap.url, 32.dp) {
                                    Surface(shape = CircleShape, color = cs.surfaceVariant, modifier = Modifier.size(32.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(label.firstOrNull()?.uppercase() ?: "", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                                        }
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text(hostOf(c.snap.url), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                                c.group?.let { g ->
                                    Spacer(Modifier.width(8.dp))
                                    Box(Modifier.size(10.dp).background(g.tint, CircleShape))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
