@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.hrips.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.launch

private class PageAction(
    val icon: ImageVector,
    val label: String,
    val active: Boolean = false,
    val onClick: () -> Unit,
)

private const val PAGE_COLS = 3

/**
 * «Параметры страницы» (телефон): шторка, которую открывает левая кнопка адресной капсулы нижней панели.
 * Все действия над текущей страницей, раньше лежавшие в меню «⋯»: шапка страницы, тумблеры «ПК-версия» и
 * «Без рекламы», сетка «Эта страница» (сегменты, большие внешние углы) и безопасность соединения.
 * Пунктов, которых в браузере нет («Скрыть отвлекающие объекты», «Слушать страницу», размер текста), здесь нет.
 */
@Composable
internal fun PageOptionsSheet(
    onDismiss: () -> Unit,
    browser: Browser,
    tab: Tab,
    onFind: () -> Unit,
    onScreenshot: () -> Unit,
    onQr: () -> Unit,
    onSecurity: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val store = browser.store
    val hasSite = tab.siteHost() != null

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val act: (() -> Unit) -> () -> Unit = { action -> { close(); action() } }

    val saved = store.isBookmarked(tab.url)
    val actions = buildList {
        add(PageAction(if (saved) HripsIcons.StarFilled else HripsIcons.Star, if (saved) "В закладках" else "Закладка", saved, act {
            toggleBookmarkWithNotice(store, tab.url, tab.title)
        }))
        add(PageAction(HripsIcons.FindInPage, "Найти", onClick = act { onFind() }))
        add(PageAction(HripsIcons.Qr, "QR-код", onClick = act { onQr() }))
        if (hasSite) {
            add(PageAction(HripsIcons.Reader, if (tab.readerActive) "Закрыть чтение" else "Чтение", tab.readerActive, act { tab.toggleReader() }))
        }
        if (hasSite && !PageActions.isTranslated(tab)) {
            val back = tab.translated || tab.translating
            add(PageAction(HripsIcons.Translate, if (back) "Оригинал" else "Перевести", back, act { PageActions.translate(tab, browser) }))
        }
        if (hasSite) add(PageAction(HripsIcons.Open, "В приложении", onClick = act { PageActions.openInApp(context, tab.url) }))
        add(PageAction(HripsIcons.FilePdf, "PDF", onClick = act { PageActions.savePdf(tab, browser.downloads) }))
        add(PageAction(HripsIcons.Print, "Печать", onClick = act { PagePrint.print(context, tab) }))
        add(PageAction(HripsIcons.Capture, "Снимок", onClick = act { onScreenshot() }))
        add(PageAction(HripsIcons.Home, "На старт", onClick = act { PageActions.addToStartPage(context, store, tab) }))
        if (hasSite && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            add(PageAction(HripsIcons.Phone, "Ярлык", onClick = act { PageActions.addToHomeScreen(context, tab) }))
        }
    }

    val (letter, title, subtitle) = toolsHeaderOf(tab)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surfaceContainerLow,
        shape = HripsShapes.XL,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToolsHeader(letter, title, subtitle, Modifier.toolsIn(0), tab = tab)

            Row(Modifier.fillMaxWidth().toolsIn(1), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (hasSite) {
                    ToolToggle(HripsIcons.Desktop, "ПК-версия", tab.desktopMode, true, Modifier.weight(1f)) {
                        val next = !tab.desktopMode
                        haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        tab.setDesktop(next)
                    }
                }
                ToolToggle(HripsIcons.Block, "Без рекламы", browser.adBlock.enabled, browser.adBlock.extension != null, Modifier.weight(1f)) {
                    val next = !browser.adBlock.enabled
                    haptic.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    browser.adBlock.setBlocking(next) { tab.reload() }
                }
            }

            Column(Modifier.fillMaxWidth().toolsIn(2)) {
                Text(
                    "Эта страница",
                    Modifier.padding(start = 12.dp, top = 2.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    actions.chunked(PAGE_COLS).forEachIndexed { r, line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            line.forEachIndexed { c, a ->
                                SegTile(
                                    a.icon, a.label, segCorners(actions.size, PAGE_COLS, r * PAGE_COLS + c, 24.dp, 8.dp),
                                    Modifier.weight(1f), height = 78.dp, on = a.active,
                                    labelSize = 12.5.sp, labelLines = 1, onClick = a.onClick,
                                )
                            }
                            repeat(PAGE_COLS - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            if (hasSite) {
                Column(Modifier.fillMaxWidth().toolsIn(3)) {
                    ToolRow(
                        if (tab.trust == Trust.SECURE) HripsIcons.BarShield else HripsIcons.BarInfo,
                        "Безопасность соединения",
                        Corners.all(24.dp),
                        danger = tab.trust == Trust.WARNING,
                        onClick = act { onSecurity() },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
