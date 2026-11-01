package com.hrips.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object ImageSearch {
    private const val PREFIX = "data:text/html;charset=utf-8;base64,"
    private const val MAX_SIDE = 1600
    private const val MAX_READ_BYTES = 40L * 1024 * 1024
    private const val CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private class Target(
        val host: String,
        val action: String,
        val fileField: String? = null,
        val base64Field: String? = null,
        val extra: Map<String, String> = emptyMap(),
    )

    private val google = Target("www.google.com", "https://lens.google.com/v3/upload?hl=ru", fileField = "encoded_image")
    private val targets = mapOf(
        "ya.ru" to Target("ya.ru", "https://yandex.ru/images/search?rpt=imageview&cbir_page=sites", fileField = "upfile", extra = mapOf("prg" to "1")),
        "www.google.com" to google,
        "www.bing.com" to Target(
            "www.bing.com", "https://www.bing.com/images/search?view=detailv2&iss=sbiupload&FORM=IRSBIQ",
            base64Field = "imageBin",
        ),
    )

    private class Prepared(val url: String? = null, val page: String? = null)

    private fun prepare(engine: SearchEngine, jpeg: ByteArray): Prepared? =
        (if (engine.host == "ya.ru") yandexResultUrl(jpeg)?.let { Prepared(url = it) } else null)
            ?: Prepared(page = buildPage(targetFor(engine), jpeg))

    @Volatile private var lastError: String = ""

    private fun yandexResultUrl(jpeg: ByteArray): String? {
        lastError = ""
        val id = yandexAppHost(jpeg) ?: yandexLegacy(jpeg)
        return id?.let { "https://yandex.ru/images/search?rpt=imageview&cbir_id=" + Uri.encode(it) }
    }

    private class Reply(val code: Int, val body: String)

    private fun post(url: String, headers: Map<String, String>, write: (OutputStream) -> Unit): Reply {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", CHROME_UA)
            conn.setRequestProperty("Origin", "https://yandex.ru")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use(write)
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else ""
            return Reply(code, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun yandexAppHost(jpeg: ByteArray): String? = try {
        val reply = post(
            "https://yandex.ru/images-apphost/image-download?cbird=37&images_avatars_size=preview&images_avatars_namespace=images-cbir",
            mapOf("Content-Type" to "image/jpeg", "Referer" to "https://yandex.ru/images/", "Accept" to "*/*"),
        ) { it.write(jpeg) }
        if (reply.code !in 200..299) {
            lastError = "Яндекс ответил ${reply.code}"
            null
        } else {
            val found = findCbirUrl(JSONObject(reply.body))
            if (found == null) lastError = "в ответе Яндекса нет адреса фото"
            found?.let { cbirId(it) }
        }
    } catch (e: Exception) {
        lastError = e.javaClass.simpleName
        null
    }

    private fun yandexLegacy(jpeg: ByteArray): String? = try {
        val boundary = "----hrips" + System.nanoTime()
        val request = Uri.encode("""{"blocks":[{"block":"cbir-uploader__get-cbir-id"}]}""")
        val reply = post(
            "https://yandex.ru/images/search?rpt=imageview&format=json&request=$request",
            mapOf("Content-Type" to "multipart/form-data; boundary=$boundary", "Accept" to "application/json, text/plain, */*"),
        ) { out ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"upfile\"; filename=\"blob\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            out.write(jpeg)
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        if (reply.code !in 200..299) null else {
            JSONObject(reply.body).getJSONArray("blocks").getJSONObject(0).getJSONObject("params")
                .optString("cbirId").takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    private fun findCbirUrl(node: Any?): String? = when (node) {
        is String -> node.takeIf { it.contains("get-images-cbir/") }
        is JSONObject -> node.keys().asSequence().firstNotNullOfOrNull { findCbirUrl(node.opt(it)) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findCbirUrl(node.opt(it)) }
        else -> null
    }

    private fun cbirId(url: String): String? {
        var tail = url.substringAfter("get-images-cbir/").substringBefore('?')
        val last = tail.substringAfterLast('/')
        if (last == "preview" || last == "orig" || last.startsWith("s") && last.drop(1).all { it.isDigit() } && tail.count { it == '/' } >= 2) {
            tail = tail.substringBeforeLast('/')
        }
        return tail.takeIf { it.contains('/') && it.isNotBlank() }
    }

    fun effectiveEngine(engine: SearchEngine): SearchEngine =
        if (supports(engine)) engine else SearchEngines.all.first { it.host == google.host }

    fun byUrl(engine: SearchEngine, imageUrl: String): String {
        val enc = Uri.encode(imageUrl)
        return when (effectiveEngine(engine).host) {
            "ya.ru" -> "https://yandex.ru/images/search?rpt=imageview&url=$enc"
            "www.bing.com" -> "https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:$enc"
            else -> "https://lens.google.com/uploadbyurl?url=$enc"
        }
    }

    private var busy = false

    fun start(context: Context, uri: Uri, engine: SearchEngine, tab: Tab, tabAlive: () -> Boolean) {
        if (busy) {
            Notices.show("Поиск по фото уже идёт")
            return
        }
        busy = true
        Notices.show("Ищу по фото…")
        val app = context.applicationContext
        val queued = AppExecutors.tryExecute {
            var jpeg: ByteArray? = null
            var prepared: Prepared? = null
            var reason = ""
            try {
                jpeg = prepareJpeg(app, uri)
                prepared = jpeg?.let { prepare(engine, it) }
                reason = lastError
            } finally {
                val read = jpeg
                val result = prepared
                val why = reason
                MainThread.handler.post {
                    busy = false
                    when {
                        read == null -> Notices.show("Не удалось прочитать фото")
                        result == null -> Notices.show("Не удалось загрузить фото в ${engine.name}" + if (why.isEmpty()) "" else ": $why")
                        !tabAlive() -> Unit
                        else -> {
                            if (!supports(engine)) Notices.show("${engine.name} не ищет по фото, открыт Google Lens")
                            result.url?.let { tab.load(it) } ?: result.page?.let { tab.loadImageSearch(it) }
                        }
                    }
                }
            }
        }
        if (!queued) {
            busy = false
            Notices.show("Приложение занято. Повторите через пару секунд")
        }
    }

    private fun supports(engine: SearchEngine) = targets.containsKey(engine.host)

    private fun targetFor(engine: SearchEngine): Target = targets[engine.host] ?: google

    private fun exifRotation(context: Context, uri: Uri): Float {
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }

    private fun prepareJpeg(context: Context, uri: Uri): ByteArray? {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            var bmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null

            val rotation = exifRotation(context, uri)
            val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            if (rotation != 0f || scale < 1f) {
                val m = Matrix().apply {
                    if (rotation != 0f) postRotate(rotation)
                    if (scale < 1f) postScale(scale, scale)
                }
                val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (t !== bmp) bmp.recycle()
                bmp = t
            }
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp.recycle()
            val out = ByteArrayOutputStream()
            flat.compress(Bitmap.CompressFormat.JPEG, 85, out)
            flat.recycle()
            return out.toByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_READ_BYTES }
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    private fun buildPage(target: Target, jpeg: ByteArray): String {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val extra = target.extra.entries.joinToString("") {
            """<input type="hidden" name="${esc(it.key)}" value="${esc(it.value)}">"""
        }
        val field = when {
            target.base64Field != null -> """<input type="hidden" name="${esc(target.base64Field)}" id="b">"""
            else -> """<input type="file" name="${esc(target.fileField.orEmpty())}" id="i" style="display:none">"""
        }
        val fill = if (target.base64Field != null) {
            "document.getElementById('b').value=D;"
        } else {
            "var s=atob(D),n=s.length,u=new Uint8Array(n);for(var k=0;k<n;k++)u[k]=s.charCodeAt(k);" +
                "var dt=new DataTransfer();dt.items.add(new File([u],'image.jpg',{type:'image/jpeg'}));" +
                "document.getElementById('i').files=dt.files;"
        }
        val html = """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark">
<title>Поиск по фото</title>
<style>html,body{height:100%;margin:0}body{display:flex;align-items:center;justify-content:center;font:16px system-ui,sans-serif;color:#555;background:Canvas}
@media(prefers-color-scheme:dark){body{color:#bbb}}</style></head>
<body><form id="f" method="post" enctype="multipart/form-data" action="${esc(target.action)}">$extra$field</form>
<p>Ищу по фото…</p>
<script>var D="$b64";try{$fill}catch(e){}document.getElementById('f').submit();</script>
</body></html>"""
        return PREFIX + Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
