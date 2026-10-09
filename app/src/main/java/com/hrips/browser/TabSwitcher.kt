@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.border
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
fun captureThumbnail(
    activity: Activity?,
    view: View?,
    tab: Tab,
    allowPixelCopy: Boolean = true,
    timeoutMs: Long = 1500,
    done: () -> Unit,
) {
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
    main.postDelayed({ finish() }, timeoutMs)

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
        if (finished || !allowPixelCopy || Build.VERSION.SDK_INT < 26) { finish(); return }
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
    LaunchedEffect(browser.groups.size) {
        browser.pruneGroups()
        if (filter != null && browser.groups.none { it.id == filter }) filter = null
    }
    // Закрытие карточки с возможностью сразу вернуть вкладку
    val closeWithUndo: (Int) -> Unit = { i ->
        val before = browser.closedTabs.firstOrNull()
        browser.closeTab(i)
        if (browser.closedTabs.firstOrNull() !== before) {
            Notices.show("Вкладка закрыта", "Вернуть") { browser.reopenLast() }
        }
    }

    // Карточка, раскрывающаяся в страницу (после нажатия на карточку)
    var expanding by remember { mutableStateOf<Expand?>(null) }
    val origins = LocalOrigins.current

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
            t, selected = i == current, compact = compact,
            onClick = { rect, thumb -> if (expanding == null) expanding = Expand(rect, thumb, i) },
            onClose = { closeWithUndo(i) },
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
                                    // signed > 0: карточка правее центра. Боковые поворачиваются лицом к центру, как в coverflow
                                    val signed = ((page - cp.currentPage) - cp.currentPageOffsetFraction).coerceIn(-1.5f, 1.5f)
                                    val d = signed.absoluteValue.coerceIn(0f, 1f)
                                    val sc = 1f - 0.12f * d
                                    scaleX = sc
                                    scaleY = sc
                                    alpha = 1f - 0.5f * d
                                    rotationY = -signed.coerceIn(-1f, 1f) * 22f
                                    cameraDistance = 14f * density
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val t = slide?.tab
                            if (slide == null) {
                                // Пустое место карусели: рисовать нечего
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
                if (mode == 1) {
                    browser.closePrivateTabs()
                } else {
                    val toClose = browser.tabs.count { !it.isPrivate }
                    browser.tabs.indices.reversed().filter { !browser.tabs[it].isPrivate }.forEach { browser.closeTab(it) }
                    // История закрытых хранит 15 вкладок: вернуть можно не больше
                    val undoable = minOf(toClose, browser.closedTabs.size)
                    if (undoable > 0) {
                        Notices.show("Закрыто вкладок: $toClose", "Вернуть") { repeat(undoable) { browser.reopenLast() } }
                    }
                }
            },
            closedCount = browser.closedTabs.size,
            onClosed = { showClosed = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        expanding?.let { e ->
            ExpandOverlay(e) {
                browser.currentIndex = e.index
                origins.instantClose = true
                onClose()
            }
        }

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

/** Нажатая карточка: где она на экране и какая на ней картинка. */
private class Expand(val rect: androidx.compose.ui.geometry.Rect, val thumb: ImageBitmap?, val index: Int)

/**
 * Карточка вырастает до размера экрана: рамка скругления уменьшается до нуля, картинка растягивается вместе с ней.
 * В конце вызывается [onDone]: переключатель закрывается мгновенно (его собственная анимация выхода пропускается).
 */
@Composable
private fun ExpandOverlay(e: Expand, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val bg = MaterialTheme.colorScheme.surfaceContainerLowest
    val startCorner = with(LocalDensity.current) { 36.dp.toPx() }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, spec)
        onDone()
    }
    Box(
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val t = progress.value
                val full = androidx.compose.ui.geometry.Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                val r = androidx.compose.ui.geometry.lerp(e.rect, full, t)
                val placeable = measurable.measure(
                    androidx.compose.ui.unit.Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
            }
            .graphicsLayer {
                val corner = startCorner * (1f - progress.value.coerceIn(0f, 1f))
                shape = RoundedCornerShape(androidx.compose.foundation.shape.CornerSize(corner))
                clip = true
            }
            .background(bg),
    ) {
        if (e.thumb != null) {
            Image(
                bitmap = e.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private class Slide(val tab: Tab?, val index: Int, val priv: Boolean) {
    val key: Any = tab?.let { tabKey(it) } ?: if (priv) "empty-private" else "empty-normal"
}

private fun Tab.matches(q: String) = matchesQuery(label(), url, q)

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

/**
 * Если превью ещё нет: вся площадь залита цветом сайта (тем же, что у плитки), по центру печенье со значком.
 * У каждого сайта свой цвет, поэтому вкладки без снимка не сливаются в серое поле. Приватная и стартовая - цвета темы.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabPlaceholder(tab: Tab, compact: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val (bg, fg) = when {
        tab.isPrivate -> cs.tertiaryContainer to cs.onTertiaryContainer
        tab.home -> cs.primaryContainer to cs.onPrimaryContainer
        else -> siteColors(tab.url, dark)
    }
    val cookie = if (compact) 64.dp else 104.dp
    val icon = if (compact) 30.dp else 48.dp
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0.78f).compositeOver(cs.surfaceContainerLowest)))),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(cookie).background(fg.copy(alpha = 0.16f), MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
            when {
                tab.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(icon), tint = fg)
                tab.home -> Icon(HripsIcons.Home, null, Modifier.size(icon), tint = fg)
                else -> Favicon(tab.url, icon) { SiteTile(tab.url, tab.label(), icon) }
            }
        }
    }
}

/**
 * Миниатюра начальной страницы: настоящая главная, разложенная в размер окна и уменьшенная до ширины карточки.
 * Касания до неё не доходят (их получает карточка), а точки перехода у неё свои, чтобы не мешать настоящей странице.
 */
@Composable
private fun HomeTabPreview(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as HripsApp
    val cfg = LocalConfiguration.current
    val wide = isWideWindow()
    val cs = MaterialTheme.colorScheme
    val noPick: (SearchEngine) -> Unit = {}
    BoxWithConstraints(modifier.clipToBounds().background(cs.background)) {
        val fullW = cfg.screenWidthDp.dp
        val fullH = cfg.screenHeightDp.dp
        val scale = maxWidth / fullW
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .size(fullW, fullH)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            CompositionLocalProvider(LocalOrigins provides remember { OriginTracker() }) {
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    StartPage(
                        store = app.store,
                        wallpaper = app.wallpaper.image,
                        onOpen = {},
                        onSearch = {},
                        onScanQr = {},
                        engine = if (wide) null else SearchEngines.current,
                        onPickEngine = if (wide) null else noPick,
                    )
                    Box(Modifier.matchParentSize().pointerInput(Unit) {})
                }
            }
        }
    }
}

/** Метка группы на карточке: в карусели плашка с названием, в сетке только цветная точка. */
@Composable
private fun GroupMark(group: TabGroup, compact: Boolean, modifier: Modifier = Modifier) {
    if (compact) {
        Box(modifier.size(16.dp).background(group.tint, CircleShape).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape))
    } else {
        Surface(shape = CircleShape, color = group.tint, contentColor = if (group.tint.luminance() > 0.5f) Color.Black else Color.White, modifier = modifier) {
            Text(
                group.name, Modifier.padding(horizontal = 12.dp, vertical = 5.dp).widthIn(max = 120.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
            )
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
    onClick: (androidx.compose.ui.geometry.Rect, ImageBitmap?) -> Unit,
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
    var pastThreshold by remember { mutableStateOf(false) }
    var cardRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var shownThumb by remember { mutableStateOf<ImageBitmap?>(null) }
    val subtitle = when {
        tab.isPrivate && tab.home -> "Приватная вкладка"
        tab.home -> "Начальная страница"
        else -> runCatching { Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.") ?: tab.url
    }
    val shape = RoundedCornerShape(if (compact) 28.dp else 36.dp)

    Box(
        modifier
            .onGloballyPositioned { cardRect = it.boundsInRoot() }
            .graphicsLayer {
                translationY = offsetY.value
                // Чем выше утащили, тем сильнее карточка сжимается и наклоняется, как отрываемый листок
                val pull = (-offsetY.value / dismissPx).coerceIn(0f, 2f)
                val sc = 1f - 0.07f * pull
                scaleX = sc
                scaleY = sc
                rotationZ = -pull * 3f
                alpha = 1f - (abs(offsetY.value) / (dismissPx * 2.2f)).coerceIn(0f, 0.8f)
            }
            .pointerInput(tab, compact) {
                // В сетке карточки лежат в прокручиваемом списке: смахивание вверх отнимало бы у него прокрутку.
                // Там карточку закрывают кнопкой или удержанием
                if (compact) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = {
                        pastThreshold = false
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
                    val next = (offsetY.value + dy).coerceAtMost(0f)
                    // Короткий отклик, когда карточку оттянули достаточно, чтобы она закрылась при отпускании
                    val past = next < -dismissPx
                    if (past != pastThreshold) {
                        pastThreshold = past
                        haptic.performHapticFeedback(if (past) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentFrequentTick)
                    }
                    scope.launch { offsetY.snapTo(next) }
                }
            },
    ) {
        // Рамка: выбранная вкладка в цвете акцента, остальные в тоне поверхности. Нажатая карточка чуть проседает
        val frame by animateColorAsState(
            if (selected) cs.primary else cs.surfaceContainerHigh,
            MaterialTheme.motionScheme.defaultEffectsSpec<Color>(),
            label = "tabFrame",
        )
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val press by animateFloatAsState(if (pressed) 0.97f else 1f, MaterialTheme.motionScheme.fastSpatialSpec<Float>(), label = "tabPress")
        val inner = RoundedCornerShape(if (compact) 23.dp else 30.dp)
        val closeCard: () -> Unit = {
            // Закрытие кнопкой выглядит так же, как смахивание: карточка улетает вверх, потом вкладка закрывается
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            scope.launch {
                offsetY.animateTo(-dismissPx * 5, tween(160))
                onClose()
            }
        }
        Surface(
            shape = shape,
            color = frame,
            shadowElevation = 0.dp,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = press; scaleY = press }
                .clip(shape)
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    onClick = { onClick(cardRect, shownThumb) },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                ),
        ) {
            Box(Modifier.fillMaxSize().padding(if (compact) 5.dp else 6.dp).clip(inner).background(cs.surfaceContainerLowest)) {
                // Свежий снимок из памяти, иначе сохранённый на диске (после перезапуска)
                // В сетке хватает маленькой копии: она декодируется быстрее и занимает меньше памяти
                val fromDisk by produceState<ImageBitmap?>(TabThumbs.peek(tab.url, compact), tab.url, tab.thumbnail, compact) {
                    value = if (tab.thumbnail == null && !tab.isPrivate && !tab.home && tab.url.isNotBlank()) TabThumbs.load(context, tab.url, small = compact) else null
                }
                val thumb = tab.thumbnail ?: fromDisk
                LaunchedEffect(thumb) { shownThumb = thumb }
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
                } else if (tab.home && !tab.isPrivate) {
                    // Начальная страница рисуется по-настоящему: плитки, поиск и обои, как на экране
                    HomeTabPreview(Modifier.fillMaxSize())
                } else {
                    TabPlaceholder(tab, compact)
                }

                // Метка группы слева сверху, кнопка закрытия справа сверху
                if (group != null) {
                    GroupMark(group, compact, Modifier.align(Alignment.TopStart).padding(if (compact) 8.dp else 12.dp))
                }
                Surface(
                    onClick = closeCard,
                    shape = CircleShape,
                    color = cs.surface.copy(alpha = 0.88f),
                    contentColor = cs.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(if (compact) 6.dp else 10.dp)
                        .size(if (compact) 30.dp else 38.dp)
                        .semantics { contentDescription = "Закрыть вкладку ${tab.label()}" },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(HripsIcons.Close, null, Modifier.size(if (compact) 16.dp else 20.dp))
                    }
                }

                // Подпись плашкой снизу: значок, название, адрес. Лежит поверх снимка, поэтому читается на любой странице
                Surface(
                    shape = RoundedCornerShape(if (compact) 18.dp else 24.dp),
                    color = cs.surfaceContainerHigh.copy(alpha = 0.94f),
                    contentColor = cs.onSurface,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(if (compact) 6.dp else 8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TabIcon(tab, if (compact) 20.dp else 30.dp)
                        Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tab.label(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                            )
                            if (!compact) {
                                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // Значок появляется сверху, когда карточку оттянули достаточно, чтобы она закрылась
        if (!compact) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .size(44.dp)
                    .graphicsLayer {
                        val k = (-offsetY.value / dismissPx).coerceIn(0f, 1f)
                        alpha = k
                        scaleX = 0.6f + 0.4f * k
                        scaleY = 0.6f + 0.4f * k
                    }
                    .background(cs.error, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(HripsIcons.Close, null, Modifier.size(22.dp), tint = cs.onError) }
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
                onClick = { menu = false; closeCard() },
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
            FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = onNew) { Text(if (priv) "Приватная вкладка" else "Новая вкладка") }
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
            // Печенье живое: при нажатии сжимается, после нажатия делает четверть оборота (плюс при этом остаётся плюсом)
            val fabSource = remember { MutableInteractionSource() }
            val fabPressed by fabSource.collectIsPressedAsState()
            var fabTurns by remember { mutableIntStateOf(0) }
            val fabHaptic = LocalHapticFeedback.current
            val fabRotation by animateFloatAsState(fabTurns * 90f, MaterialTheme.motionScheme.slowSpatialSpec<Float>(), label = "fabTurn")
            val fabScale by animateFloatAsState(if (fabPressed) 0.88f else 1f, MaterialTheme.motionScheme.fastSpatialSpec<Float>(), label = "fabPress")
            FloatingActionButton(
                onClick = { fabHaptic.performHapticFeedback(HapticFeedbackType.Confirm); fabTurns++; onNew() },
                shape = MaterialShapes.Cookie9Sided.toShape(),
                containerColor = fabBg,
                contentColor = fabFg,
                interactionSource = fabSource,
                modifier = Modifier.size(68.dp).graphicsLayer { rotationZ = fabRotation; scaleX = fabScale; scaleY = fabScale },
            ) {
                Icon(HripsIcons.Add, if (privatePage) "Новая приватная вкладка" else "Новая вкладка", Modifier.size(30.dp))
            }
        }
    }
}
