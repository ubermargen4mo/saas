@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.hrips.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutManagerCompat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class MenuItem(
    val icon: ImageVector,
    val label: String,
    /** Включено / активно: пункт заливается primaryContainer и получает галочку справа. */
    val on: Boolean = false,
    val enabled: Boolean = true,
    /** Свой значок вместо векторного (значок приложения у «Открыть в приложении»). */
    val leading: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
)

/** Ступени ползунка размера текста, %: мельче шаги возле 100, крупнее по краям (как в Safari). */
private val TEXT_SCALE_STEPS = intArrayOf(50, 75, 85, 100, 115, 125, 150, 175, 200)

/**
 * Меню сайта (телефон): шторка от левой кнопки адресной капсулы. Сверху тумблеры «ПК-версия» и «Без рекламы», ниже
 * группы «страница» и «инструменты», внизу закреплённый ряд: «найти на странице», размер текста, безопасность соединения.
 */
@Composable
internal fun PageOptionsMenu(
    onDismiss: () -> Unit,
    browser: Browser,
    tab: Tab,
    onFind: () -> Unit,
    onScreenshot: () -> Unit,
    onQr: () -> Unit,
    onSecurity: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val store = browser.store
    val hasSite = tab.siteHost() != null

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    // Действие выполняется, когда шторка уже спряталась.
    val close: (() -> Unit) -> Unit = { after ->
        if (!closing) {
            closing = true
            scope.launch {
                sheetState.hide()
                after()
            }.invokeOnCompletion { onDismiss() }
        }
    }

    val app by produceState<PageActions.AppTarget?>(null, tab.url, hasSite) {
        value = if (hasSite) withContext(Dispatchers.Default) { PageActions.appFor(context, tab.url) } else null
    }
    val appIcon: ImageBitmap? = remember(app) { app?.icon?.toIconBitmap(96) }

    val saved = store.isBookmarked(tab.url)

    fun tick(on: Boolean) =
        haptic.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    fun item(
        icon: ImageVector,
        label: String,
        on: Boolean = false,
        enabled: Boolean = true,
        leading: (@Composable () -> Unit)? = null,
        action: () -> Unit,
    ) = MenuItem(icon, label, on, enabled, leading) { close(action) }

    val pageGroup = buildList {
        add(item(
            if (saved) HripsIcons.StarFilled else HripsIcons.Star,
            if (saved) "Убрать из закладок" else "Добавить в закладки",
            on = saved,
        ) { toggleBookmarkWithNotice(store, tab.url, tab.title) })
        if (hasSite) {
            add(item(
                HripsIcons.Reader,
                if (tab.readerActive) "Выйти из режима чтения" else "Режим чтения",
                on = tab.readerActive,
            ) { tab.toggleReader() })
        }
        if (hasSite && !PageActions.isTranslated(tab)) {
            val back = tab.translated || tab.translating
            add(item(
                HripsIcons.Translate,
                if (back) "Показать оригинал" else "Перевести страницу",
                on = back,
            ) { PageActions.translate(tab, browser) })
        }
    }

    val appLeading: (@Composable () -> Unit)? = if (appIcon != null) ({ AppIcon(appIcon) }) else null
    val toolsGroup = buildList {
        add(item(HripsIcons.Qr, "QR-код страницы") { onQr() })
        if (hasSite) {
            val name = app?.label
            add(item(
                HripsIcons.Open,
                if (name != null) "Открыть в $name" else "Открыть в приложении",
                leading = appLeading,
            ) { PageActions.openInApp(context, tab.url) })
        }
        add(item(HripsIcons.FilePdf, "Сохранить как PDF") { PageActions.savePdf(tab, browser.downloads) })
        add(item(HripsIcons.Print, "Распечатать страницу") { PagePrint.print(context, tab) })
        add(item(HripsIcons.Capture, "Снимок страницы") { onScreenshot() })
        add(item(HripsIcons.Home, "На стартовую страницу") { PageActions.addToStartPage(store, tab) })
        if (hasSite && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            add(item(HripsIcons.Phone, "Ярлык на главный экран") { PageActions.addToHomeScreen(context, tab) })
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surfaceContainerLow,
        shape = HripsShapes.XL,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().toolsIn(0), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (hasSite) {
                        ToolToggle(HripsIcons.Desktop, "ПК-версия", tab.desktopMode, true, Modifier.weight(1f)) {
                            val next = !tab.desktopMode
                            tick(next)
                            tab.setDesktop(next)
                        }
                    }
                    ToolToggle(HripsIcons.Block, "Без рекламы", browser.adBlock.enabled, browser.adBlock.extension != null, Modifier.weight(1f)) {
                        val next = !browser.adBlock.enabled
                        tick(next)
                        browser.adBlock.setBlocking(next) { tab.reload() }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.toolsIn(1)) { MenuGroup(pageGroup) }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.toolsIn(2)) { MenuGroup(toolsGroup) }
            }

            Spacer(Modifier.height(12.dp))
            val round = HripsLayout.BarHeight
            Row(
                Modifier.fillMaxWidth().toolsIn(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(HripsIcons.FindPage, "Найти на странице", round) { close { onFind() } }
                TextSizeBar(
                    percent = tab.textScale,
                    onChange = { browser.updateTextScale(tab, it) },
                    modifier = Modifier.weight(1f).height(round),
                )
                if (hasSite) {
                    val warn = !tab.loading && tab.trust == Trust.WARNING
                    RoundButton(
                        if (tab.trust == Trust.SECURE) HripsIcons.BarShield else HripsIcons.BarInfo,
                        "Безопасность соединения",
                        round,
                        tint = if (warn) cs.error else cs.onSurfaceVariant,
                    ) { close { onSecurity() } }
                } else {
                    Spacer(Modifier.size(round))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Круглая кнопка нижнего ряда: фон как у пунктов меню, значок 24 dp. */
@Composable
internal fun RoundButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    PressSurface(
        onClick = onClick,
        corners = Corners.all(size / 2),
        color = cs.surfaceContainerHigh,
        contentColor = tint,
        pressedCorner = size / 3,
        pressScale = 0.94f,
        modifier = Modifier.size(size),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun AppIcon(bmp: ImageBitmap) {
    Image(bmp, null, Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)))
}

internal fun Drawable.toIconBitmap(px: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, px, px)
    draw(canvas)
    return bmp.asImageBitmap()
}

/** Группа пунктов-сегментов: большие углы только снаружи группы, между пунктами зазор вместо разделителей. */
@Composable
private fun MenuGroup(items: List<MenuItem>) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { i, m ->
            ToolRow(
                m.icon, m.label,
                segCorners(items.size, 1, i, 24.dp, 8.dp),
                selected = m.on,
                trailing = if (m.on) HripsIcons.Check else null,
                enabled = m.enabled,
                leading = m.leading,
                onClick = m.onClick,
            )
        }
    }
}

