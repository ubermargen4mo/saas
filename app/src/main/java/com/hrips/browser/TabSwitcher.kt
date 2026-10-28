@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hrips.browser

import androidx.compose.material3.toShape
import androidx.compose.material3.MaterialShapes
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp as lerpF
import kotlinx.coroutines.flow.first

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

    val main = MainThread.handler
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

// Поиск по вкладкам: по названию и адресу.
private fun Tab.matches(q: String) = q.isEmpty() || matchesQuery(label(), url, q)

private fun tabSubtitle(tab: Tab): String = when {
    tab.isPrivate && tab.home -> "История не сохраняется"
    tab.home -> "Новая вкладка"
    else -> plainHost(tab.url) ?: tab.url
}

/** Доля отрезка [from, to] прогресса: 0 до его начала, 1 после конца. Для ступенчатого появления частей экрана. */
private fun stage(p: Float, from: Float, to: Float): Float = ((p - from) / (to - from)).coerceIn(0f, 1f)

// Нижняя панель обзора: пилюля и кнопки 42 dp, как у адресной строки (HripsLayout.BarHeight).
private val SWITCHER_BAR_H = HripsLayout.BarHeight
private val SWITCHER_TOP_H = 40.dp
private const val STRIP_REACH = 9

private class CoordsHolder {
    var c: LayoutCoordinates? = null
    fun rect(): UiRect = c?.takeIf { it.isAttached }?.boundsInRoot() ?: UiRect.Zero
}

private class SwitcherEnv(val browser: Browser) {
    var currentHolder: CoordsHolder? = null

    // Меню по удержанию карточки: вкладка и прямоугольник её карточки в окне.
    var menuTab by mutableStateOf<Tab?>(null)
    var menuRect by mutableStateOf(UiRect.Zero)

    // Выбор нескольких вкладок («Выбрать вкладки» в меню).
    var selecting by mutableStateOf(false)
    val picked = mutableStateListOf<String>()

    // Перестановка: какую карточку тянут, какая возвращается на место и на сколько каждая смещена от своей ячейки.
    var draggingId by mutableStateOf<String?>(null)
    var settlingId by mutableStateOf<String?>(null)
    var dragX by mutableFloatStateOf(0f)
    var dragY by mutableFloatStateOf(0f)

    // Палец, поднятый после удержания, не должен «нажать» карточку и открыть вкладку.
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

    /** Вкладка [id] встаёт на место вкладки [targetId]. Закреплённые и обычные друг с другом не меняются местами. */
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
    // Открываем обзор на группе текущей вкладки.
    LaunchedEffect(Unit) {
        val t = browser.current
        if (!t.isPrivate) store.activeTabGroup = store.groupOf(t.id)
    }
    val groupId by remember { derivedStateOf { store.activeTabGroup.takeIf { id -> id.isEmpty() || store.tabGroups.any { it.id == id } } ?: "" } }
    val normalCount by remember(browser) { derivedStateOf { browser.tabs.count { !it.isPrivate && store.groupOf(it.id) == groupId } } }
    val privateCount by remember(browser) { derivedStateOf { browser.tabs.count { it.isPrivate } } }

