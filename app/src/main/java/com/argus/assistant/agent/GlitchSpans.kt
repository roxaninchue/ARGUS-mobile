package com.blaze.assistant.agent

import com.blaze.assistant.BrandName

/**
 * fix/chat-flat-ui (owner, 14 Nov 2026): inside the folded "Thinking" section, the parts of the model's reasoning that
 * name the assistant or quote / paraphrase its own system instructions are drawn glitched (ui/GlitchText.kt). This finds
 * those parts. Pure: no android.* imports (GlitchSpansTest).
 *
 * Three detectors, merged into sorted, non-overlapping [Span]s (end exclusive):
 *  1. the assistant's name ([BrandName.NAME], any case) as a whole word;
 *  2. self-reference phrases ("my instructions", "system prompt", "I was told to", ...): the whole sentence that holds one;
 *  3. overlap with the active system prompt: every run of [SHINGLE] normalised words that also appears, in order, in the
 *     prompt ([Index]), as long as the run has at least [MIN_CONTENT] content words (so "I want to make sure that" is
 *     never a match on its own).
 */
object GlitchSpans {
    /** A glitched range of the thought text: [start] inclusive, [end] exclusive. */
    data class Span(val start: Int, val end: Int) {
        init { require(start in 0..end) { "bad span $start..$end" } }
        val length: Int get() = end - start
    }

    /** Words per shingle: 6 normalised words in a row must match the prompt. */
    const val SHINGLE = 6
    /** Content words (not stop words, 3+ letters) a matching shingle needs. */
    const val MIN_CONTENT = 2
    /** A self-reference sentence is cut to at most this many characters on each side of the phrase. */
    const val SENTENCE_REACH = 240
    /** Spans this close (only spaces / punctuation between them) are merged into one. */
    const val MERGE_GAP = 3

    private val word = Regex("[\\p{L}\\p{N}]+(?:['\u2019][\\p{L}\\p{N}]+)*")

    private val name = Regex("(?i)(?<![\\p{L}\\p{N}_])" + Regex.escape(BrandName.NAME) + "(?![\\p{L}\\p{N}_])")

    /** Self-reference: the model talking about its own instructions. */
    private val phrases = Regex(
        "(?i)\\b(?:" +
            "my (?:own )?(?:system |core |hidden |personal )?(?:instructions|guidelines|directives|rules|prompt)" +
            "|(?:the |a )?system (?:prompt|message|instructions?)" +
            "|developer (?:message|instructions?)" +
            "|i(?: was|'m| am|\u2019m| have been|'ve been|\u2019ve been) (?:told|instructed|programmed|configured) to" +
            "|i was told" +
            "|(?:the|my) instructions (?:say|said|tell|state|require|mention)" +
            "|according to my (?:instructions|guidelines|rules|prompt)" +
            ")\\b")

    private val stop = setOf(
        "the", "and", "for", "you", "your", "are", "with", "that", "this", "but", "not", "from", "have", "has", "had", "was",
        "were", "will", "would", "can", "could", "should", "may", "might", "must", "they", "them", "their", "there", "then",
        "than", "what", "when", "where", "which", "who", "how", "why", "all", "any", "each", "just", "also", "into", "about",
        "only", "some", "such", "very", "more", "most", "other", "its", "it's", "our", "out", "one", "let", "lets", "let's",
        "it", "is", "to", "of", "a", "an", "in", "on", "at", "or", "if", "be", "as", "by", "so", "do", "does", "did", "i", "me",
        "my", "we", "us", "he", "she", "him", "her", "his", "hers", "user", "user's", "now", "need", "want", "make", "sure",
        "okay", "ok", "yes", "no", "like", "get", "got", "say", "says", "said", "think", "know", "see", "way", "thing", "things")

    private fun norm(w: String): String = w.lowercase().replace("\u2019", "'")

    private data class Tok(val w: String, val start: Int, val end: Int)

    private fun tokens(s: String): List<Tok> = word.findAll(s).map { Tok(norm(it.value), it.range.first, it.range.last + 1) }.toList()

