package com.hrips.browser

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Нажатие на замок в адресной строке: что именно известно о защите этой страницы. */
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
        tab.url.startsWith("http://") ->
            "Соединение не защищено" to "Сайт $host открыт без шифрования. Не вводите здесь пароли и данные карт."
        else -> "Нет данных о защите" to "Для этой страницы информации о безопасности нет."
    }
    HripsDialog(
        icon = if (tab.trust == Trust.SECURE) HripsIcons.Lock else HripsIcons.Alert,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
