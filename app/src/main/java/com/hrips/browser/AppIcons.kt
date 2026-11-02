package com.hrips.browser

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

enum class AppIcon(val title: String, val alias: String, val preview: Int) {
    LIGHT("Светлая", "com.hrips.browser.LauncherLight", R.drawable.icon_preview_light),
    DARK("Тёмная", "com.hrips.browser.LauncherDark", R.drawable.icon_preview_dark),
}

object AppIcons {
    private const val TAG = "AppIcons"

    private val default = AppIcon.LIGHT

    private fun component(context: Context, icon: AppIcon) = ComponentName(context, icon.alias)

    private fun state(context: Context, icon: AppIcon) =
        context.packageManager.getComponentEnabledSetting(component(context, icon))

    fun current(context: Context): AppIcon =
        AppIcon.entries.firstOrNull {
            it != default && state(context, it) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: default

    fun set(context: Context, icon: AppIcon): Boolean {
        if (current(context) == icon) return true
        val pm = context.packageManager
        return try {
            pm.setComponentEnabledSetting(component(context, icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            AppIcon.entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(component(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось сменить значок на ${icon.name}", e)
            false
        }
    }
}
