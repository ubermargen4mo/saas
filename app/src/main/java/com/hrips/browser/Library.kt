package com.hrips.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Закладки и история: переключатель-сегмент, карточки с иконками сайтов, пустое состояние с фигурой. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySheet(
    store: Store,
    page: Int,
    onPage: (Int) -> Unit,
    onOpen: (String) -> Unit,
    onDownloads: () -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val list = (if (page == 0) store.bookmarks else store.history).toList()
    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Библиотека", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (page == 1 && list.isNotEmpty()) TextButton(onClick = { store.clearHistory() }) { Text("Очистить") }
                FilledTonalIconButton(onClick = onDownloads) { Icon(HripsIcons.Download, "Загрузки") }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
                listOf("Закладки", "История").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = page == i,
                        onClick = { onPage(i) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            if (list.isEmpty()) {
                if (page == 0) {
                    EmptyState(HripsIcons.Star, "Закладок пока нет", "Нажмите на звёздочку в меню страницы, чтобы сохранить её здесь.")
                } else {
                    EmptyState(HripsIcons.History, "История пуста", "Здесь появятся страницы, которые вы открывали. Приватные вкладки сюда не попадают.", badge = Badge.CLOVER)
                }
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(list, key = { _, e -> e.url }) { i, e ->
                        Surface(
                            onClick = { onOpen(e.url) },
                            shape = segShape(i, list.size),
                            color = cs.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Favicon(e.url, 40.dp) {
                                    Box(
                                        Modifier.size(40.dp).background(cs.secondaryContainer, RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            (e.title.ifBlank { siteKey(e.url) ?: "?" }).first().uppercase(),
                                            style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer,
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(e.title.ifBlank { e.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        siteKey(e.url) ?: e.url, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { if (page == 0) store.removeBookmark(e) else store.removeHistory(e) }) {
                                    Icon(HripsIcons.Close, "Удалить")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
