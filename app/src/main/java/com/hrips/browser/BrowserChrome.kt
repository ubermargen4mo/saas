@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Ширина капсулы «фото и QR» рядом со строкой (две кнопки по 36 dp, поля по 2 dp) вместе с зазором 8 dp. */
private val ExtrasWidth = 84.dp

@Composable
internal fun AddressBar(
    tab: Tab,
    onSearch: () -> Unit,
    engine: SearchEngine,
    query: String?,
    onPickEngine: (SearchEngine) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    // Вкладки в строке адреса: слева крестик и значок сайта, щит стоит справа от адреса (как замок в Safari).
    inline: Boolean = false,
    // Смах вниз по строке (как на телефоне): открывает менеджер вкладок.
    onSwipeDown: (() -> Unit)? = null,
    // Справа от строки капсула [extras] (фото и QR), видна при [showExtras]; на главной вместо неё [homeExtras] внутри строки.
    extras: (@Composable () -> Unit)? = null,
    showExtras: Boolean = false,
    // На главной кнопки лежат внутри строки справа, на месте «обновить» (его на главной нет).
    homeExtras: (@Composable () -> Unit)? = null,
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onPasteGo: () -> Unit = {},
    onCloseTab: () -> Unit = {},
    onCloseAll: () -> Unit = {},
) {
    val withPicker = tab.home || query != null
    var showSecurity by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val openMenu = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        menuOpen = true
    }
    val shown = when {
        tab.home -> ""
        query != null -> query
        else -> tab.url.removePrefix("https://")
    }
    val motion = MaterialTheme.motionScheme
    val fxIn = motion.defaultEffectsSpec<Float>()
    val fxOut = motion.fastEffectsSpec<Float>()
    val popIn = motion.fastSpatialSpec<Float>()
    val cs = MaterialTheme.colorScheme
    // Как в Safari: плоская капсула, адрес по центру, замок рядом с ним, справа обновление.
    val barHeight = (if (wide) 40.dp else 44.dp) * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

    // Загрузка как в нижней панели телефона: заливка адресной строки с бегущим бликом и кольцо на кнопке «обновить».
    val progressState = remember { Animatable(0f) }
    LaunchedEffect(tab) {
        snapshotFlow { if (tab.home) 0f else if (tab.loading) tab.progress / 100f else 1f }.collectLatest { v ->
            if (v < progressState.value && tab.loading) progressState.snapTo(v) else progressState.animateTo(v, motion.defaultEffectsSpec<Float>())
        }
    }
    var fillOn by remember { mutableStateOf(false) }
    LaunchedEffect(tab.loading, tab.home) {
        if (tab.loading && !tab.home) {
            fillOn = true
        } else if (fillOn) {
            delay(450)
            fillOn = false
        }
    }
    val fillAlpha = animateFloatAsState(if (fillOn) 1f else 0f, motion.defaultEffectsSpec<Float>(), label = "barFillAlpha")

    val shieldIcon = if (tab.trust == Trust.SECURE) HripsIcons.TbShield else HripsIcons.TbInfo
    val shieldTint = if (tab.trust == Trust.WARNING) cs.error else cs.onSurfaceVariant

    @Composable
    fun AddressText(textModifier: Modifier) {
        AnimatedContent(
            targetState = shown,
            transitionSpec = {
                fadeIn(tween(160, delayMillis = 70)) togetherWith fadeOut(tween(90)) using
                    SizeTransform(clip = false) { _, _ -> snap() }
            },
            contentAlignment = Alignment.Center,
            modifier = textModifier,
            label = "barText",
        ) { text ->
            val style = MaterialTheme.typography.bodyLarge
            if (text.isEmpty()) {
                Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(HripsIcons.TbSearch, null, tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(2.dp))
                    Text("Искать или задать вопрос", maxLines = 1, overflow = TextOverflow.Ellipsis, style = style, color = cs.onSurfaceVariant)
                }
            } else {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = style,
                    color = cs.onSurface,
                )
            }
        }
    }

    @Composable
    fun ReloadButton() {
        BarButton(modifier = Modifier.size(40.dp), onClick = { tab.reloadOrStop() }, enabled = !tab.home) {
            AnimatedContent(
                targetState = tab.loading,
                transitionSpec = {
                    (fadeIn(fxIn) + scaleIn(popIn, initialScale = 0.6f)) togetherWith (fadeOut(fxOut) + scaleOut(popIn, targetScale = 0.6f)) using
                        SizeTransform(clip = false) { _, _ -> snap() }
                },
                label = "barReload",
            ) { loading ->
                when {
                    loading -> LoadRing(tab.progress / 100f, LocalContentColor.current)
                    else -> Icon(HripsIcons.TbRefresh, "Обновить")
                }
            }
        }
    }

    // Поля под кнопки слева и справа одинаковые, чтобы текст стоял ровно по центру капсулы.
    val sidePad = if (inline) (if (withPicker) 112.dp else 72.dp) else (if (withPicker) 76.dp else 44.dp)
    val swipePx = with(LocalDensity.current) { 36.dp.toPx() }

    @Composable
    fun BarBody(capsuleModifier: Modifier) {
        FlatCapsule(
            modifier = capsuleModifier.height(barHeight).originAnchor("search").then(
                if (onSwipeDown == null) Modifier else Modifier.pointerInput(onSwipeDown) {
                    var total = 0f
                    var fired = false
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f; fired = false },
                        onDragEnd = {},
                        onDragCancel = {},
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            total += dy
                            if (!fired && total > swipePx) {
                                fired = true
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onSwipeDown()
                            }
                        },
                    )
                },
            ),
            color = flatFill(),
        ) {
          Box(Modifier.fillMaxSize()) {
            if (fillOn || fillAlpha.value > 0.01f) LoadFill(progressState.asState(), fillAlpha, tab.loading, cs.onSurface)
            // Текст ровно по центру капсулы: слева и справа одинаковые поля под кнопки, сами кнопки лежат поверх.
            Box(
                Modifier.fillMaxSize().padding(horizontal = sidePad).combinedClickable(
                    onClickLabel = "Ввести запрос или адрес",
                    onLongClickLabel = "Действия с адресом",
                    onLongClick = openMenu,
                    onClick = onSearch,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AddressText(Modifier.weight(1f, fill = false))
                    if (inline && !withPicker) {
                        BarButton(onClick = { showSecurity = true }, modifier = Modifier.size(28.dp)) {
                            Icon(shieldIcon, "Безопасность соединения", Modifier.size(18.dp), tint = shieldTint)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (inline) {
                    BarButton(onClick = onCloseTab, modifier = Modifier.padding(start = 2.dp).size(36.dp)) {
                        Icon(HripsIcons.TbCloseSmall, "Закрыть вкладку")
                    }
                }
                if (withPicker) {
                    Box(Modifier.padding(start = 4.dp)) { EnginePicker(engine, onPickEngine) }
                } else if (inline) {
                    Spacer(Modifier.width(6.dp))
                    TabSiteIcon(tab, 18.dp, shape = CircleShape)
                } else {
                    BarButton(onClick = { showSecurity = true }, modifier = Modifier.size(40.dp)) {
                        Icon(shieldIcon, "Безопасность соединения", tint = shieldTint)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (tab.home) {
                    homeExtras?.invoke()
                    Spacer(Modifier.width(4.dp))
                } else {
                    ReloadButton()
                }
            }

            PillMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                hasPage = !tab.home,
                onCopy = onCopy,
                onShare = onShare,
                onPasteGo = onPasteGo,
                onCloseTab = onCloseTab,
                onCloseAll = onCloseAll,
            )
            if (showSecurity) SecurityDialog(tab) { showSecurity = false }
          }
        }
    }

        // Строка короткая (от 560 dp), текст по центру; когда адрес не помещается, строка растёт до края.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val label = shown.ifEmpty { "Искать или задать вопрос" }
        val textStyle = MaterialTheme.typography.bodyLarge
        val textDp = with(density) { measurer.measure(AnnotatedString(label), textStyle, maxLines = 1).size.width.toDp() }
        val chrome = sidePad * 2 + 8.dp + (if (shown.isEmpty()) 28.dp else 0.dp)
        val wanted by animateDpAsState(maxOf(560.dp, textDp + chrome), tween(220), label = "barWidth")
        val extraW by animateDpAsState(if (extras != null && showExtras) ExtrasWidth else 0.dp, tween(220), label = "barExtraW")
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            val availW = maxWidth
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarBody(Modifier.width(wanted.coerceAtMost(availW - extraW)))
                if (extras != null) {
                    AnimatedVisibility(
                        visible = showExtras,
                        enter = fadeIn(tween(160)) + expandHorizontally(tween(220)),
                        exit = fadeOut(tween(100)) + shrinkHorizontally(tween(220)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(8.dp))
                            extras()
                        }
                    }
                }
            }
        }
}

