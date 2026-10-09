@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private class Opt(val mode: Int, val title: String, val note: String)

private val httpsOptions = listOf(
    Opt(PrivacyEngine.HTTPS_OFF, "Разрешить HTTP", "Как раньше: сайты без шифрования открываются без предупреждения"),
    Opt(PrivacyEngine.HTTPS_PRIVATE, "Только в приватных вкладках", "Перед открытием сайта без HTTPS показывается предупреждение"),
    Opt(PrivacyEngine.HTTPS_ALL, "Во всех вкладках", "Сайт без HTTPS открывается только после вашего подтверждения"),
)

private val dohOptions = listOf(
    Opt(PrivacyEngine.DOH_OFF, "Выключен", "Адреса сайтов запрашиваются у DNS системы, обычно это DNS провайдера"),
    Opt(PrivacyEngine.DOH_AUTO, "Автоматически", "Через выбранного провайдера, при сбое запрос уходит обычным путём"),
    Opt(PrivacyEngine.DOH_STRICT, "Строго", "Только через провайдера: если он недоступен, сайты не откроются"),
)

/** Разделы страницы «Конфиденциальность»: приватность сайтов, HTTPS-only и защищённый DNS. */
@Composable
internal fun NetworkPrivacySection(browser: Browser) {
    val store = browser.store

    SectionTitle("Приватность сайтов")
    Group(
        { s ->
            SwitchRow(
                HripsIcons.Shield, "Сигнал Global Privacy Control",
                "Сайты получают просьбу не продавать и не передавать данные о вас",
                store.gpc, true, s,
            ) { store.updateGpc(it); browser.applyPrivacy() }
        },
        { s ->
            SwitchRow(
                HripsIcons.Block, "Удалять tracking-параметры",
                "Убирает известные рекламные и трекинговые параметры из адресов до загрузки страницы",
                store.stripTrackingParams, true, s,
            ) { store.updateStripTrackingParams(it); browser.applyPrivacy() }
        },
        { s ->
            SwitchRow(
                HripsIcons.Image, "Картинки страниц в карточках вкладок",
                "Если нет снимка экрана, браузер сам запрашивает у сайта превью (og:image). Приватные вкладки не затрагиваются",
                store.pageImages, true, s,
            ) { store.updatePageImages(it) }
        },
    )

    SectionTitle("Безопасное соединение (HTTPS)")
    GroupOf(httpsOptions) { o, s ->
        SettingsRow(
            null, o.title, o.note, s,
            onClick = { store.updateHttpsMode(o.mode); browser.applyPrivacy() },
            trailing = { RadioButton(selected = store.httpsMode == o.mode, onClick = null) },
        )
    }

    SectionTitle("Защищённый DNS (DoH)")
    GroupOf(dohOptions) { o, s ->
        SettingsRow(
            null, o.title, o.note, s,
            onClick = { store.updateDohMode(o.mode); browser.applyPrivacy() },
            trailing = { RadioButton(selected = store.dohMode == o.mode, onClick = null) },
        )
    }
    if (store.dohMode != PrivacyEngine.DOH_OFF) {
        SectionTitle("Провайдер DNS")
        // null в конце списка = «свой сервер»
        GroupOf(PrivacyEngine.dohProviders + listOf<DohProvider?>(null)) { p, s ->
            val id = p?.id ?: PrivacyEngine.CUSTOM
            SettingsRow(
                null, p?.name ?: "Свой сервер",
                p?.note ?: store.dohCustom.ifBlank { "Адрес вида https://dns.example/dns-query" }, s,
                onClick = { store.updateDohProvider(id); browser.applyPrivacy() },
                trailing = { RadioButton(selected = store.dohProvider == id, onClick = null) },
            )
        }
        if (store.dohProvider == PrivacyEngine.CUSTOM) CustomDoh(browser)
        Text(
            "DoH шифрует запросы адресов, но выбранный провайдер видит, какие сайты вы открываете. Выбирайте того, кому доверяете.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CustomDoh(browser: Browser) {
    val store = browser.store
    var text by remember { mutableStateOf(store.dohCustom) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; status = null },
            singleLine = true,
            label = { Text("Адрес DoH-сервера") },
            placeholder = { Text("https://dns.example/dns-query") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(shapes = ButtonDefaults.shapes(),
            enabled = !busy && PrivacyEngine.isValidDoh(text),
            onClick = {
                busy = true
                status = null
                val uri = text.trim()
                PrivacyEngine.probe(uri) { ok ->
                    busy = false
                    if (ok) {
                        store.updateDohCustom(uri)
                        browser.applyPrivacy()
                        status = "Сервер отвечает, адрес сохранён"
                    } else {
                        status = "Сервер не ответил как DoH, адрес не сохранён"
                    }
                }
            },
        ) { Text(if (busy) "Проверяю…" else "Проверить и сохранить") }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
