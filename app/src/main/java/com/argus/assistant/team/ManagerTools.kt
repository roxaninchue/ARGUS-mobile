package com.blaze.assistant.team

import com.blaze.assistant.agent.TeamHooks

/**
 * P1-TM-04: what the bot editor and the team tools both write: one bot's editable fields (A-team agentSheet + agent_create
 * / agent_update; Bot HQ mode). Pure. [problems] says what is wrong in the user's words (empty = fine); [toBot] turns a
 * draft into a new bot or the edit of an existing one.
 *
 * Limits (A-team): name 60 ([BotRules.NAME_MAX]), label (A-team "role") 60, email 120 and `x@y.z` or empty, portfolio
 * 600, instructions (the SOUL) 4000. Effort low / medium / high / xhigh ("max" reads as xhigh). Mode personal / teammate.
 * Face 0..5 in [com.blaze.assistant.ui.argus.OrbFace] order ([FACES]); null = picked from the name. Colour any `#rrggbb`;
 * the swatches are A-team COLORS ([COLORS], the same as ArgusTokens.BOT_COLORS). Reports to the Manager or another bot
 * on the team: never itself, never in a loop.
 */
data class BotDraft(
    val name: String = "",
    val label: String = "",
    val email: String = "",
    val portfolio: String = "",
    val instructions: String = "",
    val face: Int? = null,
    val color: String = COLORS[0],
    val reportsTo: String = BotRules.MANAGER_ID,
    val effort: String = "medium",
    val mode: String = "personal",
    val toolGroups: Set<String> = DEFAULT_GROUPS,
    val skills: List<String> = emptyList(),
) {
    companion object {
        const val LABEL_MAX = 60
        const val EMAIL_MAX = 120
        const val PORTFOLIO_MAX = 600
        const val INSTRUCTIONS_MAX = 4000

        val DEFAULT_GROUPS = setOf("web", "memory", "jobs")
        val EFFORTS = listOf("low", "medium", "high", "xhigh")
        val MODES = listOf("personal", "teammate")
        /** A-team BOT_FACES, in OrbFace order. */
        val FACES = listOf("Orbit", "Tilt", "Steep", "Pure", "Twin", "Polar")
        /** A-team COLORS (app.js), the bot swatches. */
        val COLORS = listOf("#8b5cf6", "#22c55e", "#f59e0b", "#ef4444", "#06b6d4", "#6366f1", "#3b82f6", "#cbd5e1")
        /** ArgusTokens.BOT_COLORS names, in [COLORS] order. */
        val COLOR_NAMES = listOf("violet", "green", "amber", "red", "cyan", "indigo", "blue", "silver")

        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val HEX = Regex("^#?[0-9a-fA-F]{6}$")

        /** "High", "max", "x-high" → the stored word; null when it isn't one. */
        fun effortOf(s: String?): String? {
            val t = s?.trim()?.lowercase()?.replace(Regex("[\\s_-]+"), "") ?: return null
            return when (t) { "max", "maximum", "xhigh", "extrahigh" -> "xhigh"; "med" -> "medium"; else -> t.takeIf { it in EFFORTS } }
        }

        fun modeOf(s: String?): String? = s?.trim()?.lowercase()?.takeIf { it in MODES }

        /** "#3B82F6", "3b82f6" or a swatch name ("blue") → "#3b82f6"; null when it is none of these. */
        fun colorOf(s: String?): String? {
            val t = s?.trim()?.lowercase() ?: return null
            COLOR_NAMES.indexOf(t).takeIf { it >= 0 }?.let { return COLORS[it] }
            return if (HEX.matches(t)) "#" + t.removePrefix("#") else null
        }

        /** 0..5, a face name ("Twin") or null. */
        fun faceOf(s: Any?): Int? = when (s) {
            is Number -> s.toInt().takeIf { it in FACES.indices }
            is String -> s.trim().let { w -> w.toIntOrNull()?.takeIf { it in FACES.indices } ?: FACES.indexOfFirst { it.equals(w, true) }.takeIf { it >= 0 } }
            else -> null
        }

        /** A new bot's colour: the next swatch by roster size (A-team addAgent). */
        fun nextColor(rosterSize: Int) = COLORS[((rosterSize % COLORS.size) + COLORS.size) % COLORS.size]

        /** The draft of an existing bot ([soul] = its stored instructions). */
        fun of(b: Bot, soul: String?): BotDraft = BotDraft(
            name = b.name, label = b.label, email = b.email, portfolio = b.portfolio, instructions = soul.orEmpty(),
            face = b.avatar.face, color = colorOf(b.avatar.color) ?: b.avatar.color, reportsTo = b.reportsTo ?: BotRules.MANAGER_ID,
            effort = effortOf(b.engine.model.effort) ?: "medium", mode = modeOf(b.mode) ?: "personal",
            toolGroups = b.toolGroups, skills = b.skills)

        /** True when [lead] reports (directly or up the chain) to [selfId]: making [selfId] report to it would loop. */
        fun loops(selfId: String, lead: String, team: Collection<Bot>): Boolean {
            val by = team.associateBy { it.id }
            var cur: String? = lead
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) {
                if (cur == selfId) return true
                cur = by[cur]?.reportsTo
            }
            return false
        }
    }

    /** Trimmed, and cut to the limits (A-team slices what a tool sends; the editor shows [problems] instead). */
    fun clipped(): BotDraft = copy(name = name.trim().take(BotRules.NAME_MAX), label = label.trim().take(LABEL_MAX),
        email = email.trim().take(EMAIL_MAX), portfolio = portfolio.trim().take(PORTFOLIO_MAX), instructions = instructions.trim().take(INSTRUCTIONS_MAX))

    /** What is wrong, in the user's words, first problem first. [team] = every bot (the Manager included). */
    fun problems(team: Collection<Bot>, selfId: String? = null): List<String> {
        val out = ArrayList<String>()
        BotRules.nameProblem(name, team, selfId)?.let { out += it }
        if (label.trim().length > LABEL_MAX) out += "Keep the label under $LABEL_MAX characters."
        val e = email.trim()
        if (e.isNotEmpty() && (e.length > EMAIL_MAX || !EMAIL.matches(e))) out += "That email doesn't look right."
        if (portfolio.trim().length > PORTFOLIO_MAX) out += "Keep the portfolio under $PORTFOLIO_MAX characters."
        if (instructions.trim().length > INSTRUCTIONS_MAX) out += "Keep the instructions under $INSTRUCTIONS_MAX characters."
        if (face != null && face !in FACES.indices) out += "Pick one of the six looks."
        if (colorOf(color) == null) out += "Pick a colour like #3b82f6."
        if (effort !in EFFORTS) out += "Effort is low, medium, high or xhigh."
        if (mode !in MODES) out += "Mode is personal or teammate."
        val lead = reportsTo.trim()
        when {
            lead == BotRules.MANAGER_ID -> Unit
            selfId != null && lead == selfId -> out += "A bot can't report to itself."
            team.none { it.id == lead && it.enabled } -> out += "Pick who it reports to from the team."
            selfId != null && loops(selfId, lead, team) -> out += "That would make a loop: ${team.first { it.id == lead }.name} already reports to this bot."
        }
        return out
    }

    /** The bot this draft makes ([base] null: a new CUSTOM bot; the store gives it its id) or the edit of [base]. */
    fun toBot(base: Bot?, now: Long): Bot {
        val col = colorOf(color) ?: color
        if (base == null) return Bot(
            id = "", kind = BotKind.CUSTOM, name = name.trim(), label = label.trim(), avatar = Avatar("orb", col, face = face),
            mode = mode, soulPath = "", skills = skills, toolGroups = toolGroups,
            engine = EngineSpec(model = ModelPolicy(effort = effort)), reportsTo = reportsTo.trim().ifEmpty { BotRules.MANAGER_ID },
            createdAt = now, u = now, email = email.trim(), portfolio = portfolio.trim())
        return base.copy(name = name.trim(), label = label.trim(), avatar = base.avatar.copy(color = col, face = face), mode = mode,
            skills = skills, toolGroups = toolGroups, engine = base.engine.copy(model = base.engine.model.copy(effort = effort)),
            reportsTo = reportsTo.trim().ifEmpty { BotRules.MANAGER_ID }, email = email.trim(), portfolio = portfolio.trim())
    }
}

