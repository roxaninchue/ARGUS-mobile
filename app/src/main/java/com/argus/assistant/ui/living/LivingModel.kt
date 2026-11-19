package com.argus.assistant.ui.living

import com.argus.assistant.agent.ToolTrail

/**
 * Living UI: the chat's messages → the cards on the canvas. The same stream that drives "Show tool steps": the step
 * messages carry the exact call in their meta ([com.argus.assistant.agent.Friendly.exact]: `name(k=v, …)`, or `$ cmd`
 * for a shell) and [ToolTrail] keeps what each call gave back, matched by that exact text.
 *
 * Per turn (your message and everything up to the next one):
 *  - [Kind.PROMPT]: your message, the root.
 *  - [Kind.BROWSER]: a web search (the search words in the URL bar) or a page read (its address); title and page text
 *    from the result.
 *  - [Kind.FILE]: a file read or written: its name and the first [FILE_LINES] lines of the real text.
 *  - [Kind.LOG]: every other call in a row is one steps log: shell commands as `$ cmd`, other tools in plain words,
 *    each with done / failed / running.
 *  - [Kind.ANSWER]: the reply. An acknowledgement ("On it") is not drawn.
 *  - [Kind.RESULT] (0.2.2, "show, don't tell"): what a finished call gave back that can be shown (the weather, the
 *    place, the alarm, the event, the picture, a table; [ResultCards]), placed just before the answer.
 * 0.2.2: a steps row says what was done with the call's main argument ([LivingSteps.label]) and true duplicates fold.
 *
 * A step message without a call (narration) draws nothing; nothing raw reaches the canvas except a shell command, the
 * page address and the real text of pages and files. Only the newest [LivingSpec.TURNS_SHOWN] turns are kept, and no
 * more than [LivingSpec.MAX_CARDS] cards (the oldest turns drop first).
 *
 * Pure: LivingModelTest.
 */
object LivingModel {
    enum class Role { USER, BLAZE, STEP }
    enum class Kind { PROMPT, BROWSER, FILE, LOG, ANSWER, RESULT }
    enum class State { RUNNING, DONE, FAILED }

    /** What the model needs of a chat message. [ack] = an acknowledgement reply, not drawn. */
    data class Msg(val id: Long, val role: Role, val text: String, val meta: String = "", val at: Long = 0L, val ack: Boolean = false)

    /** One row of a steps log: a command (`$ ls`) or a tool in plain words. */
    data class Row(val text: String, val command: Boolean, val state: State, val output: String? = null)

    data class Card(
        val key: String,
        val kind: Kind,
        val state: State,
        /** Prompt / answer text; a browser card's page text. */
        val text: String = "",
        /** Browser: the address or the search words. */
        val url: String = "",
        /** Browser: the page title (or "Results for …"); file: the file name. */
        val title: String = "",
        /** File: true = written or edited, false = read. Browser: true = a search. */
        val flag: Boolean = false,
        /** File: the lines shown. */
        val lines: List<String> = emptyList(),
        /** Log: its rows. */
        val rows: List<Row> = emptyList(),
        val at: Long = 0L,
        /** Result (0.2.2): what to draw. */
        val result: ResultCard? = null,
        /** 0.2.4 browser: what the agent is doing in it now ("Typing “restaurants”"), or did last. */
        val now: String = "",
        /** 0.2.4: how many calls this card stands for (one browser card per tab takes every step in it; searches in a row fold). */
        val steps: Int = 1,
        /** 0.2.4: a card of the agent's own browser (browser_* tools), updated in place; tap opens the glass view. */
        val live: Boolean = false,
    )

    data class Turn(val key: String, val cards: List<Card>) { val steps: Int get() = cards.sumOf { if (it.kind == Kind.LOG) it.rows.size else if (it.kind == Kind.BROWSER) it.steps else if (it.kind == Kind.FILE) 1 else 0 } }

    data class Board(
        val turns: List<Turn>,
        /** The card being worked on now (pulses, Next goes there), or the newest card when idle. */
        val active: String?,
        /** The pill: "Reading a page · 3 steps", "Done · 6 steps". */
        val status: String,
        val working: Boolean,
    ) {
        val cards: List<Card> get() = turns.flatMap { it.cards }
    }

    const val FILE_LINES = 10
    const val LOG_ROWS = 8
    const val TEXT_MAX = 900
    const val PROMPT_MAX = 280

