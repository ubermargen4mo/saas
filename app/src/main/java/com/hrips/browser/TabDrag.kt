@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hrips.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyListState

@Composable
internal fun Modifier.tabHold(env: SwitcherEnv, tab: Tab, holder: CoordsHolder, onMoved: (() -> Unit)?, onClose: (() -> Unit)? = null): Modifier {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val movedNow by rememberUpdatedState(onMoved)
    val tabNow by rememberUpdatedState(tab)
    val id = tab.id
    return this.secondaryClick(enabled = !env.selecting) {
        env.menuRect = holder.rect()
        env.menuTab = tabNow
    }.middleClick(enabled = onClose != null && !env.selecting) { onClose?.invoke() }.pointerInput(id) {
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

internal fun reorderInList(state: LazyListState, env: SwitcherEnv, id: String) {
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

internal fun reorderInGrid(state: LazyGridState, env: SwitcherEnv, id: String) {
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

@Composable
internal fun DragAutoScroll(
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

@Composable
internal fun SelectBadge(on: Boolean, modifier: Modifier = Modifier) {
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

@Composable
internal fun TabHoldMenu(
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
