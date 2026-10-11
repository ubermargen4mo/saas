package com.hrips.browser

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * Запасной значок сайта, пока нет настоящей иконки: цветная плитка с первой буквой. Цвет зависит только от
 * адреса сайта (без www и m.), поэтому один и тот же сайт выглядит одинаково везде: во вкладках, библиотеке, карточках.
 * Идея из Opera, там цвета берутся из таблицы известных сайтов; здесь они считаются по хэшу домена.
 */
@Composable
fun SiteTile(
    url: String,
    text: String,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(size / 4),
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (bg, fg) = remember(url, dark) { siteColors(url, dark) }
    // Буква берётся из адреса сайта (он известен сразу и не меняется), потом из названия. Если нет ни того ни другого, просто земной шар без плитки, а не «?»
    val letter = remember(url, text) {
        val host = siteKey(url)?.removePrefix("m.")
        host?.firstOrNull { it.isLetterOrDigit() }?.uppercase()
            ?: text.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase()
    }
    if (letter == null) {
        // Пока про сайт ничего не известно: земной шар без подложки (как в Опере)
        GlobeIcon(size, modifier)
        return
    }
    val fontSize = with(LocalDensity.current) { (size * 0.52f).toSp() }
    Box(modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        // Буква - часть плитки: размер от размера плитки, а не от шрифта системы. Высота строки равна размеру шрифта
        // и пустое место сверху и снизу срезано: иначе строка (24sp из темы) выше самой плитки, и буква съезжала вниз и вбок
        Text(
            letter,
            style = TextStyle(
                color = fg,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                lineHeight = fontSize,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Земной шар без подложки: значок сайта, о котором пока ничего не получено. */
@Composable
fun GlobeIcon(size: Dp, modifier: Modifier = Modifier) {
    Icon(HripsIcons.Globe, null, modifier.size(size), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Значок сайта для вкладки: настоящая иконка, а пока её нет и страница ничего не прислала (идёт загрузка, названия
 * ещё нет), земной шар без подложки. Когда название пришло, плитка с буквой.
 */
@Composable
fun TabSiteIcon(tab: Tab, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(size / 4)) {
    Favicon(tab.url, size) {
        if (tab.loading && tab.title.isBlank()) GlobeIcon(size, modifier)
        else SiteTile(tab.url, tab.label(), size, modifier, shape)
    }
}

/** Оттенок 0..359 из адреса сайта. String.hashCode() задан стандартом, поэтому цвет не меняется между запусками. */
fun siteHue(url: String): Float {
    val host = runCatching { Uri.parse(url).host }.getOrNull() ?: url
    val key = host.lowercase().removePrefix("www.").removePrefix("m.")
    return Math.floorMod(key.hashCode(), 360).toFloat()
}

/** Пара цветов сайта (фон, содержимое), те же, что у [SiteTile]: заглушки и плитки одного сайта выглядят одинаково. */
fun siteColors(url: String, dark: Boolean): Pair<Color, Color> {
    val hue = siteHue(url)
    // Фон ярче прежнего (в тёмной теме было почти чёрное пятно), а буква почти белая или почти чёрная для чёткого контраста
    return hsv(hue, if (dark) 0.55f else 0.32f, if (dark) 0.58f else 0.93f) to
        hsv(hue, if (dark) 0.08f else 0.85f, if (dark) 1.0f else 0.32f)
}

private fun hsv(hue: Float, saturation: Float, value: Float) =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