    private val CALL = Regex("^([A-Za-z][A-Za-z0-9_.-]*)\\(")
    private val ARG = Regex("(?:^|, )([A-Za-z_][A-Za-z0-9_]*)=")

    /** The tool an exact text names; `$ cmd` is a shell call; null = narration. */
    fun toolOf(meta: String): String? {
        val m = meta.trim()
        if (m.isEmpty()) return null
        if (m.startsWith("$ ")) return "run_shell"
        return CALL.find(m)?.groupValues?.get(1)
    }

    /** `name(a=1, b=x y)` → {a: "1", b: "x y"}. Values were clipped to 120 characters by Friendly.exact. */
    fun args(meta: String): Map<String, String> {
        val m = meta.trim()
        val open = m.indexOf('(')
        if (open < 0 || !m.endsWith(")")) return emptyMap()
        val body = m.substring(open + 1, m.length - 1)
        val hits = ARG.findAll(body).toList()
        val out = LinkedHashMap<String, String>()
        for ((i, h) in hits.withIndex()) {
            val start = h.range.last + 1
            val end = if (i + 1 < hits.size) hits[i + 1].range.first else body.length
            out[h.groupValues[1]] = body.substring(start, end).trim()
        }
        return out
    }

    enum class Tool { SEARCH, PAGE, BROWSER, FILE_READ, FILE_WRITE, OTHER }

    val pageTools = setOf("fetch_page", "read_page", "fetch_url", "browser", "open_url")

    /**
     * 0.2.4 (Finn: "1 browser per agent, I don't want an infinite line of browsers"): every step in ARGUS's own browser
     * (open, read, click, type, scroll, …) updates ONE card per tab of the turn in place, instead of a new card (or a log
     * row) per step. A new card only when the model opens a new tab (browser_open new_tab=true).
     */
    val browserTools = setOf("browser_open", "browser_read", "browser_show", "browser_click", "browser_type", "browser_scroll", "browser_select",
        "browser_key", "browser_wait", "browser_find", "browser_back", "browser_tabs", "browser_secure_input")

    fun toolKind(name: String): Tool {
        val n = name.lowercase()
        return when {
            n == "web_search" || n == "search_web" || n == "websearch" || n.endsWith("_search") && n.startsWith("web") -> Tool.SEARCH
            // 0.2.0: only tools that land on a page get a page card; clicks, typing, scrolls, waits are steps of the log
            n in browserTools -> Tool.BROWSER
            n in pageTools -> Tool.PAGE
            n in setOf("file_read", "read_file", "file_view") -> Tool.FILE_READ
            n in setOf("file_write", "file_edit", "write_file", "edit_file", "save_file", "export_file") -> Tool.FILE_WRITE
            else -> Tool.OTHER
        }
    }

    /** Plain words for a tool that has no card of its own (the screen passes StepTool's labels). */
    fun defaultLabel(name: String, running: Boolean): String {
        val words = name.replace('_', ' ').replace('.', ' ').trim().ifEmpty { "a step" }
        return if (running) "Working: $words" else words.replaceFirstChar { it.uppercase() }
    }

