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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

@Composable
internal fun AddressBar(
    tab: Tab,
    store: Store,
    onSearch: () -> Unit,
    engine: SearchEngine,
    query: String?,
    onPickEngine: (SearchEngine) -> Unit,
    onMenu: () -> Unit,
    onExtensions: () -> Unit,
    showMenu: Boolean,
    downloads: Downloads,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    // Логотип движка с выбором: на главной и на странице выдачи. На обычных сайтах - замок и адрес.
    val withPicker = tab.home || query != null
    var showSecurity by remember { mutableStateOf(false) }
    val shown = when {
        tab.home -> ""
        query != null -> query
        else -> tab.url.removePrefix("https://")
    }
    val motion = MaterialTheme.motionScheme
    val fxIn = motion.defaultEffectsSpec<Float>()
    val fxOut = motion.fastEffectsSpec<Float>()
    val popIn = motion.fastSpatialSpec<Float>()
    run {
        // Единый вид (планшет и телефон): контурный щит слева, адрес, кнопка «Обновить» справа внутри строки
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.heightIn(min = if (wide) 40.dp else 44.dp).originAnchor("search"),
        ) {
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                // Логотип движка <-> замок: плавная смена при переходе между главной/выдачей и сайтом
                AnimatedContent(
                    targetState = withPicker,
                    transitionSpec = {
                        (fadeIn(fxIn) + scaleIn(popIn, initialScale = 0.7f)) togetherWith (fadeOut(fxOut) + scaleOut(popIn, targetScale = 0.7f)) using
                            SizeTransform(clip = false) { _, _ -> snap() }
                    },
                    label = "barLeading",
                ) { picker ->
                    if (picker) {
                        Box(Modifier.padding(start = 4.dp)) { EnginePicker(engine, onPickEngine) }
                    } else {
                        BarButton(onClick = { showSecurity = true }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (tab.trust == Trust.SECURE) HripsIcons.BarShield else HripsIcons.BarInfo,
                                "Безопасность соединения",
                                tint = if (tab.trust == Trust.WARNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSearch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Адрес сменился (новая страница, поиск): старый текст гаснет, новый проявляется следом, без прыжка ширины
                    AnimatedContent(
                        targetState = shown,
                        transitionSpec = {
                            fadeIn(tween(160, delayMillis = 70)) togetherWith fadeOut(tween(90)) using
                                SizeTransform(clip = false) { _, _ -> snap() }
                        },
                        contentAlignment = Alignment.CenterStart,
                        label = "barText",
                    ) { text ->
                        Text(
                            text.ifEmpty { "Искать или задать вопрос" },
                            modifier = Modifier.padding(start = if (withPicker) 8.dp else 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                BarButton(modifier = Modifier.size(40.dp), onClick = { tab.reloadOrStop() }, enabled = !tab.home) {
                    // Обновить <-> остановить: значок подменяется со сжатием и проявлением, а не мигает
                    AnimatedContent(
                        targetState = tab.loading,
                        transitionSpec = {
                            (fadeIn(fxIn) + scaleIn(popIn, initialScale = 0.6f)) togetherWith (fadeOut(fxOut) + scaleOut(popIn, targetScale = 0.6f)) using
                                SizeTransform(clip = false) { _, _ -> snap() }
                        },
                        label = "barReload",
                    ) { loading ->
                        Icon(if (loading) HripsIcons.BarClose else HripsIcons.BarRefresh, "Обновить")
                    }
                }
                if (showSecurity) SecurityDialog(tab) { showSecurity = false }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabStrip(browser: Browser) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    // Перетаскивание вкладки: долгое нажатие, затем движение влево/вправо
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Отпущенная вкладка не прыгает на место, а садится пружиной: пока садится, она ещё «в руке» (свой слой, без animateItem)
    var settlingId by remember { mutableStateOf<String?>(null) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val release: () -> Unit = {
        val id = draggingId
        if (id != null) {
            draggingId = null
            settlingId = id
            settleJob?.cancel()
            settleJob = scope.launch {
                val a = androidx.compose.animation.core.Animatable(dragOffset)
                a.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { dragOffset = value }
                dragOffset = 0f
                if (settlingId == id) settlingId = null
            }
        }
    }

    // При переключении вкладки автоматически возвращаем выбранную вкладку в видимую область.
    LaunchedEffect(browser.current.id, browser.tabs.size) {
        val index = browser.tabs.indexOfFirst { it.id == browser.current.id }
        if (index >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (!visible) listState.animateScrollToItem(index)
        }
    }

    // Высота полосы 44dp (4 сверху, 0 снизу): строка с адресом встаёт ровно туда, где она в Опере
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f, fill = false), state = listState, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(browser.tabs, key = { _, t -> t.id }) { i, t ->
                val id = t.id
                val dragging = draggingId == id
                val held = dragging || settlingId == id
                val selected = i == browser.currentIndex
                val tabColor by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                    MaterialTheme.motionScheme.defaultEffectsSpec<Color>(),
                    label = "tabStripColor",
                )
                // Взятая вкладка приподнимается: чуть крупнее и с тенью
                val lift by animateFloatAsState(if (dragging) 1.05f else 1f, MaterialTheme.motionScheme.fastSpatialSpec<Float>(), label = "tabLift")
                val shadow by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "tabShadow")
                Surface(
                    onClick = { browser.currentIndex = i },
                    shape = CircleShape,
                    color = tabColor,
                    shadowElevation = shadow,
                    modifier = Modifier
                        .height(36.dp).widthIn(min = 120.dp, max = 220.dp)
                        .then(if (held) Modifier.zIndex(1f) else Modifier.animateItem())
                        .graphicsLayer { translationX = if (held) dragOffset else 0f; scaleX = lift; scaleY = lift }
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    settleJob?.cancel()
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
                                    val from = browser.tabs.indexOfFirst { it.id == id }
                                    // Раскладка ещё не обновилась после прошлой перестановки: ждём
                                    if (from < 0 || me.index != from) return@detectDragGesturesAfterLongPress
                                    val center = me.offset + me.size / 2f + dragOffset
                                    val target = infos.firstOrNull { it.key != id && center >= it.offset && center <= it.offset + it.size }
                                    if (target != null) {
                                        browser.moveTab(from, target.index)
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
                            // У приватных вкладок иконку сайта не загружаем и не кэшируем
                            Icon(HripsIcons.Mask, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        } else if (t.home) {
                            Icon(HripsIcons.Home, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Favicon(t.url, 18.dp) {
                                SiteTile(t.url, t.label(), 18.dp, shape = CircleShape)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.label(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { browser.closeTab(i) }, modifier = Modifier.size(30.dp)) {
                            Icon(HripsIcons.Close, "Закрыть", Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        BarButton(modifier = Modifier.size(40.dp), onClick = { browser.newTab(incognito = browser.current.isPrivate) }) { Icon(HripsIcons.Add, "Новая вкладка") }
    }
}

/**
 * Счётчик вкладок: скруглённый квадрат-контур с числом внутри. Нажатие открывает список вкладок.
 * Число сменяется по вертикали, как на счётчике (больше - снизу вверх, меньше - сверху вниз), а сама рамка
 * упруго «подпрыгивает», когда вкладка добавилась или закрылась.
 */
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
            // Размер и толщина линии как у остальных значков панели (контур 18 из 24, линия 1.8)
            Modifier.size(20.dp).graphicsLayer { scaleX = bump.value; scaleY = bump.value }.border(1.8.dp, color, RoundedCornerShape(6.dp)),
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
                // Цифра внутри значка - часть картинки: размер в dp, чтобы при крупном шрифте системы она не вылезала из рамки
                val digitSize = with(LocalDensity.current) { (if (n > 9) 9.dp else 10.dp).toSp() }
                Text(
                    if (n > 99) ":)" else n.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = digitSize, fontWeight = FontWeight.SemiBold),
                    color = color,
                )
            }
        }
    }
}

