package com.blaze.assistant.memory

/**
 * P3-AG-05 (05 §4.3, A-team v2.16c autoLearn): the user's rules and facts are saved by the app itself, the moment they
 * are said, not left to the model; then shown as one "Saved to memory" card with Undo. Pure (LearnDetectTest):
 *
 *  - [detect]: A-team manager.js `learn()` detectors, ported verbatim — RULE_START / RULE_ANY (standing rules), PREF
 *    (preferences and facts about them), REM ("remember that…"), SKIP (questions, "never mind"), SECRET (dropped and
 *    counted, so the chat can say "not saved: that looks like a secret"). At most 4 per message.
 *  - [place]: where a line goes (deterministic, 05 §4.3): rules → `user:core` + the relevant bot's SOUL "Standing
 *    rules"; allergies / dietary requirements → `user:core` as a **requirement** with `share:food`; names → `user:core`;
 *    people → `user:people`; places / time zone → `user:places`; preferences → `user:preferences`; the rest → `user:notes`.
 *  - [decide]: memory_propose rules. Only the Manager writes `user:*`; any other bot proposes, and the Manager (this
 *    deterministic rule) accepts stated lines, auto-accepts requirements, and sends inferred ones to the user as a
 *    question. Secrets, one-off task lines and things only true today are rejected.
 *  - [NARROW_RULE]: the narrowness line every bot's prompt carries.
 */
object AutoLearn {
    // ------------------------------------------------------------------------------------------------ A-team regexes (manager.js v2.16c)
    val SECRET = Regex("""(?i)\b(gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+)\b|\b(password|passwd|api[_ -]?key|secret|token|pin)\s*[:=]\s*\S{4,}|\b(?:\d[ -]?){13,19}\b""")
    val RULE_START = Regex("""(?i)^(?:please\s+)?(?:always|never|don'?t|do not|stop|no more|from now on|going forward|make sure|every time|whenever|only ever|avoid)\b""")
    val RULE_ANY = Regex("""(?i)\b(?:from now on|going forward|in future|every time|each time|by default)\b|\b(?:always|never)\s+(?:use|ask|send|call|reply|write|answer|say|show|tell|save|check|look|run|delete|edit|change|put|keep|do|give|make|start|end|open|include|add|book|pay|text|email|remind|wait|let)\b""")
    val PREF = Regex("""(?i)\b(?:I(?:'m| am)\s+(?:a|an|vegan|vegetarian|allergic|based|from|in)\b|I\s+(?:prefer|like|love|hate|dislike|don'?t like|can'?t stand|usually|live|work|study)\b|my\s+(?:name|nickname|favou?rite|birthday|wife|husband|partner|son|daughter|mom|mum|dad|boss|company|business|team|time ?zone|email|address|city|school|job)\b[^.!?\n]{0,60}\b(?:is|are|was)\b|call me\b)""")
    val REM = Regex("""(?i)^(?:please\s+)?(?:remember|note|keep in mind)(?:\s+(?:that|this|to))?\s*[:,-]?\s+(.+)$""")
    val SKIP = Regex("""(?i)^(?:never ?mind|nvm|no worries|don'?t worry|stop it|stop$)|\?\s*$""")
    private val NAME = Regex("""(?i)call me|my (?:name|nickname)""")
    private val PERSON = Regex("""(?i)^my\b|\bmy (?:wife|husband|partner|son|daughter|mom|mum|dad|boss)\b""")

    /** Argus additions: requirements and places that A-team filed as plain preferences. */
    private val REQUIREMENT = Regex("""(?i)\b(?:allerg(?:y|ic)|intoleran(?:t|ce)|coeliac|celiac|vegan|vegetarian|halal|kosher|gluten[- ]free|lactose|can'?t eat|don'?t eat|do not eat|no (?:pork|beef|meat|nuts|dairy|shellfish|gluten))\b""")
    private val PLACE = Regex("""(?i)\b(?:I live|I(?:'m| am) (?:based|from|in)\b|my (?:time ?zone|address|city)|time ?zone)""")
    private val TRANSIENT = Regex("""(?i)\b(right now|at the moment|just now|this time|this turn|this chat|today|tonight|this morning|this afternoon|for now)\b""")
    private val TASKISH = Regex("""(?i)^(the user (asked|wants|wanted|requested)|user asked|asked (me|argus|blaze) to|(argus|blaze) (ran|did|fixed|created))""")

