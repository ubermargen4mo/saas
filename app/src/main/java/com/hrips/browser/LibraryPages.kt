package com.hrips.browser

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ListPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    listModifier: Modifier = Modifier,
    side: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .consumeTaps()
            .nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { PageTopBar(title, onBack, scroll, actions, maxWidth = if (side != null) WITH_SIDE_MAX_WIDTH else PAGE_BAR_MAX_WIDTH) },
    ) { pad ->
        // С боковой панелью шапка, панель и список выровнены как один блок, иначе заголовок висел отдельно от колонок.
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
        Row(Modifier.then(if (side != null) Modifier.widthIn(max = WITH_SIDE_MAX_WIDTH) else Modifier).fillMaxSize()) {
            if (side != null) {
                Box(Modifier.width(SIDE_PANE_WIDTH).fillMaxHeight().padding(start = HripsLayout.PageGutter)) { side() }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                header?.let {
                    Box(Modifier.fillMaxWidth().padding(horizontal = HripsLayout.PageGutter), contentAlignment = Alignment.TopCenter) { it() }
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
                            Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
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
                Favicon(e.url, 40.dp, fill = true, shape = CircleShape) {
                    SiteTile(e.url, e.title.ifBlank { siteKey(e.url) ?: "?" }, 40.dp, shape = CircleShape)
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

private const val DAY_MS = 24L * 60L * 60L * 1000L
private val SIDE_PANE_WIDTH = 280.dp
private val PAGE_BAR_MAX_WIDTH = HripsLayout.PageMaxWidth + HripsLayout.PageGutter * 2
private val WITH_SIDE_MAX_WIDTH = SIDE_PANE_WIDTH + HripsLayout.PageMaxWidth + HripsLayout.PageGutter * 3

private fun startOfDay(time: Long): Long = Calendar.getInstance().run {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

private fun dayTitle(context: Context, day: Long, today: Long, short: Boolean = false): String {
    return when {
        day == today -> "Сегодня"
        today - day in 1..(DAY_MS + 3_600_000L) -> "Вчера"
        short -> {
            val sameYear = Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.YEAR) ==
                Calendar.getInstance().apply { timeInMillis = today }.get(Calendar.YEAR)
            DateUtils.formatDateTime(
                context, day,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
                    (if (sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR),
            )
        }
        else -> DateUtils.formatDateTime(
            context, day,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR,
        )
    }
}

@Composable
private fun DayRow(title: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = HripsShapes.L,
        color = if (selected) cs.primaryContainer else Color.Transparent,
        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) cs.onPrimaryContainer.copy(alpha = 0.75f) else cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryDays(
    groups: List<Pair<Long, List<Entry>>>,
    total: Int,
    selected: Long?,
    today: Long,
    onSelect: (Long?) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, end = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "all") { DayRow("Все дни", total, selected == null) { onSelect(null) } }
        items(groups, key = { it.first }) { (day, list) ->
            DayRow(dayTitle(context, day, today, short = true), list.size, selected == day) { onSelect(day) }
        }
    }
}

private fun filterEntries(all: List<Entry>, query: String): List<Entry> =
    if (query.isBlank()) all else all.filter { matchesQuery(it.title.ifBlank { it.url }, it.url, query) }

private fun LazyListScope.searchItem(visible: Boolean, hint: String, query: String, bottom: Dp, onChange: (String) -> Unit) {
    if (!visible) return
    item(key = "search") {
        Column(Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(bottom = bottom)) {
            PageSearchField(hint, query, onChange)
        }
    }
}

private fun LazyListScope.emptyItems(isEmpty: Boolean, noMatches: Boolean, empty: @Composable () -> Unit) {
    if (isEmpty) {
        item(key = "empty") { empty() }
    } else if (noMatches) {
        item(key = "none") {
            EmptyState(HripsIcons.Search, "Ничего не найдено", "Попробуйте другой запрос", Modifier.padding(top = 56.dp))
        }
    }
}

@Composable
fun HistoryScreen(store: Store, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val all by remember(store) { derivedStateOf { store.history.toList() } }
    val shown = remember(all, query) { filterEntries(all, query) }
    val today = remember(all) { startOfDay(System.currentTimeMillis()) }
    val groups = remember(shown) {
        val out = ArrayList<Pair<Long, MutableList<Entry>>>()
        for (e in shown) {
            val d = startOfDay(e.time)
            if (out.lastOrNull()?.first == d) out.last().second.add(e) else out.add(d to mutableListOf(e))
        }
        out
    }
    val wide = isWideWindow()
    var pickedDay by remember { mutableStateOf<Long?>(null) }
    val selectedDay = pickedDay?.takeIf { d -> groups.any { it.first == d } }
    val visibleGroups = if (wide && selectedDay != null) groups.filter { it.first == selectedDay } else groups

    ListPage(
        title = "История",
        onBack = onBack,
        actions = {
            if (all.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(HripsIcons.Trash, "Очистить историю") }
            }
        },
        side = if (wide && groups.isNotEmpty()) ({ HistoryDays(groups, shown.size, selectedDay, today) { pickedDay = it } }) else null,
    ) {
        searchItem(all.isNotEmpty(), "Поиск в истории", query, 4.dp) { query = it }
        emptyItems(all.isEmpty(), shown.isEmpty()) {
            EmptyState(
                HripsIcons.History, "История пуста",
                "Здесь появятся страницы, которые вы открывали. Приватные вкладки сюда не попадают.",
                Modifier.padding(top = 56.dp), badge = Badge.CLOVER,
            )
        }
        for ((day, list) in visibleGroups) {
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
    val shown = filterEntries(all, query)

    val context = LocalContext.current
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/html"),
    ) { uri ->
        if (uri != null) {
            val html = BookmarksHtml.export(store.bookmarks.toList())
            val count = store.bookmarks.size
            AppExecutors.startThread("hrips-bm-export", onError = { Notices.show("Не удалось записать файл") }) {
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("файл не открывается")
                out.use { it.write(html.toByteArray(Charsets.UTF_8)) }
                Notices.show("Выгружено закладок: $count", NoticeKind.SUCCESS)
            }
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            AppExecutors.startThread("hrips-bm-import", onError = { Notices.show("Не удалось прочитать файл") }) {
                val found = BookmarksHtml.parse(readImportText(context, uri))
                MainThread.handler.post {
                    val added = store.importBookmarks(found)
                    Notices.show(
                        if (found.isEmpty()) "В файле нет закладок" else "Добавлено закладок: $added из ${found.size}",
                        if (added > 0) NoticeKind.SUCCESS else NoticeKind.INFO,
                    )
                }
            }
        }
    }

    ListPage(title = "Закладки", onBack = onBack) {
        item(key = "io") {
            Column(Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PanelItem(
                    "Импорт из файла", subtitle = "HTML-файл закладок из Chrome, Firefox, Safari", icon = HripsIcons.Download,
                    shape = HripsShapes.segment(0, 2), onClick = { importLauncher.launch(arrayOf("text/html", "*/*")) },
                )
                PanelItem(
                    "Экспорт в файл", subtitle = if (all.isEmpty()) "Пока нечего выгружать" else "Закладок: ${all.size}", icon = HripsIcons.Share,
                    shape = HripsShapes.segment(1, 2), enabled = all.isNotEmpty(), onClick = { exportLauncher.launch("hrips-bookmarks.html") },
                )
            }
        }
        searchItem(all.isNotEmpty(), "Поиск в закладках", query, 12.dp) { query = it }
        emptyItems(all.isEmpty(), shown.isEmpty()) {
            EmptyState(
                HripsIcons.Star, "Закладок пока нет",
                "Нажмите на звёздочку в меню страницы, чтобы сохранить её здесь.",
                Modifier.padding(top = 56.dp),
            )
        }
        itemsIndexed(shown, key = { _, e -> e.url }) { i, e ->
            EntryRow(e, segShape(i, shown.size), showTime = false, onOpen = { onOpen(e.url) }, onRemove = { store.removeBookmark(e) })
        }
    }
}
