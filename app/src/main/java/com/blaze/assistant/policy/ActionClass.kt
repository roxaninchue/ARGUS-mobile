package com.blaze.assistant.policy

import com.blaze.assistant.agent.CommandPolicy
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.ActionClass.COMPUTE_PAID
import com.blaze.assistant.team.ActionClass.CREDENTIAL
import com.blaze.assistant.team.ActionClass.DELETE
import com.blaze.assistant.team.ActionClass.PHYSICAL
import com.blaze.assistant.team.ActionClass.READ
import com.blaze.assistant.team.ActionClass.SEND
import com.blaze.assistant.team.ActionClass.SETTINGS
import com.blaze.assistant.team.ActionClass.SPEND
import com.blaze.assistant.team.ActionClass.WRITE_LOCAL
import com.blaze.assistant.team.ActionClass.WRITE_REMOTE

/**
 * P2-SF-01: the ToolRegistry declaration of every tool's [ActionClass] (05 §6.1, §7.1). Pure Kotlin (no android.*,
 * no org.json), so [Policy] and its tests run on the JVM.
 *
 *  - [TABLE]: every tool the app registers (Agent.tools(): ToolRouter groups, core, CommandRegistry's blaze_command,
 *    connectors, terminal, phone, browser…) with its base class. ActionClassCoverageTest scans the sources and fails
 *    when a registered tool is missing here (or this table names a tool that no longer exists).
 *  - [of]: the class of one call. Some tools depend on their arguments: an `action` that only reads ([READ_ACTIONS])
 *    or deletes, shell / git through [CommandPolicy] (SAFE → WRITE_LOCAL, ASK → WRITE_REMOTE, RISKY → DELETE), a
 *    browser click whose label pays / sends / deletes, an HTTP GET, a `/command`, and free-text tasks / routines
 *    checked with the A-team RISKY regex ([risky]: money → SPEND, delete → DELETE, send → SEND). A class is only
 *    ever raised by arguments above [TABLE]'s base, except for tools whose base is an "action router" ([BY_ACTION]).
 *  - MCP tools (`mcp__server__tool`) are classed from the server's own hints ([McpInfo]): readOnly → READ,
 *    destructive → DELETE, else WRITE_REMOTE.
 *  - Unknown tools (and MCP tools without hints) get [UNKNOWN] = DELETE: always a card, never pre-approved.
 */
object ActionClasses {

    /** The class an unknown tool gets: the strictest sensible one (always asks, can't be pre-approved). */
    val UNKNOWN: ActionClass = DELETE

    /** MCP hints for one tool (Mcp.kt / McpConfig: readOnlyHint, destructiveHint, the user's Ask/Always/Off mode). */
    data class McpInfo(val mode: String? = null, val readOnly: Boolean = false, val destructive: Boolean = false)

    /** One classified call: the class, the class before arguments raised it, and why (for cards and the audit log). */
    data class Classified(val cls: ActionClass, val base: ActionClass, val why: String = "", val commandKey: String? = null,
                          val commandLevel: CommandPolicy.Level? = null)

