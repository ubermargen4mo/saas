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
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024

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
        Log.w(TAG, "Не удалось скопировать $uri", e)
        null
    }

    /** Копирует выбранные документы. Возвращает file:// Uri для тех, что удалось скопировать. */
    fun copyFiles(context: Context, uris: List<Uri>): List<Uri> {
        cleanup(context)
        val batch = File(root(context), System.nanoTime().toString())
        var remaining = MAX_TOTAL_BYTES
        return uris.mapIndexedNotNull { i, u ->
            if (u.scheme == "file") return@mapIndexedNotNull u
            val f = File(File(batch, i.toString()), safe(displayName(context, u) ?: "file"))
            val copied = runCatching { copyOne(context, u, f, remaining) }.getOrNull() ?: return@mapIndexedNotNull null
            remaining -= copied
            Uri.fromFile(f)
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
            val bytes = longArrayOf(0L)
            val tooLarge = booleanArrayOf(false)
            val tooManyFiles = booleanArrayOf(false)
            copyChildren(context, tree, rootId, dest, 0, count, bytes, tooLarge, tooManyFiles)
            if (tooLarge[0]) throw IllegalStateException("Папка слишком большая")
            if (tooManyFiles[0]) throw IllegalStateException("В папке слишком много файлов")
            Uri.fromFile(dest)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось скопировать папку", e)
            null
        }
    }

    private fun copyChildren(context: Context, tree: Uri, parentId: String, dir: File, depth: Int, count: IntArray, bytes: LongArray, tooLarge: BooleanArray, tooManyFiles: BooleanArray) {
        if (depth > MAX_DEPTH || tooLarge[0] || tooManyFiles[0]) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext() && !tooLarge[0] && !tooManyFiles[0]) {
                if (count[0] >= MAX_FILES) {
                    tooManyFiles[0] = true
                    break
                }
                if (bytes[0] >= MAX_TOTAL_BYTES) break
                val id = c.getString(0)
                val name = safe(c.getString(1) ?: "file")
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val sub = File(dir, name).apply { mkdirs() }
                    copyChildren(context, tree, id, sub, depth + 1, count, bytes, tooLarge, tooManyFiles)
                } else {
                    val remaining = MAX_TOTAL_BYTES - bytes[0]
                    val copied = try {
                        copyOne(context, DocumentsContract.buildDocumentUriUsingTree(tree, id), File(dir, name), remaining)
                    } catch (e: IllegalStateException) {
                        tooLarge[0] = true
                        null
                    }
                    if (copied != null) {
                        bytes[0] += copied
                        count[0]++
                    }
                }
            }
        }
    }
}
