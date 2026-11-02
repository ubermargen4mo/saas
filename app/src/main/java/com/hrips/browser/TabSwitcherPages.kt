@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hrips.browser

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.flow.first

@Composable
internal fun TabGridPage(
    list: List<Pair<Int, Tab>>,
    current: Int,
    env: SwitcherEnv,
    onOpen: (Int) -> Unit,
    onClose: (Tab) -> Unit,
    onSettled: () -> Unit,
    barReserve: Dp,
    aspect: Float,
    query: String = "",
) {
    val start = (list.indexOfFirst { it.first == current } - 2).coerceAtLeast(0)
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = start)
    PrefetchVisibleTabThumbnails(list, small = true, stateKey = state) {
        state.layoutInfo.visibleItemsInfo.map { it.index }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { if (!it) onSettled() }
    }
    DragAutoScroll(
        env = env,
        span = { id -> state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.let { it.offset.y.toFloat() to (it.offset.y + it.size.height).toFloat() } },
        viewport = { state.layoutInfo.viewportStartOffset.toFloat() to (state.layoutInfo.viewportEndOffset - state.layoutInfo.afterContentPadding).toFloat() },
        scroll = { dy -> state.scrollBy(dy) },
        onMoved = { id -> reorderInGrid(state, env, id) },
    )
    val swipeClose by remember(state) { derivedStateOf { !state.canScrollForward } }
    val phone = LocalConfiguration.current.screenWidthDp < 600
    val cardAspect = aspect.coerceIn(0.62f, 0.9f)
    val single = list.size == 1
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val titleH = 34.dp
        val availH = maxHeight - 8.dp - titleH - barReserve - 16.dp
        val singleW = minOf(maxWidth * 0.66f, availH * cardAspect).coerceAtLeast(120.dp)
        val sidePad = if (single) ((maxWidth - singleW) / 2).coerceAtLeast(16.dp) else 16.dp
    LazyVerticalGrid(
        state = state,
        columns = if (single) GridCells.Fixed(1) else if (phone) GridCells.Fixed(2) else GridCells.Adaptive(minSize = 180.dp),
        contentPadding = PaddingValues(start = sidePad, end = sidePad, top = 8.dp, bottom = barReserve + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(list, key = { tabKey(it.second) }, contentType = { "tab" }) { (i, t) ->
            val held = env.isHeld(t.id)
            GridSwipeUpToClose(
                enabled = swipeClose && !env.selecting,
                onClose = { onClose(t) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (held) Modifier.zIndex(1f) else Modifier.animateItem(fadeInSpec = null, placementSpec = tween<IntOffset>(MOVE_MS, easing = FastOutSlowInEasing), fadeOutSpec = tween<Float>(FADE_OUT_MS)))
                    .graphicsLayer {
                        if (held) {
                            translationX = env.dragX
                            translationY = env.dragY
                            scaleX = 1.04f
                            scaleY = 1.04f
                        }
                    },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    TabCard(
                        tab = t,
                        selected = i == current,
                        env = env,
                        onOpen = { onOpen(i) },
                        onClose = { onClose(t) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(cardAspect),
                        clean = true,
                        corner = 20.dp,
                        onMoved = { reorderInGrid(state, env, t.id) },
                    )
                    TabTitleRow(t, Modifier.fillMaxWidth().height(titleH), query, pinned = env.browser.store.isPinned(t.id))
                }
            }
        }
    }
    }
}