    /** Base class per tool (05 §7.1). Keep sorted by area; one line per tool or tool family. */
    val TABLE: Map<String, ActionClass> = buildMap {
        fun c(cls: ActionClass, vararg names: String) = names.forEach { put(it, cls) }
        // --- reading, looking, searching (Modes.planTools are all here) ---
        c(READ, "about_blaze", "blaze_now", "blaze_docs_search", "blaze_docs_read", "project_docs_search", "project_docs_read",
            "web_search", "fetch_page", "blaze_research", "find_tool", "load_tools", "find_skill", "use_skill", "skill_file",
            "search_past_chats", "search_chat", "read_turn", "read_artifact", "cache_toc", "cache_read", "cache_search", "cache_summarize",
            "recall_memory", "memory_search", "search_screen_memory", "device_status", "battery_level", "list_apps", "app_usage",
            "read_screen", "look_at_screen", "wait", "self_test", "get_location", "get_weather", "ask_user", "open_view",
            "recent_notifications", "notif_conversations", "notif_read", "read_messages", "recent_calls", "find_contact",
            "calendar_events", "gcal_events", "gmail_search", "gmail_read", "drive_search", "gcontacts_search",
            "classroom_courses", "classroom_coursework", "github_issues", "github_my_prs", "github_my_repos", "github_notifications",
            "github_search_repos", "github_ci", "notion_search", "slack_read", "slack_channels", "spotify_now_playing",
            "file_read", "file_list", "file_search", "terminal_status", "terminal_jobs", "processes", "list_routines", "list_triggers",
            "list_agents", "check_agent", "agent_progress", "home_widget_list", "store_search", "store_info", "store_status",
            "browser_open", "browser_read", "browser_scroll", "browser_back", "browser_tabs", "browser_find", "browser_wait", "browser_show",
            "phone_open", "phone_read", "phone_screenshot", "phone_wait", "phone_status", "money", "camera_math", "phone_health",
            // P5-TM-01: ending a turn without a message changes nothing
            "stay_silent")
        // --- the user's own data on the phone / in Argus (undoable or local) ---
        c(WRITE_LOCAL, "save_file", "remember", "memory_save", "memory_update", "memory_merge", "update_brief", "goal", "flashcards",
            "design", "generate_image", "gallery", "gallery_ops", "cover_art", "beat_lab", "clip_studio", "video_ops", "music_ops", "movies",
            "media_download", "read_aloud", "time_capsule", "duo_workspace", "life_logs", "call_copilot", "brand_kit",
            "stage_show", "stage_update", "stage_move", "stage_close", "stage_layout",
            "home_widget_add", "home_widget_update", "home_widget_remove", "set_alarm", "set_timer", "set_volume", "media_control",
            "create_routine", "schedule_command", "create_trigger", "classroom_sync", "process", "clipboard", "blaze_camera",
            "post_notification", "compose_sms", "request_secret", "phone_handoff", "store_open",
            "start_agent", "spawn_agent", "delegate", "orchestrate", "message_agent", "cancel_agent", "create_specialist", "post_update",
            // P5-TM-01: a message to a teammate bot; the receiver runs under its own Policy with trigger BOT (05 §7.2)
            "send_to_bot",
            // P1-TM-05 job board (team.db, local, undoable): classified here so ActionClassCoverageTest stays green after the P1/P2 merge
            "add_job", "done_job", "update_job", "retry_job", "agent_delegate", "create_task", "update_task",
            "skill_manage", "write_skill", "save_workflow", "run_skill", "watch",
            "file_write", "file_edit", "export_file", "download_file", "run_code", "job_input", "preview_server", "terminal_stop",
            "blaze_command")
        // --- other apps / services where the change stays the user's own (allowed when only the user is affected) ---
        c(WRITE_REMOTE, "add_calendar_event", "gcal_create", "move_event", "add_contact", "edit_contact",
            "github_create_issue", "github_create_repo", "notion_add_page", "spotify_play", "spotify_control",
            "browser_click", "browser_type", "browser_select", "browser_key", "phone_act", "http_request", "ask_pc",
            "run_shell", "termux_run", "git", "group_brain")
        // --- speaking for the user ---
        c(SEND, "send_sms", "send_text", "message_contact", "gmail_send", "slack_post", "notif_reply", "reply_to_notification",
            "share_text", "share_to_app", "call_contact", "call_number", "safe_walk")
        // --- money ---
        c(SPEND, "run_ussd")
        // --- deleting ---
        c(DELETE, "forget", "memory_delete", "file_delete", "delete_event", "delete_routine", "delete_trigger", "uninstall_app")
        // --- phone / Argus settings, installs ---
        c(SETTINGS, "device_setting", "system_action", "phone_mode", "open_settings_page", "bank_mode", "jarvis_mode", "follow_ups",
            "halo_persona", "blaze_self", "blazerouter", "standing_permission", "update_blaze", "store_install", "store_update",
            "github_install_apk", "linux_install", "install_skill", "skill_market", "browser_vpn")
        // --- secrets ---
        c(CREDENTIAL, "browser_secure_input", "jamendo_setup")
        // --- screen taps and opening things in other apps ---
        c(PHYSICAL, "tap", "type_text", "scroll", "press_button", "do_actions", "run_plan", "phone_task", "open_app", "open_url",
            "open_camera", "open_settings", "search_in_app", "navigate_to", "dial", "call_control")
    }

    /** Tools whose class is decided by their `action` argument ([actionClass]); the table value is the fallback. */
    val BY_ACTION = setOf("gallery", "gallery_ops", "flashcards", "process", "clipboard", "life_logs", "goal", "brand_kit", "time_capsule",
        "duo_workspace", "skill_manage", "blaze_self", "bank_mode", "jarvis_mode", "follow_ups", "call_control", "safe_walk", "music_ops",
        "video_ops", "movies", "skill_market", "blazerouter", "halo_persona", "group_brain", "phone_health", "read_aloud", "call_copilot",
        "stage_update", "home_widget_update", "beat_lab", "clip_studio", "cover_art", "watch", "spotify_control", "generate_image")

    /** WRITE_REMOTE tools that only ever touch the user's own account (05 §7.1 "allow if only the user is affected"). */
    val SELF_ONLY = setOf("spotify_play", "spotify_control", "move_event")

