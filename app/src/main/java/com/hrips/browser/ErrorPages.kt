package com.hrips.browser

import android.net.Uri
import android.text.TextUtils
import android.util.Base64
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import org.mozilla.geckoview.WebRequestError
import java.text.DateFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Страницы ошибок. Если движок не смог загрузить адрес, он просит у нас страницу вместо него (onLoadError).
 * Раньше мы отвечали "ничего", и при отсутствии интернета или плохом сертификате оставалась пустая вкладка.
 *
 * Оформление в духе Material 3 Expressive, как остальной интерфейс: большая фигура-«печенье» со значком,
 * крупный заголовок, кнопки-таблетки (при нажатии скругление «сжимается»), советы сегментированным списком,
 * подробности в раскрывающейся карточке. Цвета берутся из текущей темы приложения. Опасные страницы
 * (сертификат, фишинг, вредоносные сайты) красные, с «безопасной» кнопкой возврата на первом месте;
 * «всё равно открыть» спрятано в «Дополнительно» и требует второго нажатия.
 */
object ErrorPages {
    /** Значок на фигуре (контурные пути в сетке 24x24). */
    private enum class Kind(val path: String) {
        OFFLINE(
            "M12 20h.01M8.5 16.43a5 5 0 0 1 7 0M5 12.86a10 10 0 0 1 5.17-2.69M19 12.86a10 10 0 0 0-2-1.52" +
                "M2 8.82a15 15 0 0 1 4.18-2.64M22 8.82a15 15 0 0 0-11.29-3.76M2 2l20 20",
        ),
        SEARCH(
            "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.35-4.35M9.5 9.5a1.8 1.8 0 1 1 2.6 1.6c-.6.3-1.1.8-1.1 1.4M11 15h.01",
        ),
        CLOCK("M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 6v6l4 2"),
        BLOCK("M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM4.9 4.9l14.2 14.2"),
        REFRESH("M3 12a9 9 0 0 1 15-6.7L21 8M21 3v5h-5M21 12a9 9 0 0 1-15 6.7L3 16M3 21v-5h5"),
        SHIELD(
            "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72" +
                "a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1zM12 8v4M12 16h.01",
        ),
        WARNING("M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3zM12 9v4M12 17h.01"),
        FILE("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M12 18h.01M10 13.5a2 2 0 1 1 2.6 1.9"),
        ALERT("M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 8v4M12 16h.01"),
    }

    private class Info(
        val kind: Kind,
        val title: String,
        val text: String,
        val tips: List<String> = emptyList(),
        val danger: Boolean = false,
        val canBypass: Boolean = false,
        val canRetry: Boolean = true,
    )

