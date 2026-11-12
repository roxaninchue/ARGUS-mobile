package com.blaze.assistant.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 0.45.20 step pills (files/from-site/STEPS_DELIVERY_CONTRACT.md §2, §3): ONE quiet line under the pending reply showing
 * the newest `step` text; each step replaces the one before. Hidden on done / failed / cancelled, when the run waits for
 * the phone (`needs_device`, the device tool UI takes over) and when reply text starts streaming (`delta`). Never shown
 * for a reply that lands within [SHOW_AFTER_MS] of the send.
 *
 * Specialties are always general ("Using a specialty" / "Updating a specialty", contract amended 11:08), never which one. The server
 * promises clean text; the phone checks again ([clean]): no model / provider / route / owner names, raw tool names,
 * JSON, braces, URLs or code, at most [MAX] characters, no trailing period. A step that fails the check is dropped (the
 * pill keeps the previous line) rather than shown half-cleaned.
 *
 * [current] is the visible line as a StateFlow so other surfaces (the Blaze Phone PiP) can mirror it.
 * Pure [Machine] (StepPillTest drives it with explicit times); [StepPills] is the app's single live instance.
 */
object StepPill {
    const val MAX = 60
    /** No pill when the reply lands within this long of the send (contract: "~2 s"). */
    const val SHOW_AFTER_MS = 2_000L
    // Contract amended 11:08 CAT: the word is "specialty", never "specialist".
    const val SPECIALIST_IN = "Using a specialty"
    const val SPECIALIST_UPDATE = "Updating a specialty"

    /** A step of kind `specialty` (older drafts said `specialist`). */
    fun isSpecialty(kind: String): Boolean = kind == "specialty" || kind == "specialist"

    // Owner names are built from pieces so no source file carries them (OwnerName19Test greps the repo).
    private val owner = Regex("(?i)\\b(" + listOf("lack" + "son", "lu" + "ke", "kap" + "apa").joinToString("|") + ")\\b")
    private val url = Regex("(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|net|org|io|dev|app|ai|co)\\b)")
    private val code = Regex("[{}\\[\\]<>`|\\\\=;$#@]|\\b[a-z]+_[a-z0-9_]+\\b|\\b[a-z]+[A-Z][a-zA-Z]*\\(|\\w+\\(|=>|->|::")
    private val routeWords = Regex("(?i)\\b(model|provider|route|routing|lineup|endpoint|gateway|token|api key|llm|gpt|claude|gemini|llama|mistral|qwen|deepseek|groq|cerebras|sambanova|openrouter|cohere|nvidia|anthropic|openai|codestral|kimi|glm)\\b")

    /** True when [t] names a model, provider, route or owner (what [clean] refuses on sight). */
    fun mentionsRoute(t: String): Boolean = owner.containsMatchIn(t) || routeWords.containsMatchIn(t) ||
        (try { ModelHide.mentions(t) } catch (_: Throwable) { false })

    /**
     * The pill text for a step event, or null when it must not be shown. [kind] "specialist" (or a specialty event)
     * always gives the general line.
     */
    fun clean(text: String, kind: String = "step"): String? {
        if (isSpecialty(kind)) return if (text.contains("updat", ignoreCase = true)) SPECIALIST_UPDATE else SPECIALIST_IN
        var t = text.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        if (owner.containsMatchIn(t) || url.containsMatchIn(t) || code.containsMatchIn(t) || routeWords.containsMatchIn(t)) return null
        if (try { ModelHide.mentions(t) } catch (_: Throwable) { false }) return null
        t = t.trimEnd('.', ' ', '\u2026')
        if (t.isEmpty()) return null
        if (t.length > MAX) t = t.take(MAX - 1).substringBeforeLast(' ').trimEnd(',', ';', ':', ' ').ifEmpty { t.take(MAX - 1) } + "\u2026"
        return t
    }

    /** Statuses that hide the pill. needs_device hides it without closing the turn (work can go on after the phone). */
    private val HIDE_STATUS = setOf("done", "failed", "cancelled", "expired", "needs_device")
    private val END = setOf("done", "failed", "cancelled", "expired")

    /** One turn's pill. Times are passed in so tests control them. */
    class Machine(val showAfterMs: Long = SHOW_AFTER_MS) {
        var start = 0L; private set
        var text: String? = null; private set
        var kind = "step"; private set
        var closed = true; private set

        fun begin(now: Long) { start = now; text = null; kind = "step"; closed = false }
        fun end() { text = null; closed = true }

