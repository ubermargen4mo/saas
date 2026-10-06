package com.hrips.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.GeckoSession

/** Поиск текста на странице: строка под адресной, счётчик совпадений, переход вверх и вниз. */
@Composable
fun FindBar(session: GeckoSession, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var current by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var found by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }

    fun run(text: String, backwards: Boolean) {
        if (text.isEmpty()) {
            session.finder.clear()
            current = 0
            total = 0
            found = true
            return
        }
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD
        session.finder.find(text, flags).accept({ r ->
            if (r != null) {
                current = r.current
                total = r.total
                found = r.found
            }
        }, { })
    }

    LaunchedEffect(Unit) {
        session.finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
        focus.requestFocus()
    }
    DisposableEffect(session) { onDispose { session.finder.clear() } }
    BackHandler { onClose() }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = { query = it; run(it, false) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (found) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run(query, false) }),
                modifier = Modifier.weight(1f).padding(start = 20.dp, top = 12.dp, bottom = 12.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                "Найти на странице",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Text(
                    when {
                        !found -> "0"
                        total > 0 -> "$current/$total"
                        else -> "…"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = { run(query, true) }, enabled = query.isNotEmpty()) {
                Icon(HripsIcons.ChevronDown, "Предыдущее", Modifier.rotate(180f))
            }
            IconButton(onClick = { run(query, false) }, enabled = query.isNotEmpty()) {
                Icon(HripsIcons.ChevronDown, "Следующее")
            }
            IconButton(onClick = onClose) { Icon(HripsIcons.Close, "Закрыть поиск") }
        }
    }
}