    /** Actions that only read (status, list…) for [BY_ACTION] tools. */
    val READ_ACTIONS = setOf("list", "get", "get_image", "history", "status", "show", "details", "due", "search", "info", "now", "read",
        "view", "preview", "check", "setup_check", "crash_log", "speed_stats", "tool_stats", "token_ledger", "find", "browse", "review",
        "on_this_day", "backup_plan", "clean_up", "wrapped", "recap", "exposure", "handheld", "dof", "nd", "summary", "summarize")
    private val deleteAction = Regex("""^(delete\w*|remove\w*|trash|wipe|erase|clear\w*|uninstall|forget|purge|drop)$""")
    private val sendAction = Regex("""^(share|send\w*|reply|post|publish|invite)$""")

    /** `/command`s Blaze may run itself (CommandRegistry.ACTIONS) that change settings; the rest only open a page or prompt. */
    val COMMANDS: Map<String, ActionClass> = buildMap {
        listOf("plan", "build", "think", "voice", "orb", "simple", "advanced", "bank", "persona", "agent", "pin").forEach { put(it, SETTINGS) }
        listOf("remember", "logs", "brainstorm", "holding").forEach { put(it, WRITE_LOCAL) }
        put("forget", DELETE)
        listOf("views", "settings", "routines", "memory", "usage", "agents").forEach { put(it, READ) }
    }

    /**
     * A-team RISKY (a-team app/www/manager.js `RISKY`, MIT): what in a free-text task or routine needs the user's yes,
     * whoever starts it. Keys: money, send, delete.
     */
    val RISKY: Map<String, Regex> = linkedMapOf(
        "money" to Regex("""\b(buy|purchase|pay|payment|order|checkout|subscribe|subscription|donate|transfer|send money|book (a|the)? ?(flight|hotel|ticket|table|room)|top ?up|withdraw|invest|bet)\b""", RegexOption.IGNORE_CASE),
        "send" to Regex("""\b(send|email|e-mail|text|sms|whatsapp|message|dm|tweet|post|reply to|forward|publish|invite)\b""", RegexOption.IGNORE_CASE),
        "delete" to Regex("""\b(delete|remove|erase|wipe|drop|cancel|unsubscribe|uninstall|rm -rf|destroy)\b""", RegexOption.IGNORE_CASE),
    )

    /** A-team `risky(text)`: which RISKY kinds the text matches (empty = none). */
    fun risky(text: String): List<String> = RISKY.filter { (_, re) -> re.containsMatchIn(text) }.keys.toList()

    /** A-team browser.js RISKY: a click label that pays, books, sends or deletes on a web page. */
    private val browserMoney = Regex("""\b(pay|pay now|buy|buy now|purchase|place (your )?order|order now|checkout|check out|confirm (and )?(pay|order|booking|purchase)|complete (purchase|order|booking)|book( now)?|reserve|submit (order|payment)|send money|transfer|subscribe|donate)\b""", RegexOption.IGNORE_CASE)
    private val browserDelete = Regex("""\b(delete|remove account|close account|unsubscribe all)\b""", RegexOption.IGNORE_CASE)
    private val browserSend = Regex("""\b(send( now)?|post|publish|reply)\b""", RegexOption.IGNORE_CASE)

    /** Tools whose free text starts work later or elsewhere (tasks, routines, triggers, agents): checked with [risky]. */
    val FREE_TEXT = setOf("start_agent", "spawn_agent", "delegate", "orchestrate", "message_agent", "create_routine", "schedule_command",
        "create_trigger", "ask_pc", "phone_task", "create_specialist", "watch")

    /** Severity used to merge classes (raise only): reading < local < taps < remote < settings < paid compute < secrets < send < delete < spend. */
    private val severity = listOf(READ, WRITE_LOCAL, PHYSICAL, WRITE_REMOTE, SETTINGS, COMPUTE_PAID, CREDENTIAL, SEND, DELETE, SPEND)
    fun severity(c: ActionClass): Int = severity.indexOf(c)
    fun max(a: ActionClass, b: ActionClass): ActionClass = if (severity(b) > severity(a)) b else a

    fun isMcp(tool: String) = tool.startsWith("mcp__") || tool.startsWith("mcp_")

    /** True when the table declares [tool] (MCP tools are declared by their server). */
    fun declared(tool: String): Boolean = tool in TABLE

    /** The base class of [tool] ([UNKNOWN] when nobody declared it). */
    fun base(tool: String, mcp: McpInfo? = null): ActionClass = when {
        isMcp(tool) -> when { mcp == null -> UNKNOWN; mcp.destructive -> DELETE; mcp.readOnly -> READ; else -> WRITE_REMOTE }
        else -> TABLE[tool] ?: UNKNOWN
    }

    /** The class of one call. */
    fun of(tool: String, args: Map<String, String> = emptyMap(), mcp: McpInfo? = null): ActionClass = classify(tool, args, mcp).cls

