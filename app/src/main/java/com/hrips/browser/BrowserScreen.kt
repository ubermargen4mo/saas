package com.hrips.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs

internal fun Tab.label() = when {
    home && isPrivate -> "Приватная вкладка"
    home -> "Начальная страница"
    else -> title.ifBlank { url }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(browser: Browser) {
    var showTabs by remember { mutableStateOf(false) }
    val pinnedTab = remember(showTabs) {
        if (showTabs) Snapshot.withoutReadObservation { browser.current } else null
    }
    val tab = pinnedTab ?: browser.current
    var pullPx by remember { mutableFloatStateOf(0f) }
    var pullRefreshing by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val pullThresholdPx = with(density) { 110.dp.toPx() }
    LaunchedEffect(pullRefreshing, tab) {
        if (!pullRefreshing) return@LaunchedEffect
        withTimeoutOrNull(3000) { snapshotFlow { tab.loading }.first { it } }
        withTimeoutOrNull(30000) { snapshotFlow { tab.loading }.first { !it } }
        pullRefreshing = false
    }
    LaunchedEffect(tab.id, tab.home) { tab.expandChrome() }
    LaunchedEffect(tab.id, tab.home, tab.textScale) { browser.syncTextScale(tab) }
    val store = browser.store
    val context = LocalContext.current
    val appContext = context.applicationContext
    val app = appContext as HripsApp
    val activity = context as? Activity
    val wide = isWideWindow()
    // Вкладки на планшете: расположение и видимость из настроек, кнопка в шапке перекрывает правило.
    val ctxTabs = browser.contextTabs(browser.current).size
    val tabsRule = when (store.tabsShow) {
        TABS_ALWAYS -> true
        TABS_MORE_THAN_TWO -> ctxTabs > 1
        else -> false
    }
    val tabsVisible = wide && (store.tabsOverride ?: tabsRule)
    val inlineTabs = wide && store.tabsStyle == TABS_STYLE_INLINE
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSitePerms by remember { mutableStateOf(false) }
    var showPasswords by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showExtManager by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showPageOptions by remember { mutableStateOf(false) }
    // Шторка читалки открывается вместо меню сайта, когда включён режим чтения.
    var showReaderSheet by remember { mutableStateOf(false) }
    var showPageQr by remember { mutableStateOf(false) }
    var showPageSecurity by remember { mutableStateOf(false) }
    var showQrScan by remember { mutableStateOf(false) }
    val clipboard = remember { appContext.getSystemService(ClipboardManager::class.java) }
    val imageRequest = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) ImageSearch.start(appContext, uri, SearchEngines.current, tab) { browser.tabs.contains(tab) }
    }
    val downloadsRequested = browser.downloads.pageRequested
    LaunchedEffect(downloadsRequested) {
        if (downloadsRequested) {
            showTabs = false; showSearch = false; showSettings = false; showTools = false; showPageOptions = false
            showReaderSheet = false; showQrScan = false; showHistory = false; showBookmarks = false; showExtManager = false
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

    val searchByImage: () -> Unit = { pickImage.launch(imageRequest) }
    val openPageMenu: () -> Unit = { if (tab.readerActive) showReaderSheet = true else showPageOptions = true }
    val closeCurrentTab: () -> Unit = { browser.closeTab(browser.currentIndex) }
    val tabIndex = browser.contextTabs(tab).indexOfFirst { it.id == tab.id }.coerceAtLeast(0)

    // Действия меню адресной строки: общие для нижней панели телефона и адресной строки планшета.
    val copyAddress: () -> Unit = {
        val copied = runCatching {
            clipboard?.setPrimaryClip(ClipData.newPlainText("", tab.url))
        }.isSuccess && clipboard != null
        Notices.show(if (copied) "Адрес скопирован" else "Не удалось скопировать")
    }
    val shareAddress: () -> Unit = { PageActions.share(activity ?: appContext, tab) }
    val pasteAndGo: () -> Unit = {
        val text = runCatching {
            clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(appContext)?.toString()?.trim()
        }.getOrNull().orEmpty()
        if (text.isEmpty()) {
            Notices.show("Буфер обмена пуст")
        } else {
            if (isSearch(text) && !tab.isPrivate) store.addSearch(text)
            tab.load(text, shownEngine)
            oneOff = null
        }
    }

    val canReturnToPage = tab.home && tab.hasReturn
    val opener = browser.openerOf(tab)
    val goBack: () -> Unit = {
        when {
            canReturnToPage -> tab.returnToPage()
            tab.canGoBack -> tab.goBack()
            // После «домой» и перехода на новый адрес «назад» возвращает закрытую страницу.
            tab.hasReturn -> tab.returnToPage()
            opener == null && tab.fromApp -> {
                val index = browser.tabs.indexOf(tab)
                if (index >= 0) browser.closeTab(index)
                activity?.moveTaskToBack(true)
            }
            else -> browser.returnToOpener(tab)
        }
    }
    val backEnabled = !tab.home && (tab.canGoBack || opener != null || tab.hasReturn)
    BackHandler(enabled = canReturnToPage || tab.canGoBack || tab.hasReturn || opener != null || tab.fromApp) { goBack() }
    // Объявлен после общего «назад», поэтому приоритетнее: сначала закрываем читалку.
    BackHandler(enabled = tab.readerActive) { tab.closeReader() }

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
    // Значки в строке состояния читаются на цвете сайта: тёмные на светлом, светлые на тёмном.
    val themed = tab.themeColor.takeIf { wide && !tab.home && !priv && !tab.errorPage }
    LaunchedEffect(themed, priv, systemDark) {
        val w = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(w, w.decorView)
        val light = !priv && !systemDark
        c.isAppearanceLightStatusBars = if (themed != null) themed.luminance() > 0.5f else light
        c.isAppearanceLightNavigationBars = light
    }

    val viewRef = remember { arrayOfNulls<GeckoView>(1) }
    var tabsFade by remember { mutableStateOf(false) }
    val overlaysOpen by rememberUpdatedState(showTabs || showSearch || showHistory || showBookmarks)
    val requestTabs: () -> Unit = {
        // Сначала текущая вкладка, затем ближайшие соседи: пачка превью небольшая и полезная.
        val around = browser.thumbCandidates(radius = 8)
        TabThumbs.prefetch(appContext, around.take(12).map { it.second.id }, small = true)
        val center = browser.currentIndex
        TabThumbs.prefetch(appContext, around.filter { abs(it.first - center) <= 1 }.map { it.second.id }, small = false)
        val open = {
            tabsFade = !wide && tab.thumbnail != null && !tab.home
            showTabs = true
        }
        if (tab.home || TabThumbs.isFresh(tab.id)) {
            open()
        } else {
            // Захват не задерживает открытие переключателя дольше 600 мс.
            captureThumbnail(activity, viewRef[0], tab, allowPixelCopy = false, timeoutMs = 600) { open() }
        }
    }
    val openSearch: () -> Unit = {
        if (!tab.home && !TabThumbs.isFresh(tab.id)) captureThumbnail(activity, viewRef[0], tab, allowPixelCopy = false) { }
        showSearch = true
    }

    // Переход на другую вкладку: снимок уходящей начинаем до переключения, ответ принимается и после ухода.
    val pickTab: (Tab) -> Unit = { target ->
        val i = browser.tabs.indexOf(target)
        if (i >= 0 && i != browser.currentIndex) {
            if (!tab.home && !TabThumbs.isFresh(tab.id)) {
                captureThumbnail(activity, viewRef[0], tab, allowPixelCopy = false, acceptAfterDeparture = true) { }
            }
            browser.currentIndex = i
        }
    }
    // Соседняя вкладка того же контекста: d = +1 следующая, -1 предыдущая.
    val swipeTab: (Int) -> Unit = { d ->
        val inContext = browser.contextTabs(tab).map { it.id }.toSet()
        var i = browser.currentIndex + d
        while (i in browser.tabs.indices && browser.tabs[i].id !in inContext) i += d
        browser.tabs.getOrNull(i)?.let { pickTab(it) }
    }

    // Наблюдаем загрузку внутри эффекта, а не в ключах композиции: изменения loading не перезапускают весь экран.
    LaunchedEffect(tab, tab.url, tab.home) {
        if (tab.home) return@LaunchedEffect
        snapshotFlow { tab.loading }
            .distinctUntilChanged()
            .collectLatest { loading ->
                if (loading) return@collectLatest
                delay(700)
                if (!tab.loading && !overlaysOpen) captureThumbnail(activity, viewRef[0], tab) { }
            }
    }

    // При запуске удаляем устаревшие снимки.
    LaunchedEffect(Unit) {
        val ids = browser.tabs.filter { !it.isPrivate && !it.home }.map { it.id }
        if (ids.isNotEmpty()) TabThumbs.prune(appContext, ids.toSet())
    }
    LaunchedEffect(browser.tabs.size, browser.currentIndex, showTabs) {
        if (showTabs) return@LaunchedEffect
        val ids = browser.thumbCandidates(radius = 10)
            .filter { it.second.thumbnail == null }
            .take(12)
            .map { it.second.id }
        TabThumbs.prefetch(appContext, ids, small = true)
    }

    // Поиск по странице на телефоне (как в Safari): внизу ряд поиска, над ним нижняя панель, свёрнутая в домен.
    val findPhone = findOpen && !wide && !tab.home && !fs
    val collapsed = !wide && !tab.home && (tab.chromeHidden || findPhone) && !showTools && !showPageOptions && !showReaderSheet
    val findLift by animateDpAsState(
        // Домен в FindGap над рядом, ряд в FindGap над клавиатурой; панель сама уже лежит в BarGap над своим низом.
        if (findPhone) HripsLayout.FindCircle + HripsLayout.FindGap * 2 - HripsLayout.BarGap else 0.dp,
        HripsMotion.barCollapse(),
        label = "findLift",
    )
    // Развернуть панель во время поиска значит закончить поиск: вместе внизу они не помещаются.
    val expandBar: () -> Unit = {
        if (findOpen) findOpen = false
        tab.expandChrome()
    }

    // Панель плавает поверх страницы, а сайт должен знать, сколько внизу закрыто: тогда его нижние панели (YouTube и др.)
    // встают над нашей. setVerticalClipping работает в композиторе, без перерасчёта страницы, поэтому обновляется
    // покадрово; пружина общая с панелью (HripsMotion.barCollapse), так что страница и панель двигаются синхронно.
    val pageClip = remember { IntArray(1) }
    val barClipDp = animateDpAsState(
        if (collapsed) HripsLayout.BarCompactHeight else HripsLayout.BarHeight,
        HripsMotion.barCollapse(),
        label = "pageClip",
    )
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val imeShown = WindowInsets.isImeVisible
    val imeInsets = WindowInsets.ime
    // Во время поиска клавиатура лежит поверх страницы, а ряд поиска и пилюля домена поверх её низа: закрыто и
    // это. Без поиска под клавиатурой навигационную панель считать не надо.
    val clipActive = !fs && !wide && !tab.home && (!imeShown || findPhone)
    val bottomBase = { if (findPhone) maxOf(imeInsets.getBottom(density), navBottomPx) else if (imeShown) 0 else navBottomPx }
    LaunchedEffect(clipActive, navBottomPx, density, tab.session, imeShown, findPhone) {
        snapshotFlow { barClipDp.value + findLift }.collectLatest { h ->
            val px = if (clipActive) bottomBase() + with(density) { (HripsLayout.BarGap + h + 6.dp).roundToPx() } else 0
            pageClip[0] = px
            runCatching { viewRef[0]?.setVerticalClipping(px) }
        }
    }
    val overlayOpen = showSearch || findOpen || showTabs || showTools || showPageOptions || showReaderSheet || showHistory || showBookmarks ||
        showSettings || showSitePerms || showPasswords || showDownloads || showExtManager || showQrScan
    // Клавиатура и мышь: кнопка мыши «вперёд» приходит как KEYCODE_FORWARD, сочетания как в настольных браузерах.
    // При открытых экранах поверх страницы не вмешиваемся.
    val shortcuts: (KeyEvent) -> Boolean = h@{ ev ->
        if (ev.action != KeyEvent.ACTION_DOWN || fs) return@h false
        val k = ev.keyCode
        if (k == KeyEvent.KEYCODE_FORWARD) {
            if (overlayOpen || ev.repeatCount > 0) return@h false
            if (tab.canGoForward && !tab.home) tab.goForward()
            return@h true
        }
        val ctrl = ev.isCtrlPressed
        val alt = ev.isAltPressed
        val shift = ev.isShiftPressed
        if (!ctrl && !alt && k != KeyEvent.KEYCODE_F5 && k != KeyEvent.KEYCODE_F6) return@h false
        if (overlayOpen && !(ctrl && (k == KeyEvent.KEYCODE_T || k == KeyEvent.KEYCODE_N))) return@h false
        if (ev.repeatCount > 0) return@h true
        when {
            ctrl && shift && k == KeyEvent.KEYCODE_T -> browser.reopenLast()
            ctrl && shift && k == KeyEvent.KEYCODE_N -> browser.newTab(incognito = true)
            ctrl && k == KeyEvent.KEYCODE_T -> browser.newTab(incognito = browser.current.isPrivate)
            ctrl && k == KeyEvent.KEYCODE_W -> browser.closeTab(browser.currentIndex)
            ctrl && (k == KeyEvent.KEYCODE_L || k == KeyEvent.KEYCODE_K) -> openSearch()
            alt && k == KeyEvent.KEYCODE_D -> openSearch()
            k == KeyEvent.KEYCODE_F6 -> openSearch()
            (ctrl && k == KeyEvent.KEYCODE_R) || k == KeyEvent.KEYCODE_F5 -> if (!tab.home) tab.reload()
            ctrl && k == KeyEvent.KEYCODE_F -> if (!tab.home) findOpen = true
            ctrl && k == KeyEvent.KEYCODE_D -> if (!tab.home) toggleBookmarkWithNotice(store, tab.url, tab.title)
            ctrl && k == KeyEvent.KEYCODE_H -> showHistory = true
            ctrl && k == KeyEvent.KEYCODE_J -> showDownloads = true
            ctrl && (k == KeyEvent.KEYCODE_TAB || k == KeyEvent.KEYCODE_PAGE_DOWN || k == KeyEvent.KEYCODE_PAGE_UP) ->
                swipeTab(if (k == KeyEvent.KEYCODE_PAGE_UP || (k == KeyEvent.KEYCODE_TAB && shift)) -1 else 1)
            ctrl && k in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val list = browser.contextTabs(tab)
                val n = k - KeyEvent.KEYCODE_1
                (if (n == 8) list.lastOrNull() else list.getOrNull(n))?.let { pickTab(it) }
            }
            alt && k == KeyEvent.KEYCODE_DPAD_LEFT -> goBack()
            alt && k == KeyEvent.KEYCODE_DPAD_RIGHT -> if (tab.canGoForward && !tab.home) tab.goForward()
            else -> return@h false
        }
        true
    }
    val shortcutsNow by rememberUpdatedState(shortcuts)
    DisposableEffect(Unit) {
        KeyShortcuts.handler = { ev -> shortcutsNow(ev) }
        onDispose { KeyShortcuts.handler = null }
    }
    val noticeLift by animateDpAsState(
        if (collapsed) HripsLayout.BarHeight - HripsLayout.BarCompactHeight else 0.dp,
        MaterialTheme.motionScheme.defaultSpatialSpec<Dp>(),
        label = "noticeLift",
    )
    val barText = remember(tab.url, tab.home, parsed) {
        when {
            tab.home -> ""
            parsed != null -> parsed.second
            else -> tab.siteHost() ?: tab.url.removePrefix("https://")
        }
    }
    val statusColor = MaterialTheme.colorScheme.surface
    // Шапка планшета подстраивается под сайт; в приватных вкладках, на главной и служебных страницах остаётся обычная.
    val siteColor = if (wide && !tab.home && !tab.isPrivate && !tab.errorPage) tab.themeColor else null
    LaunchedEffect(statusColor) {
        activity?.window?.setBackgroundDrawable(ColorDrawable(statusColor.toArgb()))
    }

    val origins = remember { OriginTracker() }
    CompositionLocalProvider(LocalOrigins provides origins) {
    // Пока поверх окно системы (выбор файлов), Android на время убирает inset строки состояния, и GeckoView меняет
    // размер туда-обратно: на синхронном surfaceChanged главный поток может зависнуть (см. StableInsets.kt).
    val sideInsets = rememberStableInsets(
        WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal),
        "боковые",
    )
    val statusInsets = rememberStableInsets(WindowInsets.statusBars, "строка состояния")
    val navInsets = rememberStableInsets(WindowInsets.navigationBars, "навигация")
    Box(Modifier.fillMaxSize().then(if (fs) Modifier else Modifier.windowInsetsPadding(sideInsets)).trackTouches(origins)) {
    Column(Modifier.fillMaxSize().pageImePadding(overlayOpen)) {
        if (!fs) {
        SiteHeaderTheme(siteColor) {
        Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().then(if (wide) Modifier else Modifier.background(statusColor)).windowInsetsPadding(statusInsets)) {
            if (wide && !inlineTabs && store.tabsPos == TABS_ABOVE) {
                AnimatedVisibility(visible = tabsVisible) { TabStrip(browser, above = true) }
            }
            AnimatedVisibility(
                visible = wide,
                enter = fadeIn(tween(HripsMotion.ChromeMs)),
                exit = ExitTransition.None,
            ) {
            Row(
                // Отступы как в Safari: 12 dp от краёв окна, 8 dp между группами, высота 40 dp.
                Modifier.fillMaxWidth().padding(
                    horizontal = 12.dp,
                    vertical = 6.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val bs = 36.dp
                val haptic = LocalHapticFeedback.current
                // Слева: показать и скрыть вкладки, «домой», «назад» и «вперёд».
                ToolbarGroup {
                    BarButton(modifier = Modifier.size(bs), onClick = { store.overrideTabs(!tabsVisible) }) {
                        Icon(HripsIcons.TbSidebar, if (tabsVisible) "Скрыть вкладки" else "Показать вкладки")
                    }
                }
                Spacer(Modifier.width(8.dp))
                ToolbarGroup {
                    BarButton(modifier = Modifier.size(bs), onClick = { tab.goHome() }) { Icon(HripsIcons.TbHome, "Домой") }
                }
                Spacer(Modifier.width(8.dp))
                var quickTabs by remember { mutableStateOf(false) }
                // Список вкладок по долгому «назад» раскрывается под кнопкой, якорь у него вся группа.
                Box {
                    TabQuickList(
                        visible = quickTabs,
                        onDismiss = { quickTabs = false },
                        tabs = { browser.contextTabs(tab) },
                        current = tab,
                        onPick = pickTab,
                        below = true,
                    )
                    ToolbarGroup {
                        BarButton(
                            modifier = Modifier.size(bs),
                            onClick = goBack,
                            enabled = backEnabled,
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                quickTabs = true
                            },
                        ) { Icon(HripsIcons.ChevronBack, "Назад") }
                        BarButton(modifier = Modifier.size(bs), onClick = { tab.goForward() }, enabled = tab.canGoForward && !tab.home) {
                            Icon(HripsIcons.ChevronForward, "Вперёд")
                        }
                    }
                }
                Box(Modifier.weight(1f).padding(horizontal = 8.dp).then(if (inlineTabs) Modifier.originAnchor("tabs") else Modifier), contentAlignment = Alignment.Center) {
                    val addressBar: @Composable () -> Unit = {
                        AddressBar(
                            tab = tab,
                            browser = browser,
                            onSearch = openSearch,
                            engine = shownEngine,
                            query = parsed?.second,
                            onPickEngine = pickEngine,
                            modifier = Modifier.fillMaxWidth(),
                            wide = wide,
                            inline = inlineTabs,
                            onSwipeDown = { requestTabs() },
                            onSwipeSide = swipeTab,
                            tabIndex = tabIndex,
                            onPageOptions = openPageMenu,
                            // Фото и QR только на главной, внутри строки вместо «обновить».
                            homeExtras = if (tab.isPrivate) null else ({
                                BarButton(modifier = Modifier.size(bs), onClick = searchByImage) { Icon(HripsIcons.Image.variant(2f), "Поиск по фото") }
                                BarButton(modifier = Modifier.size(bs), onClick = { showQrScan = true }) { Icon(HripsIcons.TbQr, "Сканировать QR-код") }
                            }),
                            onCopy = copyAddress,
                            onShare = shareAddress,
                            onPasteGo = pasteAndGo,
                            onCloseTab = closeCurrentTab,
                            onCloseAll = browser::closeAllTabs,
                        )
                    }
                    if (inlineTabs) InlineTabsRow(browser, showTabs = tabsVisible, modifier = Modifier.fillMaxWidth(), address = addressBar) else addressBar()
                }
                // Три капсулы: «+» со счётчиком вкладок; расширения с закреплёнными значками и загрузками; меню.
                ToolbarGroup {
                    BarButton(modifier = Modifier.size(bs), onClick = { browser.newTab(incognito = browser.current.isPrivate) }) { Icon(HripsIcons.Add.variant(2f), "Новая вкладка") }
                    if (!inlineTabs) TabCounterButton(browser.tabs.size, Modifier.size(bs).originAnchor("tabs"), tablet = true) { requestTabs() }
                }
                Spacer(Modifier.width(8.dp))
                var pinMenu by remember { mutableStateOf(false) }
                ToolbarGroup {
                    Box {
                        BarButton(
                            modifier = Modifier.size(bs),
                            onClick = { showExtManager = true },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                pinMenu = true
                            },
                        ) { Icon(HripsIcons.Puzzle.variant(2f), "Расширения") }
                        ToolbarPinMenu(pinMenu, { pinMenu = false }, store.toolbarPins) { store.toggleToolbarPin(it.id) }
                    }
                    ToolbarPin.entries.filter { it.id in store.toolbarPins }.forEach { pin ->
                        BarButton(
                            modifier = Modifier.size(bs),
                            enabled = pin != ToolbarPin.FIND || !tab.home,
                            onClick = {
                                when (pin) {
                                    ToolbarPin.BOOKMARKS -> showBookmarks = true
                                    ToolbarPin.HISTORY -> showHistory = true
                                    ToolbarPin.FIND -> findOpen = true
                                    ToolbarPin.SETTINGS -> showSettings = true
                                }
                            },
                        ) { Icon(pin.icon(), pin.title) }
                    }
                    AnimatedDownloadsSlot(browser.downloads, onClick = { showDownloads = true }, size = bs, icon = HripsIcons.DownloadArrow)
                }
                Spacer(Modifier.width(8.dp))
                ToolbarGroup {
                    BarButton(modifier = Modifier.size(bs), onClick = { showTools = true }) { Icon(HripsIcons.MoreVert, "Инструменты") }
                }
            }
            }
            if (wide && !inlineTabs && store.tabsPos == TABS_BELOW) {
                AnimatedVisibility(visible = tabsVisible) { TabStrip(browser) }
            }
        }
        }
        }

        // Планшет: ряд поиска как на телефоне плавает под адресной строкой на всю ширину, ничего не сдвигая.
        if (wide) {
            TabletFindOverlay(tab, visible = findOpen && !tab.home, onClose = { findOpen = false })
        }

        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
        if (wide) {
            AnimatedVisibility(
                visible = tabsVisible && !inlineTabs && store.tabsPos == TABS_LEFT,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) { VerticalTabs(browser) }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (tab.home && tab.isPrivate) key("private-start") { FadeInBox {
                PrivateStartPage(
                    privateCount = browser.privateCount,
                    screenshotsBlocked = !store.allowPrivateShots,
                    onSearch = openSearch,
                    onCloseAll = { browser.closePrivateTabs() },
                    engine = if (wide) null else shownEngine,
                    onPickEngine = pickEngine,
                    showSearch = false,
                )
            } } else if (tab.home) key("start") { FadeInBox {
                StartPage(store = store, wallpaper = app.wallpaper.image, onOpen = { tab.load(it) }, onSearch = openSearch, onScanQr = { showQrScan = true }, onSearchImage = searchByImage, engine = if (wide) null else shownEngine, onPickEngine = pickEngine, showSearch = false)
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
                        it.setAutofillEnabled(!tab.isPrivate && app.passwords.mode == PasswordMode.SYSTEM)
                        if (it.session !== tab.session) {
                            it.release()
                            it.setSession(tab.session)
                            runCatching { it.setVerticalClipping(pageClip[0]) }
                        }
                    },
                    onRelease = { it.release() },
                )
                TabSwapCover(tab)
                PullIndicator({ pullPx }, pullThresholdPx, pullRefreshing, Modifier.align(Alignment.TopCenter))
            }
        }
        }

        if (wide && !fs) Spacer(Modifier.windowInsetsPadding(navInsets))
    }

    // Заглушка под нижней капсулой (телефон, только m.youtube.com): сайт поднимает свою нижнюю панель над капсулой,
    // а за капсулой оставалась видна прокручиваемая страница. Закрываем эту зону цветом фона приложения; высота та же,
    // что у закрытой зоны, поэтому сворачивание капсулы и ряд поиска двигают заглушку вместе со страницей.
    val onYoutube = remember(tab.url) { runCatching { Uri.parse(tab.url).host?.lowercase() }.getOrNull() == "m.youtube.com" }
    val stubOn = clipActive && !tab.errorPage && onYoutube
    val stubAlpha by animateFloatAsState(if (stubOn) 1f else 0f, tween(HripsMotion.ChromeMs), label = "pageStub")
    if (!fs && !wide && !tab.home) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val h = bottomBase() + (HripsLayout.BarGap + barClipDp.value + findLift + 6.dp).roundToPx()
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                }
                .drawBehind { if (stubAlpha > 0.01f) drawRect(statusColor, alpha = stubAlpha) },
        )
    }

    AnimatedVisibility(
        visible = findPhone,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(bottom = HripsLayout.FindGap),
        enter = fadeIn(tween(HripsMotion.ChromeMs)) + slideInVertically(tween(HripsMotion.ChromeMs)) { it },
        exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it },
    ) {
        key(tab.id) { FindBottomBar(tab, onClose = { findOpen = false }) }
    }

    if (!fs && !wide) {
        BottomBar(
            tab = tab,
            browser = browser,
            shown = barText,
            collapsed = collapsed,
            backEnabled = backEnabled,
            tabCount = browser.contextTabs(tab).size,
            tabIndex = tabIndex,
            engine = shownEngine,
            onPickEngine = pickEngine,
            onSearchImage = searchByImage,
            onScanQr = { showQrScan = true },
            downloads = browser.downloads,
            onBack = goBack,
            onForward = { tab.goForward() },
            onAddress = openSearch,
            onPageOptions = openPageMenu,
            onMore = { showTools = true },
            onDownloads = { showDownloads = true },
            onExpand = expandBar,
            onSwipeUp = { if (collapsed) expandBar() else requestTabs() },
            onSwipeDown = { if (!tab.home) tab.collapseChrome() },
            onCopy = copyAddress,
            onShare = shareAddress,
            onPasteGo = pasteAndGo,
            onCloseTab = closeCurrentTab,
            onCloseAll = browser::closeAllTabs,
            quickTabs = { browser.contextTabs(tab) },
            onPickTab = pickTab,
            onSwipeSide = swipeTab,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(
                    if (findPhone) Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    else Modifier.navigationBarsPadding(),
                )
                .padding(bottom = HripsLayout.BarGap + findLift),
        )
    }

    // Расширение открыло вкладку или всплывающее окно: менеджер расширений убираем, иначе он останется поверх.
    LaunchedEffect(tab.id) { showExtManager = false }
    LaunchedEffect(browser.extensions.popup) { if (browser.extensions.popup != null) showExtManager = false }
    RevealHost(visible = showExtManager, group = null, onDismiss = { showExtManager = false }) {
        ExtensionsScreen(browser.extensions, tab.isPrivate, startOnStore = false, onBack = { showExtManager = false })
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
            onPasswords = { showPasswords = true },
            onOpenDownloads = { showSettings = false; showDownloads = true },
        )
    }
    RevealHost(visible = showTabs, group = "tabs", startColor = MaterialTheme.colorScheme.surfaceContainer, onDismiss = { showTabs = false }, fadeOnly = !wide, selfAnimated = !wide) {
        TabSwitcher(
            browser = browser,
            onClose = { showTabs = false },
            onHistory = { showTabs = false; showHistory = true },
        )
    }
    // Не подписываем весь экран на thumbnail, когда анимация закрытия переключателя не нужна.
    if (showTabs && tabsFade) {
        tab.thumbnail?.let { TabsCollapseOverlay(it) }
    }
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
    RevealHost(visible = showPasswords, group = null, onDismiss = { showPasswords = false }) {
        PasswordsScreen(app.passwords, onOpenSite = { url -> showPasswords = false; showSettings = false; browser.newTab(url, incognito = tab.isPrivate) }, onBack = { showPasswords = false })
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
            onLibrary = { page -> if (page == 0) showBookmarks = true else showHistory = true },
            onDownloads = { showDownloads = true },
            onSettings = { showSettings = true },
            onScanQr = { showQrScan = true },
            onFullscreen = { immersive = true },
            onTabs = { requestTabs() },
            onExtensions = { showExtManager = true },
        )
    }

    if (showPageOptions) {
        PageOptionsMenu(
            onDismiss = { showPageOptions = false },
            browser = browser,
            tab = tab,
            onFind = { findOpen = true },
            onScreenshot = { PageActions.screenshot(activity, viewRef[0], tab, browser.downloads) },
            onQr = { showPageQr = true },
            onSecurity = { showPageSecurity = true },
        )
    }
    if (showReaderSheet && tab.readerActive) {
        ReaderSheet(
            onDismiss = { showReaderSheet = false },
            browser = browser,
            tab = tab,
            onFind = { findOpen = true },
        )
    }
    if (showPageQr) QrDialog(tab.url) { showPageQr = false }
    if (showPageSecurity) SecurityDialog(tab) { showPageSecurity = false }

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

    browser.external.pending?.let { ExternalOpenDialog(browser, tab, it) }
    browser.translatePrompt?.let { TranslateDialog(browser, it) }
    browser.permissions.queue.firstOrNull()?.let { PermissionDialog(browser.permissions, it) }
}

