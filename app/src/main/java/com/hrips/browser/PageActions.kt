package com.hrips.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Действия меню страницы, которые не относятся к экрану: поделиться, открыть в приложении, ярлык, PDF, снимок. */
object PageActions {

    fun share(context: Context, tab: Tab) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, tab.url)
            .putExtra(Intent.EXTRA_SUBJECT, tab.title)
        context.startActivity(Intent.createChooser(send, null))
    }

    /**
     * Открывает адрес в приложении этого сайта (YouTube, Telegram, карты...), но не в браузере.
     * Android 11+: система сама отсекает браузеры флагом REQUIRE_NON_BROWSER.
     * Раньше: ищем обработчики, у которых в фильтре указан конкретный сайт (у браузеров его нет).
     */
    fun openInApp(context: Context, url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val none = { Toast.makeText(context, "Для этого сайта нет приложения", Toast.LENGTH_SHORT).show() }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                context.startActivity(view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER))
            } else {
                val app = context.packageManager.queryIntentActivities(view, PackageManager.MATCH_ALL).firstOrNull {
                    it.activityInfo.packageName != context.packageName && it.filter?.authoritiesIterator()?.hasNext() == true
                }
                if (app == null) none() else context.startActivity(view.setPackage(app.activityInfo.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: ActivityNotFoundException) {
            none()
        }
    }

    /**
     * Перевод страницы через Google Переводчик на язык системы. Страница открывается в этой же вкладке
     * через translate.google.com, поэтому Google увидит адрес переводимой страницы.
     */
    fun translate(tab: Tab) {
        val lang = Locale.getDefault().language.ifBlank { "ru" }
        tab.load("https://translate.google.com/translate?sl=auto&tl=$lang&u=" + Uri.encode(tab.url))
    }

    fun isTranslated(tab: Tab): Boolean {
        val h = siteKey(tab.url) ?: return false
        return h == "translate.google.com" || h.endsWith(".translate.goog")
    }

    fun addToStartPage(context: Context, store: Store, tab: Tab) {
        if (store.speedDial.any { it.url == tab.url }) {
            Toast.makeText(context, "Уже есть на начальной странице", Toast.LENGTH_SHORT).show()
            return
        }
        store.addDial(tab.url, tab.title.ifBlank { tab.siteHost() ?: tab.url })
        Toast.makeText(context, "Добавлено на начальную страницу", Toast.LENGTH_SHORT).show()
    }

    /** Ярлык на главный экран: круг с первой буквой сайта (цвет зависит от сайта). */
    fun addToHomeScreen(context: Context, tab: Tab) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, "Лаунчер не поддерживает ярлыки", Toast.LENGTH_SHORT).show()
            return
        }
        val host = tab.siteHost() ?: return
        val label = tab.title.ifBlank { host }.take(24)
        val palette = intArrayOf(0xFF5B4BB5.toInt(), 0xFF00796B.toInt(), 0xFFC2410C.toInt(), 0xFF1D4ED8.toInt(), 0xFFA21CAF.toInt(), 0xFF4D7C0F.toInt())
        val bmp = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(palette[Math.floorMod(host.hashCode(), palette.size)])
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 100f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        c.drawText(host.first().uppercase(), 108f, 108f - (p.descent() + p.ascent()) / 2, p)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tab.url)).setClass(context, MainActivity::class.java)
        val info = ShortcutInfoCompat.Builder(context, "site-" + tab.url.hashCode())
            .setShortLabel(label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(bmp))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    private fun safeName(s: String) = s.replace(Regex("""[\\/:*?"<>|\n\r]"""), "_").trim().take(80).ifBlank { "page" }

    /** «Сохранить как PDF»: движок собирает PDF страницы, дальше это обычная загрузка. */
    fun savePdf(tab: Tab, downloads: Downloads) {
        val name = safeName(tab.title.ifBlank { tab.siteHost() ?: "page" }) + ".pdf"
        try {
            tab.session.saveAsPdf().accept({ stream ->
                if (stream == null) downloads.toast("Не удалось сохранить PDF")
                else downloads.saveStream(name, "application/pdf", tab.isPrivate) { stream }
            }, { downloads.toast("Не удалось сохранить PDF") })
        } catch (e: Throwable) {
            downloads.toast("Не удалось сохранить PDF")
        }
    }

    /** Снимок видимой части страницы (без интерфейса браузера) в PNG, как обычная загрузка. */
    fun screenshot(activity: Activity?, view: View?, tab: Tab, downloads: Downloads) {
        if (activity == null || view == null || tab.home || view.width == 0 || view.height == 0 ||
            (view as? org.mozilla.geckoview.GeckoView)?.session !== tab.session
        ) {
            downloads.toast("Нечего снимать"); return
        }
        if ((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            downloads.toast("Снимки в приватных вкладках выключены. Включить: Настройки, Конфиденциальность"); return
        }
        try {
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val rect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
            PixelCopy.request(activity.window, rect, bmp, { result ->
                if (result != PixelCopy.SUCCESS) {
                    downloads.toast("Не удалось сделать снимок"); return@request
                }
                val name = "hrips-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
                downloads.saveStream(name, "image/png", tab.isPrivate) {
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    ByteArrayInputStream(out.toByteArray())
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Throwable) {
            downloads.toast("Не удалось сделать снимок")
        }
    }
}
