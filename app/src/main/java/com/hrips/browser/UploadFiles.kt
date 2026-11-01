package com.hrips.browser

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

object UploadFiles {
    private const val TAG = "UploadFiles"
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024
    private const val KEEP_MS = 60 * 60 * 1000L

    private const val MAX_TREE_ENTRIES = 5_000
    private const val MAX_TREE_DEPTH = 40

    private fun root(context: Context) = File(context.cacheDir, "uploads")

    fun cleanup(context: Context) {
        val dir = root(context)
        if (!dir.exists()) return
        val trash = File(context.cacheDir, "uploads_to_be_deleted")
        trash.deleteRecursively()
        if (dir.renameTo(trash)) trash.deleteRecursively() else dir.deleteRecursively()
    }

    private fun pruneOld(context: Context) {
        val now = System.currentTimeMillis()
        root(context).listFiles()?.forEach {
            if (now - it.lastModified() > KEEP_MS) it.deleteRecursively()
        }
    }

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
