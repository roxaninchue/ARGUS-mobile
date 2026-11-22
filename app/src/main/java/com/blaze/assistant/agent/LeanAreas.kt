package com.blaze.assistant.agent

/**
 * 0.55.5: the areas find_tools lists ([LeanTurn.areas]): every ToolRouter group but the catch-all "more", then every
 * ToolPick cue, so whatever the full tool list could send, the lean model knows exists and can fetch by area.
 */
object LeanAreas {
    fun all(): List<LeanTurn.Area> =
        ToolRouter.groups.filter { it.id != "more" }.map { g -> LeanTurn.Area(g.id, g.about, g.names) } +
            ToolPick.CUES.map { c -> LeanTurn.Area(c.id, c.about) { it in c.names } }
}
