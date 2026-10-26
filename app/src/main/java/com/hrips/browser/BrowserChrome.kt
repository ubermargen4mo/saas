@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
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

@Composable
internal fun AddressBar(
    tab: Tab,
    onSearch: () -> Unit,
    engine: SearchEngine,
    query: String?,
    onPickEngine: (SearchEngine) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    classic: Boolean = false,
) {
    val withPicker = tab.home || query != null
    var showSecurity by remember { mutableStateOf(false) }
    val shown = when {
        tab.home -> ""
        query != null -> query
        else -> tab.url   // полный адрес, вместе со схемой (https:// и т. д.)
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

    FlatCapsule(
        modifier = modifier.height(barHeight).originAnchor("search"),
        color = if (classic) cs.surfaceContainerHigh else flatFill(),
    ) {
      Box(Modifier.fillMaxSize()) {
        if (!classic && (fillOn || fillAlpha.value > 0.01f)) LoadFill(progressState.asState(), fillAlpha, tab.loading, cs.onSurface)
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            if (withPicker) {
                Box(Modifier.padding(start = 4.dp)) { EnginePicker(engine, onPickEngine) }
            } else if (classic) {
                BarButton(onClick = { showSecurity = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (tab.trust == Trust.SECURE) HripsIcons.BarShield else HripsIcons.BarInfo,
                        "Безопасность соединения",
                        tint = if (tab.trust == Trust.WARNING) cs.error else cs.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.width(36.dp))
            }
            Row(
                Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = "Ввести запрос или адрес") { onSearch() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (classic) Arrangement.Start else Arrangement.Center,
            ) {
                if (!withPicker && !classic) {
                    Box(Modifier.size(24.dp).clickable(onClickLabel = "Безопасность соединения") { showSecurity = true }, contentAlignment = Alignment.Center) {
                        Icon(
                            if (tab.trust == Trust.SECURE) HripsIcons.BarShield else HripsIcons.BarInfo,
                            "Безопасность соединения",
                            Modifier.size(18.dp),
                            tint = if (tab.trust == Trust.WARNING) cs.error else cs.onSurfaceVariant,
                        )
                    }
                }
                AnimatedContent(
                    targetState = shown,
                    transitionSpec = {
                        fadeIn(tween(160, delayMillis = 70)) togetherWith fadeOut(tween(90)) using
                            SizeTransform(clip = false) { _, _ -> snap() }
                    },
                    contentAlignment = if (classic) Alignment.CenterStart else Alignment.Center,
                    modifier = Modifier.weight(1f, fill = false),
                    label = "barText",
                ) { text ->
                    Text(
                        text.ifEmpty { "Искать или задать вопрос" },
                        modifier = if (classic) Modifier.padding(start = if (withPicker) 8.dp else 4.dp) else Modifier.padding(horizontal = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (classic) TextAlign.Start else TextAlign.Center,
                        style = if (classic) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = if (text.isEmpty()) cs.onSurfaceVariant else cs.onSurface,
                    )
                }
            }
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
                        loading && classic -> Icon(HripsIcons.BarClose, "Остановить")
                        loading -> LoadRing(tab.progress / 100f, LocalContentColor.current)
                        classic -> Icon(HripsIcons.BarRefresh, "Обновить")
                        else -> Icon(HripsIcons.BarRefresh, "Обновить", Modifier.size(18.dp))
                    }
                }
            }
            if (showSecurity) SecurityDialog(tab) { showSecurity = false }
        }
      }
    }
}

/** Вкладки того же контекста, что и [of]: приватные только с приватными, обычные только своей группы. */
internal fun Browser.contextTabs(of: Tab): List<Tab> =
    tabs.filter { it.isPrivate == of.isPrivate && (of.isPrivate || store.groupOf(it.id) == store.groupOf(of.id)) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabStrip(browser: Browser, classic: Boolean = false) {
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
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = if (classic) 4.dp else 0.dp, bottom = if (classic) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(Modifier.weight(1f, fill = !classic), state = listState, horizontalArrangement = Arrangement.spacedBy(if (classic) 4.dp else 2.dp)) {
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
                    if (isCurrent) (if (classic) MaterialTheme.colorScheme.surfaceContainerHighest else flatFill()) else Color.Transparent,
                    MaterialTheme.motionScheme.defaultEffectsSpec<Color>(),
                    label = "tabStripColor",
                )
                val lift by animateFloatAsState(if (dragging) 1.05f else 1f, MaterialTheme.motionScheme.fastSpatialSpec<Float>(), label = "tabLift")
                val shadow by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "tabShadow")
                Surface(
                    onClick = { browser.tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { browser.currentIndex = it } },
                    shape = if (classic) CircleShape else RoundedCornerShape(9.dp),
                    color = tabColor,
                    shadowElevation = shadow,
                    modifier = Modifier
                        .height(if (classic) 40.dp else 30.dp).widthIn(min = 120.dp, max = if (classic) 220.dp else 240.dp)
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
                            TabSiteIcon(t, if (classic) 18.dp else 16.dp, shape = CircleShape)
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
                            modifier = Modifier.size(if (classic) 40.dp else 28.dp),
                        ) {
                            Icon(HripsIcons.Close, "Закрыть вкладку: ${t.label()}", Modifier.size(if (classic) 16.dp else 14.dp))
                        }
                    }
                }
            }
        }
        if (classic) {
            BarButton(modifier = Modifier.size(40.dp), onClick = { browser.newTab(incognito = browser.current.isPrivate) }) { Icon(HripsIcons.Add, "Новая вкладка") }
        }
    }
}

@Composable
fun TabCounterButton(count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
            Modifier.size(22.dp).graphicsLayer { scaleX = bump.value; scaleY = bump.value }.border(1.8.dp, color, HripsShapes.IconFrame),
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
    val fg = if (dark) Color(0xFFF3F3F3) else Color(0xFF1A1A1A)
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
    classic: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val rowModifier = Modifier.height(40.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)).padding(horizontal = horizontalPadding)
    if (classic) {
        // Классическая шапка: кнопки без подложек.
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically, content = content)
    } else {
        FlatCapsule { Row(rowModifier, verticalAlignment = Alignment.CenterVertically, content = content) }
    }
}
