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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Снимок страницы для карточки вкладки. Делается через PixelCopy, пока страница ещё на экране
 * (GeckoView рисует в SurfaceView, обычный drawToBitmap дал бы чёрный квадрат).
 * [done] вызывается всегда, с картинкой или без: после него можно открывать переключатель.
 */
fun captureThumbnail(activity: Activity?, view: View?, tab: Tab, done: () -> Unit) {
    if (activity == null || view == null || tab.home || Build.VERSION.SDK_INT < 26 ||
        view.width == 0 || view.height == 0 || !view.isShown ||
        (view as? org.mozilla.geckoview.GeckoView)?.session !== tab.session
    ) {
        done(); return
    }
    // Окно закрыто от скриншотов (приватная вкладка): PixelCopy вернул бы чёрный кадр
    if ((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
        done(); return
    }
    try {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val w = 480
        val h = (w * view.height.toFloat() / view.width).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        PixelCopy.request(activity.window, rect, bmp, { result ->
            if (result == PixelCopy.SUCCESS) {
                tab.thumbnail = bmp.asImageBitmap()
                // На диск (в фоне): после перезапуска карточка сразу с картинкой. Приватные вкладки не сохраняем
                val url = tab.url
                if (!tab.isPrivate && url.isNotBlank()) {
                    val app = activity.applicationContext
                    Thread { TabThumbs.save(app, url, bmp) }.start()
                }
            }
            done()
        }, Handler(Looper.getMainLooper()))
    } catch (e: Throwable) {
        done()
    }
}

private fun tabKey(t: Tab) = System.identityHashCode(t)

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
    val pager = rememberPagerState(initialPage = if (browser.current.isPrivate) 1 else 0) { 2 }
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
    val privTop = lerp(cs.primaryContainer, Color.Black, 0.6f)
    val normalTop = cs.primaryContainer
    val surface = cs.surface

    Box(
        Modifier
            .fillMaxSize()
            // Фон плавно темнеет по мере свайпа к приватной странице
            .drawBehind {
                val p = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                drawRect(
                    Brush.verticalGradient(
                        listOf(lerp(normalTop, privTop, p), lerp(surface, Color.Black, p * 0.55f)),
                    ),
                )
            }
            // Экран лежит поверх GeckoView: нажатия мимо карточек не должны попадать в сайт под ним
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Шапка: переключатель «Вкладки / Приватные» и поиск по вкладкам
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searching) {
                    Surface(shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(HripsIcons.Search, null, tint = cs.onSurfaceVariant)
                            Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp)) {
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
                        page = pager.currentPage,
                        normal = normalCount,
                        priv = privateCount,
                        onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                        modifier = Modifier.weight(1f).widthIn(max = 380.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    FilledTonalIconButton(onClick = { searching = true }, modifier = Modifier.size(52.dp)) {
                        Icon(HripsIcons.Search, "Поиск по вкладкам")
                    }
                }
            }

            if (!searching && pager.currentPage == 0 && browser.groups.isNotEmpty()) {
                GroupChips(
                    groups = browser.groups,
                    counts = browser.groups.associate { g -> g.id to all.count { it.group == g.id } },
                    total = normalCount,
                    selected = filter,
                    onSelect = { filter = it },
                    onEdit = { editGroup = it },
                )
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                val priv = page == 1
                val q = query.trim()
                val list = all.mapIndexedNotNull { i, t ->
                    if (t.isPrivate == priv && (priv || filter == null || t.group == filter) &&
                        (q.isEmpty() || t.label().contains(q, true) || t.url.contains(q, true))
                    ) i to t else null
                }
                val pick: (Int) -> Unit = { i -> browser.currentIndex = i; onClose() }
                val newTab: () -> Unit = { browser.newTab(incognito = priv); onClose() }
                when {
                    list.isEmpty() -> EmptyTabs(priv, searching = q.isNotEmpty(), onNew = newTab)
                    grid -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 170.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItems(list, key = { tabKey(it.second) }) { (i, t) ->
                            TabCard(
                                t, selected = i == current, compact = true, onClick = { pick(i) }, onClose = { closeWithUndo(i) },
                                group = browser.groupOf(t), groups = browser.groups,
                                onAssign = { g -> browser.setGroup(t, g) }, onNewGroup = { newGroupFor = t },
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.78f).animateItem(),
                            )
                        }
                    }
                    else -> BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = 104.dp)) {
                        val cardW = minOf(maxWidth * 0.76f, 400.dp)
                        val cardH = minOf(cardW * (if (maxWidth > 600.dp) 1.0f else 1.35f), maxHeight - 24.dp)
                        val state = rememberLazyListState()
                        val gapPx = with(LocalDensity.current) { 16.dp.toPx() }
                        LaunchedEffect(Unit) {
                            val idx = list.indexOfFirst { it.first == current }
                            if (idx >= 0) state.scrollToItem(idx)
                        }
                        LazyRow(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = (maxWidth - cardW) / 2),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            flingBehavior = rememberSnapFlingBehavior(state),
                        ) {
                            items(list, key = { tabKey(it.second) }) { (i, t) ->
                                val key = tabKey(t)
                                TabCard(
                                    t, selected = i == current, compact = false, onClick = { pick(i) }, onClose = { closeWithUndo(i) },
                                    group = browser.groupOf(t), groups = browser.groups,
                                    onAssign = { g -> browser.setGroup(t, g) }, onNewGroup = { newGroupFor = t },
                                    modifier = Modifier
                                        .width(cardW)
                                        .height(cardH)
                                        .animateItem()
                                        // Карточка в центре крупнее и ярче, соседние уменьшаются и бледнеют
                                        .graphicsLayer {
                                            val info = state.layoutInfo
                                            val item = info.visibleItemsInfo.firstOrNull { it.key == key }
                                            if (item != null) {
                                                val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                                val itemCenter = item.offset + item.size / 2f
                                                val d = (abs(itemCenter - viewportCenter) / (item.size + gapPx)).coerceIn(0f, 1f)
                                                val s = 1f - 0.1f * d
                                                scaleX = s
                                                scaleY = s
                                                alpha = 1f - 0.4f * d
                                            }
                                        },
                                )
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
            onNew = { browser.newTab(incognito = pager.currentPage == 1); onClose() },
            onDone = onClose,
            privatePage = pager.currentPage == 1,
            onCloseAll = {
                if (pager.currentPage == 1) browser.closePrivateTabs()
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

/**
 * Связанная группа из двух кнопок, как в Material 3 Expressive: выбранная становится «таблеткой»,
 * у невыбранной внутренний край остаётся с малым скруглением. Форма и цвет меняются пружиной.
 */
@Composable
private fun ModeToggle(page: Int, normal: Int, priv: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.height(52.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ModeSegment(HripsIcons.Grid, "Вкладки", normal, selected = page == 0, first = true, modifier = Modifier.weight(1f)) { onSelect(0) }
        ModeSegment(HripsIcons.Mask, "Приватные", priv, selected = page == 1, first = false, modifier = Modifier.weight(1f)) { onSelect(1) }
    }
}

@Composable
private fun ModeSegment(
    icon: ImageVector,
    label: String,
    count: Int,
    selected: Boolean,
    first: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val inner by animateDpAsState(if (selected) 26.dp else 8.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "inner")
    val container by animateColorAsState(if (selected) cs.primary else cs.surfaceContainerHigh, label = "segBg")
    val content by animateColorAsState(if (selected) cs.onPrimary else cs.onSurfaceVariant, label = "segFg")
    val shape = if (first) {
        RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = inner, bottomEnd = inner)
    } else {
        RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = 26.dp, bottomEnd = 26.dp)
    }
    Surface(onClick = onClick, shape = shape, color = container, contentColor = content, modifier = modifier.fillMaxHeight()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Surface(shape = CircleShape, color = content.copy(alpha = 0.18f), contentColor = content) {
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
            Surface(shape = CircleShape, color = cs.surfaceVariant, modifier = Modifier.size(size)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(tab.label().firstOrNull()?.uppercase() ?: "", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
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
                Icon(HripsIcons.Search, null, Modifier.size(40.dp), tint = cs.onSecondaryContainer.copy(alpha = 0.7f))
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
                selected -> BorderStroke(3.dp, cs.primary)
                group != null -> BorderStroke(2.dp, group.tint.copy(alpha = 0.85f))
                else -> null
            },
            shadowElevation = if (selected) 8.dp else 0.dp,
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
                        modifier = Modifier.size(if (compact) 32.dp else 38.dp),
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
                    val fromDisk by produceState<ImageBitmap?>(null, tab.url, tab.thumbnail) {
                        value = if (tab.thumbnail == null && !tab.isPrivate && !tab.home && tab.url.isNotBlank()) TabThumbs.load(context, tab.url) else null
                    }
                    val thumb = tab.thumbnail ?: fromDisk
                    if (thumb != null && !tab.home) {
                        Image(
                            bitmap = thumb,
                            contentDescription = "Превью страницы",
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
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
private fun EmptyTabs(priv: Boolean, searching: Boolean, onNew: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 104.dp),
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
                containerColor = cs.primary,
                contentColor = cs.onPrimary,
                modifier = Modifier.size(68.dp),
            ) {
                Icon(HripsIcons.Add, if (privatePage) "Новая приватная вкладка" else "Новая вкладка", Modifier.size(30.dp))
            }
        }
    }
}
