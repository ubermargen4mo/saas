package com.hrips.browser

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.IsoFields
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate

private val YEAR_WEEK_VALUE = Regex("(\\d{4})-W(\\d{2})")

sealed class PromptRequest {
    class Alert(val title: String, val message: String, val onClose: () -> Unit) : PromptRequest()
    class Confirm(val title: String, val message: String, val onAnswer: (Boolean) -> Unit) : PromptRequest()
    class Input(val title: String, val message: String, val initial: String, val onAnswer: (String?) -> Unit) : PromptRequest()
    class Auth(
        val title: String,
        val message: String,
        val host: String,
        val onlyPassword: Boolean,
        val user: String,
        val onAnswer: (Pair<String, String>?) -> Unit,
    ) : PromptRequest()
    class ColorPick(val initial: String, val presets: List<String>, val onAnswer: (String?) -> Unit) : PromptRequest()
    class DateTime(
        val type: Int,
        val initial: String?,
        val min: String?,
        val max: String?,
        val step: String?,
        val onAnswer: (String?) -> Unit,
    ) : PromptRequest()
    class Pick(
        val title: String,
        val items: List<PickItem>,
        val multiple: Boolean,
        val onAnswer: (List<String>?) -> Unit,
    ) : PromptRequest()
}

class PickItem(val id: String, val label: String, val enabled: Boolean, val selected: Boolean, val header: Boolean)

class Prompts(private val context: Context) {
    val queue = mutableStateListOf<PromptRequest>()

    var pickFiles: ((multiple: Boolean, mimeTypes: Array<String>, onResult: (List<Uri>) -> Unit) -> Unit)? = null

    var pickFolder: ((onResult: (List<Uri>) -> Unit) -> Unit)? = null

    private var pendingFileSelection: ((List<Uri>) -> Unit)? = null

    fun replaceFileSelection(onResult: (List<Uri>) -> Unit) {
        pendingFileSelection?.let { previous -> runCatching { previous(emptyList()) } }
        pendingFileSelection = onResult
    }

    fun deliverFileSelection(uris: List<Uri>) {
        val callback = pendingFileSelection ?: return
        pendingFileSelection = null
        runCatching { callback(uris) }
    }

    private fun show(req: PromptRequest) {
        queue.add(req)
    }

    fun resolve(req: PromptRequest, answer: () -> Unit) {
        if (queue.remove(req)) answer()
    }

    private fun complete(result: GeckoResult<PromptDelegate.PromptResponse>, make: () -> PromptDelegate.PromptResponse) {
        try {
            result.complete(make())
        } catch (e: Exception) {
        }
    }

