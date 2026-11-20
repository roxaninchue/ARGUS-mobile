package com.blaze.assistant.agent

/**
 * Skill shelf (20 Nov 2026, docs/SKILL-SHELF.md; MODEL-FIRST "Still to retire": SkillMatch picking skills from words).
 *
 * The model sees every skill that's switched on, one line each (`name — use when …`), and decides itself which one to
 * read with use_skill or replay with run_skill. Nothing guesses before the model and nothing refuses after it.
 *
 *  - Order: the user's own first (learned, then written / installed), then the bundled library; alphabetical inside
 *    each group, so the text is byte-stable for the same set (it rides in the SLOW prompt tier and caches).
 *  - Each line comes from the skill's own description ([useWhen] of it); no second description to keep up to date.
 *  - Shelf cost 4/4: a line is `name — use when …`, at most [LINE_WORDS] words after the dash and no examples
 *    (parentheses, quotes, "e.g." / "such as" lists and dash asides are cut). The cap is enforced here, for the
 *    bundled library and the user's own skills alike, so the "+N more" count stays honest.
 *  - Within [BUDGET_CHARS], always: the user's own skills get the room first, then the bundled library; lines that
 *    don't fit are counted by group in one honest "+N more" line pointing at find_skill (round 5: a big personal
 *    library can no longer push the shelf past its budget).
 *
 * Pure apart from [promptBlock], which reads the cached candidates (SkillShelfTest).
 */
object SkillShelf {
    /** ≈ 1,600 tokens; the 57 bundled skills take about 5,500 chars. */
    const val BUDGET_CHARS = 6_500
    /** A line's summary is cut near this length, at a word boundary. */
    const val LINE_CHARS = 80
    /** Shelf cost 4/4: words after the dash ("use when …"), so a line is about 12 words with its name and marker. */
    const val LINE_WORDS = 10

    const val HEAD = "\n\nSKILLS on this phone (name \u2014 use when). When one fits what the user wants, read it with use_skill before you start; " +
        "one marked [replay] is the user's recorded phone steps and run_skill replays it. find_skill searches them.\n"

    data class Entry(val name: String, val summary: String, val source: String, val replay: Boolean) {
        val line: String get() = "- $name \u2014 $summary${if (replay) " [replay]" else ""}\n"
    }

