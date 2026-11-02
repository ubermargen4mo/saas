@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hrips.browser

import androidx.compose.material3.toShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect as UiRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp as lerpF

@Composable
internal fun SwitcherHeader(
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
                Spacer(Modifier.size(SWITCHER_TOP_H))
            }
        }
    }
}

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
            BarButton(onClick = { menu = true }) { Icon(HripsIcons.MoreVert, "Ещё") }
        }
        SegMenuPopup(
            visible = menu,
            onDismiss = { menu = false },
            position = rememberBelowStartMenuPosition(),
            origin = TransformOrigin(0f, 0f),
        ) {
            val act: (() -> Unit) -> () -> Unit = { action -> { menu = false; action() } }
            val views = listOf(
                Triple(0, "Список", HripsIcons.Rows),
                Triple(1, "Сетка", HripsIcons.Grid),
                Triple(2, "Карточки", HripsIcons.Layers),
            ).map { (v, label, icon) ->
                MenuEntry(icon, label, checked = v == view, onClick = act { onView(v) })
            }
            val second = buildList {
                add(MenuEntry(HripsIcons.History, "История", onClick = act(onHistory)))
                if (!privatePage) {
                    add(
                        MenuEntry(
                            HripsIcons.History,
                            if (closedCount > 0) "Недавно закрытые ($closedCount)" else "Недавно закрытые",
                            enabled = closedCount > 0,
                            onClick = act(onClosed),
                        ),
                    )
                }
            }
            val third = listOf(
                MenuEntry(
                    HripsIcons.Trash,
                    if (privatePage) "Закрыть приватные вкладки" else "Закрыть все вкладки",
                    danger = true,
                    onClick = act(onCloseAll),
                ),
            )
            SegMenuColumn(listOf(views, second, third))
        }
    }
}

@Composable
internal fun EmptyTabs(priv: Boolean, searching: Boolean, onNew: () -> Unit, modifier: Modifier = Modifier.fillMaxSize().padding(bottom = 104.dp)) {
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
internal fun SwitcherBar(
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
            BarPill(
                palette,
                Modifier
                    .morphFrom(from, s, reveal)
                    .graphicsLayer { alpha = if (s == null || from == null) 0f else 1f - stage(reveal(), 0.5f, 0.85f) },
                pressHost = false,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (from != null && fromText.isNotEmpty()) {
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

@Composable
internal fun SelectionHeader(
    count: Int,
    privatePage: Boolean,
    linksCount: Int,
    allPinned: Boolean,
    store: Store,
    groupId: String,
    onDone: () -> Unit,
    onClose: () -> Unit,
    onPin: (Boolean) -> Unit,
    onCopyLinks: () -> Unit,
    onMove: (String) -> Unit,
    onNewGroup: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val palette = rememberBarPalette()
    var moveMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val has = count > 0
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarPill(palette, Modifier.height(SWITCHER_TOP_H)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarButton(onClick = onClose, enabled = has, wrap = true) {
                    Text(
                        "Закрыть",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (has) cs.error else LocalContentColor.current,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                if (!privatePage) {
                    Box {
                        BarButton(onClick = { moveMenu = true }, enabled = has, wrap = true) {
                            Text("Переместить", style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp))
                        }
                        SegMenuPopup(
                            visible = moveMenu,
                            onDismiss = { moveMenu = false },
                            position = rememberBelowStartMenuPosition(),
                            origin = TransformOrigin(0f, 0f),
                        ) {
                            val entries = groupPickerEntries(
                                store = store,
                                current = groupId,
                                onBack = {},
                                onPick = { g -> moveMenu = false; onMove(g) },
                                onNew = { moveMenu = false; onNewGroup() },
                            ).drop(1)
                            SegMenuColumn(entries)
                        }
                    }
                }
                Box {
                    BarButton(onClick = { moreMenu = true }, enabled = has) { Icon(HripsIcons.BarMoreH, "Ещё") }
                    SegMenuPopup(
                        visible = moreMenu,
                        onDismiss = { moreMenu = false },
                        position = rememberBelowStartMenuPosition(),
                        origin = TransformOrigin(0f, 0f),
                    ) {
                        val act: (() -> Unit) -> () -> Unit = { action -> { moreMenu = false; action() } }
                        val entries = buildList {
                            if (!privatePage) {
                                add(
                                    MenuEntry(
                                        if (allPinned) HripsIcons.TabUnpin else HripsIcons.TabPin,
                                        (if (allPinned) "Открепить вкладки" else "Закрепить вкладки") + " ($count)",
                                        onClick = act { onPin(!allPinned) },
                                    ),
                                )
                            }
                            add(
                                MenuEntry(
                                    HripsIcons.Link,
                                    "Копировать ссылки ($linksCount)",
                                    enabled = linksCount > 0,
                                    onClick = act(onCopyLinks),
                                ),
                            )
                        }
                        SegMenuColumn(listOf(entries))
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(SWITCHER_TOP_H)
                .clip(CircleShape)
                .background(cs.primary)
                .clickable {
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                    onDone()
                }
                .semantics { contentDescription = "Готово" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(HripsIcons.Check, null, Modifier.size(24.dp), tint = cs.onPrimary)
        }
    }
}
