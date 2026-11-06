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
import androidx.compose.runtime.mutableIntStateOf
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
            StatusCheck("Метки отслеживания в адресах", store.stripTrackingParams, "Удаляются до загрузки страницы", "Не удаляются. Нажмите, чтобы включить") {
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
                    "Строгая защита",
                    if (on) "Ещё скрытые трекеры, майнеры, сборщики отпечатков и защита от отпечатков во всех вкладках. " +
                        "Вход и оплата на части сайтов могут сломаться. Действует после перезагрузки страницы"
                    else "Сначала включите защиту от трекеров",
                    on && store.strictProtection, { browser.setStrictProtection(it) },
                    enabled = on, shape = s,
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

        ClearOnExitSection(store)
        if (showClear) ClearDataSheet(browser) { showClear = false }
    }
}

@Composable
internal fun PermissionsPage(app: HripsApp, onSitePermissions: () -> Unit, onBack: () -> Unit) {
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

        WebPushSection(app)
    }
}

@Composable
private fun ClearOnExitSection(store: Store) {
    val mask = store.clearOnExitMask
    fun toggle(bit: Int, on: Boolean) = store.updateClearOnExitMask(if (on) mask or bit else mask and bit.inv())
    SectionTitle("При выходе")
    Group(
        { s ->
            PanelSwitch(
                "Кнопка «Выйти» в меню",
                if (store.clearOnExit) "Закрывает браузер полностью. Ниже можно отметить, что при этом удалять, по умолчанию ничего"
                else "Чтобы закрывать браузер полностью и, если нужно, удалять данные. Свернуть или смахнуть приложение не считается выходом",
                store.clearOnExit, { store.updateClearOnExit(it) },
                icon = HripsIcons.Close, shape = s,
            )
        },
    )
    Reveal(store.clearOnExit) {
        Column {
            SectionTitle("Удалять при выходе")
            Group(
                { s -> PanelSwitch("Открытые вкладки", "И недавно закрытые", ExitClear.has(mask, ExitClear.TABS), { toggle(ExitClear.TABS, it) }, icon = HripsIcons.Layers, shape = s) },
                { s -> PanelSwitch("История и запросы", null, ExitClear.has(mask, ExitClear.HISTORY), { toggle(ExitClear.HISTORY, it) }, icon = HripsIcons.History, shape = s) },
                { s -> PanelSwitch("Cookies и данные сайтов", "Вы выйдете из аккаунтов, push-подписки сайтов снимутся", ExitClear.has(mask, ExitClear.COOKIES), { toggle(ExitClear.COOKIES, it) }, icon = HripsIcons.Globe, shape = s) },
                { s -> PanelSwitch("Кэш и превью вкладок", null, ExitClear.has(mask, ExitClear.CACHE), { toggle(ExitClear.CACHE, it) }, icon = HripsIcons.Package, shape = s) },
            )
        }
    }
}

/** Уведомления сайтов и веб-push: по умолчанию через Google (FCM), по желанию через приложение UnifiedPush. */
@Composable
private fun WebPushSection(app: HripsApp) {
    val store = app.store
    val webPush = app.webPush
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val google = remember(tick) { webPush.hasGoogleServices() }
    val external = remember(tick, store.webPush) { webPush.externalDistributors() }
    val current = remember(tick, store.webPush, store.pushVia) { webPush.distributor() }
    val count = remember(tick, store.webPush, store.pushVia) { webPush.subscriptionCount() }
    val options = remember(google, external) {
        buildList {
            if (google) add(app.packageName to "Google (FCM)")
            external.forEach { add(it to webPush.labelOf(it)) }
        }
    }
    val canPush = options.isNotEmpty()

    SectionTitle("Уведомления сайтов")
    Group(
        { s ->
            PanelSwitch(
                "Уведомления сайтов",
                if (store.siteNotifications) "Сайты могут попросить разрешение присылать уведомления"
                else "Выключены: сайты не спрашивают разрешение, уведомления не показываются",
                store.siteNotifications, { store.updateSiteNotifications(it) },
                icon = HripsIcons.Bell, shape = s,
            )
        },
        { s ->
            PanelSwitch(
                "Push, когда браузер закрыт",
                when {
                    !canPush -> "Нужны сервисы Google Play или приложение UnifiedPush (ntfy, Sunup и др.)"
                    store.webPush && current != null -> "Через ${webPush.labelOf(current)}. Подписок сайтов: $count"
                    store.webPush -> "Способ доставки выберется при первой подписке сайта"
                    else -> "Уведомления придут только пока браузер открыт"
                },
                store.webPush && canPush,
                { on ->
                    if (on) {
                        if (!webPush.enable()) Notices.show("Нет сервисов Google Play и приложений UnifiedPush")
                    } else {
                        webPush.disable()
                    }
                    tick++
                },
                enabled = store.siteNotifications && (canPush || store.webPush),
                icon = HripsIcons.Globe, shape = s,
            )
        },
    )
    Reveal(store.siteNotifications && store.webPush && options.size > 1) {
        Column(Modifier.padding(top = 8.dp)) {
            Group(
                { s ->
                    val selected = current ?: options.first().first
                    DropdownRow(
                        "Способ доставки",
                        "При смене подписки сайтов снимутся, сайты подпишутся заново",
                        options.firstOrNull { it.first == selected }?.second ?: options.first().second,
                        options, selected,
                        { pkg -> webPush.select(pkg); tick++ },
                        shape = s,
                    )
                },
            )
        }
    }
    Callout(
        if (google && (current == null || current == app.packageName))
            "Push идут через серверы Google, как в Chrome: ничего устанавливать не нужно. Сообщения зашифрованы для браузера, Google их не читает"
        else
            "Уведомление приходит, только если сайт поддерживает Web Push и вы разрешили ему уведомления. Сообщения зашифрованы для браузера, посредник их не читает",
        Modifier.padding(top = 8.dp),
    )
}
