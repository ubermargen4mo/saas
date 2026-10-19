package com.hrips.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object IncomingIntents {
    private const val MAX_FILE_BYTES = 200L * 1024 * 1024
    private const val MAX_SHARED_TEXT = 32 * 1024
    private const val KEEP_DAYS = 7L
    private val URL_IN_TEXT = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val DOCUMENT_EXTENSIONS = setOf("html", "htm", "xhtml", "pdf")

    fun isLocalFile(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_VIEW && intent.data?.scheme?.lowercase().let { it == "file" || it == "content" }

    private const val CUSTOM_TAB_SESSION = "android.support.customtabs.extra.SESSION"

    /** Ссылка пришла из другого приложения (Custom Tab или обычный VIEW): после «назад» вернёмся туда. */
    fun isFromOtherApp(activity: android.app.Activity, intent: Intent?): Boolean {
        intent ?: return false
        if (intent.action != Intent.ACTION_VIEW) return false
        if (intent.hasExtra(CUSTOM_TAB_SESSION)) return true
        val ref = activity.referrer ?: return false
        return ref.scheme == "android-app" && ref.host != activity.packageName
    }

    fun resolve(context: Context, intent: Intent?): String? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> view(context, intent)
            Intent.ACTION_SEND -> send(intent)
            Intent.ACTION_WEB_SEARCH -> {
                val q = intent.getStringExtra(android.app.SearchManager.QUERY)?.trim().orEmpty().take(MAX_SHARED_TEXT)
                if (q.isEmpty()) null else if (!isSearch(q)) toUrl(q) else search(q)
            }
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
        URL_IN_TEXT.find(text)?.let { return it.value.trimEnd('.', ',', ';', ')', '!', '?') }
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

    private fun localFile(context: Context, uri: Uri, mimeHint: String?): String? {
        val dir = File(context.cacheDir, "opened").apply { mkdirs() }
        cleanup(dir)
        val mime = (mimeHint ?: context.contentResolver.getType(uri))?.lowercase()
        var name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        name = sanitizeFileName(name, fallback = "document")
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

    private fun fallback(uri: Uri): String? = if (uri.scheme == "file") uri.toString() else null

    private fun hasKnownExtension(name: String): Boolean {
        val e = name.substringAfterLast('.', "").lowercase()
        return e in DOCUMENT_EXTENSIONS
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

    private fun cleanup(dir: File) {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
    }
}
