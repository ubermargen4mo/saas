package com.hrips.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Что просят открыть другие приложения:
 *  - ссылки http/https (браузер по умолчанию);
 *  - локальные HTML и PDF (file:// и content://);
 *  - «Поделиться в hrips» (ACTION_SEND): ссылка открывается, обычный текст ищется;
 *  - «Искать в hrips» (ACTION_PROCESS_TEXT): выделенный текст всегда ищется.
 */
object IncomingIntents {
    private const val MAX_FILE_BYTES = 200L * 1024 * 1024
    private const val MAX_SHARED_TEXT = 32 * 1024
    private const val KEEP_DAYS = 7L

    /** true, если для intent'а придётся копировать файл: вызывать [resolve] не из главного потока. */
    fun isLocalFile(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_VIEW && intent.data?.scheme?.lowercase().let { it == "file" || it == "content" }

    /** Адрес, который нужно открыть в новой вкладке, или null, если в intent'е для нас ничего нет. */
    fun resolve(context: Context, intent: Intent?): String? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> view(context, intent)
            Intent.ACTION_SEND -> send(intent)
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
                    .take(MAX_SHARED_TEXT)
                if (text.isEmpty()) null else search(text)
            }
            else -> null
        }
    }

    private fun search(text: String) = SearchEngines.current.template + Uri.encode(text)

    private fun send(intent: Intent): String? {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            .take(MAX_SHARED_TEXT)
        if (text.isEmpty()) return null
        // Обычно делятся «заголовок + ссылка»: берём первую ссылку с http(s)://
        Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(text)?.let { return it.value.trimEnd('.', ',', ';', ')', '!', '?') }
        // Просто «example.com» - адрес, всё остальное - поисковый запрос
        return if (!isSearch(text)) toUrl(text) else search(text)
    }

    private fun view(context: Context, intent: Intent): String? {
        val uri = intent.data ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> uri.toString()
            "file", "content" -> localFile(context, uri, intent.type)
            else -> null
        }
    }

    /**
     * Локальные документы копируем в кэш приложения и открываем оттуда как file://: content:// движок
     * не читает, а прямой доступ к чужим файлам закрыт системой. Сам файл при этом не меняется.
     * Минус: у HTML рядом лежащие картинки и стили по относительным путям не подтянутся.
     */
    private fun localFile(context: Context, uri: Uri, mimeHint: String?): String? {
        val dir = File(context.cacheDir, "opened").apply { mkdirs() }
        cleanup(dir)
        val mime = (mimeHint ?: context.contentResolver.getType(uri))?.lowercase()
        var name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        name = name.replace(Regex("[\\\\/:*?\"<>|\u0000]"), "_").ifEmpty { "document" }
        if (!hasKnownExtension(name)) {
            name += when {
                mime == "application/pdf" -> ".pdf"
                mime == "application/xhtml+xml" -> ".xhtml"
                else -> ".html"
            }
        }
        val target = File(File(dir, System.nanoTime().toString()), name)
        return try {
            target.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(uri) ?: return fallback(uri)
            input.use { src ->
                target.outputStream().use { out ->
                    var total = 0L
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILE_BYTES) error("too big")
                        out.write(buf, 0, n)
                    }
                }
            }
            Uri.fromFile(target).toString()
        } catch (e: Exception) {
            target.parentFile?.deleteRecursively()
            fallback(uri)
        }
    }

    /** Скопировать не вышло: для file:// пробуем открыть напрямую (сработает, если есть доступ к файлам). */
    private fun fallback(uri: Uri): String? = if (uri.scheme == "file") uri.toString() else null

    private fun hasKnownExtension(name: String): Boolean {
        val e = name.substringAfterLast('.', "").lowercase()
        return e in setOf("html", "htm", "xhtml", "pdf")
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } else null
    } catch (e: Exception) {
        null
    }

    /** Старые копии (больше недели) удаляем; свежие нужны, чтобы вкладка с документом переживала перезапуск. */
    private fun cleanup(dir: File) {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
    }
}
