@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.hrips.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private class MenuItem(
    val icon: ImageVector,
    val label: String,
    /** Включено / активно: пункт заливается primaryContainer и получает галочку справа. */
    val on: Boolean = false,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val stay: Boolean = false,
    val onClick: () -> Unit,
)

/** Меню встаёт над нижней панелью по центру; запас под тень нужен, иначе окно её обрежет. */
private class AboveBarPosition(
    private val bottomGap: Int,
    private val topMargin: Int,
    private val shadowPad: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
        val y = (windowSize.height - bottomGap - popupContentSize.height + shadowPad).coerceAtLeast(topMargin)
        return IntOffset(x, y)
    }
}

/**
 * Меню сайта (телефон): плавающая карточка над нижней панелью, её открывает левая кнопка адресной капсулы.
 * Стиль как у Orion/Safari: карточка встаёт над панелью, внизу ряд «найти на странице» + ползунок размера текста.
 * Вместо разделителей между пунктами у нас сегменты: у группы большие внешние углы, у соседних пунктов маленькие,
 * между ними зазор 3 dp, при нажатии пункт округляется (общие `ToolRow` и `segCorners`).
 * Сверху тумблеры «ПК-версия» и «Без рекламы» (плитки, как были), ниже группы: «страница» (закладка, чтение, перевод)
 * и «инструменты» (QR, PDF, печать и т. д.).
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
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val store = browser.store
    val hasSite = tab.siteHost() != null

    // Появление и закрытие идут через graphicsLayer: пружина без перекомпозиции за кадр.
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 900f)) }
    // Действие выполняется после закрытия: снимок экрана и печать не должны застать меню на экране.
    val close: (() -> Unit) -> Unit = { after ->
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(0f, tween(120, easing = FastOutSlowInEasing))
                after()
                onDismiss()
            }
        }
    }
    val dismiss: () -> Unit = { close { } }

    val saved = store.isBookmarked(tab.url)

    fun item(
        icon: ImageVector,
        label: String,
        on: Boolean = false,
        enabled: Boolean = true,
        danger: Boolean = false,
        stay: Boolean = false,
        action: () -> Unit,
    ) = MenuItem(icon, label, on, enabled, danger, stay) {
        if (stay) action() else close(action)
    }

    val pageGroup = buildList {
        add(item(if (saved) HripsIcons.StarFilled else HripsIcons.Star, if (saved) "В закладках" else "Закладка", on = saved) {
            toggleBookmarkWithNotice(store, tab.url, tab.title)
        })
        if (hasSite) {
            add(item(HripsIcons.Reader, if (tab.readerActive) "Закрыть чтение" else "Чтение", on = tab.readerActive) { tab.toggleReader() })
        }
        if (hasSite && !PageActions.isTranslated(tab)) {
            val back = tab.translated || tab.translating
            add(item(HripsIcons.Translate, if (back) "Оригинал" else "Перевести", on = back) { PageActions.translate(tab, browser) })
        }
    }

    val toolsGroup = buildList {
        add(item(HripsIcons.Qr, "QR-код") { onQr() })
        if (hasSite) add(item(HripsIcons.Open, "Открыть в приложении") { PageActions.openInApp(context, tab.url) })
        add(item(HripsIcons.FilePdf, "Сохранить в PDF") { PageActions.savePdf(tab, browser.downloads) })
        add(item(HripsIcons.Print, "Печать") { PagePrint.print(context, tab) })
        add(item(HripsIcons.Capture, "Снимок экрана") { onScreenshot() })
        add(item(HripsIcons.Home, "Добавить на старт") { PageActions.addToStartPage(context, store, tab) })
        if (hasSite && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            add(item(HripsIcons.Phone, "Ярлык на экран") { PageActions.addToHomeScreen(context, tab) })
        }
        if (hasSite) {
            add(
                item(
                    if (tab.trust == Trust.SECURE) HripsIcons.BarShield else HripsIcons.BarInfo,
                    "Безопасность соединения",
                    danger = tab.trust == Trust.WARNING,
                ) { onSecurity() },
            )
        }
    }

    // Положение: над нижней панелью (отступ снизу + зазор + высота панели + воздух), список прокручивается, если не влезает.
    val shadowPad = 12.dp
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val bottomGap = navBottom + with(density) { (HripsLayout.BarGap + HripsLayout.BarHeight + 10.dp).roundToPx() }
    val position = remember(density, bottomGap) {
        with(density) { AboveBarPosition(bottomGap, 24.dp.roundToPx(), shadowPad.roundToPx()) }
    }
    val menuWidth = (config.screenWidthDp.dp - shadowPad * 2).coerceAtMost(400.dp)
    val bottomRowHeight = 52.dp
    val listMax = (config.screenHeightDp.dp - with(density) { bottomGap.toDp() } - 64.dp - bottomRowHeight - 24.dp).coerceAtLeast(160.dp)

    Popup(
        popupPositionProvider = position,
        onDismissRequest = dismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            Modifier
                .padding(shadowPad)
                .graphicsLayer {
                    val p = progress.value
                    // Растёт из нижней середины, откуда пришёл палец.
                    transformOrigin = TransformOrigin(0.12f, 1f)
                    val sc = 0.6f + 0.4f * p
                    scaleX = sc
                    scaleY = sc
                    alpha = (p * 1.8f).coerceIn(0f, 1f)
                },
        ) {
            Surface(
                modifier = Modifier.width(menuWidth),
                shape = HripsShapes.XL,
                color = cs.surfaceContainerLow,
                contentColor = cs.onSurface,
                shadowElevation = 8.dp,
                border = BorderStroke(0.5.dp, cs.onSurface.copy(alpha = 0.10f)),
            ) {
                Column(Modifier.padding(8.dp)) {
                    Column(
                        Modifier.heightIn(max = listMax).verticalScroll(rememberScrollState()),
                    ) {
                        // Тумблеры как раньше: две плитки рядом с подписью «Включено / Выключено».
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (hasSite) {
                                ToolToggle(HripsIcons.Desktop, "ПК-версия", tab.desktopMode, true, Modifier.weight(1f)) {
                                    val next = !tab.desktopMode
                                    haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                    tab.setDesktop(next)
                                }
                            }
                            ToolToggle(HripsIcons.Block, "Без рекламы", browser.adBlock.enabled, browser.adBlock.extension != null, Modifier.weight(1f)) {
                                val next = !browser.adBlock.enabled
                                haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                browser.adBlock.setBlocking(next) { tab.reload() }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        MenuGroup(pageGroup)
                        Spacer(Modifier.height(10.dp))
                        MenuGroup(toolsGroup)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        PressSurface(
                            onClick = { close { onFind() } },
                            corners = Corners(26.dp, 8.dp, 8.dp, 26.dp),
                            color = cs.surfaceContainerHigh,
                            contentColor = cs.onSurfaceVariant,
                            pressedCorner = 26.dp,
                            pressScale = 0.95f,
                            modifier = Modifier.size(width = 58.dp, height = bottomRowHeight),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(HripsIcons.FindPage, "Найти на странице", Modifier.size(24.dp))
                            }
                        }
                        TextSizeBar(
                            percent = store.textScale,
                            onChange = { browser.setTextScale(it) },
                            modifier = Modifier.weight(1f).height(bottomRowHeight),
                        )
                    }
                }
            }
        }
    }
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
                danger = m.danger,
                onClick = m.onClick,
            )
        }
    }
}

/**
 * Размер текста: маленькая «A», ползунок по ступеням и большая «A». Нажатие на букву двигает на одну ступень.
 * Значение применяется сразу, пока тянешь ползунок: страница перерисовывается на глазах.
 */
@Composable
private fun TextSizeBar(percent: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val steps = TEXT_SCALE_STEPS
    val index = remember(percent) { steps.indices.minByOrNull { abs(steps[it] - percent) } ?: 0 }
    val set: (Int) -> Unit = { i ->
        val v = i.coerceIn(0, steps.lastIndex)
        if (v != index) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            onChange(steps[v])
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 8.dp),
        color = cs.surfaceContainerHigh,
        contentColor = cs.onSurface,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { set(index - 1) },
                contentAlignment = Alignment.Center,
            ) {
                Text("A", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = cs.onSurfaceVariant)
            }
            Slider(
                value = index.toFloat(),
                onValueChange = { set(it.roundToInt()) },
                valueRange = 0f..steps.lastIndex.toFloat(),
                steps = steps.size - 2,
                colors = SliderDefaults.colors(
                    activeTrackColor = cs.primary,
                    inactiveTrackColor = cs.surfaceContainerHighest,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).semantics { stateDescription = "${steps[index]}%" },
            )
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { set(index + 1) },
                contentAlignment = Alignment.Center,
            ) {
                Text("A", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium), color = cs.onSurface)
            }
        }
    }
}
