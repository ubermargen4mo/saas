package com.hrips.browser

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/**
 * Подготовка файлов для `<input type=file>`. Схема как в Firefox (android-components, `FilePicker` + `Uri.toFileUri`):
 *  - GeckoView принимает только пути к локальным файлам; для `content://` он ищет колонку `_data`, а у Google Диска,
 *    галереи, мессенджеров её нет. Поэтому выбранный файл копируется в `cache/uploads` и движку отдаётся `file://`.
 *  - Папка (`webkitdirectory`) копироваться НЕ должна: движку отдаётся сам URI дерева (`OpenDocumentTree`),
 *    он обходит его сам (`IntentUtils.traverseTreeUri`) и отдаёт сайту список файлов.
 *  - URI внутри приватного каталога приложения сайту не отдаются.
 */
object UploadFiles {
    private const val TAG = "UploadFiles"
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024
    private const val KEEP_MS = 60 * 60 * 1000L

    /** Движок обходит дерево в UI-потоке, поэтому огромные папки отсекаем заранее (иначе «приложение не отвечает»). */
    private const val MAX_TREE_ENTRIES = 5_000
    private const val MAX_TREE_DEPTH = 40

    private fun root(context: Context) = File(context.cacheDir, "uploads")

    /** Удаляет все скопированные файлы (при старте приложения; сайт ничего из прошлого запуска уже не читает). */
    fun cleanup(context: Context) {
        val dir = root(context)
        if (!dir.exists()) return
        // Как в Firefox: сначала переименовать, чтобы не столкнуться с новой загрузкой, потом удалить.
        val trash = File(context.cacheDir, "uploads_to_be_deleted")
        trash.deleteRecursively()
        if (dir.renameTo(trash)) trash.deleteRecursively() else dir.deleteRecursively()
    }

    /** Удаляет только старые пачки: файлы текущей загрузки (и других полей формы на той же странице) остаются. */
    private fun pruneOld(context: Context) {
        val now = System.currentTimeMillis()
        root(context).listFiles()?.forEach {
            if (now - it.lastModified() > KEEP_MS) it.deleteRecursively()
        }
    }

    /** Файл внутри приватного каталога приложения (`/data/data`, `/data/user`): отдавать его сайту нельзя. */
    fun isPrivate(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        return try {
            val canonical = File(uri.path ?: return true).canonicalPath
            val dataDir = File(context.applicationInfo.dataDir).canonicalPath
            canonical.startsWith(dataDir) || canonical.startsWith("/data/data") || canonical.startsWith("/data/user")
        } catch (e: Exception) {
            true
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun copyOne(context: Context, uri: Uri, target: File, remaining: Long): Long? = try {
        if (remaining <= 0L) return null
        target.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri) ?: return null
        var copied = 0L
        try {
            input.use { src ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        copied += n.toLong()
                        if (copied > remaining) {
                            throw IllegalStateException("Выбранные файлы слишком большие")
                        }
                        out.write(buffer, 0, n)
                    }
                }
            }
            copied
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "Не удалось скопировать файл (${uri.scheme})", e)
        null
    }

    /** Копирует выбранные файлы в `cache/uploads/<пачка>/<номер>/<имя>`. Вызывать НЕ из главного потока. */
    fun copyFiles(context: Context, uris: List<Uri>): List<Uri> {
        runCatching { pruneOld(context) }
        val batch = File(root(context), System.nanoTime().toString())
        var remaining = MAX_TOTAL_BYTES
        return uris.mapIndexedNotNull { i, u ->
            when (u.scheme) {
                "file" -> if (isPrivate(context, u)) { AppLog.w(TAG, "файл #$i из приватного каталога приложения пропущен"); null } else u
                "content" -> {
                    val name = sanitizeFileName(displayName(context, u) ?: "file", fallback = "_")
                    val f = File(File(batch, i.toString()), name)
                    val t0 = System.currentTimeMillis()
                    val copied = copyOne(context, u, f, remaining)
                    if (copied == null) {
                        AppLog.w(TAG, "файл #$i не скопирован")
                        return@mapIndexedNotNull null
                    }
                    AppLog.i(TAG, "файл #$i скопирован: $copied байт за ${System.currentTimeMillis() - t0} мс")
                    remaining -= copied
                    Uri.fromFile(f)
                }
                else -> null
            }
        }
    }

    /** Проверка папки без копирования: не больше [MAX_TREE_ENTRIES] элементов и [MAX_TREE_DEPTH] уровней. */
    fun treeWithinLimits(context: Context, tree: Uri): Boolean {
        try {
            var count = 0
            val stack = ArrayDeque<Pair<String, Int>>()
            stack.addLast(DocumentsContract.getTreeDocumentId(tree) to 0)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            while (stack.isNotEmpty()) {
                val (id, depth) = stack.removeLast()
                if (depth > MAX_TREE_DEPTH) return false
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        count++
                        if (count > MAX_TREE_ENTRIES) return false
                        if (c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.addLast(c.getString(0) to depth + 1)
                        }
                    }
                }
            }
            AppLog.i(TAG, "папка: элементов $count")
            return true
        } catch (e: Exception) {
            AppLog.w(TAG, "Не удалось прочитать папку", e)
            return false
        }
    }
}
