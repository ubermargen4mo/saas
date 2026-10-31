package com.hrips.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Печать страницы через системную службу печати Android (принтеры, «Сохранить как PDF», Google Cloud/Mopria и т.д.).
 * Страница сначала сохраняется движком в PDF (`saveAsPdf`), затем файл отдаётся системе печати.
 */
object PagePrint {
    private const val TAG = "PagePrint"

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

    fun print(context: Context, tab: Tab) {
        val activity = context.activity()
        if (activity == null) {
            Notices.show("Не удалось открыть печать")
            return
        }
        val appContext = context.applicationContext
        val name = sanitizeFileName(tab.title.ifBlank { tab.siteHost() ?: "page" }, fallback = "page", maxLength = 60)
        val main = MainThread.handler
        try {
            tab.saveAsPdf().accept({ stream ->
                if (stream == null) {
                    Notices.show("Не удалось подготовить печать")
                    return@accept
                }
                val dir = File(appContext.cacheDir, "print").apply { mkdirs() }
                dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3600_000L) it.delete() }
                val file = File(dir, "print_${System.nanoTime()}.pdf")
                val queued = AppExecutors.tryExecute {
                    val ok = try {
                        stream.use { src -> FileOutputStream(file).use { out -> src.copyTo(out) } }
                        file.length() > 0L
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось записать PDF для печати", e)
                        false
                    }
                    main.post {
                        if (!ok || activity.isFinishing || activity.isDestroyed) {
                            file.delete()
                            if (!ok) Notices.show("Не удалось подготовить печать")
                            return@post
                        }
                        try {
                            val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                            pm.print(name, PdfAdapter(file, "$name.pdf"), null)
                        } catch (e: Exception) {
                            Log.w(TAG, "Не удалось запустить печать", e)
                            file.delete()
                            Notices.show("Не удалось открыть печать")
                        }
                    }
                }
                if (!queued) {
                    runCatching { stream.close() }
                    Notices.show("Приложение занято. Повторите через пару секунд")
                }
            }, { Notices.show("Не удалось подготовить печать") })
        } catch (e: Exception) {
            Log.w(TAG, "saveAsPdf не удался", e)
            Notices.show("Не удалось подготовить печать")
        }
    }

    private class PdfAdapter(private val file: File, private val docName: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(docName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            val queued = AppExecutors.tryExecute {
                try {
                    FileInputStream(file).use { src ->
                        FileOutputStream(destination.fileDescriptor).use { out -> src.copyTo(out) }
                    }
                    if (cancellationSignal?.isCanceled == true) callback.onWriteCancelled()
                    else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось отправить PDF в печать", e)
                    callback.onWriteFailed(null)
                }
            }
            if (!queued) callback.onWriteFailed(null)
        }

        override fun onFinish() {
            file.delete()
        }
    }
}
