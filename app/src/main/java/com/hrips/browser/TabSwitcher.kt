@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hrips.browser

import androidx.compose.material3.toShape
import androidx.compose.material3.MaterialShapes
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as UiRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private const val CAPTURE_WIDTH = 720

// Незавершённые захваты по id вкладки (только главный поток). Повторный запрос не запускает второй захват, а ждёт первый.
private val pendingCaptures = HashMap<String, MutableList<() -> Unit>>()

/**
 * Снимок страницы для карточки вкладки.
 *
 * Как в проекте-образце: захват один на вкладку, результат принимается только если вкладка за это время не ушла
 * на другой адрес, а кадр не пустой и не чёрный. [acceptAfterDeparture] - захват начат прямо перед уходом со вкладки:
 * к моменту ответа экран уже показывает другую, и это нормально.
 */
fun captureThumbnail(
    activity: Activity?,
    view: View?,
    tab: Tab,
    allowPixelCopy: Boolean = true,
    timeoutMs: Long = 1000,
    acceptAfterDeparture: Boolean = false,
    done: () -> Unit,
) {
    val gv = view as? org.mozilla.geckoview.GeckoView
    if (activity == null || gv == null || tab.home ||
        gv.width == 0 || gv.height == 0 || !gv.isShown ||
        gv.session !== tab.session
    ) {
        done(); return
    }
    if ((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
        done(); return
    }
    pendingCaptures[tab.id]?.let { it.add(done); return }
    val waiters = mutableListOf(done)
    pendingCaptures[tab.id] = waiters

    val main = Handler(Looper.getMainLooper())
    val startUrl = tab.url
    var finished = false
    var timeout: Runnable? = null
    val finish: () -> Unit = {
        if (!finished) {
            finished = true
            timeout?.let { main.removeCallbacks(it) }
            if (pendingCaptures[tab.id] === waiters) pendingCaptures.remove(tab.id)
            waiters.toList().forEach { waiter -> runCatching { waiter() } }
        }
    }
    timeout = Runnable { finish() }.also { main.postDelayed(it, timeoutMs) }

    fun stillValid(): Boolean =
        !finished && tab.url == startUrl && (acceptAfterDeparture || gv.session === tab.session)

    fun store(bmp: Bitmap): Boolean {
        if (!stillValid() || TabThumbs.isLikelyFailed(bmp)) return false
        if (!tab.updateThumbnail(bmp.asImageBitmap())) return false
        if (!tab.isPrivate && tab.url.isNotBlank()) TabThumbs.saveAsync(activity.applicationContext, tab.id, bmp)
        return true
    }

    fun scaleAndStore(full: Bitmap) {
        val w = CAPTURE_WIDTH
        val ran = AppExecutors.tryExecute {
            val ratioH = (w * full.height.toFloat() / full.width).toInt().coerceAtLeast(1)
            val h = ratioH.coerceAtMost(w * 3)
            val scaled = try {
                when {
                    full.width <= w -> full
                    else -> {
                        val sc = Bitmap.createScaledBitmap(full, w, ratioH, true)
                        if (h < ratioH) {
                            try { Bitmap.createBitmap(sc, 0, 0, w, h) } finally { sc.recycle() }
                        } else sc
                    }
                }
            } catch (e: Exception) {
                if (!full.isRecycled) full.recycle()
                main.post { finish() }
                throw e
            }
            if (scaled !== full && !full.isRecycled) full.recycle()
            main.post {
                if (!store(scaled) && !scaled.isRecycled) scaled.recycle()
                finish()
            }
        }
        if (!ran) {
            if (!full.isRecycled) full.recycle()
            finish()
        }
    }

    fun viaPixelCopy() {
        if (finished || !allowPixelCopy || Build.VERSION.SDK_INT < 26) { finish(); return }
        try {
            val loc = IntArray(2)
            gv.getLocationInWindow(loc)
            val w = CAPTURE_WIDTH
            val h = (w * gv.height.toFloat() / gv.width).toInt().coerceIn(1, w * 3)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val rect = Rect(loc[0], loc[1], loc[0] + gv.width, loc[1] + gv.height)
            PixelCopy.request(activity.window, rect, bmp, { result ->
                val stored = result == PixelCopy.SUCCESS && store(bmp)
                if (!stored && !bmp.isRecycled) bmp.recycle()
                finish()
            }, main)
        } catch (_: Exception) {
            finish()
        }
    }

    try {
        gv.capturePixels().accept({ full ->
            main.post {
                if (finished) {
                    if (full != null && !full.isRecycled) full.recycle()
                    return@post
                }
                if (full == null || full.width <= 0 || full.height <= 0) {
                    viaPixelCopy()
                } else {
                    scaleAndStore(full)
                }
            }
        }, {
            main.post { if (!finished) viaPixelCopy() }
        })
    } catch (_: Exception) {
        main.post { if (!finished) viaPixelCopy() }
    }
}

// ---------------------------------------------------------------------------------------------
// Переключатель вкладок. Намеренно простой: плоский список или сетка, никаких 3D, пружин,
// живых композиций стартовой страницы и анимаций размера. Все движения - короткие tween.
// ---------------------------------------------------------------------------------------------

private const val FADE_IN_MS = 130
private const val FADE_OUT_MS = 90
private const val MOVE_MS = 180
private const val SWIPE_MS = 150

private fun tabKey(t: Tab) = t.id

private fun Tab.matches(q: String) = q.isEmpty() || matchesQuery(label(), url, q)

private fun tabSubtitle(tab: Tab): String = when {
    tab.isPrivate && tab.home -> "История не сохраняется"
    tab.home -> "Новая вкладка"
    else -> runCatching { Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.") ?: tab.url
}

private class CoordsHolder {
    var c: LayoutCoordinates? = null
    fun rect(): UiRect = c?.takeIf { it.isAttached }?.boundsInRoot() ?: UiRect.Zero
}

private class SwitcherEnv {
    var currentHolder: CoordsHolder? = null
}

@Composable
fun TabSwitcher(browser: Browser, onClose: () -> Unit, onHistory: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var view by remember { mutableIntStateOf(browser.store.tabsView) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    var showClosed by remember { mutableStateOf(false) }
    var closeTick by remember { mutableIntStateOf(0) }
    val closeWithUndo: (Int) -> Unit = { i ->
        val before = browser.closedTabs.firstOrNull()
        browser.closeTab(i, deferEngine = true)
        closeTick++
        if (browser.closedTabs.firstOrNull() !== before) {
            Notices.show("Вкладка закрыта", "Вернуть") { browser.reopenLast() }
        }
    }

    val origins = LocalOrigins.current
    val current = browser.currentIndex
    val q = query.trim()
    val qState = rememberUpdatedState(q)

    val normalCount by remember(browser) { derivedStateOf { browser.tabs.count { !it.isPrivate } } }
    val privateCount by remember(browser) { derivedStateOf { browser.tabs.size - normalCount } }

    val normalList by remember(browser) {
        derivedStateOf {
            val qq = qState.value
            browser.tabs.mapIndexedNotNull { i, t -> if (!t.isPrivate && t.matches(qq)) i to t else null }
        }
    }
    val privList by remember(browser) {
        derivedStateOf {
            val qq = qState.value
            browser.tabs.mapIndexedNotNull { i, t -> if (t.isPrivate && t.matches(qq)) i to t else null }
        }
    }

    var mode by remember { mutableIntStateOf(if (browser.current.isPrivate) 1 else 0) }
    val env = remember { SwitcherEnv() }

    val modeP = animateFloatAsState(mode.toFloat(), tween(MOVE_MS, easing = FastOutSlowInEasing), label = "mode")
    val progress: () -> Float = { modeP.value.coerceIn(0f, 1f) }
    // Смена режима боковым свайпом, когда карточки уже доехали до края: LazyRow забирает жест себе,
    // родительский detectHorizontalDragGestures его не видит, поэтому ловим остаток прокрутки.
    val modeNow by rememberUpdatedState(mode)
    val modeSwipePx = with(LocalDensity.current) { 56.dp.toPx() }
    val modeSwipe = remember(modeSwipePx) {
        object : NestedScrollConnection {
            var total = 0f
            var fired = false
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (fired || source != NestedScrollSource.UserInput || available.x == 0f) return Offset.Zero
                total += available.x
                if (modeNow == 0 && total < -modeSwipePx) { fired = true; mode = 1 }
                else if (modeNow == 1 && total > modeSwipePx) { fired = true; mode = 0 }
                else if ((modeNow == 0 && total > 0f) || (modeNow == 1 && total < 0f)) total = 0f
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                total = 0f; fired = false
                return Velocity.Zero
            }
        }
    }
    val bgNormal = cs.surface
    val bgPrivate = lerp(cs.surface, cs.tertiaryContainer, 0.55f)

    LaunchedEffect(closeTick) {
        if (closeTick == 0) return@LaunchedEffect
        delay(700)
        browser.flushPendingClose()
    }
    DisposableEffect(browser) { onDispose { browser.flushPendingClose() } }

    val reportRect = {
        env.currentHolder?.c?.takeIf { it.isAttached }?.let { origins.currentCardRect = it.boundsInRoot() }
        Unit
    }
    LaunchedEffect(current, view, mode) {
        repeat(2) { withFrameNanos { } }
        reportRect()
    }

    val onNew: (Boolean) -> Unit = { priv ->
        browser.newTab(incognito = priv, returnToPrevious = false)
        onClose()
    }
    val onOpen: (Int) -> Unit = { i ->
        browser.currentIndex = i
        origins.expandStamp++
        onClose()
    }
    val onCloseTab: (Tab) -> Unit = { tab ->
        val i = browser.tabs.indexOf(tab)
        if (i >= 0) closeWithUndo(i)
    }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Box(Modifier.fillMaxSize().drawBehind { drawRect(lerp(bgNormal, bgPrivate, progress())) })

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SwitcherHeader(
                searching = searching,
                onSearching = { searching = it },
                query = query,
                onQuery = { query = it },
                focus = focus,
                progress = progress,
                normal = normalCount,
                priv = privateCount,
                onSelect = { mode = it },
            )

            AnimatedContent(
                targetState = mode,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Карточка, смахнутая вверх, уходит под пилюлю режимов, а не поверх неё.
                    .clipToBounds()
                    .nestedScroll(modeSwipe)
                    .pointerInput(mode) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -56.dp.toPx() && mode == 0) mode = 1
                                else if (total > 56.dp.toPx() && mode == 1) mode = 0
                            },
                            onHorizontalDrag = { _, dx -> total += dx },
                        )
                    },
                transitionSpec = {
                    fadeIn(tween(FADE_IN_MS)) togetherWith fadeOut(tween(FADE_OUT_MS)) using
                        SizeTransform(clip = false) { _, _ -> snap() }
                },
                label = "tabsMode",
            ) { m ->
                val list = if (m == 1) privList else normalList
                if (list.isEmpty()) {
                    EmptyTabs(m == 1, searching = q.isNotEmpty(), onNew = { onNew(m == 1) })
                } else when (view) {
                    1 -> TabGridPage(list, current, env, onOpen, onCloseTab, onSettled = reportRect)
                    2 -> TabCardsPage(list, current, env, onOpen, onCloseTab, onSettled = reportRect)
                    else -> TabListPage(list, current, env, onOpen, onCloseTab, onSettled = reportRect)
                }
            }
        }

        SwitcherBar(
            view = view,
            count = browser.tabs.size,
            onCycleView = {
                view = (view + 1) % 3
                browser.store.updateTabsView(view)
            },
            onHistory = onHistory,
            onNew = { onNew(mode == 1) },
            onDone = onClose,
            privatePage = mode == 1,
            onCloseAll = {
                closeTick++
                if (mode == 1) {
                    browser.closePrivateTabs(deferEngine = true)
                } else {
                    val toClose = browser.tabs.count { !it.isPrivate }
                    browser.tabs.indices.reversed().filter { !browser.tabs[it].isPrivate }.forEach { browser.closeTab(it, deferEngine = true) }
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

        if (showClosed) {
            ClosedTabsSheet(browser, onDismiss = { showClosed = false }, onRestored = { showClosed = false; onClose() })
        }
    }
}

@Composable
private fun PrefetchVisibleTabThumbnails(
    list: List<Pair<Int, Tab>>,
    small: Boolean,
    stateKey: Any,
    visibleIndices: () -> List<Int>,
) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(stateKey, list, small) {
        snapshotFlow {
            val visible = visibleIndices().distinct().sorted()
            if (visible.isEmpty()) return@snapshotFlow emptyList()

            val first = visible.first()
            val last = visible.last()
            val nearby = ((first - 3).coerceAtLeast(0)..(last + 3).coerceAtMost(list.lastIndex))
                .filterNot(visible::contains)
                .sortedBy { minOf(abs(it - first), abs(it - last)) }
            (visible + nearby).distinct().mapNotNull { index ->
                list.getOrNull(index)?.second?.takeIf { !it.isPrivate && !it.home }?.id
            }
        }.distinctUntilChanged().collectLatest { ids ->
            // Пустая область тоже отменяет старую очередь, чтобы не грузить уже невидимые карточки.
            TabThumbs.prefetch(context, ids, small)
        }
    }
}

