@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopPage(browser: Browser, onBack: () -> Unit) {
    val sites = browser.store.desktopSites.toList()
    PageScaffold("Версия для ПК", onBack) {
        Callout(
            "По умолчанию на всех сайтах открывается мобильная версия. Включить версию для ПК можно в меню страницы (⋮): она запомнится только для этого сайта.",
            icon = HripsIcons.Desktop,
        )
        SectionTitle("Сайты")
        if (sites.isEmpty()) {
            HeroBanner(
                title = "Пока нет ни одного сайта",
                subtitle = "Сайты с включённой версией для ПК появятся здесь",
                icon = HripsIcons.Desktop,
                badge = Badge.BURST,
                tone = 2,
            )
        } else {
            GroupOf(sites) { host, s ->
                PanelItem(
                    host, shape = s, subtitle = "Версия для ПК включена", icon = HripsIcons.Desktop,
                    trailing = {
                        IconButton(onClick = { browser.setSiteDesktop(host, false) }) { Icon(HripsIcons.Trash, "Выключить") }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            HripsTextButton(onClick = { browser.resetDesktopSites() }) { Text("Выключить на всех сайтах") }
        }
    }
}

@Composable
internal fun MediaPage(store: Store, onBack: () -> Unit) {
    PageScaffold("Медиа", onBack) {
        Group(
            { s ->
                PanelSwitch(
                    "Управление в уведомлении",
                    "Пауза, перемотка и треки в шторке и на экране блокировки. Звук продолжает играть при выключенном экране",
                    store.mediaControls, { store.updateMediaControls(it) }, icon = HripsIcons.NotificationsLine,
                    shape = s,
                )
            },
            { s ->
                PanelSwitch(
                    "Картинка в картинке",
                    "Видео на весь экран уходит в маленькое окно, когда вы выходите на главный экран",
                    store.pipEnabled, { store.updatePip(it) }, icon = HripsIcons.Pip,
                    shape = s,
                )
            },
        )
    }
}

@Composable
internal fun PerformancePage(browser: Browser, onBack: () -> Unit) {
    val store = browser.store
    PageScaffold("Производительность", onBack) {
        Group(
            { s ->
                PanelSwitch(
                    "Экономия памяти",
                    "При сильной нехватке памяти фоновые вкладки временно выгружаются. При возврате страница восстанавливается.",
                    store.suspendTabsOnMemoryPressure, { store.updateSuspendTabsOnMemoryPressure(it) },
                    shape = s,
                )
            },
            { s ->
                PanelSwitch(
                    "Держать страницу при сворачивании",
                    "Текущая страница остаётся в памяти до 20 минут после сворачивания, чтобы не перезагружаться при возврате. Занимает больше памяти в фоне.",
                    store.keepPageInBackground, { store.updateKeepPageInBackground(it) },
                    shape = s,
                )
            },
        )
        Callout(
            "Включение может приводить к повторной загрузке тяжёлых страниц. Текущая вкладка и активное воспроизведение не выгружаются.",
            Modifier.padding(top = 12.dp), icon = HripsIcons.Gauge,
        )
    }
}

private fun folderLabel(tree: String?): String {
    if (tree == null) return "Загрузки (системная папка)"
    return runCatching {
        val id = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree))
        id.substringAfter(':', "").ifBlank { "Внутренняя память" }
    }.getOrDefault("Выбранная папка")
}

@Composable
internal fun DownloadsPage(browser: Browser, onBack: () -> Unit, onOpenDownloads: () -> Unit) {
    val store = browser.store
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            store.updateDlTree(uri.toString())
        }
    }
    PageScaffold("Загрузки", onBack) {
        Group(
            { s ->
                PanelSwitch(
                    "Спрашивать перед загрузкой", "Показывать имя файла и размер, чтобы подтвердить или отменить",
                    store.askBeforeDownload, { store.updateAskBeforeDownload(it) },
                    shape = s,
                )
            },
            { s ->
                PanelItem(
                    "Папка загрузок", subtitle = folderLabel(store.dlTree), icon = HripsIcons.Folder,
                    onClick = { picker.launch(null) },
                    trailing = { Icon(HripsIcons.ChevronForward.variant(1.8f, 12f / 13f), null, tint = cs.onSurfaceVariant) },
                    shape = s,
                )
            },
        )
        if (store.dlTree != null) {
            HripsTextButton(
                onClick = {
                    store.dlTree?.let { t ->
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                Uri.parse(t), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                    }
                    store.updateDlTree(null)
                },
            ) { Text("Вернуть системную папку «Загрузки»") }
        }
        Callout(
            "Новые загрузки сохраняются в выбранную папку. Файлы, скачанные раньше, остаются на месте.",
            Modifier.padding(top = 8.dp), icon = HripsIcons.Folder,
        )
    }
}
