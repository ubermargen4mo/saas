package com.hrips.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Небольшой разбор разметки для заметок об обновлении. Понимает то, что пишется в ченжлоге:
 * заголовки `#`…`######`, списки (`-`, `*`, `+`, `•`, `1.`), цитаты `>`, блоки кода, линию `---`,
 * **жирный**, *курсив*, ~~зачёркнутый~~, `код` и [ссылки](https://example.com).
 * Одиночная строка, которая начинается с эмодзи («🆕 Новое»), показывается как заголовок раздела.
 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Section(val text: String) : MdBlock
    data class Item(val marker: String, val text: String, val depth: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*$""")
private val BULLET = Regex("""^(\s*)([-*+•])\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
private val RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")

private fun startsWithEmoji(s: String): Boolean {
    if (s.isEmpty()) return false
    val cp = s.codePointAt(0)
    return cp >= 0x1F000 || Character.getType(cp) == Character.OTHER_SYMBOL.toInt()
}

internal fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').lines()
    val out = ArrayList<MdBlock>()
    val para = StringBuilder()
    fun flushPara() {
        val text = para.toString().trim()
        para.clear()
        if (text.isEmpty()) return
        val oneLine = !text.contains('\n')
        out += if (oneLine && text.length <= 40 && startsWithEmoji(text) && !text.endsWith('.')) MdBlock.Section(text) else MdBlock.Para(text)
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flushPara()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                out += MdBlock.Code(code.toString())
            }
            trimmed.isEmpty() -> flushPara()
            RULE.matches(line) -> { flushPara(); out += MdBlock.Rule }
            HEADING.matches(line) -> {
                flushPara()
                val m = HEADING.matchEntire(line)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            BULLET.matches(line) -> {
                flushPara()
                val m = BULLET.matchEntire(line)!!
                out += MdBlock.Item("•", m.groupValues[3], m.groupValues[1].length / 2)
            }
            NUMBERED.matches(line) -> {
                flushPara()
                val m = NUMBERED.matchEntire(line)!!
                out += MdBlock.Item(m.groupValues[2] + ".", m.groupValues[3], m.groupValues[1].length / 2)
            }
            trimmed.startsWith(">") -> {
                flushPara()
                val text = trimmed.removePrefix(">").trim()
                val last = out.lastOrNull()
                if (last is MdBlock.Quote) out[out.lastIndex] = MdBlock.Quote(last.text + "\n" + text) else out += MdBlock.Quote(text)
            }
            // Продолжение пункта списка: строка с отступом сразу под пунктом
            line.startsWith("  ") && para.isEmpty() && out.lastOrNull() is MdBlock.Item -> {
                val last = out.last() as MdBlock.Item
                out[out.lastIndex] = last.copy(text = last.text + " " + trimmed)
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(trimmed)
            }
        }
        i++
    }
    flushPara()
    return out
}

private fun inlineMd(src: String, link: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = src.length
    fun closeOf(token: String, from: Int): Int? = src.indexOf(token, from).takeIf { it > from }
    fun isWord(ch: Char?) = ch != null && (ch.isLetterOrDigit())
    while (i < n) {
        val c = src[i]
        when {
            c == '\\' && i + 1 < n && src[i + 1] in "\\`*_{}[]()#+-.!~>" -> { append(src[i + 1]); i += 2 }
            src.startsWith("**", i) || src.startsWith("__", i) -> {
                val tok = src.substring(i, i + 2)
                val e = closeOf(tok, i + 2)
                if (e == null) { append(c); i++ } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inlineMd(src.substring(i + 2, e), link, codeBg)) }
                    i = e + 2
                }
            }
            src.startsWith("~~", i) -> {
                val e = closeOf("~~", i + 2)
                if (e == null) { append(c); i++ } else {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inlineMd(src.substring(i + 2, e), link, codeBg)) }
                    i = e + 2
                }
            }
            c == '`' -> {
                val e = closeOf("`", i + 1)
                if (e == null) { append(c); i++ } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(src.substring(i + 1, e)) }
                    i = e + 1
                }
            }
            c == '[' -> {
                val mid = src.indexOf("](", i + 1)
                val end = if (mid > 0) src.indexOf(')', mid + 2) else -1
                if (mid > i + 1 && end > mid + 2) {
                    val url = src.substring(mid + 2, end)
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
                        append(src.substring(i + 1, mid))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '*' || (c == '_' && !isWord(src.getOrNull(i - 1))) -> {
                val e = closeOf(c.toString(), i + 1)
                val okClose = e != null && (c == '*' || !isWord(src.getOrNull(e + 1)))
                if (!okClose || src[i + 1].isWhitespace()) { append(c); i++ } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineMd(src.substring(i + 1, e), link, codeBg)) }
                    i = e + 1
                }
            }
            else -> { append(c); i++ }
        }
    }
}

@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val blocks = remember(text) { parseMarkdown(text) }
    val codeBg = cs.surfaceContainerHighest
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    inlineMd(b.text, cs.primary, codeBg),
                    Modifier.padding(top = if (index == 0) 0.dp else 12.dp),
                    style = when (b.level) {
                        1 -> type.headlineSmall
                        2 -> type.titleLarge
                        3 -> type.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        else -> type.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    },
                    color = cs.onSurface,
                )
                is MdBlock.Section -> Text(
                    inlineMd(b.text, cs.primary, codeBg),
                    Modifier.padding(top = if (index == 0) 0.dp else 14.dp),
                    style = type.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = cs.onSurface,
                )
                is MdBlock.Item -> Row(Modifier.padding(start = (b.depth * 16).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(b.marker, Modifier.width(if (b.marker == "•") 12.dp else 24.dp), style = type.bodyLarge, color = cs.onSurfaceVariant)
                    Text(inlineMd(b.text, cs.primary, codeBg), Modifier.weight(1f), style = type.bodyLarge, color = cs.onSurface)
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(cs.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                    Text(inlineMd(b.text, cs.primary, codeBg), style = type.bodyLarge.copy(fontStyle = FontStyle.Italic), color = cs.onSurfaceVariant)
                }
                is MdBlock.Code -> Surface(shape = RoundedCornerShape(12.dp), color = codeBg, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        b.text,
                        Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                        style = type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = cs.onSurface,
                    )
                }
                is MdBlock.Para -> Text(inlineMd(b.text, cs.primary, codeBg), style = type.bodyLarge, color = cs.onSurface)
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = cs.outlineVariant)
            }
        }
    }
}