/**
 * P1-TM-04: bot create / edit / delete for both the editor and the team tools, so the two follow one set of rules.
 * [create] inserts and saves the instructions as the bot's SOUL when there are any; [update] refuses the Manager (locked)
 * and saves the SOUL only when it changed; [delete] is [TeamStore.delete] (never the Manager; a domain bot is switched
 * off, not removed). Disk I/O: call off the main thread.
 */
class TeamEdits(private val store: TeamStore, private val clock: () -> Long = { System.currentTimeMillis() }) {

    fun create(draft: BotDraft, by: String): TeamStore.Result {
        draft.problems(store.all()).firstOrNull()?.let { return TeamStore.Result.Refused(it) }
        val made = store.insert(draft.toBot(null, clock()))
        if (made !is TeamStore.Result.Ok) return made
        val soul = draft.instructions.trim()
        if (soul.isEmpty()) return made
        return store.saveSoul(made.bot.id, soul, by).let { if (it is TeamStore.Result.Ok) it else made }
    }

    fun update(id: String, draft: BotDraft, by: String): TeamStore.Result {
        if (id == BotRules.MANAGER_ID) return TeamStore.Result.Refused(locked())
        val old = store.get(id) ?: return TeamStore.Result.Refused("No bot $id.")
        draft.problems(store.all(), id).firstOrNull()?.let { return TeamStore.Result.Refused(it) }
        val saved = store.update(draft.toBot(old, clock()))
        if (saved !is TeamStore.Result.Ok) return saved
        val soul = draft.instructions.trim()
        if (soul == (store.soul(id) ?: "").trim()) return saved
        return store.saveSoul(id, soul, by).let { if (it is TeamStore.Result.Ok) it else saved }
    }

