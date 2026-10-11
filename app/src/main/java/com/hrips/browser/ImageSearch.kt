package com.hrips.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Поиск по фото в поисковике, который выбран по умолчанию.
 *
 * Поисковики принимают картинку только POST-запросом (multipart), а у движка нет способа отправить его из приложения.
 * Поэтому собираем маленькую локальную страницу `data:` с формой: скрипт кладёт уменьшенное фото в поле файла
 * и сразу отправляет форму, так что пользователь попадает прямо на страницу результатов.
 *
 * Своего поиска по картинкам нет у DuckDuckGo, Ecosia, Qwant, Startpage и Википедии: для них берём Google Lens
 * и говорим об этом в уведомлении.
 */
object ImageSearch {
    /** Начало адреса служебной страницы (вкладка узнаёт её по этому началу и флагу, см. Tab.loadImageSearch). */
    const val PREFIX = "data:text/html;charset=utf-8;base64,"

    private const val MAX_SIDE = 1600
    private const val MAX_READ_BYTES = 40L * 1024 * 1024

    /** [fileField] - поле с файлом; [base64Field] - поле, куда кладётся картинка в base64 (так принимает Bing). */
    class Target(
        val host: String,
        val action: String,
        val fileField: String? = null,
        val base64Field: String? = null,
        val extra: Map<String, String> = emptyMap(),
    )

    private val google = Target("www.google.com", "https://lens.google.com/v3/upload?hl=ru", fileField = "encoded_image")
    private val targets = mapOf(
        "ya.ru" to Target("ya.ru", "https://yandex.ru/images/search?rpt=imageview", fileField = "upfile"),
        "www.google.com" to google,
        "www.bing.com" to Target(
            "www.bing.com", "https://www.bing.com/images/search?view=detailv2&iss=sbiupload&FORM=IRSBIQ",
            base64Field = "imageBin",
        ),
    )

    /** Готовый запрос: либо сразу адрес результатов [url], либо страница-отправщик [page] для загрузки формой. */
    class Prepared(val url: String? = null, val page: String? = null)

    /**
     * Загружает фото в поисковик (блокирующе, не в главном потоке). Яндекс не принимает форму напрямую: страница
     * просто открывает выбор файла, поэтому для него фото загружается запросом из приложения, а в браузере
     * открывается готовая страница результатов. null - загрузить не удалось.
     */
    fun prepare(engine: SearchEngine, jpeg: ByteArray): Prepared? =
        if (engine.host == "ya.ru") yandexResultUrl(jpeg)?.let { Prepared(url = it) }
        else Prepared(page = buildPage(targetFor(engine), jpeg))

    /** Два шага, как на сайте: загрузка фото возвращает cbirId, по нему открывается страница результатов. */
    private fun yandexResultUrl(jpeg: ByteArray): String? = try {
        val boundary = "----hrips" + System.nanoTime()
        val request = Uri.encode("""{"blocks":[{"block":"cbir-uploader__get-cbir-id"}]}""")
        val conn = URL("https://yandex.ru/images/search?rpt=imageview&format=json&request=$request").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.outputStream.use { out ->
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"upfile\"; filename=\"blob\"\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray())
                out.write(jpeg)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            if (conn.responseCode !in 200..299) null else {
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val id = JSONObject(body).getJSONArray("blocks").getJSONObject(0).getJSONObject("params").optString("cbirId")
                if (id.isBlank()) null else "https://yandex.ru/images/search?cbir_id=" + Uri.encode(id) + "&rpt=imageview"
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    fun supports(engine: SearchEngine) = targets.containsKey(engine.host)

    /** Куда на самом деле уйдёт фото для [engine] (для неподдерживаемых - Google Lens). */
    fun targetFor(engine: SearchEngine): Target = targets[engine.host] ?: google

    /** Читает и уменьшает фото. Вызывать не в главном потоке. null - не удалось прочитать картинку. */
    fun prepareJpeg(context: Context, uri: Uri): ByteArray? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            var bmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            if (bmp == null) null else {
                val rotation = resolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
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
                // Прозрачность (png) на jpeg станет чёрной: подкладываем белый фон
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                bmp.recycle()
                val out = ByteArrayOutputStream()
                flat.compress(Bitmap.CompressFormat.JPEG, 85, out)
                flat.recycle()
                out.toByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_READ_BYTES }
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** Страница-отправщик: форма с фото, которая уходит сама. */
    fun buildPage(target: Target, jpeg: ByteArray): String {
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