    private fun describe(error: WebRequestError, host: String): Info = when (error.code) {
        WebRequestError.ERROR_UNKNOWN_HOST ->
            Info(
                Kind.SEARCH, "Не удаётся найти сайт", "Адрес «$host» не нашёлся. Возможно, в нём опечатка, или нет связи с интернетом.",
                listOf(
                    "Проверьте, правильно ли написан адрес",
                    "Откройте другой сайт: если он тоже не грузится, дело в сети",
                    "Если включён VPN или прокси, попробуйте выключить",
                ),
            )
        WebRequestError.ERROR_OFFLINE ->
            Info(
                Kind.OFFLINE, "Нет подключения к интернету", "Hrips не может выйти в сеть, поэтому страница не загрузилась.",
                listOf(
                    "Включите Wi-Fi или мобильные данные",
                    "Выключите режим «в самолёте»",
                    "Нажмите «Повторить», когда связь появится",
                ),
            )
        WebRequestError.ERROR_CONNECTION_REFUSED ->
            Info(
                Kind.BLOCK, "Сайт отказал в соединении", "Сервер $host не принимает подключения. Возможно, он временно не работает.",
                listOf("Подождите несколько минут и повторите", "Проверьте адрес и порт, если вводили их вручную"),
            )
        WebRequestError.ERROR_NET_TIMEOUT ->
            Info(
                Kind.CLOCK, "Сайт слишком долго отвечает", "Сервер $host не ответил вовремя.",
                listOf("Проверьте качество соединения", "Попробуйте позже или переключитесь на другую сеть"),
            )
        WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT ->
            Info(
                Kind.REFRESH, "Соединение прервано", "Связь с $host оборвалась во время загрузки страницы.",
                listOf("Повторите загрузку", "Если связь нестабильна, смените сеть Wi-Fi или мобильную"),
            )
        WebRequestError.ERROR_REDIRECT_LOOP ->
            Info(
                Kind.REFRESH, "Слишком много перенаправлений", "Сайт $host бесконечно отправляет вас с одной страницы на другую.",
                listOf("Очистите cookies этого сайта: чаще всего причина в них", "Попробуйте открыть сайт позже"),
            )
        WebRequestError.ERROR_PROXY_CONNECTION_REFUSED ->
            Info(
                Kind.BLOCK, "Прокси отказал в соединении", "Не удалось подключиться к прокси-серверу, через который идёт трафик.",
                listOf("Выключите VPN или прокси и повторите", "Проверьте настройки прокси в Android"),
            )
        WebRequestError.ERROR_UNKNOWN_PROXY_HOST ->
            Info(
                Kind.SEARCH, "Не найден прокси-сервер", "Адрес прокси-сервера не нашёлся, поэтому страница не загрузилась.",
                listOf("Выключите VPN или прокси и повторите", "Проверьте настройки прокси в Android"),
            )
        WebRequestError.ERROR_HTTPS_ONLY ->
            Info(
                Kind.SHIELD, "Сайт не поддерживает HTTPS",
                "Включён режим HTTPS-only, а у $host нет защищённой версии. Hrips не стал открывать его без шифрования.",
                listOf(
                    "Попробуйте позже: возможно, защищённое соединение с сайтом временно не работает",
                    "Если сайт нужен: Настройки, «Конфиденциальность», «Безопасное соединение (HTTPS)», пункт «Разрешить HTTP»",
                ),
            )
        WebRequestError.ERROR_BAD_HSTS_CERT ->
            Info(
                Kind.SHIELD, "Сайт небезопасен",
                "У $host проблема с сертификатом, а сайт требует всегда использовать защищённое соединение. " +
                    "Открыть его в обход проверки нельзя.",
                listOf(
                    "Проверьте дату и время на устройстве: при неверных часах сертификаты считаются просроченными",
                    "Если вы в общедоступной сети Wi-Fi, подключитесь к другой",
                ),
                danger = true,
            )
        WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_SECURITY_SSL ->
            Info(
                Kind.SHIELD, "Соединение не защищено",
                "Hrips не смог убедиться, что это настоящий $host. Возможно, сертификат просрочен или выдан для другого сайта, " +
                    "или кто-то в вашей сети пытается подменить сайт.",
                listOf(
                    "Не вводите здесь пароли и данные карт",
                    "Проверьте дату и время на устройстве: при неверных часах сертификаты считаются просроченными",
                    "В общедоступной сети Wi-Fi переключитесь на мобильные данные",
                ),
                danger = true,
                canBypass = true,
            )
        WebRequestError.ERROR_PORT_BLOCKED ->
            Info(
                Kind.BLOCK, "Порт заблокирован", "Адрес использует порт, который браузер не открывает из соображений безопасности.",
                listOf("Проверьте, правильно ли записан адрес"),
            )
        WebRequestError.ERROR_MALFORMED_URI ->
            Info(Kind.ALERT, "Некорректный адрес", "Адрес записан с ошибкой, поэтому Hrips не может его открыть.", listOf("Проверьте, правильно ли записан адрес"))
        WebRequestError.ERROR_UNKNOWN_PROTOCOL ->
            Info(
                Kind.ALERT, "Неизвестный протокол", "Hrips не умеет открывать адреса такого вида.",
                listOf("Проверьте, правильно ли записан адрес"), canRetry = false,
            )
        WebRequestError.ERROR_FILE_NOT_FOUND ->
            Info(Kind.FILE, "Файл не найден", "Файл по этому адресу не существует: его могли переместить или удалить.")
        WebRequestError.ERROR_FILE_ACCESS_DENIED ->
            Info(
                Kind.FILE, "Нет доступа к файлу", "Hrips не может прочитать этот файл: у него нет на это разрешения.",
                listOf("Откройте файл из файлового менеджера через «Открыть с помощью» и выберите Hrips"),
            )
        WebRequestError.ERROR_CORRUPTED_CONTENT, WebRequestError.ERROR_INVALID_CONTENT_ENCODING ->
            Info(
                Kind.ALERT, "Страница повреждена", "Данные с $host пришли в неправильном виде, поэтому страницу показать нельзя.",
                listOf("Повторите загрузку", "Если ошибка не уходит, проблема на стороне сайта: попробуйте позже"),
            )
        WebRequestError.ERROR_UNSAFE_CONTENT_TYPE ->
            Info(
                Kind.WARNING, "Этот тип содержимого не открывается", "Из соображений безопасности Hrips не показывает такое содержимое напрямую.",
                canRetry = false,
            )
        WebRequestError.ERROR_CONTENT_CRASHED ->
            Info(
                Kind.REFRESH, "Страница аварийно закрылась", "При показе $host что-то сломалось.",
                listOf("Повторите загрузку", "Закройте лишние вкладки: возможно, не хватило памяти"),
            )
        WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI ->
            Info(
                Kind.WARNING, "Вредоносный сайт", "Сайт $host помечен как распространяющий вредоносные программы.",
                listOf("Не скачивайте и не устанавливайте ничего с этого сайта", "Закройте страницу и вернитесь к безопасным"),
                danger = true, canRetry = false,
            )
        WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI ->
            Info(
                Kind.WARNING, "Подозрение на фишинг", "Сайт $host может выдавать себя за другой, чтобы выманить пароли и данные карт.",
                listOf("Не вводите здесь пароли, коды и данные карт", "Если сайт просил войти в аккаунт, смените пароль на настоящем сайте"),
                danger = true, canRetry = false,
            )
        WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI ->
            Info(
                Kind.WARNING, "Опасный сайт", "Сайт $host помечен как вредный: он может навредить устройству или данным.",
                listOf("Закройте страницу и вернитесь к безопасным"),
                danger = true, canRetry = false,
            )
        WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI ->
            Info(
                Kind.WARNING, "Нежелательное ПО", "Сайт $host помечен как предлагающий нежелательные программы: они меняют настройки и мешают работе.",
                listOf("Не устанавливайте программы с этого сайта"),
                danger = true, canRetry = false,
            )
        else ->
            Info(Kind.ALERT, "Не удалось загрузить страницу", "Что-то пошло не так при загрузке $host.", listOf("Обновите страницу или откройте её позже"))
    }

