package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * When Argus should learn on its own (port of Argus Desktop v0.7 internal/learn, same rules as the CLI):
 *  - [rules]: the MEMORY & SKILLS block of the system prompt (save silently, replace stale facts,
 *    forget on request, write a skill when a workflow repeats or is taught).
 *  - [decide]: the cheap end-of-turn check. No model call. It decides whether the background review
 *    (a model call) is worth making at all: skipped for small talk, when the turn already saved,
 *    and when nothing durable was said. Before v0.8.0 the review ran after nearly every answer.
 *  - [accept]: the filter every auto-saved fact passes (no secrets, no account numbers, nothing only
 *    true today, no task logs).
 *  - [signature] / [repeats]: a turn's workflow fingerprint, so a repeated multi-step job can become a skill.
 * No I/O except the signature store, so it stays easy to keep in step with Desktop and CLI.
 */
object Learn {

    fun rules(auto: Boolean): String = if (!auto) """

MEMORY & SKILLS
- Learning automatically is OFF in Settings. Save a memory (remember) or a skill (write_skill) only when the user asks you to. forget still works when asked.
""" else """

MEMORY & SKILLS (learn as you go; nobody has to ask)
- Save silently with remember the moment a durable fact appears: preferences, how they like answers, the apps and accounts they use, people, places, projects and recurring routines. One short sentence each, with its context ("Orders Yango to work on weekdays", not "Uses Yango"). Keep their certainty: "usually" stays usually; mark guesses as inferred.
- Don't save one-off task details, things only true today, secrets (keys, passwords, PINs, card or ID numbers) or what is already in your prompt.
- When a fact changes, replace the old memory: remember(fact, replaces="words from the old fact") instead of adding a second one. When the user asks you to forget something, forget every matching memory and confirm.
- Save a workflow with save_workflow (trigger, steps, checks; or write_skill for free-form) when a multi-step task repeats or the user teaches you a method ("here's how I top up airtime"). Name it kebab-case; description = when to use it; body = short numbered steps, checks and pitfalls. Before a matching task, use_skill it. When you find a better way, rewrite the skill with the same name.
- Memory saves need no announcement. When you create or change a skill, tell the user in one short line at the end (e.g. "Saved this as the airtime-topup skill.").
"""

    data class Turn(val user: String, val reply: String, val tools: List<String>, val savedMemory: Boolean, val savedSkill: Boolean)
    data class Decision(val reflect: Boolean, val memory: Boolean, val skill: Boolean, val why: String)

    private val smallTalk = ("hi hey hello yo thanks thank you thx ty ok okay cool nice great yes no yep nope sure continue go on ahead " +
        "do it done lol k please pls and good perfect awesome alright right").split(" ").toSet()

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Small talk or a short go-ahead. */
    fun trivial(s: String): Boolean {
        val w = words(s)
        if (w.isEmpty()) return true
        if (w.size > 4) return false
        if (w.all { it in smallTalk }) return true
        return w.size <= 2 && !s.lowercase().contains("remember")
    }

    private val factCues = listOf(
        "i prefer", "i'd prefer", "i like", "i love", "i don't like", "i dont like", "i hate", "i want you to", "i'd rather",
        "always ", "never ", "from now on", "remember", "don't forget", "keep in mind", "for future", "next time",
        "my name", "call me", "i'm called", "i am called", "i use ", "i'm using", "i am using", "we use ", "i work", "i live", "i'm based", "i am based",
        "my setup", "my project", "my repo", "my site", "my store", "my phone", "my laptop", "my pc", "i usually", "i normally", "stop doing", "please don't",
        "instead of", "in future", "in the future", "my timezone", "my email", "my number", "my birthday", "my wife", "my husband", "my partner",
        "my boss", "my team", "my mom", "my mum", "my dad", "my sister", "my brother", "my friend", "my school", "my job",
    )
    private val teachCues = listOf(
        "here's how", "here is how", "this is how", "the way i", "the way we", "how i do", "how we do", "steps:", "step 1", "first,",
        "follow these", "workflow", "the process is", "my process", "every time i", "whenever i", "each time", "routine",
        "save this as a skill", "make a skill", "make this a skill", "as a skill", "next time do", "do it like this",
    )

    fun hasFactCue(s: String) = " ${s.lowercase()} ".let { l -> factCues.any { l.contains(it) } }
    fun hasTeachCue(s: String) = s.lowercase().let { l -> teachCues.any { l.contains(it) } }

    /** Memory and lookup tools don't make a turn a "workflow". */
    private val lookTools = setOf("remember", "recall_memory", "forget", "use_skill", "skill_file", "write_skill", "skill_manage",
        "load_tools", "find_tool", "search_past_chats", "notify")

    fun steps(tools: List<String>): Pair<Int, Int> {
        val work = tools.filter { it !in lookTools }
        return work.size to work.toSet().size
    }