@Composable
private fun TabGridPage(
    list: List<Pair<Int, Tab>>,
    current: Int,
    env: SwitcherEnv,
    onOpen: (Int) -> Unit,
    onClose: (Tab) -> Unit,
    onSettled: () -> Unit,
) {
    val start = (list.indexOfFirst { it.first == current } - 2).coerceAtLeast(0)
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = start)
    PrefetchVisibleTabThumbnails(list, small = true, stateKey = state) {
        state.layoutInfo.visibleItemsInfo.map { it.index }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { if (!it) onSettled() }
    }
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(list, key = { tabKey(it.second) }, contentType = { "tab" }) { (i, t) ->
            TabCard(
                tab = t,
                selected = i == current,
                env = env,
                onOpen = { onOpen(i) },
                onClose = { onClose(t) },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .animateItem(fadeInSpec = null, placementSpec = tween<IntOffset>(MOVE_MS, easing = FastOutSlowInEasing), fadeOutSpec = tween<Float>(FADE_OUT_MS)),
            )
        }
    }
}

@Composable
private fun TabListPage(
    list: List<Pair<Int, Tab>>,
    current: Int,
    env: SwitcherEnv,
    onOpen: (Int) -> Unit,
    onClose: (Tab) -> Unit,
    onSettled: () -> Unit,
) {
    val start = (list.indexOfFirst { it.first == current } - 1).coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    PrefetchVisibleTabThumbnails(list, small = true, stateKey = state) {
        state.layoutInfo.visibleItemsInfo.map { it.index }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { if (!it) onSettled() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxHeight(),
        ) {
            items(list, key = { tabKey(it.second) }, contentType = { "tab" }) { (i, t) ->
                SwipeToClose(
                    onClose = { onClose(t) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(fadeInSpec = null, placementSpec = tween<IntOffset>(MOVE_MS, easing = FastOutSlowInEasing), fadeOutSpec = tween<Float>(FADE_OUT_MS)),
                ) {
                    TabRow(tab = t, selected = i == current, env = env, onOpen = { onOpen(i) }, onClose = { onClose(t) })
                }
            }
        }
    }
}

/**
 * Крупные карточки в один ряд. Листаются горизонтально с примагничиванием, смахивание карточки вверх закрывает вкладку.
 * Никакого наклона и масштабирования соседей: карточка двигается только по Y и гаснет, всё в graphicsLayer.
 */
@Composable
private fun TabCardsPage(
    list: List<Pair<Int, Tab>>,
    current: Int,
    env: SwitcherEnv,
    onOpen: (Int) -> Unit,
    onClose: (Tab) -> Unit,
    onSettled: () -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = list.indexOfFirst { it.first == current }.coerceAtLeast(0))
    val fling = rememberSnapFlingBehavior(state)
    PrefetchVisibleTabThumbnails(list, small = false, stateKey = state) {
        state.layoutInfo.visibleItemsInfo.map { it.index }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { if (!it) onSettled() }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = 104.dp)) {
        val cw = minOf(maxWidth * 0.78f, 380.dp)
        val ch = minOf(cw * (if (maxWidth > 600.dp) 1.0f else 1.4f), maxHeight - 16.dp)
        val side = (maxWidth - cw) / 2
        LazyRow(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list, key = { tabKey(it.second) }, contentType = { "tab" }) { (i, t) ->
                SwipeUpToClose(
                    onClose = { onClose(t) },
                    modifier = Modifier
                        .size(cw, ch)
                        .animateItem(fadeInSpec = null, placementSpec = tween<IntOffset>(MOVE_MS, easing = FastOutSlowInEasing), fadeOutSpec = null),
                ) {
                    TabCard(
                        tab = t,
                        selected = i == current,
                        env = env,
                        onOpen = { onOpen(i) },
                        onClose = { onClose(t) },
                        modifier = Modifier.fillMaxSize(),
                        big = true,
                    )
                }
            }
        }
    }
}

