package com.hrips.browser

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import org.mozilla.geckoview.GeckoRuntimeSettings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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

internal fun defaultScheme(ctx: android.content.Context, dark: Boolean): ColorScheme =
    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)

internal val LocalAppDark = androidx.compose.runtime.compositionLocalOf<Boolean?> { null }

@Composable
internal fun isAppDark(): Boolean = LocalAppDark.current ?: isSystemInDarkTheme()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HripsTheme(incognito: Boolean = false, seed: Int = THEME_AUTO, mode: Int = THEME_MODE_SYSTEM, content: @Composable () -> Unit) {
    val systemDark = when (mode) {
        THEME_MODE_LIGHT -> false
        THEME_MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val dark = incognito || systemDark
    val ctx = LocalContext.current
    val view = LocalView.current
    val scheme = remember(ctx, systemDark, incognito, seed) {
        when {
            incognito -> PrivateScheme
            seed != THEME_AUTO -> seedScheme(Color(seed), systemDark)
            else -> defaultScheme(ctx, systemDark)
        }
    }
    SideEffect {
        (ctx.applicationContext as? HripsApp)?.let { app ->
            val wanted = if (systemDark) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
            runCatching {
                val settings = app.runtime.settings
                if (settings.preferredColorScheme != wanted) settings.setPreferredColorScheme(wanted)
            }
        }
        ErrorPages.updatePalette(scheme)
        GeckoColors.update(scheme)
        if (!view.isInEditMode) {
            val activity = (ctx as? android.app.Activity) ?: (ctx as? ContextWrapper)?.findActivity()
            activity?.window?.let { window ->
                val controller = WindowInsetsControllerCompat(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAppDark provides systemDark) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            typography = HripsTypography,
            content = content,
        )
    }
}
