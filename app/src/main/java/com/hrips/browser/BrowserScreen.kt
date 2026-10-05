package com.hrips.browser

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoView

private fun Tab.label() = if (home) "Начальная страница" else title.ifBlank { url }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BrowserScreen(browser: Browser) {
    val tab = browser.current
    val store = browser.store
    val app = LocalContext.current.applicationContext as HripsApp
    // Адаптивность: планшеты и широкие окна (>= 600dp) получают десктопную раскладку
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var showTabs by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var libPage by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    // Разовый выбор движка с главной страницы: сбрасывается после поиска
    var oneOff by remember { mutableStateOf<SearchEngine?>(null) }
    // Если открыта выдача поисковика, в строке показываем запрос и логотип движка, а не ссылку
    val parsed = remember(tab.url, tab.home) { if (tab.home) null else parseSearch(tab.url) }
    val shownEngine = parsed?.first ?: oneOff ?: SearchEngines.current
    val pickEngine: (SearchEngine) -> Unit = { e ->
        if (parsed != null) tab.load(parsed.second, e) else oneOff = e
    }

    val canReturnToPage = tab.home && tab.url.isNotBlank()
    BackHandler(enabled = canReturnToPage || tab.canGoBack) {
        if (canReturnToPage) tab.home = false else tab.session.goBack()
    }

    DisposableEffect(tab.session) {
        val s = tab.session
        browser.setTabActive(s, true)
        onDispose { browser.setTabActive(s, false) }
    }

    // Полноэкранное видео: прячем интерфейс браузера и системные панели
    val fs = tab.fullscreen
    val activity = LocalContext.current as? Activity
    LaunchedEffect(fs) {
        val w = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(w, w.decorView)
        if (fs) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(enabled = fs) { tab.session.exitFullScreen() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().imePadding()) {
        if (!fs) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            if (wide) TabStrip(browser)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (wide) 8.dp else 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (wide) {
                    IconButton(onClick = { tab.session.goBack() }, enabled = tab.canGoBack && !tab.home) {
                        Icon(HripsIcons.Back, "Назад")
                    }
                    IconButton(onClick = { tab.session.goForward() }, enabled = tab.canGoForward && !tab.home) {
                        Icon(HripsIcons.Forward, "Вперёд")
                    }
                    IconButton(onClick = { if (tab.loading) tab.session.stop() else tab.session.reload() }, enabled = !tab.home) {
                        Icon(if (tab.loading) HripsIcons.Close else HripsIcons.Refresh, "Обновить")
                    }
                    IconButton(onClick = { tab.goHome() }) { Icon(HripsIcons.Home, "Домой") }
                }
                // На широком экране адресная строка по центру и не растягивается на всю ширину
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    AddressBar(
                        tab = tab,
                        store = store,
                        onSearch = { showSearch = true },
                        engine = shownEngine,
                        query = parsed?.second,
                        onPickEngine = pickEngine,
                        onMenu = { showLibrary = true },
                        showMenu = !wide,
                        downloads = browser.downloads,
                        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                    )
                }
                if (wide) {
                    DownloadsButton(browser.downloads, onClick = { showDownloads = true })
                    TabCounterButton(browser.tabs.size) { showTabs = true }
                    IconButton(onClick = { libPage = 0; showLibrary = true }) { Icon(HripsIcons.Menu, "Меню") }
                    Spacer(Modifier.width(48.dp)) // балансирует левые кнопки (4 шт.), чтобы строка была по центру
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (tab.loading && !tab.home) {
                LinearWavyProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }

        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab.home) {
                StartPage(store = store, wallpaper = app.wallpaper.image, onOpen = { tab.load(it) }, onSearch = { showSearch = true })
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { GeckoView(it) },
                    update = { if (it.session !== tab.session) it.setSession(tab.session) },
                )
            }
        }

        // Нижняя плавающая панель только на узких экранах (телефон)
        if (!fs && !wide) {
            Box(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                HorizontalFloatingToolbar(expanded = true) {
                    IconButton(onClick = { tab.session.goBack() }, enabled = tab.canGoBack && !tab.home) {
                        Icon(HripsIcons.Back, "Назад")
                    }
                    IconButton(onClick = { tab.session.goForward() }, enabled = tab.canGoForward && !tab.home) {
                        Icon(HripsIcons.Forward, "Вперёд")
                    }
                    IconButton(onClick = { if (tab.loading) tab.session.stop() else tab.session.reload() }, enabled = !tab.home) {
                        Icon(if (tab.loading) HripsIcons.Close else HripsIcons.Refresh, "Обновить")
                    }
                    IconButton(onClick = { browser.newTab() }) { Icon(HripsIcons.Add, "Новая вкладка") }
                    TabCounterButton(browser.tabs.size) { showTabs = true }
                }
            }
        } else if (wide && !fs) {
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    // Страница загрузок поверх браузера
    AnimatedVisibility(
        visible = showDownloads,
        enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
    ) {
        DownloadsScreen(browser.downloads, onBack = { showDownloads = false })
    }
    // Поисковая панель: разовый выбор движка, история запросов, подсказки
    AnimatedVisibility(
        visible = showSearch,
        enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.96f),
        exit = fadeOut(tween(120)),
    ) {
        SearchPanel(
            store = store,
            initial = if (tab.home) "" else parsed?.second ?: tab.url,
            initialEngine = shownEngine,
            onSubmit = { text, engine ->
                if (isSearch(text)) store.addSearch(text)
                tab.load(text, engine)
                oneOff = null
                showSearch = false
            },
            onDismiss = { showSearch = false },
        )
    }
    // Плашка "Скачать файл?" и иконка, летящая к кнопке загрузок
    DownloadPrompt(browser.downloads)
    DownloadFlightOverlay(browser.downloads.fx)
    }

    if (showTabs) {
        ModalBottomSheet(onDismissRequest = { showTabs = false }) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(max = 520.dp),
            ) {
                gridItemsIndexed(browser.tabs) { i, t ->
                    val selected = i == browser.currentIndex
                    ElevatedCard(
                        onClick = { browser.currentIndex = i; showTabs = false },
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.label(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(if (t.home) "" else t.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { browser.closeTab(i) }) { Icon(HripsIcons.Close, "Закрыть") }
                        }
                    }
                }
            }
        }
    }

    if (showLibrary) {
        val downloads = browser.downloads
        val context = LocalContext.current
        ModalBottomSheet(onDismissRequest = { showLibrary = false }) {
            ListItem(
                headlineContent = { Text("Настройки") },
                leadingContent = { Icon(HripsIcons.Settings, null) },
                modifier = Modifier.clickable { showLibrary = false; showSettings = true },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Версия для ПК", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = tab.desktopMode, onCheckedChange = { tab.setDesktop(it) })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Блокировка рекламы", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            browser.adBlock.error != null -> "Не загрузилась: " + browser.adBlock.error
                            browser.adBlock.extension == null -> "Загружается…"
                            else -> "uBlock Origin"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = browser.adBlock.enabled,
                    enabled = browser.adBlock.extension != null,
                    onCheckedChange = { browser.adBlock.setBlocking(it); tab.session.reload() },
                )
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = libPage == 0, onClick = { libPage = 0 }, label = { Text("Закладки") })
                FilterChip(selected = libPage == 1, onClick = { libPage = 1 }, label = { Text("История") })
                FilterChip(selected = false, onClick = { showLibrary = false; showDownloads = true }, label = { Text("Загрузки") })
                Spacer(Modifier.weight(1f))
                if (libPage == 1) TextButton(onClick = { store.clearHistory() }) { Text("Очистить") }
            }
            val list = if (libPage == 0) store.bookmarks else store.history
            if (list.isEmpty()) {
                Text(
                    if (libPage == 0) "Закладок пока нет. Нажмите на звёздочку в адресной строке." else "История пуста.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(list.toList()) { e ->
                    ListItem(
                        headlineContent = { Text(e.title.ifBlank { e.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(e.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            IconButton(onClick = { if (libPage == 0) store.removeBookmark(e) else store.removeHistory(e) }) {
                                Icon(HripsIcons.Close, "Удалить")
                            }
                        },
                        modifier = Modifier.clickable { tab.load(e.url); showLibrary = false },
                    )
                }
            }
        }
    }

    if (showSettings) {
        val context = LocalContext.current
        var clearHistory by remember { mutableStateOf(true) }
        var clearCookies by remember { mutableStateOf(false) }
        var clearCache by remember { mutableStateOf(true) }
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
                Text("Поисковая система", style = MaterialTheme.typography.titleMedium)
                SearchEngines.all.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { store.setSearchEngine(e) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = SearchEngines.current == e, onClick = { store.setSearchEngine(e) })
                        Text(e.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Подсказки при вводе", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Вводимый текст отправляется выбранному поисковику",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = store.suggestionsOn, onCheckedChange = { store.updateSuggestions(it) })
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text("Обои главной страницы", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.wallpaper.pick?.invoke() }) { Text("Выбрать фото") }
                    TextButton(enabled = app.wallpaper.image != null, onClick = { app.wallpaper.clear() }) { Text("Убрать") }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Защита от трекеров", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Если сайт работает неправильно, попробуйте выключить и перезагрузить страницу",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = store.trackingProtection, onCheckedChange = { browser.setTracking(it) })
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text("Очистить данные", style = MaterialTheme.typography.titleMedium)
                listOf(
                    Triple("История", clearHistory) { v: Boolean -> clearHistory = v },
                    Triple("Cookies и данные сайтов (выход из аккаунтов)", clearCookies) { v: Boolean -> clearCookies = v },
                    Triple("Кэш", clearCache) { v: Boolean -> clearCache = v },
                ).forEach { (label, checked, set) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { set(!checked) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { set(it) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = clearHistory || clearCookies || clearCache,
                    onClick = {
                        if (clearHistory) { store.clearHistory(); store.clearSearches() }
                        browser.clearData(clearCookies, clearCache) {
                            Toast.makeText(context, "Данные очищены", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("Очистить") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Диалоги страниц: alert/confirm/prompt, выпадающие списки, повторная отправка формы
    PromptHost(browser.prompts)

    // Запрос разрешения сайта (камера, микрофон, местоположение)
    browser.permissions.queue.firstOrNull()?.let { req ->
        AlertDialog(
            onDismissRequest = { browser.permissions.answer(req, false) },
            title = { Text(req.origin) },
            text = { Text("Сайт хочет ${req.what}.") },
            confirmButton = { TextButton(onClick = { browser.permissions.answer(req, true) }) { Text("Разрешить") } },
            dismissButton = { TextButton(onClick = { browser.permissions.answer(req, false) }) { Text("Запретить") } },
        )
    }
}

@Composable
private fun AddressBar(
    tab: Tab,
    store: Store,
    onSearch: () -> Unit,
    engine: SearchEngine,
    query: String?,
    onPickEngine: (SearchEngine) -> Unit,
    onMenu: () -> Unit,
    showMenu: Boolean,
    downloads: Downloads,
    modifier: Modifier = Modifier,
) {
    // Логотип движка с выбором: на главной и на странице выдачи. На обычных сайтах - замок и адрес.
    val withPicker = tab.home || query != null
    val shown = when {
        tab.home -> ""
        query != null -> query
        else -> tab.url.removePrefix("https://")
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (withPicker) Box(Modifier.padding(start = 6.dp)) { EnginePicker(engine, onPickEngine) }
            // Нажатие на текст открывает поисковую панель (ввод, подсказки, история)
            Row(
                Modifier.weight(1f).clickable(onClick = onSearch).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!withPicker) {
                    Icon(
                        if (tab.url.startsWith("https://")) HripsIcons.Lock else HripsIcons.Info,
                        null,
                        Modifier.padding(start = 16.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    shown.ifEmpty { "Искать или задать вопрос" },
                    modifier = Modifier.padding(start = if (withPicker) 8.dp else 12.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (shown.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { store.toggleBookmark(tab.url, tab.title) }, enabled = !tab.home) {
                val saved = store.isBookmarked(tab.url)
                Icon(
                    if (saved) HripsIcons.StarFilled else HripsIcons.Star, "Закладка",
                    tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // На телефоне отдельной кнопки загрузок нет: иконка летит к меню, кольцо рисуется вокруг него
            if (showMenu) DownloadsButton(downloads, onClick = onMenu, icon = HripsIcons.Menu, description = "Меню")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabStrip(browser: Browser) {
    val listState = rememberLazyListState()
    // Перетаскивание вкладки: долгое нажатие, затем движение влево/вправо
    var draggingId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f, fill = false), state = listState, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(browser.tabs, key = { _, t -> System.identityHashCode(t) }) { i, t ->
                val id = System.identityHashCode(t)
                val dragging = draggingId == id
                val selected = i == browser.currentIndex
                Surface(
                    onClick = { browser.currentIndex = i },
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                    shadowElevation = if (dragging) 8.dp else 0.dp,
                    modifier = Modifier
                        .widthIn(min = 120.dp, max = 220.dp)
                        .then(if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
                        .graphicsLayer { translationX = if (dragging) dragOffset else 0f }
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggingId = id; dragOffset = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.x
                                    val infos = listState.layoutInfo.visibleItemsInfo
                                    val me = infos.firstOrNull { it.key == id } ?: return@detectDragGesturesAfterLongPress
                                    val from = browser.tabs.indexOfFirst { System.identityHashCode(it) == id }
                                    // Раскладка ещё не обновилась после прошлой перестановки: ждём
                                    if (from < 0 || me.index != from) return@detectDragGesturesAfterLongPress
                                    val center = me.offset + me.size / 2f + dragOffset
                                    val target = infos.firstOrNull { it.key != id && center >= it.offset && center <= it.offset + it.size }
                                    if (target != null) {
                                        browser.moveTab(from, target.index)
                                        val newOffset = if (target.index > from) target.offset + target.size - me.size else target.offset
                                        dragOffset += me.offset - newOffset
                                    }
                                },
                                onDragEnd = { draggingId = null; dragOffset = 0f },
                                onDragCancel = { draggingId = null; dragOffset = 0f },
                            )
                        },
                ) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (t.home) {
                            Icon(HripsIcons.Home, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Favicon(t.url, 18.dp) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(18.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            t.label().firstOrNull()?.uppercase() ?: "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.label(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { browser.closeTab(i) }, modifier = Modifier.size(36.dp)) {
                            Icon(HripsIcons.Close, "Закрыть", Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        IconButton(onClick = { browser.newTab() }) { Icon(HripsIcons.Add, "Новая вкладка") }
    }
}

/** Счётчик вкладок: скруглённый квадрат-контур с числом внутри. Нажатие открывает список вкладок. */
@Composable
private fun TabCounterButton(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        val color = LocalContentColor.current
        Box(
            Modifier.size(24.dp).border(2.dp, color, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = count,
                transitionSpec = { (fadeIn(tween(150)) + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut(tween(100)) + scaleOut(targetScale = 0.6f)) },
                label = "tabCount",
            ) { n ->
                Text(
                    if (n > 99) ":)" else n.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = if (n > 9) 10.sp else 12.sp, fontWeight = FontWeight.SemiBold),
                    color = color,
                )
            }
        }
    }
}
