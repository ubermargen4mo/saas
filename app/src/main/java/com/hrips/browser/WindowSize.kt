package com.hrips.browser

import android.app.Activity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Shared adaptive-window policy used by browser chrome and the start page. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun isWideWindow(): Boolean {
    val activity = LocalContext.current as? Activity ?: return false
    return calculateWindowSizeClass(activity).widthSizeClass != WindowWidthSizeClass.Compact
}