@Composable
private fun ExternalOpenDialog(browser: Browser, tab: Tab, r: ExternalRequest) {
    val decline: () -> Unit = { browser.external.dismiss(); if (r.web) tab.load(r.uri) }
    HripsDialog(
        icon = HripsIcons.Open,
        onDismissRequest = decline,
        title = { Text(if (r.appLabel != null) "Открыть в «${r.appLabel}»?" else "Открыть в другом приложении?") },
        text = { Text(r.uri.take(200)) },
        confirmButton = {
            HripsTextButton(onClick = { browser.external.open(r) { fallback -> tab.load(fallback) } }) { Text("Открыть") }
        },
        dismissButton = { HripsTextButton(onClick = decline) { Text(if (r.web) "Остаться в браузере" else "Отмена") } },
    )
}

@Composable
private fun TranslateDialog(browser: Browser, p: TranslatePrompt) {
    HripsDialog(
        icon = HripsIcons.Translate,
        onDismissRequest = { browser.translatePrompt = null },
        title = { Text("Перевести страницу?") },
        text = {
            Text(
                "С языка «${PageActions.languageName(p.from)}» на «${PageActions.languageName(p.to)}». " +
                    "Для этой пары нужно один раз скачать языковые модели (около ${maxOf(1L, p.bytes / 1_048_576L)} МБ). " +
                    "Перевод идёт на устройстве: страница никуда не отправляется.",
            )
        },
        confirmButton = {
            HripsTextButton(onClick = {
                browser.translatePrompt = null
                PageActions.startTranslate(p.tab, p.from, p.to)
            }) { Text("Скачать и перевести") }
        },
        dismissButton = { HripsTextButton(onClick = { browser.translatePrompt = null }) { Text("Отмена") } },
    )
}

