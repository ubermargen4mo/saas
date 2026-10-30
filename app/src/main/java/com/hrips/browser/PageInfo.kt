package com.hrips.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * «Информация о сайте» (замок в адресной строке) в духе «Сведения об устройстве» из Pixel: кольцо со значком
 * состояния, адрес сайта, статус, круглые действия с подписью и карточки со сведениями.
 */
@Composable
fun SecurityDialog(tab: Tab, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val host = runCatching { Uri.parse(tab.url).host }.getOrNull() ?: tab.url
    val secure = tab.trust == Trust.SECURE && !tab.errorPage
    val (title, body) = when {
        tab.errorPage -> "Страница не загрузилась" to "Hrips не смог открыть $host. Причина написана на самой странице."
        tab.trust == Trust.SECURE -> "Соединение защищено" to "Данные между вами и $host шифруются."
        tab.secureException ->
            "Сертификат не проверен" to "Вы открыли $host в обход ошибки сертификата. Данные могут быть доступны посторонним."
        tab.url.startsWith("http://", ignoreCase = true) ->
            "Соединение не защищено" to "Сайт $host открыт без шифрования. Не вводите здесь пароли и данные карт."
        else -> "Нет данных о защите" to "Для этой страницы информации о безопасности нет."
    }
    val tone = if (secure) StatusOk else StatusWarn
    val protocol = when {
        tab.url.startsWith("https://", ignoreCase = true) -> "HTTPS"
        tab.url.startsWith("http://", ignoreCase = true) -> "HTTP"
        else -> null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = HripsShapes.XL, color = cs.surfaceContainerLow, contentColor = cs.onSurface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StatusRing(
                    filled = 3, total = 3,
                    center = tone,
                    icon = if (secure) HripsIcons.Lock else HripsIcons.Alert,
                    size = 104.dp,
                )
                Spacer(Modifier.height(16.dp))
                Text(host, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(title, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    InfoAction(HripsIcons.Link, "Копировать адрес", enabled = tab.url.isNotBlank() && !tab.home) {
                        val ok = runCatching {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("", tab.url))
                        }.isSuccess
                        Notices.show(if (ok) "Адрес скопирован" else "Не удалось скопировать")
                        onDismiss()
                    }
                    InfoAction(HripsIcons.Close, "Закрыть", onClick = onDismiss)
                }

                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val rows = buildList<@Composable (androidx.compose.ui.graphics.Shape) -> Unit> {
                        add { s -> PanelItem("Состояние", subtitle = body, icon = if (secure) HripsIcons.ShieldCheck else HripsIcons.Shield, shape = s) }
                        if (protocol != null) add { s -> PanelItem("Протокол", value = protocol, icon = HripsIcons.Lock, shape = s) }
                        tab.certIssuer?.takeIf { secure }?.let { issuer ->
                            add { s -> PanelItem("Сертификат выдан", subtitle = issuer, icon = HripsIcons.Info, shape = s) }
                        }
                    }
                    rows.forEachIndexed { i, row -> row(HripsShapes.segment(i, rows.size)) }
                }
            }
        }
    }
}

/** Круглое действие с подписью под ним, как «Забыть» / «Отключить» в сведениях об устройстве. */
@Composable
private fun InfoAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(28.dp),
            color = cs.inverseSurface,
            contentColor = cs.inverseOnSurface,
            modifier = Modifier.size(width = 84.dp, height = 56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
        }
        Text(
            label, Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.4f),
            textAlign = TextAlign.Center, maxLines = 2,
        )
    }
}