    /**
     * The board for [msgs] (oldest first). [working] = a turn is running now. [results] = this chat's [ToolTrail]
     * results, oldest first. [label] names a tool in plain words (running = present tense).
     */
    fun board(
        msgs: List<Msg>, working: Boolean, results: List<ToolTrail.Result> = emptyList(),
        label: (String, Boolean) -> String = ::defaultLabel,
    ): Board {
        val used = BooleanArray(results.size)
        val turns = ArrayList<Turn>()
        var cur: ArrayList<Card>? = null
        var curKey = ""
        var log: ArrayList<Row>? = null
        var logKey = ""
        var logAt = 0L
        // 0.2.2: the turn's finished calls (for its result cards) and the place it found (for the steps' words)
        var calls = ArrayList<ResultCards.Call>()
        var place = ""
        // 0.2.4: this turn's browser cards, one per tab (tab key → index in the turn's cards), and the tab in use
        var tabs = LinkedHashMap<Int, Int>()
        var tab = 0

        fun closeLog() {
            val l = log ?: return
            val c = cur ?: return
            val st = when { l.any { it.state == State.RUNNING } -> State.RUNNING; l.last().state == State.FAILED -> State.FAILED; else -> State.DONE }
            c += Card(logKey, Kind.LOG, st, rows = LivingSteps.merge(l).takeLast(LOG_ROWS), at = logAt)
            log = null
        }
        fun closeTurn() {
            closeLog()
            cur?.let { c ->
                // 0.2.2: the turn's result cards go just before its answer (or at the end while it still works)
                val answer = c.lastOrNull()?.takeIf { it.kind == Kind.ANSWER }
                val shown = ResultCards.of(calls, answer?.text).mapIndexed { i, r ->
                    Card("r${curKey}-${r::class.simpleName}$i", Kind.RESULT, State.DONE, at = answer?.at ?: c.last().at, result = r)
                }
                if (shown.isNotEmpty()) { if (answer != null) c.addAll(c.lastIndex, shown) else c.addAll(shown) }
                if (c.isNotEmpty()) turns += Turn(curKey, c)
            }
            cur = null; calls = ArrayList(); place = ""; tabs = LinkedHashMap(); tab = 0
        }

        for (m in msgs) {
            when (m.role) {
                Role.USER -> {
                    closeTurn()
                    curKey = "t${m.id}"
                    cur = arrayListOf(Card("p${m.id}", Kind.PROMPT, State.DONE, text = clip(m.text.trim(), PROMPT_MAX), at = m.at))
                }
                Role.BLAZE -> {
                    if (m.ack || cur == null || m.text.isBlank()) continue
                    closeLog()
                    cur!! += Card("a${m.id}", Kind.ANSWER, State.DONE, text = clip(plain(m.text), TEXT_MAX), at = m.at)
                }
                Role.STEP -> {
                    val c = cur ?: continue
                    val name = toolOf(m.meta) ?: continue
                    val r = match(m, name, results, used)
                    val state = when {
                        r != null -> if (r.failed) State.FAILED else State.DONE
                        working -> State.RUNNING
                        else -> State.DONE
                    }
                    val a = args(m.meta)
                    val out = r?.output.orEmpty()
                    if (state == State.DONE) {
                        calls += ResultCards.Call(name, a, out, false)
                        if (name.equals("get_location", true)) ResultCards.place(out)?.locality?.takeIf { it.isNotBlank() }?.let { place = it }
                    }
                    when (toolKind(name)) {
                        Tool.SEARCH -> {
                            val q = a["query"] ?: a["q"] ?: a["text"] ?: a.values.firstOrNull().orEmpty()
                            val title = if (q.isBlank()) "Search results" else "Results for \u201c${unquote(q)}\u201d"
                            val prev = c.lastOrNull()
                            // 0.2.4: searches one after the other are one Searching card that shows the newest query
                            if (log == null && prev != null && prev.kind == Kind.BROWSER && prev.flag) {
                                c[c.lastIndex] = prev.copy(state = state, text = clip(out.trim(), TEXT_MAX).ifBlank { prev.text }, url = unquote(q),
                                    title = title, steps = prev.steps + 1)
                            } else {
                                closeLog()
                                c += Card("s${m.id}", Kind.BROWSER, state, text = clip(out.trim(), TEXT_MAX), url = unquote(q), title = title, flag = true, at = m.at)
                            }
                        }
                        Tool.BROWSER -> {
                            val (title, body) = LivingPage.read(out)
                            val seen = LivingPage.url(out)
                            val asked = unquote(a["url"] ?: "")
                            val askedUrl = asked.takeIf { looksLikeUrl(it) }.orEmpty()
                            if (name == "browser_open" && a["new_tab"]?.trim()?.lowercase() == "true" && tabs.isNotEmpty()) tab = (tabs.keys.maxOrNull() ?: 0) + 1
                            if (name == "browser_tabs" && out.startsWith("Switched to ")) {
                                val t = out.removePrefix("Switched to ").trim().removeSuffix(".")
                                tabs.entries.firstOrNull { c.getOrNull(it.value)?.title == t }?.let { tab = it.key }
                            }
                            val i = tabs[tab]
                            val prev = i?.let { c.getOrNull(it) }
                            val url = seen.ifBlank { askedUrl }.ifBlank { prev?.url.orEmpty() }
                            val doing = browserDoing(name, a, state == State.RUNNING, host(url))
                            if (i != null && prev != null) {
                                c[i] = prev.copy(state = state, url = url, title = title.ifBlank { prev.title }, now = doing,
                                    text = if (body.isNotBlank()) clip(body, TEXT_MAX) else prev.text, steps = prev.steps + 1)
                            } else {
                                closeLog()
                                tabs[tab] = c.size
                                c += Card("w${m.id}", Kind.BROWSER, state, text = clip(body, TEXT_MAX), url = url,
                                    title = title.ifBlank { if (url.isBlank()) "" else host(url) }, now = doing, at = m.at, live = true)
                            }
                        }
                        Tool.PAGE -> {
                            val u = unquote(a["url"] ?: a["u"] ?: a["link"] ?: a["address"] ?: "")
                            val (title, body) = LivingPage.read(out)
                            // 0.2.0: reading the page that is already open updates its card instead of drawing a second one
                            val prev = c.lastOrNull()
                            if (log == null && u.isBlank() && prev != null && prev.kind == Kind.BROWSER && !prev.flag) {
                                c[c.lastIndex] = prev.copy(state = state, text = if (body.isNotBlank()) clip(body, TEXT_MAX) else prev.text,
                                    title = title.ifBlank { prev.title })
                            } else {
                                closeLog()
                                c += Card("b${m.id}", Kind.BROWSER, state, text = clip(body, TEXT_MAX), url = u,
                                    title = title.ifBlank { host(u).ifBlank { "Page" } }, at = m.at)
                            }
                        }
                        Tool.FILE_READ, Tool.FILE_WRITE -> {
                            closeLog()
                            val wrote = toolKind(name) == Tool.FILE_WRITE
                            val path = unquote(a["path"] ?: a["file"] ?: a["name"] ?: a["filename"] ?: "")
                            val src = if (wrote) unquote(a["content"] ?: a["text"] ?: a["new_text"] ?: a["new"] ?: "").replace("\\n", "\n") else out
                            c += Card("f${m.id}", Kind.FILE, state, title = path.substringAfterLast('/').ifBlank { "file" }, url = path,
                                flag = wrote, lines = src.lines().dropLastWhile { it.isBlank() }.take(FILE_LINES).map { it.take(160) }, at = m.at)
                        }
                        Tool.OTHER -> {
                            val shell = m.meta.trim().startsWith("$ ")
                            val text = if (shell) m.meta.trim().take(200) else LivingSteps.label(name, a, state == State.RUNNING, label(name, state == State.RUNNING), place)
                            if (log == null) { log = ArrayList(); logKey = "l${m.id}"; logAt = m.at }
                            log!! += Row(text, shell, state, out.takeIf { it.isNotBlank() }?.let { clip(it.trim(), 200) })
                        }
                    }
                }
            }
        }
        closeTurn()

        // newest turns only, then the card cap (oldest turns go first; the newest turn keeps its newest cards)
        var kept = turns.takeLast(LivingSpec.TURNS_SHOWN)
        while (kept.size > 1 && kept.sumOf { it.cards.size } > LivingSpec.MAX_CARDS) kept = kept.drop(1)
        if (kept.size == 1 && kept[0].cards.size > LivingSpec.MAX_CARDS) {
            val t = kept[0]
            kept = listOf(Turn(t.key, listOf(t.cards.first()) + t.cards.drop(1).takeLast(LivingSpec.MAX_CARDS - 1)))
        }

        val last = kept.lastOrNull()
        val activeCard = last?.cards?.lastOrNull { it.state == State.RUNNING } ?: last?.cards?.lastOrNull()
        return Board(kept, activeCard?.key, status(last, working, activeCard, label), working)
    }

