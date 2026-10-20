package com.hrips.browser

import android.app.Activity
import android.content.ContextWrapper
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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

private val PrivateSeed = Color(0xFF8B6CF0)

/** Fixed hrips blue / deep-navy palette for Android versions without Material You colors. */
private val HripsLightScheme = lightColorScheme(
    primary = Color(0xFF0C4A6E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E8FA),
    onPrimaryContainer = Color(0xFF001D2E),
    inversePrimary = Color(0xFF89BBF2),
    secondary = Color(0xFF52616B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E5EF),
    onSecondaryContainer = Color(0xFF111D26),
    tertiary = Color(0xFF426369),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC7E9EC),
    onTertiaryContainer = Color(0xFF081F22),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDFE4E8),
    onSurfaceVariant = Color(0xFF3E484E),
    surfaceTint = Color(0xFF0C4A6E),
    inverseSurface = Color(0xFF2B3136),
    inverseOnSurface = Color(0xFFF0F3F6),
    outline = Color(0xFF6E7B84),
    outlineVariant = Color(0xFFBFC9D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8FAFC),
    surfaceDim = Color(0xFFD8E0E6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F8),
    surfaceContainer = Color(0xFFEAF0F4),
    surfaceContainerHigh = Color(0xFFE7EDF1),
    surfaceContainerHighest = Color(0xFFE1E8EC),
    primaryFixed = Color(0xFFD0E8FA),
    primaryFixedDim = Color(0xFF89BBF2),
    onPrimaryFixed = Color(0xFF001D2E),
    onPrimaryFixedVariant = Color(0xFF194663),
    secondaryFixed = Color(0xFFD5E5EF),
    secondaryFixedDim = Color(0xFFB9C9D3),
    onSecondaryFixed = Color(0xFF111D26),
    onSecondaryFixedVariant = Color(0xFF3A4A54),
    tertiaryFixed = Color(0xFFC7E9EC),
    tertiaryFixedDim = Color(0xFFAFCED1),
    onTertiaryFixed = Color(0xFF081F22),
    onTertiaryFixedVariant = Color(0xFF354F53),
)

private val HripsDarkScheme = darkColorScheme(
    primary = Color(0xFF89BBF2),
    onPrimary = Color(0xFF042A4B),
    primaryContainer = Color(0xFF194663),
    onPrimaryContainer = Color(0xFFD0E8FA),
    inversePrimary = Color(0xFF0C4A6E),
    secondary = Color(0xFFB9C9D3),
    onSecondary = Color(0xFF24333C),
    secondaryContainer = Color(0xFF3A4A54),
    onSecondaryContainer = Color(0xFFD5E5EF),
    tertiary = Color(0xFFAFCED1),
    onTertiary = Color(0xFF1E383B),
    tertiaryContainer = Color(0xFF354F53),
    onTertiaryContainer = Color(0xFFC7E9EC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF10161B),
    onBackground = Color(0xFFE0E5E9),
    surface = Color(0xFF10161B),
    onSurface = Color(0xFFE0E5E9),
    surfaceVariant = Color(0xFF424C53),
    onSurfaceVariant = Color(0xFFC0CBD1),
    surfaceTint = Color(0xFF89BBF2),
    inverseSurface = Color(0xFFE0E5E9),
    inverseOnSurface = Color(0xFF20282E),
    outline = Color(0xFF89949E),
    outlineVariant = Color(0xFF424C53),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353D43),
    surfaceDim = Color(0xFF10161B),
    surfaceContainerLowest = Color(0xFF0B1014),
    surfaceContainerLow = Color(0xFF171E23),
    surfaceContainer = Color(0xFF1C252B),
    surfaceContainerHigh = Color(0xFF262F35),
    surfaceContainerHighest = Color(0xFF313A40),
    primaryFixed = Color(0xFFD0E8FA),
    primaryFixedDim = Color(0xFF89BBF2),
    onPrimaryFixed = Color(0xFF001D2E),
    onPrimaryFixedVariant = Color(0xFF194663),
    secondaryFixed = Color(0xFFD5E5EF),
    secondaryFixedDim = Color(0xFFB9C9D3),
    onSecondaryFixed = Color(0xFF111D26),
    onSecondaryFixedVariant = Color(0xFF3A4A54),
    tertiaryFixed = Color(0xFFC7E9EC),
    tertiaryFixedDim = Color(0xFFAFCED1),
    onTertiaryFixed = Color(0xFF081F22),
    onTertiaryFixedVariant = Color(0xFF354F53),
)

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

private fun ContextWrapper.findActivity(): Activity? {
    var context = baseContext
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return context as? Activity
}

private val PrivateScheme = darkSchemeFrom(PrivateSeed)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HripsTheme(incognito: Boolean = false, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = incognito || systemDark
    val ctx = LocalContext.current
    val view = LocalView.current
    val scheme = remember(ctx, systemDark, incognito) {
        when {
            incognito -> PrivateScheme
            Build.VERSION.SDK_INT >= 31 ->
                if (systemDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            systemDark -> HripsDarkScheme
            else -> HripsLightScheme
        }
    }
    SideEffect {
        // Incognito uses a dark palette even when Android itself is in light mode.
        // Keep edge-to-edge system icons readable when the browser switches modes.
        if (!view.isInEditMode) {
            val activity = (ctx as? android.app.Activity) ?: (ctx as? ContextWrapper)?.findActivity()
            activity?.window?.let { window ->
                val controller = WindowInsetsControllerCompat(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = HripsTypography,
        content = content,
    )
}
