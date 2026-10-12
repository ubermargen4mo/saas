package com.hrips.browser

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.platform.LocalContext

private val PrivateSeed = Color(0xFF8B6CF0)

private fun darkSchemeFrom(seed: Color): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val hue = hsl[0]
    fun tone(h: Float, s: Float, t: Int): Color =
        Color(ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), t / 100f)))
    val pS = 0.88f
    val sS = 0.22f
    val tS = 0.5f
    val nS = 0.08f
    val nvS = 0.1f
    val th = hue + 60f
    fun p(t: Int) = tone(hue, pS, t)
    fun s(t: Int) = tone(hue, sS, t)
    fun t(t: Int) = tone(th, tS, t)
    fun n(t: Int) = tone(hue, nS, t)
    fun nv(t: Int) = tone(hue, nvS, t)
    return darkColorScheme(
        primary = p(80), onPrimary = p(20), primaryContainer = p(30), onPrimaryContainer = p(90),
        inversePrimary = p(40),
        secondary = s(80), onSecondary = s(20), secondaryContainer = s(30), onSecondaryContainer = s(90),
        tertiary = t(80), onTertiary = t(20), tertiaryContainer = t(30), onTertiaryContainer = t(90),
        background = n(6), onBackground = n(90),
        surface = n(6), onSurface = n(90), surfaceVariant = nv(30), onSurfaceVariant = nv(80),
        surfaceTint = p(80),
        inverseSurface = n(90), inverseOnSurface = n(20),
        surfaceBright = n(24), surfaceDim = n(6),
        surfaceContainerLowest = n(4), surfaceContainerLow = n(10), surfaceContainer = n(12),
        surfaceContainerHigh = n(17), surfaceContainerHighest = n(22),
        outline = nv(60), outlineVariant = nv(30),
        primaryFixed = p(90), primaryFixedDim = p(80), onPrimaryFixed = p(10), onPrimaryFixedVariant = p(30),
        secondaryFixed = s(90), secondaryFixedDim = s(80), onSecondaryFixed = s(10), onSecondaryFixedVariant = s(30),
        tertiaryFixed = t(90), tertiaryFixedDim = t(80), onTertiaryFixed = t(10), onTertiaryFixedVariant = t(30),
    )
}

private val PrivateScheme = darkSchemeFrom(PrivateSeed)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HripsTheme(incognito: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        incognito -> PrivateScheme
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    SideEffect {
        ErrorPages.updatePalette(scheme)
        GeckoColors.update(scheme)
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = HripsTypography,
        content = content,
    )
}