/** Смахивание вверх. Палец ведёт карточку, бросок или проход за порог закрывает. Движение только через graphicsLayer. */
@Composable
private fun SwipeUpToClose(onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val closeNow by rememberUpdatedState(onClose)
    val dragState = rememberDraggableState { d -> offset = (offset + d).coerceAtMost(0f) }
    Box(
        modifier
            .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val from = offset
                    scope.launch {
                        if (from < -height * 0.22f || velocity < -1200f) {
                            animate(from, -height * 1.15f, 0f, tween(SWIPE_MS)) { v, _ -> offset = v }
                            runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                            closeNow()
                            delay(300)
                            offset = 0f
                        } else {
                            animate(from, 0f, 0f, tween(MOVE_MS, easing = FastOutSlowInEasing)) { v, _ -> offset = v }
                        }
                    }
                },
            )
            .graphicsLayer {
                translationY = offset
                alpha = 1f - 0.85f * (-offset / height).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) { content() }
}

/** Горизонтальный свайп закрывает строку. Движение идёт только через graphicsLayer (без перекомпозиций). */
@Composable
private fun SwipeToClose(onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val closeNow by rememberUpdatedState(onClose)
    Box(
        modifier
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val from = offset
                        val far = abs(from) > width * 0.35f
                        scope.launch {
                            if (far) {
                                animate(from, sign(from) * width, 0f, tween(SWIPE_MS)) { v, _ -> offset = v }
                                runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                                closeNow()
                                delay(300)
                                offset = 0f
                            } else {
                                animate(from, 0f, 0f, tween(MOVE_MS, easing = FastOutSlowInEasing)) { v, _ -> offset = v }
                            }
                        }
                    },
                    onDragCancel = { offset = 0f },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        offset += dx
                    },
                )
            }
            .graphicsLayer {
                translationX = offset
                alpha = 1f - 0.7f * (abs(offset) / width).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) { content() }
}

