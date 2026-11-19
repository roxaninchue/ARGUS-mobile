package com.argus.assistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle

/**
 * Small Markdown renderer for chat replies: **bold**, *italic*, `code`, ```blocks```, # headings,
 * bullets, numbered lists, > quotes, [links](url), ~~strike~~ and --- rules. Symbols never show raw.
 */
object Md {
    // v0.31.1: groups 9-16 are inline HTML (<b>…</b>, <a href>, <span style="color">, <font color>); see HtmlLite.
    private val inline = Regex("""\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?![\w])|`([^`]+)`|~~(.+?)~~|\[([^\]]+)]\((https?://[^)\s]+)\)""" +
        """|<(b|strong|i|em|u|ins|s|del|strike|code|kbd|tt|mark|sub|sup|small|big|q|cite|var|samp|abbr)(?:\s[^<>]*)?>(.+?)</\9\s*>""" +
        """|<a\s[^<>]*?href\s*=\s*["']([^"']*)["'][^<>]*>(.+?)</a\s*>""" +
        """|<span\s[^<>]*?style\s*=\s*["'][^"']*?(?<![-\w])color\s*:\s*([^;"']+)[^"']*["'][^<>]*>(.+?)</span\s*>""" +
        """|<font\s[^<>]*?color\s*=\s*["']?([#\w(),. ]+?)["']?(?:\s[^<>]*)?>(.+?)</font\s*>""" +
        // v0.45.28: a bare web address (group 17), so _ and * inside it are never read as italics.
        """|(https?://[^\s<>"'`\[\]{}|\\^]+)""")
    // v0.31.1 perf: compiled once (they were compiled per line on every streamed frame).
    private val headingRe = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
    private val headingTightRe = Regex("^\\s{0,3}(#{2,6})(?=[^#\\s])(.*)$")
    private val ruleRe = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val bulletRe = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val numRe = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val quoteRe = Regex("^\\s*>\\s?(.*)$")
    private val tidyRes = listOf("**", "__", "~~").map { it to Regex(Regex.escape(it)) }

    /** v0.45.3: while streaming, a last line that is only a list marker ("-", "*", "3.") waits for its words instead of flashing as text. */
    fun dropLoneMarker(s: String): String {
        val i = s.lastIndexOf('\n')
        val last = s.substring(i + 1)
        return if (Regex("""^\s*(?:[-*+•]|\d{1,2}[.)])\s*$""").matches(last) && last.isNotEmpty()) s.substring(0, maxOf(i, 0)) else s
    }

