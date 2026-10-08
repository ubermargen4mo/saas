package com.hrips.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.util.Locale

/** Значок расширения: берём иконку кнопки, если у неё своя, иначе иконку самого расширения. */
@Composable
fun ExtIcon(ext: WebExtension, action: WebExtension.Action?, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image = action?.icon ?: ext.metaData.icon
    var bmp by remember(ext.id, image) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(ext.id, image, px) {
        runCatching { image.getBitmap(px).accept({ b -> if (b != null) bmp = b.asImageBitmap() }, { }) }
    }
    val b = bmp
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (b != null) {
            androidx.compose.foundation.Image(b, null, Modifier.size(size))
        } else {
            ShapeBadge(
                HripsIcons.Puzzle, size, badge = Badge.COOKIE6,
                container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** Текст значка расширения (счётчик заблокированного и т. п.) поверх иконки. */
@Composable
private fun ExtIconWithBadge(ext: WebExtension, action: WebExtension.Action?, size: Dp) {
    Box {
        ExtIcon(ext, action, size)
        val text = action?.badgeText
        val bg = action?.badgeBackgroundColor
        val fg = action?.badgeTextColor
        if (!text.isNullOrEmpty()) {
            Surface(
                shape = CircleShape,
                color = bg?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(
                    text, Modifier.padding(horizontal = 5.dp, vertical = 1.dp), maxLines = 1, fontSize = 10.sp,
                    color = fg?.let { Color(it) } ?: MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

private fun displayName(ext: WebExtension) = ext.metaData.name?.takeIf { it.isNotBlank() } ?: ext.id

/** Лист с кнопками расширений (по значку пазла): нажатие открывает окно расширения. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsSheet(
    extensions: Extensions,
    isPrivate: Boolean,
    onManage: (store: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Сначала установленные пользователем, встроенные в конце
    val list = extensions.installed.sortedBy { it.isBuiltIn }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = cs.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Расширения", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                FilledTonalButton(onClick = { onClose(); onManage(false) }) { Text("Управление") }
            }
            Spacer8()
            if (list.none { !it.isBuiltIn }) {
                EmptyState(
                    HripsIcons.Puzzle, "Расширений пока нет",
                    "Ставьте расширения из каталога Mozilla: блокировщики, менеджеры паролей, тёмные темы и другие.",
                )
                Button(onClick = { onClose(); onManage(true) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Открыть каталог")
                }
                Spacer8()
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                list.forEachIndexed { i, ext ->
                    val action = extensions.actions[ext.id]
                    val md = ext.metaData
                    val blockedInPrivate = isPrivate && !md.allowedInPrivateBrowsing
                    val usable = md.enabled && !blockedInPrivate && action != null && action.enabled != false
                    val subtitle = when {
                        !md.enabled -> "Выключено"
                        blockedInPrivate -> "Выключено в приватных вкладках"
                        action == null -> "Работает в фоне"
                        else -> action.title?.takeIf { it.isNotBlank() && it != displayName(ext) } ?: "Нажмите, чтобы открыть"
                    }
                    Surface(
                        onClick = {
                            onClose()
                            if (usable) action?.click() else onManage(false)
                        },
                        shape = segShape(i, list.size),
                        color = cs.surfaceContainer,
                        modifier = Modifier.fillMaxWidth().alpha(if (usable) 1f else 0.6f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            ExtIconWithBadge(ext, action, 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(displayName(ext), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))

/** Страница управления: установленные расширения и каталог Mozilla. */
@Composable
fun ExtensionsScreen(extensions: Extensions, startOnStore: Boolean, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(if (startOnStore) 1 else 0) }
    var toRemove by remember { mutableStateOf<WebExtension?>(null) }

    PageScaffold("Расширения", onBack) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
            listOf("Установленные (${extensions.installed.size})", "Каталог").forEachIndexed { i, label ->
                SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                    Text(label, maxLines = 1)
                }
            }
        }
        if (tab == 0) {
            InstalledList(extensions, onRemove = { toRemove = it }, onStore = { tab = 1 })
        } else {
            StoreList(extensions)
        }
    }

    toRemove?.let { ext ->
        HripsDialog(
            icon = HripsIcons.Trash,
            onDismissRequest = { toRemove = null },
            title = { Text("Удалить «${displayName(ext)}»?") },
            text = { Text("Расширение и его данные будут удалены с этого устройства.") },
            confirmButton = { TextButton(onClick = { extensions.uninstall(ext); toRemove = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ColumnScope.InstalledList(extensions: Extensions, onRemove: (WebExtension) -> Unit, onStore: () -> Unit) {
    val list = extensions.installed.sortedBy { it.isBuiltIn }
    var expanded by remember { mutableStateOf<String?>(null) }
    if (list.isEmpty()) {
        EmptyState(HripsIcons.Puzzle, "Расширений пока нет", "Откройте каталог и установите первое одним нажатием.")
        Button(onClick = onStore) { Text("Открыть каталог") }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        list.forEachIndexed { i, ext ->
            InstalledCard(
                ext, extensions, segShape(i, list.size), expanded == ext.id,
                onToggle = { expanded = if (expanded == ext.id) null else ext.id },
                onRemove = { onRemove(ext) },
            )
        }
    }
}

@Composable
private fun InstalledCard(
    ext: WebExtension,
    extensions: Extensions,
    shape: androidx.compose.ui.graphics.Shape,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val md = ext.metaData
    val sub = buildString {
        append("v").append(md.version)
        if (ext.isBuiltIn) append(" · встроенное")
        if (!md.enabled) append(" · выключено")
    }
    Surface(onClick = onToggle, shape = shape, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ExtIconWithBadge(ext, extensions.actions[ext.id], 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(displayName(ext), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(sub, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (!ext.isBuiltIn) {
                    Switch(
                        checked = md.enabled,
                        onCheckedChange = { extensions.setEnabled(ext, it) },
                        thumbContent = if (md.enabled) {
                            { Icon(HripsIcons.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
                        } else null,
                    )
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    md.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("В приватных вкладках", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Расширение сможет видеть страницы, открытые инкогнито",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            )
                        }
                        Switch(checked = md.allowedInPrivateBrowsing, onCheckedChange = { extensions.setAllowedInPrivate(ext, it) })
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                        md.optionsPageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            TextButton(onClick = { extensions.openTab(url, true, false) }) { Text("Настройки") }
                        }
                        if (!ext.isBuiltIn) {
                            TextButton(onClick = onRemove) { Text("Удалить", color = cs.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.StoreList(extensions: Extensions) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AmoAddon>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(query, reload) {
        loading = true
        failed = false
        if (query.isNotBlank()) delay(450) // не дёргаем каталог на каждую букву
        Amo.search(query.trim())
            .onSuccess { results = it }
            .onFailure { failed = true }
        loading = false
    }

    HripsField(query, { query = it }, singleLine = true, label = { Text("Поиск расширений") })
    Text(
        if (query.isBlank()) "Самые популярные" else "Результаты поиска",
        Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall, color = cs.primary,
    )

    when {
        loading && results.isEmpty() -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        failed -> {
            EmptyState(HripsIcons.Puzzle, "Каталог недоступен", "Проверьте соединение и попробуйте ещё раз.")
            FilledTonalButton(onClick = { reload++ }) { Text("Повторить") }
        }
        results.isEmpty() -> EmptyState(HripsIcons.Search, "Ничего не найдено", "Попробуйте другой запрос.")
        else -> {
            val have = extensions.installed.map { it.id }.toSet()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                results.forEachIndexed { i, a ->
                    Surface(shape = segShape(i, results.size), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            RemoteIcon(a.iconUrl, 48.dp) {
                                ShapeBadge(
                                    HripsIcons.Puzzle, 48.dp, badge = Badge.COOKIE6,
                                    container = cs.secondaryContainer, content = cs.onSecondaryContainer,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                if (a.summary.isNotBlank()) {
                                    Text(
                                        a.summary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                    )
                                }
                                val meta = buildList {
                                    if (a.ratingCount > 0) add("★ " + String.format(Locale.getDefault(), "%.1f", a.rating))
                                    if (a.users > 0) add(countLabel(a.users) + " польз.")
                                }.joinToString(" · ")
                                if (meta.isNotEmpty()) {
                                    Text(meta, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                }
                            }
                            when {
                                a.guid in have -> FilledTonalButton(onClick = {}, enabled = false) { Text("Установлено") }
                                a.guid in extensions.installing -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                                a.xpiUrl != null -> Button(onClick = { extensions.install(a.guid, a.xpiUrl) }) { Text("Установить") }
                            }
                        }
                    }
                }
            }
            Text(
                "Каталог: addons.mozilla.org. Расширения подписаны Mozilla, но написаны сторонними авторами.",
                Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
        }
    }
}

private fun countLabel(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f млн", n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000} тыс."
    else -> n.toString()
}

private fun permissionLabel(p: String): String = when (p) {
    "tabs" -> "Видеть адреса и названия открытых вкладок"
    "webRequest", "webRequestBlocking", "webRequestAuthProvider" -> "Перехватывать и менять сетевые запросы"
    "declarativeNetRequest", "declarativeNetRequestWithHostAccess" -> "Блокировать и менять содержимое страниц"
    "cookies" -> "Читать и менять cookies"
    "history" -> "Читать и менять историю"
    "bookmarks" -> "Читать и менять закладки"
    "downloads", "downloads.open" -> "Управлять загрузками"
    "clipboardRead" -> "Читать буфер обмена"
    "clipboardWrite" -> "Записывать в буфер обмена"
    "notifications" -> "Показывать уведомления"
    "geolocation" -> "Определять ваше местоположение"
    "nativeMessaging" -> "Обмениваться данными с другими приложениями"
    "management" -> "Управлять другими расширениями"
    "privacy" -> "Менять настройки приватности браузера"
    "proxy" -> "Управлять прокси браузера"
    "browsingData" -> "Очищать данные браузера"
    "topSites" -> "Видеть часто посещаемые сайты"
    "webNavigation" -> "Видеть переходы между страницами"
    "find" -> "Искать текст на страницах"
    "search" -> "Использовать поисковые системы"
    "sessions" -> "Видеть недавно закрытые вкладки"
    "menus", "contextMenus" -> "Добавлять пункты в контекстное меню"
    "identity" -> "Входить в аккаунты от вашего имени"
    "activeTab" -> "Работать с открытой страницей по вашему нажатию"
    else -> p
}

private val allSites = setOf("<all_urls>", "*://*/*", "http://*/*", "https://*/*", "*://*/", "http://*/", "https://*/")

private fun originLines(origins: List<String>): List<String> {
    if (origins.isEmpty()) return emptyList()
    if (origins.any { it in allSites }) return listOf("Доступ к вашим данным на всех сайтах")
    val hosts = origins.map { it.substringAfter("://").substringBefore("/").removePrefix("*.") }.distinct()
    val shown = hosts.take(3).joinToString(", ")
    return listOf("Доступ к данным на сайтах: $shown" + if (hosts.size > 3) " и ещё ${hosts.size - 3}" else "")
}

/** Окно с запросом прав: что именно расширение сможет делать. */
@Composable
fun ExtensionPromptDialog(prompt: ExtPrompt, onAnswer: (allow: Boolean, privateAllowed: Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var priv by remember(prompt) { mutableStateOf(false) }
    val name = displayName(prompt.extension)
    val lines = prompt.permissions.map { permissionLabel(it) } + originLines(prompt.origins) +
        prompt.dataCollection.map { "Сбор данных: $it" }
    HripsDialog(
        icon = HripsIcons.Puzzle,
        onDismissRequest = { onAnswer(false, false) },
        title = {
            Text(
                when (prompt.kind) {
                    ExtPrompt.Kind.INSTALL -> "Установить «$name»?"
                    ExtPrompt.Kind.UPDATE -> "Обновить «$name»?"
                    ExtPrompt.Kind.OPTIONAL -> "«$name» просит доступ"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (lines.isEmpty()) "Расширению не нужны особые разрешения." else "Расширению потребуется:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                lines.forEach {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(HripsIcons.Check, null, Modifier.size(18.dp).padding(top = 2.dp), tint = cs.primary)
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (prompt.kind == ExtPrompt.Kind.INSTALL) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Разрешить в приватных вкладках", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = priv, onCheckedChange = { priv = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true, priv) }) {
                Text(if (prompt.kind == ExtPrompt.Kind.INSTALL) "Установить" else "Разрешить")
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(false, false) }) { Text("Отмена") } },
    )
}

/** Окно расширения поверх страницы: внутри обычная веб-страница расширения. */
@Composable
fun ExtensionPopupDialog(popup: ExtPopup, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = cs.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = 620.dp),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ExtIcon(popup.extension, null, 24.dp)
                    Text(displayName(popup.extension), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = onClose) { Icon(HripsIcons.Close, "Закрыть") }
                }
                AndroidView(
                    factory = { ctx -> GeckoView(ctx).apply { setSession(popup.session) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 540.dp).weight(1f, fill = false)
                        .padding(8.dp),
                )
            }
        }
    }
}

/** Сообщение об ошибке установки и т. п. */
@Composable
fun ExtensionErrorDialog(message: String, onDismiss: () -> Unit) {
    HripsDialog(
        icon = HripsIcons.Alert,
        onDismissRequest = onDismiss,
        title = { Text("Расширения") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
    )
}
