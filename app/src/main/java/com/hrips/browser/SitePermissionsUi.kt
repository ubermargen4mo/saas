package com.hrips.browser

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission

/** Одна строка списка: либо разрешение из Gecko (геолокация, уведомления), либо наше (камера, микрофон). */
private class SiteRow(
    val host: String,
    val label: String,
    allowed: Boolean,
    val gecko: ContentPermission?,
    val kind: String?,
) {
    var allowed by mutableStateOf(allowed)
}

private fun hostOf(uri: String) = Uri.parse(uri).host ?: uri

/** Список всех запомненных решений для сайтов: можно переключить, отозвать или сбросить всё. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitePermissionsSheet(runtime: GeckoRuntime, sites: SitePermissions, onClose: () -> Unit) {
    val geckoRows = remember { mutableStateListOf<SiteRow>() }
    var loaded by remember { mutableStateOf(false) }
    val storage = runtime.storageController

    LaunchedEffect(Unit) {
        storage.getAllPermissions().accept({ list ->
            geckoRows.clear()
            for (p in list.orEmpty()) {
                val label = when (p.permission) {
                    PermissionDelegate.PERMISSION_GEOLOCATION -> "Местоположение"
                    PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "Уведомления"
                    else -> continue
                }
                if (p.value == ContentPermission.VALUE_PROMPT) continue
                geckoRows.add(SiteRow(hostOf(p.uri), label, p.value == ContentPermission.VALUE_ALLOW, p, null))
            }
            loaded = true
        }, { loaded = true })
    }

    val ours = sites.saved.map { SiteRow(it.host, SitePermissions.label(it.kind), it.allowed, null, it.kind) }
    val rows = (geckoRows.toList() + ours).sortedWith(compareBy({ it.host }, { it.label }))

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Разрешения сайтов", style = MaterialTheme.typography.titleLarge)
            Text(
                "Сайты, для которых вы разрешили или запретили доступ. Отозванное разрешение сайт спросит заново.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            if (rows.isEmpty()) {
                Text(
                    if (loaded) "Пока ничего не сохранено" else "Загрузка...",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(rows, key = { _, r -> (r.gecko?.let { g -> "g${g.permission}" } ?: "o${r.kind}") + r.host }) { index, row ->
                      Surface(shape = segShape(index, rows.size), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.host, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    row.label + ": " + if (row.allowed) "разрешено" else "запрещено",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = row.allowed, onCheckedChange = { v ->
                                if (row.gecko != null) {
                                    storage.setPermission(row.gecko, if (v) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY)
                                    row.allowed = v
                                } else if (row.kind != null) {
                                    sites.set(row.host, row.kind, v)
                                }
                            })
                            IconButton(onClick = {
                                if (row.gecko != null) {
                                    storage.setPermission(row.gecko, ContentPermission.VALUE_PROMPT)
                                    geckoRows.remove(row)
                                } else if (row.kind != null) {
                                    sites.forget(row.host, row.kind)
                                }
                            }) { Icon(HripsIcons.Trash, "Сбросить") }
                        }
                      }
                    }
                }
                TextButton(
                    onClick = {
                        geckoRows.toList().forEach { r -> r.gecko?.let { storage.setPermission(it, ContentPermission.VALUE_PROMPT) } }
                        geckoRows.clear()
                        sites.clear()
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Сбросить все") }
            }
        }
    }
}
