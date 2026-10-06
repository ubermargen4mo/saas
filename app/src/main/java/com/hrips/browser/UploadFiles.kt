package com.hrips.browser

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * Подготовка файлов и папок для отправки на сайты. Всё копируется в кэш приложения и отдаётся движку
 * как обычные файлы: так не зависим от того, какой именно провайдер документов вернул проводник.
 * Копии лежат до следующего выбора (сайт читает файл не сразу, а при отправке формы).
 */
object UploadFiles {
    private const val TAG = "UploadFiles"
    private const val MAX_FILES = 20_000
    private const val MAX_DEPTH = 40

    private fun root(context: Context) = File(context.cacheDir, "uploads")

    /** Удаляет копии от прошлых выборов. */
    fun cleanup(context: Context) {
        root(context).deleteRecursively()
    }

    private fun safe(name: String): String {
        val n = name.replace(Regex("[\\\\/:*?\"<>|\u0000]"), "_")
        return if (n.isEmpty() || n == "." || n == "..") "_" else n
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun copyOne(context: Context, uri: Uri, target: File): Boolean = try {
        target.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
        if (input == null) false else {
            input.use { src -> target.outputStream().use { out -> src.copyTo(out) } }
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось скопировать $uri", e)
        false
    }

    /** Копирует выбранные документы. Возвращает file:// Uri для тех, что удалось скопировать. */
    fun copyFiles(context: Context, uris: List<Uri>): List<Uri> {
        cleanup(context)
        val batch = File(root(context), System.nanoTime().toString())
        return uris.mapIndexedNotNull { i, u ->
            if (u.scheme == "file") return@mapIndexedNotNull u
            val f = File(File(batch, i.toString()), safe(displayName(context, u) ?: "file"))
            if (copyOne(context, u, f)) Uri.fromFile(f) else null
        }
    }

    /**
     * Копирует выбранную папку целиком (с вложенными) и возвращает file:// Uri копии.
     * Имя папки сохраняется, поэтому сайт увидит те же относительные пути, что и на компьютере.
     */
    fun copyTree(context: Context, tree: Uri): Uri? {
        return try {
            cleanup(context)
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val rootName = displayName(context, DocumentsContract.buildDocumentUriUsingTree(tree, rootId)) ?: "folder"
            val dest = File(File(root(context), System.nanoTime().toString()), safe(rootName)).apply { mkdirs() }
            val count = intArrayOf(0)
            copyChildren(context, tree, rootId, dest, 0, count)
            Uri.fromFile(dest)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось скопировать папку", e)
            null
        }
    }

    private fun copyChildren(context: Context, tree: Uri, parentId: String, dir: File, depth: Int, count: IntArray) {
        if (depth > MAX_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext() && count[0] < MAX_FILES) {
                val id = c.getString(0)
                val name = safe(c.getString(1) ?: "file")
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val sub = File(dir, name).apply { mkdirs() }
                    copyChildren(context, tree, id, sub, depth + 1, count)
                } else {
                    count[0]++
                    copyOne(context, DocumentsContract.buildDocumentUriUsingTree(tree, id), File(dir, name))
                }
            }
        }
    }
}
