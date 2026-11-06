@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    var showNotes by remember { mutableStateOf(false) }
    val updateInfo = Updater.info
    if (showNotes && updateInfo != null && updateInfo.notes.isNotBlank()) {
        ChangelogPage("Что нового в ${updateInfo.versionName}", updateInfo.notes) { showNotes = false }
        return
    }
    PageScaffold("О программе", onBack) {
        AboutHero(app, version, gecko)
        if (Updater.available) {
            Spacer(Modifier.height(24.dp))
            UpdateButton(app)
            SectionTitle("Обновления")
            val hasNotes = updateInfo?.notes?.isNotBlank() == true
            GroupOf(if (hasNotes) listOf("notes", "auto") else listOf("auto")) { row, s ->
                if (row == "notes" && updateInfo != null) {
                    SettingsRow(
                        HripsIcons.Package, "Что нового в ${updateInfo.versionName}", "Список изменений этой версии",
                        s, onClick = { showNotes = true },
                    )
                } else {
                    PanelSwitch(
                        "Проверять автоматически", "Раз в сутки при запуске. Запрос идёт на github.com",
                        app.store.autoUpdate, { app.store.updateAutoUpdate(it) },
                        shape = s,
                    )
                }
            }
        }
        SectionTitle("Диагностика")
        DiagnosticsGroup(app)
    }
}

/** Заметки к обновлению на всю страницу, с разметкой (заголовки, списки, жирный, ссылки). */
@Composable
internal fun ChangelogPage(title: String, notes: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // Первая строка `## Что нового` нужна CI (по ней он вырезает раздел для релиза) и повторяла бы заголовок страницы.
    val body = remember(notes) {
        val lines = notes.lines().dropWhile { it.isBlank() }
        (if (lines.firstOrNull()?.startsWith("## ") == true) lines.drop(1) else lines).joinToString("\n").trim()
    }
    PageScaffold(title, onBack) {
        MarkdownText(body, Modifier.widthIn(max = HripsLayout.PageMaxWidth).padding(top = 8.dp, bottom = 24.dp))
    }
}

@Composable
private fun DiagnosticsGroup(app: HripsApp) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(app.store.logging) {
        while (true) {
            size = withContext(Dispatchers.IO) { AppLog.sizeBytes(context) }
            if (!app.store.logging) break
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
