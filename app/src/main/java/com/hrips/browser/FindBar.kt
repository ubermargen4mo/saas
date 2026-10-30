package com.hrips.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Состояние «найти на странице»: запрос и счётчик совпадений. Один и тот же ряд поиска на телефоне и на планшете. */
@Stable
internal class FinderState(private val tab: Tab) {
    var query by mutableStateOf("")
        private set
    var current by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var found by mutableStateOf(true)
        private set

    fun run(text: String, backwards: Boolean) {
        if (text.isEmpty()) {
            tab.finderClear()
            current = 0
            total = 0
            found = true
            return
        }
        val flags = tab.finderFlags(backwards)
        tab.finderFind(text, flags).accept({ r ->
            if (r != null) {
                current = r.current
                total = r.total
                found = r.found
            }
        }, { })
    }

    fun updateQuery(text: String) {
        query = text
        run(text, false)
    }

    fun next() = run(query, false)

    fun previous() = run(query, true)

    /** «3/12», «0» если ничего нет, «…» пока движок считает; пусто, пока запрос пуст. */
    val counter: String
        get() = when {
            query.isEmpty() -> ""
            !found -> "0"
            total > 0 -> "$current/$total"
            else -> "…"
        }
}

@Composable
internal fun rememberFinder(tab: Tab): FinderState {
    val state = remember { FinderState(tab) }
    LaunchedEffect(tab.id, tab.session) { tab.finderSetHighlights() }
    DisposableEffect(tab.session) { onDispose { tab.finderClear() } }
    return state
}

/**
 * Поиск по странице, как в Safari: нижний ряд из трёх элементов. Круглая кнопка «Готово», капсула с лупой
 * и полем ввода, капсула со стрелками «предыдущее / следующее». Свёрнутая панель с доменом стоит прямо над рядом
 * (см. `BrowserScreen`). Ряд плавает поверх страницы, клавиатура лежит поверх неё, страница не ужимается.
 * На планшете ряд тот же, но стоит сверху под адресной строкой на всю ширину (адресная строка там не сворачивается). Ряд сидит на тех же отступах и тех же капсулах (`BarPill`), что и нижняя панель браузера.
 */
@Composable
internal fun FindBottomBar(tab: Tab, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val finder = rememberFinder(tab)
    val cs = MaterialTheme.colorScheme
    val palette = rememberBarPalette()
    val focus = remember { FocusRequester() }
    LaunchedEffect(tab.id, tab.session) { focus.requestFocus() }
    BackHandler { onClose() }

    val findStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = HripsLayout.FindTextSp.sp)

    Row(
        modifier.fillMaxWidth().padding(horizontal = HripsLayout.BarMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = cs.primary,
            contentColor = cs.onPrimary,
            shadowElevation = 6.dp,
            modifier = Modifier.size(HripsLayout.FindCircle),
        ) {
            BarButton(onClick = onClose, modifier = Modifier.fillMaxSize()) {
                Icon(HripsIcons.FindCheck, "Готово", Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.width(8.dp))

        BarPill(palette, pressHost = false, modifier = Modifier.weight(1f).height(HripsLayout.FindPill)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    HripsIcons.Search, null,
                    Modifier.padding(start = 9.dp).size(22.dp),
                    tint = cs.onSurfaceVariant,
                )
                BasicTextField(
                    value = finder.query,
                    onValueChange = finder::updateQuery,
                    singleLine = true,
                    textStyle = findStyle.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { finder.next() }),
                    modifier = Modifier.weight(1f).padding(start = 9.dp, end = 8.dp).focusRequester(focus),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (finder.query.isEmpty()) {
                                Text(
                                    "Найти",
                                    color = cs.onSurfaceVariant,
                                    style = findStyle,
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (finder.query.isNotEmpty()) {
                    Text(
                        finder.counter,
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    BarButton(onClick = { finder.updateQuery("") }, modifier = Modifier.size(32.dp)) {
                        Icon(HripsIcons.Close, "Очистить", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(2.dp))
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
        Spacer(Modifier.width(8.dp))

        BarPill(palette) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarButton(onClick = finder::previous, enabled = finder.query.isNotEmpty(), modifier = Modifier.size(width = 32.dp, height = HripsLayout.FindPill)) {
                    Icon(HripsIcons.ChevronDown, "Предыдущее", Modifier.size(26.dp).rotate(180f))
                }
                BarButton(onClick = finder::next, enabled = finder.query.isNotEmpty(), modifier = Modifier.size(width = 32.dp, height = HripsLayout.FindPill)) {
                    Icon(HripsIcons.ChevronDown, "Следующее", Modifier.size(26.dp))
                }
            }
        }
    }
}