    /** [live] = a reply still streaming: a tag being typed at the very end ("<str") is hidden until it closes. */
    /**
     * [rich] (chat replies, 0.54.2: owner "the text layout looks ugly"): inline code as drawn chips ([MdChips]) and every
     * bullet / numbered item as its own paragraph with a hanging indent ([MdChips.indentEm]), so a wrapped item's next line
     * starts under its text, not under the bullet. A paragraph already starts on a new line, so the '\n' next to a list
     * item is left out (keeping it would add a blank line). Off = the 0.52 output (copy text, speech, cards, tables).
     */
    fun render(src: String, accent: Color, codeBg: Color, live: Boolean = false, rich: Boolean = false): AnnotatedString = buildAnnotatedString {
        val lines = src.replace("\r", "").let { if (live) dropLoneMarker(HtmlLite.dropPartialTag(it)) else it }.split('\n')
        // which lines are list paragraphs (rich only): no '\n' is written on either side of one
        val item = BooleanArray(lines.size)
        if (rich) { var code = false; lines.forEachIndexed { k, l ->
            if (l.trimStart().startsWith("```")) code = !code
            else if (!code) { val t = tidy(l); item[k] = headingRe.find(t) == null && headingTightRe.find(t) == null && !ruleRe.matches(t) && (bulletRe.find(t) != null || numRe.find(t) != null) }
        } }
        fun nl(i: Int) { if (i < lines.lastIndex && !item[i] && !item[i + 1]) append('\n') }
        var inCode = false
        lines.forEachIndexed { i, raw ->
            if (raw.trimStart().startsWith("```")) { inCode = !inCode; return@forEachIndexed }
            if (inCode) {
                withStyle(SpanStyle(fontFamily = Type.Mono, fontSize = 13.sp, background = codeBg)) { append(raw) }
                nl(i); return@forEachIndexed
            }
            var line = tidy(raw)
            val heading = headingRe.find(line) ?: headingTightRe.find(line)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = if (level <= 2) 17.sp else 16.sp)) {
                        inlines(heading.groupValues[2].trimEnd('#', ' '), accent, codeBg, rich) }
                }
                ruleRe.matches(line) -> append("──────────")
                else -> {
                    val bullet = bulletRe.find(line)
                    val num = numRe.find(line)
                    val quote = quoteRe.find(line)
                    when {
                        item[i] && bullet != null -> listItem(bullet.groupValues[1].length / 2, "\u2022 ", bullet.groupValues[2], accent, codeBg)
                        item[i] && num != null -> listItem(num.groupValues[1].length / 2, num.groupValues[2] + ". ", num.groupValues[3], accent, codeBg)
                        bullet != null -> { append("  ".repeat(bullet.groupValues[1].length / 2) + "•  "); inlines(bullet.groupValues[2], accent, codeBg, rich) }
                        num != null -> { append("  ".repeat(num.groupValues[1].length / 2) + num.groupValues[2] + ".  "); inlines(num.groupValues[3], accent, codeBg, rich) }
                        quote != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("│ "); inlines(quote.groupValues[1], accent, codeBg, rich) }
                        else -> inlines(line, accent, codeBg, rich)
                    }
                }
            }
            if (rich) nl(i) else if (i < lines.lastIndex) append('\n')
        }
    }

    /** One list item as its own paragraph: the marker in mono (a known width), wrapped lines hang under the text. */
    private fun AnnotatedString.Builder.listItem(depth: Int, marker: String, text: String, accent: Color, codeBg: Color) {
        val (first, rest) = MdChips.indentEm(depth, marker.length)
        withStyle(ParagraphStyle(textIndent = TextIndent(first.em, rest.em))) {
            withStyle(SpanStyle(fontFamily = Type.Mono)) { append(marker) }
            inlines(text, accent, codeBg, chips = true)
        }
    }

    /** v0.45.28: plain text, with bare web addresses marked as links (ChatLinks). */
    private fun AnnotatedString.Builder.plain(s: String, accent: Color = Color.Unspecified) {
        val t = HtmlLite.decodeDisplay(HtmlLite.stripTags(s))
        val links = if (t.contains("://")) ChatLinks.find(t) else emptyList()
        if (links.isEmpty()) { append(t); return }
        var at = 0
        for ((a, b, url) in links) {
            append(t.substring(at, a))
            pushStringAnnotation(ChatLinks.TAG, url)
            withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(t.substring(a, b)) }
            pop()
            at = b
        }
        append(t.substring(at))
    }

    private fun AnnotatedString.Builder.inlines(s: String, accent: Color, codeBg: Color, chips: Boolean = false) {
        var last = 0
        val dark = accent.isUnspecified() || accent.luminance() > 0.5f
        for (m in inline.findAll(s)) {
            plain(s.substring(last, m.range.first), accent)
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inlines(g[1], accent, codeBg, chips) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inlines(g[2], accent, codeBg, chips) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlines(g[3], accent, codeBg, chips) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlines(g[4], accent, codeBg, chips) }
                g[5].isNotEmpty() -> code(g[5], codeBg, chips)
                g[6].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[6]) }
                g[8].isNotEmpty() -> {
                    pushStringAnnotation(ChatLinks.TAG, g[8])
                    withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(g[7]) }
                    pop()
                }
                g[9].isNotEmpty() -> {
                    val inner = g[10]
                    when (g[9]) {
                        "code", "kbd", "tt", "samp" -> code(HtmlLite.decodeDisplay(HtmlLite.stripTags(inner)), codeBg, chips)
                        else -> withStyle(htmlStyle(g[9], accent, dark)) { inlines(inner, accent, codeBg, chips) }
                    }
                }
                g[11].isNotEmpty() || g[12].isNotEmpty() -> {
                    val href = HtmlLite.decodeAll(g[11]).trim()
                    // only real web / mail links; javascript:, data: and the rest show as plain text
                    if (Regex("^(https?://|mailto:)\\S+$", RegexOption.IGNORE_CASE).matches(href)) {
                        pushStringAnnotation(ChatLinks.TAG, href)
                        withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { inlines(g[12], accent, codeBg, chips) }
                        pop()
                    } else inlines(g[12], accent, codeBg, chips)
                }
                g[14].isNotEmpty() -> { val t = HtmlLite.tint(g[13], dark); if (t != null) withStyle(SpanStyle(color = t, fontWeight = FontWeight.Medium)) { inlines(g[14], accent, codeBg, chips) } else inlines(g[14], accent, codeBg, chips) }
                g[16].isNotEmpty() -> { val t = HtmlLite.tint(g[15], dark); if (t != null) withStyle(SpanStyle(color = t, fontWeight = FontWeight.Medium)) { inlines(g[16], accent, codeBg, chips) } else inlines(g[16], accent, codeBg, chips) }
                g[17].isNotEmpty() -> {
                    val hit = ChatLinks.find(g[17]).firstOrNull()
                    if (hit == null) append(g[17]) else {
                        pushStringAnnotation(ChatLinks.TAG, hit.third)
                        withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(hit.third) }
                        pop()
                        append(g[17].substring(hit.second))
                    }
                }
                else -> plain(m.value, accent)
            }
            last = m.range.last + 1
        }
        plain(s.substring(last), accent)
    }

    /**
     * Inline code. The 0.52 look: a mono run with a span background (Compose fills the whole line box, so in chat it read as
     * a tall square block). [chips] (chat replies, 0.54.2): no span background; the run is tagged [MdChips.TAG] with a
     * narrow no-break space on each side for padding, a size smaller, and MdText draws a tight rounded chip behind the glyphs.
     */
    private fun AnnotatedString.Builder.code(text: String, codeBg: Color, chips: Boolean) {
        if (!chips) { withStyle(SpanStyle(fontFamily = Type.Mono, fontSize = 13.sp, background = codeBg)) { append(" $text ") }; return }
        pushStringAnnotation(MdChips.TAG, "")
        append(MdChips.PAD)
        withStyle(SpanStyle(fontFamily = Type.Mono, fontSize = MdChips.CODE_EM.em)) { append(text) }
        append(MdChips.PAD)
        pop()
    }

    private fun Color.isUnspecified() = this == Color.Unspecified

    /** Inline HTML tag → style (monochrome-friendly: <mark> is a soft white wash, not yellow). */
    private fun htmlStyle(tag: String, accent: Color, dark: Boolean): SpanStyle = when (tag) {
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.SemiBold)
        "i", "em", "cite", "var", "q" -> SpanStyle(fontStyle = FontStyle.Italic)
        "u", "ins", "abbr" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "s", "del", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "mark" -> SpanStyle(background = (if (dark) Color.White else Color.Black).copy(alpha = 0.16f), fontWeight = FontWeight.Medium)
        "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 11.sp)
        "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 11.sp)
        "small" -> SpanStyle(fontSize = 12.sp)
        "big" -> SpanStyle(fontSize = 17.sp)
        else -> SpanStyle()
    }

    /**
     * v0.28.1: a marker left open (a reply still streaming "**Offline mo", or a stray "**") is hidden instead of
     * shown raw: an odd number of `**` / `__` / `` ` `` / `~~` drops the last unmatched one.
     */
    fun tidy(line: String): String {
        var t = line
        for ((m, re) in tidyRes) {
            val n = re.findAll(t).count()
            if (n % 2 == 1) { val i = t.lastIndexOf(m); t = t.removeRange(i, i + m.length) }
        }
        if (t.count { it == '`' } % 2 == 1) { val i = t.lastIndexOf('`'); t = t.removeRange(i, i + 1) }
        return t
    }

    /** One line or a short label (step lines, card titles) with its inline markdown rendered. */
    fun inline(src: String, accent: Color, codeBg: Color): AnnotatedString = render(src.replace('\n', ' '), accent, codeBg)

    /** Plain text for copy, speech and notifications. */
    fun plain(src: String): String = render(src, Color.Unspecified, Color.Unspecified).text
        .replace("──────────", "").replace("│ ", "")
}