    /**
     * The first thing a description says, cut near [max] chars: a leading "Workflow." / "Use when:" and a short title
     * sentence ("Price Scout.", "Paperwork Mode.") are dropped; then the first sentence.
     */
    fun summary(description: String, max: Int = LINE_CHARS): String {
        var s = description.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("^(?i)workflow\\.\\s*"), "").replace(Regex("^(?i)use when:\\s*"), "")
        // A title sentence of one to three words ("Price Scout. Find what…") says less than the sentence after it.
        Regex("^([^.!?]{1,40})\\.\\s+(.+)$").find(s)?.let { m -> if (m.groupValues[1].trim().split(' ').size <= 3) s = m.groupValues[2] }
        s = s.split(Regex("(?<=[.!?])\\s"), limit = 2)[0].trim().trimEnd('.')
        if (s.length > max) s = s.take(max).let { t -> t.substring(0, t.lastIndexOf(' ').takeIf { it > max / 2 } ?: t.length) }.trimEnd(',', ';', ':', ' ', '-', '\u2014') + "\u2026"
        return s
    }

    /** Words a cut line doesn't end on. */
    private val DANGLING = setOf("a", "an", "the", "and", "or", "to", "of", "for", "with", "in", "on", "at", "by", "from", "into", "as",
        "that", "which", "its", "it", "their", "your", "then", "so", "but", "nor", "than", "via", "per", "each", "every", "any", "all",
        "this", "these", "those", "one", "if", "when", "while", "what", "who", "how", "without", "within", "through", "about", "over",
        "across", "after", "before", "up", "out")
    /** Where a cut line may end: before one of these, or after a comma. */
    private val BOUNDARY = setOf("and", "or", "but", "then", "for", "with", "in", "on", "at", "by", "from", "into", "as", "of", "to",
        "through", "via", "without", "within", "that", "which", "while", "so", "about", "over", "across", "after", "before")
    private val ARTICLE = setOf("a", "an", "the", "my", "your", "our", "one", "this", "these", "every", "any", "all")
    private val DROP_LEAD = setOf("how", "where", "when", "whenever", "if")
    private val NOT_GERUND = setOf("string", "spring", "during", "nothing", "something", "anything", "everything")

    /** "Advises" → "advise", "teaches" → "teach", "applies" → "apply" (a specialist's prompt often reads "Explains…"). */
    private fun base(w: String): String = when {
        w.length < 4 || w.any { it.isUpperCase() } || !w.endsWith("s") || w.endsWith("ss") || w.endsWith("us") || w.endsWith("is") -> w
        w.endsWith("ies") -> w.dropLast(3) + "y"
        Regex("(?:sh|ch|x|z|o)es$").containsMatchIn(w) -> w.dropLast(2)
        else -> w.dropLast(1)
    }

    /**
     * Shelf cost 4/4: what a shelf line says after the dash, "use when …", from a skill's description or a specialist's
     * prompt. The first thing it says ([summary], uncut), with the examples taken out (parentheses, quotes, "e.g." /
     * "such as" / "including" lists, dash asides, whatever follows a colon), as a "use when" phrase of at most [words]
     * words and [max] chars. A cut lands at a word boundary, backs up to the last comma or "and" / "for" / "with"… when it
     * would split a phrase, and never ends on "and" / "the" / "to". Pure and deterministic (no locale, no clock): the
     * same text gives the same bytes.
     */
    fun useWhen(description: String, words: Int = LINE_WORDS, max: Int = LINE_CHARS): String {
        val whole = summary(description, Int.MAX_VALUE)
        var s = whole.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s*\"[^\"]*\""), "").replace(Regex("\\s*\u201c[^\u201d]*\u201d"), "")
        s = s.replaceFirst(Regex("\\s+[-\u2013\u2014]\\s+.*?\\s+[-\u2013\u2014]\\s+"), " ")
        s = s.split(Regex("\\s+[-\u2013\u2014]\\s+|[:;]\\s|,?\\s+(?i:e\\.g\\.?|i\\.e\\.?|such as|for example|including)(?=\\s|$)"), limit = 2)[0]
        s = s.replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', ';', ':', '-', '\u2014', ' ')
        // a description that is all example keeps its words, without the brackets and quotes
        if (s.isEmpty()) s = whole.replace(Regex("[()\"\u201c\u201d]"), "").replace(Regex("\\s+"), " ").trim()
        s = s.replaceFirst(Regex("^(?i)(?:use when|use for|use|you)\\b:?\\s*"), "")
        var first = s.substringBefore(' ').lowercase()
        val how = first in DROP_LEAD   // "How Argus writes code…" → "use when Argus writes code…"
        if (how) { s = s.substringAfter(' ', ""); first = s.substringBefore(' ').lowercase() }
        fun low(t: String) = t.substringBefore(' ').let { w -> if (w.drop(1).any { it.isUpperCase() }) t else t.replaceFirstChar { it.lowercase() } }
        val lead: String; val rest: String
        when {
            s.isEmpty() -> { lead = ""; rest = "" }
            first == "to" -> { lead = "use when asked to"; rest = s.substringAfter(' ', "") }
            how -> { lead = "use when"; rest = s }
            first in ARTICLE || first.contains('-') -> { lead = "use when asked for"; rest = low(s) }
            first.length >= 6 && first.endsWith("ing") && first !in NOT_GERUND -> { lead = "use when"; rest = low(s) }
            else -> { lead = "use when asked to"; rest = low(s).let { t -> base(t.substringBefore(' ')) + t.drop(t.substringBefore(' ').length) } }
        }
        // "step by step", "one by one" stay whole, so a cut never leaves "step by" (each still counts as three words)
        val all = "$lead $rest".trim().replace(Regex("\\b(\\w+) by \\1\\b"), "$1\u0000by\u0000$1").split(' ').filter { it.isNotEmpty() }
        val minKeep = lead.split(' ').size + 2
        var n = all.size
        while (n > 1 && all.subList(0, n).sumOf { w -> 1 + w.count { it == '\u0000' } } > words) n--
        while (n > minKeep && all.subList(0, n).joinToString(" ").length > max) n--
        fun bare(i: Int) = all[i].lowercase().trimEnd(',', ';', ':')
        fun trim() { while (n > minKeep && (bare(n - 1) in DANGLING || all[n - 1].all { !it.isLetterOrDigit() })) n-- }
        if (n < all.size) {
            trim()
            // a cut that splits a phrase backs up to the last comma or joining word
            val natural = bare(n) in BOUNDARY || all[n - 1].endsWith(",")
            if (!natural) {
                val b = (n - 1 downTo minKeep).firstOrNull { bare(it) in BOUNDARY || all[it - 1].endsWith(",") }
                if (b != null) { n = b; trim() }
            }
        }
        val out = all.subList(0, n).joinToString(" ").replace('\u0000', ' ').trimEnd(',', ';', ':', '-', '\u2014', '.', ' ')
        // one very long "word" (a URL, a path) still can't take the line past [max]
        return if (out.length <= max) out else out.take(max).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }.trimEnd()
    }

    private fun rank(source: String) = when (source) { "learned" -> 0; "user" -> 1; else -> 2 }

    /** Switched-on skills, one per name, in shelf order. */
    fun entries(cands: List<SkillMatch.Cand>): List<Entry> = cands.asSequence().filter { it.on && it.name.isNotBlank() }
        // the same set in any order gives the same entries: the user's own copy wins a name clash, whatever the read order
        .sortedWith(compareBy<SkillMatch.Cand>({ rank(it.source) }, { it.name.lowercase() }, { if (it.name == it.name.lowercase()) 0 else 1 }, { it.name }, { it.description }))
        .distinctBy { it.name.lowercase() }
        .map { Entry(it.name, useWhen(it.description.ifBlank { it.name }), it.source, it.source == "learned" && it.body.contains("run_skill")) }
        .sortedWith(compareBy<Entry>({ rank(it.source) }, { it.name.lowercase() })).toList()

    /** Room kept at the end of the block for the overflow line. */
    private const val TAIL = 70

    /**
     * The prompt part ("" when no skill is on). Everything fits inside [budget]: the user's own skills get the room first,
     * then the bundled library; whatever doesn't fit is counted, by group, in one honest "+N more" line.
     */
    fun block(cands: List<SkillMatch.Cand>, budget: Int = BUDGET_CHARS): String {
        val all = entries(cands)
        if (all.isEmpty()) return ""
        val sb = StringBuilder(HEAD)
        var left = budget - HEAD.length - TAIL
        var ownCut = 0; var libCut = 0
        for (e in all) {
            val l = e.line
            if (l.length <= left) { sb.append(l); left -= l.length } else if (rank(e.source) < 2) ownCut++ else libCut++
        }
        if (ownCut > 0) sb.append("+${ownCut + libCut} more skills ($ownCut yours, $libCut bundled): find_skill lists them.\n")
        else if (libCut > 0) sb.append("+$libCut more bundled skills: find_skill lists them.\n")
        return sb.toString().trimEnd('\n')
    }

    /**
     * What Argus is holding: the shelf's own name for [name] when a switched-on skill (bundled, the user's, or learned)
     * by that name really exists, else null. A read is recorded from what exists, not from how the tool's result reads.
     */
    fun known(name: String, cands: List<SkillMatch.Cand>): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return cands.firstOrNull { it.on && it.name.equals(n, ignoreCase = true) }?.name
    }

    @Volatile private var memo: Pair<Int, String>? = null

    /** This phone's shelf, rebuilt only when the skill set changes (install, switch, learn, delete). */
    fun promptBlock(cands: List<SkillMatch.Cand> = SkillMatch.cached()): String = try {
        val sig = cands.map { "${it.name}\t${it.description}\t${it.on}\t${it.source}\t${it.body.contains("run_skill")}" }.sorted().joinToString("\n").hashCode()
        memo?.takeIf { it.first == sig }?.second ?: block(cands).also { memo = sig to it }
    } catch (_: Throwable) { "" }
}