    /** The pill's words. */
    fun status(last: Turn?, working: Boolean, active: Card?, label: (String, Boolean) -> String = ::defaultLabel): String {
        if (last == null) return if (working) "Working" else "Nothing here yet"
        val n = last.steps
        val count = if (n == 1) "1 step" else "$n steps"
        if (!working) return if (n == 0) "Done" else "Done \u00b7 $count"
        val now = when (active?.kind) {
            Kind.BROWSER -> if (active.flag) "Searching the web" else if (active.live) active.now.ifBlank { "Using the browser" } else "Reading a page"
            Kind.FILE -> if (active.flag) "Writing a file" else "Reading a file"
            Kind.LOG -> active.rows.lastOrNull()?.let { if (it.command) "Running a command" else it.text } ?: "Working"
            Kind.ANSWER -> "Writing the answer"
            else -> "Thinking"
        }
        return if (n == 0) now else "$now \u00b7 $count"
    }

    private fun match(m: Msg, name: String, results: List<ToolTrail.Result>, used: BooleanArray): ToolTrail.Result? {
        var hit = results.indices.firstOrNull { !used[it] && results[it].exact == m.meta.trim() }
        if (hit == null) hit = results.indices.firstOrNull { !used[it] && toolOf(results[it].exact) == name && results[it].at >= m.at }
        hit?.let { used[it] = true }
        return hit?.let { results[it] }
    }