@Composable
internal fun TabListPage(
    list: List<Pair<Int, Tab>>,
    current: Int,
    env: SwitcherEnv,
    onOpen: (Int) -> Unit,
    onClose: (Tab) -> Unit,
    onSettled: () -> Unit,
    barReserve: Dp,
    query: String = "",
) {
    val start = (list.indexOfFirst { it.first == current } - 1).coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    PrefetchVisibleTabThumbnails(list, small = true, stateKey = state) {
        state.layoutInfo.visibleItemsInfo.map { it.index }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { if (!it) onSettled() }
    }
    DragAutoScroll(
        env = env,
        span = { id -> state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.let { it.offset.toFloat() to (it.offset + it.size).toFloat() } },
        viewport = { state.layoutInfo.viewportStartOffset.toFloat() to (state.layoutInfo.viewportEndOffset - state.layoutInfo.afterContentPadding).toFloat() },
        scroll = { dy -> state.scrollBy(dy) },
        onMoved = { id -> reorderInList(state, env, id) },
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = barReserve + 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxHeight(),
        ) {
            items(list, key = { tabKey(it.second) }, contentType = { "tab" }) { (i, t) ->
                val held = env.isHeld(t.id)
                SwipeToClose(
                    onClose = { onClose(t) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (held) Modifier.zIndex(1f) else Modifier.animateItem(fadeInSpec = null, placementSpec = tween<IntOffset>(MOVE_MS, easing = FastOutSlowInEasing), fadeOutSpec = tween<Float>(FADE_OUT_MS)))
                        .graphicsLayer {
                            if (held) {
                                translationY = env.dragY
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        },
                ) {
                    TabRow(
                        tab = t, selected = i == current, env = env, onOpen = { onOpen(i) }, onClose = { onClose(t) }, query = query,
                        onMoved = { reorderInList(state, env, t.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun TabCardsPage(
    list: List<Pair<Int, Tab>>,
    current: Int,
    env: SwitcherEnv,
    onOpen: (Int) -> Unit,
    onClose: (Tab) -> Unit,
    onSettled: () -> Unit,
    aspect: Float,
    barReserve: Dp,
    query: String = "",
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = list.indexOfFirst { it.first == current }.coerceAtLeast(0))
    val fling = rememberSnapFlingBehavior(state)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    PrefetchVisibleTabThumbnails(list, small = false, stateKey = state) {
        state.layoutInfo.visibleItemsInfo.map { it.index }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { if (!it) onSettled() }
    }
    val stride = remember { mutableFloatStateOf(1f) }
    Column(Modifier.fillMaxSize().padding(bottom = barReserve)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val titleH = 40.dp
            val spacing = 14.dp
            val maxCh = (maxHeight - titleH - 8.dp).coerceAtLeast(120.dp)
            val cw = minOf(maxCh * aspect, maxWidth * 0.78f)
            val ch = cw / aspect
            val side = (maxWidth - cw) / 2
            SideEffect { stride.floatValue = with(density) { (cw + spacing).toPx() } }
            LazyRow(
                state = state,
                flingBehavior = fling,
                contentPadding = PaddingValues(horizontal = side),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(list, key = { _, item -> tabKey(item.second) }, contentType = { _, _ -> "tab" }) { idx, (i, t) ->
                    SwipeUpToClose(
                        onClose = { onClose(t) },
                        modifier = Modifier
                            .size(cw, ch + titleH)
                            .animateItem(fadeInSpec = null, placementSpec = tween<IntOffset>(MOVE_MS, easing = FastOutSlowInEasing), fadeOutSpec = null)
                            .graphicsLayer {
                                val st = stride.floatValue
                                val d = abs(idx - (state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / st)).coerceIn(0f, 1f)
                                val sc = 1f - 0.07f * d
                                scaleX = sc
                                scaleY = sc
                            },
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            TabCard(
                                tab = t,
                                selected = i == current,
                                env = env,
                                onOpen = { onOpen(i) },
                                onClose = { onClose(t) },
                                modifier = Modifier.fillMaxWidth().height(ch),
                                big = true,
                            )
                            TabTitleRow(t, Modifier.fillMaxWidth().height(titleH), query, pinned = env.browser.store.isPinned(t.id))
                        }
                    }
                }
            }
        }
        if (list.size > 1) {
            TabStrip(
                list = list,
                state = state,
                mainStride = { stride.floatValue },
                aspect = aspect,
                onJump = { idx -> scope.launch { state.animateScrollToItem(idx) } },
            )
        }
    }
}

@Composable
private fun TabTitleRow(tab: Tab, modifier: Modifier = Modifier, query: String = "", pinned: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        TabIcon(tab, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            tab.label(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (pinned) {
            Spacer(Modifier.width(6.dp))
            Icon(HripsIcons.TabPin, "Закреплена", Modifier.size(14.dp), tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun TabStrip(
    list: List<Pair<Int, Tab>>,
    state: LazyListState,
    mainStride: () -> Float,
    aspect: Float,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary
    val itemH = 52.dp
    val itemW = (itemH * aspect).coerceIn(26.dp, 88.dp)
    val itemWPx = with(density) { itemW.toPx() }
    val stridePx = with(density) { (itemW + 8.dp).toPx() }
    val cornerPx = with(density) { 8.dp.toPx() }
    val strokePx = with(density) { 2.dp.toPx() }
    val last = list.lastIndex
    val pos: () -> Float = {
        val s = mainStride()
        if (s <= 1f) 0f else (state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / s).coerceIn(0f, last.toFloat())
    }
    val center by remember(state, list.size) { derivedStateOf { pos().roundToInt().coerceIn(0, last) } }
    LaunchedEffect(center, list) {
        val ids = ((center - STRIP_REACH)..(center + STRIP_REACH))
            .filter { it in 0..last }
            .mapNotNull { list[it].second.takeIf { t -> !t.isPrivate && !t.home }?.id }
        TabThumbs.prefetch(context, ids, small = true)
    }
    var width by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .fillMaxWidth()
            .height(itemH + 20.dp)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(state, list.size) {
                val settle: () -> Unit = {
                    scope.launch { state.animateScrollToItem(pos().roundToInt().coerceIn(0, last)) }
                }
                detectHorizontalDragGestures(
                    onDragEnd = settle,
                    onDragCancel = settle,
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        state.dispatchRawDelta(-dx * (mainStride() / stridePx))
                    },
                )
            },
    ) {
        for (idx in (center - STRIP_REACH)..(center + STRIP_REACH)) {
            if (idx < 0 || idx > last) continue
            val tab = list[idx].second
            key(tab.id) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .size(itemW, itemH)
                        .graphicsLayer {
                            val d = idx - pos()
                            translationX = width / 2f - itemWPx / 2f + d * stridePx
                            val near = (1f - abs(d)).coerceIn(0f, 1f)
                            val sc = 1f + 0.2f * near
                            scaleX = sc
                            scaleY = sc
                            alpha = 0.6f + 0.4f * near
                            shape = RoundedCornerShape(8.dp)
                            clip = true
                        }
                        .drawWithContent {
                            drawContent()
                            val near = (1f - abs(idx - pos())).coerceIn(0f, 1f)
                            if (near > 0.02f) {
                                drawRoundRect(primary.copy(alpha = near), cornerRadius = CornerRadius(cornerPx), style = Stroke(strokePx))
                            }
                        }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onJump(idx) },
                ) {
                    TabPreview(tab, mini = true, small = true, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
