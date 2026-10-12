@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.abs

@Composable
fun ExtIcon(ext: WebExtension, action: WebExtension.Action?, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image = action?.icon ?: ext.metaData.icon
    var bmp by remember(ext.id) { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(ext.id, image, px) {
        var active = true
        try {
            image.getBitmap(px).accept({ b -> if (active && b != null) bmp = b.asImageBitmap() }, { })
        } catch (e: Exception) {
        }
        onDispose { active = false }
    }
    val b = bmp
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (b != null) {
            Image(b, null, Modifier.size(size))
        } else {
            ShapeBadge(
                HripsIcons.Puzzle, size, badge = Badge.COOKIE6,
                container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

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

private fun canOpen(ext: WebExtension, action: WebExtension.Action?, isPrivate: Boolean): Boolean {
    val md = ext.metaData
    return md.enabled && !(isPrivate && !md.allowedInPrivateBrowsing) && action != null && action.enabled != false
}

@Composable
fun ExtensionsScreen(
    extensions: Extensions,
    isPrivate: Boolean,
    startOnStore: Boolean,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(if (startOnStore) 1 else 0) }
    var toRemove by remember { mutableStateOf<WebExtension?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AmoAddon>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(tab, query, reload) {
        if (tab != 1) return@LaunchedEffect
        loading = true
        failed = false
        if (query.isNotBlank()) delay(450)
        Amo.search(query.trim())
            .onSuccess { results = it }
            .onFailure { failed = true }
        loading = false
    }

    val installed = extensions.installed
    val mine = installed.filter { !it.isBuiltIn }
    val builtIn = installed.filter { it.isBuiltIn }
    val toggle: (String) -> Unit = { id -> expanded = if (expanded == id) null else id }

    var shown by remember { mutableStateOf(tab) }
    val slide = remember { Animatable(0f) }
    LaunchedEffect(tab) {
        if (shown == tab) {
            slide.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f))
            return@LaunchedEffect
        }
        val dir = if (tab > shown) 1f else -1f
        slide.animateTo(-dir, tween(90))
        shown = tab
        slide.snapTo(dir)
        slide.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f))
    }

    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    Box(
        Modifier.fillMaxSize().pointerInput(tab) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    if (total < -swipeThreshold && tab == 0) tab = 1 else if (total > swipeThreshold && tab == 1) tab = 0
                },
                onDragCancel = { total = 0f },
                onHorizontalDrag = { _, dx -> total += dx },
            )
        },
    ) {
    ListPage(
        title = "Расширения",
        onBack = onBack,
        header = {
            Box(Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                PillTabs(listOf("Установленные (${installed.size})", "Каталог"), tab, { tab = it })
            }
        },
        listModifier = Modifier.graphicsLayer {
            translationX = slide.value * size.width * 0.18f
            alpha = (1f - abs(slide.value)).coerceIn(0f, 1f)
        },
    ) {
        if (shown == 0) {
            if (mine.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            HripsIcons.Puzzle, "Расширений пока нет",
                            "Ставьте расширения из каталога Mozilla: блокировщики, менеджеры паролей, тёмные темы и другие.",
                            badge = Badge.COOKIE6,
                        )
                        Button(shapes = ButtonDefaults.shapes(), onClick = { tab = 1 }) { Text("Открыть каталог") }
                    }
                }
            } else {
                item(key = "h-mine") { ExtHeader("Ваши расширения") }
                extGroup(mine, extensions, isPrivate, expanded, toggle, onClose = onBack) { toRemove = it }
            }
            if (builtIn.isNotEmpty()) {
                item(key = "h-builtin") { ExtHeader("Встроенные") }
                extGroup(builtIn, extensions, isPrivate, expanded, toggle, onClose = onBack) { toRemove = it }
            }
        } else {
            storeItems(extensions, query, { query = it }, results, loading, failed) { reload++ }
        }
    }
    }

    toRemove?.let { ext ->
        HripsDialog(
            icon = HripsIcons.Trash,
            onDismissRequest = { toRemove = null },
            title = { Text("Удалить «${displayName(ext)}»?") },
            text = { Text("Расширение и его данные будут удалены с этого устройства.") },
            confirmButton = { HripsTextButton(onClick = { extensions.uninstall(ext); toRemove = null }) { Text("Удалить") } },
            dismissButton = { HripsTextButton(onClick = { toRemove = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PillTabs(labels: List<String>, current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val pos by animateFloatAsState(current.toFloat(), MaterialTheme.motionScheme.defaultSpatialSpec<Float>(), label = "pillTabs")
    Box(modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth(1f / labels.size)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = pos * size.width
                    shape = CircleShape
                    clip = true
                }
                .drawBehind { drawRect(cs.primary) },
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                val c = lerp(cs.onSurfaceVariant, cs.onPrimary, (1f - abs(pos - i)).coerceIn(0f, 1f))
                Surface(
                    onClick = { onSelect(i) }, shape = CircleShape, color = Color.Transparent, contentColor = c,
                    modifier = Modifier.weight(1f).fillMaxHeight().semantics {
                        role = Role.Tab
                        selected = i == current
                    },
                ) {
                    Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtHeader(text: String) {
    Text(
        text,
        Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    )
}

private fun LazyListScope.extGroup(
    list: List<WebExtension>,
    extensions: Extensions,
    isPrivate: Boolean,
    expanded: String?,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
    onRemove: (WebExtension) -> Unit,
) {
    itemsIndexed(list, key = { _, e -> e.id }) { i, ext ->
        Box(Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth()) {
            InstalledCard(
                ext, extensions, isPrivate, segShape(i, list.size), expanded == ext.id,
                onToggle = { onToggle(ext.id) },
                onOpen = { onClose(); extensions.actions[ext.id]?.click() },
                onRemove = { onRemove(ext) },
            )
        }
    }
}

@Composable
private fun InstalledCard(
    ext: WebExtension,
    extensions: Extensions,
    isPrivate: Boolean,
    shape: Shape,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val md = ext.metaData
    val action = extensions.actions[ext.id]
    val usable = canOpen(ext, action, isPrivate)
    val sub = listOfNotNull(
        md.version?.takeIf { it.isNotBlank() }?.let { "v$it" },
        when {
            !md.enabled -> "выключено"
            isPrivate && !md.allowedInPrivateBrowsing -> "выключено в приватных вкладках"
            action == null -> "работает в фоне"
            else -> null
        },
    ).joinToString(" · ")
    Surface(onClick = onToggle, shape = shape, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ExtIconWithBadge(ext, action, 44.dp)
                Column(Modifier.weight(1f).alpha(if (md.enabled) 1f else 0.6f)) {
                    Text(displayName(ext), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(sub, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (!ext.isBuiltIn) {
                    Switch(
                        checked = md.enabled, onCheckedChange = { extensions.setEnabled(ext, it) },
                        modifier = Modifier.semantics { contentDescription = displayName(ext) },
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
                        Switch(
                            checked = md.allowedInPrivateBrowsing, onCheckedChange = { extensions.setAllowedInPrivate(ext, it) },
                            modifier = Modifier.semantics { contentDescription = "В приватных вкладках" },
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        md.optionsPageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            HripsTextButton(onClick = { extensions.openTab(url, true, false) }) { Text("Настройки") }
                        }
                        if (!ext.isBuiltIn) {
                            HripsTextButton(onClick = onRemove) { Text("Удалить", color = cs.error) }
                        }
                        if (usable) {
                            FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = onOpen) { Text("Открыть") }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.storeItems(
    extensions: Extensions,
    query: String,
    onQuery: (String) -> Unit,
    results: List<AmoAddon>,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    item(key = "search") {
        Box(Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth()) { PageSearchField("Поиск расширений", query, onQuery) }
    }
    item(key = "h-store") { ExtHeader(if (query.isBlank()) "Самые популярные" else "Результаты поиска") }
    when {
        loading && results.isEmpty() -> item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        failed -> item(key = "failed") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(HripsIcons.Puzzle, "Каталог недоступен", "Проверьте соединение и попробуйте ещё раз.")
                FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = onRetry) { Text("Повторить") }
            }
        }
        results.isEmpty() -> item(key = "none") { EmptyState(HripsIcons.Search, "Ничего не найдено", "Попробуйте другой запрос.") }
        else -> {
            itemsIndexed(results, key = { _, a -> a.guid }) { i, a ->
                Box(Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth()) { StoreCard(a, extensions, segShape(i, results.size)) }
            }
            item(key = "footer") {
                Text(
                    "Каталог: addons.mozilla.org. Расширения подписаны Mozilla, но написаны сторонними авторами.",
                    Modifier.widthIn(max = HripsLayout.PageMaxWidth).fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StoreCard(a: AmoAddon, extensions: Extensions, shape: Shape) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = shape, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
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
                extensions.installed.any { it.id == a.guid } -> FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = {}, enabled = false) { Text("Установлено") }
                a.guid in extensions.installing -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                a.xpiUrl != null -> Button(shapes = ButtonDefaults.shapes(), onClick = { extensions.install(a.guid, a.xpiUrl) }) { Text("Установить") }
            }
        }
    }
}

private fun countLabel(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f млн", n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000} тыс."
    else -> n.toString()
}

private fun permissionLabel(p: String): String? = when (p) {
    "tabs" -> "Видеть адреса и названия открытых вкладок"
    "webRequest", "webRequestBlocking", "webRequestAuthProvider" -> "Перехватывать и менять сетевые запросы"
    "declarativeNetRequest", "declarativeNetRequestWithHostAccess" -> "Блокировать и перенаправлять сетевые запросы"
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
    "browserSettings" -> "Менять настройки браузера"
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
    "tabHide" -> "Скрывать и показывать вкладки"
    "devtools" -> "Расширять инструменты разработчика"
    "dns" -> "Определять адреса сайтов (DNS)"
    "contextualIdentities" -> "Управлять контейнерами вкладок"
    "pkcs11" -> "Подключать модули безопасности (PKCS#11)"
    "storage", "unlimitedStorage", "alarms", "idle", "theme", "scripting", "declarativeNetRequestFeedback",
    "webRequestFilterResponse", "webRequestFilterResponse.serviceWorkerScript", "menus.overrideContext" -> null
    else -> "Разрешение: $p"
}

private fun dataCollectionLine(keys: List<String>): String? {
    val labels = keys.mapNotNull {
        when (it) {
            "none" -> null
            "authenticationInfo" -> "данные для входа"
            "bookmarksInfo" -> "закладки"
            "browsingActivity" -> "историю просмотров"
            "financialAndPaymentInfo" -> "платёжные данные"
            "healthInfo" -> "данные о здоровье"
            "locationInfo" -> "местоположение"
            "personalCommunications" -> "личную переписку"
            "personallyIdentifyingInfo" -> "данные, по которым вас можно опознать"
            "searchTerms" -> "поисковые запросы"
            "websiteActivity" -> "действия на сайтах"
            "websiteContent" -> "содержимое сайтов"
            "technicalAndInteraction" -> "технические данные и данные о работе с расширением"
            else -> it
        }
    }
    return if (labels.isEmpty()) null else "Автор собирает: " + labels.joinToString(", ")
}

private val allSites = setOf("<all_urls>", "*://*/*", "http://*/*", "https://*/*", "*://*/", "http://*/", "https://*/")

private fun originLines(origins: List<String>): List<String> {
    if (origins.isEmpty()) return emptyList()
    if (origins.any { it in allSites }) return listOf("Доступ к вашим данным на всех сайтах")
    val lines = ArrayList<String>(2)
    val web = origins.filter { !it.startsWith("file:", ignoreCase = true) }
    if (web.size != origins.size) lines += "Доступ к локальным файлам"
    val hosts = web.map { it.substringAfter("://").substringBefore("/").removePrefix("*.") }.filter { it.isNotBlank() }.distinct()
    if (hosts.isNotEmpty()) {
        val shown = hosts.take(3).joinToString(", ")
        lines += "Доступ к данным на сайтах: $shown" + if (hosts.size > 3) " и ещё ${hosts.size - 3}" else ""
    }
    return lines
}

@Composable
fun ExtensionPromptDialog(prompt: ExtPrompt, onAnswer: (allow: Boolean, privateAllowed: Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var priv by remember(prompt) { mutableStateOf(false) }
    val name = displayName(prompt.extension)
    val lines = remember(prompt) {
        prompt.permissions.mapNotNull { permissionLabel(it) } + originLines(prompt.origins) +
            listOfNotNull(dataCollectionLine(prompt.dataCollection))
    }
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when {
                        lines.isEmpty() -> "Расширению не нужны особые разрешения."
                        prompt.kind == ExtPrompt.Kind.UPDATE -> "Для обновления нужны новые разрешения:"
                        else -> "Расширению потребуется:"
                    },
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
            HripsTextButton(onClick = { onAnswer(true, priv) }) {
                Text(if (prompt.kind == ExtPrompt.Kind.INSTALL) "Установить" else "Разрешить")
            }
        },
        dismissButton = { HripsTextButton(onClick = { onAnswer(false, false) }) { Text("Отмена") } },
    )
}

@Composable
fun ExtensionPopupDialog(popup: ExtPopup, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = HripsShapes.L,
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
                key(popup.session) {
                    AndroidView(
                        factory = { ctx -> GeckoView(ctx).apply { setSession(popup.session) } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 540.dp).weight(1f, fill = false)
                            .padding(8.dp),
                        onRelease = { it.releaseSession() },
                    )
                }
            }
        }
    }
}

@Composable
fun ExtensionErrorDialog(message: String, onDismiss: () -> Unit) {
    HripsDialog(
        icon = HripsIcons.Alert,
        onDismissRequest = onDismiss,
        title = { Text("Расширения") },
        text = { Text(message) },
        confirmButton = { HripsTextButton(onClick = onDismiss) { Text("Понятно") } },
    )
}
