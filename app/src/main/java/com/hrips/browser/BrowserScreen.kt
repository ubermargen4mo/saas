package com.hrips.browser

import android.app.Activity
import androidx.compose.ui.platform.LocalDensity
import android.net.Uri
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    // Жест «потяните вниз, чтобы обновить»: расстояние пальца и порог срабатывания
    var pullPx by remember { mutableFloatStateOf(0f) }
    // Палец отпустили за порогом: кружок крутится, пока страница не обновится
    var pullRefreshing by remember { mutableStateOf(false) }
    val pullThresholdPx = with(LocalDensity.current) { 110.dp.toPx() }
    // Ждём, пока страница начнёт грузиться и закончит; на случай, если загрузка не стартовала, сдаёмся через 3 с
    LaunchedEffect(pullRefreshing, tab) {
        if (!pullRefreshing) return@LaunchedEffect
        withTimeoutOrNull(3000) { snapshotFlow { tab.loading }.first { it } }
        withTimeoutOrNull(30000) { snapshotFlow { tab.loading }.first { !it } }
        pullRefreshing = false
    }
    val store = browser.store
    val app = LocalContext.current.applicationContext as HripsApp
    // Адаптивность привязана к размеру текущего окна, а не к типу устройства/конфигурации экрана.
    // Это корректно работает для split-screen, свободного resize и складных устройств.
    val wide = isWideWindow()
    val activity = LocalContext.current as? Activity
    var showTabs by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var libPage by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showSitePerms by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showExtSheet by remember { mutableStateOf(false) }
    var showExtManager by remember { mutableStateOf(false) }
    var extStoreFirst by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showQrScan by remember { mutableStateOf(false) }
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
        if (canReturnToPage) tab.home = false else tab.goBack()
    }

    DisposableEffect(tab.id) {
        tab.ensureLoaded() // вкладка с прошлого запуска грузится, когда её открыли
        browser.setTabActive(tab, true, true)
        onDispose { browser.setTabActive(tab, false, false) }
    }

    // Crash/kill может заменить GeckoSession, не меняя ID вкладки. Re-activate only the new session;
    // the tab-level DisposableEffect must not deactivate it during its old-session cleanup.
    LaunchedEffect(tab.session) {
        if (browser.isAppVisible) browser.setTabActive(tab, true, true)
    }

    // Полноэкранное видео: прячем интерфейс браузера и системные панели
    // Полноэкранный режим из меню: прячет интерфейс браузера, как видео на весь экран
    var immersive by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { immersive = false }
    BackHandler(enabled = showExtManager) { showExtManager = false }
    val fs = tab.fullscreen || immersive
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
    BackHandler(enabled = fs) { if (tab.fullscreen) tab.exitFullscreen() else immersive = false }

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
    // Превью обновляем сами, когда страница догрузилась: карточки в переключателе сразу с актуальной картинкой
    LaunchedEffect(tab, tab.loading, tab.url) {
        if (!tab.loading && !tab.home && !showTabs && !showSearch && !showLibrary) {
            kotlinx.coroutines.delay(1200)
            if (!showTabs && !showSearch && !showLibrary) captureThumbnail(activity, viewRef[0], tab) { }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().imePadding()) {
        if (!fs) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            if (wide) TabStrip(browser)
            // Размеры строки сняты со скриншота Оперы (плотность 2.0): центр строки на 102dp от верха экрана,
            // низ шторки на 131dp (под ним сразу страница). Кнопки по 48dp, адресная строка 40dp.
            Row(
                Modifier.fillMaxWidth().padding(
                    start = if (wide) 4.dp else 12.dp,
                    end = if (wide) 4.dp else 12.dp,
                    top = 4.dp,
                    bottom = if (wide) 1.dp else 4.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (wide) {
                    // Слева: назад, вперёд, домой. «Обновить» теперь внутри адресной строки
                    IconButton(onClick = { tab.goBack() }, enabled = tab.canGoBack && !tab.home) {
                        Icon(HripsIcons.BarBack, "Назад", Modifier.size(24.dp))
                    }
                    IconButton(onClick = { tab.goForward() }, enabled = tab.canGoForward && !tab.home) {
                        Icon(HripsIcons.BarForward, "Вперёд", Modifier.size(24.dp))
                    }
                    IconButton(onClick = { tab.goHome() }) { Icon(HripsIcons.BarHome, "Домой", Modifier.size(24.dp)) }
                }
                // Адресная строка занимает всё свободное место между кнопками
                Box(Modifier.weight(1f).padding(horizontal = if (wide) 4.dp else 0.dp), contentAlignment = Alignment.Center) {
                    AddressBar(
                        tab = tab,
                        store = store,
                        onSearch = { showSearch = true },
                        engine = shownEngine,
                        query = parsed?.second,
                        onPickEngine = pickEngine,
                        onMenu = { showTools = true },
                        onExtensions = { showExtSheet = true },
                        showMenu = !wide,
                        downloads = browser.downloads,
                        modifier = Modifier.fillMaxWidth(),
                        wide = wide,
                    )
                }
                if (wide) {
                    // Справа: вкладки, расширения, [загрузки: только пока идут], меню
                    TabCounterButton(browser.tabs.size) { requestTabs() }
                    IconButton(onClick = { showExtSheet = true }) { Icon(HripsIcons.Puzzle, "Расширения", Modifier.size(24.dp)) }
                    AnimatedDownloadsSlot(browser.downloads, onClick = { showDownloads = true })
                    // Три точки как в Опере: центр на 28dp от правого края экрана, от значка расширений 42dp.
                    // Кнопка 36dp + отступ 6dp справа дают именно такое положение.
                    Box(Modifier.width(36.dp).height(48.dp), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { showTools = true }, modifier = Modifier.wrapContentSize(unbounded = true)) {
                            Icon(HripsIcons.BarMore, "Инструменты", Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (tab.loading && !tab.home) {
                LinearWavyProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }

        if (findOpen && !tab.home) {
            key(tab.id) { FindBar(tab, onClose = { findOpen = false }) }
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
                        // Выделенный текст -> «Искать» открывает результаты в новой вкладке того же режима
                        it.onSearchText = { text ->
                            browser.newTab(SearchEngines.current.template + Uri.encode(text), incognito = tab.isPrivate)
                        }
                        // Потянули страницу вниз, пока она наверху: обновляем при отпускании
                        it.pullEnabled = !fs
                        it.onPull = { px -> pullPx = px }
                        it.onPullRelease = { px ->
                            if (px >= pullThresholdPx) {
                                tab.reload()
                                pullRefreshing = true
                            }
                        }
                        // Системное автозаполнение Android (Google, Bitwarden, 1Password...).
                        // В приватной вкладке выключено, чтобы менеджер паролей не запоминал логины оттуда.
                        it.setAutofillEnabled(!tab.isPrivate)
                        if (it.session !== tab.session) {
                            it.releaseSession()
                            it.setSession(tab.session)
                        }
                    },
                )
                PullIndicator(pullPx, pullThresholdPx, pullRefreshing, Modifier.align(Alignment.TopCenter))
            }
        }

        // Нижняя плавающая панель только на узких экранах (телефон)
        if (!fs && !wide) {
            Box(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                HorizontalFloatingToolbar(expanded = true) {
                    IconButton(onClick = { tab.goBack() }, enabled = tab.canGoBack && !tab.home) {
                        Icon(HripsIcons.Back, "Назад", Modifier.size(24.dp))
                    }
                    IconButton(onClick = { tab.goForward() }, enabled = tab.canGoForward && !tab.home) {
                        Icon(HripsIcons.Forward, "Вперёд", Modifier.size(24.dp))
                    }
                    IconButton(onClick = { tab.reloadOrStop() }, enabled = !tab.home) {
                        Icon(if (tab.loading) HripsIcons.BarClose else HripsIcons.BarRefresh, "Обновить")
                    }
                    IconButton(onClick = { browser.newTab(incognito = tab.isPrivate) }) { Icon(HripsIcons.Add, "Новая вкладка") }
                    TabCounterButton(browser.tabs.size) { requestTabs() }
                }
            }
        } else if (wide && !fs) {
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    // Расширения: кнопки по значку пазла, страница управления, запросы прав и окна расширений
    if (showExtSheet) {
        ExtensionsSheet(
            extensions = browser.extensions,
            isPrivate = tab.isPrivate,
            onManage = { store -> extStoreFirst = store; showExtManager = true },
            onClose = { showExtSheet = false },
        )
    }
    AnimatedVisibility(
        visible = showExtManager,
        enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
    ) {
        ExtensionsScreen(browser.extensions, extStoreFirst, onBack = { showExtManager = false })
    }
    browser.extensions.prompt?.let { p ->
        ExtensionPromptDialog(p) { allow, priv -> browser.extensions.answerPrompt(allow, priv) }
    }
    browser.extensions.popup?.let { p ->
        ExtensionPopupDialog(p, onClose = { browser.extensions.closePopup() })
    }
    browser.extensions.error?.let { msg ->
        ExtensionErrorDialog(msg) { browser.extensions.error = null }
    }

    // Страница загрузок поверх браузера
    AnimatedVisibility(
        visible = showDownloads,
        enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
    ) {
        DownloadsScreen(browser.downloads, onBack = { showDownloads = false })
    }
    // Сканер QR-кодов поверх браузера; найденный адрес открывается в новой вкладке (в приватной, если текущая приватная)
    AnimatedVisibility(
        visible = showQrScan,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160)),
    ) {
        QrScannerScreen(
            onOpen = { url -> showQrScan = false; browser.newTab(url, incognito = tab.isPrivate) },
            onClose = { showQrScan = false },
        )
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
            onScanQr = { showQrScan = true },
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
        req.contentPermission?.let { permission ->
            LaunchedEffect(req) { permission.notifyShown() }
        }
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
