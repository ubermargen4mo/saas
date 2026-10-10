@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Экран первого запуска: все разрешения просим сразу, одним заходом.
 * Шаг 1: камера, микрофон, местоположение, уведомления (системные окна Android подряд).
 * Шаг 2: установка приложений из браузера (.apk), это отдельная страница системных настроек.
 * Дальше сайты спрашивают только своё разрешение ("можно ли этому сайту..."), системные окна не появляются.
 */
@Composable
fun FirstRunScreen(store: Store, permissions: Permissions) {
    val context = LocalContext.current

    // После обновления или переустановки с уже выданными правами не показываем лишний шаг
    LaunchedEffect(Unit) {
        if (permissions.firstRunStage == 0 && permissions.missing().isEmpty()) permissions.firstRunStage = 1
    }
    val stage = permissions.firstRunStage
    val installOk = permissions.canInstallApks()
    LaunchedEffect(stage, installOk) {
        if (stage == 1 && installOk) store.finishFirstRun()
    }
    if (stage == 1 && installOk) return

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text("hrips", style = WordmarkStyle.copy(fontSize = 44.sp), color = MaterialTheme.colorScheme.primary)
                    if (stage == 0) {
                        Text("Нужны разрешения", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Разрешите всё сейчас, и браузер больше не будет отвлекать системными окнами посреди страницы. " +
                                "Сайты по-прежнему спросят своё согласие, прежде чем что-то использовать.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PermissionRow(HripsIcons.Camera, "Камера", "Видеозвонки и фото на сайтах")
                        PermissionRow(HripsIcons.Mic, "Микрофон", "Голосовые сообщения и звонки")
                        PermissionRow(HripsIcons.Pin, "Местоположение", "Карты и поиск рядом с вами")
                        PermissionRow(HripsIcons.Bell, "Уведомления", "Загрузки, управление музыкой и видео, уведомления сайтов")
                        Spacer(Modifier.height(4.dp))
                        Button(shapes = ButtonDefaults.shapes(),
                            onClick = {
                                val need = permissions.missing()
                                if (need.isEmpty()) permissions.firstRunStage = 1
                                else permissions.requestFirstRun?.invoke(need.toTypedArray()) ?: run { permissions.firstRunStage = 1 }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text("Разрешить", style = MaterialTheme.typography.titleMedium) }
                        HripsTextButton(onClick = { store.finishFirstRun() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Не сейчас")
                        }
                    } else {
                        Text("Последний шаг", style = MaterialTheme.typography.headlineMedium)
                        PermissionRow(
                            HripsIcons.Package, "Установка приложений",
                            "Нужна, только если вы скачиваете файлы .apk. Откроется страница настроек Android: включите переключатель для hrips.",
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(shapes = ButtonDefaults.shapes(),
                            onClick = {
                                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                                }
                                store.finishFirstRun()
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text("Открыть настройки", style = MaterialTheme.typography.titleMedium) }
                        HripsTextButton(onClick = { store.finishFirstRun() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Пропустить", textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        ShapeBadge(
            icon, 52.dp,
            badge = Badge.entries[Math.floorMod(title.hashCode(), Badge.entries.size)],
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
