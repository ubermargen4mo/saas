package com.hrips.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.lib.state.ext.observeAsComposableState

private data class TabsSnapshot(val selectedId: String?, val tabs: List<TabUi>, val normal: Int, val priv: Int)

/** Переключатель вкладок. Список и выбор — прямо из BrowserStore, операции — через TabsUseCases. */
@Composable
fun TabSwitcher(
    c: Components,
    privateMode: Boolean,
    onPrivateMode: (Boolean) -> Unit,
    onClose: () -> Unit,
    onNewTab: () -> Unit,
) {
    val snapshot by c.store.observeAsComposableState { s ->
        TabsSnapshot(
            selectedId = s.selectedTab?.id,
            tabs = (if (privateMode) s.privateTabs else s.normalTabs).map { it.toUi() },
            normal = s.normalTabs.size,
            priv = s.privateTabs.size,
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !privateMode,
                    onClick = { onPrivateMode(false) },
                    label = { Text("Вкладки · ${snapshot.normal}") },
                )
                FilterChip(
                    selected = privateMode,
                    onClick = { onPrivateMode(true) },
                    label = { Text("Приватные · ${snapshot.priv}") },
                    leadingIcon = { Icon(HripsIcons.Mask, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                Box(Modifier.weight(1f))
                TextButton(
                    enabled = snapshot.tabs.isNotEmpty(),
                    onClick = {
                        if (privateMode) c.tabsUseCases.removePrivateTabs() else c.tabsUseCases.removeNormalTabs()
                    },
                ) { Text("Закрыть все") }
            }

            if (snapshot.tabs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (privateMode) "Нет приватных вкладок" else "Нет открытых вкладок",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(snapshot.tabs, key = { it.id }) { tab ->
                        TabCard(
                            tab = tab,
                            selected = tab.id == snapshot.selectedId,
                            onSelect = {
                                c.tabsUseCases.selectTab(tab.id)
                                onClose()
                            },
                            onRemove = {
                                c.externalTabs.remove(tab.id)
                                c.tabsUseCases.removeTab(tab.id)
                            },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onNewTab, modifier = Modifier.weight(1f)) {
                    Icon(HripsIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Новая вкладка", modifier = Modifier.padding(start = 8.dp))
                }
                TextButton(onClick = onClose) { Text("Готово") }
            }
        }
    }
}

@Composable
private fun TabCard(tab: TabUi, selected: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = HripsShapes.M,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tab.title.ifBlank { tab.host() },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onRemove) {
                    Icon(HripsIcons.Close, contentDescription = "Закрыть вкладку", modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = tab.host(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 14.dp, top = 8.dp, bottom = 24.dp),
            )
        }
    }
}
