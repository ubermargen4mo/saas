package com.hrips.browser

import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** Что нужно показать пользователю. Дальше разбирается в [PromptHost]. */
sealed class PromptRequest {
    class Alert(val title: String, val message: String, val onClose: () -> Unit) : PromptRequest()
    class Confirm(val title: String, val message: String, val onAnswer: (Boolean) -> Unit) : PromptRequest()
    class Input(val title: String, val message: String, val initial: String, val onAnswer: (String?) -> Unit) : PromptRequest()
    class Pick(
        val title: String,
        val items: List<PickItem>,
        val multiple: Boolean,
        val onAnswer: (List<String>?) -> Unit,
    ) : PromptRequest()
}

class PickItem(val id: String, val label: String, val enabled: Boolean, val selected: Boolean, val header: Boolean)

/**
 * Запросы страниц к пользователю. Раньше PromptDelegate в приложении не было вообще, из-за чего:
 *  - не открывался выбор файла (загрузка файлов на сайты не работала),
 *  - не открывались выпадающие списки <select>,
 *  - не показывались alert/confirm/prompt, подтверждение повторной отправки формы и т.п.
 * Схема та же, что в geckoview_example и Fenix: движок отдаёт prompt, мы показываем диалог и
 * завершаем GeckoResult ответом пользователя.
 */
class Prompts(private val context: Context) {
    val queue = mutableStateListOf<PromptRequest>()

    /**
     * Устанавливается из MainActivity: открывает системный выбор файлов (SAF) и возвращает
     * список выбранных Uri (пустой список = отмена).
     */
    var pickFiles: ((multiple: Boolean, mimeTypes: Array<String>, onResult: (List<Uri>) -> Unit) -> Unit)? = null

    private fun show(req: PromptRequest) {
        queue.add(req)
    }

    /** Убирает запрос из очереди и выполняет ответ. Вызывается из диалогов. */
    fun resolve(req: PromptRequest, answer: () -> Unit) {
        if (queue.remove(req)) answer()
    }

    // Prompt мог быть уже закрыт движком (например, страница ушла). Тогда ответ просто игнорируем.
    private fun complete(result: GeckoResult<PromptDelegate.PromptResponse>, make: () -> PromptDelegate.PromptResponse) {
        try {
            result.complete(make())
        } catch (e: Exception) {
            // нечего делать: prompt уже завершён
        }
    }

    private fun accept(types: Array<out String?>?): Array<String> {
        val out = linkedSetOf<String>()
        for (t in types.orEmpty()) {
            val s = t?.trim().orEmpty()
            if (s.isEmpty()) continue
            if (s.startsWith(".")) {
                // accept=".pdf" -> application/pdf
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

        // alert()
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

        // confirm()
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

        // prompt()
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

        // <select> и меню
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

        // <input type="file">: загрузка файлов на сайты
        override fun onFilePrompt(
            session: GeckoSession,
            prompt: PromptDelegate.FilePrompt,
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val pick = pickFiles
            // Выбор папки движок пока не поддерживает, и без активности выбирать негде
            if (pick == null || prompt.type == PromptDelegate.FilePrompt.Type.FOLDER) {
                return GeckoResult.fromValue(prompt.dismiss())
            }
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
            pick(multiple, accept(prompt.mimeTypes)) { uris ->
                complete(result) {
                    when {
                        uris.isEmpty() -> prompt.dismiss()
                        multiple -> prompt.confirm(context, uris.toTypedArray())
                        else -> prompt.confirm(context, uris[0])
                    }
                }
            }
            return result
        }

        // "Покинуть страницу? Изменения могут не сохраниться"
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

        // Обновление страницы, которая получена POST-запросом
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

        // Страница пытается открыть всплывающее окно без действия пользователя
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
    }
}

/** Показывает первый запрос из очереди [Prompts.queue]. Вызывается один раз из BrowserScreen. */
@Composable
fun PromptHost(prompts: Prompts) {
    val req = prompts.queue.firstOrNull() ?: return
    key(req) {
        when (req) {
            is PromptRequest.Alert -> {
                val title: (@Composable () -> Unit)? = if (req.title.isBlank()) null else ({ Text(req.title) })
                AlertDialog(
                    onDismissRequest = { prompts.resolve(req) { req.onClose() } },
                    title = title,
                    text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(req.message) } },
                    confirmButton = { TextButton(onClick = { prompts.resolve(req) { req.onClose() } }) { Text("OK") } },
                )
            }

            is PromptRequest.Confirm -> {
                val title: (@Composable () -> Unit)? = if (req.title.isBlank()) null else ({ Text(req.title) })
                AlertDialog(
                    onDismissRequest = { prompts.resolve(req) { req.onAnswer(false) } },
                    title = title,
                    text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(req.message) } },
                    confirmButton = { TextButton(onClick = { prompts.resolve(req) { req.onAnswer(true) } }) { Text("OK") } },
                    dismissButton = { TextButton(onClick = { prompts.resolve(req) { req.onAnswer(false) } }) { Text("Отмена") } },
                )
            }

            is PromptRequest.Input -> {
                var text by remember(req) { mutableStateOf(req.initial) }
                val title: (@Composable () -> Unit)? = if (req.title.isBlank()) null else ({ Text(req.title) })
                AlertDialog(
                    onDismissRequest = { prompts.resolve(req) { req.onAnswer(null) } },
                    title = title,
                    text = {
                        Column {
                            if (req.message.isNotBlank()) {
                                Text(req.message, Modifier.padding(bottom = 12.dp))
                            }
                            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                        }
                    },
                    confirmButton = { TextButton(onClick = { prompts.resolve(req) { req.onAnswer(text) } }) { Text("OK") } },
                    dismissButton = { TextButton(onClick = { prompts.resolve(req) { req.onAnswer(null) } }) { Text("Отмена") } },
                )
            }

            is PromptRequest.Pick -> {
                val selected = remember(req) {
                    mutableStateListOf<String>().apply { addAll(req.items.filter { it.selected }.map { it.id }) }
                }
                val title: (@Composable () -> Unit)? = if (req.title.isBlank()) null else ({ Text(req.title) })
                AlertDialog(
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
                            TextButton(onClick = { prompts.resolve(req) { req.onAnswer(selected.toList()) } }) { Text("OK") }
                        }
                    },
                    dismissButton = { TextButton(onClick = { prompts.resolve(req) { req.onAnswer(null) } }) { Text("Отмена") } },
                )
            }
        }
    }
}