    private fun accept(types: Array<out String?>?): Array<String> {
        val out = linkedSetOf<String>()
        for (t in types.orEmpty()) {
            val s = t?.trim().orEmpty()
            if (s.isEmpty()) continue
            if (s.startsWith(".")) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(s.substring(1).lowercase())?.let { out.add(it) }
            } else {
                out.add(s)
            }
        }
        if (out.isEmpty()) out.add("*/*")
        return out.toTypedArray()
    }

    private fun flatten(choices: Array<PromptDelegate.ChoicePrompt.Choice>?, out: MutableList<PickItem>) {
        for (c in choices.orEmpty()) {
            if (c.separator) continue
            val kids = c.items
            if (kids != null && kids.isNotEmpty()) {
                out.add(PickItem(c.id, c.label.orEmpty(), enabled = false, selected = false, header = true))
                flatten(kids, out)
            } else {
                out.add(PickItem(c.id, c.label.orEmpty(), enabled = !c.disabled, selected = c.selected, header = false))
            }
        }
    }

    val delegate = object : PromptDelegate {

        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.AlertPrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            show(PromptRequest.Alert(prompt.title.orEmpty(), prompt.message.orEmpty()) {
                complete(result) { prompt.dismiss() }
            })
            return result
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.ButtonPrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            show(PromptRequest.Confirm(prompt.title.orEmpty(), prompt.message.orEmpty()) { ok ->
                complete(result) {
                    prompt.confirm(
                        if (ok) PromptDelegate.ButtonPrompt.Type.POSITIVE else PromptDelegate.ButtonPrompt.Type.NEGATIVE
                    )
                }
            })
            return result
        }

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.TextPrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            show(PromptRequest.Input(prompt.title.orEmpty(), prompt.message.orEmpty(), prompt.defaultValue.orEmpty()) { text ->
                complete(result) { if (text == null) prompt.dismiss() else prompt.confirm(text) }
            })
            return result
        }

        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: PromptDelegate.ChoicePrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            val items = mutableListOf<PickItem>()
            flatten(prompt.choices, items)
            val multiple = prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE
            show(PromptRequest.Pick(prompt.title.orEmpty().ifBlank { prompt.message.orEmpty() }, items, multiple) { ids ->
                complete(result) {
                    when {
                        ids == null -> prompt.dismiss()
                        multiple -> prompt.confirm(ids.toTypedArray())
                        ids.isNotEmpty() -> prompt.confirm(ids[0])
                        else -> prompt.dismiss()
                    }
                }
            })
            return result
        }

        override fun onFilePrompt(
            session: GeckoSession,
            prompt: PromptDelegate.FilePrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val folder = prompt.type == PromptDelegate.FilePrompt.Type.FOLDER
            val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            val finish: (List<Uri>) -> Unit = { uris ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    complete(result) {
                        try {
                            when {
                                uris.isEmpty() -> prompt.dismiss()
                                multiple -> prompt.confirm(context.applicationContext, uris.toTypedArray())
                                else -> prompt.confirm(context.applicationContext, uris[0])
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("Prompts", "Не удалось передать файл сайту", e)
                            Notices.show("Не удалось прикрепить файл")
                            prompt.dismiss()
                        }
                    }
                }
            }
            if (folder) {
                val pickDir = pickFolder ?: return GeckoResult.fromValue(prompt.dismiss())
                pickDir(finish)
            } else {
                val pick = pickFiles ?: return GeckoResult.fromValue(prompt.dismiss())
                pick(multiple, accept(prompt.mimeTypes), finish)
            }
            return result
        }

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.BeforeUnloadPrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            show(PromptRequest.Confirm("Покинуть страницу?", "Внесённые изменения могут не сохраниться.") { ok ->
                complete(result) { prompt.confirm(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY) }
            })
            return result
        }

        override fun onRepostConfirmPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.RepostConfirmPrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            show(PromptRequest.Confirm("Отправить данные ещё раз?", "Для обновления страницы нужно повторно отправить введённые данные.") { ok ->
                complete(result) { prompt.confirm(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY) }
            })
            return result
        }

        override fun onPopupPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.PopupPrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            show(PromptRequest.Confirm("Всплывающее окно", "Сайт хочет открыть новое окно. Разрешить?") { ok ->
                complete(result) { prompt.confirm(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY) }
            })
            return result
        }

        override fun onAuthPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.AuthPrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            val opts = prompt.authOptions
            val onlyPassword = (opts.flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0
            val host = opts.uri?.let { Uri.parse(it).host }.orEmpty()
            show(PromptRequest.Auth(
                prompt.title.orEmpty().ifBlank { "Вход" },
                prompt.message.orEmpty(),
                host,
                onlyPassword,
                opts.username.orEmpty(),
            ) { cred ->
                complete(result) {
                    when {
                        cred == null -> prompt.dismiss()
                        onlyPassword -> prompt.confirm(cred.second)
                        else -> prompt.confirm(cred.first, cred.second)
                    }
                }
            })
            return result
        }

        override fun onColorPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.ColorPrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            show(PromptRequest.ColorPick(prompt.defaultValue ?: "#000000", prompt.predefinedValues?.filterNotNull().orEmpty()) { c ->
                complete(result) { if (c == null) prompt.dismiss() else prompt.confirm(c) }
            })
            return result
        }

        override fun onDateTimePrompt(
            session: GeckoSession,
            prompt: PromptDelegate.DateTimePrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            show(PromptRequest.DateTime(prompt.type, prompt.defaultValue, prompt.minValue, prompt.maxValue, prompt.stepValue) { v ->
                complete(result) { if (v == null) prompt.dismiss() else prompt.confirm(v) }
            })
            return result
        }
    }
}

