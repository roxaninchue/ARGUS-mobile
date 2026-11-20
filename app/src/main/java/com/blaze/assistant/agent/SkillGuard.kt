package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.30.5 (Blazeinchue: "create higher measures stopping blaze from calling unrelated skills and also keep track of often
 * performed tasks so it can give priority to creating skills for them").
 *
 *  - 2026-11-20 skill shelf (docs/SKILL-SHELF.md): the relevance gate (`gate` / `relevant` / `strongHit`) that refused
 *    use_skill / run_skill by word overlap is gone. The model sees every skill (SkillShelf) and chooses; a tool no longer
 *    tells it a skill is "unrelated". Round 5: `unnamed` (the proper-name stripper for specialist matching) went with
 *    SpecialistMatch.
 *  - [Freq]: every real task (≥ 2 work tool calls) is counted by its fingerprint (Learn.signature). It counts as covered
 *    only when the model really used or saved a skill for it (use_skill / run_skill / save_workflow…), never by word
 *    overlap with a skill's description. A task done [Freq.PRIORITY] times with no skill covering it is flagged: the end-of-turn learning review runs for it with
 *    skill-writing first, and the prompt carries a one-line nudge listing such tasks.
 * Pure except [Freq]'s small store in Settings (FreqLogicTest / SkillGuardTest cover the logic).
 */
object SkillGuard {

    // ---------------- often-performed tasks ----------------

    object Freq {
        data class Task(val sig: List<String>, val count: Int, val last: Long, val example: String, val skilled: Boolean = false)

        /** A task this many times with no skill gets priority for one. */
        const val PRIORITY = 3
        const val SAME = 0.5
        const val KEEP = 60

        /** Adds [sig] (one real task) to [tasks]: merged into the most similar one, or a new row. Pure. */
        fun record(tasks: List<Task>, sig: List<String>, example: String, now: Long): Pair<List<Task>, Task> {
            val best = tasks.withIndex().maxByOrNull { Learn.similarity(sig, it.value.sig) }
            val out = tasks.toMutableList()
            val t = if (best != null && Learn.similarity(sig, best.value.sig) >= SAME) {
                val o = best.value
                val merged = o.copy(sig = (o.sig + sig).distinct().takeLast(16), count = o.count + 1, last = now, example = example.take(160))
                out[best.index] = merged; merged
            } else Task(sig, 1, now, example.take(160)).also { out += it }
            // Keep the most-done, then the most recent.
            val kept = out.sortedWith(compareByDescending<Task> { it.count }.thenByDescending { it.last }).take(KEEP)
            return kept to t
        }

        /** Done often, no skill yet. */
        fun needsSkill(t: Task) = t.count >= PRIORITY && !t.skilled

        fun markSkilled(tasks: List<Task>, sig: List<String>): List<Task> =
            tasks.map { if (Learn.similarity(sig, it.sig) >= SAME) it.copy(skilled = true) else it }

        fun toJson(tasks: List<Task>): String = JSONArray().apply {
            tasks.forEach { put(JSONObject().put("sig", JSONArray(it.sig)).put("n", it.count).put("last", it.last).put("ex", it.example).put("skilled", it.skilled)) }
        }.toString()

        fun fromJson(s: String): List<Task> = try {
            val a = JSONArray(s)
            (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                val sa = o.optJSONArray("sig") ?: JSONArray()
                Task((0 until sa.length()).map { sa.getString(it) }, o.optInt("n", 1), o.optLong("last"), o.optString("ex"), o.optBoolean("skilled"))
            } }
        } catch (_: Exception) { emptyList() }

        /** The prompt nudge for the top tasks still without a skill ("" when none). */
        fun promptLine(tasks: List<Task>): String {
            val top = tasks.filter { needsSkill(it) }.sortedByDescending { it.count }.take(3)
            if (top.isEmpty()) return ""
            return "\nFREQUENT TASKS WITHOUT A SKILL (save one with save_workflow when you next finish one of these; reuse it after): " +
                top.joinToString("; ") { "\u201c${it.example.take(70)}\u201d ×${it.count}" }
        }

        // store
        private const val KEY = "task_freq"
        fun load(): List<Task> = try { fromJson(Settings.str(KEY, "[]")) } catch (_: Throwable) { emptyList() }
        fun save(tasks: List<Task>) { try { Settings.setStr(KEY, toJson(tasks)) } catch (_: Throwable) { } }

        /** Records a finished task; true when it now deserves a skill and none covers it. */
        @Synchronized fun note(sig: List<String>, example: String, covered: Boolean): Boolean {
            if (sig.isEmpty()) return false
            val (all, t) = record(load(), sig, example, System.currentTimeMillis())
            val fixed = if (covered) markSkilled(all, t.sig) else all
            save(fixed)
            return !covered && needsSkill(t)
        }

        @Synchronized fun skillSaved(sig: List<String>) { if (sig.isNotEmpty()) save(markSkilled(load(), sig)) }
    }
}
