@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.hrips.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val ThemeLight = Color(0xFFFFFFFF)
private val ThemeSepia = Color(0xFFF4ECD8)
private val ThemeDark = Color(0xFF16161A)

/**
 * Шторка режима чтения: открывается нажатием на значок чтения в адресной строке (вместо меню сайта).
 * Сверху название и выбор шрифта, ряд из четырёх кругов-тем (как в Safari), закладка, внизу поиск по странице
 * и размер текста (те же, что в меню сайта) и кнопка «Выйти из режима чтения». Кнопок внутри самой читалки нет.
 * Шрифт и тема уходят в страницу по каналу [ReaderMode.setLook], размер текста меняет движок ([Browser.updateTextScale]).
 */
@Composable
internal fun ReaderSheet(
    onDismiss: () -> Unit,
    browser: Browser,
    tab: Tab,
    onFind: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val store = browser.store
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    // Действие выполняется, когда шторка уже спряталась (поиск и выход не должны застать её на экране).
    val close: (() -> Unit) -> Unit = { after ->
        if (!closing) {
            closing = true
            scope.launch {
                sheetState.hide()
                after()
            }.invokeOnCompletion { onDismiss() }
        }
    }
    val prefs = tab.readerPrefs
    val saved = store.isBookmarked(tab.url)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surfaceContainerLow,
        shape = HripsShapes.XL,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Режим чтения",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                FontChip(prefs.font) { tab.setReaderLook(font = it) }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                ThemeDot(
                    "Как в системе", prefs.theme == "auto",
                    Brush.linearGradient(0f to ThemeLight, 0.5f to ThemeLight, 0.5f to ThemeDark, 1f to ThemeDark),
                    cs.primary,
                    Modifier.weight(1f),
                ) { tab.setReaderLook(theme = "auto") }
                ThemeDot("Светлая", prefs.theme == "light", Brush.linearGradient(listOf(ThemeLight, ThemeLight)), Color(0xFF1D1D1F), Modifier.weight(1f)) {
                    tab.setReaderLook(theme = "light")
                }
                ThemeDot("Сепия", prefs.theme == "sepia", Brush.linearGradient(listOf(ThemeSepia, ThemeSepia)), Color(0xFF433422), Modifier.weight(1f)) {
                    tab.setReaderLook(theme = "sepia")
                }
                ThemeDot("Тёмная", prefs.theme == "dark", Brush.linearGradient(listOf(ThemeDark, ThemeDark)), Color.White, Modifier.weight(1f)) {
                    tab.setReaderLook(theme = "dark")
                }
            }
            Spacer(Modifier.height(16.dp))
            ToolRow(
                if (saved) HripsIcons.StarFilled else HripsIcons.Star,
                if (saved) "Убрать из закладок" else "Добавить в закладки",
                Corners.all(24.dp),
                selected = saved,
                trailing = if (saved) HripsIcons.Check else null,
            ) { close { toggleBookmarkWithNotice(store, tab.url, tab.title) } }
            Spacer(Modifier.height(12.dp))
            val round = HripsLayout.BarHeight
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(HripsIcons.FindPage, "Найти на странице", round) { close { onFind() } }
                TextSizeBar(
                    percent = tab.textScale,
                    onChange = { browser.updateTextScale(tab, it) },
                    modifier = Modifier.weight(1f).height(round),
                )
            }
            Spacer(Modifier.height(12.dp))
            PressSurface(
                onClick = { close { tab.closeReader() } },
                corners = Corners.all(26.dp),
                color = cs.primary,
                contentColor = cs.onPrimary,
                pressedCorner = 16.dp,
                pressScale = 0.98f,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(HripsIcons.Reader, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Выйти из режима чтения", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Круг с цветом темы читалки; у выбранной обводка акцентом и галочка цвета [mark]. */
@Composable
private fun ThemeDot(
    label: String,
    selected: Boolean,
    fill: Brush,
    mark: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(fill)
                .border(if (selected) 2.5.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, CircleShape)
                .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick)
                .semantics {
                    contentDescription = label
                    this.selected = selected
                },
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(HripsIcons.Check, null, Modifier.size(26.dp), tint = mark)
        }
    }
}

/** Выбор шрифта читалки: капсула с названием и стрелкой, по нажатию короткий список. */
@Composable
private fun FontChip(font: String, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val fonts = listOf("serif" to "С засечками", "sans" to "Без засечек")
    Box {
        Surface(onClick = { open = true }, shape = CircleShape, color = cs.surfaceContainerHigh) {
            Row(Modifier.height(36.dp).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fonts.firstOrNull { it.first == font }?.second ?: fonts[0].second, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(2.dp))
                Icon(HripsIcons.ChevronDown, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = HripsShapes.ML) {
            fonts.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { open = false; onPick(id) },
                    trailingIcon = if (id == font) ({ Icon(HripsIcons.Check, null, Modifier.size(20.dp), tint = cs.primary) }) else null,
                )
            }
        }
    }
}