    fun delete(id: String): TeamStore.Result = store.delete(id)

    companion object {
        fun locked() = "${BotRules.MANAGER_NAME} is locked. Its name, label, email, portfolio, instructions and look can't be changed."
    }
}

/** P1-TM-04: the user's messages in a chat, oldest first (the delete confirmation reads the first one after the ask). */
fun interface ChatLog {
    data class Line(val text: String, val at: Long)
    fun userMessages(chatId: String): List<Line>
}

/**
 * P1-TM-04: the team's shared notes (A-team `remember` scope team). On the phone a note is an AM memory line starting
 * "Team note: " until mem_scope (P3-AG-01); [remove] deletes that one line by id (the note_team Undo).
 */
interface TeamNotes {
    /** [reply] = the memory's own answer ("Remembered: …"); [id] = the new line, or null when nothing new was saved. */
    data class Saved(val reply: String, val id: Long?)
    fun add(text: String): Saved
    fun remove(id: Long): Boolean
}

/**
 * P1-TM-04: the team tools (05 §8 `team` group), ported from A-team manager.js (t_agents_list / t_agent_create /
 * t_agent_update / t_agent_delete), chat.js (update_agent) and the `remember` scope team (note_team). Pure: arguments and
 * results are JSON / plain text, failures start with "Error:" (JobTools style), so ManagerToolsTest calls it directly.
 *
 *  - agents_list, agent_create, agent_update, agent_delete: the Manager only ([MANAGER_ONLY]).
 *  - agent_update and update_agent never change the Manager (locked). update_agent changes the caller, or with `for`
 *    a bot that reports to it: name, role (label), email, portfolio.
 *  - agent_delete asks first: the first call sets [pending] and answers "NOT deleted yet"; a second call with
 *    `confirm: true` deletes only when the user's first message after the ask is a yes ([YES]); any other answer clears
 *    the ask and nothing is deleted. An ask lasts [PENDING_MS]. The shell's Delete / Keep card calls [confirmByButton] /
 *    [keep]. Never the Manager.
 *  - note_team: saved through [notes], with an Undo ([onUndo]).
 *
 * [onAsk] gets the pending delete (null when it is answered or dropped); [changed] gets the id of a bot that was created,
 * changed or deleted (the shell reloads the roster).
 */