    data class Learned(val kind: String, val text: String)

    data class Detected(val items: List<Learned>, val secrets: Int)

    /** A-team learn(text): sentences split on . ! and new lines; at most 4; first letter upper case. */
    fun detect(text: String?): Detected {
        val out = ArrayList<Learned>(); var secrets = 0
        for (s0 in (text ?: "").split(Regex("(?<=[.!])\\s+|\\n+"))) {
            val s = s0.replace(Regex("\\s+"), " ").trim().replace(Regex("[.!]+$"), "")
            if (s.length < 6 || s.length > 300 || SKIP.containsMatchIn(s)) continue
            if (SECRET.containsMatchIn(s)) { if (REM.containsMatchIn(s) || RULE_START.containsMatchIn(s) || PREF.containsMatchIn(s)) secrets++; continue }
            val r = REM.find(s)
            if (r != null) {
                val body = r.groupValues[1]
                out += Learned(if (RULE_START.containsMatchIn(body) || RULE_ANY.containsMatchIn(body)) "rule" else "note", body.replace(Regex("(?i)^to\\s+"), ""))
                continue
            }
            if (RULE_START.containsMatchIn(s) || RULE_ANY.containsMatchIn(s)) { out += Learned("rule", s); continue }
            if (PREF.containsMatchIn(s)) out += Learned(if (NAME.containsMatchIn(s)) "name" else if (PERSON.containsMatchIn(s)) "person" else "preference", s)
        }
        return Detected(out.map { it.copy(text = it.text.replaceFirstChar { c -> c.uppercaseChar() }) }.take(4), secrets)
    }

    // ------------------------------------------------------------------------------------------------ placement

    data class Placement(val scope: String, val ateamKind: String, val mindKind: String, val shareTags: Set<String> = emptySet(),
                         val requirement: Boolean = false, val soulRule: Boolean = false)

    fun place(l: Learned): Placement {
        val t = l.text
        return when {
            REQUIREMENT.containsMatchIn(t) -> Placement(MemScopes.USER_CORE, "rule", "preference", setOf("share:food"), requirement = true)
            l.kind == "rule" -> Placement(MemScopes.USER_CORE, "rule", "preference", soulRule = true)
            l.kind == "name" -> Placement(MemScopes.USER_CORE, "name", "profile")
            l.kind == "person" -> Placement("user:people", "person", "person")
            PLACE.containsMatchIn(t) -> Placement("user:places", "place", "profile")
            l.kind == "preference" -> Placement("user:preferences", "preference", "preference")
            else -> Placement("user:notes", "note", "fact")
        }
    }

    // ------------------------------------------------------------------------------------------------ proposals

    data class Proposal(val text: String, val scope: String, val fromBot: String, val stated: Boolean, val requirement: Boolean = false)

    sealed class Decision {
        data class Accept(val scope: String, val why: String) : Decision()
        /** Shown to the user as a question card ("Atlas thinks you prefer aisle seats. Keep it?"). */
        data class Ask(val why: String) : Decision()
        data class Reject(val why: String) : Decision()
    }

    const val SECRET_NOTICE = "Not saved: that looks like a secret (a password, key, card or ID number). Keep those in the vault."

    /**
     * ARGUS's accept rule for memory_propose (05 §4.3, §10.5). The Manager itself never needs a proposal. A line that
     * fails [acceptable] is rejected; a requirement (allergy, dietary) is accepted at once; a stated line is accepted;
     * an inferred line into the profile is asked about; a proposal into a scope that isn't the profile is pointless.
     */
    fun decide(p: Proposal): Decision {
        val s = MemScopes.parse(p.scope) ?: return Decision.Reject("unknown scope \"${p.scope}\"")
        if (s !is MemScopes.Scope.UserCore && s !is MemScopes.Scope.User) return Decision.Reject("only the user's profile takes proposals; save it in your own scope")
        acceptable(p.text)?.let { return Decision.Reject(it) }
        if (p.requirement || REQUIREMENT.containsMatchIn(p.text)) return Decision.Accept(MemScopes.USER_CORE, "a requirement (allergy or diet) is always kept")
        if (!p.stated) return Decision.Ask("${p.fromBot} worked this out; the user confirms before it is kept")
        return Decision.Accept(p.scope, "stated by the user")
    }