/**
 * Размер текста: капсула целиком служит ползунком, заливка идёт слева до выбранной ступени. Нажатие на крайние 40 dp
 * двигает на одну ступень. Содержимое рисуется дважды: поверх обычного лежит копия цвета onPrimary, обрезанная по заливке.
 */
@Composable
internal fun TextSizeBar(percent: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val steps = TEXT_SCALE_STEPS
    val index = remember(percent) { steps.indices.minByOrNull { abs(steps[it] - percent) } ?: 0 }
    val set by rememberUpdatedState<(Int) -> Unit> { i ->
        val v = i.coerceIn(0, steps.lastIndex)
        if (v != index) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            onChange(steps[v])
        }
    }
    var widthPx by remember { mutableIntStateOf(1) }
    val current by rememberUpdatedState(index)
    fun stepAt(x: Float) = ((x / widthPx).coerceIn(0f, 1f) * steps.lastIndex).roundToInt()
    val fraction by animateFloatAsState(
        index / steps.lastIndex.toFloat(),
        MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
        label = "textScaleFill",
    )
    val label = "${steps[index]}%"
    val labelStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    val content: @Composable (Color) -> Unit = { color ->
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("A", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = color)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(label, maxLines = 1, style = labelStyle, color = color)
            }
            Text("A", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 21.sp, fontWeight = FontWeight.Medium), color = color)
        }
    }
    Box(
        modifier
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .drawBehind { drawRect(cs.primary, size = Size(size.width * fraction, size.height)) }
            .pointerInput(Unit) {
                val edge = 40.dp.toPx()
                detectTapGestures { o ->
                    when {
                        o.x < edge -> set(current - 1)
                        o.x > widthPx - edge -> set(current + 1)
                        else -> set(stepAt(o.x))
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { set(stepAt(it.x)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        set(stepAt(change.position.x))
                    },
                    onDragEnd = {},
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = "Размер текста"
                stateDescription = label
            },
    ) {
        content(cs.onSurface)
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() } },
        ) { content(cs.onPrimary) }
    }
}
