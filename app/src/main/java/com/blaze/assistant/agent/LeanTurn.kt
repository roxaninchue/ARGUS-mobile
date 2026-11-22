package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 0.55.0 lean harness (docs/LEAN-HARNESS.md; Finn approved the design 2026-11-22): like opencode's harness for free
 * models. A main-chat turn sends
 *  - a short, STABLE system prompt that lives on the phone ([PROMPT]: byte-identical turn after turn, so providers cache
 *    it; only a small live tail follows it),
 *  - [CORE] (19 tools, 0.55.5 adds the team and add_calendar_event: alarms, timers, messages, weather and the other everyday tools never wait on a lookup), the phone's [TOP_N] most-used other tools
 *    ([top]), and the two catalog tools [FIND] (`find_tools`) and [LOAD] (`load_skill`): about 12-16 tools instead of ~50.
 * Every other tool and skill waits in blaze.db ([LeanCatalog]) and joins from the next step when the model asks:
 * `find_tools` ([find], non-suspend: it only searches and marks names) adds what it found to this turn's [State.found], and
 * ToolPick sends those too. `load_skill` is a case in Agent.runToolInner's `when` (it reads the skill like use_skill).
 *
 * Nothing here lives in Agent.runLoop (the method is at the JVM's 64 KB limit): the turn's [State] is made in
 * Agent.requestMessages, carried on the turn's [CallState] (forks share it) and read by ToolPick.core / ToolPick.pick and
 * LlmClient (the "· lean" route label). Background agents, specialists, routines and the chat lane never go lean.
 *
 * Pure Kotlin + org.json apart from [enabled] (Settings, guarded); LeanTurnTest.
 */
object LeanTurn {
    const val FIND = "find_tools"
    const val LOAD = "load_skill"
    const val PREF = "lean_harness"
    const val TOP_N = 4
    const val FIND_MAX = 6
    const val SKILL_MAX = 3
    const val TAG = " \u00b7 lean"

    /** Sent on every lean turn. Alarms, timers and messages are here so they never wait on a lookup. */
    val CORE = listOf("ask_user", "web_search", "fetch_page", "memory_search", "memory_save",
        "set_alarm", "set_timer", "message_contact", "calendar_events", "open_app",
        // 0.55.2 (Finn 18:00: "Weather in Lusaka" web-searched weather.com and couldn't confirm; it worked before): the
        // everyday tools the full list always sent (ToolPick.ESSENTIAL / HARNESS / ALWAYS_GROUPS) ride every lean turn too.
        "get_weather", "get_location", "create_routine", "device_status", "read_screen", "call_control",
        // 0.55.5 (Finn 19:57: "Set up my life team" never found agent_create; "add to my calendar" sat behind a lookup too):
        // the team (see / add bots) and adding a calendar event ride every lean turn. The model decides when to use them.
        "agents_list", "agent_create", "add_calendar_event")

    /**
     * 0.55.2: tools the full list always sent that lean leaves behind find_tools on purpose. Every always-sent tool must be
     * in [CORE] or here (LeanParityTest), so lean can never drop an everyday tool again without a decision.
     */
    val BEHIND_FIND = setOf("load_tools", "find_tool", "remember", "run_plan", "search_in_app", "blaze_now", "open_view",
        "save_file", "make_document", "delegate", "http_request", "run_shell", "run_code", "file_read", "file_write", "file_edit",
        "file_list", "browser_open", "browser_read", "browser_click", "browser_type", "generate_image", "request_secret",
        "blaze_self", "design",
        // 0.55.5: the old ToolRouter core and the schedule / study groups (always sent before lean). Each is one find_tools
        // away, by words or by its area's name (find_tools lists every area: [areas]). LeanTurnTest keeps this list complete.
        "forget", "memory_update", "memory_delete", "memory_merge", "about_blaze", "jarvis_mode", "bank_mode", "follow_ups",
        "blaze_docs_search", "blaze_docs_read", "blazerouter", "processes", "process", "recall_memory", "list_apps", "blaze_camera",
        "self_test", "goal", "use_skill", "find_skill", "search_past_chats", "run_skill", "search_chat", "read_turn", "read_artifact",
        "update_brief", "project_docs_search", "project_docs_read", "cache_toc", "cache_read", "cache_search", "cache_summarize",
        "pdf_page", "phone_task", "start_agent", "list_agents", "check_agent", "message_agent", "cancel_agent", "stage_show",
        "request_app_connection", "done_job", "create_task", "update_task", "gcal_events", "gcal_create", "list_routines",
        "delete_routine", "schedule_command", "create_trigger", "list_triggers", "delete_trigger", "move_event", "delete_event",
        "flashcards")

    /**
     * 0.55.5 (Finn 20:02: "make sure nothing else is missing, we cannot keep going back and forth with lost tools"): every
     * area of tools the app has (ToolRouter's groups and ToolPick's cues, wired by [LeanAreas]). find_tools' description
     * lists them all, so the model always knows what exists, and find_tools with an area's name returns that area's tools
     * (the model picks the area; nothing reads the user's words). This replaces the group list load_tools used to carry.
     */
    data class Area(val id: String, val about: String, val has: (String) -> Boolean)
    @Volatile var areas: List<Area> = emptyList()
    const val AREA_MAX = 12
    val META = listOf(FIND, LOAD)

    /** Never counted as "most used" (they're already sent, or they're the catalog's own). */
    private val NOT_TOP = (CORE + META + listOf("find_tool", "load_tools", "use_skill")).toSet()

    /** The short, stable system prompt (on the phone, byte-identical every turn). Keep it under [PROMPT_MAX] chars. */
    val PROMPT = """
You are ${com.blaze.assistant.BrandName.NAME}, an AI assistant that runs on the user's Android phone and acts for them.
HOW YOU WORK: you have a few core tools now. For anything else (phone settings like Bluetooth or Wi-Fi, the screen, apps, files, the terminal, the browser, music, GitHub, email, your own settings and more) call $FIND with what you need in plain words; the tools it finds are yours from your next step. For work with known steps (essays, flashcards, slides, code review, research, designs) call $FIND or $LOAD and follow the skill. Never say you can't do something before $FIND came back empty.
YOUR TEAM: you lead the user's team of bots. agents_list shows it; agent_create adds a bot (a researcher, a writer, a coach…) for work that comes back. When they ask for a bot or a team, suggest the bots and create the ones they agree to.
WHEN A STEP FAILS: say in a few words what failed, then try another way or offer a concrete choice. Never answer a failure with a vague question.
Alarms, timers and messages: call set_alarm, set_timer or message_contact straight away. Weather: call get_weather (never web search for weather) and quote its source and time.
Act, then report what the tool results say. Claim success only from a result that says so. Ask with ask_user only when a real choice is the user's (which contact, which account); check facts yourself.
VOICE: a sharp, warm friend: contractions, answer first, plain words, no filler or stock lines ("How can I help?"). Fit the length to the ask: a line for chat, more for research and how-tos.
GREETINGS: "hi", "hey", "good morning" get a warm hello (by name when you know it) plus one specific, useful thing: what's next today from the [context] block, or an offer that fits what they've been doing. Never answer a greeting with a bare "Hi".
TIME: the CURRENT LOCAL TIME line below is exact. Plan from it: never offer a time today that has already passed as if it were still ahead. When the user points out the time, your last plan was wrong: own it in a few words and give the corrected plan from now, never just repeat the time.
SCHEDULE: an item marked "(listed twice)" is a duplicate: whenever you mention that day, point it out and offer to delete the extra one.
WHO SAID WHAT: only the user's own messages are what they said or chose. Memory, the [context] block and suggestions (yours or reply chips) are not their words: never say "you chose" or "you said" from those.
Reply in the user's language. Never show tool names, JSON or code unless asked.
""".trim()

    const val PROMPT_MAX = 4_000

    /** Settings "lean_harness" (default on). */
    fun enabled(): Boolean = try { Settings.bool(PREF, true) } catch (_: Throwable) { true }

    /** Which turns go lean: the main chat's own turns, not background agents, specialists or routines. */
    fun eligible(on: Boolean, agentId: String?, specialist: Boolean, routine: Boolean): Boolean = on && agentId == null && !specialist && !routine

    /** One lean turn. [turn]: the turn's key (Agent's promptTurn). [top]: its most-used tools, fixed for the turn. */
    class State(val turn: Long, val top: List<String>) {
        /** Tools find_tools found this turn: sent from the next step, kept to the end of the turn. */
        val found: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** Skills load_skill read this turn. */
        val skills: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** Tool names this turn may send (core, top, catalog tools, found). */
        fun names(): Set<String> = LinkedHashSet<String>().apply { addAll(CORE); addAll(top); addAll(META); addAll(found) }
    }

    /** The phone's whole tool list as Agent.tools last built it (find_tools searches it; ToolPick still decides what is sent). */
    @Volatile var lastAll: JSONArray? = null

    /** The lean state of the turn the caller runs in, or null (not a lean turn). */
    fun current(): State? = CallState.current().lean

    /**
     * The turn's state: kept for every step of turn [turn], new when a new turn starts, null (and cleared) when the turn
     * isn't lean. Called from Agent.requestMessages before ToolPick runs for the step.
     */
    fun begin(st: CallState, turn: Long, lean: Boolean, top: () -> List<String>): State? {
        if (!lean) { st.lean = null; return null }
        st.lean?.takeIf { it.turn == turn }?.let { return it }
        return State(turn, try { top() } catch (_: Throwable) { emptyList() }).also { st.lean = it }
    }

    /** The [n] most-used tools from [counts] (calls per tool), not core or catalog tools, only ones this phone has. */
    fun top(counts: Map<String, Int>, available: Set<String>, n: Int = TOP_N): List<String> =
        counts.entries.filter { it.key !in NOT_TOP && it.key in available && it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(n).map { it.key }

    /**
     * The tools a lean step sends: those of [selected] (the turn's already-filtered list, its order kept) whose name is in
     * the turn's [State.names] or [used] (tools this chat already called). [selected] decides what may be sent at all
     * (Plan mode, private chats, a bot's subset), so nothing outside it can ride along.
     */
    fun filter(selected: JSONArray, st: State, used: Set<String>): JSONArray {
        val want = st.names() + used
        return JSONArray().apply {
            for (i in 0 until selected.length()) {
                val n = selected.optJSONObject(i)?.optJSONObject("function")?.optString("name") ?: continue
                if (n in want) put(selected.opt(i))
            }
        }
    }

    /** "Argus Cloud · direct" + " · lean" on a lean turn (the route line the reply shows). */
    fun tag(): String = if (current() != null) TAG else ""

    // ---------------- the live tail and the whole message ----------------

    /** The lean system message: [PROMPT] first (cached prefix), then the stable rules the app adds, then the live tail. */
    fun system(persona: String, rules: String, name: String?, lang: String, memory: String, skillsHint: String = "", now: String = "", extra: String = ""): String = buildString {
        if (persona.isNotBlank()) append(persona.trim()).append('\n')
        append(PROMPT)
        if (rules.isNotBlank()) append('\n').append(rules.trim())
        if (skillsHint.isNotBlank()) append('\n').append(skillsHint.trim())
        // live tail: changes per user / turn, after everything cacheable
        if (!name.isNullOrBlank()) append("\nThe user's name is ").append(name.trim()).append('.')
        if (lang.isNotBlank()) append('\n').append(lang.trim())
        if (memory.isNotBlank()) append('\n').append(memory.trim().take(MEMORY_MAX))
        // 0.55.1 (Finn, 11:05: "optimal time to study?" got 9:00-12:00): the exact local time, zone and locale every turn,
        // as the full prompt's CURRENT LOCAL TIME tail had it, after everything cacheable. (The [context] block with today's
        // schedule follows this message, added by Agent.requestMessages for every turn, lean or not.)
        if (now.isNotBlank()) append('\n').append(now.trim())
        if (extra.isNotBlank()) append('\n').append(extra.trim().take(EXTRA_MAX))
    }

    const val MEMORY_MAX = 1_500
    const val EXTRA_MAX = 400

    private val nowFmt = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", java.util.Locale.ENGLISH)

    /** "CURRENT LOCAL TIME: Sunday 22 November 2026, 11:05, Africa/Lusaka (UTC+02:00); locale en-ZM. …" (pure; tests pass a clock). */
    fun nowLine(now: java.time.ZonedDateTime, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        val off = now.offset.id.let { if (it == "Z") "+00:00" else it }
        return "CURRENT LOCAL TIME: ${now.format(nowFmt)}, ${now.zone.id} (UTC$off); locale ${locale.toLanguageTag()}. " +
            "This is exact: answer any time, date or day question from it and plan the rest of today from it."
    }

    // ---------------- find_tools ----------------

    fun findDef(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", FIND)
        .put("description", "Search every tool and skill on this phone by what you need, in plain words (\"turn on bluetooth\", \"read my screen\", \"open a github issue\", \"make flashcards\"), " +
            "or pass an area's name to get that area's tools. The tools it finds are available from your next step; skills are read with $LOAD." + areaMenu())
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("description", "What you need to do, in plain words")))
            .put("required", JSONArray(listOf("query")))))

    /** " Areas: team (…); device (…); …" from [areas] (empty before they're wired). */
    fun areaMenu(): String = areas.takeIf { it.isNotEmpty() }?.joinToString("; ", " Areas: ", ".") { "${it.id} (${it.about})" }.orEmpty()

    /** The area [q] names ("team", "area: team"), or null. */
    fun areaOf(q: String): Area? { val k = q.trim().lowercase().removePrefix("area").trim(' ', ':', '=').trim(); return areas.firstOrNull { it.id == k } }

    fun loadSkillDef(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", LOAD)
        .put("description", "Read one skill's full steps by its name (from $FIND), then follow them.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("name", JSONObject().put("type", "string").put("description", "The skill's name")))
            .put("required", JSONArray(listOf("name")))))

    /** One hit of [find]. */
    data class Hit(val kind: String, val name: String, val summary: String)

    private val wordRe = Regex("[a-z0-9]{2,}")
    private val stop = setOf("the", "my", "a", "an", "to", "on", "of", "for", "in", "and", "or", "me", "it", "is", "with", "please", "can", "you", "turn", "do", "this", "that")

    /** Words that mean the same tool family (the query's words are extended by these before scoring). */
    private val SYN = mapOf("bluetooth" to "device setting toggle", "wifi" to "device setting toggle", "wi" to "device setting",
        "torch" to "flashlight device setting", "flashlight" to "device setting", "volume" to "device setting sound", "brightness" to "device setting display",
        "screen" to "read tap scroll", "text" to "sms message", "sms" to "message send", "email" to "gmail", "mail" to "gmail",
        "song" to "music", "music" to "media play", "photo" to "gallery camera image", "picture" to "image gallery", "file" to "file",
        "command" to "shell terminal run", "code" to "run code terminal", "website" to "browser open", "site" to "browser open",
        "remind" to "routine reminder calendar", "reminder" to "routine calendar", "call" to "call contact dial", "contact" to "contact find")

    private fun terms(q: String): List<String> {
        val base = wordRe.findAll(q.lowercase().replace('_', ' ')).map { it.value }.filter { it !in stop }.toList()
        return (base + base.flatMap { SYN[it]?.split(' ') ?: emptyList() }).distinct()
    }

    /** Local score of one tool for the query terms: name words x3, description words x1. */
    fun score(terms: List<String>, name: String, desc: String): Int {
        if (terms.isEmpty()) return 0
        val nameWords = name.lowercase().split('_', '-', '.', ':').toSet()
        val d = desc.lowercase()
        return terms.sumOf { t -> (if (t in nameWords || name.lowercase() == t) 3 else 0) + (if (Regex("\\b" + Regex.escape(t)).containsMatchIn(d)) 1 else 0) }
    }

    /**
     * find_tools: searches the catalog ([catalog]: name, summary, kind) and the turn's real tool list ([all]: only tools
     * this phone can send), marks the tools found on [st] (sent from the next step), and answers in a few lines.
     * Non-suspend and quick (no network); never throws.
     */
    fun find(query: String, all: JSONArray, catalog: ((String, Int) -> List<LeanCatalog.Entry>)?, st: State?, max: Int = FIND_MAX): String {
        val q = query.trim()
        if (q.isEmpty()) return "Say what you need in a few words, e.g. find_tools query=\"turn on bluetooth\"."
        val real = LinkedHashMap<String, String>()
        for (i in 0 until all.length()) all.optJSONObject(i)?.optJSONObject("function")?.let { f -> real[f.optString("name")] = f.optString("description") }
        // 0.55.5: an area by its name → that area's tools (only ones this phone has).
        areaOf(q)?.let { a ->
            val picked = real.keys.filter { a.has(it) && it != FIND && it != LOAD }.take(AREA_MAX)
            if (picked.isNotEmpty()) {
                st?.found?.addAll(picked)
                val already = st?.names().orEmpty()
                return buildString {
                    append("Tools in ").append(a.id).append(" (available from your next step; call one now):")
                    for (n in picked) append("\n- ").append(n).append(if (n in already) " (already loaded)" else "").append(": ").append(LeanCatalog.firstSentence(real[n].orEmpty()))
                }
            }
        }
        val t = terms(q)
        val scored = HashMap<String, Int>()
        val skills = LinkedHashMap<String, String>()
        try {
            catalog?.invoke(q + " " + t.joinToString(" "), max * 3)?.forEachIndexed { i, e ->
                if (e.kind == LeanCatalog.KIND_SKILL) { if (skills.size < SKILL_MAX) skills.putIfAbsent(e.name, e.summary) }
                else if (e.name in real) scored.merge(e.name, 40 - i, ::maxOf)
            }
        } catch (_: Throwable) { /* the local scoring below still answers */ }
        for ((n, d) in real) { val s = score(t, n, d); if (s > 0) scored.merge(n, s * 4, Int::plus) }
        val already = st?.names().orEmpty()
        val picked = scored.entries.filter { it.key != FIND && it.key != LOAD }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(max).map { it.key }
        st?.found?.addAll(picked)
        if (picked.isEmpty() && skills.isEmpty()) return "No tool or skill matched \"$q\". Try other words (what you want to happen, e.g. \"switch on bluetooth\", \"send a whatsapp\"), or do it another way."
        return buildString {
            if (picked.isNotEmpty()) {
                append("Tools for \"").append(q).append("\" (available from your next step; call one now):")
                for (n in picked) append("\n- ").append(n).append(if (n in already) " (already loaded)" else "").append(": ").append(LeanCatalog.firstSentence(real[n].orEmpty()))
            }
            if (skills.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("Skills (read one with $LOAD name=…):")
                for ((n, s) in skills) append("\n- ").append(n).append(": ").append(s)
            }
        }
    }
}
