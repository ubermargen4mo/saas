package com.hrips.browser

import android.app.Activity
import androidx.compose.ui.platform.LocalDensity
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.tween
import kotlinx.coroutines.Job
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoView

internal fun Tab.label() = when {
    home && isPrivate -> "Приватная вкладка"
    home -> "Начальная страница"
    else -> title.ifBlank { url }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PageProgressBar(tab: Tab) {
    Box(Modifier.fillMaxWidth().height(10.dp), contentAlignment = Alignment.Center) {
        val shownProgress by animateFloatAsState(
            tab.progress / 100f,
            MaterialTheme.motionScheme.defaultEffectsSpec<Float>(),
            label = "pageProgress",
        )
        val finishing = !tab.loading && tab.progress >= 100 && shownProgress < 0.99f
        val barAlpha by animateFloatAsState(
            if ((tab.loading || finishing) && !tab.home) 1f else 0f,
            MaterialTheme.motionScheme.defaultEffectsSpec<Float>(),
            label = "pageProgressAlpha",
        )
        if (barAlpha > 0.01f) {
            LinearWavyProgressIndicator(
                progress = { shownProgress },
                modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = barAlpha },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(browser: Browser) {
    var showTabs by remember { mutableStateOf(false) }
    val pinnedTab = remember(showTabs) {
        if (showTabs) androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { browser.current } else null
    }
    val tab = pinnedTab ?: browser.current
    var pullPx by remember { mutableFloatStateOf(0f) }
    var pullRefreshing by remember { mutableStateOf(false) }
    val pullThresholdPx = with(LocalDensity.current) { 110.dp.toPx() }
    LaunchedEffect(pullRefreshing, tab) {
        if (!pullRefreshing) return@LaunchedEffect
        withTimeoutOrNull(3000) { snapshotFlow { tab.loading }.first { it } }
        withTimeoutOrNull(30000) { snapshotFlow { tab.loading }.first { !it } }
        pullRefreshing = false
    }
    LaunchedEffect(tab.id, tab.url, tab.home) { tab.chromeHidden = false }
    val store = browser.store
    val app = LocalContext.current.applicationContext as HripsApp
    val wide = isWideWindow()
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val activity = LocalContext.current as? Activity
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSitePerms by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showExtManager by remember { mutableStateOf(false) }
    var extStoreFirst by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showQrScan by remember { mutableStateOf(false) }
    val appContext = LocalContext.current.applicationContext
    val imageRequest = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) ImageSearch.start(appContext, uri, SearchEngines.current, tab) { browser.tabs.contains(tab) }
    }
    val downloadsRequested = browser.downloads.pageRequested
    LaunchedEffect(downloadsRequested) {
        if (downloadsRequested) {
            showTabs = false; showSearch = false; showSettings = false; showTools = false
            showQrScan = false; showHistory = false; showBookmarks = false; showExtManager = false
            showDownloads = true
            browser.downloads.pageRequested = false
        }
    }
    var findOpen by remember { mutableStateOf(false) }
    var oneOff by remember { mutableStateOf<SearchEngine?>(null) }
    val parsed = remember(tab.url, tab.home) { if (tab.home) null else parseSearch(tab.url) }
    val shownEngine = parsed?.first ?: oneOff ?: SearchEngines.current
    val pickEngine: (SearchEngine) -> Unit = { e ->
        if (parsed != null) tab.load(parsed.second, e) else oneOff = e
    }

    val canReturnToPage = tab.home && tab.url.isNotBlank()
    val opener = browser.openerOf(tab)
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
        tab.ensureLoaded()
        browser.setTabActive(tab, true, true)
        onDispose { browser.setTabActive(tab, false, false) }
    }

    LaunchedEffect(tab.session) {
        if (browser.isAppVisible) browser.setTabActive(tab, true, true)
    }

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

    val priv = tab.isPrivate
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(priv, store.allowPrivateShots) {
        val w = activity?.window ?: return@LaunchedEffect
        if (priv && !store.allowPrivateShots) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    LaunchedEffect(priv, systemDark) {
        val w = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(w, w.decorView)
        c.isAppearanceLightStatusBars = !priv && !systemDark
        c.isAppearanceLightNavigationBars = !priv && !systemDark
    }

    val viewRef = remember { arrayOfNulls<GeckoView>(1) }
    var tabsFade by remember { mutableStateOf(false) }
    val appCtx = LocalContext.current.applicationContext
    val requestTabs: () -> Unit = {
        // Читаем снимки соседних вкладок в память заранее, чтобы карточки появились сразу с картинкой.
        val around = browser.tabs.indices
            .filter { kotlin.math.abs(it - browser.currentIndex) <= 8 }
            .map { browser.tabs[it] }
            .filter { !it.isPrivate && !it.home }
        TabThumbs.prefetch(appCtx, around.map { it.id }, small = true)
        TabThumbs.prefetch(appCtx, around.filter { kotlin.math.abs(browser.tabs.indexOf(it) - browser.currentIndex) <= 1 }.map { it.id }, small = false)
        val open = {
            tabsFade = tab.thumbnail != null && !tab.home
            showTabs = true
        }
        if (tab.home || TabThumbs.isFresh(tab.id)) {
            open()
        } else {
            // Свежий снимок до открытия, как в образце: во время анимации захват давал рывки.
            // Если страница ответит позже 600 мс, открываем со старым снимком.
            captureThumbnail(activity, viewRef[0], tab, allowPixelCopy = false, timeoutMs = 600) { open() }
        }
    }
    // Адресная строка и поиск открываются поверх страницы: снимок делаем параллельно, открытие не ждёт.
    val openSearch: () -> Unit = {
        if (!tab.home && !TabThumbs.isFresh(tab.id)) captureThumbnail(activity, viewRef[0], tab, allowPixelCopy = false) { }
        showSearch = true
    }
    LaunchedEffect(tab, tab.loading, tab.url) {
        if (!tab.loading && !tab.home && !showTabs && !showSearch && !showHistory && !showBookmarks) {
            kotlinx.coroutines.delay(700)
            if (!showTabs && !showSearch && !showHistory && !showBookmarks) captureThumbnail(activity, viewRef[0], tab) { }
        }
    }

    // При запуске: убрать превью несуществующих вкладок и сразу прочитать остальные в память.
    LaunchedEffect(Unit) {
        val ids = browser.tabs.filter { !it.isPrivate && !it.home }.map { it.id }
        if (ids.isNotEmpty()) TabThumbs.prune(appCtx, ids.toSet())
        TabThumbs.prefetch(appCtx, ids.take(60), small = true)
    }
    LaunchedEffect(browser.tabs.size, showTabs) {
        if (showTabs) return@LaunchedEffect
        val ids = browser.tabs.indices
            .filter { kotlin.math.abs(it - browser.currentIndex) <= 10 }
            .map { browser.tabs[it] }
            .filter { !it.isPrivate && !it.home && it.thumbnail == null }
            .map { it.id }
        TabThumbs.prefetch(appCtx, ids, small = true)
    }

    val collapsed = !wide && !tab.home && tab.chromeHidden && !showTools
    val noticeLift by animateDpAsState(
        if (collapsed) HripsLayout.BarHeight - HripsLayout.BarCompactHeight else 0.dp,
        MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.Dp>(),
        label = "noticeLift",
    )
    val barText = when {
        tab.home -> ""
        parsed != null -> parsed.second
        else -> tab.siteHost() ?: tab.url.removePrefix("https://")
    }
    val statusColor = MaterialTheme.colorScheme.surface

    val origins = remember { OriginTracker() }
    CompositionLocalProvider(LocalOrigins provides origins) {
    val sideInsets = WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)
    Box(Modifier.fillMaxSize().then(if (fs) Modifier else Modifier.windowInsetsPadding(sideInsets)).trackTouches(origins)) {
    Column(Modifier.fillMaxSize().imePadding()) {
        if (!fs) {
        Column(Modifier.fillMaxWidth().then(if (wide) Modifier else Modifier.background(statusColor)).statusBarsPadding()) {
            if (wide) TabStrip(browser)
            AnimatedVisibility(
                visible = wide,
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
                    BarButton(onClick = goBack, enabled = backEnabled) {
                        Icon(HripsIcons.BarBack, "Назад", Modifier.size(24.dp))
                    }
                    BarButton(onClick = { tab.goForward() }, enabled = tab.canGoForward && !tab.home) {
                        Icon(HripsIcons.BarForward, "Вперёд", Modifier.size(24.dp))
                    }
                    BarButton(onClick = { tab.goHome() }) { Icon(HripsIcons.BarHome, "Домой", Modifier.size(24.dp)) }
                }
                Box(Modifier.weight(1f).padding(horizontal = if (wide) 4.dp else 0.dp), contentAlignment = Alignment.Center) {
                    AddressBar(
                        tab = tab,
                        onSearch = { openSearch() },
                        engine = shownEngine,
                        query = parsed?.second,
                        onPickEngine = pickEngine,
                        modifier = Modifier.fillMaxWidth(),
                        wide = wide,
                    )
                }
                if (wide) {
                    TabCounterButton(browser.tabs.size, Modifier.originAnchor("tabs")) { requestTabs() }
                    BarButton(onClick = { showExtManager = true }) { Icon(HripsIcons.Puzzle, "Расширения", Modifier.size(24.dp)) }
                    AnimatedDownloadsSlot(browser.downloads, onClick = { showDownloads = true })
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

        if (wide) PageProgressBar(tab)

        if (findOpen && !tab.home) {
            key(tab.id) { FindBar(tab, onClose = { findOpen = false }) }
        }

        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab.home && tab.isPrivate) key("private-start") { FadeInBox {
                PrivateStartPage(
                    privateCount = browser.privateCount,
                    screenshotsBlocked = !store.allowPrivateShots,
                    onSearch = { openSearch() },
                    onCloseAll = { browser.closePrivateTabs() },
                    engine = if (wide) null else shownEngine,
                    onPickEngine = pickEngine,
                    showSearch = wide,
                )
            } } else if (tab.home) key("start") { FadeInBox {
                StartPage(store = store, wallpaper = app.wallpaper.image, onOpen = { tab.load(it) }, onSearch = { openSearch() }, onScanQr = { showQrScan = true }, onSearchImage = { pickImage.launch(imageRequest) }, engine = if (wide) null else shownEngine, onPickEngine = pickEngine, showSearch = wide)
            } } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> HripsGeckoView(ctx).also { viewRef[0] = it } },
                    update = {
                        it.incognito = tab.isPrivate
                        it.onSearchText = { text ->
                            browser.newTab(SearchEngines.current.template + Uri.encode(text), incognito = tab.isPrivate)
                        }
                        it.pullEnabled = !fs
                        it.onPull = { px -> pullPx = px }
                        it.onPullRelease = { px ->
                            if (px >= pullThresholdPx) {
                                tab.reload()
                                pullRefreshing = true
                            }
                        }
                        it.setAutofillEnabled(!tab.isPrivate)
                        if (it.session !== tab.session) {
                            it.release()
                            it.setSession(tab.session)
                        }
                    },
                    onRelease = { it.release() },
                )
                TabSwapCover(tab)
                PullIndicator(pullPx, pullThresholdPx, pullRefreshing, Modifier.align(Alignment.TopCenter))
            }
        }

        if (wide && !fs) Spacer(Modifier.navigationBarsPadding())
    }

    if (!fs && !wide) {
        BottomBar(
            tab = tab,
            shown = barText,
            collapsed = collapsed,
            backEnabled = backEnabled,
            tabCount = browser.tabs.size,
            tabIndex = browser.currentIndex,
            engine = shownEngine,
            onPickEngine = pickEngine,
            onSearchImage = { pickImage.launch(imageRequest) },
            onScanQr = { showQrScan = true },
            downloads = browser.downloads,
            onBack = goBack,
            onForward = { tab.goForward() },
            onAddress = { openSearch() },
            onMore = { showTools = true },
            onDownloads = { showDownloads = true },
            onExpand = { tab.chromeHidden = false },
            onSwipeUp = { if (collapsed) tab.chromeHidden = false else requestTabs() },
            onSwipeSide = { d ->
                val i = browser.currentIndex + d
                if (i in browser.tabs.indices) {
                    // Снимок уходящей вкладки начинаем до переключения; ответ принимается и после ухода.
                    if (!tab.home && !TabThumbs.isFresh(tab.id)) {
                        captureThumbnail(activity, viewRef[0], tab, allowPixelCopy = false, acceptAfterDeparture = true) { }
                    }
                    browser.currentIndex = i
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = HripsLayout.BarGap),
        )
    }

    RevealHost(visible = showExtManager, group = null, onDismiss = { showExtManager = false }) {
        ExtensionsScreen(browser.extensions, tab.isPrivate, extStoreFirst, onBack = { showExtManager = false })
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

    RevealHost(visible = showDownloads, group = null, onDismiss = { showDownloads = false }) {
        DownloadsScreen(browser.downloads, onBack = { showDownloads = false })
    }
    RevealHost(visible = showHistory, group = null, onDismiss = { showHistory = false }) {
        HistoryScreen(store, onOpen = { tab.load(it); showHistory = false }, onBack = { showHistory = false })
    }
    RevealHost(visible = showBookmarks, group = null, onDismiss = { showBookmarks = false }) {
        BookmarksScreen(store, onOpen = { tab.load(it); showBookmarks = false }, onBack = { showBookmarks = false })
    }
    RevealHost(visible = showQrScan, group = null, onDismiss = { showQrScan = false }) {
        QrScannerScreen(
            onOpen = { url -> showQrScan = false; browser.newTab(url, incognito = tab.isPrivate) },
            onClose = { showQrScan = false },
        )
    }
    RevealHost(visible = showSettings, group = null, onDismiss = { showSettings = false }) {
        SettingsScreen(
            browser = browser,
            app = app,
            onBack = { showSettings = false },
            onSitePermissions = { showSitePerms = true },
            onOpenDownloads = { showSettings = false; showDownloads = true },
        )
    }
    RevealHost(visible = showTabs, group = "tabs", startColor = MaterialTheme.colorScheme.surfaceContainer, onDismiss = { showTabs = false }, fadeOnly = true) {
        TabSwitcher(
            browser = browser,
            onClose = { showTabs = false },
            onHistory = { showTabs = false; showHistory = true },
        )
    }
    val collapseThumb = tab.thumbnail
    if (showTabs && tabsFade && collapseThumb != null) TabsCollapseOverlay(collapseThumb)
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
    DownloadPrompt(browser.downloads)
    DownloadFlightOverlay(browser.downloads.fx)

    RevealHost(visible = showSitePerms, group = null, onDismiss = { showSitePerms = false }) {
        SitePermissionsScreen(browser.runtime, browser.permissions.sites, onBack = { showSitePerms = false })
    }

    if (!store.firstRunDone) FirstRunScreen(store, browser.permissions)

    Box(
        if (wide) Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 100.dp, end = 12.dp)
        else Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(bottom = HripsLayout.BarGap + HripsLayout.BarHeight + 8.dp, end = HripsLayout.BarMargin),
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
            onTabs = { requestTabs() },
            onExtensions = { showExtManager = true },
        )
    }

    if (immersive && !tab.fullscreen) {
        FilledTonalIconButton(
            onClick = { immersive = false },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).size(48.dp).alpha(0.7f),
        ) { Icon(HripsIcons.Close, "Выйти из полноэкранного режима") }
    }

    NoticeHost(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = if (wide) 96.dp else HripsLayout.BarGap + HripsLayout.BarHeight + 16.dp)
            .offset { IntOffset(0, noticeLift.roundToPx()) },
    )
    }
    }

    browser.contextMenu?.let { info -> ContextMenuSheet(info, browser) { browser.contextMenu = null } }

    PromptHost(browser.prompts)

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
        }
    }
    Notices.show("Откройте: Настройки → Пароли и аккаунты → Автозаполнение")
}
