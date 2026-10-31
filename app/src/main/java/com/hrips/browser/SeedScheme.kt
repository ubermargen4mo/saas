package com.hrips.browser

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Полная цветовая схема из одного выбранного цвета ([seed]), светлая или тёмная.
 *
 * Оттенок и насыщенность берутся у выбранного цвета: акцент (primary) - он же с подобранной светлотой,
 * вторичный (secondary) - тот же оттенок, но приглушённый, третичный (tertiary) - сдвинут на 60° по кругу.
 * Поверхности и фон - почти нейтральные, с лёгкой примесью оттенка. Серый цвет даёт чисто серую схему.
 * Цвет текста на акценте выбирается по контрасту, поэтому читаемо даже на жёлтом и белом.
 */
internal fun seedScheme(seed: Color, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val hue = hsl[0]
    val sat = hsl[1].coerceIn(0f, 0.95f)
    val lum = hsl[2]

    fun tone(h: Float, s: Float, l: Float): Color =
        Color(ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

    val thue = hue + 60f
    val sSec = sat * 0.28f
    val sTer = sat * 0.55f
    val sNeu = (sat * 0.14f).coerceAtMost(0.10f)
    val sNeuV = (sat * 0.20f).coerceAtMost(0.14f)

    fun p(l: Float) = tone(hue, sat, l)
    fun s(l: Float) = tone(hue, sSec, l)
    fun t(l: Float) = tone(thue, sTer, l)
    fun n(l: Float) = tone(hue, sNeu, l)
    fun nv(l: Float) = tone(hue, sNeuV, l)
    fun on(bg: Color) = if (bg.luminance() > 0.40f) Color(0xFF121212) else Color.White

    return if (dark) {
        val primary = tone(hue, sat, (0.62f + lum * 0.18f).coerceIn(0.66f, 0.80f))
        val secondary = s(0.78f)
        val tertiary = t(0.78f)
        darkColorScheme(
            primary = primary, onPrimary = on(primary),
            primaryContainer = p(0.28f), onPrimaryContainer = p(0.90f),
            inversePrimary = p(0.40f),
            secondary = secondary, onSecondary = on(secondary),
            secondaryContainer = s(0.28f), onSecondaryContainer = s(0.90f),
            tertiary = tertiary, onTertiary = on(tertiary),
            tertiaryContainer = t(0.28f), onTertiaryContainer = t(0.90f),
            background = n(0.07f), onBackground = n(0.90f),
            surface = n(0.07f), onSurface = n(0.90f),
            surfaceVariant = nv(0.26f), onSurfaceVariant = nv(0.80f),
            surfaceTint = primary,
            inverseSurface = n(0.90f), inverseOnSurface = n(0.20f),
            surfaceBright = n(0.24f), surfaceDim = n(0.07f),
            surfaceContainerLowest = n(0.04f), surfaceContainerLow = n(0.10f), surfaceContainer = n(0.12f),
            surfaceContainerHigh = n(0.17f), surfaceContainerHighest = n(0.22f),
            outline = nv(0.60f), outlineVariant = nv(0.30f),
            primaryFixed = p(0.90f), primaryFixedDim = p(0.80f), onPrimaryFixed = p(0.10f), onPrimaryFixedVariant = p(0.30f),
            secondaryFixed = s(0.90f), secondaryFixedDim = s(0.80f), onSecondaryFixed = s(0.10f), onSecondaryFixedVariant = s(0.30f),
            tertiaryFixed = t(0.90f), tertiaryFixedDim = t(0.80f), onTertiaryFixed = t(0.10f), onTertiaryFixedVariant = t(0.30f),
        )
    } else {
        val primary = tone(hue, sat, lum.coerceIn(0.28f, 0.46f))
        val secondary = s(0.40f)
        val tertiary = t(0.40f)
        lightColorScheme(
            primary = primary, onPrimary = on(primary),
            primaryContainer = p(0.90f), onPrimaryContainer = p(0.12f),
            inversePrimary = p(0.75f),
            secondary = secondary, onSecondary = on(secondary),
            secondaryContainer = s(0.89f), onSecondaryContainer = s(0.12f),
            tertiary = tertiary, onTertiary = on(tertiary),
            tertiaryContainer = t(0.89f), onTertiaryContainer = t(0.12f),
            background = n(0.98f), onBackground = n(0.10f),
            surface = n(0.98f), onSurface = n(0.10f),
            surfaceVariant = nv(0.90f), onSurfaceVariant = nv(0.30f),
            surfaceTint = primary,
            inverseSurface = n(0.20f), inverseOnSurface = n(0.95f),
            surfaceBright = n(0.98f), surfaceDim = n(0.86f),
            surfaceContainerLowest = Color.White, surfaceContainerLow = n(0.96f), surfaceContainer = n(0.94f),
            surfaceContainerHigh = n(0.92f), surfaceContainerHighest = n(0.90f),
            outline = nv(0.50f), outlineVariant = nv(0.80f),
            primaryFixed = p(0.90f), primaryFixedDim = p(0.80f), onPrimaryFixed = p(0.10f), onPrimaryFixedVariant = p(0.30f),
            secondaryFixed = s(0.90f), secondaryFixedDim = s(0.80f), onSecondaryFixed = s(0.10f), onSecondaryFixedVariant = s(0.30f),
            tertiaryFixed = t(0.90f), tertiaryFixedDim = t(0.80f), onTertiaryFixed = t(0.10f), onTertiaryFixedVariant = t(0.30f),
        )
    }
}
