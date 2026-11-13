package com.blaze.assistant.policy

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
 * P2-SF-01: what each tool does, as one of the ten action classes of 05 §7.1 (the enum itself is
 * [com.blaze.assistant.team.ActionClass], P0-TM-01, so team.db rows and the policy share one type).
 *
 * Every tool the agent can call is declared here in [ToolRegistry]. A tool whose class depends on what it is asked to
 * do (`gallery` list vs delete, `git` status vs push) declares a base class (the riskiest common use) plus per-action
 * classes for its `action` argument. Shell and git commands are classed by [com.blaze.assistant.agent.CommandPolicy.actionClass],
 * MCP tools by their server's read-only / destructive hints ([mcpClass]). A tool that is itself a card the user answers
 * (ask_user, request_secret, the browser's secure input) is [Decl.selfCard]: the card is the approval.
 *
 * Pure Kotlin. ToolRegistryTest fails when a tool defined anywhere in the app has no declaration here.
 */
object ActionClasses {
    /** Classes that never take a standing "Always allow" (05 §7.1 "Can be pre-approved?" = no or per-session only). */
    private val noStanding = setOf(SPEND, DELETE, CREDENTIAL, COMPUTE_PAID)

    /** May a standing policy (Always allow, a bot's tool grant) cover this class? SEND has its own narrower rule. */
    fun preApprovable(c: ActionClass): Boolean = c !in noStanding && c != SEND

    /** Reads and local writes: nothing leaves the phone, nothing is lost. */
    fun quiet(c: ActionClass): Boolean = c == READ || c == WRITE_LOCAL

    /** SEND / SPEND / DELETE: never executed unconfirmed when a routine, suggestion or another bot started it (05 §7.2). */
    fun guarded(c: ActionClass): Boolean = c == SEND || c == SPEND || c == DELETE

    /** The class of an MCP tool from its server's hints (McpConfig.Risk READ / ACTION / WRITE). */
    fun mcpClass(readOnly: Boolean, destructive: Boolean): ActionClass = when {
        destructive -> DELETE
        readOnly -> READ
        else -> WRITE_REMOTE
    }

    /** "ACTION" / "READ" / "WRITE" (McpConfig.Risk names) → class. */
    fun mcpClass(risk: String): ActionClass = when (risk.uppercase()) {
        "READ" -> READ
        "WRITE" -> DELETE
        else -> WRITE_REMOTE
    }
}

object ToolRegistry {
    /**
     * One tool's declaration. [base] = its class when no action matches; [actions] = class per value of its [arg]
     * argument (default "action"); [selfCard] = the tool is a card the user answers, so it needs no approval of its own.
     */
    data class Decl(val base: ActionClass, val actions: Map<String, ActionClass> = emptyMap(), val selfCard: Boolean = false,
                    val arg: String = "action")

    /** Shell-like tools: classed per command by CommandPolicy (the registry's base is the fallback). */
    val COMMAND_TOOLS = setOf("run_shell", "termux_run", "git")

    private fun d(c: ActionClass, vararg a: Pair<String, ActionClass>) = Decl(c, mapOf(*a))

    private val decls = HashMap<String, Decl>()
    private fun all(c: ActionClass, vararg names: String) { for (n in names) decls[n] = Decl(c) }
    private fun one(n: String, decl: Decl) { decls[n] = decl }

    init {
        // ---------------- READ: look, search, list; nothing changes ----------------
        all(READ, "about_blaze", "agent_status", "agents_list", "app_usage", "ask_user", "battery_level", "blaze_docs_read", "blaze_docs_search",
            "blaze_now", "blaze_research", "browser_back", "browser_find", "browser_open", "browser_read", "browser_scroll", "browser_show",
            "browser_tabs", "browser_wait", "cache_read", "cache_search", "cache_summarize", "cache_toc", "calendar_events", "camera_math",
            "check_agent", "classroom_courses", "classroom_coursework", "classroom_sync", "device_status", "drive_search", "fetch_page",
            "file_list", "file_read", "file_search", "find_contact", "find_skill", "find_tool", "gcal_events", "gcontacts_search", "get_location",
            "get_weather", "github_ci", "github_issues", "github_my_prs", "github_my_repos", "github_notifications", "github_search_repos",
            "gmail_read", "gmail_search", "group_brain", "home_widget_list", "list_agents", "list_apps", "list_routines", "list_triggers",
            "load_tools", "look_at_screen", "memory_search", "notif_conversations", "notif_read", "notion_search", "open_view", "phone_health",
            "phone_read", "phone_screenshot", "phone_status", "phone_wait", "processes", "project_docs_read", "project_docs_search",
            "read_artifact", "read_messages", "read_screen", "read_turn", "recall_memory", "recent_calls", "recent_notifications",
            "search_chat", "search_past_chats", "search_screen_memory", "self_test", "skill_file", "slack_channels", "slack_read",
            "spotify_now_playing", "store_info", "store_search", "store_status", "terminal_jobs", "terminal_status", "use_skill", "wait",
            "web_search")
        one("ask_user", Decl(READ, selfCard = true))

        // ---------------- WRITE_LOCAL: ARGUS's own data on this phone ----------------
        all(WRITE_LOCAL, "add_job", "agent_create", "agent_delegate", "agent_progress", "agent_update", "cancel_agent", "cover_art",
            "create_routine", "create_specialist", "create_task", "create_trigger", "delegate", "done_job", "download_file", "duo_workspace",
            "export_file", "file_edit", "file_write", "generate_image", "goal", "job_input", "media_download", "memory_merge", "memory_save",
            "memory_update", "message_agent", "movies", "note_team", "orchestrate", "post_notification", "post_update", "preview_server",
            "read_aloud", "remember", "retry_job", "run_code", "run_skill", "save_file", "save_workflow", "schedule_command", "spawn_agent",
            "stage_close", "stage_layout", "stage_move", "stage_show", "stage_update", "start_agent", "terminal_stop", "update_agent",
            "update_brief", "update_job", "update_task", "watch", "write_skill", "home_widget_add", "home_widget_update", "set_alarm", "set_timer",
            "beat_lab", "clip_studio", "video_ops", "music_ops")
        one("design", d(WRITE_LOCAL, "read" to READ, "list" to READ, "open" to READ))
        one("flashcards", d(WRITE_LOCAL, "list" to READ, "due" to READ, "delete_deck" to DELETE))
        one("follow_ups", d(WRITE_LOCAL, "list" to READ))
        one("time_capsule", d(WRITE_LOCAL, "list" to READ, "cancel" to DELETE))
        one("brand_kit", d(WRITE_LOCAL, "get" to READ))
        one("process", d(WRITE_LOCAL, "details" to READ, "status" to READ, "delete" to DELETE))
        one("life_logs", d(WRITE_LOCAL, "status" to READ, "review" to READ, "delete" to DELETE))
        one("gallery", d(WRITE_LOCAL, "list" to READ, "share" to SEND, "delete" to DELETE))
        one("gallery_ops", d(WRITE_LOCAL, "on_this_day" to READ, "backup_plan" to READ, "trash" to DELETE))
        one("skill_manage", d(WRITE_LOCAL, "delete" to DELETE))
        one("money", d(READ, "add_bill" to WRITE_LOCAL, "add_receipt" to WRITE_LOCAL, "remove_bill" to DELETE, "enable" to SETTINGS, "disable" to SETTINGS))
        one("clipboard", d(WRITE_LOCAL, "get" to READ, "get_image" to READ, "history" to READ))

        // ---------------- DELETE: something is gone ----------------
        all(DELETE, "agent_delete", "delete_event", "delete_routine", "delete_trigger", "file_delete", "forget", "home_widget_remove",
            "memory_delete", "uninstall_app")

        // ---------------- WRITE_REMOTE: changes outside the phone (accounts, calendars, docs, the PC) ----------------
        all(WRITE_REMOTE, "add_calendar_event", "add_contact", "ask_pc", "edit_contact", "gcal_create", "github_create_issue",
            "github_create_repo", "move_event", "notion_add_page")
        one("http_request", Decl(WRITE_REMOTE, mapOf("get" to READ, "head" to READ, "options" to READ), arg = "method"))

        // ---------------- SEND: speaks for the user ----------------
        all(SEND, "call_contact", "call_number", "gmail_send", "message_contact", "notif_reply", "reply_to_notification", "safe_walk",
            "send_sms", "send_text", "share_text", "share_to_app", "slack_post")
        one("safe_walk", d(SEND, "status" to READ))

        // ---------------- SPEND: money ----------------
        all(SPEND, "run_ussd")   // USSD codes buy airtime / bundles and move mobile money

        // ---------------- SETTINGS: the phone's or ARGUS's settings, installs ----------------
        all(SETTINGS, "blaze_command", "device_setting", "github_install_apk", "install_skill", "jarvis_mode", "linux_install",
            "phone_mode", "store_install", "store_update", "update_blaze", "browser_vpn", "toggle_setting")
        one("blaze_self", d(SETTINGS, "setup_check" to READ, "status" to READ))
        one("blazerouter", d(SETTINGS, "status" to READ, "dashboard" to READ))
        one("bank_mode", d(SETTINGS, "status" to READ))
        one("call_copilot", d(SETTINGS, "status" to READ))
        one("halo_persona", d(SETTINGS, "list" to READ, "delete" to DELETE, "reset" to DELETE))
        one("skill_market", d(SETTINGS, "browse" to READ, "review" to READ, "share" to SEND))

        // ---------------- CREDENTIAL: secrets, sign-ins, approval rules ----------------
        all(CREDENTIAL, "jamendo_setup", "standing_permission")   // standing_permission changes what ARGUS may do unasked
        one("request_secret", Decl(CREDENTIAL, selfCard = true))
        one("browser_secure_input", Decl(CREDENTIAL, selfCard = true))

        // ---------------- PHYSICAL: hands on the phone's screen and apps ----------------
        all(PHYSICAL, "blaze_camera", "browser_click", "browser_key", "browser_select", "browser_type", "compose_sms", "dial", "do_actions",
            "media_control", "navigate_to", "open_app", "open_camera", "open_settings", "open_settings_page", "open_url", "phone_act",
            "phone_handoff", "phone_open", "phone_task", "press_button", "run_plan", "scroll", "search_in_app", "set_volume", "spotify_control",
            "spotify_play", "store_open", "system_action", "tap", "type_text")
        one("call_control", d(PHYSICAL, "status" to READ, "start_blaze_call" to SEND))

        // ---------------- commands: per command (CommandPolicy.actionClass); base = local work ----------------
        all(WRITE_LOCAL, "run_shell", "termux_run")
        one("git", d(WRITE_LOCAL, "status" to READ, "log" to READ, "diff" to READ, "push" to WRITE_REMOTE))
    }

    /** Every declared tool name. */
    val names: Set<String> get() = decls.keys

    fun decl(name: String): Decl? = decls[name]

    /** True when [name] has a class: declared here, or an MCP tool (classed by its server's hints). */
    fun declared(name: String): Boolean = name in decls || isMcp(name)

    fun isMcp(name: String) = name.startsWith("mcp__") || name.startsWith("mcp_")

    /** The tool is a card the user answers itself (no separate approval). */
    fun selfCard(name: String): Boolean = decls[name]?.selfCard == true

    /**
     * The class of one call of [name]; [arg] reads one argument of the call ("" when absent). Null = not declared
     * (the policy then treats it as [WRITE_REMOTE], so an undeclared tool asks rather than runs unseen).
     */
    fun classOf(name: String, arg: (String) -> String = { "" }): ActionClass? {
        val dc = decls[name] ?: return null
        if (dc.actions.isEmpty()) return dc.base
        val v = arg(dc.arg).trim().lowercase()
        return dc.actions[v] ?: dc.base
    }

    /** What an undeclared tool counts as. */
    val UNDECLARED = WRITE_REMOTE
}