/** Вкладки того же контекста, что и [of]: приватные только с приватными, обычные только своей группы. */
internal fun Browser.contextTabs(of: Tab): List<Tab> =
    tabs.filter { it.isPrivate == of.isPrivate && (of.isPrivate || store.groupOf(it.id) == store.groupOf(of.id)) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabStrip(browser: Browser, above: Boolean = false) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settlingId by remember { mutableStateOf<String?>(null) }
    val settleJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val scope = rememberCoroutineScope()
    val release: () -> Unit = {
        val id = draggingId
        if (id != null) {
            draggingId = null
            settlingId = id
            settleJob[0]?.cancel()
            settleJob[0] = scope.launch {
                val a = androidx.compose.animation.core.Animatable(dragOffset)
                a.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { dragOffset = value }
                dragOffset = 0f
                if (settlingId == id) settlingId = null
            }
        }
    }

    // Только вкладки текущей группы (или только приватные): чужие группы в полосе не показываем.
    val shown = browser.contextTabs(browser.current)
    val seen = remember { shown.map { it.id }.toMutableSet() }
    val lastSize = remember { intArrayOf(shown.size) }

    LaunchedEffect(browser.current.id, shown.size) {
        seen.retainAll(shown.mapTo(HashSet()) { it.id })
        val grew = shown.size > lastSize[0]
        lastSize[0] = shown.size
        val index = shown.indexOfFirst { it.id == browser.current.id }
        if (index < 0) return@LaunchedEffect
        if (!grew) {
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (!visible) listState.animateScrollToItem(index)
            return@LaunchedEffect
        }
        repeat(30) {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
            if (item == null) {
                listState.animateScrollToItem(index)
            } else {
                val end = info.viewportEndOffset - info.afterContentPadding
                val start = info.viewportStartOffset + info.beforeContentPadding
                val delta = when {
                    item.offset + item.size > end -> item.offset + item.size - end
                    item.offset < start -> item.offset - start
                    else -> 0
                }
                if (delta != 0) listState.scrollBy(delta.toFloat())
            }
            withFrameNanos { }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (above) 4.dp else 0.dp, bottom = if (above) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(Modifier.weight(1f), state = listState, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(shown, key = { _, t -> t.id }) { i, t ->
                val id = t.id
                val grow = remember(id) { Animatable(if (seen.add(id)) 0f else 1f) }
                LaunchedEffect(id) {
                    if (grow.value < 1f) grow.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
                }
                val dragging = draggingId == id
                val held = dragging || settlingId == id
                val isCurrent = t.id == browser.current.id
                val tabColor by animateColorAsState(
                    if (isCurrent) flatFill() else Color.Transparent,
                    MaterialTheme.motionScheme.defaultEffectsSpec<Color>(),
                    label = "tabStripColor",
                )
                val lift by animateFloatAsState(if (dragging) 1.05f else 1f, MaterialTheme.motionScheme.fastSpatialSpec<Float>(), label = "tabLift")
                val shadow by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "tabShadow")
                Surface(
                    onClick = { browser.tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { browser.currentIndex = it } },
                    shape = RoundedCornerShape(9.dp),
                    color = tabColor,
                    shadowElevation = shadow,
                    modifier = Modifier
                        .height(30.dp).widthIn(min = 120.dp, max = 240.dp)
                        .semantics { this.selected = isCurrent }
                        .layout { m, c ->
                            val p = m.measure(c)
                            val w = (p.width * grow.value).roundToInt()
                            layout(w, p.height) { p.place(0, 0) }
                        }
                        .then(if (grow.value < 1f) Modifier.clipToBounds() else Modifier)
                        .then(if (held) Modifier.zIndex(1f) else Modifier.animateItem(fadeInSpec = null))
                        .graphicsLayer {
                            translationX = if (held) dragOffset else 0f
                            scaleX = lift
                            scaleY = lift
                            alpha = grow.value
                        }
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    settleJob[0]?.cancel()
                                    settlingId = null
                                    draggingId = id
                                    dragOffset = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.x
                                    val infos = listState.layoutInfo.visibleItemsInfo
                                    val me = infos.firstOrNull { it.key == id } ?: return@detectDragGesturesAfterLongPress
                                    val from = me.index
                                    if (shown.getOrNull(from)?.id != id) return@detectDragGesturesAfterLongPress
                                    val center = me.offset + me.size / 2f + dragOffset
                                    val target = infos.firstOrNull { it.key != id && center >= it.offset && center <= it.offset + it.size }
                                    if (target != null) {
                                        val fullFrom = browser.tabs.indexOfFirst { it.id == id }
                                        val fullTo = browser.tabs.indexOfFirst { it.id == target.key }
                                        if (fullFrom < 0 || fullTo < 0) return@detectDragGesturesAfterLongPress
                                        browser.moveTab(fullFrom, fullTo)
                                        val newOffset = if (target.index > from) target.offset + target.size - me.size else target.offset
                                        dragOffset += me.offset - newOffset
                                    }
                                },
                                onDragEnd = { release() },
                                onDragCancel = { release() },
                            )
                        },
                ) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (t.isPrivate) {
                            Icon(HripsIcons.Mask, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        } else if (t.home) {
                            Icon(HripsIcons.Home, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            TabSiteIcon(t, 16.dp, shape = CircleShape)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.label(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { browser.tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { browser.closeTab(it) } },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(HripsIcons.TbCloseSmall, "Закрыть вкладку: ${t.label()}")
                        }
                    }
                }
            }
        }
    }
}

