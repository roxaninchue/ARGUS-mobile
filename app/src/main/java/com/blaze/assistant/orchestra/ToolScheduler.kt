package com.blaze.assistant.orchestra

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 0.52.0: parallel, conflict-free multi tool calling.
 *
 * When the model returns several tool calls in one reply, the independent ones run at the same time instead of one by
 * one. What may run beside what is decided by the resources each call touches ([Resources.claims]):
 *
 *  - network / read-only tools (web_search, fetch_page, gmail_search, memory reads…) claim nothing: fully parallel;
 *  - file tools claim their path: two reads share it, a write is exclusive, so "write A then read A" in one reply
 *    keeps its order while "write A" and "write B" run together;
 *  - everything that drives the phone screen claims `screen` (exclusive): one screen action at a time, in the order the
 *    model gave them; the background browser claims `browser` the same way;
 *  - each shell / code call runs in its own terminal session (claims nothing), except calls aimed at one named
 *    session or job, which serialize on `term:<id>`;
 *  - calls that may show the user a card (approvals, sends, ask_user) claim `user` so two cards never stack;
 *  - a tool this file doesn't know claims `tool:<name>` (it never runs beside itself) plus `unknown` (unknown tools
 *    take turns with each other), so new tools are safe by default but still run beside network calls.
 *
 * Inside one batch the order is a dependency graph: call j waits for every earlier call i whose claims conflict with
 * its own (write/write or write/read on the same key). Across batches and agents the same keys are held in the shared
 * [KeyedLocks]. Every call has its own timeout, results come back in the model's order with their call ids, and each
 * call's start / end is reported through [Listener] (the agent's pill streams these as steps).
 *
 * Pure (kotlinx.coroutines + org.json); ToolSchedulerTest.
 */
object ToolScheduler {
    data class Call(val id: String, val name: String, val args: JSONObject)

    data class Result(val id: String, val name: String, val output: String, val ms: Long, val timedOut: Boolean = false, val failed: Boolean = false)

    interface Listener {
        fun started(index: Int, call: Call) {}
        fun finished(index: Int, call: Call, result: Result) {}
    }

    object Resources {
        /** Tools that touch the phone screen (accessibility actions and screen reads that depend on what's in front). */
        val screen = setOf("tap", "type_text", "scroll", "press_button", "open_app", "open_url", "search_in_app", "do_actions", "run_plan",
            "read_screen", "look_at_screen", "system_action", "open_settings_page", "open_camera", "blaze_camera", "navigate_to", "phone_act",
            "phone_open", "phone_read", "phone_screenshot", "phone_wait", "phone_handoff", "phone_mode", "call_control", "dial", "run_ussd", "wait")
        /** Network calls and reads of the user's data: no claim at all. */
        val free = setOf("web_search", "fetch_page", "http_request", "recall_memory", "memory_search", "search_past_chats", "search_chat", "gmail_search", "gmail_read",
            "gcal_events", "calendar_events", "drive_search", "gcontacts_search", "find_contact", "recent_notifications", "read_messages", "recent_calls",
            "device_status", "battery_level", "github_issues", "github_my_prs", "github_my_repos", "github_notifications", "github_search_repos", "github_ci",
            "notion_search", "slack_read", "slack_channels", "search_screen_memory", "spotify_now_playing", "list_apps", "list_routines", "list_triggers",
            "list_agents", "check_agent", "app_usage", "notif_conversations", "notif_read", "cache_read", "cache_search", "cache_toc", "cache_summarize",
            "read_artifact", "read_turn", "find_skill", "terminal_status", "terminal_jobs", "classroom_courses", "classroom_coursework", "processes",
            "generate_image", "phone_status", "ask_pc")
        /** Tools that write a path (the key is the path arg). */
        val fileWrite = setOf("file_write", "file_edit", "file_delete", "save_file", "export_file", "download_file", "skill_file", "write_skill")
        val fileRead = setOf("file_read", "file_list", "file_search")
        /** Each call is its own terminal session unless it names one. */
        val terminal = setOf("run_shell", "termux_run", "run_code", "linux_install", "terminal_stop", "preview_server", "job_input", "process")
        /** May put a card in front of the user (approval / question / send): one at a time. */
        val user = setOf("ask_user", "send_sms", "send_text", "compose_sms", "gmail_send", "slack_post", "call_contact", "call_number", "share_text", "share_to_app",
            "uninstall_app", "standing_permission", "notif_reply", "reply_to_notification", "github_install_apk", "delete_event", "memory_delete", "forget")
        val browser = { n: String -> n.startsWith("browser_") }