    val normalList by remember(browser) {
        derivedStateOf {
            val qq = qState.value
            browser.tabs.mapIndexedNotNull { i, t -> if (!t.isPrivate && store.groupOf(t.id) == groupId && t.matches(qq)) i to t else null }
                // Закреплённые идут первыми (сортировка устойчивая: порядок внутри каждой части прежний).
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
        val t = browser.newTab(incognito = priv, returnToPrevious = false)
        if (!priv) store.assignTab(t.id, groupId)
        onClose()
    }
    val onOpen: (Int) -> Unit = { i ->
        if (env.swallowClick) {
            // Палец поднят после удержания: меню уже показано (или карточку переставляли), вкладку не открываем.
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
    // Карточки карусели повторяют форму окна: на телефоне это телефон, на планшете планшет.
    val aspect = remember(window, statusPx, wide) {
        val chrome = if (wide) with(density) { 112.dp.roundToPx() } else 0
        (window.width.toFloat() / (window.height - statusPx - chrome).coerceAtLeast(1)).coerceIn(0.35f, 2.2f)
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barReserve = navBottom + SWITCHER_BAR_H + HripsLayout.BarGap * 2
    // Откуда «вырастает» нижняя панель: прямоугольник адресной строки. На широком окне такой строки внизу нет.
    val fromRect = remember { if (wide) null else origins.resolve("tabs") }
    val fromText = remember {
        val t = browser.current
        if (t.home) "Поиск" else t.siteHost() ?: plainHost(t.url) ?: t.url
    }
    // Карточка могла сдвинуться, пока шло появление: после его конца сообщаем итоговый прямоугольник.
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

    // Отпущенная после перестановки карточка плавно возвращается из-под пальца в свою ячейку.
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

    // «Выбрать вкладки»: закрываем отмеченные одним действием, с возвратом.
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
    // Вкладка, для которой в меню выбрали «Новая группа»: название спрашиваем диалогом.
    var newGroupFor by remember { mutableStateOf<Tab?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal().coerceIn(0f, 1f) }
                .drawBehind { drawRect(lerp(bgNormal, bgPrivate, progress())) },
        )

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(
                Modifier.graphicsLayer {
                    val r = stage(reveal(), 0.08f, 0.55f)
                    alpha = r
                    translationY = -(1f - r) * 14.dp.toPx()
                },
            ) {
                SwitcherHeader(
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

            AnimatedContent(
                targetState = mode,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Только прозрачность, без масштаба и сдвига: карточка не должна менять свой прямоугольник,
                    // иначе страница, сжимающаяся в карточку, целится не туда.
                    .graphicsLayer {
                        alpha = stage(reveal(), 0.12f, 0.6f)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
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
                    1 -> TabGridPage(list, current, env, onOpen, onCloseTab, onSettled = reportRect, barReserve = barReserve, aspect = aspect, query = q)
                    2 -> TabCardsPage(list, current, env, onOpen, onCloseTab, onSettled = reportRect, aspect = aspect, barReserve = barReserve, query = q)
                    else -> TabListPage(list, current, env, onOpen, onCloseTab, onSettled = reportRect, barReserve = barReserve, query = q)
                }
            }
        }

        if (env.selecting) {
            SelectionBar(
                count = env.picked.size,
                onDone = { env.stopSelecting() },
                onClose = closeSelected,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            SwitcherBar(
                count = normalCount,
                privatePage = mode == 1,
                reveal = reveal,
                from = fromRect,
                fromText = fromText,
                onNew = { onNew(mode == 1) },
                onSelectPrivate = { mode = if (it) 1 else 0 },
                onSearch = { searching = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

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
                    onNewGroup = { newGroupFor = it },
                )
            }
        }
        newGroupFor?.let { target ->
            GroupDialog(
                title = "Новая группа",
                initialName = "",
                initialIcon = 1,
                onSave = { n, i ->
                    val g = store.addTabGroup(n, i)
                    store.assignTab(target.id, g.id)
                    newGroupFor = null
                    Notices.show("Вкладка перемещена: ${g.name}")
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
    // Смах вверх закрывает вкладку, пока сетка не может прокрутиться дальше вниз (всё помещается или список упёрся в конец).
    // Иначе тот же жест листает сетку: у вертикальной прокрутки и у смаха вверх один и тот же жест, и две вещи сразу он не умеет.
    val swipeClose by remember(state) { derivedStateOf { !state.canScrollForward } }
    // На телефоне всегда две колонки (раньше Adaptive(160) на узком экране давал одну), на широком окне по ширине.
    val phone = LocalConfiguration.current.screenWidthDp < 600
    // Карточки чуть вытянуты вверх, как миниатюры в Safari; крайние значения не дают ни «спичек», ни квадратов.
    val cardAspect = aspect.coerceIn(0.62f, 0.9f)
    LazyVerticalGrid(
        state = state,
        columns = if (phone) GridCells.Fixed(2) else GridCells.Adaptive(minSize = 180.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = barReserve + 16.dp),
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
                    // Тянутую карточку не анимируем как соседей: она идёт за пальцем и остаётся поверх.
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
                    TabTitleRow(t, Modifier.fillMaxWidth().height(34.dp), query, pinned = env.browser.store.isPinned(t.id))
                }
            }
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

/**
 * Карусель крупных карточек формы окна (телефон или планшет) и лента-миниатюра всех вкладок снизу.
 * Лента двигается вместе с каруселью покадрово: позиция берётся из состояния LazyRow и читается только
 * в graphicsLayer, то есть без перекомпозиций. Жест по ленте листает карусель, касание прыгает к вкладке.
 * Смахивание карточки вверх закрывает вкладку, соседние карточки чуть меньше и прозрачнее.
 */
@Composable
private fun TabCardsPage(
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
    // Шаг карусели в пикселях (ширина карточки + зазор): по нему лента считает, где сейчас карусель.
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

/** Подпись под карточкой: значок сайта и название, как под превью вкладок в Safari. */
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

/**
 * Лента маленьких копий всех вкладок. Текущая крупнее и обведена, остальные приглушены. Лента всегда центрирует
 * позицию карусели: на последней вкладке последняя миниатюра по центру. Композятся только соседние миниатюры.
 */
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
                        // Палец по ленте двигает карусель в пересчёте на её шаг: миниатюры ведут за пальцем.
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

/**
 * Смахивание вверх для карточки в сетке. От [SwipeUpToClose] отличается тем, что забирает жест себе только когда он
 * направлен вверх, преимущественно вертикален и [enabled]; движение вниз и в стороны остаются за прокруткой сетки.
 * Бросок или проход за порог закрывает вкладку, иначе карточка возвращается на место.
 */
@Composable
private fun GridSwipeUpToClose(enabled: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val closeNow by rememberUpdatedState(onClose)
    val enabledNow by rememberUpdatedState(enabled)
    Box(
        modifier
            .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var claimed = false
                    val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                        if (enabledNow && over < 0f) {
                            change.consume()
                            claimed = true
                        }
                    }
                    if (slop == null || !claimed) return@awaitEachGesture
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(slop)
                    offset = (offset + slop.positionChange().y).coerceAtMost(0f)
                    val finished = verticalDrag(slop.id) { change ->
                        tracker.addPointerInputChange(change)
                        offset = (offset + change.positionChange().y).coerceAtMost(0f)
                        change.consume()
                    }
                    val velocity = if (finished) tracker.calculateVelocity().y else 0f
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
                }
            }
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
    // Чистый «экран» со скруглёнными углами без подписи внутри: она рисуется под карточкой (карусель и сетка).
    clean: Boolean = big,
    corner: Dp = 28.dp,
    // Вызывается после каждого сдвига при перетаскивании; null: карточку переставлять нельзя (карусель).
    onMoved: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val holder = remember { CoordsHolder() }
    if (selected) SideEffect { env.currentHolder = holder }
    if (clean) {
        val screen = RoundedCornerShape(corner)
        Box(
            modifier
                .onPlaced { holder.c = it }
                .clip(screen)
                .background(cs.surfaceContainerLowest)
                .border(if (selected) 3.dp else 0.5.dp, if (selected) cs.primary else cs.onSurface.copy(alpha = 0.14f), screen)
                .semantics { this.selected = selected }
                .tabHold(env, tab, holder, onMoved)
                .clickable(onClick = onOpen),
        ) {
            TabPreview(tab, mini = false, small = !big, modifier = Modifier.fillMaxSize())
            if (env.selecting) {
                SelectBadge(tab.id in env.picked, Modifier.align(Alignment.TopEnd).padding(4.dp))
            } else {
                CloseTabButton(tab, Modifier.align(Alignment.TopEnd).padding(4.dp), onClose)
            }
        }
        return
    }
    Box(
        modifier
            .onPlaced { holder.c = it }
            .clip(HripsShapes.L)
            .background(if (selected) cs.primary else cs.surfaceContainerHigh)
            .semantics { this.selected = selected }
            .tabHold(env, tab, holder, onMoved)
            .clickable(onClick = onOpen),
    ) {
        Box(Modifier.fillMaxSize().padding(4.dp).clip(HripsShapes.ML).background(cs.surfaceContainerLowest)) {
            TabPreview(tab, mini = false, small = !big, modifier = Modifier.fillMaxSize())
            if (env.selecting) {
                SelectBadge(tab.id in env.picked, Modifier.align(Alignment.TopEnd))
            } else {
                CloseTabButton(tab, Modifier.align(Alignment.TopEnd), onClose)
            }
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
    query: String = "",
    onMoved: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val holder = remember { CoordsHolder() }
    // Меню по удержанию встаёт у всей строки, а не у превью, к которому привязана анимация открытия.
    val rowHolder = remember { CoordsHolder() }
    if (selected) SideEffect { env.currentHolder = holder }
    val pinned = env.browser.store.isPinned(tab.id)
    val titleColor = if (selected) cs.onPrimaryContainer else cs.onSurface
    val subColor = if (selected) cs.onPrimaryContainer.copy(alpha = 0.72f) else cs.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(HripsShapes.ML)
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .semantics { this.selected = selected }
            .onPlaced { rowHolder.c = it }
            .tabHold(env, tab, rowHolder, onMoved)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tab.label(), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = titleColor)
                if (pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(HripsIcons.TabPin, "Закреплена", Modifier.size(14.dp), tint = subColor)
                }
            }
            Text(tabSubtitle(tab), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = subColor)
        }
        if (env.selecting) {
            SelectBadge(tab.id in env.picked)
        } else {
            IconButton(onClick = onClose) {
                Icon(HripsIcons.Close, "Закрыть вкладку ${tab.label()}", tint = titleColor)
            }
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
    privatePage: Boolean,
    store: Store,
    onGroup: (String) -> Unit,
    view: Int,
    onView: (Int) -> Unit,
    onHistory: () -> Unit,
    closedCount: Int,
    onClosed: () -> Unit,
    onCloseAll: () -> Unit,
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
        // Как в Safari на айфоне: «⋯» слева, группа вкладок по центру. Поиск открывается снизу, лупой.
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSearching) {
                Surface(shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(HripsIcons.Search, null, tint = cs.onSurfaceVariant)
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp)) {
                            if (query.isEmpty()) {
                                Text(
                                    "Поиск по содержимому вкладок", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
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
                SwitcherMenu(view, onView, onHistory, privatePage, closedCount, onClosed, onCloseAll)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    GroupPill(privatePage, store, onGroup)
                }
                // Пустой кружок справа держит пилюлю группы строго по центру.
                Spacer(Modifier.size(SWITCHER_TOP_H))
            }
        }
    }
}

/**
 * «⋯» слева сверху. Сюда переехали вид (список, сетка, карточки) и история: нижняя панель осталась только для
 * самого частого (новая вкладка, приватный режим, счётчик, поиск). Нажимается целиком, как кнопки нижней панели.
 */
@Composable
private fun SwitcherMenu(
    view: Int,
    onView: (Int) -> Unit,
    onHistory: () -> Unit,
    privatePage: Boolean,
    closedCount: Int,
    onClosed: () -> Unit,
    onCloseAll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        BarPill(rememberBarPalette(), Modifier.size(SWITCHER_TOP_H)) {
            BarButton(onClick = { menu = true }) { Icon(HripsIcons.BarMore, "Ещё") }
        }
        SegMenu(expanded = menu, onDismiss = { menu = false }) {
            val views = listOf(
                Triple(0, "Список", HripsIcons.Rows),
                Triple(1, "Сетка", HripsIcons.Grid),
                Triple(2, "Карточки", HripsIcons.Layers),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                views.forEachIndexed { i, (v, label, icon) ->
                    ToolRow(
                        icon, label, segCorners(views.size, 1, i, 22.dp, 6.dp),
                        selected = v == view,
                        trailing = if (v == view) HripsIcons.Check else null,
                    ) { menu = false; onView(v) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val hasClosed = !privatePage
                val n = if (hasClosed) 2 else 1
                ToolRow(HripsIcons.History, "История", segCorners(n, 1, 0, 22.dp, 6.dp)) { menu = false; onHistory() }
                if (hasClosed) {
                    ToolRow(
                        HripsIcons.History,
                        if (closedCount > 0) "Недавно закрытые ($closedCount)" else "Недавно закрытые",
                        segCorners(n, 1, 1, 22.dp, 6.dp),
                        enabled = closedCount > 0,
                    ) { menu = false; onClosed() }
                }
            }
            ToolRow(
                HripsIcons.Trash, if (privatePage) "Закрыть приватные вкладки" else "Закрыть все вкладки",
                Corners.all(22.dp), danger = true,
            ) { menu = false; onCloseAll() }
        }
    }
}

/**
 * Меню экрана вкладок в стиле меню инструментов: панель surfaceContainer с большим скруглением, внутри сегментные
 * группы строк (внешние углы большие, внутренние маленькие, при нажатии строка округляется).
 */
@Composable
private fun SegMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val width = minOf(280.dp, (LocalConfiguration.current.screenWidthDp - 32).dp)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = HripsShapes.XL,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.width(width).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private val GROUP_ICONS get() = listOf(HripsIcons.Home, HripsIcons.Folder, HripsIcons.Star, HripsIcons.Globe, HripsIcons.Layers, HripsIcons.Grid, HripsIcons.Person)

internal fun groupIcon(i: Int) = GROUP_ICONS[i.coerceIn(0, GROUP_ICONS.lastIndex)]

/**
 * Группа вкладок по центру сверху, как в Safari: значок и название («Личные» по умолчанию).
 * Нажатие открывает список групп, долгое нажатие настройки выбранной группы (название, значок, удаление).
 */
@Composable
private fun GroupPill(privatePage: Boolean, store: Store, onGroup: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }   // id редактируемой группы
    var creating by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val active = store.activeTabGroup.takeIf { id -> id.isEmpty() || store.tabGroups.any { it.id == id } } ?: ""
    val activeGroup = store.tabGroups.firstOrNull { it.id == active }
    val title = if (privatePage) "Приватные" else activeGroup?.name ?: store.personalName
    val icon = if (privatePage) HripsIcons.Mask else groupIcon(activeGroup?.icon ?: store.personalIcon)
    Box {
        BarPill(rememberBarPalette(), Modifier.height(SWITCHER_TOP_H)) {
            BarButton(
                onClick = { menu = true },
                onLongClick = if (privatePage) null else {
                    { haptic.performHapticFeedback(HapticFeedbackType.LongPress); editing = active }
                },
                wrap = true,
            ) {
                Row(Modifier.padding(horizontal = 14.dp).widthIn(max = 220.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(4.dp))
                    Icon(HripsIcons.ChevronDown, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                }
            }
        }
        SegMenu(expanded = menu, onDismiss = { menu = false }) {
            val all = listOf(TabGroupRow("", store.personalName, store.personalIcon)) +
                store.tabGroups.map { TabGroupRow(it.id, it.name, it.icon) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                all.forEachIndexed { i, g ->
                    val selected = !privatePage && g.id == active
                    ToolRow(
                        groupIcon(g.icon), g.name, segCorners(all.size, 1, i, 22.dp, 6.dp),
                        selected = selected,
                        trailing = if (selected) HripsIcons.Check else null,
                    ) { menu = false; onGroup(g.id) }
                }
            }
            ToolRow(HripsIcons.Add, "Новая группа", Corners.all(22.dp)) { menu = false; creating = true }
        }
    }
    editing?.let { id ->
        val g = store.tabGroups.firstOrNull { it.id == id }
        GroupDialog(
            title = "Группа вкладок",
            initialName = g?.name ?: store.personalName,
            initialIcon = g?.icon ?: store.personalIcon,
            onSave = { n, i -> store.editTabGroup(id, n, i); editing = null },
            onDelete = if (id.isEmpty()) null else ({ store.deleteTabGroup(id); editing = null }),
            onDismiss = { editing = null },
        )
    }
    if (creating) {
        GroupDialog(
            title = "Новая группа",
            initialName = "",
            initialIcon = 1,
            onSave = { n, i -> val g = store.addTabGroup(n, i); creating = false; onGroup(g.id) },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
}

private class TabGroupRow(val id: String, val name: String, val icon: Int)

/** Название и значок группы; для созданных пользователем групп есть ещё удаление (вкладки уходят в «Личные»). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupDialog(
    title: String,
    initialName: String,
    initialIcon: Int,
    onSave: (String, Int) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableIntStateOf(initialIcon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    singleLine = true,
                    label = { Text("Название") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GROUP_ICONS.forEachIndexed { i, ic ->
                        val sel = i == icon
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { icon = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(ic, null, Modifier.size(22.dp), tint = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(HripsIcons.Trash, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Удалить группу")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, icon) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
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

/**
 * Нижняя панель экрана вкладок, как в Safari на айфоне: лупа, «Приватные», счётчик вкладок, «+» (на месте «Готово»).
 * Начинается ровно как адресная строка браузера (её прямоугольник), а по мере открытия вырастает на всю ширину,
 * и в ней проявляются кнопки: строка поиска превращается в панель вкладок.
 * Размер и положение считаются в layout по прогрессу открытия, поэтому за кадр нет перекомпозиции.
 */
@Composable
private fun SwitcherBar(
    count: Int,
    privatePage: Boolean,
    reveal: () -> Float,
    from: UiRect?,
    fromText: String,
    onNew: () -> Unit,
    onSelectPrivate: (Boolean) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val palette = rememberBarPalette()
    val density = LocalDensity.current
    val fabBg by animateColorAsState(if (privatePage) cs.tertiary else cs.primary, tween(MOVE_MS), label = "fabBg")
    val fabFg by animateColorAsState(if (privatePage) cs.onTertiary else cs.onPrimary, tween(MOVE_MS), label = "fabFg")
    var slot by remember { mutableStateOf<UiRect?>(null) }
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = HripsLayout.BarMargin, vertical = HripsLayout.BarGap),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .height(SWITCHER_BAR_H)
                .onGloballyPositioned {
                    val r = it.boundsInRoot()
                    if (slot != r) slot = r
                },
        ) {
            val s = slot
            // Адресная строка вырастает в широкую пилюлю и к концу растворяется: на её месте проявляются три отдельных элемента.
            BarPill(
                palette,
                Modifier
                    .morphFrom(from, s, reveal)
                    .graphicsLayer { alpha = if (s == null || from == null) 0f else 1f - stage(reveal(), 0.5f, 0.85f) },
                pressHost = false,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (from != null && fromText.isNotEmpty()) {
                        // Надпись адресной строки гаснет первой, пока панель только начинает расти.
                        Text(
                            fromText,
                            Modifier.graphicsLayer { alpha = 1f - stage(reveal(), 0f, 0.3f) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                    }
                }
            }
            // Как в образце: лупа и «+» отдельные круглые кнопки, посередине капсула-переключатель.
            Row(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val c = stage(reveal(), if (from == null) 0f else 0.45f, if (from == null) 0.5f else 0.9f)
                        alpha = c
                        val sc = 0.92f + 0.08f * c
                        scaleX = sc
                        scaleY = sc
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BarPill(palette, Modifier.size(SWITCHER_BAR_H)) {
                    BarButton(onClick = onSearch) { Icon(HripsIcons.Search, "Поиск по вкладкам") }
                }
                // Средняя капсула занимает всё место между кнопками, до самых краёв; сегменты делят её пополам.
                BarPill(palette, Modifier.weight(1f).height(SWITCHER_BAR_H)) {
                    Box(Modifier.fillMaxSize()) {
                        ModeToggle(privatePage, count, onSelectPrivate, Modifier.fillMaxSize())
                    }
                }
                AccentAddButton(fabBg, fabFg, if (privatePage) "Новая приватная вкладка" else "Новая вкладка", SWITCHER_BAR_H) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onNew()
                }
            }
        }
    }
}

/**
 * Переключатель «Приватные» / «N вкладок» (обычные вкладки текущей группы). Выбранный сегмент подсвечен капсулой,
 * которая скользит к соседнему: положение и ширина анимируются пружиной темы. Закрывать обзор он не умеет.
 */
@Composable
private fun ModeToggle(privatePage: Boolean, count: Int, onSelectPrivate: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val haptic = LocalHapticFeedback.current
    var privX by remember { mutableIntStateOf(0) }
    var privW by remember { mutableIntStateOf(0) }
    var tabsX by remember { mutableIntStateOf(0) }
    var tabsW by remember { mutableIntStateOf(0) }
    val x by animateFloatAsState((if (privatePage) privX else tabsX).toFloat(), motion.defaultSpatialSpec(), label = "toggleX")
    val w by animateFloatAsState((if (privatePage) privW else tabsW).toFloat(), motion.defaultSpatialSpec(), label = "toggleW")
    val pill by animateColorAsState(if (privatePage) cs.tertiaryContainer else cs.onSurface.copy(alpha = 0.12f), motion.defaultEffectsSpec(), label = "togglePill")
    val privFg by animateColorAsState(if (privatePage) cs.onTertiaryContainer else cs.onSurface, motion.defaultEffectsSpec(), label = "togglePrivFg")
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .layout { m, c ->
                    // Подсветка выбранного сегмента занимает всю высоту капсулы.
                    val p = m.measure(Constraints.fixed(w.roundToInt().coerceAtLeast(0), c.maxHeight.coerceAtLeast(0)))
                    layout(0, p.height) { p.place(x.roundToInt(), 0) }
                }
                .background(pill, CircleShape),
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .onPlaced { tabsX = it.positionInParent().x.roundToInt(); tabsW = it.size.width }
                    .semantics { selected = !privatePage },
            ) {
                BarButton(
                    onClick = { if (privatePage) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelectPrivate(false) } },
                    modifier = Modifier.fillMaxSize(), wrap = true,
                ) {
                    Text(
                        tabsCountText(count), maxLines = 1, softWrap = false,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .onPlaced { privX = it.positionInParent().x.roundToInt(); privW = it.size.width }
                    .semantics { selected = privatePage },
            ) {
                BarButton(
                    onClick = { if (!privatePage) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelectPrivate(true) } },
                    modifier = Modifier.fillMaxSize(), wrap = true,
                ) {
                    Text(
                        "Приватные", color = privFg, maxLines = 1, softWrap = false,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

private fun tabsCountText(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    val word = when {
        m100 in 11..14 -> "вкладок"
        m10 == 1 -> "вкладка"
        m10 in 2..4 -> "вкладки"
        else -> "вкладок"
    }
    return "$n $word"
}

/** Рост пилюли из исходного прямоугольника (адресная строка) в итоговый: ширина, высота и позиция по прогрессу. */
private fun Modifier.morphFrom(start: UiRect?, slot: UiRect?, progress: () -> Float): Modifier = layout { measurable, constraints ->
    val fw = constraints.maxWidth
    val fh = constraints.maxHeight
    val e = progress().coerceIn(0f, 1f)
    if (start == null || slot == null || e >= 1f) {
        val p = measurable.measure(Constraints.fixed(fw, fh))
        return@layout layout(fw, fh) { p.place(0, 0) }
    }
    val w = lerpF(start.width, fw.toFloat(), e).roundToInt().coerceAtLeast(1)
    val h = lerpF(start.height, fh.toFloat(), e).roundToInt().coerceAtLeast(1)
    val x = lerpF(start.left - slot.left, 0f, e).roundToInt()
    val y = lerpF(start.top - slot.top, 0f, e).roundToInt()
    val p = measurable.measure(Constraints.fixed(w, h))
    layout(fw, fh) { p.place(x, y) }
}

/** Круглая кнопка «+» цвета режима. Нажимается целиком (сжимается весь кружок) с пружинным возвратом. */
@Composable
private fun AccentAddButton(bg: Color, fg: Color, label: String, size: Dp = 44.dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        if (pressed) HripsMotion.pressDown() else HripsMotion.pressRelease(),
        label = "addPress",
    )
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(MaterialShapes.Cookie9Sided.toShape())
            .background(bg)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(HripsIcons.Add, null, Modifier.size(26.dp), tint = fg)
    }
}


/**
 * Удержание карточки. Сразу после долгого нажатия появляется меню. Если палец после этого пошёл, меню прячется и
 * карточка едет за пальцем (перестановка); [onMoved] вызывается после каждого сдвига и решает, не пора ли поменяться
 * местами с соседом. [onMoved] = null: переставлять нельзя (карусель), остаётся только меню.
 * Нажатие, которое придёт вместе с подъёмом пальца, проглатывается через [SwitcherEnv.swallowClick].
 */
@Composable
private fun Modifier.tabHold(env: SwitcherEnv, tab: Tab, holder: CoordsHolder, onMoved: (() -> Unit)?): Modifier {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val movedNow by rememberUpdatedState(onMoved)
    val tabNow by rememberUpdatedState(tab)
    val id = tab.id
    return this.pointerInput(id) {
        var total = Offset.Zero
        var armed = false
        val finish: () -> Unit = {
            if (armed) {
                armed = false
                env.endDrag(id)
                scope.launch {
                    delay(150)
                    env.swallowClick = false
                }
            }
        }
        detectDragGesturesAfterLongPress(
            onDragStart = {
                total = Offset.Zero
                armed = !env.selecting
                if (armed) {
                    env.swallowClick = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    env.menuRect = holder.rect()
                    env.menuTab = tabNow
                }
            },
            onDrag = { change, amount ->
                if (armed) {
                    change.consume()
                    total += amount
                    if (env.draggingId == id) {
                        env.dragX += amount.x
                        env.dragY += amount.y
                        movedNow?.invoke()
                    } else if (movedNow != null && total.getDistance() > viewConfiguration.touchSlop * 2f) {
                        env.menuTab = null
                        env.settlingId = null
                        env.dragX = total.x
                        env.dragY = total.y
                        env.draggingId = id
                        movedNow?.invoke()
                    }
                }
            },
            onDragEnd = finish,
            onDragCancel = finish,
        )
    }
}

/** Список: перетаскиваемая строка занимает место той, над серединой которой оказалась. */
private fun reorderInList(state: LazyListState, env: SwitcherEnv, id: String) {
    val infos = state.layoutInfo.visibleItemsInfo
    val me = infos.firstOrNull { it.key == id } ?: return
    val center = me.offset + me.size / 2f + env.dragY
    val target = infos.firstOrNull { it.key != id && center >= it.offset && center <= it.offset + it.size } ?: return
    val targetId = target.key as? String ?: return
    if (env.swap(id, targetId)) {
        val newOffset = if (target.index > me.index) target.offset + target.size - me.size else target.offset
        env.dragY += (me.offset - newOffset).toFloat()
    }
}

/** Сетка: то же, но в двух измерениях. Ячейки одного размера, поэтому новое место карточки - это старое место соседа. */
private fun reorderInGrid(state: LazyGridState, env: SwitcherEnv, id: String) {
    val infos = state.layoutInfo.visibleItemsInfo
    val me = infos.firstOrNull { it.key == id } ?: return
    val cx = me.offset.x + me.size.width / 2f + env.dragX
    val cy = me.offset.y + me.size.height / 2f + env.dragY
    val target = infos.firstOrNull {
        it.key != id &&
            cx >= it.offset.x && cx <= it.offset.x + it.size.width &&
            cy >= it.offset.y && cy <= it.offset.y + it.size.height
    } ?: return
    val targetId = target.key as? String ?: return
    if (env.swap(id, targetId)) {
        env.dragX += (me.offset.x - target.offset.x).toFloat()
        env.dragY += (me.offset.y - target.offset.y).toFloat()
    }
}

/**
 * Пока тянут карточку к верхнему или нижнему краю, список сам прокручивается: перестановка не ограничена одним экраном.
 * После прокрутки смещение карточки пересчитывается, чтобы она осталась под пальцем.
 */
@Composable
private fun DragAutoScroll(
    env: SwitcherEnv,
    span: (String) -> Pair<Float, Float>?,
    viewport: () -> Pair<Float, Float>,
    scroll: suspend (Float) -> Float,
    onMoved: (String) -> Unit,
) {
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    val step = with(density) { 12.dp.toPx() }
    LaunchedEffect(env.draggingId) {
        val id = env.draggingId ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val (top, bottom) = span(id) ?: continue
            val (viewTop, viewBottom) = viewport()
            val dy = when {
                top + env.dragY < viewTop + edge -> -step
                bottom + env.dragY > viewBottom - edge -> step
                else -> 0f
            }
            if (dy != 0f) {
                val used = scroll(dy)
                if (used != 0f) {
                    env.dragY += used
                    onMoved(id)
                }
            }
        }
    }
}

/** Кружок выбора вместо крестика, когда включено «Выбрать вкладки». */
@Composable
private fun SelectBadge(on: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (on) cs.primary else cs.surface.copy(alpha = 0.9f))
                .border(1.5.dp, if (on) cs.primary else cs.onSurfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Icon(HripsIcons.Check, "Выбрана", Modifier.size(16.dp), tint = cs.onPrimary)
        }
    }
}

/**
 * Меню по удержанию вкладки, как у адресной строки: сегменты без подложки. Внизу отдельным сегментом «Закрыть все».
 * «Переместить» открывает список групп прямо в этом же окне. «Новая группа» уходит в диалог выше по дереву.
 */
@Composable
private fun TabHoldMenu(
    browser: Browser,
    env: SwitcherEnv,
    tab: Tab,
    closeAllLabel: String,
    onCloseAll: () -> Unit,
    onCloseTab: (Tab) -> Unit,
    onDuplicated: () -> Unit,
    onNewGroup: (Tab) -> Unit,
) {
    val context = LocalContext.current
    val store = browser.store
    var groups by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = { env.menuTab = null }
    val act: (() -> Unit) -> () -> Unit = { action -> { dismiss(); action() } }
    val priv = tab.isPrivate
    val pinned = store.isPinned(tab.id)

    SegMenuPopup(
        visible = true,
        onDismiss = dismiss,
        position = rememberHoldMenuPosition(env.menuRect),
        origin = TransformOrigin(0.5f, 0f),
    ) {
        val entries = if (groups) {
            groupPickerEntries(
                store = store,
                current = store.groupOf(tab.id),
                onBack = { groups = false },
                onPick = { g ->
                    dismiss()
                    store.assignTab(tab.id, g)
                    Notices.show("Вкладка перемещена: ${store.groupName(g)}")
                },
                onNew = { dismiss(); onNewGroup(tab) },
            )
        } else {
            val main = buildList {
                if (!tab.home && tab.url.isNotBlank()) {
                    add(MenuEntry(HripsIcons.Link, "Копировать ссылку", onClick = act {
                        val ok = runCatching {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("", tab.url))
                        }.isSuccess
                        Notices.show(if (ok) "Адрес скопирован" else "Не удалось скопировать")
                    }))
                }
                if (!priv) {
                    add(MenuEntry(
                        if (pinned) HripsIcons.TabUnpin else HripsIcons.TabPin,
                        if (pinned) "Открепить вкладку" else "Закрепить вкладку",
                        onClick = act { store.setPinned(tab.id, !pinned) },
                    ))
                }
                add(MenuEntry(HripsIcons.Layers, "Дублировать вкладку", onClick = act {
                    val origin = browser.tabs.indexOf(tab)
                    val copy = browser.newTab(tab.url, incognito = priv, returnToPrevious = false)
                    if (!priv) store.assignTab(copy.id, store.groupOf(tab.id))
                    val at = browser.tabs.indexOf(copy)
                    if (origin >= 0 && at > origin + 1) browser.moveTab(at, origin + 1)
                    onDuplicated()
                }))
                add(MenuEntry(HripsIcons.CheckCircle, "Выбрать вкладки", onClick = act { env.startSelecting(tab.id) }))
                if (!priv) add(MenuEntry(HripsIcons.Folder, "Переместить", submenu = true, onClick = { groups = true }))
                add(MenuEntry(HripsIcons.Close, "Закрыть вкладку", danger = true, onClick = act { onCloseTab(tab) }))
            }
            listOf(
                main,
                listOf(MenuEntry(HripsIcons.Close, closeAllLabel, danger = true, onClick = act(onCloseAll))),
            )
        }
        SegMenuColumn(entries)
    }
}

/** Нижняя панель режима «Выбрать вкладки»: той же высоты, что и обычная (42 dp). */
@Composable
private fun SelectionBar(count: Int, onDone: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val palette = rememberBarPalette()
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = HripsLayout.BarMargin, vertical = HripsLayout.BarGap),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().height(SWITCHER_BAR_H),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BarPill(palette, Modifier.height(SWITCHER_BAR_H)) {
                BarButton(onClick = onDone, wrap = true) {
                    Text("Готово", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            BarPill(palette, Modifier.weight(1f).height(SWITCHER_BAR_H)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (count == 0) "Выберите вкладки" else "Выбрано: $count",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            BarPill(palette, Modifier.height(SWITCHER_BAR_H)) {
                BarButton(onClick = onClose, enabled = count > 0, wrap = true) {
                    Text(
                        "Закрыть",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (count > 0) cs.error else LocalContentColor.current,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