@Composable
private fun CloseTabButton(tab: Tab, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .size(44.dp)
            .clickable {
                runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                onClose()
            }
            .semantics { contentDescription = "Закрыть вкладку: ${tab.label()}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(cs.surface), contentAlignment = Alignment.Center) {
            Icon(HripsIcons.Close, null, Modifier.size(16.dp), tint = cs.onSurface)
        }
    }
}

@Composable
private fun TabCard(
    tab: Tab,
    selected: Boolean,
    env: SwitcherEnv,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val holder = remember { CoordsHolder() }
    if (selected) SideEffect { env.currentHolder = holder }
    Box(
        modifier
            .onPlaced { holder.c = it }
            .clip(HripsShapes.L)
            .background(if (selected) cs.primary else cs.surfaceContainerHigh)
            .semantics { this.selected = selected }
            .clickable(onClick = onOpen),
    ) {
        Box(Modifier.fillMaxSize().padding(4.dp).clip(HripsShapes.ML).background(cs.surfaceContainerLowest)) {
            TabPreview(tab, mini = false, small = !big, modifier = Modifier.fillMaxSize())
            CloseTabButton(tab, Modifier.align(Alignment.TopEnd), onClose)
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(6.dp)
                    .clip(HripsShapes.M)
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabIcon(tab, 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    tab.label(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabRow(
    tab: Tab,
    selected: Boolean,
    env: SwitcherEnv,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val holder = remember { CoordsHolder() }
    if (selected) SideEffect { env.currentHolder = holder }
    val titleColor = if (selected) cs.onPrimaryContainer else cs.onSurface
    val subColor = if (selected) cs.onPrimaryContainer.copy(alpha = 0.72f) else cs.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(HripsShapes.ML)
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .semantics { this.selected = selected }
            .clickable(onClick = onOpen)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabPreview(
            tab, mini = true, small = true,
            modifier = Modifier.size(64.dp).onPlaced { holder.c = it }.clip(HripsShapes.S),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tab.label(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = titleColor)
            Text(tabSubtitle(tab), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = subColor)
        }
        IconButton(onClick = onClose) {
            Icon(HripsIcons.Close, "Закрыть вкладку ${tab.label()}", tint = titleColor)
        }
    }
}

/** Превью вкладки: сохранённый снимок, иначе картинка страницы, иначе простая заглушка. */
@Composable
private fun TabPreview(tab: Tab, mini: Boolean, small: Boolean, modifier: Modifier = Modifier) {
    val thumbState = rememberTabThumb(tab, small)
    val thumb = thumbState.image
    val context = LocalContext.current.applicationContext
    val pageImageState = produceState<ImageBitmap?>(null, tab.url, thumbState.resolved, thumb == null, PageImages.enabled) {
        // Don't fetch a page image while its saved tab thumbnail is still being read from disk.
        value = if (thumbState.resolved && thumb == null && !tab.isPrivate && !tab.home && tab.url.isNotBlank()) {
            PageImages.load(context, tab.url)
        } else null
    }
    val pageImage = pageImageState.value
    Box(modifier) {
        if (thumb != null && !tab.home) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (pageImage != null && !tab.home) {
            Image(
                bitmap = pageImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (tab.home && !tab.isPrivate) {
            HomeMini(mini)
        } else {
            TabPlaceholder(tab, mini)
        }
    }
}

/** Уменьшенная копия настоящей главной (фон, логотип, строка поиска): рисуется мгновенно, без снимка и без заглушки. */
@Composable
private fun HomeMini(mini: Boolean) {
    val cs = MaterialTheme.colorScheme
    val wallpaper = (LocalContext.current.applicationContext as HripsApp).wallpaper.image
    Box(Modifier.fillMaxSize().background(cs.background)) {
        if (wallpaper != null) {
            Image(bitmap = wallpaper, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (mini) 6.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(if (mini) 0.12f else 0.18f))
            Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.logo_hrips),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.onBackground),
                modifier = Modifier.fillMaxWidth(0.62f).height(if (mini) 14.dp else 34.dp),
            )
            if (!mini) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(22.dp).clip(CircleShape).background(cs.surfaceContainerHigh))
                Spacer(Modifier.height(18.dp))
                repeat(2) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        repeat(4) { Box(Modifier.size(22.dp).clip(CircleShape).background(cs.surfaceContainerHigh)) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SwitcherHeader(
    searching: Boolean,
    onSearching: (Boolean) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    focus: FocusRequester,
    progress: () -> Float,
    normal: Int,
    priv: Int,
    onSelect: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AnimatedContent(
        targetState = searching,
        transitionSpec = {
            fadeIn(tween(FADE_IN_MS)) togetherWith fadeOut(tween(FADE_OUT_MS)) using
                SizeTransform(clip = false) { _, _ -> snap() }
        },
        label = "switcherHeader",
    ) { isSearching ->
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSearching) {
                Surface(shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(HripsIcons.Search, null, tint = cs.onSurfaceVariant)
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 16.dp)) {
                            if (query.isEmpty()) {
                                Text(
                                    "Поиск по вкладкам", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = onQuery,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                                cursorBrush = SolidColor(cs.primary),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                        IconButton(onClick = { onSearching(false); onQuery("") }) { Icon(HripsIcons.Close, "Закрыть поиск") }
                    }
                }
            } else {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ModeToggle(
                        progress = progress,
                        normal = normal,
                        priv = priv,
                        onSelect = onSelect,
                        modifier = Modifier.widthIn(max = HripsLayout.SegmentedMaxWidth).fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalIconButton(onClick = { onSearching(true) }, modifier = Modifier.size(56.dp)) {
                    Icon(HripsIcons.Search, "Поиск по вкладкам")
                }
            }
        }
    }
}

@Composable
private fun ModeToggle(progress: () -> Float, normal: Int, priv: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val progressState by rememberUpdatedState(progress)
    val p by remember { derivedStateOf { (progressState().coerceIn(0f, 1f) * 16f).roundToInt() / 16f } }
    Box(modifier.height(56.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth(0.5f)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = progress().coerceIn(0f, 1f) * size.width
                    shape = CircleShape
                    clip = true
                }
                .drawBehind { drawRect(lerp(cs.primary, cs.tertiary, progress().coerceIn(0f, 1f))) },
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

@Composable
private fun TabIcon(tab: Tab, size: Dp) {
    val cs = MaterialTheme.colorScheme
    when {
        tab.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(size * 0.8f), tint = cs.primary)
        tab.home -> Icon(HripsIcons.Home, null, Modifier.size(size * 0.8f), tint = cs.onSurfaceVariant)
        else -> TabSiteIcon(tab, size, shape = CircleShape)
    }
}

@Composable
private fun TabPlaceholder(tab: Tab, mini: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val (bg, fg) = when {
        tab.isPrivate -> cs.tertiaryContainer to cs.onTertiaryContainer
        tab.home -> cs.primaryContainer to cs.onPrimaryContainer
        else -> siteColors(tab.url, dark)
    }
    val icon = if (mini) 28.dp else 48.dp
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0.78f).compositeOver(cs.surfaceContainerLowest)))),
        contentAlignment = Alignment.Center,
    ) {
        if (mini) {
            TabGlyph(tab, icon, fg)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).background(fg.copy(alpha = 0.16f), MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                    TabGlyph(tab, icon, fg)
                }
                if (!tab.isPrivate) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        tab.label(), color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 36.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabGlyph(tab: Tab, size: Dp, tint: Color) {
    when {
        tab.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(size), tint = tint)
        tab.home -> Icon(HripsIcons.Home, null, Modifier.size(size), tint = tint)
        else -> TabSiteIcon(tab, size)
    }
}

private data class TabThumbResult(val image: ImageBitmap?, val resolved: Boolean)

@Composable
private fun rememberTabThumb(tab: Tab, small: Boolean): TabThumbResult {
    val context = LocalContext.current.applicationContext
    val activeImage = tab.thumbnail
    val cachedImage = TabThumbs.peek(tab.id, small)
    val initial = TabThumbResult(
        image = activeImage ?: cachedImage,
        resolved = activeImage != null || cachedImage != null || tab.isPrivate || tab.home,
    )
    val loaded by produceState(initial, tab.id, activeImage, small, tab.isPrivate, tab.home) {
        value = when {
            activeImage != null -> TabThumbResult(activeImage, resolved = true)
            tab.isPrivate || tab.home -> TabThumbResult(null, resolved = true)
            else -> TabThumbResult(TabThumbs.load(context, tab.id, small), resolved = true)
        }
    }
    val result = if (activeImage != null) TabThumbResult(activeImage, resolved = true) else loaded
    LaunchedEffect(result.image) {
        val bitmap = result.image ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            runCatching { bitmap.asAndroidBitmap().prepareToDraw() }
        }
    }
    return result
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

@Composable
private fun SwitcherBar(
    view: Int,
    count: Int,
    privatePage: Boolean,
    onCycleView: () -> Unit,
    onHistory: () -> Unit,
    onNew: () -> Unit,
    onDone: () -> Unit,
    onCloseAll: () -> Unit,
    closedCount: Int,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val fabBg by animateColorAsState(if (privatePage) cs.tertiary else cs.primary, tween(MOVE_MS), label = "fabBg")
    val fabFg by animateColorAsState(if (privatePage) cs.onTertiary else cs.onPrimary, tween(MOVE_MS), label = "fabFg")
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HripsIsland {
                BarButton(onClick = onCycleView) {
                    when (view) {
                        0 -> Icon(HripsIcons.Grid, "Показать сеткой")
                        1 -> Icon(HripsIcons.Layers, "Показать карточками")
                        else -> Icon(HripsIcons.Rows, "Показать списком")
                    }
                }
                BarButton(onClick = onHistory) { Icon(HripsIcons.History, "История") }
                TabCounterButton(count, onClick = onDone)
                Box {
                    BarButton(onClick = { menu = true }) { Icon(HripsIcons.MoreVert, "Ещё") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = HripsShapes.L) {
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
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onNew()
                },
                shape = MaterialShapes.Cookie9Sided.toShape(),
                containerColor = fabBg,
                contentColor = fabFg,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(HripsIcons.Add, if (privatePage) "Новая приватная вкладка" else "Новая вкладка", Modifier.size(26.dp))
            }
        }
    }
}