        private val pathKeys = listOf("path", "file", "dest", "destination", "target", "dir", "directory", "filename", "name")

        /** Normalised path of a file call ("" when it has none): no trailing slash, no "./", collapsed "//". */
        fun path(args: JSONObject): String {
            val raw = pathKeys.firstNotNullOfOrNull { k -> args.optString(k).trim().takeIf { it.isNotEmpty() } } ?: return ""
            var p = raw.replace(Regex("/{2,}"), "/").removePrefix("./")
            while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
            return p
        }

        fun claims(name: String, args: JSONObject): Set<KeyedLocks.Claim> {
            val out = LinkedHashSet<KeyedLocks.Claim>()
            when {
                name in screen -> out += KeyedLocks.Claim("screen", true)
                browser(name) -> out += KeyedLocks.Claim("browser", true)
                name in fileWrite -> {
                    out += KeyedLocks.Claim(FILE_OP, false)
                    val p = path(args)
                    out += KeyedLocks.Claim(if (p.isEmpty()) "file:?" else "file:$p", true)
                    // A write inside a folder conflicts with a listing of that folder, not with other writes beside it:
                    // writers share the folder key, a listing takes it alone.
                    parents(p).forEach { out += KeyedLocks.Claim("dir:$it", false) }
                }
                name in fileRead -> {
                    out += KeyedLocks.Claim(FILE_OP, false)
                    val p = path(args)
                    out += KeyedLocks.Claim(if (p.isEmpty()) "file:?" else "file:$p", false)
                    if (name == "file_list" || name == "file_search") out += KeyedLocks.Claim("dir:${p.ifEmpty { "/" }}", true)
                }
                name in terminal -> {
                    out += KeyedLocks.Claim(SHELL, false)
                    val session = listOf("session", "job", "id", "pid").firstNotNullOfOrNull { k -> args.optString(k).trim().takeIf { it.isNotEmpty() } }
                    if (session != null) out += KeyedLocks.Claim("term:$session", true)
                }
                name in user -> out += KeyedLocks.Claim("user", true)
                name in free -> Unit
                else -> { out += KeyedLocks.Claim("tool:$name", true); out += KeyedLocks.Claim("unknown", true) }
            }
            // Anything that may ask before acting (approvals) takes the user lane too.
            if (name in screen && name in setOf("call_control", "dial", "run_ussd")) out += KeyedLocks.Claim("user", true)
            return out
        }

        /** "/a/b/c.kt" → ["/a/b", "/a", "/"]; "app/x.kt" → ["app", "."]. */
        fun parents(p: String): List<String> {
            if (p.isEmpty()) return emptyList()
            val out = ArrayList<String>()
            var cur = p
            while (true) {
                val i = cur.lastIndexOf('/')
                if (i < 0) { out += "."; break }
                cur = if (i == 0) "/" else cur.substring(0, i)
                out += cur
                if (cur == "/") break
            }
            return out
        }

        /** In-batch ordering tags: a shell command may read or write any file, so shell calls keep their order with file calls. */
        const val SHELL = "~shell"
        const val FILE_OP = "~file"

        fun conflict(a: Set<KeyedLocks.Claim>, b: Set<KeyedLocks.Claim>): Boolean {
            val aSh = a.any { it.key == SHELL }; val bSh = b.any { it.key == SHELL }
            val aF = a.any { it.key == FILE_OP }; val bF = b.any { it.key == FILE_OP }
            if ((aSh && bF) || (bSh && aF)) return true
            return a.any { x -> b.any { y -> x.key == y.key && (x.write || y.write) } }
        }

