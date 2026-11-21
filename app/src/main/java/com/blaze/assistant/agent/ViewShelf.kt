package com.blaze.assistant.agent

import com.blaze.assistant.BrandName

/**
 * View shelf (21 Nov 2026, docs/VIEW-SHELF.md; MODEL-FIRST "Still to retire": `ViewRouter.route` picking a view from the
 * message's words).
 *
 * The model sees every view on every turn, one line each (`id — what it holds — the tool that writes there`), and decides
 * itself whether a request belongs in one. Nothing reads the message to pick a view, and no "REQUIRED VIEW THIS TURN"
 * order is injected. What does change per turn is a fact, not a guess: when a design is current (made in the last turn,
 * open in Design, or touched in the last 30 minutes), [currentDesign] says which one it is, so the model can change it
 * instead of starting a new one.
 *
 *  - [specs]: the views ARGUS writes to. Also read by [ClaimGuard] (does a reply's "I've updated the design" have a
 *    succeeded tool call behind it?) and by the agent loop (a claim nudge switches on that view's tool [Spec.groups]).
 *  - [promptBlock]: byte-stable for the same Stage setting, so it rides in the cached STATIC prefix.
 *  - [currentDesign]: one line for the turn tier, only when the agent loop's structural check says a design is current.
 *
 * Pure Kotlin (no Android), unit-tested in ViewShelfTest.
 */
object ViewShelf {

    /** The whole shelf stays under this (≈ 350 tokens; ViewShelfTest). The old full VIEWS block was 3,890 chars, plus a per-turn order of up to 743. */
    const val BUDGET_CHARS = 1_600

    /**
     * A view the agent writes to. [holds] is what belongs there, written as "use when" text for the shelf line; [tools]
     * are the tools that put things there (the first one is named on the shelf); [groups] are ToolRouter groups switched
     * on when the model claims work in this view without having done it (ClaimGuard's nudge).
     */
    data class Spec(val id: String, val name: String, val holds: String, val tools: List<String>, val groups: List<String>)

    /** The shelf's order. Stage first: when it's on, live things go on the chat before any screen opens. */
    val specs = listOf(
        Spec("stage", "Stage", "live cards on the chat itself: a timer or pomodoro, music, a video, a quiz or flashcards to go through, a plan, the browser",
            listOf("stage_show", "stage_update"), listOf("stage", "media")),
        Spec("learn", "Learn", "flashcard decks and quizzes to study, with spaced repetition",
            listOf("flashcards", "open_view"), listOf("study")),
        Spec("design", "Design", "visual work to keep, change or export, with a live preview: 3D models, 2D art, pages and sites, UI mockups, posters, slides, floor plans, games",
            listOf(Designs.TOOL, "file_write", "open_view"), listOf("terminal", "media")),
        Spec("schedule", "Schedule", "reminders, routines, repeating or timed jobs and triggers",
            listOf("create_routine", "schedule_command", "create_trigger", "set_alarm", "add_calendar_event", "open_view"), listOf("schedule", "device")),
        Spec("gallery", "Gallery", "images, logos, wallpapers, beats and other media ${BrandName.NAME} makes",
            listOf("generate_image", "gallery"), listOf("media")),
        Spec("memory", "Memory", "lasting facts about the user, their people, preferences and decisions",
            listOf("memory_save", "remember", "forget", "memory_search"), emptyList()),
        Spec("skills", "Skills", "saved methods and workflows to reuse, and skill packs",
            listOf("save_workflow", "write_skill", "install_skill", "use_skill"), listOf("extras")),
        Spec("projects", "Projects", "ongoing efforts with their own threads, brief and instructions",
            listOf("update_brief", "open_view"), emptyList()),
        Spec("browser", "Browser", "websites ${BrandName.NAME} opens, fills in or signs in to, in background tabs",
            listOf("browser_open", "browser_click", "browser_type", "browser_secure_input"), listOf("browser")),
        Spec("archive", "Archive", "past conversations; there is no screen, so answer in chat from what search_chat finds",
            listOf("search_chat", "search_past_chats"), emptyList()),
    )

    fun byId(id: String): Spec? = specs.firstOrNull { it.id == id }

    /** The tool named on a view's shelf line: the first one that writes there (open_view only opens). */
    fun writer(s: Spec): String = s.tools.firstOrNull { it != "open_view" } ?: "open_view"

    const val HEAD = "\n\nVIEWS in ${BrandName.UPPER} (id \u2014 what it holds \u2014 the tool that writes there). Work that belongs in a view is made there " +
        "with its tool, and the reply says in a line or two what you made and where, so the user can open it; open_view opens any view by id. " +
        "Plain answers, explanations and a quick diagram or chart in the reply stay in chat.\n"

    const val TAIL = "Other screens (Jobs, Accounts, Agents, Usage, Settings and more) only show things; open_view opens them too.\n"

    /** One shelf line. */
    fun line(s: Spec): String = "- ${s.id} \u2014 ${s.holds} \u2014 ${writer(s)}\n"

    /**
     * The shelf, every turn. [stageOn] = Settings' Stage switch: with the Stage off its line is left out, since stage_show
     * would only fail. Byte-stable for the same switch (PromptCacheBenchTest keeps it in the cached prefix).
     */
    fun promptBlock(stageOn: Boolean): String = buildString {
        append(HEAD)
        for (s in specs) if (s.id != "stage" || stageOn) append(line(s))
        append(TAIL)
    }

    /**
     * The fact line for a turn while a design is current. [why] says how the agent loop knows (the last turn made it, it's
     * open in Design, or it was changed a few minutes ago). The model decides whether the message is about it.
     */
    fun currentDesign(title: String, kind: String, path: String, why: String): String =
        "\nCURRENT DESIGN: \u201c$title\u201d ($kind, $path), $why. If the user is talking about it, change it with design action=update " +
            (if (kind == "3d") "(set_parts for parts to add or change by id, remove_parts for ids to drop; action=read shows the part ids) "
            else "(edits=[{find, replace}] or a new html; action=read shows it) ") +
            "and keep the rest as it is; design action=open shows it, and design action=create is for something new."

    /** Why a design counts as current, in the words [currentDesign] uses. */
    fun why(lastTurn: Boolean, showing: Boolean): String = when {
        lastTurn -> "made or changed in your last turn"
        showing -> "open in Design right now"
        else -> "changed in the last 30 minutes"
    }
}
