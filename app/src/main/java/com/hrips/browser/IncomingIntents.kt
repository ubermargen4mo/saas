package com.hrips.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

object IncomingIntents {
    private const val MAX_FILE_BYTES = 200L * 1024 * 1024
    private const val MAX_SHARED_TEXT = 32 * 1024
    private const val KEEP_DAYS = 7L
    private val URL_IN_TEXT = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val DOCUMENT_EXTENSIONS = setOf("html", "htm", "xhtml", "pdf")
    private const val CUSTOM_TAB_SESSION = "android.support.customtabs.extra.SESSION"

    fun isLocalFile(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_VIEW && intent.data?.scheme?.lowercase().let { it == "file" || it == "content" }

    fun isFromOtherApp(activity: Activity, intent: Intent?): Boolean {
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
            Intent.ACTION_WEB_SEARCH -> sharedText(intent, android.app.SearchManager.QUERY)?.let { toUrl(it, external = true) }
            Intent.ACTION_PROCESS_TEXT -> sharedText(intent, Intent.EXTRA_PROCESS_TEXT)?.let(::search)
            else -> null
        }
    }

    private fun sharedText(intent: Intent, extra: String): String? =
        intent.getCharSequenceExtra(extra)?.toString()?.trim().orEmpty().take(MAX_SHARED_TEXT).ifEmpty { null }

    private fun search(text: String) = SearchEngines.current.template + Uri.encode(text)

    private fun send(intent: Intent): String? {
        val text = sharedText(intent, Intent.EXTRA_TEXT) ?: return null
        URL_IN_TEXT.find(text)?.let { return it.value.trimEnd('.', ',', ';', ')', '!', '?') }
        return toUrl(text, external = true)
    }

    private fun view(context: Context, intent: Intent): String? {
        val uri = intent.data ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> uri.toString()
            "file" -> if (isOwnPrivateFile(context, uri)) null else localFile(context, uri, intent.type)
            "content" -> localFile(context, uri, intent.type)
            else -> null
        }
    }

    private fun localFile(context: Context, uri: Uri, mimeHint: String?): String? {
        val dir = File(context.cacheDir, "opened").apply { mkdirs() }
        cleanup(dir)
        val mime = (mimeHint ?: context.contentResolver.getType(uri))?.lowercase()
        var name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        name = sanitizeFileName(name, fallback = "document")
        if (name.substringAfterLast('.', "").lowercase() !in DOCUMENT_EXTENSIONS) {
            name += when (mime) {
                "application/pdf" -> ".pdf"
                "application/xhtml+xml" -> ".xhtml"
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

    /**
     * Другое приложение может прислать file:// на личные файлы браузера (настройки, журнал, пароли): ContentResolver
     * открыл бы их с правами браузера и показал во вкладке. Такие адреса не открываем.
     */
    private fun isOwnPrivateFile(context: Context, uri: Uri): Boolean {
        val path = uri.path ?: return true
        return try {
            val file = File(path).canonicalFile
            val own = listOfNotNull(context.dataDir, context.filesDir.parentFile).map { it.canonicalFile }.distinct()
            val opened = File(context.cacheDir, "opened").canonicalFile
            !file.startsWith(opened) && own.any { file.startsWith(it) }
        } catch (e: Exception) {
            true
        }
    }

    private fun fallback(uri: Uri): String? = if (uri.scheme == "file") uri.toString() else null

    private fun displayName(context: Context, uri: Uri): String? =
        if (uri.scheme != "content") null else queryDisplayName(context, uri)

    private fun cleanup(dir: File) {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
    }
}
