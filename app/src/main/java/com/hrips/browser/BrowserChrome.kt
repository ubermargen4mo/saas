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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

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
    if (wide) {
        // Планшетный вид: контурный щит слева, адрес, кнопка «Обновить» справа внутри строки
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.height(40.dp),
        ) {
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                if (withPicker) {
                    Box(Modifier.padding(start = 4.dp)) { EnginePicker(engine, onPickEngine) }
                } else {
                    IconButton(onClick = { showSecurity = true }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (tab.trust == Trust.SECURE) HripsIcons.Shield else HripsIcons.Info,
                            "Безопасность соединения",
                            tint = if (tab.trust == Trust.WARNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSearch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        shown.ifEmpty { "Искать или задать вопрос" },
                        modifier = Modifier.padding(start = if (withPicker) 8.dp else 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (shown.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(modifier = Modifier.size(40.dp), onClick = { tab.reloadOrStop() }, enabled = !tab.home) {
                    Icon(if (tab.loading) HripsIcons.Close else HripsIcons.Refresh, "Обновить")
                }
                if (showSecurity) SecurityDialog(tab) { showSecurity = false }
            }
        }
        return
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // Фиксированная высота: на сайтах и на главной строка выглядит одинаково
        modifier = modifier.height(48.dp),
    ) {
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            if (withPicker) Box(Modifier.padding(start = 4.dp)) { EnginePicker(engine, onPickEngine) }
            // Нажатие на текст открывает поисковую панель (ввод, подсказки, история)
            Row(
                Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSearch),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!withPicker) {
                    // Иконка берётся из проверки сертификата движком, а не из того, с чего начинается адрес.
                    // Отдельная кнопка сохраняет тот же визуальный размер значка, но даёт нормальную touch-area.
                    val secureNow = tab.trust == Trust.SECURE
                    IconButton(
                        onClick = { showSecurity = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            if (secureNow) HripsIcons.Lock else HripsIcons.Info,
                            "Безопасность соединения",
                            Modifier.size(18.dp),
                            tint = if (tab.trust == Trust.WARNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    shown.ifEmpty { "Искать или задать вопрос" },
                    modifier = Modifier.padding(start = if (withPicker) 8.dp else 12.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (shown.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(modifier = Modifier.size(40.dp), onClick = { store.toggleBookmark(tab.url, tab.title) }, enabled = !tab.home) {
                val saved = store.isBookmarked(tab.url)
                Icon(
                    if (saved) HripsIcons.StarFilled else HripsIcons.Star, "Закладка",
                    tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showSecurity) SecurityDialog(tab) { showSecurity = false }
            // Пазл расширений стоит прямо перед меню
            if (showMenu) IconButton(modifier = Modifier.size(40.dp), onClick = onExtensions) { Icon(HripsIcons.Puzzle, "Расширения") }
            // На телефоне отдельной кнопки загрузок нет: иконка летит к меню, кольцо рисуется вокруг него
            if (showMenu) DownloadsButton(downloads, onClick = onMenu, icon = HripsIcons.MoreVert, description = "Инструменты", size = 40.dp)
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

    // При переключении вкладки автоматически возвращаем выбранную вкладку в видимую область.
    LaunchedEffect(browser.current.id, browser.tabs.size) {
        val index = browser.tabs.indexOfFirst { it.id == browser.current.id }
        if (index >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (!visible) listState.animateScrollToItem(index)
        }
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f, fill = false), state = listState, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(browser.tabs, key = { _, t -> t.id }) { i, t ->
                val id = t.id
                val dragging = draggingId == id
                val selected = i == browser.currentIndex
                Surface(
                    onClick = { browser.currentIndex = i },
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                    shadowElevation = if (dragging) 8.dp else 0.dp,
                    modifier = Modifier
                        .height(36.dp).widthIn(min = 120.dp, max = 220.dp)
                        .then(if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
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
                                onDragEnd = { draggingId = null; dragOffset = 0f },
                                onDragCancel = { draggingId = null; dragOffset = 0f },
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
        IconButton(modifier = Modifier.size(40.dp), onClick = { browser.newTab(incognito = browser.current.isPrivate) }) { Icon(HripsIcons.Add, "Новая вкладка") }
    }
}

/** Счётчик вкладок: скруглённый квадрат-контур с числом внутри. Нажатие открывает список вкладок. */
@Composable
fun TabCounterButton(count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Вкладки: $count" },
    ) {
        val color = LocalContentColor.current
        Box(
            // Размер и толщина линии как у остальных значков панели (контур 18 из 24, линия 1.8)
            Modifier.size(20.dp).border(1.8.dp, color, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = count,
                transitionSpec = { (fadeIn(tween(150)) + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut(tween(100)) + scaleOut(targetScale = 0.6f)) },
                label = "tabCount",
            ) { n ->
                Text(
                    if (n > 99) ":)" else n.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = if (n > 9) 9.sp else 11.sp, fontWeight = FontWeight.SemiBold),
                    color = color,
                )
            }
        }
    }
}