@Composable
fun PromptHost(prompts: Prompts) {
    val req = prompts.queue.firstOrNull() ?: return
    key(req) {
        when (req) {
            is PromptRequest.Alert -> {
                val title: (@Composable () -> Unit)? = if (req.title.isBlank()) null else ({ Text(req.title) })
                HripsDialog(
                    onDismissRequest = { prompts.resolve(req) { req.onClose() } },
                    title = title,
                    text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(req.message) } },
                    confirmButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onClose() } }) { Text("OK") } },
                )
            }

            is PromptRequest.Confirm -> {
                val title: (@Composable () -> Unit)? = if (req.title.isBlank()) null else ({ Text(req.title) })
                HripsDialog(
                    onDismissRequest = { prompts.resolve(req) { req.onAnswer(false) } },
                    title = title,
                    text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(req.message) } },
                    confirmButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(true) } }) { Text("OK") } },
                    dismissButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(false) } }) { Text("Отмена") } },
                )
            }

            is PromptRequest.Input -> {
                var text by remember(req) { mutableStateOf(req.initial) }
                val title: (@Composable () -> Unit)? = if (req.title.isBlank()) null else ({ Text(req.title) })
                HripsDialog(
                    onDismissRequest = { prompts.resolve(req) { req.onAnswer(null) } },
                    title = title,
                    text = {
                        Column {
                            if (req.message.isNotBlank()) {
                                Text(req.message, Modifier.padding(bottom = 12.dp))
                            }
                            HripsField(value = text, onValueChange = { text = it }, singleLine = true)
                        }
                    },
                    confirmButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(text) } }) { Text("OK") } },
                    dismissButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(null) } }) { Text("Отмена") } },
                )
            }

            is PromptRequest.Pick -> {
                val selected = remember(req) {
                    mutableStateListOf<String>().apply { addAll(req.items.filter { it.selected }.map { it.id }) }
                }
                val title: (@Composable () -> Unit)? = if (req.title.isBlank()) null else ({ Text(req.title) })
                HripsDialog(
                    onDismissRequest = { prompts.resolve(req) { req.onAnswer(null) } },
                    title = title,
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            req.items.forEach { item ->
                                if (item.header) {
                                    Text(
                                        item.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                } else {
                                    val checked = item.id in selected
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = item.enabled) {
                                                if (req.multiple) {
                                                    if (checked) selected.remove(item.id) else selected.add(item.id)
                                                } else {
                                                    prompts.resolve(req) { req.onAnswer(listOf(item.id)) }
                                                }
                                            }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (req.multiple) {
                                            Checkbox(checked = checked, onCheckedChange = null, enabled = item.enabled)
                                        } else {
                                            RadioButton(selected = checked, onClick = null, enabled = item.enabled)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Text(item.label, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        if (req.multiple) {
                            HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(selected.toList()) } }) { Text("OK") }
                        }
                    },
                    dismissButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(null) } }) { Text("Отмена") } },
                )
            }

            is PromptRequest.Auth -> AuthDialog(req, prompts)
            is PromptRequest.ColorPick -> ColorDialog(req, prompts)
            is PromptRequest.DateTime -> DateTimeDialog(req, prompts)
        }
    }
}

@Composable
private fun AuthDialog(req: PromptRequest.Auth, prompts: Prompts) {
    var user by remember(req) { mutableStateOf(req.user) }
    var pass by remember(req) { mutableStateOf("") }
    HripsDialog(
        icon = HripsIcons.Key,
        onDismissRequest = { prompts.resolve(req) { req.onAnswer(null) } },
        title = { Text(req.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val msg = req.message.ifBlank { req.host }
                if (msg.isNotBlank()) Text(msg)
                if (!req.onlyPassword) {
                    HripsField(user, { user = it }, label = { Text("Логин") }, singleLine = true)
                }
                HripsField(
                    pass, { pass = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(user to pass) } }) { Text("Войти") } },
        dismissButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(null) } }) { Text("Отмена") } },
    )
}

