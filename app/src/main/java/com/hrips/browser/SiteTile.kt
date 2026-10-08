package com.hrips.browser

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
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
    val hue = remember(url) { siteHue(url) }
    val bg = hsv(hue, if (dark) 0.50f else 0.22f, if (dark) 0.34f else 0.96f)
    val fg = hsv(hue, if (dark) 0.20f else 0.75f, if (dark) 0.96f else 0.38f)
    val letter = remember(text) { text.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?" }
    Box(modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        Text(letter, color = fg, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.46f).sp)
    }
}

/** Оттенок 0..359 из адреса сайта. String.hashCode() задан стандартом, поэтому цвет не меняется между запусками. */
fun siteHue(url: String): Float {
    val host = runCatching { Uri.parse(url).host }.getOrNull() ?: url
    val key = host.lowercase().removePrefix("www.").removePrefix("m.")
    return Math.floorMod(key.hashCode(), 360).toFloat()
}

private fun hsv(hue: Float, saturation: Float, value: Float) =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
