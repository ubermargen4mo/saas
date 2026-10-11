package com.hrips.browser

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Варианты значка приложения. Каждый = свой activity-alias в манифесте. */
enum class AppIcon(val title: String, val alias: String, val preview: Int) {
    BLUE("Синяя", "com.hrips.browser.LauncherBlue", R.drawable.icon_preview_blue),
    ORANGE("Оранжевая", "com.hrips.browser.LauncherOrange", R.drawable.icon_preview_orange),
    BW("Чёрно-белая", "com.hrips.browser.LauncherBw", R.drawable.icon_preview_bw),
}

object AppIcons {
    private fun component(context: Context, icon: AppIcon) = ComponentName(context, icon.alias)

    /** Какой значок включён сейчас. Если ни один не включён явно, это синий (он включён в манифесте). */
    fun current(context: Context): AppIcon {
        val pm = context.packageManager
        return AppIcon.entries.firstOrNull {
            it != AppIcon.BLUE && pm.getComponentEnabledSetting(component(context, it)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: AppIcon.BLUE
    }

    /**
     * Включает выбранный значок и выключает остальные. Сначала включаем новый, потом выключаем старый,
     * чтобы в лаунчере ни на миг не остаться без значка. DONT_KILL_APP: приложение не закрывается
     * (на некоторых лаунчерах значок обновляется с небольшой задержкой).
     */
    fun set(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(component(context, icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        AppIcon.entries.filter { it != icon }.forEach {
            pm.setComponentEnabledSetting(component(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
