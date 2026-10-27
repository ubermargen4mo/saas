@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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

private class Opt(val mode: Int, val short: String, val note: String)

private val openInAppsOptions = listOf(
    Opt(ExternalLinks.MODE_ASK, "Спрашивать", "Если ссылку открывает установленное приложение (YouTube, Telegram и др.), браузер предложит перейти в него"),
    Opt(ExternalLinks.MODE_ALWAYS, "В приложении", "Переход сразу в приложение, без вопроса"),
    Opt(ExternalLinks.MODE_NEVER, "В браузере", "Ссылки всегда открываются здесь"),
)

private val httpsOptions = listOf(
    Opt(PrivacyEngine.HTTPS_OFF, "Разрешить", "Как раньше: сайты без шифрования открываются без предупреждения"),
    Opt(PrivacyEngine.HTTPS_PRIVATE, "Приватные", "Только в приватных вкладках: перед открытием сайта без HTTPS показывается предупреждение"),
    Opt(PrivacyEngine.HTTPS_ALL, "Все вкладки", "Сайт без HTTPS открывается только после вашего подтверждения"),
)

private val dohOptions = listOf(
    Opt(PrivacyEngine.DOH_OFF, "Выкл", "Адреса сайтов запрашиваются у DNS системы, обычно это DNS провайдера"),
    Opt(PrivacyEngine.DOH_AUTO, "Авто", "Через выбранного провайдера, при сбое запрос уходит обычным путём"),
    Opt(PrivacyEngine.DOH_STRICT, "Строго", "Только через провайдера: если он недоступен, сайты не откроются"),
)

@Composable
private fun OptionTiles(options: List<Opt>, selected: Int, onSelect: (Int) -> Unit) {
    TileSwitcher(
        options = options.map { it.mode to it.short },
        selected = selected,
        onSelect = onSelect,
        description = { m -> options.firstOrNull { it.mode == m }?.note },
        showCheck = false,
    )
}

@Composable
internal fun NetworkPrivacySection(browser: Browser) {
    val store = browser.store

    SectionTitle("Приватность сайтов")
    Panel {
        PanelSwitch(
            "Сигнал Global Privacy Control",
            "Сайты получают просьбу не продавать и не передавать данные о вас",
            store.gpc, { store.updateGpc(it); browser.applyPrivacy() },
        )
        PanelDivider()
        PanelSwitch(
            "Удалять tracking-параметры",
            "Убирает известные рекламные и трекинговые параметры из адресов до загрузки страницы",
            store.stripTrackingParams, { store.updateStripTrackingParams(it); browser.applyPrivacy() },
        )
        PanelDivider()
        PanelSwitch(
            "Картинки страниц в карточках вкладок",
            "Если нет снимка экрана, браузер сам запрашивает у сайта превью (og:image). Приватные вкладки не затрагиваются",
            store.pageImages, { store.updatePageImages(it) },
        )
    }

    SectionTitle("Ссылки в приложениях")
    OptionTiles(openInAppsOptions, store.openInApps) { store.updateOpenInApps(it) }

    SectionTitle("Безопасное соединение (HTTPS)")
    OptionTiles(httpsOptions, store.httpsMode) { store.updateHttpsMode(it); browser.applyPrivacy() }

    SectionTitle("Защищённый DNS (DoH)")
    OptionTiles(dohOptions, store.dohMode) { store.updateDohMode(it); browser.applyPrivacy() }
    Reveal(store.dohMode != PrivacyEngine.DOH_OFF) {
        Column {
            Spacer(Modifier.height(12.dp))
            val current = PrivacyEngine.dohProviders.firstOrNull { it.id == store.dohProvider }
            Panel {
                DropdownRow(
                    title = "Провайдер DNS",
                    subtitle = current?.note ?: store.dohCustom.ifBlank { "Адрес вида https://dns.example/dns-query" },
                    value = current?.name ?: "Свой сервер",
                    options = PrivacyEngine.dohProviders.map { it.id to it.name } + (PrivacyEngine.CUSTOM to "Свой сервер"),
                    selectedId = store.dohProvider,
                    onSelect = { store.updateDohProvider(it); browser.applyPrivacy() },
                )
            }
            if (store.dohProvider == PrivacyEngine.CUSTOM) CustomDoh(browser)
            Callout(
                "DoH шифрует запросы адресов, но выбранный провайдер видит, какие сайты вы открываете. Выбирайте того, кому доверяете.",
                Modifier.padding(top = 8.dp),
            )
        }
    }
    Callout(
        "DoH действует на страницы в движке. Мелкие служебные запросы приложения (значки сайтов, картинки для превью вкладок, проверка адреса DoH) идут через системный DNS. В приватных вкладках они не делаются.",
        Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun CustomDoh(browser: Browser) {
    val store = browser.store
    var text by remember { mutableStateOf(store.dohCustom) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HripsField(
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
