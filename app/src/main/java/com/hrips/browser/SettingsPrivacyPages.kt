@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private class StatusCheck(val title: String, val ok: Boolean, val okText: String, val badText: String, val fix: () -> Unit)

@Composable
internal fun PrivacyPage(browser: Browser, app: HripsApp, onBack: () -> Unit) {
    val store = browser.store
    val adBlock = app.adBlock
    val context = LocalContext.current
    var showClear by remember { mutableStateOf(false) }

    PageScaffold("Конфиденциальность", onBack) {
        val on = store.trackingProtection
        val checks = listOf(
            StatusCheck("Защита от трекеров", on, "Блокирует трекеры и слежку", "Выключена. Нажмите, чтобы включить") { browser.setTracking(true) },
            StatusCheck("Global Privacy Control", store.gpc, "Сайты просят не продавать ваши данные", "Выключен. Нажмите, чтобы включить") {
                store.updateGpc(true); browser.applyPrivacy()
            },
            StatusCheck("Tracking-параметры в адресах", store.stripTrackingParams, "Удаляются до загрузки страницы", "Не удаляются. Нажмите, чтобы включить") {
                store.updateStripTrackingParams(true); browser.applyPrivacy()
            },
            StatusCheck("Соединение HTTPS", store.httpsMode != PrivacyEngine.HTTPS_OFF, "Предупреждение о сайтах без шифрования", "Без предупреждений. Нажмите, чтобы включить") {
                store.updateHttpsMode(PrivacyEngine.HTTPS_ALL); browser.applyPrivacy()
            },
            StatusCheck("Защищённый DNS", store.dohMode != PrivacyEngine.DOH_OFF, "Запросы адресов шифруются", "Выключен. Нажмите, чтобы включить") {
                store.updateDohMode(PrivacyEngine.DOH_AUTO); browser.applyPrivacy()
            },
            StatusCheck("Скриншоты приватных вкладок", !store.allowPrivateShots, "Запрещены", "Разрешены. Нажмите, чтобы запретить") {
                store.updatePrivateShots(false)
            },
        )
        val okCount = checks.count { it.ok }
        val allOk = okCount == checks.size
        val tone = when {
            allOk -> StatusOk
            okCount * 3 <= checks.size -> StatusBad
            else -> StatusWarn
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusRing(
                filled = okCount, total = checks.size,
                center = tone,
                arc = tone,
                icon = if (allOk) HripsIcons.Check else HripsIcons.Alert,
                size = 120.dp,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (allOk) "Защита включена" else if (tone == StatusBad) "Защита почти не включена" else "Защиту можно улучшить",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                if (allOk) "Все проверки пройдены" else "Проверьте настройки ($okCount из ${checks.size})",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Column(Modifier.fillMaxWidth()) {
            checks.forEach { c -> StatusRow(c.ok, c.title, if (c.ok) c.okText else c.badText, onClick = if (c.ok) null else c.fix) }
        }

        SectionTitle("Защита")
        Group(
            { s ->
                PanelSwitch(
                    "Блокировка рекламы",
                    when {
                        adBlock.error != null -> "Недоступна"
                        adBlock.extension == null -> "Загрузка…"
                        else -> "Скрывает рекламу на сайтах (uBlock Origin)"
                    },
                    adBlock.enabled, { adBlock.setBlocking(it) },
                    enabled = adBlock.extension != null, shape = s,
                )
            },
            { s ->
                PanelSwitch(
                    "Защита от трекеров", "Если сайт работает неправильно, выключите и перезагрузите страницу",
                    on, { browser.setTracking(it) },
                    shape = s,
                )
            },
            { s ->
                PanelSwitch(
                    "Скриншоты в приватных вкладках", "По умолчанию запрещены, а миниатюра в списке приложений скрыта",
                    store.allowPrivateShots, { store.updatePrivateShots(it) },
                    shape = s,
                )
            },
        )

        NetworkPrivacySection(browser)

        SectionTitle("Очистить данные")
        Group(
            { s ->
                PanelItem(
                    "Очистить данные", subtitle = "История, cookies, кэш", icon = HripsIcons.Trash,
                    onClick = { showClear = true },
                    trailing = { Icon(HripsIcons.ChevronForward.variant(1.8f, 12f / 13f), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    shape = s,
                )
            },
        )
        if (showClear) ClearDataSheet(browser) { showClear = false }
    }
}

@Composable
internal fun PermissionsPage(onSitePermissions: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val openApp = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + context.packageName))
            )
        }
    }
    val system = buildList {
        add(SystemPerm("Камера", HripsIcons.Camera, granted(Manifest.permission.CAMERA)))
        add(SystemPerm("Микрофон", HripsIcons.Mic, granted(Manifest.permission.RECORD_AUDIO)))
        add(SystemPerm("Геопозиция", HripsIcons.Globe, granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)))
        if (Build.VERSION.SDK_INT >= 33) add(SystemPerm("Уведомления", HripsIcons.Bell, granted(Manifest.permission.POST_NOTIFICATIONS)))
    }

    PageScaffold("Разрешения", onBack) {
        PermissionsSummary(system, onClick = { openApp() })

        SectionTitle("Сайты")
        Group(
            { s ->
                SettingsRow(
                    HripsIcons.ShieldCheck, "Разрешения сайтов", "Что вы разрешили или запретили сайтам. Можно отозвать", s,
                    onClick = onSitePermissions,
                    trailing = { Icon(HripsIcons.ChevronForward.variant(1.8f, 12f / 13f), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            },
        )
    }
}
