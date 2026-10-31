@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Готовый цвет темы: название и ARGB. */
private class ThemePreset(val name: String, val argb: Int)

private val ThemePresets = listOf(
    ThemePreset("Hrips", 0xFF0C4A6E.toInt()),
    ThemePreset("Синий", 0xFF2F6BFF.toInt()),
    ThemePreset("Небесный", 0xFF1E9BE8.toInt()),
    ThemePreset("Бирюзовый", 0xFF00A6A6.toInt()),
    ThemePreset("Зелёный", 0xFF2E9E4F.toInt()),
    ThemePreset("Лайм", 0xFF7CB518.toInt()),
    ThemePreset("Жёлтый", 0xFFE8B400.toInt()),
    ThemePreset("Оранжевый", 0xFFF07A12.toInt()),
    ThemePreset("Красный", 0xFFE03A3A.toInt()),
    ThemePreset("Розовый", 0xFFEA4C89.toInt()),
    ThemePreset("Пурпурный", 0xFFB93FC9.toInt()),
    ThemePreset("Фиолетовый", 0xFF7A4FE0.toInt()),
    ThemePreset("Индиго", 0xFF4C5BD4.toInt()),
    ThemePreset("Кофейный", 0xFF8B5E3C.toInt()),
    ThemePreset("Графит", 0xFF5F6B73.toInt()),
)

private const val OPAQUE = 0xFF000000.toInt()

/**
 * Раздел «Цвет темы»: как выглядит выбранный цвет (светлая и тёмная схема), готовые цвета одним нажатием
 * и подробный выбор своего цвета (палитра, оттенок, RGB, HEX). Изменения применяются сразу ко всему приложению.
 */
@Composable
internal fun ThemeColorSection(store: Store) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val seed = store.themeSeed

    SectionTitle("Цвет темы")
    ThemePreviewPair(seed)
    Spacer(Modifier.height(12.dp))

    // На Android 12+ цвет по умолчанию - Material You (обои), на старых версиях - фирменный синий.
    val material = Build.VERSION.SDK_INT >= 31
    val auto = remember(ctx, dark) { defaultScheme(ctx, dark) }
    val presets = if (material) ThemePresets else ThemePresets.drop(1)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val cells = buildList<Triple<String, Int, ColorScheme?>> {
            add(Triple(if (material) "Системный" else "Hrips", THEME_AUTO, auto))
            presets.forEach { add(Triple(it.name, it.argb, null)) }
        }
        cells.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (name, argb, fixed) ->
                    val scheme = fixed ?: remember(argb, dark) { seedScheme(Color(argb), dark) }
                    ThemeSwatch(name, scheme, seed == argb, { store.updateThemeSeed(argb) }, Modifier.weight(1f))
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    CustomColorCard(store, presets)
}

// ---------------------------------------------------------------------------------------------
// Предпросмотр
// ---------------------------------------------------------------------------------------------

/** Две мини-карточки: как цвет выглядит в светлой и в тёмной теме системы. */
@Composable
private fun ThemePreviewPair(seed: Int) {
    val ctx = LocalContext.current
    val light = remember(ctx, seed) { if (seed == THEME_AUTO) defaultScheme(ctx, false) else seedScheme(Color(seed), false) }
    val night = remember(ctx, seed) { if (seed == THEME_AUTO) defaultScheme(ctx, true) else seedScheme(Color(seed), true) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MiniWindow("Светлая", light, Modifier.weight(1f))
        MiniWindow("Тёмная", night, Modifier.weight(1f))
    }
}

