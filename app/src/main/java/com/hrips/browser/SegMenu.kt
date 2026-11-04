package com.hrips.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

internal class MenuEntry(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val submenu: Boolean = false,
    val checked: Boolean = false,
    val onClick: () -> Unit,
)

internal object SegMenuSpec {
    val Width = 231.dp
    val RowHeight = 36.dp
    val ItemGap = 3.dp
    val Big = 18.dp
    val Small = 6.dp
    val PlatePad = 8.dp
    val PlateRadius = 26.dp
    val GroupGap = 8.dp
    val ShadowPad = 14.dp
    val AnchorGap = 15.dp
    val ScreenMargin = 12.dp
}

@Composable
private fun SegRow(e: MenuEntry, corners: Corners) {
    val cs = MaterialTheme.colorScheme
    PressSurface(
        onClick = e.onClick,
        corners = corners,
        color = cs.surfaceContainerHigh,
        contentColor = if (e.danger) cs.error else cs.onSurface,
        pressedCorner = SegMenuSpec.Big,
        pressScale = 0.985f,
        modifier = Modifier.fillMaxWidth().height(SegMenuSpec.RowHeight).alpha(if (e.enabled) 1f else 0.4f),
        enabled = e.enabled,
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(e.icon, null, Modifier.size(20.dp), tint = if (e.danger) cs.error else cs.onSurface)
            Spacer(Modifier.width(12.dp))
            Text(
                e.label,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            )
            if (e.checked) {
                Icon(HripsIcons.Check, null, Modifier.size(18.dp), tint = cs.primary)
            } else if (e.submenu) {
                Icon(HripsIcons.ChevronForward.variant(1.8f, 12f / 13f), null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SegMenuColumn(groups: List<List<MenuEntry>>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(SegMenuSpec.PlateRadius),
        color = cs.surfaceContainer,
        contentColor = cs.onSurface,
        shadowElevation = 8.dp,
        border = BorderStroke(0.5.dp, cs.onSurface.copy(alpha = 0.10f)),
    ) {
        Column(
            Modifier.width(SegMenuSpec.Width).heightIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(SegMenuSpec.PlatePad),
            verticalArrangement = Arrangement.spacedBy(SegMenuSpec.GroupGap),
        ) {
            groups.filter { it.isNotEmpty() }.forEach { g ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SegMenuSpec.ItemGap)) {
                    g.forEachIndexed { i, e ->
                        SegRow(e, segCorners(g.size, 1, i, SegMenuSpec.Big, SegMenuSpec.Small))
                    }
                }
            }
        }
    }
}

private class PlateMenuPosition(
    private val margin: Int,
    private val pad: Int,
    private val place: (anchor: IntRect, w: Int, h: Int, clampY: (Int) -> Int) -> IntOffset,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width - pad * 2
        val h = popupContentSize.height - pad * 2
        val maxX = (windowSize.width - w - margin).coerceAtLeast(margin)
        val maxY = (windowSize.height - h - margin).coerceAtLeast(margin)
        val p = place(anchorBounds, w, h) { it.coerceIn(margin, maxY) }
        return IntOffset(p.x.coerceIn(margin, maxX) - pad, p.y - pad)
    }
}

@Composable
private fun rememberPlatePosition(
    gap: Dp,
    key: Any?,
    place: (anchor: IntRect, w: Int, h: Int, gap: Int, margin: Int, clampY: (Int) -> Int) -> IntOffset,
): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density, key) {
        with(density) {
            val g = gap.roundToPx()
            val margin = SegMenuSpec.ScreenMargin.roundToPx()
            PlateMenuPosition(margin, SegMenuSpec.ShadowPad.roundToPx()) { a, w, h, clampY -> place(a, w, h, g, margin, clampY) }
        }
    }
}

@Composable
internal fun rememberAnchorMenuPosition(): PopupPositionProvider =
    rememberPlatePosition(SegMenuSpec.AnchorGap, null) { a, w, h, gap, margin, clampY ->
        val above = a.top - gap - h
        IntOffset(a.center.x - w / 2, if (above >= margin) above else clampY(a.bottom + gap))
    }

@Composable
internal fun rememberBelowStartMenuPosition(): PopupPositionProvider =
    rememberPlatePosition(8.dp, null) { a, _, _, gap, _, clampY -> IntOffset(a.left, clampY(a.bottom + gap)) }

@Composable
internal fun rememberHoldMenuPosition(card: Rect): PopupPositionProvider =
    rememberPlatePosition(12.dp, card) { _, w, _, gap, _, clampY ->
        IntOffset(card.center.x.roundToInt() - w / 2, clampY(card.top.roundToInt() + gap))
    }

@Composable
internal fun SegMenuPopup(
    visible: Boolean,
    onDismiss: () -> Unit,
    position: PopupPositionProvider,
    origin: TransformOrigin,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 900f))
        } else if (mounted) {
            progress.animateTo(0f, tween(120, easing = FastOutSlowInEasing))
            mounted = false
        }
    }
    if (!mounted) return
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.padding(SegMenuSpec.ShadowPad).graphicsLayer {
                val p = progress.value
                transformOrigin = origin
                val sc = 0.6f + 0.4f * p
                scaleX = sc
                scaleY = sc
                alpha = (p * 1.8f).coerceIn(0f, 1f)
            },
        ) { content() }
    }
}

internal fun Store.groupName(id: String): String =
    if (id.isEmpty()) personalName else tabGroups.firstOrNull { it.id == id }?.name ?: personalName

internal fun groupPickerEntries(
    store: Store,
    current: String,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
): List<List<MenuEntry>> {
    val rows = buildList {
        add(MenuEntry(groupIcon(store.personalIcon), store.personalName, checked = current.isEmpty(), onClick = { onPick("") }))
        store.tabGroups.forEach { g ->
            add(MenuEntry(groupIcon(g.icon), g.name, checked = g.id == current, onClick = { onPick(g.id) }))
        }
    }
    return listOf(
        listOf(MenuEntry(HripsIcons.ChevronBack, "Назад", onClick = onBack)),
        rows,
        listOf(MenuEntry(HripsIcons.Add, "Новая группа", onClick = onNew)),
    )
}