@Composable
private fun PermissionDialog(permissions: Permissions, req: PermissionRequest) {
    req.contentPermission?.let { permission -> LaunchedEffect(req) { permission.notifyShown() } }
    HripsDialog(
        icon = HripsIcons.Shield,
        onDismissRequest = { permissions.answer(req, false) },
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
        confirmButton = { HripsTextButton(onClick = { permissions.answer(req, true) }) { Text("Разрешить") } },
        dismissButton = { HripsTextButton(onClick = { permissions.answer(req, false) }) { Text("Запретить") } },
    )
}

fun openAutofillSettings(context: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:")),
        Intent(Settings.ACTION_SETTINGS),
    )
    if (intents.none { runCatching { context.startActivity(it) }.isSuccess }) {
        Notices.show("Откройте: Настройки → Пароли и аккаунты → Автозаполнение")
    }
}

private fun Browser.thumbCandidates(radius: Int): List<Pair<Int, Tab>> {
    val center = currentIndex
    return tabs.indices
        .filter { abs(it - center) <= radius && !tabs[it].isPrivate && !tabs[it].home }
        .sortedBy { abs(it - center) }
        .map { it to tabs[it] }
}

/** Ряд поиска планшета: нулевая высота, рисуется поверх страницы под адресной строкой. Вынесен из Column из-за DSL_SCOPE_VIOLATION. */
@Composable
private fun TabletFindOverlay(tab: Tab, visible: Boolean, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(0.dp).zIndex(1f)) {
        Box(Modifier.fillMaxWidth().wrapContentHeight(align = Alignment.Top, unbounded = true)) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(HripsMotion.ChromeMs)) + slideInVertically(tween(HripsMotion.ChromeMs)) { -it },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it },
            ) {
                key(tab.id) { FindBottomBar(tab, onClose = onClose, modifier = Modifier.padding(top = HripsLayout.BarGap)) }
            }
        }
    }
}
