package com.hrips.browser

import android.app.Activity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Планшетная раскладка (полоса вкладок, кнопки в одной верхней строке) нужна только окну, где и ширины, и высоты
 * достаточно. Телефон на боку широкий (640-900dp), но низкий (~360dp): раньше он получал планшетный хром, который
 * съедал около трети высоты. Теперь низкое окно получает телефонную раскладку с нижней панелью, которая прячется при прокрутке.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun isWideWindow(): Boolean {
    val activity = LocalContext.current as? Activity ?: return false
    val size = calculateWindowSizeClass(activity)
    return size.widthSizeClass != WindowWidthSizeClass.Compact && size.heightSizeClass != WindowHeightSizeClass.Compact
}
