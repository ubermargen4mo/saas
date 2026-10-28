package com.hrips.browser

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun SecurityDialog(tab: Tab, onDismiss: () -> Unit) {
    val host = runCatching { Uri.parse(tab.url).host }.getOrNull() ?: tab.url
    val (title, body) = when {
        tab.errorPage -> "Страница не загрузилась" to "Hrips не смог открыть $host. Причина написана на самой странице."
        tab.trust == Trust.SECURE ->
            "Соединение защищено" to ("Данные между вами и $host шифруются." +
                (tab.certIssuer?.let { "\nСертификат выдан: $it." } ?: ""))
        tab.secureException ->
            "Сертификат не проверен" to "Вы открыли $host в обход ошибки сертификата. Данные могут быть доступны посторонним."
        tab.url.startsWith("http://", ignoreCase = true) ->
            "Соединение не защищено" to "Сайт $host открыт без шифрования. Не вводите здесь пароли и данные карт."
        else -> "Нет данных о защите" to "Для этой страницы информации о безопасности нет."
    }
    HripsDialog(
        icon = if (tab.trust == Trust.SECURE) HripsIcons.Lock else HripsIcons.Alert,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { HripsTextButton(onClick = onDismiss) { Text("OK") } },
    )
}