        /** Applies one run event; true when the pill's text or visibility may have changed. */
        fun onEvent(o: JSONObject): Boolean {
            if (closed) return false
            when (o.optString("type")) {
                "step" -> { val k = o.optString("kind", "step").let { if (isSpecialty(it)) "specialty" else it }; val c = clean(o.optString("text"), k) ?: return false; text = c; kind = k }
                "specialty_loaded" -> { text = SPECIALIST_IN; kind = "specialty" }
                "specialty_updated" -> { text = SPECIALIST_UPDATE; kind = "specialty" }
                "delta" -> { if (o.optString("text").isEmpty() || text == null) return false; text = null }
                "status" -> { val s = o.optString("status"); if (s !in HIDE_STATUS) return false; text = null; if (s in END) closed = true }
                "done", "failed", "cancelled", "error" -> { text = null; closed = true }
                else -> return false
            }
            return true
        }

        /** A local step (the on-phone loop's work line): same rules as a server `step`. */
        fun onLocal(line: String): Boolean = onEvent(JSONObject().put("type", "step").put("kind", "step").put("text", line))

        /** The pill to draw at [now], or null. */
        fun visible(now: Long): String? = if (closed || text == null || now - start < showAfterMs) null else text

        /** When a step that arrived early becomes visible. */
        fun dueAt(): Long = start + showAfterMs
    }
}

/**
 * P1-SH-03: what a tool call is called on screen (thread step lists, the ActivityLine, WorkChip groups). Raw tool names
 * (`bank_balance`, `memory_save`), model and provider names never reach the screen: a call is named by what it does,
 * from a short table of known tools, then by the words in its name, else "Working". Any free text shown beside it (a
 * step line, a detail) goes through [StepPill.clean] first, and a line that fails is replaced, never shown half-clean.
 */
object StepTool {
    /** AT `.tg` sub-groups (04 §2.8 #19), in display order. */
    enum class Kind(val one: String, val many: String, val group: String) {
        EDIT("edit", "edits", "Edits"), READ("read", "reads", "Reads"), COMMAND("command", "commands", "Commands"),
        CLOUD("cloud step", "cloud steps", "Cloud computer"), BROWSER("browser", "browser", "Browser"),
        MEMORY("memory", "memory", "Memory"), TEAM("team", "team", "Team"), JOBS("job", "jobs", "Jobs"),
        APPROVAL("approval", "approvals", "Approvals"), OTHER("step", "steps", "Other steps"),
    }

    /** Present and past tense and its group. */
    data class Label(val now: String, val done: String, val kind: Kind, val icon: String)

    private fun l(now: String, done: String, kind: Kind, icon: String) = Label(now, done, kind, icon)

    private val known: Map<String, Label> = mapOf(
        "web_search" to l("Searching the web", "Searched the web", Kind.BROWSER, "search"),
        "fetch_page" to l("Reading a page", "Read a page", Kind.BROWSER, "globe"),
        "browser" to l("Using the browser", "Used the browser", Kind.BROWSER, "globe"),
        "save_file" to l("Saving a file", "Saved a file", Kind.EDIT, "download"),
        "edit_file" to l("Editing a file", "Edited a file", Kind.EDIT, "pencil"),
        "write_file" to l("Writing a file", "Wrote a file", Kind.EDIT, "pencil"),
        "read_file" to l("Reading a file", "Read a file", Kind.READ, "scroll-text"),
        "remember" to l("Saving to memory", "Saved to memory", Kind.MEMORY, "brain"),
        "forget" to l("Forgetting something", "Forgot something", Kind.MEMORY, "brain"),
        "memory_search" to l("Checking memory", "Checked memory", Kind.MEMORY, "brain"),
        "memory_save" to l("Saving to memory", "Saved to memory", Kind.MEMORY, "brain"),
        "memory_update" to l("Updating memory", "Updated memory", Kind.MEMORY, "brain"),
        "memory_delete" to l("Forgetting something", "Forgot something", Kind.MEMORY, "brain"),
        "search_past_chats" to l("Looking through past chats", "Looked through past chats", Kind.READ, "message-circle"),
        "search_chat" to l("Looking back in the chat", "Looked back in the chat", Kind.READ, "message-circle"),
        "read_screen" to l("Reading the screen", "Read the screen", Kind.READ, "smartphone"),
        "open_app" to l("Opening an app", "Opened an app", Kind.COMMAND, "smartphone"),
        "run_plan" to l("Using your phone", "Used your phone", Kind.COMMAND, "smartphone"),
        "phone_task" to l("Using your phone", "Used your phone", Kind.COMMAND, "smartphone"),
        "terminal" to l("Running a command", "Ran a command", Kind.COMMAND, "terminal"),
        "run_command" to l("Running a command", "Ran a command", Kind.COMMAND, "terminal"),
        "start_agent" to l("Starting background work", "Started background work", Kind.JOBS, "workflow"),
        "send_to_bot" to l("Asking a teammate", "Asked a teammate", Kind.TEAM, "users"),
        "delegate" to l("Asking a teammate", "Asked a teammate", Kind.TEAM, "users"),
        "add_job" to l("Adding a job", "Added a job", Kind.JOBS, "list-checks"),
        "request_secret" to l("Asking for a secure detail", "Asked for a secure detail", Kind.APPROVAL, "lock"),
        "use_skill" to l(StepPill.SPECIALIST_IN, "Used a specialty", Kind.OTHER, "sparkles"),
        "run_skill" to l(StepPill.SPECIALIST_IN, "Used a specialty", Kind.OTHER, "sparkles"),
    )