    /** null when [text] may be saved; otherwise why not (A-team SECRET + Argus Learn.accept filters). */
    fun acceptable(text: String): String? {
        val t = text.trim(); val n = t.split(Regex("\\s+")).count { it.isNotEmpty() }
        return when {
            SECRET.containsMatchIn(t) -> SECRET_NOTICE
            n < 2 || t.length < 5 -> "too short to be a fact"
            t.length > 300 -> "too long for one memory line"
            TASKISH.containsMatchIn(t) -> "a task log, not a fact about the user"
            TRANSIENT.containsMatchIn(t) && !RULE_START.containsMatchIn(t) -> "only true for now"
            else -> null
        }
    }

    // ------------------------------------------------------------------------------------------------ the whole flow

    /** What the flow writes through (Mind + MemScopeStore + SoulStore on the phone). */
    interface Sink {
        /** Saves a line; returns its id, or 0 when it already existed (no card for it). */
        fun save(text: String, p: Placement, stated: Boolean, byBot: String): Long
        /** Adds a standing rule to [botId]'s SOUL.md; true when it was new. */
        fun addRule(botId: String, rule: String): Boolean
    }

    data class Saved(val id: Long, val text: String, val scope: String, val requirement: Boolean)
    data class Outcome(val saved: List<Saved>, val rulesTo: List<String>, val asked: List<Proposal>, val notices: List<String>) {
        /** The "Saved to memory" card (A-team v2.16c), or null when nothing new was saved. */
        val card: Card? get() = if (saved.isEmpty()) null else Card(
            title = "Saved to memory: " + saved.first().text.take(60) + if (saved.size > 1) " (+${saved.size - 1})" else "",
            lines = saved.map { it.text + if (it.requirement) " · always applied" else "" },
            undo = saved.map { it.id })
    }
    data class Card(val title: String, val lines: List<String>, val undo: List<Long>)

    /**
     * Runs on every user message (after MemoryGate allows it and not in a private chat). [isManager] decides between a
     * direct write and a proposal; [ruleBot] = the bot whose soul gets a standing rule (the bot being talked to; the
     * Manager when it's ARGUS).
     */
    fun run(text: String, botId: String, isManager: Boolean, ruleBot: String, sink: Sink): Outcome {
        val d = detect(text)
        val saved = ArrayList<Saved>(); val rules = ArrayList<String>(); val asked = ArrayList<Proposal>(); val notes = ArrayList<String>()
        if (d.secrets > 0) notes += SECRET_NOTICE
        for (l in d.items) {
            val p = place(l)
            val dec = if (isManager) acceptable(l.text)?.let { Decision.Reject(it) } ?: Decision.Accept(p.scope, "the Manager writes the profile")
            else decide(Proposal(l.text, p.scope, botId, stated = true, requirement = p.requirement))
            when (dec) {
                is Decision.Reject -> if (dec.why == SECRET_NOTICE && SECRET_NOTICE !in notes) notes += SECRET_NOTICE
                is Decision.Ask -> asked += Proposal(l.text, p.scope, botId, false)
                is Decision.Accept -> {
                    val id = sink.save(l.text, p.copy(scope = dec.scope), true, botId)
                    if (id > 0) saved += Saved(id, l.text, dec.scope, p.requirement)
                    if (p.soulRule && sink.addRule(ruleBot, l.text)) rules += ruleBot
                }
            }
        }
        return Outcome(saved, rules.distinct(), asked, notes)
    }

    /** The narrowness rule (A-team prompt + 05 §4.3), one line in every bot's memory block. */
    const val NARROW_RULE = "Save things as narrowly as the user said them, with their context (where, when, with whom). Never turn \"usually\" into \"always\" or one occurrence into a habit; mark what you worked out yourself as inferred."
}