private val defaultColors = listOf(
    "#f44336", "#e91e63", "#9c27b0", "#3f51b5", "#2196f3", "#009688",
    "#4caf50", "#ffeb3b", "#ff9800", "#795548", "#607d8b", "#000000", "#ffffff",
)

@Composable
private fun ColorDialog(req: PromptRequest.ColorPick, prompts: Prompts) {
    var hex by remember(req) { mutableStateOf(req.initial) }
    val parsed = remember(hex) { runCatching { android.graphics.Color.parseColor(hex) }.getOrNull() }
    val presets = req.presets.ifEmpty { defaultColors }
    HripsDialog(
        onDismissRequest = { prompts.resolve(req) { req.onAnswer(null) } },
        title = { Text("Выберите цвет") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                presets.chunked(6).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.forEach { c ->
                            val p = runCatching { android.graphics.Color.parseColor(c) }.getOrNull()
                            if (p != null) {
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(p))
                                        .border(
                                            if (c.equals(hex, ignoreCase = true)) 3.dp else 1.dp,
                                            if (c.equals(hex, ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape,
                                        )
                                        .clickable { hex = c },
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (parsed != null) Color(parsed) else Color.Transparent)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    HripsField(hex, { hex = it }, label = { Text("Код цвета") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            HripsTextButton(
                enabled = parsed != null,
                onClick = {
                    val rgb = (parsed ?: 0) and 0xFFFFFF
                    prompts.resolve(req) { req.onAnswer(String.format("#%06x", rgb)) }
                },
            ) { Text("OK") }
        },
        dismissButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(null) } }) { Text("Отмена") } },
    )
}

private fun parseDate(type: Int, v: String?): LocalDate {
    val today = LocalDate.now()
    if (v.isNullOrBlank()) return today
    return runCatching {
        when (type) {
            PromptDelegate.DateTimePrompt.Type.MONTH -> YearMonth.parse(v).atDay(1)
            PromptDelegate.DateTimePrompt.Type.WEEK -> {
                val m = YEAR_WEEK_VALUE.find(v) ?: return@runCatching today
                LocalDate.of(m.groupValues[1].toInt(), 1, 4)
                    .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, m.groupValues[2].toLong())
            }
            PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> LocalDate.parse(v.substringBefore('T'))
            else -> LocalDate.parse(v)
        }
    }.getOrDefault(today)
}

private fun parseTime(type: Int, v: String?): LocalTime {
    if (v.isNullOrBlank()) return LocalTime.now()
    return runCatching {
        LocalTime.parse(if (type == PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL) v.substringAfter('T') else v)
    }.getOrDefault(LocalTime.now())
}

private fun formatDate(type: Int, d: LocalDate): String = when (type) {
    PromptDelegate.DateTimePrompt.Type.MONTH -> YearMonth.from(d).toString()
    PromptDelegate.DateTimePrompt.Type.WEEK ->
        String.format("%04d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    else -> d.toString()
}

private fun clampValue(type: Int, value: String, min: String?, max: String?): String {
    val minValue = min?.trim().orEmpty()
    val maxValue = max?.trim().orEmpty()
    val candidate = parseComparable(type, value) ?: return value
    val lower = minValue.takeIf { it.isNotBlank() }?.let { parseComparable(type, it) }
    val upper = maxValue.takeIf { it.isNotBlank() }?.let { parseComparable(type, it) }
    return when {
        lower != null && candidate < lower -> minValue
        upper != null && candidate > upper -> maxValue
        else -> value
    }
}

private fun parseComparable(type: Int, value: String): Long? = runCatching {
    when (type) {
        PromptDelegate.DateTimePrompt.Type.TIME ->
            parseTime(type, value).toSecondOfDay().toLong()
        PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> {
            if (!value.contains('T')) return@runCatching null
            val date = LocalDate.parse(value.substringBefore('T'))
            val time = LocalTime.parse(value.substringAfter('T'))
            java.time.LocalDateTime.of(date, time).toEpochSecond(ZoneOffset.UTC)
        }
        PromptDelegate.DateTimePrompt.Type.MONTH -> {
            val month = YearMonth.parse(value)
            month.year.toLong() * 12L + month.monthValue
        }
        PromptDelegate.DateTimePrompt.Type.WEEK -> {
            val m = YEAR_WEEK_VALUE.find(value) ?: return@runCatching null
            val y = m.groupValues[1].toInt()
            val w = m.groupValues[2].toInt()
            LocalDate.of(y, 1, 4)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, w.toLong())
                .toEpochDay()
        }
        else -> LocalDate.parse(value).toEpochDay()
    }
}.getOrNull()

