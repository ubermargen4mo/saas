package com.hrips.browser

import android.app.Activity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun isWideWindow(): Boolean {
    val activity = LocalContext.current as? Activity ?: return false
    val size = calculateWindowSizeClass(activity)
    return size.widthSizeClass != WindowWidthSizeClass.Compact && size.heightSizeClass != WindowHeightSizeClass.Compact
}
