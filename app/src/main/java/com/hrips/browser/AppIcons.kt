package com.hrips.browser

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Варианты значка приложения. Каждый = свой activity-alias в манифесте. */
enum class AppIcon(val title: String, val alias: String, val preview: Int) {
    BLUE("Синяя", "com.hrips.browser.LauncherBlue", R.drawable.icon_preview_blue),
    ORANGE("Оранжевая на сером", "com.hrips.browser.LauncherOrange", R.drawable.icon_preview_orange),
}

object AppIcons {
    private fun component(context: Context, icon: AppIcon) = ComponentName(context, icon.alias)

    /** Какой значок включён сейчас. Если ни один (не должно быть), считаем синий значением по умолчанию. */
    fun current(context: Context): AppIcon {
        val pm = context.packageManager
        val orangeOn = pm.getComponentEnabledSetting(component(context, AppIcon.ORANGE)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        return if (orangeOn) AppIcon.ORANGE else AppIcon.BLUE
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
