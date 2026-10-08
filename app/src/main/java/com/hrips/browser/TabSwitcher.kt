package com.hrips.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Снимок страницы для карточки вкладки. Делается через PixelCopy, пока страница ещё на экране
 * (GeckoView рисует в SurfaceView, обычный drawToBitmap дал бы чёрный квадрат).
 * [done] вызывается всегда, с картинкой или без: после него можно открывать переключатель.
 */
fun captureThumbnail(activity: Activity?, view: View?, tab: Tab, done: () -> Unit) {
    val gv = view as? org.mozilla.geckoview.GeckoView
    if (activity == null || gv == null || tab.home ||
        gv.width == 0 || gv.height == 0 || !gv.isShown ||
        gv.session !== tab.session
    ) {
        done(); return
    }
    // Окно закрыто от скриншотов (приватная вкладка): снимок был бы чёрным
    if ((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
        done(); return
    }
    val main = Handler(Looper.getMainLooper())
    var finished = false
    // done() вызываем ровно один раз и не позже чем через 1.5 с, чтобы переключатель вкладок всегда открывался
    val finish: () -> Unit = {
        if (!finished) { finished = true; done() }
    }
    main.postDelayed({ finish() }, 1500)

    fun store(bmp: Bitmap) {
        tab.thumbnail = bmp.asImageBitmap()
        // На диск (в фоне): после перезапуска карточка сразу с картинкой. Приватные вкладки не сохраняем
        val url = tab.url
        if (!tab.isPrivate && url.isNotBlank()) {
            val app = activity.applicationContext
            AppExecutors.tryExecute { TabThumbs.save(app, url, bmp) }
        }
    }

    // Запасной способ: PixelCopy окна (работает, пока страница на экране)
    fun viaPixelCopy() {
        if (finished || Build.VERSION.SDK_INT < 26) { finish(); return }
        try {
            val loc = IntArray(2)
            gv.getLocationInWindow(loc)
            val w = 720
            val h = (w * gv.height.toFloat() / gv.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val rect = Rect(loc[0], loc[1], loc[0] + gv.width, loc[1] + gv.height)
            PixelCopy.request(activity.window, rect, bmp, { result ->
                if (result == PixelCopy.SUCCESS) store(bmp)
                finish()
            }, main)
        } catch (e: Throwable) {
            finish()
        }
    }

    // Основной способ: снимок средствами самого движка (так делает Firefox), он не зависит от SurfaceView
    try {
        gv.capturePixels().accept({ full ->
            if (full == null || full.width <= 0 || full.height <= 0) {
                viaPixelCopy()
            } else {
                val w = 720
                val h = (w * full.height.toFloat() / full.width).toInt().coerceAtLeast(1)
                val scaled = if (full.width > w) Bitmap.createScaledBitmap(full, w, h, true) else full
                store(scaled)
                finish()
            }
        }, { viaPixelCopy() })
    } catch (e: Throwable) {
        viaPixelCopy()
    }
}

private fun tabKey(t: Tab) = t.id

/**
 * Переключатель вкладок на весь экран в стиле Material 3 Expressive: переключатель «Вкладки / Приватные»
 * со сменой формы кнопок, карусель крупных карточек с живым превью страницы (центральная крупнее, соседние
 * уменьшаются и бледнеют) или сетка, плавающая панель с большой кнопкой «+». Карточку можно смахнуть вверх, чтобы закрыть.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TabSwitcher(browser: Browser, onClose: () -> Unit, onHistory: () -> Unit) {
    BackHandler(onBack = onClose)
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var grid by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    // Группы: фильтр (id группы), диалоги и шторка закрытых вкладок
    var filter by remember { mutableStateOf<String?>(null) }
    var newGroupFor by remember { mutableStateOf<Tab?>(null) }
    var editGroup by remember { mutableStateOf<TabGroup?>(null) }
    var showClosed by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(browser.groups.size) {
        browser.pruneGroups()
        if (filter != null && browser.groups.none { it.id == filter }) filter = null
    }
    // Закрытие карточки с возможностью сразу вернуть вкладку
    val closeWithUndo: (Int) -> Unit = { i ->
        val before = browser.closedTabs.firstOrNull()
        browser.closeTab(i)
        if (browser.closedTabs.firstOrNull() !== before) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar("Вкладка закрыта", actionLabel = "Вернуть", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) browser.reopenLast()
            }
        }
    }

    val all = browser.tabs.toList()
    val current = browser.currentIndex
    val normalCount = all.count { !it.isPrivate }
    val privateCount = all.size - normalCount
    val q = query.trim()

    // Списки вкладок с учётом группы и поиска
    val normalList = all.mapIndexedNotNull { i, t ->
        if (!t.isPrivate && (filter == null || t.group == filter) && t.matches(q)) i to t else null
    }
    val privList = all.mapIndexedNotNull { i, t -> if (t.isPrivate && t.matches(q)) i to t else null }

    // Карусель одна на все вкладки: сначала обычные, потом приватные. Поэтому к приватным
    // можно перейти одним обычным смахиванием, без отдельного «перелистывания страницы».
    // Пустой раздел представлен одной карточкой-заглушкой.
    val slides = buildList {
        normalList.forEach { add(Slide(it.second, it.first, false)) }
        if (normalList.isEmpty()) add(Slide(null, -1, false))
        privList.forEach { add(Slide(it.second, it.first, true)) }
        if (privList.isEmpty()) add(Slide(null, -1, true))
    }
    val slidesNow by rememberUpdatedState(slides)

    // 0 = обычные, 1 = приватные. Источник правды для шапки, фона и нижней панели
    var mode by remember { mutableIntStateOf(if (browser.current.isPrivate) 1 else 0) }
    val firstSlide = { m: Int ->
        slides.indexOfFirst { it.index == current && it.priv == (m == 1) }
            .takeIf { it >= 0 } ?: slides.indexOfFirst { it.priv == (m == 1) }.coerceAtLeast(0)
    }
    val cp = rememberPagerState(initialPage = firstSlide(mode)) { slides.size }
    val gp = rememberPagerState(initialPage = mode) { 2 }

    LaunchedEffect(grid) {
        if (grid) gp.scrollToPage(mode) else cp.scrollToPage(firstSlide(mode))
    }
    LaunchedEffect(cp) {
        snapshotFlow { cp.currentPage }.collect { p ->
            if (!grid) slidesNow.getOrNull(p)?.let { mode = if (it.priv) 1 else 0 }
        }
    }
    LaunchedEffect(gp) {
        snapshotFlow { gp.currentPage }.collect { if (grid) mode = it }
    }

    // Положение между «обычными» (0) и «приватными» (1), плавно следует за пальцем
    val progress: () -> Float = {
        if (grid) {
            (gp.currentPage + gp.currentPageOffsetFraction).coerceIn(0f, 1f)
        } else {
            val list = slidesNow
            val pos = (cp.currentPage + cp.currentPageOffsetFraction).coerceIn(0f, list.lastIndex.toFloat())
            val lo = pos.toInt()
            val hi = (lo + 1).coerceAtMost(list.lastIndex)
            val f = pos - lo
            val a = if (list[lo].priv) 1f else 0f
            val b = if (list[hi].priv) 1f else 0f
            a + (b - a) * f
        }
    }
    val bgNormal = cs.surface
    val bgPrivate = lerp(cs.surface, cs.tertiaryContainer, 0.55f)

    val select: (Int) -> Unit = { m ->
        scope.launch { if (grid) gp.animateScrollToPage(m) else cp.animateScrollToPage(firstSlide(m)) }
    }

    @Composable
    fun card(t: Tab, i: Int, compact: Boolean, mod: Modifier) {
        TabCard(
            t, selected = i == current, compact = compact, onClick = { browser.currentIndex = i; onClose() }, onClose = { closeWithUndo(i) },
            group = browser.groupOf(t), groups = browser.groups,
            onAssign = { g -> browser.setGroup(t, g) }, onNewGroup = { newGroupFor = t },
            modifier = mod,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            // Фон плавно меняет тон по мере смахивания к приватным вкладкам
            .drawBehind { drawRect(lerp(bgNormal, bgPrivate, progress())) }
            // Экран лежит поверх GeckoView: нажатия мимо карточек не должны попадать в сайт под ним
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Шапка: переключатель «Вкладки / Приватные» и поиск по вкладкам
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searching) {
                    Surface(shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(HripsIcons.Search, null, tint = cs.onSurfaceVariant)
                            Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 16.dp)) {
                                if (query.isEmpty()) Text("Поиск по вкладкам", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                                    cursorBrush = SolidColor(cs.primary),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                )
                            }
                            IconButton(onClick = { searching = false; query = "" }) { Icon(HripsIcons.Close, "Закрыть поиск") }
                        }
                    }
                } else {
                    ModeToggle(
                        progress = progress,
                        normal = normalCount,
                        priv = privateCount,
                        onSelect = select,
                        modifier = Modifier.weight(1f).widthIn(max = 400.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    FilledTonalIconButton(onClick = { searching = true }, modifier = Modifier.size(56.dp)) {
                        Icon(HripsIcons.Search, "Поиск по вкладкам")
                    }
                }
            }

            if (!searching && mode == 0 && browser.groups.isNotEmpty()) {
                GroupChips(
                    groups = browser.groups,
                    counts = browser.groups.associate { g -> g.id to all.count { it.group == g.id } },
                    total = normalCount,
                    selected = filter,
                    onSelect = { filter = it },
                    onEdit = { editGroup = it },
                )
            }

            if (grid) {
                // Сетка: два раздела, между ними переключаемся обычным смахиванием (вертикальная прокрутка не мешает)
                HorizontalPager(state = gp, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                    val priv = page == 1
                    val list = if (priv) privList else normalList
                    if (list.isEmpty()) {
                        EmptyTabs(priv, searching = q.isNotEmpty(), onNew = { browser.newTab(incognito = priv); onClose() })
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 170.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            gridItems(list, key = { tabKey(it.second) }) { (i, t) ->
                                card(t, i, true, Modifier.fillMaxWidth().aspectRatio(0.78f).animateItem())
                            }
                        }
                    }
                }
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(bottom = 104.dp)) {
                    val cardW = minOf(maxWidth * 0.78f, 400.dp)
                    val cardH = minOf(cardW * (if (maxWidth > 600.dp) 1.0f else 1.4f), maxHeight - 16.dp)
                    HorizontalPager(
                        state = cp,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = (maxWidth - cardW) / 2),
                        pageSize = PageSize.Fixed(cardW),
                        pageSpacing = 14.dp,
                        beyondViewportPageCount = 1,
                        key = { slidesNow.getOrNull(it)?.key ?: it },
                    ) { page ->
                        val slide = slidesNow.getOrNull(page)
                        Box(
                            Modifier
                                .fillMaxSize()
                                // Центральная карточка крупнее и ярче, соседние уменьшаются и бледнеют
                                .graphicsLayer {
                                    val d = ((cp.currentPage - page) + cp.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                                    val sc = 1f - 0.08f * d
                                    scaleX = sc
                                    scaleY = sc
                                    alpha = 1f - 0.45f * d
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val t = slide?.tab
                            if (slide == null) {
                                Unit
                            } else if (t == null) {
                                EmptySlide(
                                    slide.priv, searching = q.isNotEmpty(),
                                    onNew = { browser.newTab(incognito = slide.priv); onClose() },
                                    modifier = Modifier.width(cardW).height(cardH),
                                )
                            } else {
                                card(t, slide.index, false, Modifier.width(cardW).height(cardH))
                            }
                        }
                    }
                }
            }
        }

        SwitcherBar(
            grid = grid,
            count = browser.tabs.size,
            onToggleGrid = { grid = !grid },
            onHistory = onHistory,
            onNew = { browser.newTab(incognito = mode == 1); onClose() },
            onDone = onClose,
            privatePage = mode == 1,
            onCloseAll = {
                if (mode == 1) browser.closePrivateTabs()
                else browser.tabs.indices.reversed().filter { !browser.tabs[it].isPrivate }.forEach { browser.closeTab(it) }
            },
            closedCount = browser.closedTabs.size,
            onClosed = { showClosed = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))

        newGroupFor?.let { t ->
            GroupDialog(
                "Новая группа", "", browser.groups.size % GroupColors.size, "Создать",
                onDismiss = { newGroupFor = null },
                // По имени: после onConfirm в GroupDialog есть необязательные лямбды, и хвостовая лямбда уходила бы в последнюю из них
                onConfirm = { n, c ->
                    browser.createGroup(t, n, c)
                    newGroupFor = null
                },
            )
        }
        editGroup?.let { g ->
            GroupDialog(
                "Группа", g.name, g.color, "Сохранить",
                onDismiss = { editGroup = null },
                onConfirm = { n, c -> g.name = n; g.color = c; editGroup = null },
                onUngroup = { browser.ungroup(g); editGroup = null; filter = null },
                onCloseAll = { browser.closeGroup(g); editGroup = null; filter = null },
            )
        }
        if (showClosed) {
            ClosedTabsSheet(browser, onDismiss = { showClosed = false }, onRestored = { showClosed = false; onClose() })
        }
    }
}

private class Slide(val tab: Tab?, val index: Int, val priv: Boolean) {
    val key: Any = tab?.let { tabKey(it) } ?: if (priv) "empty-private" else "empty-normal"
}

private fun Tab.matches(q: String) = q.isEmpty() || label().contains(q, true) || url.contains(q, true)

/**
 * Переключатель «Вкладки / Приватные»: одна большая таблетка, внутри цветной «ползунок», который едет
 * за пальцем, пока смахиваете карточки. При переходе к приватным цвет меняется на акцентный третичный.
 */
@Composable
private fun ModeToggle(progress: () -> Float, normal: Int, priv: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val p = progress().coerceIn(0f, 1f)
    BoxWithConstraints(modifier.height(56.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(4.dp)) {
        val half = maxWidth / 2
        Box(
            Modifier
                .offset { IntOffset((progress().coerceIn(0f, 1f) * half.toPx()).roundToInt(), 0) }
                .width(half)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(lerp(cs.primary, cs.tertiary, p)),
        )
        Row(Modifier.fillMaxSize()) {
            ModeSegment(HripsIcons.Grid, "Вкладки", normal, lerp(cs.onPrimary, cs.onSurfaceVariant, p), Modifier.weight(1f)) { onSelect(0) }
            ModeSegment(HripsIcons.Mask, "Приватные", priv, lerp(cs.onSurfaceVariant, cs.onTertiary, p), Modifier.weight(1f)) { onSelect(1) }
        }
    }
}

@Composable
private fun ModeSegment(
    icon: ImageVector,
    label: String,
    count: Int,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent, contentColor = content, modifier = modifier.fillMaxHeight()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Surface(shape = CircleShape, color = content.copy(alpha = 0.2f), contentColor = content) {
                Text(count.toString(), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Значок вкладки в шапке карточки: приватная, стартовая или иконка сайта. */
@Composable
private fun TabIcon(tab: Tab, size: androidx.compose.ui.unit.Dp) {
    val cs = MaterialTheme.colorScheme
    when {
        tab.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(size * 0.8f), tint = cs.primary)
        tab.home -> Icon(HripsIcons.Home, null, Modifier.size(size * 0.8f), tint = cs.onSurfaceVariant)
        else -> Favicon(tab.url, size) {
            SiteTile(tab.url, tab.label(), size, shape = CircleShape)
        }
    }
}

/** Если превью ещё нет: большая фигура (печенье) со значком сайта. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabPlaceholder(tab: Tab) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(96.dp).background(cs.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
        when {
            tab.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(44.dp), tint = cs.onSecondaryContainer)
            tab.home -> Icon(HripsIcons.Home, null, Modifier.size(44.dp), tint = cs.onSecondaryContainer)
            else -> Favicon(tab.url, 44.dp) {
                SiteTile(tab.url, tab.label(), 44.dp)
            }
        }
    }
}

/**
 * Карточка вкладки: значок, название и адрес, кнопка закрытия и крупное превью страницы (снимок экрана).
 * Выбранная выделена цветом и рамкой, вкладка в группе - цветной точкой и рамкой цвета группы.
 * Нажатие открывает, удержание - меню групп, смахивание вверх закрывает.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabCard(
    tab: Tab,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    group: TabGroup?,
    groups: List<TabGroup>,
    onAssign: (TabGroup?) -> Unit,
    onNewGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current.applicationContext
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    var menu by remember { mutableStateOf(false) }
    val dismissPx = with(LocalDensity.current) { 110.dp.toPx() }
    val titleColor = if (selected) cs.onPrimaryContainer else cs.onSurface
    val subColor = titleColor.copy(alpha = 0.7f)
    val subtitle = when {
        tab.isPrivate && tab.home -> "Приватная вкладка"
        tab.home -> "Начальная страница"
        else -> runCatching { Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.") ?: tab.url
    }
    val shape = RoundedCornerShape(if (compact) 28.dp else 36.dp)

    Box(
        modifier
            .graphicsLayer {
                translationY = offsetY.value
                alpha = 1f - (abs(offsetY.value) / (dismissPx * 2.2f)).coerceIn(0f, 0.8f)
            }
            .pointerInput(tab) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (offsetY.value < -dismissPx) {
                                offsetY.animateTo(-dismissPx * 5, tween(160))
                                onClose()
                            } else {
                                offsetY.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offsetY.animateTo(0f, spring()) } },
                ) { change, dy ->
                    change.consume()
                    scope.launch { offsetY.snapTo((offsetY.value + dy).coerceAtMost(0f)) }
                }
            },
    ) {
        Surface(
            shape = shape,
            color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
            border = when {
                group != null -> BorderStroke(2.dp, group.tint.copy(alpha = 0.85f))
                else -> null
            },
            shadowElevation = 0.dp,
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                ),
        ) {
            Column(Modifier.padding(if (compact) 8.dp else 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TabIcon(tab, if (compact) 24.dp else 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (compact && group != null) {
                                Box(Modifier.size(8.dp).background(group.tint, CircleShape))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                tab.label(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                                color = titleColor,
                            )
                        }
                        if (!compact) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (group != null) {
                                    Box(Modifier.size(8.dp).background(group.tint, CircleShape))
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        group.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelMedium, color = titleColor,
                                        modifier = Modifier.widthIn(max = 90.dp),
                                    )
                                    Text(" · ", style = MaterialTheme.typography.bodySmall, color = subColor)
                                }
                                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = subColor)
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        onClick = onClose,
                        shape = CircleShape,
                        color = titleColor.copy(alpha = 0.1f),
                        contentColor = titleColor,
                        modifier = Modifier.size(40.dp).semantics {
                            contentDescription = "Закрыть вкладку ${tab.label()}"
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(HripsIcons.Close, "Закрыть вкладку", Modifier.size(if (compact) 18.dp else 20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(if (compact) 20.dp else 26.dp)).background(cs.surfaceContainerLowest),
                    contentAlignment = Alignment.Center,
                ) {
                    // Свежий снимок из памяти, иначе сохранённый на диске (после перезапуска)
                    // В сетке хватает маленькой копии: она декодируется быстрее и занимает меньше памяти
                    val fromDisk by produceState<ImageBitmap?>(null, tab.url, tab.thumbnail, compact) {
                        value = if (tab.thumbnail == null && !tab.isPrivate && !tab.home && tab.url.isNotBlank()) TabThumbs.load(context, tab.url, small = compact) else null
                    }
                    val thumb = tab.thumbnail ?: fromDisk
                    // Нет снимка: пробуем картинку самой страницы (og:image). Для приватных вкладок не запрашивается ничего
                    val pageImageState = produceState<ImageBitmap?>(null, tab.url, thumb == null, PageImages.enabled) {
                        value = if (thumb == null && !tab.isPrivate && !tab.home && tab.url.isNotBlank()) PageImages.load(context, tab.url) else null
                    }
                    val pageImage = pageImageState.value
                    if (thumb != null && !tab.home) {
                        Image(
                            bitmap = thumb,
                            contentDescription = "Превью страницы",
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (pageImage != null && !tab.home) {
                        Image(
                            bitmap = pageImage,
                            contentDescription = "Картинка страницы",
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        TabPlaceholder(tab)
                    }
                }
            }
        }

        // Меню по удержанию: группы и закрытие. У приватных вкладок групп нет
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(24.dp)) {
            if (!tab.isPrivate) {
                groups.filter { it.id != tab.group }.forEach { g ->
                    DropdownMenuItem(
                        text = { Text("В группу «${g.name}»", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Box(Modifier.size(14.dp).background(g.tint, CircleShape)) },
                        onClick = { menu = false; onAssign(g) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("В новую группу…") },
                    leadingIcon = { Icon(HripsIcons.Add, null) },
                    onClick = { menu = false; onNewGroup() },
                )
                if (group != null) {
                    DropdownMenuItem(
                        text = { Text("Убрать из группы") },
                        leadingIcon = { Icon(HripsIcons.Close, null) },
                        onClick = { menu = false; onAssign(null) },
                    )
                }
            }
            DropdownMenuItem(
                text = { Text("Закрыть вкладку") },
                leadingIcon = { Icon(HripsIcons.Trash, null) },
                onClick = { menu = false; onClose() },
            )
        }
    }
}

@Composable
private fun EmptySlide(priv: Boolean, searching: Boolean, onNew: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        EmptyTabs(priv, searching, onNew, Modifier.fillMaxSize())
    }
}

@Composable
private fun EmptyTabs(priv: Boolean, searching: Boolean, onNew: () -> Unit, modifier: Modifier = Modifier.fillMaxSize().padding(bottom = 104.dp)) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = if (priv) HripsIcons.Mask else HripsIcons.Search,
            title = when {
                searching -> "Ничего не найдено"
                priv -> "Нет приватных вкладок"
                else -> "Нет открытых вкладок"
            },
            text = when {
                searching -> "Попробуйте другой запрос"
                priv -> "История и cookies приватных вкладок не сохраняются"
                else -> "Откройте новую вкладку, чтобы начать"
            },
            badge = if (priv) Badge.CLOVER else Badge.COOKIE9,
        )
        if (!searching) {
            FilledTonalButton(onClick = onNew) { Text(if (priv) "Приватная вкладка" else "Новая вкладка") }
        }
    }
}

/** Нижняя плавающая панель Expressive: вид, история, счётчик (вернуться к странице), ещё; рядом большая кнопка «+» в форме печенья. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwitcherBar(
    grid: Boolean,
    count: Int,
    privatePage: Boolean,
    onToggleGrid: () -> Unit,
    onHistory: () -> Unit,
    onNew: () -> Unit,
    onDone: () -> Unit,
    onCloseAll: () -> Unit,
    closedCount: Int,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val fabBg by animateColorAsState(if (privatePage) cs.tertiary else cs.primary, label = "fabBg")
    val fabFg by animateColorAsState(if (privatePage) cs.onTertiary else cs.onPrimary, label = "fabFg")
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalFloatingToolbar(expanded = true) {
                IconButton(onClick = onToggleGrid) {
                    Icon(if (grid) HripsIcons.Rows else HripsIcons.Grid, if (grid) "Показать каруселью" else "Показать сеткой")
                }
                IconButton(onClick = onHistory) { Icon(HripsIcons.History, "История") }
                TabCounterButton(count, onClick = onDone)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(HripsIcons.MoreVert, "Ещё") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(24.dp)) {
                        if (!privatePage) {
                            DropdownMenuItem(
                                text = { Text(if (closedCount > 0) "Недавно закрытые ($closedCount)" else "Недавно закрытые") },
                                leadingIcon = { Icon(HripsIcons.History, null) },
                                enabled = closedCount > 0,
                                onClick = { menu = false; onClosed() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (privatePage) "Закрыть приватные вкладки" else "Закрыть все вкладки") },
                            leadingIcon = { Icon(HripsIcons.Trash, null) },
                            onClick = { menu = false; onCloseAll() },
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = onNew,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                containerColor = fabBg,
                contentColor = fabFg,
                modifier = Modifier.size(68.dp),
            ) {
                Icon(HripsIcons.Add, if (privatePage) "Новая приватная вкладка" else "Новая вкладка", Modifier.size(30.dp))
            }
        }
    }
}