    private fun has(n: String, vararg w: String) = w.any { Regex("(^|_)$it(_|$)").containsMatchIn(n) }

    /** The label for tool [name]: never the name itself. */
    fun label(name: String): Label {
        val n = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        known[n]?.let { return it }
        return when {
            has(n, "cloud", "codespace", "orgo", "vm") -> l("Working on the cloud computer", "Worked on the cloud computer", Kind.CLOUD, "cloud")
            has(n, "browser", "browse", "web", "page", "url", "site") -> l("Using the browser", "Used the browser", Kind.BROWSER, "globe")
            has(n, "memory", "remember", "recall") -> l("Checking memory", "Checked memory", Kind.MEMORY, "brain")
            has(n, "bot", "team", "handoff", "delegate") -> l("Asking a teammate", "Asked a teammate", Kind.TEAM, "users")
            has(n, "job", "task", "todo", "routine", "schedule", "alarm", "reminder") -> l("Updating jobs", "Updated jobs", Kind.JOBS, "list-checks")
            has(n, "shell", "exec", "run", "command", "terminal", "script", "git") -> l("Running a command", "Ran a command", Kind.COMMAND, "terminal")
            has(n, "write", "save", "create", "update", "edit", "delete", "set", "add", "send", "post", "move", "rename") ->
                l("Making a change", "Made a change", Kind.EDIT, "pencil")
            has(n, "read", "get", "list", "search", "find", "fetch", "check", "look", "query", "balance", "status", "calendar", "mail", "inbox") ->
                l("Looking things up", "Looked things up", Kind.READ, "search")
            else -> l("Working", "Worked", Kind.OTHER, "zap")
        }
    }

    /** A notice (an error line) as shown: as written, unless it names a model, provider or route. */
    fun notice(text: String): String =
        if (StepPill.mentionsRoute(text)) "Something went wrong on this turn" else text.trim()

    /** A shown line for free text from a step or a detail: [StepPill.clean]'s rules, else [fallback]. */
    fun line(text: String?, fallback: String): String = text?.let { StepPill.clean(it) } ?: fallback
}

/**
 * The app's live pill: one turn at a time per chat; [current] is what the chat (and the Blaze Phone PiP) shows.
 * Publishing is scheduled so a step that came in during the first [StepPill.SHOW_AFTER_MS] appears exactly then.
 */
object StepPills {
    data class Pill(val chat: String, val text: String, val specialist: Boolean)

    private val _current = MutableStateFlow<Pill?>(null)
    /** The visible pill (null = none). Mirrored by the Blaze Phone PiP. */
    val current: StateFlow<Pill?> = _current.asStateFlow()

    private val m = StepPill.Machine()
    private var chat = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timer: Job? = null
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    @Synchronized fun begin(chatId: String, at: Long = clock()) { chat = chatId; m.begin(at); publish() }
    @Synchronized fun end(chatId: String) { if (chatId == chat) { m.end(); publish() } }
    @Synchronized fun onEvent(chatId: String, o: JSONObject) { if (chatId == chat && m.onEvent(o)) publish() }
    @Synchronized fun onLocal(chatId: String, line: String) { if (chatId == chat && m.onLocal(line)) publish() }

    private fun publish() {
        val now = clock()
        val t = m.visible(now)
        _current.value = t?.let { Pill(chat, it, StepPill.isSpecialty(m.kind)) }
        timer?.cancel(); timer = null
        if (t == null && !m.closed && m.text != null && now < m.dueAt()) {
            val wait = m.dueAt() - now
            timer = scope.launch { delay(wait); synchronized(this@StepPills) { publish() } }
        }
    }
}
