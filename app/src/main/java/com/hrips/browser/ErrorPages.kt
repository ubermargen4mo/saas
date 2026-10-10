package com.hrips.browser

import android.net.Uri
import android.text.TextUtils
import android.util.Base64
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import java.util.Locale
import org.mozilla.geckoview.WebRequestError

/**
 * Страницы ошибок. Если движок не смог загрузить адрес, он просит у нас страницу вместо него (onLoadError).
 * Раньше мы отвечали "ничего", и при отсутствии интернета или плохом сертификате оставалась пустая вкладка.
 */
object ErrorPages {
    private class Info(
        val title: String,
        val text: String,
        val danger: Boolean = false,
        val canBypass: Boolean = false,
    )

    private fun describe(error: WebRequestError, host: String): Info = when (error.code) {
        WebRequestError.ERROR_UNKNOWN_HOST ->
            Info("Не удаётся найти сайт", "Проверьте, правильно ли написан адрес «$host», и работает ли интернет.")
        WebRequestError.ERROR_OFFLINE ->
            Info("Нет подключения к интернету", "Проверьте Wi-Fi или мобильную сеть и попробуйте снова.")
        WebRequestError.ERROR_CONNECTION_REFUSED ->
            Info("Сайт отказал в соединении", "Сервер $host не принимает подключения. Возможно, он временно не работает.")
        WebRequestError.ERROR_NET_TIMEOUT ->
            Info("Сайт слишком долго отвечает", "Сервер $host не ответил вовремя. Попробуйте позже.")
        WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT ->
            Info("Соединение прервано", "Связь с $host оборвалась во время загрузки страницы.")
        WebRequestError.ERROR_REDIRECT_LOOP ->
            Info("Слишком много перенаправлений", "Сайт $host бесконечно отправляет вас с одной страницы на другую. Помогает очистка cookies этого сайта.")
        WebRequestError.ERROR_BAD_HSTS_CERT ->
            Info(
                "Сайт небезопасен",
                "У $host проблема с сертификатом, а сайт требует всегда использовать защищённое соединение. " +
                    "Открыть его в обход проверки нельзя.",
                danger = true,
            )
        WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_SECURITY_SSL ->
            Info(
                "Соединение не защищено",
                "Hrips не смог убедиться, что это настоящий $host. Возможно, сертификат просрочен, выдан для другого сайта, " +
                    "или кто-то в вашей сети пытается подменить сайт. Пароли и данные карт здесь вводить опасно.",
                danger = true,
                canBypass = true,
            )
        WebRequestError.ERROR_PORT_BLOCKED ->
            Info("Порт заблокирован", "Адрес использует порт, который браузер не открывает из соображений безопасности.")
        WebRequestError.ERROR_MALFORMED_URI ->
            Info("Некорректный адрес", "Проверьте, правильно ли записан адрес.")
        WebRequestError.ERROR_FILE_NOT_FOUND ->
            Info("Файл не найден", "Файл по этому адресу не существует.")
        WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI, WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI,
        WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI, WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI ->
            Info("Опасная страница", "Сайт $host помечен как вредоносный, мошеннический или нежелательный.", danger = true)
        else ->
            Info("Не удалось загрузить страницу", "Попробуйте обновить страницу или открыть её позже.")
    }

    private fun esc(s: String) = TextUtils.htmlEncode(s)

    /** Цвета текущей темы приложения в виде CSS: страница ошибки (HTML) выглядит как остальной интерфейс. */
    private class Palette(
        val bg: String, val fg: String, val mut: String, val card: String,
        val accent: String, val onAccent: String, val danger: String, val onDanger: String,
        val dark: Boolean,
    )

    @Volatile
    private var palette: Palette? = null

    private fun hex(c: Color) = String.format(Locale.ROOT, "#%06X", c.toArgb() and 0xFFFFFF)

    /**
     * Запоминает схему приложения (светлая, тёмная, динамическая, приватная): вызывается из темы при каждой смене.
     * Пока схемы нет, остаются запасные цвета из шаблона.
     */
    fun updatePalette(cs: ColorScheme) {
        palette = Palette(
            bg = hex(cs.surface), fg = hex(cs.onSurface), mut = hex(cs.onSurfaceVariant), card = hex(cs.surfaceContainerHigh),
            accent = hex(cs.primary), onAccent = hex(cs.onPrimary), danger = hex(cs.error), onDanger = hex(cs.onError),
            dark = cs.surface.luminance() < 0.5f,
        )
    }