@Composable
private fun MiniWindow(label: String, s: ColorScheme, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(HripsShapes.L)
            .background(s.surface)
            .border(BorderStroke(1.dp, s.outlineVariant), HripsShapes.L)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Панель: адресная строка и кнопки
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(s.surfaceContainerHighest))
            Box(Modifier.weight(1f).height(14.dp).clip(CircleShape).background(s.surfaceContainerHigh))
            Box(Modifier.size(14.dp).clip(CircleShape).background(s.primary))
        }
        // Плитки сайтов в контейнерах акцентов
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(s.primaryContainer, s.secondaryContainer, s.tertiaryContainer).forEach { c ->
                Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(12.dp)).background(c))
            }
        }
        // Кнопка
        Box(
            Modifier.fillMaxWidth().height(30.dp).clip(CircleShape).background(s.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = s.onPrimary, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Готовые цвета
// ---------------------------------------------------------------------------------------------

/** Круглый образец цвета: половина акцентом, четверть третичным, остальное контейнером; выбранный в кольце с галочкой. */
@Composable
private fun ThemeSwatch(label: String, scheme: ColorScheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val haptic = LocalHapticFeedback.current
    val ring by animateColorAsState(if (selected) cs.primary else Color.Transparent, motion.fastEffectsSpec<Color>(), label = "swatchRing")
    val labelColor by animateColorAsState(if (selected) cs.primary else cs.onSurfaceVariant, motion.fastEffectsSpec<Color>(), label = "swatchLabel")
    Column(
        modifier
            .clip(HripsShapes.L)
            .clickable(role = Role.RadioButton) {
                if (!selected) haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
                onClick()
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).border(BorderStroke(3.dp, ring), CircleShape).padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(scheme.primaryContainer)
                drawArc(scheme.primary, 90f, 180f, true)
                drawArc(scheme.tertiary, 0f, 90f, true)
            }
            if (selected) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                    Icon(HripsIcons.Check, null, Modifier.size(15.dp), tint = cs.onPrimary)
                }
            }
        }
        Text(
            label,
            Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Свой цвет
// ---------------------------------------------------------------------------------------------

@Composable
private fun CustomColorCard(store: Store, presets: List<ThemePreset>) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    var open by remember { mutableStateOf(false) }
    val seed = store.themeSeed
    val isCustom = seed != THEME_AUTO && presets.none { it.argb == seed }
    val chevron by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, motion.fastSpatialSpec<Float>(), label = "customChevron")

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PanelItem(
            "Свой цвет",
            subtitle = if (isCustom) "#%06X".format(seed and 0xFFFFFF) else "Палитра, RGB и HEX",
            icon = HripsIcons.PaletteLine,
            onClick = { open = !open },
            shape = if (open) HripsShapes.segment(0, 2) else HripsShapes.XL,
            trailing = {
                if (isCustom) Box(Modifier.size(24.dp).clip(CircleShape).background(Color(seed)))
                Icon(HripsIcons.ChevronDown, null, Modifier.graphicsLayer { rotationZ = chevron }, tint = cs.onSurfaceVariant)
            },
        )
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Surface(shape = HripsShapes.segment(1, 2), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                ColorPicker(store)
            }
        }
    }
}

/**
 * Состояние выбора: точный ARGB плюс оттенок/насыщенность/яркость для палитры. ARGB - источник правды
 * (чтобы ввод RGB и HEX не «плыл» из-за округления), HSV нужен для палитры; у серых цветов оттенок сохраняется.
 */
private class PickerState(initial: Int) {
    var argb by mutableIntStateOf(initial or OPAQUE)
        private set
    var h by mutableFloatStateOf(0f)
        private set
    var s by mutableFloatStateOf(0f)
        private set
    var v by mutableFloatStateOf(0f)
        private set

    init { syncHsv() }

    private fun syncHsv() {
        val a = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, a)
        if (a[1] > 0.001f && a[2] > 0.001f) h = a[0]
        s = a[1]
        v = a[2]
    }

    fun fromHsv(hue: Float, sat: Float, value: Float) {
        h = hue.coerceIn(0f, 360f)
        s = sat.coerceIn(0f, 1f)
        v = value.coerceIn(0f, 1f)
        argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    }

    fun fromArgb(color: Int) {
        argb = color or OPAQUE
        syncHsv()
    }

    val r get() = (argb shr 16) and 0xFF
    val g get() = (argb shr 8) and 0xFF
    val b get() = argb and 0xFF
}

@Composable
private fun ColorPicker(store: Store) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val start = remember { if (store.themeSeed != THEME_AUTO) store.themeSeed else defaultScheme(ctx, dark).primary.toArgb() }
    val st = remember { PickerState(start) }
    val lastEmitted = remember { intArrayOf(start or OPAQUE) }
    val job = remember { arrayOfNulls<Job>(1) }

    // Применяем с небольшой задержкой: во время перетаскивания тема не перестраивается на каждый пиксель.
    fun commit() {
        val a = st.argb
        lastEmitted[0] = a
        job[0]?.cancel()
        job[0] = scope.launch {
            delay(120)
            store.updateThemeSeed(a)
        }
    }

    // Выбрали готовый цвет снаружи - палитра подхватывает его.
    LaunchedEffect(store.themeSeed) {
        val sd = store.themeSeed
        if (sd != THEME_AUTO && sd != lastEmitted[0]) {
            lastEmitted[0] = sd
            st.fromArgb(sd)
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SvSquare(st, ::commit)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Оттенок", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GradientSlider(
                value = st.h / 360f,
                colors = HueStops,
                thumb = Color(android.graphics.Color.HSVToColor(floatArrayOf(st.h, 1f, 1f))),
                onChange = { st.fromHsv(it * 360f, st.s, st.v); commit() },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("RGB", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RgbRow("R", st.r, Color(0, st.g, st.b), Color(255, st.g, st.b), Color(st.argb)) { st.fromArgb(android.graphics.Color.rgb(it, st.g, st.b)); commit() }
            RgbRow("G", st.g, Color(st.r, 0, st.b), Color(st.r, 255, st.b), Color(st.argb)) { st.fromArgb(android.graphics.Color.rgb(st.r, it, st.b)); commit() }
            RgbRow("B", st.b, Color(st.r, st.g, 0), Color(st.r, st.g, 255), Color(st.argb)) { st.fromArgb(android.graphics.Color.rgb(st.r, st.g, it)); commit() }
        }

        HexField(st.argb) { st.fromArgb(it); commit() }
    }
}

