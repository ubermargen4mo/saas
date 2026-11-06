@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun ClearDataSheet(browser: Browser, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val store = browser.store
    val context = LocalContext.current
    var history by remember { mutableStateOf(true) }
    var cookies by remember { mutableStateOf(false) }
    var cache by remember { mutableStateOf(true) }
    // Размер превью и картинок считаем на диске, не в основном потоке.
    var previews by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var images by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            previews = runCatching { TabThumbs.diskUsage(context) }.getOrNull()
            images = runCatching { PageImages.diskUsage(context) }.getOrNull()
        }
    }
    val historyCount = store.history.size
    val searchCount = store.searches.size
    val historyNote = if (historyCount == 0 && searchCount == 0) "Пока пусто, удалять нечего" else
        "Посещённых страниц: $historyCount, поисковых запросов: $searchCount"
    val cacheBytes = (previews?.first ?: 0L) + (images?.first ?: 0L)
    val cacheFiles = (previews?.second ?: 0) + (images?.second ?: 0)
    val cacheNote = if (previews == null || images == null) "Считаю размер…" else
        "Превью вкладок и картинки страниц: ${formatBytes(cacheBytes)} (${ruPlural(cacheFiles, "файл", "файла", "файлов")}), " +
            "плюс кэш страниц движка, его размер не определяется"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        containerColor = cs.surfaceContainerLow,
        shape = HripsShapes.XL,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp)) {
            Text("Очистить данные", Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.titleLarge)
            Text(
                "Выберите, что удалить с этого устройства",
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            SectionTitle("Что удалить")
            Group(
                { s -> PanelSwitch("История и запросы", historyNote, history, { history = it }, icon = HripsIcons.History, shape = s) },
                { s -> PanelSwitch("Cookies и данные сайтов", "Все cookies и локальные данные всех сайтов (localStorage и т.п.). Число записей не считается", cookies, { cookies = it }, icon = HripsIcons.Globe, shape = s) },
                { s -> PanelSwitch("Кэш и превью вкладок", cacheNote, cache, { cache = it }, icon = HripsIcons.Package, shape = s) },
            )
            Reveal(cookies) {
                Callout("Cookies и данные сайтов: вы выйдете из аккаунтов на сайтах, push-подписки сайтов снимутся", Modifier.padding(top = 12.dp), icon = HripsIcons.Alert)
            }
            Reveal(history && !cache) {
                Callout("Превью вкладок удаляются вместе с историей: ${formatBytes(previews?.first ?: 0L)}", Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                shapes = ButtonDefaults.shapes(),
                enabled = history || cookies || cache,
                onClick = {
                    if (history) { store.clearHistory(); store.clearSearches() }
                    if (history || cache) {
                        TabThumbs.clear(context)
                        PageImages.clear(context)
                        browser.tabs.forEach { it.thumbnail = null }
                    }
                    browser.clearData(cookies, cache) { Notices.show("Данные очищены") }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(HripsIcons.Trash, null, Modifier.size(22.dp))
                    Text("Очистить", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024L * 1024 -> "${(bytes / 1024.0).let { "%.0f".format(it) }} КБ"
    else -> "%.1f МБ".format(bytes / 1024.0 / 1024.0)
}
