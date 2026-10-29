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

/*
 * Меню в стиле iOS 26 (Safari): по удержанию адресной строки и по удержанию вкладки в обзоре.
 * Размеры сняты с образца и пересчитаны на экран шириной 360 dp (множитель 0,77, как у нижней панели):
 * ширина 231 dp, шаг строк 39 dp, шаг групп 57 dp, зазор до адресной строки 15 dp.
 * Меню лежит на подложке (скруглённая панель surfaceContainer с тенью и тонкой рамкой): каждая строка - отдельный
 * сегмент (большие углы только на внешнем контуре группы), группы разделены отступом внутри подложки.
 * Размер один на все меню (удержание адресной строки, удержание вкладки, «⋯» в менеджере вкладок).
 */

/** Пункт меню: значок, подпись и, по желанию, галочка («выбрано») или шеврон («есть подменю»). */
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
    // Подложка: поле вокруг сегментов и радиус (Big + поле, чтобы углы сегментов и подложки были концентричными).
    val PlatePad = 8.dp
    val PlateRadius = 26.dp
    // Зазор между группами внутри подложки (было 21 dp без подложки).
    val GroupGap = 8.dp
    // Запас вокруг подложки под тень: окно Popup обрезает всё, что рисуется за его границей.
    val ShadowPad = 14.dp
    // Зазор между меню и адресной строкой: 20 px образца.
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
                Icon(HripsIcons.ChevronRight, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            }
        }
    }
}

/**
 * Подложка и группы пунктов: внутри группы зазор 3 dp, между группами 8 dp. Пустые группы пропускаются.
 * Подложка рисуется здесь, поэтому у всех меню на сегментах один вид и один размер.
 */
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
            Modifier.width(SegMenuSpec.Width).padding(SegMenuSpec.PlatePad),
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

/*
 * Окно меню шире подложки на [SegMenuSpec.ShadowPad] с каждой стороны (запас под тень), поэтому положение
 * считается по размеру подложки (popupContentSize минус запас), а итоговое смещение сдвигается на этот запас.
 */

/** Над якорем (адресная строка внизу) по центру; если сверху не помещается, то под ним (адресная строка планшета). */
private class AnchorMenuPosition(private val gap: Int, private val margin: Int, private val pad: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width - pad * 2
        val h = popupContentSize.height - pad * 2
        val maxX = (windowSize.width - w - margin).coerceAtLeast(margin)
        val x = (anchorBounds.center.x - w / 2).coerceIn(margin, maxX)
        val above = anchorBounds.top - gap - h
        val maxY = (windowSize.height - h - margin).coerceAtLeast(margin)
        val y = if (above >= margin) above else (anchorBounds.bottom + gap).coerceIn(margin, maxY)
        return IntOffset(x - pad, y - pad)
    }
}

/** Под якорем, левый край по левому краю якоря (кнопка «⋯» в левом верхнем углу менеджера вкладок). */
private class BelowStartMenuPosition(private val gap: Int, private val margin: Int, private val pad: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width - pad * 2
        val h = popupContentSize.height - pad * 2
        val maxX = (windowSize.width - w - margin).coerceAtLeast(margin)
        val x = anchorBounds.left.coerceIn(margin, maxX)
        val maxY = (windowSize.height - h - margin).coerceAtLeast(margin)
        val y = (anchorBounds.bottom + gap).coerceIn(margin, maxY)
        return IntOffset(x - pad, y - pad)
    }
}

/**
 * У карточки вкладки: по центру карточки (с прижатием к краям экрана), верхом чуть ниже верха карточки,
 * как в образце, где меню накрывает угол карточки. Прямоугольник карточки задан в окне.
 */
private class HoldMenuPosition(private val card: Rect, private val gap: Int, private val margin: Int, private val pad: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width - pad * 2
        val h = popupContentSize.height - pad * 2
        val maxX = (windowSize.width - w - margin).coerceAtLeast(margin)
        val x = (card.center.x.roundToInt() - w / 2).coerceIn(margin, maxX)
        val maxY = (windowSize.height - h - margin).coerceAtLeast(margin)
        val y = (card.top.roundToInt() + gap).coerceIn(margin, maxY)
        return IntOffset(x - pad, y - pad)
    }
}

@Composable
internal fun rememberAnchorMenuPosition(): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            AnchorMenuPosition(SegMenuSpec.AnchorGap.roundToPx(), SegMenuSpec.ScreenMargin.roundToPx(), SegMenuSpec.ShadowPad.roundToPx())
        }
    }
}

@Composable
internal fun rememberBelowStartMenuPosition(): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            BelowStartMenuPosition(8.dp.roundToPx(), SegMenuSpec.ScreenMargin.roundToPx(), SegMenuSpec.ShadowPad.roundToPx())
        }
    }
}

@Composable
internal fun rememberHoldMenuPosition(card: Rect): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density, card) {
        with(density) {
            HoldMenuPosition(card, 12.dp.roundToPx(), SegMenuSpec.ScreenMargin.roundToPx(), SegMenuSpec.ShadowPad.roundToPx())
        }
    }
}

/**
 * Окно с меню. Вырастает из точки [origin] пружиной и гаснет за 120 мс; окно живёт на время закрытия,
 * поэтому видимость и монтирование разведены (как у быстрого списка вкладок).
 */
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

/** Название группы по её id (пустой id - «Личные»). */
internal fun Store.groupName(id: String): String =
    if (id.isEmpty()) personalName else tabGroups.firstOrNull { it.id == id }?.name ?: personalName

/**
 * Подменю «в группу»: «Назад», сами группы (текущая с галочкой) и «Новая группа».
 * Назад и новая группа - отдельные сегменты, как остальные группы меню.
 */
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
        listOf(MenuEntry(HripsIcons.Back, "Назад", onClick = onBack)),
        rows,
        listOf(MenuEntry(HripsIcons.Add, "Новая группа", onClick = onNew)),
    )
}