    private fun esc(s: String) = TextUtils.htmlEncode(s)

    /**
     * Строка для вставки в `<script>`. `JSONObject.quote` экранирует кавычки и `/`, но не `<` (последовательность `<!--<script`
     * в адресе ломала бы разбор скрипта) и не разделители строк U+2028/2029.
     */
    private fun jsString(s: String) = JSONObject.quote(s)
        .replace("<", "\\u003c")
        .replace(" ", "\\u2028")
        .replace(" ", "\\u2029")

    /** Цвета текущей темы приложения: страница ошибки (HTML) выглядит как остальной интерфейс. */
    private class Palette(
        val bg: String, val fg: String, val mut: String, val card: String, val card2: String, val line: String,
        val primary: String, val onPrimary: String, val primaryContainer: String, val onPrimaryContainer: String,
        val error: String, val onError: String, val errorContainer: String, val onErrorContainer: String,
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
            bg = hex(cs.surface), fg = hex(cs.onSurface), mut = hex(cs.onSurfaceVariant),
            card = hex(cs.surfaceContainerHigh), card2 = hex(cs.surfaceContainer), line = hex(cs.outlineVariant),
            primary = hex(cs.primary), onPrimary = hex(cs.onPrimary),
            primaryContainer = hex(cs.primaryContainer), onPrimaryContainer = hex(cs.onPrimaryContainer),
            error = hex(cs.error), onError = hex(cs.onError),
            errorContainer = hex(cs.errorContainer), onErrorContainer = hex(cs.onErrorContainer),
            dark = cs.surface.luminance() < 0.5f,
        )
    }

    /** Фигура «печенье» с 9 лепестками: многоугольник из радиуса, меняющегося по косинусу (viewBox 0 0 100 100). */
    private val COOKIE_PATH: String by lazy {
        val points = 180
        buildString {
            for (i in 0 until points) {
                val t = i * 2 * PI / points
                val r = 45.0 + 5.0 * cos(9 * t)
                append(if (i == 0) "M" else "L")
                append(String.format(Locale.ROOT, "%.2f %.2f", 50 + r * cos(t), 50 + r * sin(t)))
            }
            append("Z")
        }
    }

    /** Метки вида %NAME% в шаблоне. */
    private val PLACEHOLDER = Regex("%([A-Z]+)%")

    /** Схемы, которые нельзя повторять кнопкой «Повторить»: javascript: выполнился бы в самой странице ошибки. */
    private val NO_RETRY_SCHEMES = setOf("javascript", "data")

    /** data: адрес готовой страницы ошибки. [target] - адрес, который не загрузился. */
    fun dataUri(target: String?, error: WebRequestError): String {
        val url = target.orEmpty()
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val host = parsed?.host.orEmpty().ifEmpty { url.ifEmpty { "сайт" } }.take(100)
        val info = describe(error, host)

        // Подробности: пары «название - значение»
        val rows = ArrayList<Pair<String, String>>()
        rows += "Код ошибки" to "0x" + Integer.toHexString(error.code)
        if (url.isNotEmpty()) rows += "Адрес" to url.take(300)
        error.certificate?.let { cert ->
            val until = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(cert.notAfter)
            val expired = cert.notAfter.time < System.currentTimeMillis()
            rows += "Сертификат выдан для" to cert.subjectX500Principal.name
            rows += "Издатель" to cert.issuerX500Principal.name
            rows += "Действует до" to until + if (expired) " (срок истёк)" else ""
        }
        val details = rows.joinToString("") { (k, v) ->
            """<div class="row"><span>${esc(k)}</span><code>${esc(v)}</code></div>"""
        }

        val tips = if (info.tips.isEmpty()) "" else {
            val head = if (info.danger) "Что это значит" else "Что можно сделать"
            "<h2>$head</h2><ol class=\"tips\">" + info.tips.joinToString("") { "<li>${esc(it)}</li>" } + "</ol>"
        }

        val bypass = if (info.canBypass) {
            """<details class="more risk"><summary>Дополнительно</summary>
<div class="body"><p>Можно открыть сайт без проверки, но тогда любой в вашей сети сможет увидеть и изменить всё, что вы отправляете: пароли, переписку, данные карт.</p>
<button class="btn risk" id="bp" onclick="bypass()">Всё равно открыть (небезопасно)</button></div></details>"""
        } else ""

        val retry = if (!info.canRetry) "" else {
            val cls = if (info.danger) "tonal" else "fill"
            """<button class="btn $cls" onclick="retry()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="${Kind.REFRESH.path}"/></svg>Повторить</button>"""
        }
        val back = """<button class="btn ${if (info.danger) "fill" else "tonal"}" onclick="back()">Назад</button>"""

        val chip = if (url.startsWith("http", ignoreCase = true)) {
            """<span class="chip"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="${if (info.danger) Kind.SHIELD.path else Kind.SEARCH.path}"/></svg><b>${esc(host)}</b></span>"""
        } else ""

        val retryTarget = if (parsed?.scheme?.lowercase() in NO_RETRY_SCHEMES) "" else url

        // Все метки заменяются за один проход: если в подставленном тексте (адрес с «%TARGET%» и т. п.) окажется метка,
        // она остаётся обычным текстом, а не подменяется и не превращается в разметку
        val values = mapOf(
            "THEME" to themeCss(info.danger),
            "COOKIE" to COOKIE_PATH,
            "ICON" to info.kind.path,
            "CHIP" to chip,
            "FLAG" to if (info.danger) """<div class="flag">Небезопасно</div>""" else "",
            "TITLE" to esc(info.title),
            "TEXT" to esc(info.text),
            "ACTIONS" to if (info.danger) back + retry else retry + back,
            "TIPS" to tips,
            "BYPASS" to bypass,
            "DETAILS" to details,
            "TARGET" to jsString(retryTarget),
        )
        val html = PLACEHOLDER.replace(TEMPLATE) { m -> values[m.groupValues[1]] ?: m.value }
        return "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** CSS-переменные темы: запасные светлая и тёмная палитры, поверх них (если есть) - настоящие цвета приложения. */
    private fun themeCss(danger: Boolean): String {
        fun vars(bg: String, fg: String, mut: String, card: String, card2: String, line: String, acc: String, onAcc: String, cont: String, onCont: String) =
            "--bg:$bg;--fg:$fg;--mut:$mut;--card:$card;--card2:$card2;--line:$line;--accent:$acc;--on-accent:$onAcc;--cont:$cont;--on-cont:$onCont"
        // Опасные страницы: фон и карточки как у обычных ошибок, красный только там, где у обычных фиолетовый (фигура, кнопка, номера)
        val light = if (danger) vars("#FEF7FF", "#1D1B20", "#49454F", "#ECE6F0", "#F3EDF7", "#CAC4D0", "#B3261E", "#FFFFFF", "#F9DEDC", "#410E0B")
        else vars("#FEF7FF", "#1D1B20", "#49454F", "#ECE6F0", "#F3EDF7", "#CAC4D0", "#6750A4", "#FFFFFF", "#EADDFF", "#21005D")
        val dark = if (danger) vars("#141218", "#E6E0E9", "#CAC4D0", "#2B2930", "#211F26", "#49454F", "#F2B8B5", "#601410", "#8C1D18", "#F9DEDC")
        else vars("#141218", "#E6E0E9", "#CAC4D0", "#2B2930", "#211F26", "#49454F", "#D0BCFF", "#381E72", "#4F378B", "#EADDFF")
        val p = palette
        val own = if (p == null) "" else {
            val v = if (danger) vars(p.bg, p.fg, p.mut, p.card, p.card2, p.line, p.error, p.onError, p.errorContainer, p.onErrorContainer)
            else vars(p.bg, p.fg, p.mut, p.card, p.card2, p.line, p.primary, p.onPrimary, p.primaryContainer, p.onPrimaryContainer)
            ":root{color-scheme:${if (p.dark) "dark" else "light"};$v}"
        }
        return ":root{$light}@media (prefers-color-scheme: dark){:root{$dark}}$own"
    }

    private val TEMPLATE = """
<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>%TITLE%</title>
<style>
:root { color-scheme: light dark; }
%THEME%
* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
html, body { margin: 0; background: var(--bg); }
body { color: var(--fg); font-family: Roboto, system-ui, sans-serif; min-height: 100vh; min-height: 100dvh; display: flex; justify-content: center;
  padding: 28px 20px 40px; -webkit-text-size-adjust: 100%; }
main { width: 100%; max-width: 520px; margin: auto 0; }

.hero { position: relative; width: 120px; height: 120px; margin: 4px 0 24px; }
.hero svg { position: absolute; }
.hero .cookie { inset: 0; width: 100%; height: 100%; fill: var(--cont); animation: pop .8s cubic-bezier(.34, 1.56, .64, 1) both; }
.hero .ico { left: 50%; top: 50%; width: 52px; height: 52px; margin: -26px 0 0 -26px; fill: none; stroke: var(--on-cont);
  stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; animation: pop .8s .1s cubic-bezier(.34, 1.56, .64, 1) both; }
@keyframes pop { from { transform: scale(.4) rotate(-70deg); opacity: 0; } 60% { opacity: 1; } to { transform: none; opacity: 1; } }

.flag { display: inline-block; background: var(--accent); color: var(--on-accent); border-radius: 8px; padding: 4px 10px;
  font-size: 12px; font-weight: 700; letter-spacing: .06em; text-transform: uppercase; margin-bottom: 12px; }
.chip { display: inline-flex; align-items: center; gap: 8px; max-width: 100%; padding: 7px 14px 7px 10px; border-radius: 999px;
  background: var(--card); color: var(--mut); font-size: 14px; margin-bottom: 14px; }
.chip svg { flex: none; width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.chip b { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
h1 { font-size: 30px; line-height: 1.2; font-weight: 700; letter-spacing: -.01em; margin: 0 0 12px; overflow-wrap: anywhere; }
p.lead { font-size: 16px; line-height: 1.55; color: var(--mut); margin: 0 0 24px; overflow-wrap: anywhere; }

.actions { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; }
.btn { font: inherit; font-weight: 600; font-size: 15px; height: 48px; padding: 0 24px; border: 0; border-radius: 24px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; gap: 8px;
  transition: border-radius .3s cubic-bezier(.34, 1.56, .64, 1), transform .2s cubic-bezier(.34, 1.56, .64, 1), filter .15s; }
.btn svg { width: 20px; height: 20px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.btn:active { border-radius: 14px; transform: scale(.96); filter: brightness(.94); }
.btn:focus-visible, details.more summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.btn.fill { background: var(--accent); color: var(--on-accent); }
.btn.tonal { background: var(--cont); color: var(--on-cont); }
.btn.risk { background: transparent; color: var(--accent); border: 1.5px solid var(--accent); }
.btn.risk.armed { background: var(--accent); color: var(--on-accent); }

h2 { font-size: 14px; font-weight: 600; letter-spacing: .02em; color: var(--accent); margin: 28px 8px 10px; }
.tips { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; counter-reset: t; }
.tips li { counter-increment: t; display: flex; gap: 14px; align-items: flex-start; background: var(--card); padding: 14px 16px;
  font-size: 15px; line-height: 1.45; border-radius: 6px; overflow-wrap: anywhere; }
.tips li::before { content: counter(t); flex: none; width: 26px; height: 26px; border-radius: 50%; background: var(--cont); color: var(--on-cont);
  display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: 700; margin-top: -1px; }
.tips li:first-child { border-radius: 24px 24px 6px 6px; }
.tips li:last-child { border-radius: 6px 6px 24px 24px; }
.tips li:only-child { border-radius: 24px; }

details.more { margin-top: 12px; background: var(--card2); border-radius: 24px; overflow: hidden; }
details.more summary { list-style: none; cursor: pointer; padding: 16px 20px; font-weight: 600; font-size: 15px; display: flex;
  align-items: center; justify-content: space-between; color: var(--fg); }
details.more summary::-webkit-details-marker { display: none; }
details.more summary::after { content: ""; width: 10px; height: 10px; border-right: 2px solid var(--mut); border-bottom: 2px solid var(--mut);
  transform: rotate(45deg) translate(-2px, -2px); transition: transform .3s cubic-bezier(.34, 1.56, .64, 1); }
details.more[open] summary::after { transform: rotate(225deg) translate(-2px, -2px); }
details.more .body { padding: 0 20px 20px; }
details.more .body p { font-size: 14px; line-height: 1.5; color: var(--mut); margin: 0 0 16px; }
.row { padding: 8px 0; border-top: 1px solid var(--line); }
.row:first-child { border-top: 0; padding-top: 0; }
.row span { display: block; font-size: 12px; color: var(--mut); margin-bottom: 2px; }
.row code { display: block; font: 13px/1.45 ui-monospace, Menlo, Consolas, monospace; word-break: break-all; user-select: text; }
#msg { color: var(--accent); font-size: 14px; margin: 12px 8px 0; }
@media (prefers-reduced-motion: reduce) { * { animation: none !important; transition: none !important; } }
</style></head><body><main>
<div class="hero" aria-hidden="true">
<svg class="cookie" viewBox="0 0 100 100"><path d="%COOKIE%"/></svg>
<svg class="ico" viewBox="0 0 24 24"><path d="%ICON%"/></svg>
</div>
%FLAG%
%CHIP%
<h1>%TITLE%</h1>
<p class="lead">%TEXT%</p>
<div class="actions">%ACTIONS%</div>
%TIPS%
%BYPASS%
<details class="more"><summary>Подробности</summary><div class="body">%DETAILS%</div></details>
<div id="msg" role="status"></div>
</main>
<script>
var TARGET = %TARGET%, armed = false, timer = 0, label = '';
function retry() { if (TARGET) { location.href = TARGET; } else { history.back(); } }
function back() { if (history.length > 1) { history.back(); } else { location.href = 'about:blank'; } }
function disarm(b) { armed = false; b.classList.remove('armed'); b.textContent = label; }
function bypass() {
  var b = document.getElementById('bp'), m = document.getElementById('msg');
  if (!armed) {
    armed = true; label = b.textContent; b.classList.add('armed'); b.textContent = 'Нажмите ещё раз, чтобы открыть';
    // Случайное первое нажатие не должно оставлять кнопку «заряженной»
    timer = setTimeout(function () { disarm(b); }, 5000);
    return;
  }
  clearTimeout(timer);
  if (typeof document.addCertException !== 'function') {
    m.textContent = 'Эта версия движка не позволяет сделать исключение.';
    disarm(b);
    return;
  }
  document.addCertException(true).then(function () { location.href = TARGET; },
    function (e) { m.textContent = 'Не получилось: ' + e; disarm(b); });
}
</script></body></html>
""".trimIndent()
}
