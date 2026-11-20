package com.blaze.assistant.agent

/**
 * Skill shelf (20 Nov 2026, docs/SKILL-SHELF.md; MODEL-FIRST "Still to retire": SkillMatch picking skills from words).
 *
 * The model sees every skill that's switched on, one line each (name: what it's for), and decides itself which one to
 * read with use_skill or replay with run_skill. Nothing guesses before the model and nothing refuses after it.
 *
 *  - Order: the user's own first (learned, then written / installed), then the bundled library; alphabetical inside
 *    each group, so the text is byte-stable for the same set (it rides in the SLOW prompt tier and caches).
 *  - Each line comes from the skill's own description ([summary]); no second description to keep up to date.
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

    const val HEAD = "\n\nSKILLS on this phone (name: what it's for). When one fits what the user wants, read it with use_skill before you start; " +
        "one marked [replay] is the user's recorded phone steps and run_skill replays it. find_skill searches them.\n"

    data class Entry(val name: String, val summary: String, val source: String, val replay: Boolean) {
        val line: String get() = "- $name: $summary${if (replay) " [replay]" else ""}\n"
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

    private fun rank(source: String) = when (source) { "learned" -> 0; "user" -> 1; else -> 2 }

    /** Switched-on skills, one per name, in shelf order. */
    fun entries(cands: List<SkillMatch.Cand>): List<Entry> = cands.asSequence().filter { it.on && it.name.isNotBlank() }
        // the same set in any order gives the same entries: the user's own copy wins a name clash, whatever the read order
        .sortedWith(compareBy<SkillMatch.Cand>({ rank(it.source) }, { it.name.lowercase() }, { if (it.name == it.name.lowercase()) 0 else 1 }, { it.name }, { it.description }))
        .distinctBy { it.name.lowercase() }
        .map { Entry(it.name, summary(it.description.ifBlank { it.name }), it.source, it.source == "learned" && it.body.contains("run_skill")) }
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
