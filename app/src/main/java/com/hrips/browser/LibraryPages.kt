package com.hrips.browser

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ListPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    listModifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            PageTopBar(title, onBack, scroll, actions)
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (header != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = HripsLayout.PageGutter), contentAlignment = Alignment.TopCenter) { header() }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().then(listModifier),
                contentPadding = PaddingValues(start = HripsLayout.PageGutter, end = HripsLayout.PageGutter, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

@Composable
internal fun PageSearchField(hint: String, query: String, onChange: (String) -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(HripsIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 16.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(HripsIcons.Close, "Очистить поиск") }
            }
        }
    }
}

@Composable
private fun PageHeaderText(text: String) {
    Row(
        Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EntryRow(e: Entry, shape: RoundedCornerShape, showTime: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Box(Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth()) {
        PressSurface(
            onClick = onOpen,
            corners = shape.asCorners(),
            color = cs.surfaceContainerHigh,
            contentColor = cs.onSurface,
            pressedCorner = 20.dp,
            pressScale = 0.985f,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Favicon(e.url, 40.dp) {
                    SiteTile(e.url, e.title.ifBlank { siteKey(e.url) ?: "?" }, 40.dp, shape = HripsShapes.XS)
                }
                Column(Modifier.weight(1f)) {
                    Text(e.title.ifBlank { e.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    val site = siteKey(e.url) ?: e.url
                    val line = if (showTime) site + " • " + DateFormat.getTimeFormat(context).format(Date(e.time)) else site
                    Text(
                        line, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove) { Icon(HripsIcons.Close, "Удалить") }
            }
        }
    }
}

private fun startOfDay(time: Long): Long = Calendar.getInstance().run {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

private fun dayTitle(context: android.content.Context, day: Long, today: Long): String {
    val dayMs = 24L * 60L * 60L * 1000L
    return when {
        day == today -> "Сегодня"
        today - day in 1..(dayMs + 3_600_000L) -> "Вчера"
        else -> DateUtils.formatDateTime(
            context, day,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR,
        )
    }
}

private fun matches(e: Entry, query: String) = matchesQuery(e.title.ifBlank { e.url }, e.url, query)

@Composable
fun HistoryScreen(store: Store, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val all = store.history.toList()
    val shown = if (query.isBlank()) all else all.filter { matches(it, query) }
    val today = startOfDay(System.currentTimeMillis())
    val groups = ArrayList<Pair<Long, MutableList<Entry>>>()
    for (e in shown) {
        val d = startOfDay(e.time)
        if (groups.lastOrNull()?.first == d) groups.last().second.add(e) else groups.add(d to mutableListOf(e))
    }

    ListPage(
        title = "История",
        onBack = onBack,
        actions = {
            if (all.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(HripsIcons.Trash, "Очистить историю") }
            }
        },
    ) {
        if (all.isNotEmpty()) {
            item(key = "search") {
                Column(Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(bottom = 4.dp)) {
                    PageSearchField("Поиск в истории", query) { query = it }
                }
            }
        }
        if (all.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    HripsIcons.History, "История пуста",
                    "Здесь появятся страницы, которые вы открывали. Приватные вкладки сюда не попадают.",
                    Modifier.padding(top = 56.dp), badge = Badge.CLOVER,
                )
            }
        } else if (shown.isEmpty()) {
            item(key = "none") {
                EmptyState(HripsIcons.Search, "Ничего не найдено", "Попробуйте другой запрос", Modifier.padding(top = 56.dp))
            }
        }
        for ((day, list) in groups) {
            item(key = "day$day") { PageHeaderText(dayTitle(context, day, today)) }
            itemsIndexed(list, key = { _, e -> e.url }) { i, e ->
                EntryRow(e, segShape(i, list.size), showTime = true, onOpen = { onOpen(e.url) }, onRemove = { store.removeHistory(e) })
            }
        }
    }

    if (confirmClear) {
        HripsDialog(
            icon = HripsIcons.Trash,
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Все посещённые страницы (${all.size}) будут удалены из списка. Закладки и загрузки не затрагиваются.") },
            confirmButton = {
                HripsTextButton(onClick = { confirmClear = false; store.clearHistory(); Notices.show("История очищена") }) { Text("Очистить") }
            },
            dismissButton = { HripsTextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun BookmarksScreen(store: Store, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = store.bookmarks.toList()
    val shown = if (query.isBlank()) all else all.filter { matches(it, query) }

    ListPage(title = "Закладки", onBack = onBack) {
        if (all.isNotEmpty()) {
            item(key = "search") {
                Column(Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(bottom = 12.dp)) {
                    PageSearchField("Поиск в закладках", query) { query = it }
                }
            }
        }
        if (all.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    HripsIcons.Star, "Закладок пока нет",
                    "Нажмите на звёздочку в меню страницы, чтобы сохранить её здесь.",
                    Modifier.padding(top = 56.dp),
                )
            }
        } else if (shown.isEmpty()) {
            item(key = "none") {
                EmptyState(HripsIcons.Search, "Ничего не найдено", "Попробуйте другой запрос", Modifier.padding(top = 56.dp))
            }
        }
        itemsIndexed(shown, key = { _, e -> e.url }) { i, e ->
            EntryRow(e, segShape(i, shown.size), showTime = false, onOpen = { onOpen(e.url) }, onRemove = { store.removeBookmark(e) })
        }
    }
}
