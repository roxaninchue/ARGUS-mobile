package com.blaze.assistant.team

import com.blaze.assistant.agent.Specialists

/**
 * P1-TM-01: the 10 built-in specialists (and the user's / Blaze-made custom ones) as WORKER templates (05 §2.5,
 * 06 R5): hidden bots, never on the roster, spawned for one job. `delegate` keeps working exactly as before: it still
 * reads [Specialists]; this only gives the team runtime a [Bot] for each.
 */
object Workers {
    fun of(s: Specialists.Spec, now: Long): Bot = Bots.worker(
        id = s.id, name = s.name, groups = s.groups, skills = s.skills, now = now, color = s.color,
        face = Bots.FACES.indexOfFirst { it.equals(s.avatar, true) }.takeIf { it >= 0 },
    ).let { b ->
        val tier = s.modelPref.lowercase().takeIf { it in setOf("spark", "flare", "inferno") }
        if (tier == null) b else b.copy(engine = b.engine.copy(model = b.engine.model.copy(tier = tier)))
    }

    /** Every template, built-ins first. */
    fun templates(now: Long = System.currentTimeMillis()): List<Bot> = Specialists.all().map { of(it, now) }

    fun template(id: String, now: Long = System.currentTimeMillis()): Bot? =
        Specialists.byId(id.removePrefix("worker:"))?.let { of(it, now) }
}
