package com.hrips.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Ограничения батареи для работы в фоне. На многих прошивках (MIUI/HyperOS, One UI, ColorOS и др.) режим
 * «Оптимизировано»/«Ограничено» выгружает свёрнутый браузер вместе со страницей даже при свободной памяти,
 * и никакой приоритет процесса этому не мешает. Режим «Без ограничений» снимает это.
 */
internal object BackgroundLimits {
    fun isUnrestricted(context: Context): Boolean =
        runCatching {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)

    /**
     * Открывает системный запрос «Разрешить работу в фоне без ограничений», а если прошивка его не поддерживает —
     * список оптимизации батареи или карточку приложения. Возвращает false, если не открылось ничего.
     * Прямой запрос (BatteryLife) допустим: приложение распространяется через GitHub Releases, не через Google Play.
     */
    @SuppressLint("BatteryLife")
    fun open(context: Context): Boolean {
        val pkg = Uri.parse("package:" + context.packageName)
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (e: SecurityException) {
                AppLog.w("Battery", "система не дала открыть настройки батареи", e)
            }
        }
        return false
    }
}
