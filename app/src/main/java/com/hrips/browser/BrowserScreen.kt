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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.ExitTransition
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

/**
 * Полоса прогресса вынесена в отдельную функцию: прогресс движок присылает десятки раз за загрузку,
 * и пока его читал сам BrowserScreen, каждый тик перекомпоновывал весь экран (адресную строку, нижнюю панель, AndroidView.update).
 * Теперь чтение tab.progress / tab.loading происходит здесь, и обновляется только эта полоса.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PageProgressBar(tab: Tab) {
    Box(Modifier.fillMaxWidth().height(4.dp), contentAlignment = Alignment.Center) {
        // Прогресс приходит скачками: сглаживаем
        val shownProgress by animateFloatAsState(
            tab.progress / 100f,
            MaterialTheme.motionScheme.defaultEffectsSpec<Float>(),
            label = "pageProgress",
        )
        // Полоса не гаснет, пока не доехала до конца: иначе при быстрой загрузке она исчезала на 60%
        val finishing = !tab.loading && tab.progress >= 100 && shownProgress < 0.99f
        val barAlpha by animateFloatAsState(
            if ((tab.loading || finishing) && !tab.home) 1f else 0f,
            MaterialTheme.motionScheme.defaultEffectsSpec<Float>(),
            label = "pageProgressAlpha",
        )
        if (barAlpha > 0.01f) {
            LinearProgressIndicator(
                progress = { shownProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp).graphicsLayer { alpha = barAlpha },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // Новая страница или главная: верхняя строка снова на месте
    LaunchedEffect(tab.id, tab.url, tab.home) { tab.chromeHidden = false }
    val store = browser.store
    val app = LocalContext.current.applicationContext as HripsApp
    // Адаптивность привязана к размеру текущего окна, а не к типу устройства/конфигурации экрана.
    // Это корректно работает для split-screen, свободного resize и складных устройств.
    val wide = isWideWindow()
    // Кнопки плавающей панели на телефоне: 48dp (минимальная область нажатия по Material и TalkBack).
    // На самых узких экранах (<360dp) остаются 40dp, иначе панель вместе с кнопкой загрузок не поместится по ширине.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val barButton = HripsLayout.phoneBarButton(screenWidthDp)
    val activity = LocalContext.current as? Activity
    var showTabs by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSitePerms by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showExtSheet by remember { mutableStateOf(false) }
    var showExtManager by remember { mutableStateOf(false) }
    var extStoreFirst by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showQrScan by remember { mutableStateOf(false) }
    // Нажали на уведомление о загрузке: закрываем всё лишнее и показываем страницу загрузок
    val downloadsRequested = browser.downloads.pageRequested
    LaunchedEffect(downloadsRequested) {
        if (downloadsRequested) {
            showTabs = false; showSearch = false; showSettings = false; showTools = false
            showQrScan = false; showHistory = false; showBookmarks = false; showExtSheet = false
            showDownloads = true
            browser.downloads.pageRequested = false
        }
    }
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
    // Нет истории внутри вкладки, но её открыли из другой: «назад» закрывает её и возвращает на прежнюю
    val opener = browser.openerOf(tab)
    // Одно действие для жеста «назад» и для стрелки в панели
    val goBack: () -> Unit = {
        when {
            canReturnToPage -> tab.home = false
            tab.canGoBack -> tab.goBack()
            else -> browser.returnToOpener(tab)
        }
    }
    val backEnabled = !tab.home && (tab.canGoBack || opener != null)
    BackHandler(enabled = canReturnToPage || tab.canGoBack || opener != null) { goBack() }

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
    // Есть снимок страницы: он сжимается в карточку, переключатель только проявляется. Иначе - рост из кнопки
    var tabsFade by remember { mutableStateOf(false) }
    val requestTabs: () -> Unit = {
        // Есть прошлый снимок: открываем сразу, свежий подтянется в уже открытый переключатель.
        // Системный PixelCopy тут не годится (он бы снял сам переключатель), поэтому только снимок движка.
        if (tab.thumbnail != null || tab.home) {
            tabsFade = tab.thumbnail != null && !tab.home
            showTabs = true
            captureThumbnail(activity, viewRef[0], tab, allowPixelCopy = false) { }
        } else {
            captureThumbnail(activity, viewRef[0], tab, timeoutMs = 350) {
                tabsFade = tab.thumbnail != null && !tab.home
                showTabs = true
            }
        }
    }
    // Превью обновляем сами, когда страница догрузилась: карточки в переключателе сразу с актуальной картинкой
    LaunchedEffect(tab, tab.loading, tab.url) {
        if (!tab.loading && !tab.home && !showTabs && !showSearch && !showHistory && !showBookmarks) {
            kotlinx.coroutines.delay(1200)
            if (!showTabs && !showSearch && !showHistory && !showBookmarks) captureThumbnail(activity, viewRef[0], tab) { }
        }
    }

    // Снимки вкладок декодируем заранее, пока переключатель закрыт: он откроется сразу с картинками
    val appCtx = LocalContext.current.applicationContext
    LaunchedEffect(browser.tabs.size, showTabs) {
        if (showTabs) return@LaunchedEffect
        browser.tabs.take(12).forEach { t ->
            if (!t.isPrivate && !t.home && t.thumbnail == null && t.url.isNotBlank()) {
                TabThumbs.load(appCtx, t.url, small = true)
                TabThumbs.load(appCtx, t.url, small = false)
            }
        }
    }

    // Телефон: нижняя панель уезжает вниз, пока страницу листают вниз, и возвращается при прокрутке вверх,
    // на главной и при открытом меню остаётся на месте. State, а не делегат: читается только в слое, без рекомпозиции
    val barHidden = !wide && !tab.home && tab.chromeHidden && !showTools
    val barShift = animateFloatAsState(
        if (barHidden) 1f else 0f,
        tween(HripsMotion.ChromeMs, easing = HripsMotion.ChromeEasing),
        label = "bottomBarShift",
    )

    val origins = remember { OriginTracker() }
    CompositionLocalProvider(LocalOrigins provides origins) {
    // Ландшафт: вырез камеры и боковая панель навигации не должны закрывать интерфейс. Съедаем эти боковые поля здесь
    // один раз (дочерние navigationBarsPadding / Scaffold их уже не добавят). В полноэкранном видео поля не нужны
    val sideInsets = WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)
    Box(Modifier.fillMaxSize().then(if (fs) Modifier else Modifier.windowInsetsPadding(sideInsets)).trackTouches(origins)) {
    Column(Modifier.fillMaxSize().imePadding()) {
        if (!fs) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            if (wide) TabStrip(browser)
            // Размеры строки сняты со скриншота Оперы (плотность 2.0): центр строки на 102dp от верха экрана,
            // низ шторки на 131dp (под ним сразу страница). Кнопки по 48dp, адресная строка 40dp.
            // На телефоне главная без верхней строки: поиск и выбор движка в строке на самой странице,
            // кнопки внизу
            // Верхняя строка всегда на месте (прячется при прокрутке только нижняя панель). Появление - только fade:
            // раньше она раскрывалась по высоте, и страница под ней дёргалась каждый кадр, пока движок переразмечал вид
            AnimatedVisibility(
                visible = wide || !tab.home,
                enter = fadeIn(tween(HripsMotion.ChromeMs)),
                exit = ExitTransition.None,
            ) {
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
                    BarButton(onClick = goBack, enabled = backEnabled) {
                        Icon(HripsIcons.BarBack, "Назад", Modifier.size(24.dp))
                    }
                    BarButton(onClick = { tab.goForward() }, enabled = tab.canGoForward && !tab.home) {
                        Icon(HripsIcons.BarForward, "Вперёд", Modifier.size(24.dp))
                    }
                    BarButton(onClick = { tab.goHome() }) { Icon(HripsIcons.BarHome, "Домой", Modifier.size(24.dp)) }
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
                    TabCounterButton(browser.tabs.size, Modifier.originAnchor("tabs")) { requestTabs() }
                    BarButton(onClick = { showExtSheet = true }) { Icon(HripsIcons.Puzzle, "Расширения", Modifier.size(24.dp)) }
                    AnimatedDownloadsSlot(browser.downloads, onClick = { showDownloads = true })
                    // Три точки как в Опере: центр на 28dp от правого края экрана, от значка расширений 42dp.
                    // Кнопка 36dp + отступ 6dp справа дают именно такое положение.
                    Box(Modifier.width(36.dp).height(48.dp), contentAlignment = Alignment.Center) {
                        BarButton(onClick = { showTools = true }, modifier = Modifier.wrapContentSize(unbounded = true)) {
                            Icon(HripsIcons.BarMore, "Инструменты", Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
            }
        }

        PageProgressBar(tab)

        if (findOpen && !tab.home) {
            key(tab.id) { FindBar(tab, onClose = { findOpen = false }) }
        }

        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Ключ по виду страницы, а не по вкладке: при повторном «+» на главной страница остаётся на месте и не мигает
            // (раньше для каждой новой вкладки она создавалась заново и проявлялась из прозрачности)
            if (tab.home && tab.isPrivate) key("private-start") { FadeInBox {
                PrivateStartPage(
                    privateCount = browser.privateCount,
                    screenshotsBlocked = !store.allowPrivateShots,
                    onSearch = { showSearch = true },
                    onCloseAll = { browser.closePrivateTabs() },
                    engine = if (wide) null else shownEngine,
                    onPickEngine = pickEngine,
                )
            } } else if (tab.home) key("start") { FadeInBox {
                StartPage(store = store, wallpaper = app.wallpaper.image, onOpen = { tab.load(it) }, onSearch = { showSearch = true }, onScanQr = { showQrScan = true }, engine = if (wide) null else shownEngine, onPickEngine = pickEngine)
            } } else {
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
                            it.release()
                            it.setSession(tab.session)
                        }
                    },
                    // Страница ушла с экрана (стартовая, закрытие Activity): отвязываем сессию от вида.
                    // Иначе сессия вкладки (живёт в Application) продолжает держать вид и Activity через делегат выделения
                    onRelease = { it.release() },
                )
                TabSwapCover(tab)
                PullIndicator(pullPx, pullThresholdPx, pullRefreshing, Modifier.align(Alignment.TopCenter))
            }
        }

        if (wide && !fs) Spacer(Modifier.navigationBarsPadding())
    }

    // Нижняя панель на телефоне: плавает над страницей и места у неё не отнимает (страница на весь экран).
    // Всего 6 кнопок: ширина = 6 * barButton + 12dp отступов
    if (!fs && !wide) {
        HripsIsland(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Слой снаружи отступов: уезжает вся панель вместе с зазором и системной полосой
                .graphicsLayer { translationY = barShift.value * size.height }
                .navigationBarsPadding()
                .padding(bottom = HripsLayout.PhoneBarGap),
        ) {
            BarButton(onClick = goBack, enabled = backEnabled, modifier = Modifier.size(barButton)) {
                Icon(HripsIcons.BarBack, "Назад", Modifier.size(24.dp))
            }
            BarButton(onClick = { tab.goForward() }, enabled = tab.canGoForward && !tab.home, modifier = Modifier.size(barButton)) {
                Icon(HripsIcons.BarForward, "Вперёд", Modifier.size(24.dp))
            }
            BarButton(onClick = { browser.newTab(incognito = tab.isPrivate) }, modifier = Modifier.size(barButton)) {
                Icon(HripsIcons.Add, "Новая вкладка")
            }
            TabCounterButton(browser.tabs.size, Modifier.size(barButton).originAnchor("tabs")) { requestTabs() }
            BarButton(onClick = { showExtSheet = true }, modifier = Modifier.size(barButton)) {
                Icon(HripsIcons.Puzzle, "Расширения", Modifier.size(24.dp))
            }
            // Загрузки как на планшете: пока файл качается, между расширениями и меню раскрывается слот,
            // остров растягивается, а значок файла летит ровно в то место, где появится кнопка загрузок
            AnimatedDownloadsSlot(browser.downloads, onClick = { showDownloads = true }, size = barButton, centered = true)
            BarButton(onClick = { showTools = true }, modifier = Modifier.size(barButton)) {
                Icon(HripsIcons.BarMore, "Инструменты", Modifier.size(24.dp))
            }
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
    RevealHost(visible = showExtManager, group = null, onDismiss = { showExtManager = false }) {
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
    RevealHost(visible = showDownloads, group = null, onDismiss = { showDownloads = false }) {
        DownloadsScreen(browser.downloads, onBack = { showDownloads = false })
    }
    // История и закладки: отдельные страницы, как загрузки
    RevealHost(visible = showHistory, group = null, onDismiss = { showHistory = false }) {
        HistoryScreen(store, onOpen = { tab.load(it); showHistory = false }, onBack = { showHistory = false })
    }
    RevealHost(visible = showBookmarks, group = null, onDismiss = { showBookmarks = false }) {
        BookmarksScreen(store, onOpen = { tab.load(it); showBookmarks = false }, onBack = { showBookmarks = false })
    }
    // Сканер QR-кодов поверх браузера; найденный адрес открывается в новой вкладке (в приватной, если текущая приватная)
    RevealHost(visible = showQrScan, group = null, onDismiss = { showQrScan = false }) {
        QrScannerScreen(
            onOpen = { url -> showQrScan = false; browser.newTab(url, incognito = tab.isPrivate) },
            onClose = { showQrScan = false },
        )
    }
    // Настройки: отдельная страница, как загрузки
    RevealHost(visible = showSettings, group = null, onDismiss = { showSettings = false }) {
        SettingsScreen(
            browser = browser,
            app = app,
            onBack = { showSettings = false },
            onSitePermissions = { showSitePerms = true },
            onOpenDownloads = { showSettings = false; showDownloads = true },
        )
    }
    // Переключатель вкладок: "Вкладки / Приватный", карусель карточек с превью
    RevealHost(visible = showTabs, group = "tabs", startColor = MaterialTheme.colorScheme.surfaceContainer, onDismiss = { showTabs = false }, fadeOnly = tabsFade) {
        TabSwitcher(
            browser = browser,
            onClose = { showTabs = false },
            onHistory = { showTabs = false; showHistory = true },
        )
    }
    val collapseThumb = tab.thumbnail
    if (showTabs && tabsFade && collapseThumb != null) TabsCollapseOverlay(collapseThumb)
    // Поисковая панель: разовый выбор движка, история запросов, подсказки
    RevealHost(visible = showSearch, group = "search", startColor = MaterialTheme.colorScheme.surfaceContainerHigh, onDismiss = { showSearch = false }) {
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

    RevealHost(visible = showSitePerms, group = null, onDismiss = { showSitePerms = false }) {
        SitePermissionsScreen(browser.runtime, browser.permissions.sites, onBack = { showSitePerms = false })
    }

    // Первый запуск: все разрешения одним заходом
    if (!store.firstRunDone) FirstRunScreen(store, browser.permissions)

    // Меню "три точки": на планшете у правого верхнего края, на телефоне над нижней панелью
    // (остров по центру, шириной phoneBarWidthDp: меню прижимаем к его правому краю)
    val phoneBarWidthDp = (barButton.value * 6 + 12).toInt()
    val phoneMenuEnd = ((screenWidthDp - phoneBarWidthDp) / 2).coerceAtLeast(8).dp
    Box(
        if (wide) Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 100.dp, end = 12.dp)
        else Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(bottom = 8.dp + barButton + 16.dp, end = phoneMenuEnd),
    ) {
        ToolsMenu(
            expanded = showTools,
            onDismiss = { showTools = false },
            browser = browser,
            tab = tab,
            onFind = { findOpen = true },
            onLibrary = { page -> if (page == 0) showBookmarks = true else showHistory = true },
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
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).size(48.dp).alpha(0.7f),
        ) { Icon(HripsIcons.Close, "Выйти из полноэкранного режима") }
    }

    // Единые уведомления: выше всех оверлеев, над нижней панелью браузера
    // Когда нижняя панель спрятана, уведомления опускаются вместе с ней
    NoticeHost(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = if (wide) 96.dp else 8.dp + barButton + 24.dp)
            .offset { androidx.compose.ui.unit.IntOffset(0, (barShift.value * (barButton + 8.dp).toPx()).toInt()) },
    )
    }
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
                HripsTextButton(onClick = { browser.external.open(r) { fallback -> tab.load(fallback) } }) { Text("Открыть") }
            },
            dismissButton = { HripsTextButton(onClick = { browser.external.dismiss() }) { Text("Отмена") } },
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
            confirmButton = { HripsTextButton(onClick = { browser.permissions.answer(req, true) }) { Text("Разрешить") } },
            dismissButton = { HripsTextButton(onClick = { browser.permissions.answer(req, false) }) { Text("Запретить") } },
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
    Notices.show("Откройте: Настройки → Пароли и аккаунты → Автозаполнение")
}