    /** 64-bit FNV-1a over the words of one shingle (space-joined). */
    private fun hash(words: List<String>, from: Int, n: Int): Long {
        var h = -0x340d631b7bdddcdbL
        for (i in from until from + n) {
            val w = words[i]
            for (ch in w) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
            h = h xor ' '.code.toLong(); h *= 0x100000001b3L
        }
        return h
    }

    private fun content(words: List<String>, from: Int, n: Int): Int =
        (from until from + n).count { words[it].length >= 3 && words[it] !in stop }

    /** The active system prompt, cut into shingles once. */
    class Index internal constructor(internal val shingles: Set<Long>, val words: Int) {
        val isEmpty: Boolean get() = shingles.isEmpty()
    }

    /** Builds the shingle index of [prompt] (the system message(s) of the turn). */
    fun index(prompt: String): Index {
        val w = tokens(prompt).map { it.w }
        if (w.size < SHINGLE) return Index(emptySet(), w.size)
        val set = HashSet<Long>(w.size * 2)
        for (i in 0..w.size - SHINGLE) if (content(w, i, SHINGLE) >= MIN_CONTENT) set += hash(w, i, SHINGLE)
        return Index(set, w.size)
    }

    /** Where the assistant's name appears (whole word, any case). */
    fun names(text: String): List<Span> = name.findAll(text).map { Span(it.range.first, it.range.last + 1) }.toList()

    /** Each self-reference phrase, widened to the sentence that holds it. */
    fun selfRefs(text: String): List<Span> = phrases.findAll(text).map { m -> sentence(text, m.range.first, m.range.last + 1) }.toList()

    /** Runs of the text that also appear in the system prompt (6+ normalised words). */
    fun promptEchoes(text: String, index: Index?): List<Span> {
        if (index == null || index.isEmpty) return emptyList()
        val t = tokens(text)
        if (t.size < SHINGLE) return emptyList()
        val w = t.map { it.w }
        val out = ArrayList<Span>()
        for (i in 0..t.size - SHINGLE) {
            if (content(w, i, SHINGLE) < MIN_CONTENT) continue
            if (hash(w, i, SHINGLE) in index.shingles) out += Span(t[i].start, t[i + SHINGLE - 1].end)
        }
        return out
    }

    /** All three detectors, merged. [index] null = no prompt known (name and phrases only). */
    fun find(text: String, index: Index?): List<Span> {
        if (text.isEmpty()) return emptyList()
        return merge(text, names(text) + selfRefs(text) + promptEchoes(text, index))
    }

    /** Sorted, non-overlapping; spans with only spaces / punctuation (<= [MERGE_GAP] chars) between them become one. */
    fun merge(text: String, spans: List<Span>): List<Span> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.map { Span(it.start.coerceIn(0, text.length), it.end.coerceIn(0, text.length)) }
            .filter { it.length > 0 }.sortedWith(compareBy({ it.start }, { -it.end }))
        val out = ArrayList<Span>()
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && (s.start <= last.end || closeGap(text, last.end, s.start))) {
                if (s.end > last.end) out[out.size - 1] = Span(last.start, s.end)
            } else out += s
        }
        return out
    }

    private fun closeGap(text: String, from: Int, to: Int): Boolean =
        to - from <= MERGE_GAP && (from until to).all { i -> val ch = text[i]; ch == ' ' || ch == ',' || ch == ';' || ch == ':' || ch == '-' }

    /** The sentence around [from, to): back to the last . ! ? or line break, on to the next (kept), within [SENTENCE_REACH]. */
    internal fun sentence(text: String, from: Int, to: Int): Span {
        var s = from
        val minS = (from - SENTENCE_REACH).coerceAtLeast(0)
        while (s > minS && text[s - 1] !in ".!?\n") s--
        while (s < from && text[s].isWhitespace()) s++
        var e = to
        val maxE = (to + SENTENCE_REACH).coerceAtMost(text.length)
        while (e < maxE && text[e] !in ".!?\n") e++
        if (e < text.length && e < maxE && text[e] in ".!?") e++
        while (e > to && text[e - 1].isWhitespace()) e--
        return Span(s, e)
    }
}