class ManagerTools(
    private val team: TeamStore,
    private val jobs: JobStore?,
    private val chat: ChatLog,
    private val notes: TeamNotes,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onAsk: (Pending?) -> Unit = {},
    private val onUndo: (label: String, undo: () -> String) -> Unit = { _, _ -> },
    private val changed: (botId: String) -> Unit = {},
) {
    data class Pending(val chatId: String, val botId: String, val name: String, val at: Long)

    companion object {
        val NAMES = setOf("agents_list", "agent_create", "agent_update", "agent_delete", "update_agent", "note_team")
        val MANAGER_ONLY = setOf("agents_list", "agent_create", "agent_update", "agent_delete")
        const val PENDING_MS = 25 * 60_000L
        const val NOTE_PREFIX = "Team note: "
        /** A-team manager.js YES. */
        val YES = Regex("^\\s*(yes|y|yeah|yep|yup|sure|ok|okay|confirm(ed)?|do it|go ahead|delete (it|them|him|her)|please do|yes please)\\b", RegexOption.IGNORE_CASE)
    }

    val edits = TeamEdits(team, clock)

    @Volatile private var pend: Pending? = null

    /** The delete waiting for the user's answer, or null (an ask older than [PENDING_MS] is dropped). */
    fun pending(): Pending? {
        val p = pend ?: return null
        if (clock() - p.at < PENDING_MS) return p
        pend = null; onAsk(null); return null
    }

    private fun fn(name: String, about: String, props: Map<String, Map<String, Any?>>, required: List<String>) = TeamJson.write(linkedMapOf(
        "type" to "function", "function" to linkedMapOf("name" to name, "description" to about,
            "parameters" to linkedMapOf("type" to "object", "properties" to props, "required" to required))))
    private fun str(d: String) = linkedMapOf<String, Any?>("type" to "string", "description" to d)

    private fun fields(): LinkedHashMap<String, Map<String, Any?>> = linkedMapOf(
        "name" to str("Bot name"), "role" to str("Short role, e.g. Research lead"), "email" to str("Email (optional)"),
        "portfolio" to str("What it owns: accounts, projects, clients"), "instructions" to str("How it should work (its instructions)"),
        "face" to str("Look: Orbit, Tilt, Steep, Pure, Twin or Polar"), "color" to str("Colour: #rrggbb or violet, green, amber, red, cyan, indigo, blue, silver"),
        "reports_to" to str("Bot it reports to (default: ${BotRules.MANAGER_NAME})"), "effort" to str("low, medium, high or xhigh"),
        "mode" to str("personal or teammate"))

    fun defs(): List<String> = listOf(
        fn("agents_list", "List every bot on the team (the roster): name, id, role, who it reports to, open jobs.", linkedMapOf(), emptyList()),
        fn("agent_create", "Create a new bot. It reports to you unless you say otherwise.", fields(), listOf("name")),
        fn("agent_update", "Change a bot: name, role, email, portfolio, instructions, look, reports-to, effort or mode. ${BotRules.MANAGER_NAME} itself is locked.",
            linkedMapOf<String, Map<String, Any?>>("agent" to str("Bot name or id")).apply { putAll(fields()) }, listOf("agent")),
        fn("agent_delete", "Delete a bot. The first call only asks the user to confirm; call again with confirm: true after they say yes in their next message. ${BotRules.MANAGER_NAME} can never be deleted.",
            linkedMapOf("agent" to str("Bot name or id"), "confirm" to linkedMapOf<String, Any?>("type" to "boolean", "description" to "true only after the user said yes")), listOf("agent")),
        fn("update_agent", "Change your own name, role, email or portfolio, or those of a bot that reports to you.",
            linkedMapOf("name" to str("New name"), "role" to str("New role"), "email" to str("New email"), "portfolio" to str("New portfolio"),
                "for" to str("Bot to change (default: you)")), emptyList()),
        fn("note_team", "Save a note every bot on the team can read (shared team memory).", linkedMapOf("text" to str("The note")), listOf("text")),
    )

    /** A bot on the roster by id, name or label (case-insensitive). */
    fun bot(nameOrId: String?): Bot? {
        val n = nameOrId?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() } ?: return null
        val all = team.roster()
        return all.firstOrNull { it.id.equals(n, true) } ?: all.firstOrNull { it.name.equals(n, true) } ?: all.firstOrNull { it.label.equals(n, true) }
    }

    private fun isManagerWord(s: String?) = s != null && (s.trim().equals(BotRules.MANAGER_ID, true) || s.trim().lowercase() in BotRules.RESERVED_NAMES)

    fun run(chatId: String, botId: String?, name: String, args: String): String {
        @Suppress("UNCHECKED_CAST")
        val a = (try { TeamJson.read(args.ifBlank { "{}" }) } catch (_: Exception) { null } as? Map<String, Any?>) ?: emptyMap()
        fun s(k: String) = (a[k] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: (a[k] as? Number)?.toString()
        val caller = botId ?: BotRules.MANAGER_ID
        if (name in MANAGER_ONLY && caller != BotRules.MANAGER_ID) return "Error: only ${BotRules.MANAGER_NAME} can use $name."
        return when (name) {
            "agents_list" -> agentsList()
            "agent_create" -> create(a, ::s)
            "agent_update" -> update(a, ::s)
            "agent_delete" -> delete(chatId, s("agent") ?: s("id") ?: s("name"), a["confirm"] == true || s("confirm")?.lowercase() == "true")
            "update_agent" -> updateSelf(caller, ::s)
            "note_team" -> note(s("text") ?: s("note"))
            else -> "Error: unknown team tool $name."
        }
    }

    private fun agentsList(): String {
        val all = team.roster()
        val open = jobs?.open().orEmpty().groupingBy { it.botId }.eachCount()
        val names = all.associate { it.id to it.name }
        return all.joinToString("\n") { b ->
            "- ${b.name} (id ${b.id})" + (if (BotRules.isManager(b)) " [${BotRules.MANAGER_LABEL}, you]" else "") +
                (b.label.takeIf { it.isNotBlank() }?.let { " \u00b7 $it" } ?: "") +
                (if (!BotRules.isManager(b)) " \u00b7 reports to ${names[b.reportsTo] ?: BotRules.MANAGER_NAME}" else "") +
                (if (b.mode == "teammate") " \u00b7 teammate" else "") +
                " \u00b7 ${open[b.id] ?: 0} open jobs" +
                (b.portfolio.takeIf { it.isNotBlank() && !BotRules.isManager(b) }?.let { " \u00b7 owns: ${it.take(80)}" } ?: "")
        }
    }

    /** Applies the tool's fields onto [d]; an Error line when a value can't be read. */
    private fun apply(d: BotDraft, a: Map<String, Any?>, s: (String) -> String?, selfId: String?): Pair<BotDraft, String?> {
        var x = d
        s("name")?.let { x = x.copy(name = it) }
        (s("role") ?: s("label"))?.let { x = x.copy(label = it) }
        s("email")?.let { x = x.copy(email = it) }
        s("portfolio")?.let { x = x.copy(portfolio = it) }
        (s("instructions") ?: s("prompt") ?: s("soul"))?.let { x = x.copy(instructions = it) }
        if (a["face"] != null && a["face"] != "") x = x.copy(face = BotDraft.faceOf(a["face"]) ?: return x to "Error: the look is Orbit, Tilt, Steep, Pure, Twin or Polar.")
        s("color")?.let { x = x.copy(color = BotDraft.colorOf(it) ?: return x to "Error: \"$it\" isn't a colour; use #rrggbb or a swatch name.") }
        s("effort")?.let { x = x.copy(effort = BotDraft.effortOf(it) ?: return x to "Error: effort is low, medium, high or xhigh.") }
        s("mode")?.let { x = x.copy(mode = BotDraft.modeOf(it) ?: return x to "Error: mode is personal or teammate.") }
        (s("reports_to") ?: s("lead"))?.let { w ->
            val lead = if (isManagerWord(w)) BotRules.MANAGER_ID else bot(w)?.id ?: return x to "Error: there's no bot called \"$w\" on the team."
            if (lead == selfId) return x to "Error: a bot can't report to itself."
            x = x.copy(reportsTo = lead)
        }
        return x.clipped() to null
    }

    private fun create(a: Map<String, Any?>, s: (String) -> String?): String {
        val nm = s("name") ?: return "Error: a name is required."
        val (d, err) = apply(BotDraft(name = nm, color = BotDraft.nextColor(team.roster().size)), a, s, null)
        if (err != null) return err
        return when (val r = edits.create(d, "manager")) {
            is TeamStore.Result.Refused -> "Error: ${r.why}"
            is TeamStore.Result.Ok -> {
                changed(r.bot.id)
                val lead = team.get(r.bot.reportsTo ?: BotRules.MANAGER_ID)?.name ?: BotRules.MANAGER_NAME
                "Created ${r.bot.name} (id ${r.bot.id}), reports to $lead" +
                    (if (r.bot.engine.model.effort != "medium") ", effort ${r.bot.engine.model.effort}" else "") +
                    (if (r.bot.mode == "teammate") ", teammate" else "") +
                    (if (d.instructions.isNotBlank()) ", with instructions" else "") + "."
            }
        }
    }

    private fun update(a: Map<String, Any?>, s: (String) -> String?): String {
        val q = s("agent") ?: s("id") ?: s("for") ?: return "Error: which bot? Give its name."
        if (isManagerWord(q)) return "Error: ${TeamEdits.locked()}"
        val b = bot(q) ?: return "Error: there's no bot called \"$q\" on the team."
        if (BotRules.isManager(b)) return "Error: ${TeamEdits.locked()}"
        val before = BotDraft.of(b, team.soul(b.id))
        val (d, err) = apply(before, a, s, b.id)
        if (err != null) return err
        val ch = buildList {
            if (d.name != before.name) add("name"); if (d.label != before.label) add("role"); if (d.email != before.email) add("email")
            if (d.portfolio != before.portfolio) add("portfolio"); if (d.instructions != before.instructions.trim()) add("instructions")
            if (d.face != before.face || d.color != before.color) add("look"); if (d.reportsTo != before.reportsTo) add("reports to")
            if (d.effort != before.effort) add("effort"); if (d.mode != before.mode) add("mode")
        }
        if (ch.isEmpty()) return "Error: nothing to change."
        return when (val r = edits.update(b.id, d, "manager")) {
            is TeamStore.Result.Refused -> "Error: ${r.why}"
            is TeamStore.Result.Ok -> { changed(b.id); "${r.bot.name}: ${ch.joinToString(", ")} changed." }
        }
    }

    private fun updateSelf(caller: String, s: (String) -> String?): String {
        val who = s("for") ?: s("agent")
        val me = team.get(caller)
        val tgt = if (who == null) me else (if (isManagerWord(who)) team.get(BotRules.MANAGER_ID) else bot(who))
            ?.takeIf { it.id == caller || it.reportsTo == caller }
        if (tgt == null) return "Error: you can change only yourself or a bot that reports to you."
        if (BotRules.isManager(tgt)) return "Error: ${BotRules.MANAGER_NAME} is locked. Its name, role, email and portfolio can't be changed."
        val ch = LinkedHashMap<String, String>()
        s("name")?.let { ch["name"] = it.removeSurrounding("\"").take(BotRules.NAME_MAX) }
        s("role")?.let { ch["role"] = it.removeSurrounding("\"").take(BotDraft.LABEL_MAX) }
        s("email")?.let { ch["email"] = it.removeSurrounding("\"").take(BotDraft.EMAIL_MAX) }
        s("portfolio")?.let { ch["portfolio"] = it.removeSurrounding("\"").take(BotDraft.PORTFOLIO_MAX) }
        if (ch.isEmpty()) return "Error: nothing to change. Give a name, role, email or portfolio."
        val before = BotDraft.of(tgt, team.soul(tgt.id))
        val d = before.copy(name = ch["name"] ?: before.name, label = ch["role"] ?: before.label, email = ch["email"] ?: before.email,
            portfolio = ch["portfolio"] ?: before.portfolio)
        return when (val r = edits.update(tgt.id, d, if (caller == BotRules.MANAGER_ID) "manager" else "bot:$caller")) {
            is TeamStore.Result.Refused -> "Error: ${r.why}"
            is TeamStore.Result.Ok -> {
                changed(tgt.id)
                (if (tgt.id == caller) "Your " else "${tgt.name}'s ") + ch.keys.joinToString(", ") + " changed: " +
                    ch.entries.joinToString("; ") { (k, v) -> "$k = $v" }
            }
        }
    }

    private fun delete(chatId: String, q: String?, confirm: Boolean): String {
        if (q == null) return "Error: which bot? Give its name."
        if (isManagerWord(q)) return "Error: ${BotRules.MANAGER_NAME} can never be deleted."
        val b = bot(q) ?: return "Error: there's no bot called \"$q\" on the team."
        if (!BotRules.canDelete(b.id)) return "Error: ${BotRules.MANAGER_NAME} can never be deleted."
        val p = pending()
        if (confirm && p != null && p.botId == b.id && p.chatId == chatId) {
            val answer = chat.userMessages(chatId).filter { it.at > p.at }.minByOrNull { it.at }
                ?: return "NOT deleted yet: the user hasn't answered. Wait for their next message, then call agent_delete with confirm: true only if they say yes."
            pend = null; onAsk(null)
            if (!YES.containsMatchIn(answer.text)) return "NOT deleted: the user didn't say yes, so ${b.name} stays. Don't delete it unless they ask again."
            return removed(b)
        }
        val np = Pending(chatId, b.id, b.name, clock())
        pend = np; onAsk(np)
        return "NOT deleted yet. Ask the user plainly whether to delete ${b.name} and stop; Delete and Keep buttons are shown in the chat. " +
            "Call agent_delete again with confirm: true only after they say yes in their next message."
    }

    private fun removed(b: Bot): String = when (val r = edits.delete(b.id)) {
        is TeamStore.Result.Refused -> "Error: ${r.why}"
        is TeamStore.Result.Ok -> {
            changed(b.id)
            if (b.kind == BotKind.DOMAIN) "Switched ${b.name} off; it left the team and its jobs are unassigned." else "Deleted ${b.name}; their jobs are unassigned."
        }
    }

    /** The card's Delete: deletes the bot waiting for an answer. */
    fun confirmByButton(botId: String): String {
        if (!BotRules.canDelete(botId)) return "Error: ${BotRules.MANAGER_NAME} can never be deleted."
        val p = pending()
        if (p == null || p.botId != botId) return "Error: nothing is waiting to be deleted."
        pend = null; onAsk(null)
        val b = team.get(botId) ?: return "Error: that bot is already gone."
        return removed(b)
    }

    /** The card's Keep: drops the ask; nothing is deleted. */
    fun keep(botId: String): String {
        val p = pending()
        if (p != null && p.botId == botId) { pend = null; onAsk(null) }
        return "Kept it. Nothing was deleted."
    }

    private fun note(text: String?): String {
        val t = text?.trim()?.removePrefix(NOTE_PREFIX)?.trim()?.takeIf { it.isNotEmpty() } ?: return "Error: give the note's text."
        val r = notes.add(NOTE_PREFIX + t)
        val id = r.id ?: return r.reply
        onUndo("Saved to team notes") { if (notes.remove(id)) "Removed from team notes." else "That note was already gone." }
        return r.reply
    }
}

/** P1-TM-04: the team tools of every chat as one [TeamHooks.Tools]: the jobs tools and the Manager's team tools. */
class TeamToolSet(private val jobs: JobTools, private val manager: ManagerTools) : TeamHooks.Tools {
    override fun defs(): List<String> = jobs.defs() + manager.defs()
    override fun names(): Set<String> = JobTools.NAMES + ManagerTools.NAMES
    override fun run(chatId: String, botId: String?, name: String, args: String): String =
        if (name in ManagerTools.NAMES) manager.run(chatId, botId, name, args) else jobs.run(chatId, botId, name, args)
}
