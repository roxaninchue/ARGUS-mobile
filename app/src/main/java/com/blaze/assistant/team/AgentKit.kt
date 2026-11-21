package com.blaze.assistant.team

import com.blaze.assistant.BrandName

/**
 * Owner 2026-11-20, "ARGUS doubles as an agent team app": ARGUS keeps a small kit in chat (quick browsing, memory, the
 * phone basics, the team) and is steered, never forced, towards making agents for work it sees coming back. It picks
 * each agent's tools, and an agent's tools are capped too ([MAX_GROUPS]). A terminal agent ([terminalSoul]) owns long
 * jobs (builds, installs, scripts) in the Linux sandbox. Teams are agents grouped under a lead ([teams]). Pure; AgentKitTest.
 */
object AgentKit {
    /** Settings key: the lean chat kit (default on). Off = the full harness core as before. */
    const val PREF_LEAN = "lean_chat"

    /** What ARGUS carries in the main chat every turn; the rest stays one load_tools / find_tool away. */
    val CHAT_CORE = setOf(
        // 2026-11-20 skill shelf: the SKILLS list rides every turn, so the tool that reads one does too (run_skill / find_skill load on use).
        "load_tools", "find_tool", "ask_user", "memory_search", "memory_save", "remember", "use_skill",
        "web_search", "fetch_page", "browser_open", "browser_read", "browser_click", "browser_type",
        "open_app", "read_screen", "device_status", "blaze_now", "open_view", "get_weather", "save_file", "make_document",
        "calendar_events", "set_alarm", "set_timer", "message_contact", "request_secret", "blaze_self",
        "agents_list", "agent_create", "agent_update", "add_job", "agent_delegate", "agent_status", "agent_watch",
        // owner 2026-11-20: ARGUS has the terminal too (sandboxed commands need no card; checkpoints are the safety net)
        "run_shell", "run_code", "file_read", "file_write", "file_edit", "file_list", "terminal_checkpoint")

    /** Most tool groups one agent may hold: a focused agent picks better from a short list. */
    const val MAX_GROUPS = 4

    /** Groups an agent always has on top of what ARGUS picks (team jobs, its own memory, and the terminal: owner 2026-11-20). */
    val BASE_GROUPS = setOf("memory", "jobs", "terminal")

    /**
     * The groups ARGUS may give an agent, with what each brings (shown in agent_create's description). Ids are the
     * ToolRouter / bot tool-group ids; "web" is the plain search-and-read kit.
     */
    val GROUPS: Map<String, String> = linkedMapOf(
        "web" to "search and read the web",
        "browser" to "the background browser: sign in, fill forms, click through sites",
        "schedule" to "calendar, routines, triggers",
        "messages" to "texts, calls, contacts, WhatsApp",
        "google" to "Gmail, Drive, Google contacts and Docs",
        "apps" to "connected apps: GitHub, Notion, Slack, Spotify…",
        "device" to "alarms, timers, volume, phone settings",
        "screen" to "tap and type on the phone's screen",
        "home" to "Home board widgets and Life to-dos",
        "study" to "flashcards and study tools",
        "media" to "music and video",
        "money" to "spending summaries",
        "design" to "3D, pages, posters, slides",
        "pc" to "the paired computer",
    )

    /** "web, terminal" / ["web","terminal"] → the valid groups in order, unknown ones dropped, at most [MAX_GROUPS]. */
    fun groups(raw: Any?): List<String> {
        val parts = when (raw) {
            null -> emptyList()
            is Collection<*> -> raw.mapNotNull { it?.toString() }
            else -> raw.toString().split(',', ' ', ';', '+')
        }
        return parts.map { it.trim().lowercase() }.filter { it in GROUPS }.distinct().take(MAX_GROUPS)
    }

    /** What an agent's set ends up as: its picked groups, plus [BASE_GROUPS], plus terminal for a terminal agent. */
    fun toolSet(picked: List<String>, terminal: Boolean): Set<String> {
        return BASE_GROUPS + picked.filter { it !in BASE_GROUPS }.take(MAX_GROUPS)
    }

