package com.blaze.assistant.agent

import java.io.File

/**
 * The skills on this phone: reading them ([candidates] / [cached]: bundled, the user's own in ~/skills, and learned ones;
 * switched-off ones marked) and [find] for find_skill (SkillIndex, Desktop's bm25 ranking).
 *
 * History: v0.23.0 picked 1-3 skills per turn by a keyword score over name, description and keywords and pasted them into
 * the prompt. 2026-11-20 the skill shelf ([SkillShelf], docs/SKILL-SHELF.md) replaced the per-turn pick; round 5 removed
 * the word score itself. Nothing here reads the user's message.
 */
object SkillMatch {
    data class Cand(val name: String, val description: String, val keywords: List<String>, val body: String,
                    val source: String, val on: Boolean = true)

    // ---------------- reading skills on the phone ----------------

    private fun frontMatter(md: String): Map<String, String> {
        val m = Regex("""^\s*---\s*\n(.*?)\n---""", RegexOption.DOT_MATCHES_ALL).find(md) ?: return emptyMap()
        return m.groupValues[1].lines().mapNotNull { l ->
            val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim().trim('"', '\'')
        }.toMap()
    }

    fun kwList(v: String?): List<String> = v.orEmpty().trim().removePrefix("[").removeSuffix("]").split(',')
        .map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }

    fun body(md: String): String = md.replace(Regex("""^\s*---\s*\n.*?\n---\s*\n?""", RegexOption.DOT_MATCHES_ALL), "").trim()

    /** One skill folder (with SKILL.md) as a candidate. */
    fun fromDir(dir: File, source: String, on: Boolean): Cand? {
        val f = File(dir, "SKILL.md").takeIf { it.isFile } ?: return null
        val md = try { f.readText() } catch (_: Exception) { return null }
        val fm = frontMatter(md)
        return Cand(fm["name"] ?: dir.name, fm["description"].orEmpty(), kwList(fm["tags"]) + kwList(fm["keywords"]), body(md), source, on)
    }

    /** Every skill on the phone: bundled + user packs in ~/skills, and learned skills. Disabled ones marked off. */
    fun candidates(): List<Cand> {
        val out = ArrayList<Cand>()
        try {
            val bundled = SkillLibrary.bundled().map { it.name }.toSet()
            for (p in SkillPacks.all()) fromDir(p.dir, if (p.dir.name in bundled) "bundled" else "user", SkillLibrary.isOn(p.dir.name))?.let { out += it }
        } catch (_: Throwable) { }
        try {
            for (s in Learning.skills.value) out += Cand(s.name, s.description, listOf(s.whenToUse).filter { it.isNotBlank() },
                (s.description + "\n" + (if (s.whenToUse.isNotBlank()) "When: ${s.whenToUse}\n" else "") + s.steps +
                    (if (s.replay != null) "\n(run_skill ${s.name} replays the recorded phone steps)" else "")).trim(), "learned", true)
        } catch (_: Throwable) { }
        return out
    }

    /** The cached candidate list (re-read at most every 20 s, files can change under us). */
    @Volatile private var cache: Pair<Long, List<Cand>>? = null
    fun cached(): List<Cand> = try {
        val now = System.currentTimeMillis()
        cache?.takeIf { now - it.first < 20_000 }?.second ?: candidates().also { cache = now to it }
    } catch (_: Throwable) { emptyList() }

    fun invalidate() { cache = null; index = null }
    // 2026-11-20 skill shelf: forTurn / selectForTurn / lookupBlock / learnedOnly (the per-turn pickers) are gone; the model
    // sees every skill on SkillShelf and reads one itself. Round 5: the word score behind them (stem / words / score / match /
    // block / cardLine) went too: specialists bring their packs by name (Specialists.brief) and Freq trusts the actual calls.

    // ---------------- v0.26.2: skills by lookup (SkillIndex, Desktop's bm25 ranking) ----------------

    /** A candidate as an index row: name, description, when-to-use (keywords + the learned "When:" line). */
    fun doc(c: Cand): SkillIndex.Doc = SkillIndex.Doc(c.name, listOf(c.name, c.description,
        (c.keywords + listOfNotNull(Regex("(?m)^When: (.+)$").find(c.body)?.groupValues?.get(1))).joinToString(" ")),
        note = if (c.source == "learned" && c.body.contains("run_skill")) " [replayable with run_skill]" else "")

    /** The index for these candidates (enabled ones only; VisualRoute's skips applied by the caller). Pure. */
    fun indexOf(cands: List<Cand>): SkillIndex.Index = SkillIndex.skills(cands.filter { it.on }.distinctBy { it.name.lowercase() }.map { doc(it) })

    /** Rebuilt whenever the skill set changes: keyed on a signature of the candidates (install, toggle, learn, delete). */
    @Volatile private var index: Pair<Int, SkillIndex.Index>? = null
    fun index(cands: List<Cand> = cached()): SkillIndex.Index {
        val sig = cands.joinToString("\n") { "${it.name}\t${it.description}\t${it.on}\t${it.keywords}" }.hashCode()
        index?.takeIf { it.first == sig }?.let { return it.second }
        return indexOf(cands).also { index = sig to it }
    }

    /** find_skill: search every enabled skill. */
    fun find(query: String): String = try { SkillIndex.findLine(index(), query) } catch (e: Throwable) { "Error: ${e.message}" }
}