/** Переставляет вкладку [id] на соседнее место в [shown] (индексы общего списка берутся по id). */
private fun Browser.swapNeighbor(shown: List<Tab>, id: String, forward: Boolean): Boolean {
    val i = shown.indexOfFirst { it.id == id }
    val other = shown.getOrNull(if (forward) i + 1 else i - 1) ?: return false
    val from = tabs.indexOfFirst { it.id == id }
    val to = tabs.indexOfFirst { it.id == other.id }
    if (from < 0 || to < 0) return false
    moveTab(from, to)
    return true
}

/** Вкладки слева: список текущей группы (или приватных) во всю высоту под шапкой и кнопка «Новая вкладка». Долгое нажатие и перетаскивание меняют порядок. */
@Composable
internal fun VerticalTabs(browser: Browser, modifier: Modifier = Modifier) {
    val shown = browser.contextTabs(browser.current)
    val shownNow by rememberUpdatedState(shown)
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val pitch = with(LocalDensity.current) { (40.dp + 2.dp).toPx() }
    Column(modifier.width(232.dp).fillMaxHeight().padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(shown, key = { it.id }) { t ->
                val id = t.id
                val isCurrent = id == browser.current.id
                val dragging = draggingId == id
                Surface(
                    onClick = { browser.tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { browser.currentIndex = it } },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isCurrent) flatFill() else Color.Transparent,
                    shadowElevation = if (dragging) 8.dp else 0.dp,
                    modifier = Modifier
                        .fillMaxWidth().height(40.dp)
                        .semantics { this.selected = isCurrent }
                        .then(if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingId = id
                                    dragOffset = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    if (dragOffset > pitch / 2 && browser.swapNeighbor(shownNow, id, forward = true)) dragOffset -= pitch
                                    else if (dragOffset < -pitch / 2 && browser.swapNeighbor(shownNow, id, forward = false)) dragOffset += pitch
                                },
                                onDragEnd = { draggingId = null; dragOffset = 0f },
                                onDragCancel = { draggingId = null; dragOffset = 0f },
                            )
                        },
                ) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (t.isPrivate) {
                            Icon(HripsIcons.Mask, null, Modifier.size(18.dp), tint = cs.primary)
                        } else if (t.home) {
                            Icon(HripsIcons.Home, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                        } else {
                            TabSiteIcon(t, 18.dp, shape = CircleShape)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            t.label(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isCurrent) cs.onSurface else cs.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { browser.tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { browser.closeTab(it) } },
                            modifier = Modifier.size(32.dp),
                        ) { Icon(HripsIcons.TbCloseSmall, "Закрыть вкладку: ${t.label()}") }
                    }
                }
            }
        }
        Surface(
            onClick = { browser.newTab(incognito = browser.current.isPrivate) },
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(HripsIcons.TbPlus, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text("Новая вкладка", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
        }
    }
}

