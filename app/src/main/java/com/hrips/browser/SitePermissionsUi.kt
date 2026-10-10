package com.hrips.browser

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    val origin: String? = null,
) {
    var allowed by mutableStateOf(allowed)

    fun originKey(): String = origin ?: host
}

private fun originOf(uri: String): String = runCatching {
    val u = Uri.parse(uri)
    val scheme = u.scheme?.lowercase() ?: return@runCatching uri
    val host = u.host?.lowercase() ?: return@runCatching uri
    val port = when {
        u.port == -1 -> null
        scheme == "http" && u.port == 80 -> null
        scheme == "https" && u.port == 443 -> null
        else -> u.port
    }?.let { ":$it" }.orEmpty()
    "$scheme://$host$port"
}.getOrDefault(uri)

/** Страница со всеми запомненными решениями для сайтов: можно переключить, отозвать или сбросить всё. */
@Composable
fun SitePermissionsScreen(runtime: GeckoRuntime, sites: SitePermissions, onBack: () -> Unit) {
    val geckoRows = remember { mutableStateListOf<SiteRow>() }
    var loaded by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
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
                geckoRows.add(SiteRow(originOf(p.uri), label, p.value == ContentPermission.VALUE_ALLOW, p, null, originOf(p.uri)))
            }
            loaded = true
        }, { loaded = true })
    }

    val ours = sites.saved.map { SiteRow(it.host, SitePermissions.label(it.kind), it.allowed, null, it.kind, it.origin) }
    val rows = (geckoRows.toList() + ours).sortedWith(compareBy({ it.host }, { it.label }))

    ListPage(
        title = "Разрешения сайтов",
        onBack = onBack,
        actions = {
            if (rows.isNotEmpty()) {
                IconButton(onClick = { confirmReset = true }) { Icon(HripsIcons.Trash, "Сбросить все") }
            }
        },
    ) {
        item(key = "hint") {
            Text(
                "Сайты, для которых вы разрешили или запретили доступ. Отозванное разрешение сайт спросит заново.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (rows.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    HripsIcons.ShieldCheck,
                    if (loaded) "Пока ничего не сохранено" else "Загрузка…",
                    "Когда вы разрешите или запретите сайту камеру, микрофон, местоположение или уведомления, решение появится здесь.",
                    Modifier.padding(top = 40.dp),
                )
            }
        }
        itemsIndexed(rows, key = { _, r -> (r.gecko?.let { g -> "g${g.permission}" } ?: "o${r.kind}") + r.host }) { index, row ->
            Surface(
                shape = segShape(index, rows.size),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth(),
            ) {
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
                            sites.set(row.originKey(), row.kind, v)
                        }
                    })
                    IconButton(onClick = {
                        if (row.gecko != null) {
                            storage.setPermission(row.gecko, ContentPermission.VALUE_PROMPT)
                            geckoRows.remove(row)
                        } else if (row.kind != null) {
                            sites.forget(row.originKey(), row.kind)
                        }
                    }) { Icon(HripsIcons.Trash, "Сбросить") }
                }
            }
        }
    }

    if (confirmReset) {
        HripsDialog(
            icon = HripsIcons.Trash,
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить все разрешения?") },
            text = { Text("Сайты снова будут спрашивать доступ при следующем обращении.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    geckoRows.toList().forEach { r -> r.gecko?.let { storage.setPermission(it, ContentPermission.VALUE_PROMPT) } }
                    geckoRows.clear()
                    sites.clear()
                }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }
}