    fun decide(t: Turn, repeat: Boolean): Decision {
        val user = t.user.trim()
        val (n, distinct) = steps(t.tools)
        val fact = hasFactCue(user)
        val teach = hasTeachCue(user)
        val workflow = n >= 5 && distinct >= 2
        if (trivial(user) && !workflow) return Decision(false, false, false, "trivial turn")
        if (t.savedSkill) return Decision(false, false, false, "a skill was already saved this turn")
        val memory = fact && !t.savedMemory
        val skill = teach || (workflow && repeat)
        val reflect = memory || skill
        val why = when {
            !reflect && t.savedMemory -> "memory already saved this turn"
            !reflect -> "nothing durable in this turn"
            skill && teach -> "the user taught a method"
            skill -> "a multi-step workflow repeated"
            else -> "the user stated a fact or preference"
        }
        return Decision(reflect, memory, skill, why)
    }

    // ---------- workflow signatures ----------

    private val stopWords = ("a an and are as at be but by can could do does for from had has have how i if in into is it its me my no not of on or our " +
        "please so than that the their them then there these they this to too up us was we were what when where which who why will with would you your " +
        "can't don't i'm it's let make want need get got use using also very really some any all about just now again").split(" ").toSet()

    fun signature(user: String, tools: List<String>): List<String> {
        val set = linkedSetOf<String>()
        tools.filter { it !in lookTools }.forEach { set += "tool:$it" }
        var n = 0
        for (w in words(user)) {
            if (w.length < 3 || w in stopWords) continue
            if (set.add(w)) n++
            if (n >= 8) break
        }
        return set.sorted()
    }

    fun similarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val both = (a.toSet() intersect b.toSet()).size
        return both.toDouble() / (a.size + b.size - both)
    }

    const val REPEAT_AT = 0.5

    /** Checks [sig] against the last workflows (kept in settings) and records it. Only real workflows are kept. */
    fun repeatsAndRecord(sig: List<String>, isWorkflow: Boolean): Boolean {
        val earlier = try { JSONArray(Settings.str("learn_sigs", "[]")).let { a -> (0 until a.length()).map { i ->
            a.getJSONArray(i).let { s -> (0 until s.length()).map { s.getString(it) } } } } } catch (_: Exception) { emptyList() }
        val hit = earlier.any { similarity(sig, it) >= REPEAT_AT }
        if (isWorkflow && sig.isNotEmpty()) {
            val keep = (earlier + listOf(sig)).takeLast(40)
            Settings.setStr("learn_sigs", JSONArray(keep.map { JSONArray(it) }).toString())
        }
        return hit
    }

    // ---------- the fact filter ----------

    private val secretRe = Regex("""(?i)(sk-[a-z0-9_-]{8,}|sk-ant-|aiza[0-9a-z_-]{20,}|gh[pousr]_[a-z0-9]{16,}|xox[abpr]-|AKIA[0-9A-Z]{12,}|-----BEGIN|\bpassword\s*(is|:|=)|\bpasscode\b|\bpin\s*(is|:|=)|\bcvv\b|\bseed phrase\b|\b(one[- ]time|otp|2fa|verification) code\b)""")
    private val longDigitRe = Regex("""\d[\d -]{11,}\d""")
    private val transientRe = Regex("""(?i)\b(right now|at the moment|just now|this time|this turn|this chat|this conversation|today|tonight|this morning|this afternoon|for now|currently (?:running|open|broken|failing))\b""")
    private val taskRe = Regex("""(?i)^(the user (asked|wants|wanted|requested|is asking)|user asked|asked (me|(?:argus|blaze)) to|(?:argus|blaze) (ran|did|fixed|created))""")

    /** True when [s] looks like a key, password, code or card/account number. Never saved, even when asked. */
    fun secret(s: String) = secretRe.containsMatchIn(s) || longDigitRe.containsMatchIn(s)

    /** Cleans a fact a review proposed. Returns the cleaned fact, or null with nothing to keep. */
    fun accept(fact: String): String? {
        val f = fact.trim().split(Regex("\\s+")).joinToString(" ").removePrefix("- ").removePrefix("* ")
        val n = f.split(" ").count { it.isNotEmpty() }
        return when {
            n < 3 -> null
            n > 45 || f.length > 300 -> null
            secret(f) -> null
            transientRe.containsMatchIn(f) -> null
            taskRe.containsMatchIn(f) -> null
            else -> f
        }
    }

    private val kebab = Regex("^[a-z0-9][a-z0-9-]{1,62}$")

    /** The bar for a skill written without the user asking: a proper name, description and real steps. */
    fun goodSkill(name: String, description: String, body: String): Boolean {
        val b = body.trim()
        val steps = b.lines().map { it.trim() }.count { l -> l.length > 2 && (l[0].isDigit() || l.startsWith("- ") || l.startsWith("* ")) }
        return kebab.matches(name) && !name.contains("--") && description.trim().split(Regex("\\s+")).size >= 4 &&
            b.length >= 60 && steps >= 3 && !secretRe.containsMatchIn(b)
    }
}