    /** A page result → (title, text). The first short line is the title when the result starts with one. */
    fun page(out: String): Pair<String, String> {
        val t = out.trim()
        if (t.isEmpty()) return "" to ""
        val first = t.lineSequence().first().trim()
        val titled = first.removePrefix("Title:").removePrefix("title:").removePrefix("# ").trim()
        return if (first.length <= 120 && t.contains('\n')) titled to t.substringAfter('\n').trim() else "" to t
    }

    /**
     * 0.2.4: the browser card's "now" line, in plain words with the thing acted on: "Searching “restaurants Ndola”",
     * "Typing “restaurants” on google.com", "Clicking “Directions”". A saved secret is never shown ({{secret:…}}).
     */
    fun browserDoing(name: String, a: Map<String, String>, running: Boolean, site: String = ""): String {
        fun q(k: String) = unquote(a[k] ?: "").takeIf { it.isNotBlank() && it != "null" }
        fun quoted(s: String) = "\u201c" + clip(s.replace(Regex("\\s+"), " "), 40) + "\u201d"
        fun v(now: String, done: String) = if (running) now else done
        val on = if (site.isBlank()) "" else " on $site"
        return when (name) {
            "browser_open" -> {
                val u = q("url").orEmpty()
                if (u.isBlank()) v("Opening a page", "Opened a page")
                else if (looksLikeUrl(u)) v("Opening ${host(u)}", "Opened ${host(u)}")
                else v("Searching ${quoted(u)}", "Searched ${quoted(u)}")
            }
            "browser_type" -> {
                val t = q("text").orEmpty()
                val shown = if (t.contains("{{secret")) "a saved detail" else quoted(t)
                val submit = a["submit"]?.trim()?.lowercase() == "true"
                if (submit && !t.contains("{{secret")) v("Searching$on for $shown", "Searched$on for $shown") else v("Typing $shown$on", "Typed $shown$on")
            }
            "browser_click" -> q("text")?.let { v("Clicking ${quoted(it)}", "Clicked ${quoted(it)}") } ?: q("number")?.let { v("Clicking item $it$on", "Clicked item $it$on") } ?: v("Clicking$on", "Clicked$on")
            "browser_select" -> q("option")?.let { v("Choosing ${quoted(it)}", "Chose ${quoted(it)}") } ?: v("Choosing an option", "Chose an option")
            "browser_key" -> v("Pressing ${q("key") ?: "a key"}", "Pressed ${q("key") ?: "a key"}")
            "browser_scroll" -> if (q("direction") == "up") v("Scrolling up", "Scrolled up") else v("Scrolling down", "Scrolled down")
            "browser_find" -> q("query")?.let { v("Finding ${quoted(it)}", "Found ${quoted(it)}") } ?: v("Finding words", "Found words")
            "browser_wait" -> v("Waiting for the page", "Waited for the page")
            "browser_back" -> v("Going back", "Went back")
            "browser_tabs" -> v("Checking tabs", "Checked tabs")
            "browser_show" -> v("Showing you the page", "Showed you the page")
            "browser_secure_input" -> v("Waiting for your sign-in", "Got your sign-in")
            else -> v("Reading the page$on", "Read the page$on")
        }
    }

    /** "https://x.y/z", "x.y/z" or "maps.google.com" — not search words. */
    fun looksLikeUrl(s: String): Boolean {
        val t = s.trim()
        return t.isNotEmpty() && !t.contains(' ') && (t.contains("://") || Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(/.*)?$").matches(t))
    }

    fun host(url: String): String = url.substringAfter("://").substringBefore('/').removePrefix("www.").ifBlank { url }

    /** Markdown marks off, for a card that is read at a glance. */
    fun plain(s: String): String = s.replace(Regex("(?m)^#{1,6}\\s*"), "").replace("**", "").replace("__", "").replace("`", "").trim()

    fun clip(s: String, max: Int): String = if (s.length > max) s.take(max - 1).trimEnd() + "\u2026" else s

    private fun unquote(s: String): String = s.trim().removeSurrounding("\"").trim()
}
