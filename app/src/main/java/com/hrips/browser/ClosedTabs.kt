package com.hrips.browser

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class ClosedTab(private val parts: TabSaveParts) {
    val url: String get() = parts.url
    val title: String get() = parts.title
    fun snap(): TabSnap = parts.build()
}

private fun hostOf(url: String) = try { Uri.parse(url).host } catch (_: Exception) { null }?.removePrefix("www.") ?: url

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosedTabsSheet(browser: Browser, onDismiss: () -> Unit, onRestored: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val list = browser.closedTabs.toList()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainerLow,
        shape = HripsShapes.XL,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Недавно закрытые", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (list.isNotEmpty()) {
                    HripsTextButton(onClick = { browser.closedTabs.clear(); onDismiss() }) { Text("Очистить") }
                }
            }
            if (list.isEmpty()) {
                EmptyState(HripsIcons.History, "Пока пусто", "Закрытые вкладки появятся здесь, их можно будет вернуть")
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { System.identityHashCode(it) }) { c ->
                        val label = c.title.ifBlank { hostOf(c.url) }
                        Surface(
                            onClick = { browser.reopen(c); onRestored() },
                            shape = HripsShapes.M,
                            color = cs.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Favicon(c.url, 32.dp, fill = true, shape = CircleShape) { SiteTile(c.url, label, 32.dp, shape = CircleShape) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text(hostOf(c.url), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
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
