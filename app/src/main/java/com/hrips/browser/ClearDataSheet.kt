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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Шторка «Очистить данные»: что удалить (тумблеры со значками), предупреждение про выход из аккаунтов и
 * акцентная кнопка внизу. Вместо трёх плиток и кнопки прямо на странице «Конфиденциальность».
 */
@Composable
internal fun ClearDataSheet(browser: Browser, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val store = browser.store
    val context = LocalContext.current
    var history by remember { mutableStateOf(true) }
    var cookies by remember { mutableStateOf(false) }
    var cache by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                { s -> PanelSwitch("История и запросы", null, history, { history = it }, icon = HripsIcons.History, shape = s) },
                { s -> PanelSwitch("Cookies и данные сайтов", null, cookies, { cookies = it }, icon = HripsIcons.Globe, shape = s) },
                { s -> PanelSwitch("Кэш и превью вкладок", null, cache, { cache = it }, icon = HripsIcons.Package, shape = s) },
            )
            Reveal(cookies) {
                Callout("Cookies и данные сайтов: вы выйдете из аккаунтов на сайтах", Modifier.padding(top = 12.dp), icon = HripsIcons.Alert)
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
