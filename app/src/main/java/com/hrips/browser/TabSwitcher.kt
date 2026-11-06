@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hrips.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as UiRect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.first

internal const val FADE_IN_MS = 130

internal const val FADE_OUT_MS = 90

internal const val MOVE_MS = 180

internal const val SWIPE_MS = 150

internal fun tabKey(t: Tab) = t.id

private fun Tab.matches(q: String) = q.isEmpty() || matchesQuery(label(), url, q)

internal fun tabSubtitle(tab: Tab): String = when {
    tab.isPrivate && tab.home -> "История не сохраняется"
    tab.home -> "Новая вкладка"
    else -> plainHost(tab.url) ?: tab.url
}

internal fun stage(p: Float, from: Float, to: Float): Float = ((p - from) / (to - from)).coerceIn(0f, 1f)

internal val SWITCHER_BAR_H = HripsLayout.BarHeight

internal val SWITCHER_TOP_H = HripsLayout.BarHeight

internal const val STRIP_REACH = 9

internal class CoordsHolder {
    var c: LayoutCoordinates? = null
    fun rect(): UiRect = c?.takeIf { it.isAttached }?.boundsInRoot() ?: UiRect.Zero
}

internal class SwitcherEnv(val browser: Browser) {
    var currentHolder: CoordsHolder? = null

    var menuTab by mutableStateOf<Tab?>(null)
    var menuRect by mutableStateOf(UiRect.Zero)

    var selecting by mutableStateOf(false)
    val picked = mutableStateListOf<String>()

    var draggingId by mutableStateOf<String?>(null)
    var settlingId by mutableStateOf<String?>(null)
    var dragX by mutableFloatStateOf(0f)
    var dragY by mutableFloatStateOf(0f)

    var swallowClick = false

    fun isHeld(id: String) = draggingId == id || settlingId == id

    fun togglePicked(id: String) {
        if (!picked.remove(id)) picked.add(id)
    }

    fun startSelecting(first: String) {
        picked.clear()
        picked.add(first)
        selecting = true
    }

    fun stopSelecting() {
        selecting = false
        picked.clear()
    }

    fun endDrag(id: String) {
        if (draggingId == id) {
            draggingId = null
            settlingId = id
        }
    }

