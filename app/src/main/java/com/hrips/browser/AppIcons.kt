package com.hrips.browser

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/** Варианты значка приложения. Каждый = свой activity-alias в манифесте. */
enum class AppIcon(val title: String, val alias: String, val preview: Int) {
    BLUE("Синяя", "com.hrips.browser.LauncherBlue", R.drawable.icon_preview_blue),
    ORANGE("Оранжевая", "com.hrips.browser.LauncherOrange", R.drawable.icon_preview_orange),
    BW("Чёрно-белая", "com.hrips.browser.LauncherBw", R.drawable.icon_preview_bw),
}

object AppIcons {
    private const val TAG = "AppIcons"

    /** Значок, который включён в манифесте и считается основным, пока выбор не менялся. */
    private val default = AppIcon.BLUE

    private fun component(context: Context, icon: AppIcon) = ComponentName(context, icon.alias)

    private fun state(context: Context, icon: AppIcon) =
        context.packageManager.getComponentEnabledSetting(component(context, icon))

    /** Какой значок включён сейчас. Если ни один не включён явно, это основной (синий). */
    fun current(context: Context): AppIcon =
        AppIcon.entries.firstOrNull {
            it != default && state(context, it) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: default

    /**
     * Включает выбранный значок и выключает остальные. Сначала включаем новый, потом выключаем старые,
     * чтобы в лаунчере ни на миг не остаться без значка. DONT_KILL_APP: приложение не закрывается
     * (на некоторых лаунчерах значок обновляется с небольшой задержкой).
     * Возвращает false, если система отказала (тогда значок остаётся прежним).
     */
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
