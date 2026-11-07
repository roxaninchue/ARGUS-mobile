package com.blazeinchue.argus.agent.query

/**
 * 0.51.0 ArgusQuery extraction: from a fetched page (Web.fetch's plain text: "# title" then one line per block element) to
 * the few passages that answer the query, with code blocks and command lines kept whole and in order.
 *
 *  - boilerplate lines (cookie banners, "Sign in", nav crumbs, "All rights reserved", share buttons) are dropped;
 *  - consecutive code-looking lines become one code block (never cut in the middle of a line);
 *  - prose lines are grouped into short paragraphs;
 *  - blocks are ranked by how many query terms they hold; the best [max] are returned in page order.
 *
 * [date] finds the page's newest date (ISO, "March 4, 2026", "4 March 2026"). Pure (ExtractTest).
 */
object Extract {
    data class Piece(val text: String, val code: Boolean, val hits: Int)

    const val PROSE_MAX = 520
    const val CODE_MAX = 900

    private val boiler = Regex("""(?i)^(?:accept(?: all)?(?: cookies)?|we use cookies.*|cookie (?:settings|policy|preferences).*|sign in|sign up|log ?in|register|subscribe.*|share(?: this)?|tweet|copy link|skip to (?:main )?content|table of contents|on this page|menu|search|home\s*[›>/].*|next\s*[›>]?|previous|back to top|was this (?:page|article) helpful\??.*|(?:©|copyright).*|all rights reserved.*|privacy policy|terms of (?:use|service)|edit this page|feedback|related articles?|advertisement|loading\.*|image: .*)$""")
    private val codeLine = Regex("""^(?:\$ |# |> |sudo |\./|[A-Za-z0-9_.-]+(?: [A-Za-z0-9_.:/=@-]+)* --?[A-Za-z][\w-]*|(?:import|from|package|def|fun|val|var|const|let|class|public|private|return|if \(|for \(|while \(|#include|implementation|api|kapt|ksp|plugins|dependencies|apply|android \{|<\?xml|<uses-|<manifest|<activity|<application|\{|\}|\);?$|@[A-Z]\w+))|[;{}]\s*$|\w+\([^)]*\)\s*(?:\{|;|$)|^\s*(?:pip3?|npm|npx|yarn|apk|apt(?:-get)?|brew|cargo|gradle|\./gradlew|adb|git|docker|kubectl|curl|wget|ffmpeg|python3?|node|java|kotlinc|go|rustup|chmod|export|cd|ls|mkdir|cat|grep|sed|awk|tar|unzip)\b""")

    private val sentence = Regex("""[a-z]{2,}[.!?]\s+[A-Z]|[a-z]{2,}[.!?]$""")
    fun isCode(line: String): Boolean { val t = line.trim(); return t.length <= 400 && codeLine.containsMatchIn(t) && !sentence.containsMatchIn(t) }

    fun boilerplate(line: String): Boolean { val t = line.trim(); return t.length <= 2 || (t.length <= 80 && boiler.matches(t)) }

    /** The best passages of [page] for [terms] (lower-case query words), at most [max], in page order. */
    fun pieces(page: String, terms: List<String>, max: Int = 4): List<Piece> {
        val lines = page.lines().map { it.trim() }.filter { it.isNotEmpty() }.drop(if (page.startsWith("# ")) 1 else 0)
            .filterNot { boilerplate(it) || it.startsWith("…(cut,") }
        data class Block(val lines: MutableList<String>, val code: Boolean)
        val blocks = ArrayList<Block>()
        for (l in lines) {
            val c = isCode(l)
            val last = blocks.lastOrNull()
            if (last != null && last.code == c && (if (c) last.lines.sumOf { it.length } + l.length <= CODE_MAX else last.lines.sumOf { it.length } + l.length <= PROSE_MAX && !l.endsWith(":") && last.lines.size < 4))
                last.lines += l
            else blocks += Block(mutableListOf(l), c)
        }
        val low = terms.map { it.lowercase() }.filter { it.length >= 2 }
        fun hits(s: String): Int { val t = s.lowercase(); return low.count { t.contains(it) } }
        // a code block right after the prose that introduces it counts that prose's hits too
        val scored = blocks.mapIndexed { i, b ->
            val text = if (b.code) b.lines.joinToString("\n") else b.lines.joinToString(" ")
            val h = hits(text) + if (b.code && i > 0 && !blocks[i - 1].code) hits(blocks[i - 1].lines.joinToString(" ")) / 2 else 0
            Triple(i, Piece(clip(text, b.code), b.code, h), text.length)
        }.filter { it.second.hits > 0 && it.third >= 25 }
        val best = scored.sortedWith(compareByDescending<Triple<Int, Piece, Int>> { it.second.hits }.thenBy { it.first }).take(max)
        return best.sortedBy { it.first }.map { it.second }
    }

    /** Prose cut at a sentence end under [PROSE_MAX]; code cut at a line end under [CODE_MAX]. */
    fun clip(text: String, code: Boolean): String {
        if (code) {
            if (text.length <= CODE_MAX) return text
            val sb = StringBuilder()
            for (l in text.lines()) { if (sb.length + l.length + 1 > CODE_MAX) break; if (sb.isNotEmpty()) sb.append('\n'); sb.append(l) }
            return sb.toString()
        }
        if (text.length <= PROSE_MAX) return text
        val cut = text.take(PROSE_MAX)
        val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
        return if (end > PROSE_MAX / 2) cut.substring(0, end + 1) else cut.substringBeforeLast(' ') + "…"
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val iso = Regex("""\b(20\d{2}|19\d{2})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])\b""")
    private val mdy = Regex("""(?i)\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.? (\d{1,2}),? (20\d{2})\b""")
    private val dmy = Regex("""(?i)\b(\d{1,2}) (jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]* (20\d{2})\b""")

    /** The newest date in the first part of [text] as yyyy-MM-dd, or "" when it names none (future dates ignored). */
    fun date(text: String, nowYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)): String {
        val head = text.take(6_000)
        val found = ArrayList<String>()
        iso.findAll(head).forEach { found += it.value }
        fun m(s: String) = months.indexOf(s.lowercase().take(3)) + 1
        mdy.findAll(head).forEach { found += "%s-%02d-%02d".format(it.groupValues[3], m(it.groupValues[1]), it.groupValues[2].toInt().coerceIn(1, 31)) }
        dmy.findAll(head).forEach { found += "%s-%02d-%02d".format(it.groupValues[3], m(it.groupValues[2]), it.groupValues[1].toInt().coerceIn(1, 31)) }
        return found.filter { (it.take(4).toIntOrNull() ?: 0) <= nowYear }.maxOrNull().orEmpty()
    }
}
