package com.blaze.assistant.memory

import java.io.Reader

/**
 * Tiered memory, cache tier (0.49.0): splits a large text into sections of about [target] characters (~2k tokens at
 * 4 characters a token), cutting at headings first, then at paragraph breaks, then (one huge paragraph) at a sentence
 * end, then at a space, and only as a last resort mid-word. Never splits a surrogate pair.
 *
 * Streaming: it reads [Reader] in small blocks and holds at most one section (plus one capped line) in memory, so a
 * 50 MB file never has to be loaded whole on a low-RAM phone. Every character of the input ends up in exactly one
 * [Piece] in order, so the concatenated pieces are the original text verbatim (the cache file is written from them).
 *
 * Pure Kotlin; ChunkerTest.
 */
class Chunker(
    val target: Int = TARGET,
    val hardMax: Int = HARD_MAX,
    val minChars: Int = MIN_CHARS,
) {
    init { require(minChars in 1 until target && target < hardMax) }

    /** One section: [title] (heading or first words), its [chapter] (the latest level 1–2 heading), [level] of its heading (0 = none). */
    data class Piece(val title: String, val chapter: String, val level: Int, val text: String)

    private val sb = StringBuilder()
    private var title = ""
    private var titles = ArrayList<String>()
    private var level = 0
    private var chapter = ""
    private var sectionChapter = ""
    private var lastPara = -1          // index in sb just after the latest blank line
    private var prevBlank = true
    private var cont = false
    private var inCode = false

    /** Reads [reader] to the end and calls [emit] for each section in order. Stops early when [stop] says so. */
    fun chunk(reader: Reader, stop: () -> Boolean = { false }, emit: (Piece) -> Unit) {
        val lines = LineReader(reader, hardMax)
        while (!stop()) {
            val line = lines.next() ?: break
            feed(line, emit)
        }
        flushAll(emit)
    }

    /** Convenience for tests and small inputs. */
    fun chunk(text: String): List<Piece> { val out = ArrayList<Piece>(); chunk(text.reader()) { out += it }; return out }

    private fun feed(line: String, emit: (Piece) -> Unit) {
        val body = line.trimEnd('\n', '\r')
        if (body.trimStart().startsWith("```")) inCode = !inCode
        val h = if (inCode || body.trimStart().startsWith("```")) null else heading(body)?.takeIf { prevBlank || atx.matches(body) }
        if (h != null) {
            val (lvl, t) = h
            val startNew = sb.isNotBlank() && (sb.length >= minChars || lvl <= 2 && sb.length >= minChars / 4)
            if (startNew) flushTo(sb.length, emit, continued = false)
            if (lvl <= 2) chapter = t
            if (sb.isBlank()) { title = t; titles = arrayListOf(t); level = lvl; sectionChapter = chapter; cont = false }
            else if (titles.size < 3) titles += t
        }
        if (sb.isEmpty()) sectionChapter = chapter
        sb.append(line)
        val blank = body.isBlank()
        if (blank) lastPara = sb.length
        prevBlank = blank
        if (sb.length >= target) split(emit)
    }

    /** The buffer is over [target]: cut at the last paragraph break, else at a sentence end / space when over [hardMax]. */
    private fun split(emit: (Piece) -> Unit) {
        while (sb.length >= target) {
            if (lastPara >= minChars) { flushTo(lastPara, emit, continued = true); continue }
            if (sb.length < hardMax) return
            flushTo(cutPoint(sb, minChars, hardMax), emit, continued = true)
        }
    }

    private fun flushTo(end: Int, emit: (Piece) -> Unit, continued: Boolean) {
        if (end <= 0) return
        val text = sb.substring(0, end)
        val t = when {
            titles.size > 1 -> titles.joinToString(" · ")
            title.isNotBlank() -> title + if (cont) " (cont.)" else ""
            else -> firstWords(text)
        }
        emit(Piece(t.take(TITLE_MAX), sectionChapter, level, text))
        sb.delete(0, end)
        lastPara = if (lastPara >= end) lastPara - end else -1
        if (continued && sb.isNotEmpty()) {
            // the rest of the same section continues under the same title
            cont = title.isNotBlank()
            titles = if (title.isNotBlank()) arrayListOf(title) else arrayListOf()
        } else { title = ""; titles = arrayListOf(); level = 0; cont = false }
        sectionChapter = chapter
    }

    private fun flushAll(emit: (Piece) -> Unit) {
        if (sb.isNotEmpty()) flushTo(sb.length, emit, continued = false)
    }

    companion object {
        /** ~2k tokens. */
        const val TARGET = 8_000
        /** One section never exceeds this (a paragraph with no break is cut here at the latest). */
        const val HARD_MAX = 10_000
        /** A heading only starts a new section once the current one has this much text (tiny sections merge). */
        const val MIN_CHARS = 1_200
        const val TITLE_MAX = 80

        private val atx = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
        private val named = Regex("""^\s*(chapter|part|book|section|appendix|cap[ií]tulo|chapitre)\s+([0-9]+|[ivxlcdm]+|[a-z])\b[\s.:—-]*(.{0,70})$""", RegexOption.IGNORE_CASE)
        private val letters = Regex("""\p{L}""")

        /** (level, title) when [line] is a heading: Markdown `#`, "Chapter 3: …", or a short ALL-CAPS line. */
        fun heading(line: String): Pair<Int, String>? {
            if (line.isBlank() || line.length > 120) return null
            atx.matchEntire(line)?.let { return it.groupValues[1].length to it.groupValues[2].trim() }
            named.matchEntire(line)?.let { m ->
                val lvl = if (m.groupValues[1].lowercase() in setOf("part", "book")) 1 else 2
                return lvl to line.trim().trimEnd(':', '.')
            }
            val t = line.trim()
            val n = letters.findAll(t).count()
            if (t.length in 3..60 && n >= 3 && t == t.uppercase() && t != t.lowercase() && !t.endsWith(".") && !t.endsWith(",")) return 2 to t
            return null
        }

        /** Where to cut a block with no paragraph break: a sentence end, else a space, else [max] (never inside a surrogate pair). */
        fun cutPoint(s: CharSequence, min: Int, max: Int): Int {
            val hi = minOf(max, s.length)
            var i = hi - 1
            while (i > min) {
                val c = s[i]
                if ((c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' || c == '\n') && (i + 1 >= s.length || s[i + 1].isWhitespace() || c == '。' || c == '\n'))
                    return i + 1
                i--
            }
            i = hi - 1
            while (i > min) { if (s[i].isWhitespace()) return i + 1; i-- }
            return safe(s, hi)
        }

        /** [at] moved back one char when it would split a surrogate pair. */
        fun safe(s: CharSequence, at: Int): Int =
            if (at in 1 until s.length && Character.isHighSurrogate(s[at - 1]) && Character.isLowSurrogate(s[at])) at - 1 else at

        /** The first words of [text] as a title (headings and blank lines skipped). */
        fun firstWords(text: String, max: Int = 60): String {
            val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().trimStart('#', ' ')
            if (line.length <= max) return line
            val cut = line.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: safe(line, max)
            return line.substring(0, cut).trimEnd() + "…"
        }

        /** A one-line gist without a model: the first sentence (or line) that isn't a heading, clipped. */
        fun fallbackGist(text: String, max: Int = 120): String {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && heading(it) == null }
            val first = lines.firstOrNull() ?: text.trim()
            val sent = Regex("""^(.{20,}?[.!?。])(\s|$)""").find(first)?.groupValues?.get(1) ?: first
            if (sent.length <= max) return sent
            val cut = sent.lastIndexOf(' ', max - 1).takeIf { it > max / 2 } ?: safe(sent, max - 1)
            return sent.substring(0, cut).trimEnd() + "…"
        }
    }

    /** Lines (with their '\n') of at most [max] chars; a longer line comes out in parts (surrogate-safe). */
    private class LineReader(private val r: Reader, private val max: Int) {
        private val buf = CharArray(8192)
        private var pos = 0
        private var len = 0
        private var eof = false
        private var pending: Char? = null

        private fun fill(): Boolean {
            if (eof) return false
            len = r.read(buf, 0, buf.size); pos = 0
            if (len <= 0) { eof = true; len = 0; return false }
            return true
        }

        fun next(): String? {
            val out = StringBuilder()
            pending?.let { out.append(it); pending = null }
            while (true) {
                if (pos >= len && !fill()) break
                val c = buf[pos++]
                out.append(c)
                if (c == '\n') break
                if (out.length >= max) {
                    // never end a part on the first half of a surrogate pair
                    if (Character.isHighSurrogate(out[out.length - 1])) { pending = out[out.length - 1]; out.setLength(out.length - 1) }
                    break
                }
            }
            return if (out.isEmpty()) null else out.toString()
        }
    }
}
