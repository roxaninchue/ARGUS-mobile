package com.blaze.assistant.agent

import com.blaze.assistant.BrandName

/**
 * Unreleased round after 0.52.3, "What Argus is holding": the current chat's rolling summary from LongChat, the active
 * GoalPin goal / constraints, and the skills loaded this turn; read-only. (Pinned facts were removed, owner 2026-11-15:
 * "get rid of useless features".)
 *
 * [Snapshot] is what Agent.holding() reads from the open chat; [sections] turns it into the page's groups. Opened from
 * the profile, /holding, or by name. Pure Kotlin, unit-tested in HoldingTest.
 */
object Holding {
    data class Snapshot(val summary: String, val goal: GoalPin.Goal?, val skills: List<String>) {
        companion object { val EMPTY = Snapshot("", null, emptyList()) }
    }

    /** One read-only group: a heading, its lines, and what it says when there are none. */
    data class Section(val title: String, val lines: List<String>, val empty: String)

    val TITLE = "What ${BrandName.NAME} is holding"
    val INTRO = "What ${BrandName.NAME} keeps in mind right now. It belongs to this chat and changes as you talk."

    /** The page's read-only groups, in order: this chat's summary, the current task, the skills of the last request. */
    fun sections(s: Snapshot): List<Section> = listOf(
        Section("This chat's summary", s.summary.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty(),
            "No summary yet: this chat is still short enough to keep word for word."),
        Section("Current task", s.goal?.takeIf { it.request.isNotBlank() }?.let { g ->
            listOf(g.request) + g.constraints.map { "Constraint: $it" } }.orEmpty(),
            "No task pinned right now."),
        Section("Skills loaded for the last request", s.skills.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            "No skills were loaded."),
    )
}
