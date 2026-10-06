package com.hrips.browser

import android.content.Intent
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Меню "три точки": инструменты страницы, разделы браузера и быстрые переключатели. */
@Composable
fun ToolsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    browser: Browser,
    tab: Tab,
    onFind: () -> Unit,
    onLibrary: (page: Int) -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Новая вкладка") },
            leadingIcon = { Icon(HripsIcons.Add, null) },
            onClick = { onDismiss(); browser.newTab() },
        )
        DropdownMenuItem(
            text = { Text("Новая приватная вкладка") },
            leadingIcon = { Icon(HripsIcons.Mask, null) },
            onClick = { onDismiss(); browser.newTab(incognito = true) },
        )
        if (browser.privateCount > 0) {
            DropdownMenuItem(
                text = { Text("Закрыть приватные вкладки (${browser.privateCount})") },
                leadingIcon = { Icon(HripsIcons.Trash, null) },
                onClick = { onDismiss(); browser.closePrivateTabs() },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Найти на странице") },
            leadingIcon = { Icon(HripsIcons.Search, null) },
            enabled = !tab.home,
            onClick = { onDismiss(); onFind() },
        )
        DropdownMenuItem(
            text = { Text("Поделиться") },
            leadingIcon = { Icon(HripsIcons.Share, null) },
            enabled = !tab.home,
            onClick = {
                onDismiss()
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, tab.url)
                    .putExtra(Intent.EXTRA_SUBJECT, tab.title)
                context.startActivity(Intent.createChooser(send, null))
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Закладки") },
            leadingIcon = { Icon(HripsIcons.Star, null) },
            onClick = { onDismiss(); onLibrary(0) },
        )
        DropdownMenuItem(
            text = { Text("История") },
            leadingIcon = { Icon(HripsIcons.History, null) },
            onClick = { onDismiss(); onLibrary(1) },
        )
        DropdownMenuItem(
            text = { Text("Загрузки") },
            leadingIcon = { Icon(HripsIcons.Download, null) },
            onClick = { onDismiss(); onDownloads() },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Версия для ПК") },
            trailingIcon = { Switch(checked = tab.desktopMode, onCheckedChange = null) },
            onClick = { onDismiss(); tab.setDesktop(!tab.desktopMode) },
        )
        DropdownMenuItem(
            text = { Text("Блокировка рекламы") },
            trailingIcon = { Switch(checked = browser.adBlock.enabled, onCheckedChange = null) },
            enabled = browser.adBlock.extension != null,
            onClick = {
                onDismiss()
                browser.adBlock.setBlocking(!browser.adBlock.enabled)
                tab.session.reload()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Настройки") },
            leadingIcon = { Icon(HripsIcons.Settings, null) },
            onClick = { onDismiss(); onSettings() },
        )
    }
}