/**
 * Компактные вкладки (как в Safari на iPad): вкладки и адрес в одной строке. Текущая вкладка - это сама адресная
 * строка ([address]), остальные - узкие пилюли со значком и названием; нажатие на пилюлю делает её адресной строкой.
 * Пилюли переставляются долгим нажатием и перетаскиванием. [showTabs] = false оставляет только адресную строку.
 */
@Composable
internal fun InlineTabsRow(browser: Browser, showTabs: Boolean, modifier: Modifier = Modifier, address: @Composable () -> Unit) {
    val shown = browser.contextTabs(browser.current)
    val shownNow by rememberUpdatedState(shown)
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scroll = rememberScrollState()
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val gap = 4.dp
    val barHeight = 40.dp * density.fontScale.coerceIn(1f, 1.6f)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val total = maxWidth
        val others = if (showTabs) (shown.size - 1).coerceAtLeast(0) else 0
        val activeW = if (others == 0) total else (total * 0.5f).coerceIn(300.dp, 520.dp).coerceAtMost(total)
        val otherW = if (others == 0) 0.dp else ((total - activeW - gap * others) / others).coerceIn(52.dp, 150.dp)
        val widths by rememberUpdatedState(activeW to otherW)
        LaunchedEffect(browser.current.id, shown.size, showTabs) {
            if (!showTabs) return@LaunchedEffect
            val idx = shown.indexOfFirst { it.id == browser.current.id }
            if (idx < 0 || scroll.maxValue <= 0) return@LaunchedEffect
            val x = with(density) { (otherW + gap).toPx() * idx }
            val view = with(density) { total.toPx() }
            scroll.animateScrollTo((x - (view - with(density) { activeW.toPx() }) / 2f).toInt().coerceIn(0, scroll.maxValue))
        }
        Row(
            Modifier.horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shown.forEach { t ->
                key(t.id) {
                    val id = t.id
                    val isCurrent = id == browser.current.id
                    if (isCurrent || showTabs) {
                        val w by animateDpAsState(if (isCurrent) activeW else otherW, MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.Dp>(), label = "inlineTabW")
                        val dragging = draggingId == id
                        if (isCurrent) {
                            Box(Modifier.width(w)) { address() }
                        } else {
                            Surface(
                                onClick = { browser.tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { browser.currentIndex = it } },
                                shape = CircleShape,
                                color = flatFill(),
                                shadowElevation = if (dragging) 8.dp else 0.dp,
                                modifier = Modifier
                                    .width(w).height(barHeight)
                                    .then(if (dragging) Modifier.zIndex(1f) else Modifier)
                                    .graphicsLayer { translationX = if (dragging) dragOffset else 0f }
                                    .pointerInput(id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingId = id
                                                dragOffset = 0f
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.x
                                                val list = shownNow
                                                val i = list.indexOfFirst { it.id == id }
                                                val forward = dragOffset > 0
                                                val neighbor = list.getOrNull(if (forward) i + 1 else i - 1) ?: return@detectDragGesturesAfterLongPress
                                                val (aw, ow) = widths
                                                val nw = if (neighbor.id == browser.current.id) aw else ow
                                                val pitch = with(density) { ((ow + nw) / 2 + gap).toPx() }
                                                if (kotlin.math.abs(dragOffset) > pitch / 2 && browser.swapNeighbor(list, id, forward)) {
                                                    dragOffset += if (forward) -pitch else pitch
                                                }
                                            },
                                            onDragEnd = { draggingId = null; dragOffset = 0f },
                                            onDragCancel = { draggingId = null; dragOffset = 0f },
                                        )
                                    },
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (t.isPrivate) {
                                        Icon(HripsIcons.Mask, null, Modifier.size(18.dp), tint = cs.primary)
                                    } else if (t.home) {
                                        Icon(HripsIcons.Home, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                                    } else {
                                        TabSiteIcon(t, 18.dp, shape = CircleShape)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        t.label(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Clip,
                                        softWrap = false,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = cs.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TabCounterButton(count: Int, modifier: Modifier = Modifier, tablet: Boolean = false, onClick: () -> Unit) {
    val fadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val slideSpec = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val bump = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(count) {
        if (first) { first = false; return@LaunchedEffect }
        bump.snapTo(1f)
        bump.animateTo(1.16f, tween(80))
        bump.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
    }
    BarButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Вкладки: $count" },
    ) {
        val color = LocalContentColor.current
        Box(
            Modifier.size(if (tablet) 20.dp else 22.dp).graphicsLayer { scaleX = bump.value; scaleY = bump.value }
                .border(if (tablet) 2.dp else 1.8.dp, color, if (tablet) RoundedCornerShape(6.dp) else HripsShapes.IconFrame),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    val up = targetState > initialState
                    (fadeIn(fadeSpec) + slideInVertically(slideSpec) { if (up) it else -it }) togetherWith
                        (fadeOut(fadeSpec) + slideOutVertically(slideSpec) { if (up) -it else it }) using
                        SizeTransform(clip = true) { _, _ -> snap() }
                },
                label = "tabCount",
            ) { n ->
                val digitSize = with(LocalDensity.current) { (when { n > 99 -> 8.dp; n > 9 -> 9.dp; else -> 10.dp }).toSp() }
                Text(
                    if (n > 99) "99+" else n.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = digitSize, fontWeight = FontWeight.SemiBold),
                    color = color,
                )
            }
        }
    }
}


/**
 * Шапка планшета в цвете сайта. [site] null: ничего не меняется. Иначе вся шапка (полоса вкладок, кнопки, адресная строка)
 * получает свою цветовую схему: фон цвета сайта, текст и значки чёрные или белые по яркости фона, подложки кнопок и
 * адресная строка чуть темнее на светлом сайте и чуть светлее на тёмном. Цвет меняется плавно.
 */
@Composable
internal fun SiteHeaderTheme(site: Color?, content: @Composable () -> Unit) {
    if (site == null) {
        content()
        return
    }
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(site, tween(250), label = "siteHeaderBg")
    val dark = bg.luminance() < 0.5f
    val fg = siteInk(bg, dark)
    val scheme = cs.copy(
        surface = bg,
        background = bg,
        onSurface = fg,
        onBackground = fg,
        onSurfaceVariant = fg.copy(alpha = 0.72f),
        surfaceContainerLowest = lerp(bg, fg, 0.02f),
        surfaceContainerLow = lerp(bg, fg, 0.05f),
        surfaceContainer = lerp(bg, fg, 0.08f),
        surfaceContainerHigh = lerp(bg, fg, 0.12f),
        surfaceContainerHighest = lerp(bg, fg, 0.18f),
        primary = fg,
        onPrimary = bg,
        secondaryContainer = lerp(bg, fg, 0.18f),
    )
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MaterialTheme.motionScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            Box(Modifier.fillMaxWidth().background(bg)) { content() }
        }
    }
}

/** Плоская тональная подложка: чуть темнее светлой шапки и чуть светлее тёмной. Без границ, бликов и теней. */
@Composable
internal fun flatFill(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

@Composable
internal fun FlatCapsule(modifier: Modifier = Modifier, shape: Shape = CircleShape, color: Color = flatFill(), content: @Composable () -> Unit) {
    Surface(modifier = modifier, shape = shape, color = color, content = content)
}

/** Группа кнопок планшета на плоской подложке. */
@Composable
internal fun ToolbarGroup(
    horizontalPadding: androidx.compose.ui.unit.Dp = 2.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val rowModifier = Modifier.height(40.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)).padding(horizontal = horizontalPadding)
    FlatCapsule { Row(rowModifier, verticalAlignment = Alignment.CenterVertically, content = content) }
}

/**
 * Цвет текста и значков шапки: не просто чёрный или белый, а оттенок самого сайта. На светлом фоне цвет тёмный
 * того же тона (кремовый сайт даёт коричневые значки), на тёмном светлый и слегка подкрашенный. Нейтральный
 * серый или белый фон остаётся с обычным чёрным или белым. Яркость подбирается так, чтобы контраст был не ниже 5:1.
 */
internal fun siteInk(bg: Color, dark: Boolean): Color {
    // Оттенок подмешивается лишь частично: сильная подкраска значков на ярких сайтах смотрится хуже, чем обычный чёрный или белый.
    val neutral = if (dark) Color(0xFFF3F3F3) else Color(0xFF1A1A1A)
    return lerp(neutral, siteInkRaw(bg, dark), 0.4f)
}

private fun siteInkRaw(bg: Color, dark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(bg.toArgb(), hsv)
    val k = (hsv[1] / 0.12f).coerceIn(0f, 1f)          // 0 у серого, 1 у заметно цветного фона
    fun contrast(a: Float, b: Float) = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    val bgLum = bg.luminance()
    if (dark) {
        hsv[1] = k * minOf(hsv[1], 0.28f)
        hsv[2] = 0.95f
        var ink = Color(android.graphics.Color.HSVToColor(hsv))
        var guard = 0
        while (contrast(ink.luminance(), bgLum) < 5f && hsv[2] < 1f && guard++ < 8) {
            hsv[2] = minOf(1f, hsv[2] + 0.02f)
            ink = Color(android.graphics.Color.HSVToColor(hsv))
        }
        return ink
    }
    hsv[1] = k * minOf(0.85f, hsv[1] * 0.6f + 0.4f)
    hsv[2] = 0.26f
    var ink = Color(android.graphics.Color.HSVToColor(hsv))
    var guard = 0
    while (contrast(ink.luminance(), bgLum) < 5f && hsv[2] > 0.04f && guard++ < 12) {
        hsv[2] = maxOf(0.04f, hsv[2] - 0.03f)
        ink = Color(android.graphics.Color.HSVToColor(hsv))
    }
    // Нейтральный фон: обычный почти чёрный, как раньше.
    return if (k == 0f) Color(0xFF1A1A1A) else ink
}
