@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hrips.browser

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

private class SugRow(val text: String, val sub: String?, val icon: ImageVector, val tap: String, val fill: String?)

/**
 * Подсказки под адресной строкой на планшете: строка превращается в поле ввода прямо на месте, а подсказки
 * выезжают из неё плавающей карточкой (без полноэкранной панели). Окно не берёт фокус, поэтому клавиатура и курсор
 * остаются в строке. Пока текст равен адресу открытой страницы, показываем недавние запросы и ссылку из буфера.
 */
@Composable
internal fun AddressSuggestions(
    initial: String,
    text: String,
    engine: SearchEngine,
    store: Store,
    incognito: Boolean,
    widthPx: Int,
    anchorHeightPx: Int,
    onPick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val q = text.trim()
    val typing = q.isNotEmpty() && q != initial
    var remote by remember { mutableStateOf<List<String>>(emptyList()) }
    var clip by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        delay(150)
        clip = readClipUrl(ctx, initial)
    }
    // В приватном режиме недописанный запрос не уходит поисковику ради подсказок
    LaunchedEffect(q, engine, store.suggestionsOn, incognito) {
        if (!typing || !store.suggestionsOn || incognito) {
            remote = emptyList()
            return@LaunchedEffect
        }
        delay(120) // не дёргаем сеть на каждую букву
        remote = Suggestions.fetch(engine, q)
    }

    val history = if (incognito) emptyList() else store.searches.toList()
    val rows: List<SugRow> = if (typing) {
        val local = history.filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }.take(3)
        val net = remote.filter { r -> !r.equals(q, ignoreCase = true) && local.none { it.equals(r, ignoreCase = true) } }
        (local.map { SugRow(it, null, HripsIcons.History, it, it) } + net.map { SugRow(it, null, HripsIcons.Search, it, it) }).take(8)
    } else {
        listOfNotNull(clip?.let { SugRow("Скопированная ссылка", it, HripsIcons.Link, it, null) }) +
            history.take(6).map { SugRow(it, null, HripsIcons.History, it, it) }
    }

    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 500f)) }
    if (rows.isEmpty()) return

    val gap = with(density) { 8.dp.roundToPx() }
    Popup(
        alignment = androidx.compose.ui.Alignment.TopStart,
        offset = IntOffset(0, anchorHeightPx + gap),
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Surface(
            shape = HripsShapes.L,
            color = cs.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier
                .width(with(density) { widthPx.toDp() })
                .graphicsLayer {
                    alpha = enter.value.coerceIn(0f, 1f)
                    val s = 0.94f + 0.06f * enter.value
                    scaleX = 0.98f + 0.02f * enter.value
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        ) {
            LazyColumn(
                Modifier.heightIn(max = 380.dp).animateContentSize(),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
            ) {
                itemsIndexed(rows, key = { _, r -> r.text + "|" + r.tap }) { _, r ->
                    Surface(onClick = { onPick(r.tap) }, shape = HripsShapes.M, color = Color.Transparent) {
                        ListItem(
                            headlineContent = { Text(r.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (r.sub != null) { { Text(r.sub, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null,
                            leadingContent = { Icon(r.icon, null, tint = cs.onSurfaceVariant) },
                            trailingContent = if (r.fill != null) {
                                {
                                    // Подставить в строку, не запуская поиск (дописать запрос)
                                    IconButton(onClick = { onFill(r.fill) }) {
                                        Icon(HripsIcons.InsertQuery, "Подставить в строку", tint = cs.onSurfaceVariant)
                                    }
                                }
                            } else null,
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}
