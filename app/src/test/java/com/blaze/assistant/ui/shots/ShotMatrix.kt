package com.blaze.assistant.ui.shots

import com.blaze.assistant.ui.argus.Ground

/**
 * P1-QA-01 the shots matrix (06 §9 "Robolectric / Compose shots"): every new P1 screen in light and dark × the three
 * grounds (Plain / Orbit / Cobalt night) × font 100 % and 200 % = 12 cells per screen. Pure, so the matrix, the file
 * names and the screen list are checked on the JVM (ShotMatrixTest) even where Robolectric can't run.
 *
 * Files: `build/shots/P1ShotsTest/<screen>/<cell>.png`, cell = `<light|dark>-<plain|orbit|cobalt>-<100|200>`, so one
 * screen's 12 pictures sit side by side for the owner's review and a diff between rounds is per file.
 */
object ShotMatrix {
    /** The fonts the matrix shoots, in percent (Android's largest system font is 200 % from Android 14). */
    val FONTS = listOf(100, 200)

    /** One cell of the matrix. */
    data class Cell(val dark: Boolean, val ground: Ground, val font: Int) {
        val fontScale: Float get() = font / 100f
        val name: String get() = "${if (dark) "dark" else "light"}-${groundKey(ground)}-$font"
    }

    /** Short ground names for files (COBALT_NIGHT → cobalt). */
    fun groundKey(g: Ground): String = when (g) {
        Ground.PLAIN -> "plain"
        Ground.ORBIT -> "orbit"
        Ground.COBALT_NIGHT -> "cobalt"
    }

    /** All 12 cells: light before dark, then Plain / Orbit / Cobalt night, then 100 / 200 %. */
    val CELLS: List<Cell> = listOf(false, true).flatMap { dark -> Ground.entries.flatMap { g -> FONTS.map { f -> Cell(dark, g, f) } } }

    fun cell(name: String): Cell = CELLS.firstOrNull { it.name == name } ?: throw IllegalArgumentException("no shot cell $name")

    /** ParameterizedRobolectricTestRunner's parameters: one array per cell, holding the cell's name (stable test names). */
    fun params(): List<Array<Any>> = CELLS.map { arrayOf<Any>(it.name) }

    /** Where one picture goes, relative to the shots folder. */
    fun file(screen: String, cell: Cell): String = "P1ShotsTest/$screen/${cell.name}.png"

    /** A screen in the matrix: [key] (the folder), the P1 ticket that built it, and what the picture shows. */
    data class Screen(val key: String, val ticket: String, val what: String, val sheet: Boolean = false)

    /** Every new P1 screen. [Screen.sheet] = drawn in its own window (ModalBottomSheet), so the shot stacks the windows. */
    val SCREENS: List<Screen> = listOf(
        Screen("shell", "P1-SH-01", "Top bar (me · notch · search · bell• · +), the notch pill open on Team, Team chips, composer"),
        Screen("team", "P1-TM-03", "Team mode: header line, pins, ARGUS row, bots by recency with unread / overdue, Unassigned, All jobs"),
        Screen("team-empty", "P1-TM-03", "Team mode with only the Manager: Build your team + Demo team"),
        Screen("thread", "P1-SH-03", "A bot thread: intro, jobs card, a finished turn's work chip, handoff card, a live turn's activity line"),
        Screen("chat", "P1-SH-04", "Chat mode empty: Manager sphere, serif greeting, suggested asks"),
        Screen("chat-drawer", "P1-SH-04", "Chat mode history drawer: search, Pinned / Today / Yesterday / Older, unseen and replying dots, project chat"),
        Screen("life", "P1-SH-05", "Life mode: day line, Today strip with now mark and all-day chip, life tiles, projects, weather, answer"),
        Screen("settings-you", "P1-SH-06", "Settings You page: name, plan, the three groups, search pill"),
        Screen("settings-appearance", "P1-SH-06", "Appearance: ARGUS look, Theme, Ground, Handedness, Reduced motion"),
        Screen("new-bot", "P1-TM-04", "New bot sheet: Blank / From a template / Describe it", sheet = true),
        Screen("bot-editor", "P1-TM-04", "Bot editor sheet on an existing bot: orb, fields, look, reports-to", sheet = true),
        Screen("plan", "P1-TM-06", "Here's what I'll do: three actions and a miss, Apply 3, the preview switch", sheet = true),
        Screen("desk", "P1-TM-07", "Desk: title, segment chips with counts, the Routines link page"),
        Screen("jobs", "P1-TM-07", "Desk → Jobs on Needs you: search, filter chips with counts, rows with tags and due words, FAB"),
        Screen("job-sheet", "P1-TM-07", "Job sheet of a failed job: status, brief, log with times, Retry", sheet = true),
        Screen("new-job", "P1-TM-07", "New job sheet: title, due words, bot chips, priority", sheet = true),
        Screen("backup-import", "P1-MG-01", "Settings › Backup: Import A-team backup previewing the Demo team (bots, jobs waiting for an OK, Gemini note, name, theme switch)"),
    )

    /** P1 screens not in the matrix yet, and why (none: P1-MG-01's Backup section joined as `backup-import`). */
    val PENDING: Map<String, String> = emptyMap()

    /** The P1 tickets with screens (SH-02 is the kit, shot on its own in KitShotsTest; TM-01 / TM-02 / TM-05 have no screen). */
    val SCREEN_TICKETS = listOf("P1-SH-01", "P1-SH-03", "P1-SH-04", "P1-SH-05", "P1-SH-06", "P1-TM-03", "P1-TM-04", "P1-TM-06", "P1-TM-07", "P1-MG-01")

    /** How many pictures one full run makes. */
    val TOTAL: Int get() = SCREENS.size * CELLS.size
}
