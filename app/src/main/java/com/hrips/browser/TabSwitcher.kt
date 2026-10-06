package com.hrips.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
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
            if (result == PixelCopy.SUCCESS) tab.thumbnail = bmp.asImageBitmap()
            done()
        }, Handler(Looper.getMainLooper()))
    } catch (e: Throwable) {
        done()
    }
}

private fun tabKey(t: Tab) = System.identityHashCode(t)

/**
 * Переключатель вкладок на весь экран: "Вкладки" и "Приватный" (переключаются свайпом или нажатием),
 * карусель карточек с превью (или сетка), нижняя панель с кнопкой "+". Карточку можно смахнуть вверх, чтобы закрыть.
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

    val all = browser.tabs.toList()
    val current = browser.currentIndex
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
            // Шапка: вкладки "Вкладки / Приватный" и поиск по вкладкам
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    PrimaryTabRow(
                        selectedTabIndex = pager.currentPage,
                        modifier = Modifier.width(300.dp),
                        containerColor = Color.Transparent,
                        divider = {},
                    ) {
                        Tab(
                            selected = pager.currentPage == 0,
                            onClick = { scope.launch { pager.animateScrollToPage(0) } },
                            text = { Text("Вкладки") },
                        )
                        Tab(
                            selected = pager.currentPage == 1,
                            onClick = { scope.launch { pager.animateScrollToPage(1) } },
                            text = { Text("Приватный") },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { searching = true }) { Icon(HripsIcons.Search, "Поиск по вкладкам") }
                }
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                val priv = page == 1
                val q = query.trim()
                val list = all.mapIndexedNotNull { i, t ->
                    if (t.isPrivate == priv && (q.isEmpty() || t.label().contains(q, true) || t.url.contains(q, true))) i to t else null
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
                                t, selected = i == current, onClick = { pick(i) }, onClose = { browser.closeTab(i) },
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.8f).animateItem(),
                            )
                        }
                    }
                    else -> BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = 96.dp)) {
                        val cardW = minOf(maxWidth * 0.72f, 380.dp)
                        val cardH = minOf(cardW * (if (maxWidth > 600.dp) 1.0f else 1.3f), maxHeight - 24.dp)
                        val state = rememberLazyListState()
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
                                TabCard(
                                    t, selected = i == current, onClick = { pick(i) }, onClose = { browser.closeTab(i) },
                                    modifier = Modifier.width(cardW).height(cardH).animateItem(),
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
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Карточка вкладки: значок, название, крестик и превью страницы. Смахивается вверх. */
@Composable
private fun TabCard(tab: Tab, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val dismissPx = with(LocalDensity.current) { 110.dp.toPx() }
    val titleColor = if (selected) cs.onPrimaryContainer else cs.onSurface

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        shadowElevation = if (selected) 6.dp else 0.dp,
        modifier = modifier
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
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    tab.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(24.dp), tint = cs.primary)
                    tab.home -> Icon(HripsIcons.Home, null, Modifier.size(24.dp), tint = cs.onSurfaceVariant)
                    else -> Favicon(tab.url, 24.dp) {
                        Surface(shape = CircleShape, color = cs.surfaceVariant, modifier = Modifier.size(24.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(tab.label().firstOrNull()?.uppercase() ?: "", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    tab.label(),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(HripsIcons.Close, "Закрыть вкладку", Modifier.size(20.dp), tint = titleColor)
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = tab.thumbnail
                if (thumb != null && !tab.home) {
                    Image(
                        bitmap = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Превью ещё нет (вкладка не была открыта) или это стартовая страница
                    when {
                        tab.isPrivate -> Icon(HripsIcons.Mask, null, Modifier.size(48.dp), tint = cs.primary.copy(alpha = 0.7f))
                        tab.home -> Icon(HripsIcons.Home, null, Modifier.size(48.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.6f))
                        else -> Favicon(tab.url, 48.dp) {
                            Icon(HripsIcons.Search, null, Modifier.size(40.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTabs(priv: Boolean, searching: Boolean, onNew: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (priv) HripsIcons.Mask else HripsIcons.Search, null, Modifier.size(64.dp), tint = cs.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                searching -> "Ничего не найдено"
                priv -> "Нет приватных вкладок"
                else -> "Нет открытых вкладок"
            },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (priv && !searching) {
            Spacer(Modifier.height(8.dp))
            Text(
                "История и cookies приватных вкладок не сохраняются",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (!searching) {
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onNew) { Text(if (priv) "Приватная вкладка" else "Новая вкладка") }
        }
    }
}

/** Нижняя плавающая панель: вид, история, "+" по центру, счётчик (вернуться к странице), ещё. */
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
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = cs.surfaceContainerHighest,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().height(64.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onToggleGrid) {
                        Icon(if (grid) HripsIcons.Rows else HripsIcons.Grid, if (grid) "Показать каруселью" else "Показать сеткой")
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onHistory) { Icon(HripsIcons.History, "История") }
                }
                Spacer(Modifier.width(88.dp)) // место под кнопку "+"
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TabCounterButton(count, onClick = onDone)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { menu = true }) { Icon(HripsIcons.MoreVert, "Ещё") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(24.dp)) {
                        DropdownMenuItem(
                            text = { Text(if (privatePage) "Закрыть приватные вкладки" else "Закрыть все вкладки") },
                            leadingIcon = { Icon(HripsIcons.Trash, null) },
                            onClick = { menu = false; onCloseAll() },
                        )
                    }
                }
            }
        }
        FloatingActionButton(onClick = onNew, shape = CircleShape, modifier = Modifier.size(72.dp)) {
            Icon(HripsIcons.Add, if (privatePage) "Новая приватная вкладка" else "Новая вкладка", Modifier.size(30.dp))
        }
    }
}