    /** Groups asked for beyond [MAX_GROUPS] or unknown: told back to the model so it can choose again. */
    fun dropped(raw: Any?): List<String> {
        val parts = when (raw) { null -> emptyList(); is Collection<*> -> raw.mapNotNull { it?.toString() }; else -> raw.toString().split(',', ' ', ';', '+') }
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val kept = groups(raw)
        return parts.filter { it !in kept }
    }

    /** The instructions a terminal agent starts with (before any the model adds). */
    fun terminalSoul(): String =
        "You are a terminal agent. You own long jobs in ${BrandName.NAME}'s Linux sandbox: builds, installs, scripts, servers, data work. " +
        "Inside the terminal you act on your own: run what the job needs, read the output, fix and retry, without asking for each command. " +
        "Start anything that takes more than a minute in the background (run_shell background=true) and check on it, so the chat is never blocked. " +
        "Commands inside the sandbox run without asking the user; a checkpoint is taken before each change, so if you break something, roll back with terminal_checkpoint. " +
        "Whoever handed you the job can watch your steps live, so say what you're doing in a short line as you go. " +
        "Stay inside the terminal: no messages, purchases or phone actions, and leave the phone's shared storage alone unless the job names it. " +
        "Report back short: what ran, what it produced (paths), and what failed with the one line that matters."

    /** The steering line in ARGUS's system prompt: suggest, never force. */
    val STEER = """
AGENTS: you are also the lead of the user's team of agents. Your own kit in chat is small on purpose; the long tail of tools belongs to agents. When a request is the kind of work that will likely come back (a weekly report, watching prices, triage of a mailbox, builds and scripts, research on one subject), prefer making an agent for it with agent_create and the few tool groups it needs (at most $MAX_GROUPS), then hand it the job; a long or heavy terminal job (an Android build, installs, a data run) goes to a terminal agent (terminal: true) so the chat stays free. Every agent, you included, has the terminal; commands inside the sandbox run without asking the user, and a checkpoint is taken before each change (terminal_checkpoint rolls back). When you hand off terminal work, follow it with agent_watch (pass after=next each time) and step in with update_job if it goes wrong; tell the user what it is doing when they ask. Check agents_list first and reuse an agent that already fits. One-off questions you just answer yourself, and if a tool you need isn't loaded, load it. Tell the user in one line when you made an agent and why.
""".trim()

    /** One team: a lead and the agents that report to it (a room's members count too). */
    data class Team(val lead: Bot, val members: List<Bot>)

    /**
     * Agents grouped into teams: an agent another agent reports to leads a team of those agents (the Manager leads no
     * team here: reporting to ARGUS is the default); a room (kind ROOM) is a team of its members. Agents in no team are
     * [Grouped.solo]. Each agent shows once: a member of a lead's team is not also listed alone. Roster order kept.
     */
    data class Grouped(val teams: List<Team>, val solo: List<Bot>)

    fun teams(roster: List<Bot>): Grouped {
        val agents = roster.filter { !BotRules.isManager(it) && !it.hidden && it.enabled }
        val byId = agents.associateBy { it.id }
        val out = ArrayList<Team>(); val placed = HashSet<String>()
        for (r in agents.filter { it.kind == BotKind.ROOM }) {
            val m = r.members.mapNotNull { byId[it] }.filter { it.kind != BotKind.ROOM }
            out += Team(r, m); placed += r.id; placed += m.map { it.id }
        }
        for (lead in agents.filter { it.kind != BotKind.ROOM && it.id !in placed }) {
            val m = agents.filter { it.reportsTo == lead.id && it.id != lead.id && it.id !in placed && it.kind != BotKind.ROOM }
            if (m.isEmpty()) continue
            out += Team(lead, m); placed += lead.id; placed += m.map { it.id }
        }
        return Grouped(out, agents.filter { it.id !in placed })
    }
}
