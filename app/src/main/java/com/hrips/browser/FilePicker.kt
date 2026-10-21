package com.hrips.browser

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import java.io.File

/** Что просит страница: `<input type=file accept=... multiple capture=...>`. */
class FilePickRequest(
    val multiple: Boolean,
    val mimeTypes: Array<String>,
    val capture: Int,
)

/**
 * Выбор файла как в Chrome: системный chooser (`ACTION_GET_CONTENT`), где есть «Файлы», галерея, Google Диск,
 * мессенджеры и любые другие приложения, умеющие отдавать файлы, плюс «Камера» / «Видеокамера» / «Запись звука»,
 * если сайт принимает такие типы. Если у сайта указан `capture`, сразу открывается камера (или запись звука).
 */
class FilePickContract : ActivityResultContract<FilePickRequest, List<Uri>>() {
    private val outputs = ArrayList<Pair<File, Uri>>()

    override fun createIntent(context: Context, input: FilePickRequest): Intent {
        outputs.clear()
        val types = input.mimeTypes.ifEmpty { arrayOf("*/*") }
        val wantsImage = accepts(types, "image/")
        val wantsVideo = accepts(types, "video/")
        val wantsAudio = accepts(types, "audio/")

        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        val captureIntent: Intent? = when {
            wantsImage && hasCamera -> imageCapture(context, input.capture)
            wantsVideo && hasCamera -> videoCapture(context, input.capture)
            wantsAudio -> Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
            else -> null
        }
        if (input.capture != FilePrompt.Capture.NONE && captureIntent != null && !input.multiple) {
            return captureIntent
        }

        val extra = ArrayList<Intent>()
        if (wantsImage && hasCamera) imageCapture(context, FilePrompt.Capture.NONE)?.let(extra::add)
        if (wantsVideo && hasCamera) videoCapture(context, FilePrompt.Capture.NONE)?.let(extra::add)
        if (wantsAudio) extra.add(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION))

        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (types.size == 1) {
                type = types[0]
            } else {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, types)
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, input.multiple)
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
        }
        return Intent.createChooser(get, "Выбор файла").apply {
            if (extra.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, extra.toTypedArray())
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        val written = outputs.filter { it.first.length() > 0L }
        val pending = outputs.toList()
        outputs.clear()
        if (resultCode != android.app.Activity.RESULT_OK) {
            pending.forEach { it.first.delete() }
            return emptyList()
        }
        val uris = LinkedHashSet<Uri>()
        intent?.data?.let(uris::add)
        intent?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
        }
        if (uris.isEmpty()) {
            // Камера записала файл в наш временный каталог и ничего не вернула.
            written.firstOrNull()?.let { uris.add(it.second) }
        }
        pending.filter { it.second !in uris }.forEach { it.first.delete() }
        return uris.toList()
    }

    private fun accepts(types: Array<String>, prefix: String): Boolean =
        types.any { it == "*/*" || it.startsWith(prefix) }

    private fun newOutput(context: Context, ext: String): Uri? = try {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600 * 1000L) it.delete() }
        val f = File(dir, "capture_${System.nanoTime()}.$ext")
        FileProvider.getUriForFile(context, "${context.packageName}.files", f).also { outputs.add(f to it) }
    } catch (e: Exception) {
        null
    }

    private fun imageCapture(context: Context, capture: Int): Intent? {
        val uri = newOutput(context, "jpg") ?: return null
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            facing(capture)
        }
    }

    private fun videoCapture(context: Context, capture: Int): Intent? {
        val uri = newOutput(context, "mp4") ?: return null
        return Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            facing(capture)
        }
    }

    private fun Intent.facing(capture: Int) {
        when (capture) {
            FilePrompt.Capture.USER -> {
                putExtra("android.intent.extras.CAMERA_FACING", 1)
                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            }
            FilePrompt.Capture.ENVIRONMENT -> putExtra("android.intent.extras.CAMERA_FACING", 0)
        }
    }
}