private val HueStops = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map {
    Color(android.graphics.Color.HSVToColor(floatArrayOf(it, 1f, 1f)))
}

/** Палитра: по горизонтали насыщенность, по вертикали яркость, оттенок выбирается ползунком ниже. */
@Composable
private fun SvSquare(st: PickerState, onChange: () -> Unit) {
    val cb by rememberUpdatedState(onChange)
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(st.h, 1f, 1f)))
    Box(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(HripsShapes.M)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun apply(p: Offset) {
                        if (size.width <= 0 || size.height <= 0) return
                        st.fromHsv(st.h, (p.x / size.width).coerceIn(0f, 1f), 1f - (p.y / size.height).coerceIn(0f, 1f))
                        cb()
                    }
                    apply(down.position)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { c ->
                            if (c.pressed) {
                                apply(c.position)
                                c.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val c = Offset(st.s * size.width, (1f - st.v) * size.height)
            drawCircle(Color.Black.copy(alpha = 0.3f), 14.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White, 12.dp.toPx(), c, style = Stroke(3.dp.toPx()))
            drawCircle(Color(st.argb), 9.dp.toPx(), c)
        }
    }
}

/** Ползунок с градиентной дорожкой и круглым бегунком. [value] от 0 до 1. */
@Composable
private fun GradientSlider(
    value: Float,
    colors: List<Color>,
    thumb: Color,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cb by rememberUpdatedState(onChange)
    Box(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = 14.dp.toPx()
                    fun apply(x: Float) {
                        val span = size.width - 2 * r
                        if (span > 0f) cb(((x - r) / span).coerceIn(0f, 1f))
                    }
                    apply(down.position.x)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { c ->
                            if (c.pressed) {
                                apply(c.position.x)
                                c.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = 14.dp.toPx()
            val track = 14.dp.toPx()
            drawRoundRect(
                Brush.horizontalGradient(colors),
                topLeft = Offset(0f, (size.height - track) / 2f),
                size = Size(size.width, track),
                cornerRadius = CornerRadius(track / 2f),
            )
            val cx = r + value.coerceIn(0f, 1f) * (size.width - 2 * r)
            val cy = size.height / 2f
            drawCircle(Color.Black.copy(alpha = 0.25f), r + 1.dp.toPx(), Offset(cx, cy))
            drawCircle(Color.White, r, Offset(cx, cy))
            drawCircle(thumb, r - 3.dp.toPx(), Offset(cx, cy))
        }
    }
}

@Composable
private fun RgbRow(label: String, value: Int, from: Color, to: Color, thumb: Color, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(14.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GradientSlider(
            value = value / 255f,
            colors = listOf(from, to),
            thumb = thumb,
            onChange = { onChange((it * 255f).roundToInt()) },
            modifier = Modifier.weight(1f),
        )
        NumberBox(value, onChange)
    }
}

/** Поле числа 0..255 в виде таблетки. */
@Composable
private fun NumberBox(value: Int, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var text by remember(value) { mutableStateOf(value.toString()) }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(3)
            text = digits
            digits.toIntOrNull()?.let { onChange(it.coerceIn(0, 255)) }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = SolidColor(cs.primary),
        modifier = Modifier
            .width(58.dp)
            .height(40.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHighest)
            .onFocusChanged { if (!it.isFocused) text = value.toString() },
        decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { inner() } },
    )
}

@Composable
private fun HexField(argb: Int, onChange: (Int) -> Unit) {
    var text by remember(argb) { mutableStateOf("%06X".format(argb and 0xFFFFFF)) }
    HripsField(
        value = text,
        onValueChange = { raw ->
            val clean = raw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6).uppercase()
            text = clean
            if (clean.length == 6) onChange(OPAQUE or clean.toInt(16))
        },
        singleLine = true,
        label = { Text("HEX-код") },
        placeholder = { Text("RRGGBB") },
        isError = text.length != 6,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        trailingIcon = { Box(Modifier.size(24.dp).clip(CircleShape).background(Color(argb))) },
    )
}