    fun swap(id: String, targetId: String): Boolean {
        val store = browser.store
        if (store.isPinned(id) != store.isPinned(targetId)) return false
        val from = browser.tabs.indexOfFirst { it.id == id }
        val to = browser.tabs.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0) return false
        browser.moveTab(from, to)
        return true
    }
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

    val store = browser.store
    LaunchedEffect(Unit) {
        val t = browser.current
        if (!t.isPrivate) store.activeTabGroup = store.groupOf(t.id)
    }
    val groupId by remember { derivedStateOf { store.activeTabGroup.takeIf { id -> id.isEmpty() || store.tabGroups.any { it.id == id } } ?: "" } }
    val normalCount by remember(browser) { derivedStateOf { browser.tabs.count { !it.isPrivate && store.groupOf(it.id) == groupId } } }

    val normalList by remember(browser) {
        derivedStateOf {
            val qq = qState.value
            browser.tabs.mapIndexedNotNull { i, t -> if (!t.isPrivate && store.groupOf(t.id) == groupId && t.matches(qq)) i to t else null }
                .sortedBy { if (store.isPinned(it.second.id)) 0 else 1 }
        }
    }
    val privList by remember(browser) {
        derivedStateOf {
            val qq = qState.value
            browser.tabs.mapIndexedNotNull { i, t -> if (t.isPrivate && t.matches(qq)) i to t else null }
        }
    }

    var mode by remember { mutableIntStateOf(if (browser.current.isPrivate) 1 else 0) }
    val env = remember(browser) { SwitcherEnv(browser) }

    val modeP = animateFloatAsState(mode.toFloat(), tween(MOVE_MS, easing = FastOutSlowInEasing), label = "mode")
    val progress: () -> Float = { modeP.value.coerceIn(0f, 1f) }
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
        val t = browser.newTab(incognito = priv, returnToPrevious = false)
        if (!priv) store.assignTab(t.id, groupId)
        onClose()
    }
    val onOpen: (Int) -> Unit = { i ->
        if (env.swallowClick) {
            env.swallowClick = false
        } else if (env.selecting) {
            browser.tabs.getOrNull(i)?.let { env.togglePicked(it.id) }
        } else {
            browser.currentIndex = i
            origins.expandStamp++
            onClose()
        }
    }
    val onCloseTab: (Tab) -> Unit = { tab ->
        val i = browser.tabs.indexOf(tab)
        if (i >= 0) closeWithUndo(i)
    }

    val reveal = LocalRevealProgress.current
    val wide = isWideWindow()
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val statusPx = WindowInsets.statusBars.getTop(density)
    val aspect = remember(window, statusPx, wide) {
        val chrome = if (wide) with(density) { 112.dp.roundToPx() } else 0
        (window.width.toFloat() / (window.height - statusPx - chrome).coerceAtLeast(1)).coerceIn(0.35f, 2.2f)
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barReserve = navBottom + SWITCHER_BAR_H + HripsLayout.BarGap * 2
    val fromRect = remember { if (wide) null else origins.resolve("tabs") }
    val fromText = remember {
        val t = browser.current
        if (t.home) "Поиск" else t.siteHost() ?: plainHost(t.url) ?: t.url
    }
    LaunchedEffect(Unit) {
        snapshotFlow { reveal() >= 0.999f }.first { it }
        reportRect()
    }
    val closeAll: () -> Unit = {
        closeTick++
        if (mode == 1) {
            browser.closePrivateTabs(deferEngine = true)
        } else {
            val inGroup = { t: Tab -> !t.isPrivate && store.groupOf(t.id) == groupId }
            val toClose = browser.tabs.count(inGroup)
            browser.tabs.indices.reversed().filter { inGroup(browser.tabs[it]) }.forEach { browser.closeTab(it, deferEngine = true) }
            val undoable = minOf(toClose, browser.closedTabs.size)
            if (undoable > 0) {
                Notices.show("Закрыто вкладок: $toClose", "Вернуть") { repeat(undoable) { browser.reopenLast() } }
            }
        }
    }

    LaunchedEffect(env.settlingId) {
        val id = env.settlingId ?: return@LaunchedEffect
        val ax = Animatable(env.dragX)
        val ay = Animatable(env.dragY)
        coroutineScope {
            launch { ax.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { env.dragX = value } }
            launch { ay.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { env.dragY = value } }
        }
        if (env.settlingId == id) {
            env.settlingId = null
            env.dragX = 0f
            env.dragY = 0f
        }
    }

    val closeSelected: () -> Unit = {
        val ids = env.picked.toList()
        env.stopSelecting()
        if (ids.isNotEmpty()) {
            closeTick++
            val before = browser.closedTabs.size
            ids.forEach { id ->
                val i = browser.tabs.indexOfFirst { it.id == id }
                if (i >= 0) browser.closeTab(i, deferEngine = true)
            }
            val undoable = (browser.closedTabs.size - before).coerceAtLeast(0)
            if (undoable > 0) {
                Notices.show("Закрыто вкладок: ${ids.size}", "Вернуть") { repeat(undoable) { browser.reopenLast() } }
            } else {
                Notices.show("Закрыто вкладок: ${ids.size}")
            }
        }
    }
    var newGroupFor by remember { mutableStateOf<List<String>?>(null) }

    val context = LocalContext.current
    LaunchedEffect(mode) { env.stopSelecting() }
    val pickedTabs = { env.picked.mapNotNull { id -> browser.tabs.firstOrNull { it.id == id } } }
    val pinSelected: (Boolean) -> Unit = { on ->
        val ids = pickedTabs().filter { !it.isPrivate }.map { it.id }
        env.stopSelecting()
        ids.forEach { store.setPinned(it, on) }
        if (ids.isNotEmpty()) Notices.show(if (on) "Закреплено вкладок: ${ids.size}" else "Откреплено вкладок: ${ids.size}")
    }
    val copySelectedLinks: () -> Unit = {
        val urls = pickedTabs().filter { !it.home && it.url.isNotBlank() }.map { it.url }
        env.stopSelecting()
        if (urls.isNotEmpty()) {
            val ok = runCatching {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("", urls.joinToString("\n")))
            }.isSuccess
            Notices.show(if (ok) "Скопировано ссылок: ${urls.size}" else "Не удалось скопировать")
        }
    }
    val moveSelected: (String) -> Unit = { g ->
        val ids = env.picked.toList()
        env.stopSelecting()
        ids.forEach { store.assignTab(it, g) }
        if (ids.isNotEmpty()) Notices.show("Перемещено вкладок: ${ids.size} (${store.groupName(g)})")
    }

    Box(
        Modifier
            .fillMaxSize()
            .consumeTaps(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal().coerceIn(0f, 1f) }
                .drawBehind { drawRect(lerp(bgNormal, bgPrivate, progress())) },
        )

        // Шапка лежит поверх списка: пилюля и кнопка плавают над вкладками,
        // а сам список начинается под ними и прокручивается под шапкой.
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        var headerH by remember { mutableStateOf(SWITCHER_TOP_H + 12.dp) }
        val topReserve = statusTop + headerH

        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = mode,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = stage(reveal(), 0.12f, 0.6f)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
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
                    EmptyTabs(m == 1, searching = q.isNotEmpty(), onNew = { onNew(m == 1) }, modifier = Modifier.fillMaxSize().padding(top = topReserve, bottom = 104.dp))
                } else when (view) {
                    1 -> TabGridPage(list, current, env, onOpen, onCloseTab, onSettled = reportRect, barReserve = barReserve, aspect = aspect, topReserve = topReserve)
                    2 -> TabCardsPage(list, current, env, onOpen, onCloseTab, onSettled = reportRect, aspect = aspect, barReserve = barReserve, topReserve = topReserve)
                    else -> TabListPage(list, current, env, onOpen, onCloseTab, onSettled = reportRect, barReserve = barReserve, topReserve = topReserve)
                }
            }

            // Край прокрутки (как scroll edge effect в HIG): вкладки уходят под плавающие шапку и нижнюю панель
            // мягко, текст карточек не просвечивает между кнопками. В покое список начинается ниже, и полосы не видны.
            ScrollEdge(top = true, height = topReserve, color = { lerp(bgNormal, bgPrivate, progress()) }, alpha = { stage(reveal(), 0.08f, 0.55f) })
            ScrollEdge(
                top = false,
                height = barReserve,
                color = { lerp(bgNormal, bgPrivate, progress()) },
                alpha = { stage(reveal(), 0.08f, 0.55f) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            Box(
                Modifier
                    .statusBarsPadding()
                    .onSizeChanged { headerH = with(density) { it.height.toDp() } }
                    .graphicsLayer {
                    val r = stage(reveal(), 0.08f, 0.55f)
                    alpha = r
                    translationY = -(1f - r) * 14.dp.toPx()
                },
            ) {
                if (env.selecting) {
                    val sel = pickedTabs()
                    SelectionHeader(
                        count = sel.size,
                        privatePage = mode == 1,
                        linksCount = sel.count { !it.home && it.url.isNotBlank() },
                        allPinned = sel.isNotEmpty() && sel.all { store.isPinned(it.id) },
                        store = store,
                        groupId = groupId,
                        onDone = { env.stopSelecting() },
                        onClose = closeSelected,
                        onPin = pinSelected,
                        onCopyLinks = copySelectedLinks,
                        onMove = moveSelected,
                        onNewGroup = { newGroupFor = env.picked.toList() },
                    )
                } else SwitcherHeader(
                    searching = searching,
                    onSearching = { searching = it },
                    query = query,
                    onQuery = { query = it },
                    focus = focus,
                    privatePage = mode == 1,
                    store = store,
                    onGroup = { mode = 0; store.activeTabGroup = it },
                    view = view,
                    onView = {
                        view = it
                        browser.store.updateTabsView(it)
                    },
                    onHistory = onHistory,
                    closedCount = browser.closedTabs.size,
                    onClosed = { showClosed = true },
                    onCloseAll = closeAll,
                )
            }
        }

        SwitcherBar(
            count = normalCount,
            privatePage = mode == 1,
            reveal = reveal,
            from = fromRect,
            fromText = fromText,
            onNew = { onNew(mode == 1) },
            onSelectPrivate = { mode = if (it) 1 else 0 },
            onSearch = { env.stopSelecting(); searching = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        env.menuTab?.let { held ->
            key(held.id) {
                TabHoldMenu(
                    browser = browser,
                    env = env,
                    tab = held,
                    closeAllLabel = if (held.isPrivate) "Закрыть приватные" else "Закрыть все вкладки",
                    onCloseAll = closeAll,
                    onCloseTab = onCloseTab,
                    onDuplicated = onClose,
                    onNewGroup = { newGroupFor = listOf(it.id) },
                )
            }
        }
        newGroupFor?.let { targets ->
            GroupDialog(
                title = "Новая группа",
                initialName = "",
                initialIcon = 1,
                onSave = { n, i ->
                    val g = store.addTabGroup(n, i)
                    targets.forEach { store.assignTab(it, g.id) }
                    newGroupFor = null
                    env.stopSelecting()
                    Notices.show(if (targets.size == 1) "Вкладка перемещена: ${g.name}" else "Перемещено вкладок: ${targets.size} (${g.name})")
                },
                onDelete = null,
                onDismiss = { newGroupFor = null },
            )
        }

        if (showClosed) {
            ClosedTabsSheet(browser, onDismiss = { showClosed = false }, onRestored = { showClosed = false; onClose() })
        }
    }
}

@Composable
internal fun PrefetchVisibleTabThumbnails(
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
            TabThumbs.prefetch(context, ids, small)
        }
    }
}

/** Полоса фона у края экрана: сплошная у кромки и прозрачная к контенту. Нажатия не перехватывает. */
@Composable
private fun ScrollEdge(
    top: Boolean,
    height: androidx.compose.ui.unit.Dp,
    color: () -> androidx.compose.ui.graphics.Color,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { this.alpha = alpha() }
            .drawBehind {
                val c = color()
                val solid = if (top) 0f to c else 1f to c
                val mid = if (top) 0.62f to c.copy(alpha = 0.88f) else 0.38f to c.copy(alpha = 0.88f)
                val clear = if (top) 1f to c.copy(alpha = 0f) else 0f to c.copy(alpha = 0f)
                val stops = if (top) arrayOf(solid, mid, clear) else arrayOf(clear, mid, solid)
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(*stops))
            },
    )
}
