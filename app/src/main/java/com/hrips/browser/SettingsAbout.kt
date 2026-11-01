@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import android.content.ClipData
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay

@Composable
internal fun AboutPage(app: HripsApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val gecko = remember {
        runCatching {
            Class.forName("org.mozilla.geckoview.BuildConfig").getField("MOZ_APP_VERSION").get(null) as String
        }.getOrDefault("")
    }
    PageScaffold("О программе", onBack) {
        AboutHero(app, version, gecko)
        if (Updater.available) {
            Spacer(Modifier.height(24.dp))
            UpdateButton(app)
            SectionTitle("Обновления")
            Group(
                { s ->
                    PanelSwitch(
                        "Проверять автоматически", "Раз в сутки при запуске. Запрос идёт на github.com",
                        app.store.autoUpdate, { app.store.updateAutoUpdate(it) },
                        shape = s,
                    )
                },
            )
            (Updater.state as? Updater.State.Available)?.info?.notes?.takeIf { it.isNotBlank() }?.let {
                Callout(it, Modifier.padding(top = 12.dp), icon = HripsIcons.Package)
            }
        }
        SectionTitle("Диагностика")
        DiagnosticsGroup(app)
    }
}

@Composable
private fun DiagnosticsGroup(app: HripsApp) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(AppLog.sizeBytes(context)) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(app.store.logging) {
        while (true) {
            size = AppLog.sizeBytes(context)
            delay(2_000L)
        }
    }
    val path = remember {
        AppLog.file(context).absolutePath.let { it.substringAfter("/emulated/0/", it) }
    }
    val sizeText = when {
        size <= 0L -> "Журнал пуст"
        size < 1024L * 1024L -> "Размер ${(size + 1023L) / 1024L} КБ"
        else -> "Размер ${"%.1f".format(size / 1024.0 / 1024.0)} МБ"
    }
    Group(
        { s ->
            PanelSwitch(
                "Записывать журнал",
                "Для поиска ошибок: действия, сбои и зависания пишутся в файл. Адреса приватных вкладок не сохраняются",
                app.store.logging,
                {
                    app.store.updateLogging(it)
                    AppLog.setEnabled(it)
                    FrameJank.sync(context)
                },
                shape = s,
            )
        },
        { s ->
            PanelItem(
                "Отправить журнал", subtitle = sizeText, icon = HripsIcons.Share,
                enabled = !busy && size > 0L,
                onClick = {
                    busy = true
                    AppExecutors.startThread("hrips-log-share") {
                        val file = runCatching { AppLog.buildShareFile(context.applicationContext) }.getOrNull()
                        MainThread.handler.post {
                            busy = false
                            if (file == null) {
                                Notices.show("Журнал пуст")
                            } else {
                                try {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "Журнал hrips")
                                        clipData = ClipData.newRawUri("", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Отправить журнал"))
                                } catch (e: Exception) {
                                    AppLog.w("Settings", "не удалось отправить журнал", e)
                                    Notices.show("Не удалось отправить журнал")
                                }
                            }
                        }
                    }
                },
                shape = s,
            )
        },
        { s ->
            PanelItem("Файл журнала", subtitle = path, icon = HripsIcons.Folder, shape = s)
        },
        { s ->
            PanelItem(
                "Очистить журнал", icon = HripsIcons.Trash, enabled = size > 0L,
                onClick = {
                    AppLog.clear(context)
                    size = AppLog.sizeBytes(context)
                },
                shape = s,
            )
        },
    )
}
