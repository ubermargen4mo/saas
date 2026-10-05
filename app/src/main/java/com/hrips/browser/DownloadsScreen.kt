package com.hrips.browser

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items as lazyItems

/**
 * Страница загрузок на весь экран. Списки сгруппированы (идут сейчас / завершены / не удались),
 * внутри группы карточки сегментированы: у крайних большое скругление, у средних малое.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadsScreen(downloads: Downloads, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var filter by remember { mutableStateOf<FileKind?>(null) }
    var query by remember { mutableStateOf("") }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val all = downloads.items.toList()
    val kinds = FileKind.entries.filter { k -> all.any { fileKind(it) == k } }
    // Если файлов выбранного типа не осталось, фильтр сбрасывается сам
    val activeFilter = filter?.takeIf { it in kinds }
    val shown = all.filter {
        (activeFilter == null || fileKind(it) == activeFilter) && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true))
    }
    val running = shown.filter { it.status == DlStatus.RUNNING }
    val done = shown.filter { it.status == DlStatus.DONE }
    val failed = shown.filter { it.status == DlStatus.FAILED || it.status == DlStatus.CANCELLED }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            // Страница лежит поверх GeckoView: без этого нажатия по пустым местам уходили бы в сайт под ней
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text("Загрузки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HripsIcons.Back, "Назад") } },
                actions = {
                    if (all.any { it.status != DlStatus.RUNNING }) {
                        IconButton(onClick = { downloads.clearFinished() }) { Icon(HripsIcons.Trash, "Очистить список") }
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (all.isNotEmpty()) {
                item {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                        SearchField(query) { query = it }
                        if (kinds.size > 1) {
                            LazyRow(
                                Modifier.padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item {
                                    FilterChip(
                                        selected = activeFilter == null,
                                        onClick = { filter = null },
                                        label = { Text("Все") },
                                        shape = CircleShape,
                                        leadingIcon = if (activeFilter == null) {
                                            { Icon(HripsIcons.Check, null, Modifier.size(18.dp)) }
                                        } else null,
                                    )
                                }
                                lazyItems(kinds) { k ->
                                    val sel = activeFilter == k
                                    FilterChip(
                                        selected = sel,
                                        onClick = { filter = if (sel) null else k },
                                        label = { Text(k.label) },
                                        shape = CircleShape,
                                        leadingIcon = { Icon(if (sel) HripsIcons.Check else kindIcon(k), null, Modifier.size(18.dp)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (shown.isEmpty()) {
                item { EmptyState(hasAny = all.isNotEmpty()) }
            }

            if (running.isNotEmpty()) {
                item { SectionHeader("Загружаются", runningSummary(context, running)) }
                group(running, downloads)
            }
            if (done.isNotEmpty()) {
                item { SectionHeader("Завершено") }
                group(done, downloads)
            }
            if (failed.isNotEmpty()) {
                item { SectionHeader("Не удалось") }
                group(failed, downloads)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.group(list: List<DownloadItem>, downloads: Downloads) {
    list.forEachIndexed { i, d ->
        item(key = d.id) {
            Box(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                DownloadRow(d, segmentShape(i, list.size), downloads)
            }
        }
    }
}

private fun segmentShape(i: Int, n: Int): Shape {
    val big = 24.dp
    val small = 6.dp
    val top = if (i == 0) big else small
    val bottom = if (i == n - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null) {
    Row(
        Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
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
                            Text("Поиск в загрузках", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyState(hasAny: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shape = MaterialShapes.Cookie9Sided.toShape()
        Box(
            Modifier.size(112.dp).background(MaterialTheme.colorScheme.secondaryContainer, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (hasAny) HripsIcons.Search else HripsIcons.Download, null,
                Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (hasAny) "Ничего не найдено" else "Загрузок пока нет",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasAny) "Попробуйте другой запрос или тип файла" else "Файлы, которые вы скачаете, появятся здесь",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadRow(d: DownloadItem, shape: Shape, downloads: Downloads) {
    val context = LocalContext.current
    val status = d.status
    var menu by remember { mutableStateOf(false) }

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(shape),
        onClick = { if (status == DlStatus.DONE) downloads.open(d) },
        enabled = status == DlStatus.DONE,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            KindBadge(fileKind(d), 48.dp)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (status) {
                    DlStatus.RUNNING -> {
                        Spacer(Modifier.height(8.dp))
                        if (d.total > 0) {
                            LinearWavyProgressIndicator(progress = { (d.done.toFloat() / d.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(runningLine(context, d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DlStatus.DONE -> Text(
                        Formatter.formatShortFileSize(context, d.done) + " • " +
                            DateUtils.getRelativeTimeSpanString(d.finishedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DlStatus.FAILED -> Text(
                        "Ошибка загрузки" + (d.error?.let { ": $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DlStatus.CANCELLED -> Text("Отменено", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (status) {
                DlStatus.RUNNING -> IconButton(onClick = { downloads.cancel(d) }) { Icon(HripsIcons.Close, "Отменить загрузку") }
                DlStatus.DONE -> Box {
                    IconButton(onClick = { menu = true }) { Icon(HripsIcons.MoreVert, "Ещё") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Открыть") },
                            leadingIcon = { Icon(HripsIcons.Open, null) },
                            onClick = { menu = false; downloads.open(d) },
                        )
                        DropdownMenuItem(
                            text = { Text("Поделиться") },
                            leadingIcon = { Icon(HripsIcons.Share, null) },
                            onClick = { menu = false; downloads.share(d) },
                        )
                        DropdownMenuItem(
                            text = { Text("Убрать из списка") },
                            leadingIcon = { Icon(HripsIcons.Close, null) },
                            onClick = { menu = false; downloads.remove(d, deleteFile = false) },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить файл") },
                            leadingIcon = { Icon(HripsIcons.Trash, null) },
                            onClick = { menu = false; downloads.remove(d, deleteFile = true) },
                        )
                    }
                }
                else -> IconButton(onClick = { downloads.remove(d, deleteFile = false) }) { Icon(HripsIcons.Close, "Убрать из списка") }
            }
        }
    }
}

private fun runningLine(context: android.content.Context, d: DownloadItem): String {
    val done = Formatter.formatShortFileSize(context, d.done)
    val head = if (d.total > 0) "$done из ${Formatter.formatShortFileSize(context, d.total)}" else done
    val tail = if (d.speed > 0) " • ${Formatter.formatFileSize(context, d.speed)}/с" else ""
    return head + tail
}

/** Справа от заголовка "Загружаются": общая скорость и сколько осталось (как в системном списке загрузок). */
private fun runningSummary(context: android.content.Context, running: List<DownloadItem>): String? {
    val speed = running.sumOf { it.speed }
    if (speed <= 0) return null
    val left = running.filter { it.total > 0 && it.speed > 0 }.maxOfOrNull { (it.total - it.done) / it.speed }
    val s = Formatter.formatFileSize(context, speed) + "/с"
    return if (left != null) "$s • через ${duration(left)}" else s
}

private fun duration(sec: Long): String = when {
    sec < 60 -> "$sec с"
    sec < 3600 -> "${sec / 60} мин"
    else -> "${sec / 3600} ч ${sec % 3600 / 60} мин"
}