private fun clampDate(type: Int, date: LocalDate, min: String?, max: String?): LocalDate {
    val lower = min?.takeIf { it.isNotBlank() }?.let { runCatching { parseDate(type, it) }.getOrNull() }
    val upper = max?.takeIf { it.isNotBlank() }?.let { runCatching { parseDate(type, it) }.getOrNull() }
    return when {
        lower != null && date.isBefore(lower) -> lower
        upper != null && date.isAfter(upper) -> upper
        else -> date
    }
}

private fun clampTime(time: LocalTime, min: String?, max: String?): LocalTime {
    val normalized = time.withSecond(0).withNano(0)
    val lower = min?.takeIf { it.isNotBlank() }?.let { runCatching { parseTime(PromptDelegate.DateTimePrompt.Type.TIME, it) }.getOrNull() }
    val upper = max?.takeIf { it.isNotBlank() }?.let { runCatching { parseTime(PromptDelegate.DateTimePrompt.Type.TIME, it) }.getOrNull() }
    return when {
        lower != null && normalized.isBefore(lower) -> lower
        upper != null && normalized.isAfter(upper) -> upper
        else -> normalized
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeDialog(req: PromptRequest.DateTime, prompts: Prompts) {
    val type = req.type
    val isTime = type == PromptDelegate.DateTimePrompt.Type.TIME
    val withTime = isTime || type == PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL
    val initDate = remember(req) {
        clampDate(type, parseDate(type, req.initial), req.min, req.max)
    }
    val initTime = remember(req) {
        clampTime(parseTime(type, req.initial), req.min, req.max)
    }
    var stage by remember(req) { mutableIntStateOf(if (isTime) 1 else 0) }
    var picked by remember(req) { mutableStateOf(initDate) }

    if (stage == 0) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { prompts.resolve(req) { req.onAnswer(null) } },
            confirmButton = {
                HripsTextButton(onClick = {
                    val ms = state.selectedDateMillis
                    val d = if (ms != null) Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate() else initDate
                    if (withTime) {
                        picked = d
                        stage = 1
                    } else {
                        val value = formatDate(type, d)
                        prompts.resolve(req) { req.onAnswer(clampValue(type, value, req.min, req.max)) }
                    }
                }) { Text(if (withTime) "Далее" else "OK") }
            },
            dismissButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(null) } }) { Text("Отмена") } },
        ) { DatePicker(state = state) }
    } else {
        val time = rememberTimePickerState(initialHour = initTime.hour, initialMinute = initTime.minute, is24Hour = true)
        HripsDialog(
            onDismissRequest = { prompts.resolve(req) { req.onAnswer(null) } },
            title = { Text("Время") },
            text = { TimePicker(state = time) },
            confirmButton = {
                HripsTextButton(onClick = {
                    val t = String.format("%02d:%02d", time.hour, time.minute)
                    val value = if (isTime) t else "${picked}T$t"
                    prompts.resolve(req) { req.onAnswer(clampValue(type, value, req.min, req.max)) }
                }) { Text("OK") }
            },
            dismissButton = { HripsTextButton(onClick = { prompts.resolve(req) { req.onAnswer(null) } }) { Text("Отмена") } },
        )
    }
}
