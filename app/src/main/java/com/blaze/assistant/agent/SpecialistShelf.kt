package com.blaze.assistant.agent

/**
 * Specialist shelf (round 5, 20 Nov 2026; docs/SKILL-SHELF.md §12, MODEL-FIRST "Still to retire": SpecialistMatch).
 *
 * The model sees every specialist, one line each (id, name: what it does), the built-ins first in their fixed order and
 * then the user's own (custom, marked "yours") by id, and decides itself whether to hand work to one with `delegate`.
 * Nothing reads the user's message: SpecialistMatch, which picked specialists (and their skill packs) from the words, is
 * gone.
 *
 * Step 2 (shelf cost): the list lives in delegate's own description, with the ids as an enum on its `specialist`
 * parameter, and delegate rides every turn (ToolPick.ESSENTIAL); the system prompt keeps a one-line [pointer].
 *
 *  - Each line comes from the specialist's own prompt ([SkillShelf.summary] of it), so there's no second description
 *    to keep up to date.
 *  - Byte-stable for the same set (deterministic order, no clock): the pointer rides in the SLOW prompt tier and the
 *    tool description in the tool list, and both cache.
 *
 * Pure apart from [promptBlock], which reads [Specialists.all] (SpecialistShelfTest).
 */
object SpecialistShelf {
    /** Heads the list inside delegate's description. */
    const val LIST_HEAD = "Specialists (id (name): what it does; \"yours\" = made by the user):\n"

    data class Entry(val id: String, val name: String, val summary: String, val yours: Boolean) {
        val line: String get() = "- $id ($name): $summary${if (yours) " (yours)" else ""}\n"
    }

    /** Built-ins in their fixed order, then the user's own by id; one per id. The same set in any order gives the same entries. */
    fun entries(specs: List<Specialists.Spec>): List<Entry> {
        val order = Specialists.builtIn.map { it.id }
        val (own, built) = specs.filter { it.id.isNotBlank() }
            .sortedWith(compareBy<Specialists.Spec>({ it.custom }, { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.id.lowercase() }, { it.id }))
            .distinctBy { it.id.lowercase() }.partition { it.custom }
        return (built + own).map {
            Entry(it.id, it.name, SkillShelf.summary(it.prompt.ifBlank { it.name }), it.custom)
        }
    }

    /** The list as it reads in delegate's description (trailing newline trimmed). */
    fun lines(entries: List<Entry>): String = entries.joinToString("") { it.line }.trimEnd('\n')

    /**
     * The system prompt's one line (round 5 step 2): how many specialists there are and where they're listed. The list
     * itself is in delegate's description, which rides every turn ("" when there are none).
     */
    fun pointer(specs: List<Specialists.Spec>): String {
        val e = entries(specs)
        if (e.isEmpty()) return ""
        val yours = e.count { it.yours }
        return "\n\nSPECIALISTS: ${e.size} (${e.size - yours} built in, $yours yours), each listed with what it does in the delegate tool, " +
            "which hands one a task. create_specialist makes a new one when none covers the field."
    }

    @Volatile private var memo: Pair<Int, String>? = null

    /** This phone's pointer line, rebuilt only when the set changes (one made in Settings or by create_specialist). */
    fun promptBlock(specs: List<Specialists.Spec> = try { Specialists.all() } catch (_: Throwable) { Specialists.builtIn }): String = try {
        val sig = specs.map { "${it.id}\t${it.name}\t${it.prompt}\t${it.custom}" }.sorted().joinToString("\n").hashCode()
        memo?.takeIf { it.first == sig }?.second ?: pointer(specs).also { memo = sig to it }
    } catch (_: Throwable) { "" }
}