/**
 * Finn 23 Nov 2026, after Hermes Agent's background review: the skill keeper. Every [EVERY] tool calls in a chat
 * (counted from the reply traces since its last review), one light background agent reads that stretch of the chat
 * ([ChatDigest]) next to the names of the skills already saved, and decides by itself whether it taught a reusable
 * procedure. It has two tools and nothing else: [READ] (an existing skill, to improve it instead of duplicating it)
 * and [SAVE]. At most [MAX_STEPS] model calls and [MAX_SAVES] saves; most stretches teach nothing, and then it saves
 * nothing. No words in the user's message decide anything (docs/MODEL-FIRST.md): this replaces the "teach" word list
 * that used to gate skill learning. The loop is pure; the model, the reader and the saver are passed in
 * (Learning.skillReview wires them; SkillReviewTest).
 */
object SkillReview {
    const val EVERY = 8
    const val MAX_STEPS = 3
    const val MAX_SAVES = 2
    const val DIGEST_CHARS = 12_000
    const val SHELF_MAX = 80
    const val READ = "read_skill"
    const val SAVE = "save_skill"

    /** Due once at least [every] tool calls happened since the last review. */
    fun due(toolCalls: Int, every: Int = EVERY): Boolean = every > 0 && toolCalls >= every

    const val PROMPT = "You keep the skill library of a phone assistant. Below is a stretch of one chat: the user's messages, " +
        "the assistant's replies, and each tool it called with the outcome. Decide whether this stretch taught a reusable procedure: " +
        "a multi-step workflow that worked, a dead end and the way around it, or a correction the user gave about how to do something. " +
        "Most stretches teach nothing: then answer \"nothing\" and call no tool.\n" +
        "A skill holds lessons, not logs: when to use it, numbered steps with the exact commands, endpoints, settings and checks that mattered, " +
        "and the pitfalls hit with how they were solved. Leave out secrets (keys, tokens, passwords, codes), one-off values and today's results.\n" +
        "If a saved skill already covers this, read_skill it first and save the improved version under the same name; never save a near-duplicate. " +
        "Save at most 2 skills."

    data class Save(val name: String, val description: String, val instructions: String)

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)

    /** The reviewer's only tools. */
    fun tools(): JSONArray = JSONArray()
        .put(fn(READ, "Read a saved skill in full, to improve it instead of writing a duplicate.", JSONObject().put("name", str("The skill's name as listed")), listOf("name")))
        .put(fn(SAVE, "Save a skill (a new one, or a better version of a saved one under the same name).",
            JSONObject().put("name", str("short-kebab-name")).put("description", str("One line: when to use it"))
                .put("instructions", str("Markdown: numbered steps, checks, pitfalls and how they were solved")), listOf("name", "description", "instructions")))

    fun request(digest: String, shelf: List<String>): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", PROMPT))
        .put(JSONObject().put("role", "user").put("content",
            "Saved skills:\n" + (shelf.take(SHELF_MAX).joinToString("\n") { "- $it" }.ifEmpty { "(none)" }) + "\n\nThe chat:\n" + digest.takeLast(DIGEST_CHARS)))

    private fun slug(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)

    /**
     * Runs the review. [call] answers one request with an OpenAI-shaped assistant message (null when nothing answered),
     * [read] returns a skill's text, [save] stores one and says what happened. Returns each save attempted with its outcome.
     */
    suspend fun run(digest: String, shelf: List<String>, call: suspend (JSONArray, JSONArray) -> JSONObject?,
                    read: (String) -> String, save: suspend (Save) -> String): List<Pair<Save, String>> {
        if (digest.isBlank()) return emptyList()
        val msgs = request(digest, shelf)
        val tools = tools()
        val done = ArrayList<Pair<Save, String>>()
        for (step in 0 until MAX_STEPS) {
            val reply = call(msgs, tools) ?: break
            val calls = reply.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) break
            msgs.put(JSONObject().put("role", "assistant").put("content", reply.optString("content", "")).put("tool_calls", calls))
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val f = c.optJSONObject("function") ?: JSONObject()
                val a = try { JSONObject(f.optString("arguments", "{}").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                val out = when (f.optString("name")) {
                    READ -> read(a.optString("name")).take(8_000)
                    SAVE -> {
                        val s = Save(slug(a.optString("name")), a.optString("description").trim(), a.optString("instructions").trim())
                        when {
                            done.size >= MAX_SAVES -> "Not saved: at most $MAX_SAVES skills per review."
                            !Learn.goodSkill(s.name, s.description, s.instructions) ->
                                "Not saved: needs a kebab-case name, a description of 4+ words, 3+ numbered steps and no secrets."
                            else -> save(s).also { done += s to it }
                        }
                    }
                    else -> "Unknown tool. Only $READ and $SAVE exist."
                }
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", out))
            }
        }
        return done
    }
}
