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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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

object Suggestions {
    suspend fun fetch(engine: SearchEngine, query: String): List<String> = withContext(Dispatchers.IO) {
        if (engine.suggest.isBlank()) return@withContext emptyList<String>()
        val c = try { URL(engine.suggest + Uri.encode(query)).openConnection() as HttpURLConnection } catch (_: Exception) { return@withContext emptyList() }
        // Соединение не закрываем после успешного ответа: следующая подсказка пойдёт по нему без нового рукопожатия TLS.
        var ok = true
        try {
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) hrips")
            if (c.responseCode !in 200..299) { ok = false; return@withContext emptyList<String>() }
            val bytes = c.inputStream.use { it.readBounded(256 * 1024) }
            val text = bytes.toString(Charsets.UTF_8)
            val arr = JSONArray(text).optJSONArray(1) ?: return@withContext emptyList()
            (0 until minOf(arr.length(), 12)).mapNotNull { (arr.opt(it) as? String)?.takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            ok = false
            emptyList()
        } finally {
            if (!ok) c.disconnect()
        }
    }
}

private class Chip(val text: String, val icon: ImageVector?)

private class PanelRow(val text: String, val sub: String?, val icon: ImageVector, val tap: String, val fill: String?)

internal fun readClipUrl(ctx: Context, initial: String): String? = runCatching {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
    t?.takeIf { it.isNotEmpty() && it.length < 2000 && !it.contains(' ') && (it.contains("://") || it.contains('.')) && it != initial }
}.getOrNull()

internal fun segShape(i: Int, n: Int): RoundedCornerShape = HripsShapes.segment(i, n)

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
    val input = rememberTextFieldState(initial, TextRange(0, initial.length))
    val text = input.text.toString()
    var remote by remember { mutableStateOf<List<String>>(emptyList()) }
    var clip by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val q = text.trim()
    val typing = q.isNotEmpty() && q != initial

    LaunchedEffect(Unit) {
        delay(170)
        focus.requestFocus()
        keyboard?.show()
        delay(150)
        clip = readClipUrl(ctx, initial)
    }
    // Тёплое соединение: пока человек печатает первую букву, рукопожатие с поисковиком уже сделано.
    LaunchedEffect(engine, store.suggestionsOn, incognito) {
        if (incognito) return@LaunchedEffect
        val app = ctx.applicationContext as? HripsApp
        app?.browserIfReady?.warmHost(runCatching { Uri.parse(engine.template).host }.getOrNull())
        if (store.suggestionsOn) Suggestions.fetch(engine, "")
    }
    // Набирают адрес: резолвим его имя заранее.
    LaunchedEffect(q, incognito) {
        if (incognito || q.isEmpty() || isSearch(q)) return@LaunchedEffect
        delay(150)
        val host = runCatching { Uri.parse(toUrl(q)).host }.getOrNull()
        (ctx.applicationContext as? HripsApp)?.browserIfReady?.warmHost(host)
    }
    LaunchedEffect(q, engine, store.suggestionsOn, incognito) {
        if (!typing || !store.suggestionsOn || incognito) {
            remote = emptyList()
            return@LaunchedEffect
        }
        delay(120)
        remote = Suggestions.fetch(engine, q)
    }

    fun submit(text: String) {
        if (text.isNotBlank()) onSubmit(text.trim(), engine)
    }

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
                Surface(shape = CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        EnginePicker(engine) { engine = it; focus.requestFocus() }
                        BasicTextField(
                            state = input,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                            onKeyboardAction = KeyboardActionHandler { submit(input.text.toString()) },
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp).focusRequester(focus),
                            decorator = TextFieldDecorator { inner ->
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                    if (text.isEmpty()) {
                                        Text(
                                            "Искать или задать вопрос", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        FilledIconButton(onClick = { submit(text) }, enabled = text.isNotBlank()) {
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
                                Surface(onClick = { input.setTextAndPlaceCursorAtEnd(c.text) }, shape = HripsShapes.S, color = cs.surfaceContainerHigh) {
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
                                        IconButton(onClick = { input.setTextAndPlaceCursorAtEnd(r.fill) }) {
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
                        HripsTextButton(onClick = { store.clearSearches() }, modifier = Modifier.fillMaxWidth()) { Text("Очистить все") }
                    }
                }
            }
        }
    }
}

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
            shape = HripsShapes.L,
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
                    shape = HripsShapes.M,
                    color = if (selected) cs.primaryContainer else Color.Transparent,
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

@Composable
fun EngineLogo(e: SearchEngine, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Favicon("https://${e.host}", size, fill = true) {
            Box(Modifier.size(size).background(Color(e.color)), contentAlignment = Alignment.Center) {
                Text(e.name.first().uppercase(), color = Color.White, fontSize = with(LocalDensity.current) { (size * 0.5f).toSp() }, fontWeight = FontWeight.Bold)
            }
        }
    }
}