    /** data: адрес готовой страницы ошибки. [target] - адрес, который не загрузился. */
    fun dataUri(target: String?, error: WebRequestError): String {
        val url = target.orEmpty()
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifEmpty { url.ifEmpty { "сайт" } }
        val info = describe(error, host)

        val cert = error.certificate
        val details = buildString {
            append("Код ошибки: 0x").append(Integer.toHexString(error.code))
            if (cert != null) {
                append("\nСертификат выдан для: ").append(cert.subjectX500Principal.name)
                append("\nИздатель: ").append(cert.issuerX500Principal.name)
                append("\nДействует до: ").append(cert.notAfter)
            }
        }

        val bypass = if (info.canBypass) {
            """<button class="ghost" onclick="bypass()">Всё равно открыть (небезопасно)</button>"""
        } else ""

        val p = palette
        val accent = if (info.danger) p?.danger ?: "#d3302f" else p?.accent ?: "#6750a4"
        val onAccent = if (info.danger) p?.onDanger ?: "#ffffff" else p?.onAccent ?: "#ffffff"
        val theme = if (p == null) "" else
            ":root{color-scheme:${if (p.dark) "dark" else "light"};--bg:${p.bg};--fg:${p.fg};--mut:${p.mut};--card:${p.card}}"

        val html = TEMPLATE
            .replace("%ACCENT%", accent)
            .replace("%ON_ACCENT%", onAccent)
            .replace("%THEME%", theme)
            .replace("%TITLE%", esc(info.title))
            .replace("%TEXT%", esc(info.text))
            .replace("%DETAILS%", esc(details))
            .replace("%BYPASS%", bypass)
            .replace("%TARGET%", JSONObject.quote(url))
        return "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private val TEMPLATE = """
<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%TITLE%</title>
<style>
:root { color-scheme: light dark; --bg:#fffbfe; --fg:#1c1b1f; --mut:#625b71; --card:#f3edf7; --accent:%ACCENT%; --on-accent:%ON_ACCENT%; }
@media (prefers-color-scheme: dark) { :root { --bg:#141218; --fg:#e6e0e9; --mut:#cac4d0; --card:#2b2930; } }
%THEME%
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--fg); font-family: Roboto, system-ui, sans-serif;
  min-height:100vh; display:flex; align-items:center; justify-content:center; padding:24px; }
main { max-width:520px; width:100%; }
.mark { width:64px; height:64px; border-radius:20px; background:var(--accent); color:var(--on-accent);
  display:flex; align-items:center; justify-content:center; font-size:34px; font-weight:800; margin-bottom:20px; }
h1 { font-size:28px; line-height:1.2; margin:0 0 12px; }
p { font-size:16px; line-height:1.5; color:var(--mut); margin:0 0 24px; }
button { font:inherit; font-weight:600; border:0; border-radius:999px; padding:12px 22px; margin:0 8px 8px 0; cursor:pointer;
  background:var(--accent); color:var(--on-accent); }
button.ghost { background:transparent; color:var(--accent); border:1.5px solid var(--accent); }
details { margin-top:16px; color:var(--mut); font-size:14px; }
pre { white-space:pre-wrap; word-break:break-all; background:var(--card); border-radius:14px; padding:12px; margin:8px 0 0; }
#msg { color:var(--accent); font-size:14px; margin-top:8px; }
</style></head><body><main>
<div class="mark">!</div>
<h1>%TITLE%</h1>
<p>%TEXT%</p>
<button onclick="retry()">Повторить</button>
<button class="ghost" onclick="back()">Назад</button>
%BYPASS%
<div id="msg"></div>
<details><summary>Подробности</summary><pre>%DETAILS%</pre></details>
</main>
<script>
var TARGET = %TARGET%;
function retry() { if (TARGET) { location.href = TARGET; } else { history.back(); } }
function back() { if (history.length > 1) { history.back(); } else { location.href = 'about:blank'; } }
function bypass() {
  if (typeof document.addCertException !== 'function') {
    document.getElementById('msg').textContent = 'Эта версия движка не позволяет сделать исключение.';
    return;
  }
  document.addCertException(true).then(function () { location.href = TARGET; },
    function (e) { document.getElementById('msg').textContent = 'Не получилось: ' + e; });
}
</script></body></html>
""".trimIndent()
}
