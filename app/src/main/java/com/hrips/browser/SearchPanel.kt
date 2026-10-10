@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("DEPRECATION")

package com.hrips.browser

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Подсказки поисковика. Формат ответа у всех движков одинаковый (OpenSearch): [запрос, [подсказки]]. */
object Suggestions {
    suspend fun fetch(engine: SearchEngine, query: String): List<String> = withContext(Dispatchers.IO) {
        val c = try { URL(engine.suggest + Uri.encode(query)).openConnection() as HttpURLConnection } catch (_: Exception) { return@withContext emptyList() }
        try {
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) hrips")
            if (c.responseCode !in 200..299) return@withContext emptyList<String>()
            val bytes = c.inputStream.use { it.readBounded(256 * 1024) }
            val text = bytes.toString(Charsets.UTF_8)
            val arr = JSONArray(text).optJSONArray(1) ?: return@withContext emptyList()
            (0 until minOf(arr.length(), 12)).mapNotNull { (arr.opt(it) as? String)?.takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        } finally {
            c.disconnect()
        }
    }
}

private class Chip(val text: String, val icon: ImageVector?)

private class PanelRow(val text: String, val sub: String?, val icon: ImageVector, val tap: String, val fill: String?)

private fun readClipUrl(ctx: Context, initial: String): String? = runCatching {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
    t?.takeIf { it.isNotEmpty() && it.length < 2000 && !it.contains(' ') && (it.contains("://") || it.contains('.')) && it != initial }
}.getOrNull()

/** Группа строк со скруглением только по краям группы: общий приём M3 Expressive для списков. */
internal fun segShape(i: Int, n: Int): RoundedCornerShape {
    val big = 24.dp
    val small = 6.dp
    val top = if (i == 0) big else small
    val bottom = if (i == n - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * Полноэкранная поисковая панель. Выбор движка здесь разовый: [onSubmit] получает движок,
 * а поиск по умолчанию (SearchEngines.current) не меняется.
 */
@Composable
fun SearchPanel(
    store: Store,
    initial: String,
    initialEngine: SearchEngine,
    onSubmit: (text: String, engine: SearchEngine) -> Unit,
    onDismiss: () -> Unit,
    incognito: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var engine by remember { mutableStateOf(initialEngine) }
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var remote by remember { mutableStateOf<List<String>>(emptyList()) }
    var clip by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val q = value.text.trim()
    // Пока текст равен адресу открытой страницы, показываем историю, а не подсказки
    val typing = q.isNotEmpty() && q != initial

    LaunchedEffect(Unit) {
        delay(170) // клавиатура не должна менять раскладку посреди раскрытия окна
        focus.requestFocus()
        keyboard?.show()
        delay(150) // буфер обмена читается только когда окно уже в фокусе
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

    fun submit(text: String) {
        if (text.isNotBlank()) onSubmit(text.trim(), engine)
    }

    // При наборе подсказки идут чипами в одну строку: тап только вставляет текст в запрос, не ищет.
    // Ищут по тапу только недавние запросы ниже, когда поле пустое.
    val chips: List<Chip> = if (typing) {
        val local = (if (incognito) emptyList() else store.searches.toList()).filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }.take(3)
        val net = remote.filter { r -> !r.equals(q, ignoreCase = true) && local.none { it.equals(r, ignoreCase = true) } }
        (local.map { Chip(it, HripsIcons.History) } + net.map { Chip(it, null) }).take(10)
    } else emptyList()
    val sections: List<List<PanelRow>> = if (typing) {
        emptyList()
    } else {
        listOfNotNull(
            clip?.let { listOf(PanelRow("Скопированная ссылка", it, HripsIcons.Link, it, null)) },
            (if (incognito) emptyList() else store.searches.toList()).take(8).map { PanelRow(it, null, HripsIcons.History, it, it) }.takeIf { it.isNotEmpty() },
        )
    }

    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 12.dp)) {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(HripsIcons.Back, "Назад") }
                Surface(shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        EnginePicker(engine) { engine = it; focus.requestFocus() }
                        BasicTextField(
                            value = value,
                            onValueChange = { value = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submit(value.text) }),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp).focusRequester(focus),
                            decorationBox = { inner ->
                                // fillMaxWidth: касание в любом месте строки ставит курсор, а не только поверх уже набранного текста
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                    if (value.text.isEmpty()) {
                                        Text(
                                            "Искать или задать вопрос", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        if (value.text.isNotEmpty()) {
                            IconButton(onClick = { value = TextFieldValue("") }) { Icon(HripsIcons.Close, "Очистить") }
                        }
                        FilledIconButton(onClick = { submit(value.text) }, enabled = value.text.isNotBlank()) {
                            Icon(HripsIcons.Search, "Найти")
                        }
                    }
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            ) {
                if (chips.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(chips) { c ->
                                Surface(onClick = { value = TextFieldValue(c.text, TextRange(c.text.length)) }, shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerHigh) {
                                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (c.icon != null) {
                                            Icon(c.icon, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }
                }
                sections.forEach { rows ->
                    itemsIndexed(rows) { i, r ->
                        Surface(
                            onClick = { submit(r.tap) },
                            shape = segShape(i, rows.size),
                            color = cs.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ListItem(
                                headlineContent = { Text(r.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = if (r.sub != null) { { Text(r.sub, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null,
                                leadingContent = { Icon(r.icon, null, tint = cs.onSurfaceVariant) },
                                trailingContent = if (r.fill != null) {
                                    {
                                        // Подставить в строку, не запуская поиск (дописать запрос)
                                        IconButton(onClick = { value = TextFieldValue(r.fill, TextRange(r.fill.length)) }) {
                                            Icon(HripsIcons.InsertQuery, "Подставить в строку", tint = cs.onSurfaceVariant)
                                        }
                                    }
                                } else null,
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                }
                if (!typing && store.searches.isNotEmpty()) {
                    item {
                        TextButton(onClick = { store.clearSearches() }, modifier = Modifier.fillMaxWidth()) { Text("Очистить все") }
                    }
                }
            }
        }
    }
}

/** Логотип + стрелка вниз; по нажатию меню выбора движка (только на этот запрос). */
@Composable
fun EnginePicker(current: SearchEngine, onPick: (SearchEngine) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
        label = "chevron",
    )
    Box {
        Surface(onClick = { open = true }, shape = CircleShape, color = cs.surfaceContainerHighest) {
            Row(Modifier.padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                EngineLogo(current, 28.dp)
                Spacer(Modifier.width(4.dp))
                Icon(HripsIcons.ChevronDown, "Поисковая система", Modifier.size(20.dp).rotate(rotation))
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = cs.surfaceContainer,
        ) {
            Text(
                "Искать через (только сейчас)",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SearchEngines.all.forEach { e ->
                val selected = e.name == current.name
                Surface(
                    onClick = { onPick(e); open = false },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) cs.secondaryContainer else Color.Transparent,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp).fillMaxWidth(),
                ) {
                    Row(
                        Modifier.widthIn(min = 220.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EngineLogo(e, 32.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(e.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (selected) Icon(HripsIcons.Check, null, Modifier.padding(start = 12.dp).size(20.dp))
                    }
                }
            }
        }
    }
}

/** Логотип движка с его сайта (кэшируется), пока не загрузился: цветной кружок с буквой. */
@Composable
fun EngineLogo(e: SearchEngine, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        // Иконка на весь кружок, без тёмной каймы вокруг
        Favicon("https://${e.host}", size, fill = true) {
            Box(Modifier.size(size).background(Color(e.color)), contentAlignment = Alignment.Center) {
                Text(e.name.first().uppercase(), color = Color.White, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