    /** [of] with the reasons (used by [Policy.decide]). */
    fun classify(tool: String, args: Map<String, String> = emptyMap(), mcp: McpInfo? = null): Classified {
        val base = base(tool, mcp)
        var cls = base
        var why = ""
        var key: String? = null
        var level: CommandPolicy.Level? = null
        if (tool in BY_ACTION) actionClass(tool, args)?.let { cls = it; why = "${tool} ${args["action"].orEmpty()}".trim() }
        when (tool) {
            "run_shell", "termux_run", "schedule_command" -> {
                val cmd = args["command"] ?: args["cmd"] ?: ""
                if (cmd.isNotBlank()) {
                    level = CommandPolicy.shell(cmd); key = CommandPolicy.key(CommandPolicy.Kind.SHELL, cmd)
                    val c = commandClass(level)
                    // schedule_command stores the command for later: never lower than its own base.
                    cls = if (tool == "schedule_command") max(base, c) else c
                    why = "command: ${level.name.lowercase()}"
                }
            }
            "git" -> {
                val act = args["action"].orEmpty().lowercase()
                val text = if (act.isBlank() || act == "raw") args["args"] ?: args["command"] ?: "" else act + " " + (args["args"] ?: "")
                level = if (act == "raw" && Regex("""(?i)(--force|reset\s+--hard|push\s+--delete|branch\s+-D|clean\s+-[a-z]*f)""").containsMatchIn(text))
                    CommandPolicy.Level.RISKY else CommandPolicy.level(CommandPolicy.Kind.GIT, text)
                key = CommandPolicy.key(CommandPolicy.Kind.GIT, text)
                val sub = CommandPolicy.words(text).firstOrNull { !it.startsWith("-") }.orEmpty()
                cls = if (level == CommandPolicy.Level.SAFE && sub in GIT_READ) READ else commandClass(level)
                why = "git: ${level.name.lowercase()}"
            }
            "http_request" -> {
                val m = (args["method"] ?: "GET").uppercase()
                if (m == "GET" || m == "HEAD" || m == "OPTIONS") { cls = READ; why = "http $m" }
            }
            "browser_click", "phone_act", "browser_key" -> {
                val label = listOf(args["target"], args["text"], args["label"], args["selector"], args["name"]).filterNotNull().joinToString(" ")
                when {
                    browserMoney.containsMatchIn(label) -> { cls = SPEND; why = "the button pays or books" }
                    browserDelete.containsMatchIn(label) -> { cls = DELETE; why = "the button deletes" }
                    browserSend.containsMatchIn(label) -> { cls = SEND; why = "the button sends" }
                }
            }
            "blaze_command" -> {
                val n = args["command"].orEmpty().trim().removePrefix("/").substringBefore(' ').lowercase()
                cls = COMMANDS[n] ?: READ; why = "/$n"
            }
        }
        if (tool in FREE_TEXT) {
            val kinds = risky(args.values.joinToString(" "))
            val raised = kinds.fold(cls) { acc, k -> max(acc, when (k) { "money" -> SPEND; "delete" -> DELETE; else -> SEND }) }
            if (raised != cls) { cls = raised; why = "the task mentions " + kinds.joinToString(", ") }
        }
        return Classified(cls, base, why, key, level)
    }

    /** git sub-commands that only read (Modes.planGit lets them through in Plan). */
    private val GIT_READ = setOf("status", "diff", "log", "show", "blame", "ls-files", "rev-parse", "describe", "shortlog", "grep",
        "ls-tree", "cat-file", "rev-list", "show-ref", "for-each-ref")

    /** CommandPolicy level → class: SAFE runs (local), ASK asks unless granted, RISKY always asks and can't be pre-allowed. */
    fun commandClass(level: CommandPolicy.Level): ActionClass = when (level) {
        CommandPolicy.Level.SAFE -> WRITE_LOCAL
        CommandPolicy.Level.ASK -> WRITE_REMOTE
        CommandPolicy.Level.RISKY -> DELETE
    }

    /** The class an `action` gives a [BY_ACTION] tool, or null to keep the base. */
    fun actionClass(tool: String, args: Map<String, String>): ActionClass? {
        val a = args["action"]?.trim()?.lowercase().orEmpty()
        if (a.isEmpty()) return null
        return when {
            tool == "call_control" && a == "start_blaze_call" -> WRITE_LOCAL
            tool == "safe_walk" && a in setOf("safe", "status") -> if (a == "status") READ else WRITE_LOCAL
            tool == "gallery_ops" && a == "trash" -> DELETE
            tool == "music_ops" && a in CommandPolicy.IN_APP_SAFE -> WRITE_LOCAL
            a in READ_ACTIONS -> READ
            deleteAction.matches(a) -> DELETE
            tool == "safe_walk" -> SEND   // start / extend: texts the trusted contact if the user doesn't check in
            sendAction.matches(a) -> SEND
            else -> null
        }
    }
}
