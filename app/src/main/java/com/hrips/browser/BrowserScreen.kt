package com.hrips.browser

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.alpha
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

internal fun Tab.label() = when {
    home && isPrivate -> "Приватная вкладка"
    home -> "Начальная страница"
    else -> title.ifBlank { url }
}

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
    var showSitePerms by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
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
        tab.ensureLoaded() // вкладка с прошлого запуска грузится, когда её открыли
        browser.setTabActive(s, true)
        onDispose { browser.setTabActive(s, false) }
    }

    // Полноэкранное видео: прячем интерфейс браузера и системные панели
    // Полноэкранный режим из меню: прячет интерфейс браузера, как видео на весь экран
    var immersive by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { immersive = false }
    val fs = tab.fullscreen || immersive
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
    BackHandler(enabled = fs) { if (tab.fullscreen) tab.session.exitFullScreen() else immersive = false }

    // Приватная вкладка: окно закрыто от скриншотов и миниатюры в списке приложений
    val priv = tab.isPrivate
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(priv, store.allowPrivateShots) {
        val w = activity?.window ?: return@LaunchedEffect
        if (priv && !store.allowPrivateShots) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    // Приватная тема всегда тёмная: значки системных панелей должны быть светлыми
    LaunchedEffect(priv, systemDark) {
        val w = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(w, w.decorView)
        c.isAppearanceLightStatusBars = !priv && !systemDark
        c.isAppearanceLightNavigationBars = !priv && !systemDark
    }

    // Ссылка на GeckoView нужна, чтобы снять превью страницы перед открытием переключателя вкладок
    val viewRef = remember { arrayOfNulls<GeckoView>(1) }
    val requestTabs: () -> Unit = { captureThumbnail(activity, viewRef[0], tab) { showTabs = true } }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().imePadding()) {
        if (!fs) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            if (wide) TabStrip(browser)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (wide) 8.dp else 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (wide) {
                    IconButton(modifier = Modifier.size(40.dp), onClick = { tab.session.goBack() }, enabled = tab.canGoBack && !tab.home) {
                        Icon(HripsIcons.Back, "Назад")
                    }
                    IconButton(modifier = Modifier.size(40.dp), onClick = { tab.session.goForward() }, enabled = tab.canGoForward && !tab.home) {
                        Icon(HripsIcons.Forward, "Вперёд")
                    }
                    IconButton(modifier = Modifier.size(40.dp), onClick = { if (tab.loading) tab.session.stop() else tab.session.reload() }, enabled = !tab.home) {
                        Icon(if (tab.loading) HripsIcons.Close else HripsIcons.Refresh, "Обновить")
                    }
                    IconButton(modifier = Modifier.size(40.dp), onClick = { tab.goHome() }) { Icon(HripsIcons.Home, "Домой") }
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
                        onMenu = { showTools = true },
                        showMenu = !wide,
                        downloads = browser.downloads,
                        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                    )
                }
                if (wide) {
                    // Сначала счётчик вкладок, затем загрузки, затем меню
                    TabCounterButton(browser.tabs.size, Modifier.size(40.dp)) { requestTabs() }
                    DownloadsButton(browser.downloads, onClick = { showDownloads = true }, size = 40.dp)
                    IconButton(modifier = Modifier.size(40.dp), onClick = { showTools = true }) { Icon(HripsIcons.MoreVert, "Инструменты") }
                    Spacer(Modifier.width(40.dp)) // балансирует левые кнопки (4 шт.), чтобы строка была по центру
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (tab.loading && !tab.home) {
                LinearWavyProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }

        if (findOpen && !tab.home) {
            key(tab.session) { FindBar(tab.session, onClose = { findOpen = false }) }
        }

        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab.home && tab.isPrivate) {
                PrivateStartPage(
                    privateCount = browser.privateCount,
                    screenshotsBlocked = !store.allowPrivateShots,
                    onSearch = { showSearch = true },
                    onCloseAll = { browser.closePrivateTabs() },
                )
            } else if (tab.home) {
                StartPage(store = store, wallpaper = app.wallpaper.image, onOpen = { tab.load(it) }, onSearch = { showSearch = true })
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> HripsGeckoView(ctx).also { viewRef[0] = it } },
                    update = {
                        it.incognito = tab.isPrivate
                        // Системное автозаполнение Android (Google, Bitwarden, 1Password...).
                        // В приватной вкладке выключено, чтобы менеджер паролей не запоминал логины оттуда.
                        it.setAutofillEnabled(!tab.isPrivate)
                        if (it.session !== tab.session) it.setSession(tab.session)
                    },
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
                    IconButton(onClick = { browser.newTab(incognito = tab.isPrivate) }) { Icon(HripsIcons.Add, "Новая вкладка") }
                    TabCounterButton(browser.tabs.size) { requestTabs() }
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
    // Настройки: отдельная страница, как загрузки
    AnimatedVisibility(
        visible = showSettings,
        enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
    ) {
        SettingsScreen(
            browser = browser,
            app = app,
            onBack = { showSettings = false },
            onSitePermissions = { showSitePerms = true },
            onOpenDownloads = { showSettings = false; showDownloads = true },
        )
    }
    // Переключатель вкладок: "Вкладки / Приватный", карусель карточек с превью
    AnimatedVisibility(
        visible = showTabs,
        enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
    ) {
        TabSwitcher(
            browser = browser,
            onClose = { showTabs = false },
            onHistory = { showTabs = false; libPage = 1; showLibrary = true },
        )
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
                if (isSearch(text) && !tab.isPrivate) store.addSearch(text)
                tab.load(text, engine)
                oneOff = null
                showSearch = false
            },
            onDismiss = { showSearch = false },
            incognito = tab.isPrivate,
        )
    }
    // Плашка "Скачать файл?" и иконка, летящая к кнопке загрузок
    DownloadPrompt(browser.downloads)
    DownloadFlightOverlay(browser.downloads.fx)

    if (showSitePerms) {
        SitePermissionsSheet(browser.runtime, browser.permissions.sites) { showSitePerms = false }
    }

    // Первый запуск: все разрешения одним заходом
    if (!store.firstRunDone) FirstRunScreen(store, browser.permissions)

    // Меню "три точки" у правого верхнего края
    Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = if (wide) 100.dp else 60.dp, end = 12.dp)) {
        ToolsMenu(
            expanded = showTools,
            onDismiss = { showTools = false },
            browser = browser,
            tab = tab,
            onFind = { findOpen = true },
            onLibrary = { page -> libPage = page; showLibrary = true },
            onDownloads = { showDownloads = true },
            onSettings = { showSettings = true },
            onScreenshot = { PageActions.screenshot(activity, viewRef[0], tab, browser.downloads) },
            onFullscreen = { immersive = true },
        )
    }

    // Выход из полноэкранного режима (кроме жеста «назад»)
    if (immersive && !tab.fullscreen) {
        FilledTonalIconButton(
            onClick = { immersive = false },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).size(40.dp).alpha(0.7f),
        ) { Icon(HripsIcons.Close, "Выйти из полноэкранного режима") }
    }
    }

    if (showLibrary) {
        LibrarySheet(
            store = store,
            page = libPage,
            onPage = { libPage = it },
            onOpen = { tab.load(it); showLibrary = false },
            onDownloads = { showLibrary = false; showDownloads = true },
            onClose = { showLibrary = false },
        )
    }

    // Контекстное меню страницы (долгое нажатие / правая кнопка)
    browser.contextMenu?.let { info -> ContextMenuSheet(info, browser) { browser.contextMenu = null } }

    // Диалоги страниц: alert/confirm/prompt, выпадающие списки, повторная отправка формы
    PromptHost(browser.prompts)

    // Ссылка для другого приложения (intent:, market:, схемы приложений)
    browser.external.pending?.let { r ->
        HripsDialog(
            icon = HripsIcons.Open,
            onDismissRequest = { browser.external.dismiss() },
            title = { Text("Открыть в другом приложении?") },
            text = { Text(r.uri.take(200)) },
            confirmButton = {
                TextButton(onClick = { browser.external.open(r) { fallback -> tab.load(fallback) } }) { Text("Открыть") }
            },
            dismissButton = { TextButton(onClick = { browser.external.dismiss() }) { Text("Отмена") } },
        )
    }

    // Запрос разрешения сайта (камера, микрофон, местоположение)
    browser.permissions.queue.firstOrNull()?.let { req ->
        HripsDialog(
            icon = HripsIcons.Shield,
            onDismissRequest = { browser.permissions.answer(req, false) },
            title = { Text(req.origin) },
            text = {
                Column {
                    Text("Сайт хочет ${req.what}.")
                    if (req.canRemember) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp).clickable { req.remember = !req.remember },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = req.remember, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text("Запомнить для этого сайта")
                        }
                    } else if (req.note != null) {
                        Text(
                            req.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
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
    var showSecurity by remember { mutableStateOf(false) }
    val shown = when {
        tab.home -> ""
        query != null -> query
        else -> tab.url.removePrefix("https://")
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (withPicker) Box(Modifier.padding(start = 4.dp)) { EnginePicker(engine, onPickEngine) }
            // Нажатие на текст открывает поисковую панель (ввод, подсказки, история)
            Row(
                Modifier.weight(1f).clickable(onClick = onSearch).padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!withPicker) {
                    // Иконка берётся из проверки сертификата движком, а не из того, с чего начинается адрес
                    val secureNow = tab.trust == Trust.SECURE
                    Icon(
                        if (secureNow) HripsIcons.Lock else HripsIcons.Info,
                        "Безопасность соединения",
                        Modifier.padding(start = 16.dp).size(18.dp).clickable { showSecurity = true },
                        tint = if (tab.trust == Trust.WARNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
            IconButton(modifier = Modifier.size(40.dp), onClick = { store.toggleBookmark(tab.url, tab.title) }, enabled = !tab.home) {
                val saved = store.isBookmarked(tab.url)
                Icon(
                    if (saved) HripsIcons.StarFilled else HripsIcons.Star, "Закладка",
                    tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showSecurity) SecurityDialog(tab) { showSecurity = false }
            // На телефоне отдельной кнопки загрузок нет: иконка летит к меню, кольцо рисуется вокруг него
            if (showMenu) DownloadsButton(downloads, onClick = onMenu, icon = HripsIcons.MoreVert, description = "Инструменты", size = 40.dp)
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        .height(36.dp).widthIn(min = 120.dp, max = 220.dp)
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
                        if (t.isPrivate) {
                            // У приватных вкладок иконку сайта не загружаем и не кэшируем
                            Icon(HripsIcons.Mask, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        } else if (t.home) {
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
                        IconButton(onClick = { browser.closeTab(i) }, modifier = Modifier.size(30.dp)) {
                            Icon(HripsIcons.Close, "Закрыть", Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        IconButton(modifier = Modifier.size(40.dp), onClick = { browser.newTab(incognito = browser.current.isPrivate) }) { Icon(HripsIcons.Add, "Новая вкладка") }
    }
}

/** Счётчик вкладок: скруглённый квадрат-контур с числом внутри. Нажатие открывает список вкладок. */
@Composable
fun TabCounterButton(count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) {
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

/** Открывает выбор сервиса автозаполнения в настройках Android (на части прошивок сразу общие настройки). */
fun openAutofillSettings(context: android.content.Context) {
    val intents = listOf(
        android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
            .setData(android.net.Uri.parse("package:")),
        android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (i in intents) {
        try {
            context.startActivity(i)
            return
        } catch (e: Exception) {
            // пробуем следующий вариант
        }
    }
    Toast.makeText(context, "Откройте: Настройки → Пароли и аккаунты → Автозаполнение", Toast.LENGTH_LONG).show()
}