        /**
         * The claims held in the shared [KeyedLocks] (across batches and agents): real resources only (screen, browser,
         * files, folders, named terminal sessions). "user", "unknown", "tool:*" and the "~" tags order calls inside one
         * reply but are never held across agents, so one agent's approval card can't stall another agent.
         */
        fun lockClaims(claims: Set<KeyedLocks.Claim>): Set<KeyedLocks.Claim> =
            claims.filter { c -> c.key == "screen" || c.key == "browser" || c.key.startsWith("file:") || c.key.startsWith("dir:") || c.key.startsWith("term:") }.toSet()

        /** Tools the chat agent may run ahead, in parallel, before its in-order loop (screen, cards and agent tools stay in the loop). */
        fun prefetchable(name: String): Boolean = name !in screen && name !in user && name !in loopOnly

        val loopOnly = setOf("find_tool", "load_tools", "ask_user", "delegate", "start_agent", "spawn_agent", "orchestrate", "message_agent", "cancel_agent",
            "create_specialist", "goal", "do_actions", "run_plan", "browser_show", "phone_handoff", "self_test", "blaze_self", "update_brief")
    }

    /** For each call, the earlier calls it must wait for (pure; the plan the tests check). */
    fun plan(calls: List<Call>): List<Set<Int>> {
        val claims = calls.map { Resources.claims(it.name, it.args) }
        return calls.indices.map { j -> (0 until j).filter { i -> Resources.conflict(claims[i], claims[j]) }.toSet() }
    }

    /** Waves of calls that may start together (for logs and the docs: wave 0 starts at once). */
    fun waves(calls: List<Call>): List<List<Int>> {
        val deps = plan(calls); val level = IntArray(calls.size)
        for (j in calls.indices) level[j] = (deps[j].maxOfOrNull { level[it] + 1 } ?: 0)
        return calls.indices.groupBy { level[it] }.toSortedMap().values.toList()
    }

    /**
     * Runs [calls] as a dataflow over [plan]: each starts when the calls it depends on are done, holding its claims in
     * [locks] (shared with other agents) for as long as it runs, cut off after [timeoutMs] (per call; [timeoutFor] can
     * override it per tool). A call that throws becomes an "Error: …" result; cancellation cancels them all.
     * Results are in the order of [calls].
     */
    suspend fun run(
        calls: List<Call>,
        exec: suspend (Call) -> String,
        timeoutMs: Long = 180_000L,
        timeoutFor: (Call) -> Long = { timeoutMs },
        locks: KeyedLocks = KeyedLocks.shared,
        listener: Listener? = null,
        clock: () -> Long = { System.currentTimeMillis() },
    ): List<Result> = coroutineScope {
        val deps = plan(calls)
        val done = calls.map { CompletableDeferred<Unit>() }
        val jobs = calls.mapIndexed { i, c ->
            async {
                try {
                    deps[i].forEach { done[it].await() }
                    val claims = Resources.lockClaims(Resources.claims(c.name, c.args))
                    locks.with(claims) {
                        listener?.started(i, c)
                        val t0 = clock()
                        var timedOut = false
                        var failed = false
                        val out = try {
                            withTimeoutOrNull(timeoutFor(c)) { exec(c) } ?: run { timedOut = true; "Error: ${c.name} took longer than ${timeoutFor(c) / 1000}s and was stopped." }
                        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                            failed = true; "Error: ${c.name} failed: ${e.message ?: e.javaClass.simpleName}"
                        }
                        Result(c.id, c.name, out, clock() - t0, timedOut, failed).also { listener?.finished(i, c, it) }
                    }
                } finally { done[i].complete(Unit) }
            }
        }
        jobs.map { it.await() }
    }

    /** One line for logs: "3 calls in 2 waves: [web_search, web_search] then [file_read]". */
    fun describe(calls: List<Call>): String {
        val w = waves(calls)
        return "${calls.size} call(s) in ${w.size} wave(s): " + w.joinToString(" then ") { wave -> wave.joinToString(", ", "[", "]") { calls[it].name } }
    }
}
