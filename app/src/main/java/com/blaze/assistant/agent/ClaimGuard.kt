package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
import org.json.JSONObject

/**
 * v0.23.2: no fake claims about views. Blazeinchue asked for a 3D rocket and got "I'm on that tall modern 3D
 * rocket… view it in the design section", then "I've updated the design to show the engine" and "I've
 * opened the design view", with no design ever made.
 *
 * A final reply that says it created, updated or opened something in a view ([ViewRouter.specs]) is
 * checked against the tool calls that SUCCEEDED this turn. With no backing call the agent re-prompts once
 * ([nudge]); if it still isn't done, the claim sentences are replaced with an honest line ([honest]).
 * Pure Kotlin, unit-tested in ClaimGuardTest.
 */
object ClaimGuard {

    /** A tool call that succeeded this turn. */
    data class Call(val name: String, val args: JSONObject = JSONObject())

    private val ic = setOf(RegexOption.IGNORE_CASE)

    /** Did a tool result say it worked? (Same failure words the rest of the agent uses.) */
    fun succeeded(result: String): Boolean {
        val r = result.trimStart()
        return r.isNotEmpty() && !Regex("""^(Error|Couldn't|Could not|I couldn't|Can't|Cannot|Not |No |Need |Missing|Permission denied|The user didn't|Refused|Failed|No such|Unknown|Not in the|Nothing |There's no|The user did NOT|The user declined|The user chose|That MCP tool|That changed nothing)""", ic).containsMatchIn(r)
    }

    /** Words that name each view's things in a claim. Archive is read-only, so nothing there can be claimed. */
    private val nouns: Map<String, String> = mapOf(
        "design" to """designs?|preview|3-?d|mock-?ups?|3d scenes?|renders?|landing pages?|web ?pages?|websites?|posters?|flyers?|slides?|slide deck|diagrams?|charts?|floor ?plans?|wire-?frames?|prototypes?|layouts?|rockets?|artwork""",
        "learn" to """flash ?cards?|decks?|learn|quiz(?:zes)?""",
        "schedule" to """reminders?|routines?|alarms?|schedule|triggers?|calendar events?""",
        "gallery" to """images?|pictures?|photos?|gallery|logos?|wallpapers?|beats?|songs?|illustrations?""",
        "skills" to """skills?""",
        "projects" to """projects?""",
        "memory" to """memory|memories""",
        "browser" to """browser|tabs?""",
        // 0.46.0 Blaze Stage: "I've started a 30-minute timer", "the music player is on your screen".
        "stage" to """stage|timers?|pomodoros?|countdowns?|mini ?players?""",
    )

    private const val VERBS = """created|made|built|generated|designed|drawn|drew|rendered|updated|changed|modified|edited|adjusted|tweaked|added|opened|saved|set up|scheduled|put|placed|switched|redesigned|rebuilt|shown|showing|displayed"""
    // "I've updated the design", "I have opened…", "the design has been updated", "it's now in…", "done, I added…"
    // v0.27.x: also a sentence that opens with the verb ("Opened the Solar Panel diagram in Design view.").
    private val pastClaim = Regex("""^\s*(?:$VERBS)\b|\b(?:i|i've|i have|i just|i've just|we've|it's|it is|it has been|has been|have been|is now|are now|done|all done)\b[^.!?\n]{0,30}?\b(?:$VERBS)\b""", ic)
    // "view it in the design section", "you can see it in Design", "it's in the Learn view", "open the design view to see"
    private fun whereClaim(n: String) = Regex("""\b(?:view|see|find|check|open|look at|explore|orbit|tap)\b[^.!?\n]{0,30}\b(?:in|on|from)\s+(?:the\s+|your\s+)?(?:$n)\b(?:\s+(?:section|view|tab|screen|page|panel))?|""" +
        """\b(?:it's|it is|they're|they are|now)\s+(?:now\s+)?(?:in|on)\s+(?:the\s+|your\s+)(?:$n)\s+(?:section|view|tab|screen)|""" +
        """\bhere(?:'s| is) (?:your|the) (?:new |updated )?(?:$n)\b""", ic)
    // Inside the "I … verb" part only, so "I've updated the design, let me know" still counts.
    private val negation = Regex("""\b(not|never|unable|failed|cannot|will|would|could|should|shall|can|going to|about to)\b|n't\b|'ll\b""", ic)
    private val pastTime = Regex("""\b(earlier|before|last time|previously|yesterday|already)\b""", ic)

    private fun sentences(text: String): List<String> = text.split(Regex("""(?<=[.!?…])\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }

    /** The claim sentences in [text] about [viewId] (empty when there are none). */
    fun claims(text: String, viewId: String): List<String> {
        val n = nouns[viewId] ?: return emptyList()
        val noun = Regex("""\b(?:$n)\b""", ic)
        val where = whereClaim(n)
        return sentences(text).filter { s ->
            if (pastTime.containsMatchIn(s)) return@filter false
            // "I've opened the website in the browser" is a Browser claim, not a Design one.
            if (viewId == "design" && Regex("""\bbrowser\b""", ic).containsMatchIn(s)) return@filter false
            val w = where.containsMatchIn(s) && !Regex("""\b(couldn't|can't|cannot|unable|failed|didn't|haven't|wasn't|isn't|not yet|once|when it's|after)\b""", ic).containsMatchIn(s)
            val p = noun.containsMatchIn(s) && pastClaim.findAll(s).any { m -> !negation.containsMatchIn(m.value) }
            w || p
        }
    }

    /** Does a succeeded call put something in [spec]'s view? */
    fun backs(spec: ViewRouter.Spec, c: Call): Boolean {
        if (c.name == "open_view") return c.args.optString("view").lowercase().let { it == spec.id || (spec.id == "archive" && it == "chats") }
        return when (spec.id) {
            "design" -> c.name == Designs.TOOL || c.name == "preview_server" ||
                (c.name in setOf("file_write", "file_edit") && Regex("""(?i)\.(html?|svg|css|js)$""").containsMatchIn(c.args.optString("path").trim()))
            "gallery" -> c.name in setOf("generate_image", "gallery", "run_code")
            // v0.27.x: showing / listing memories is backed by a lookup too.
            "memory" -> c.name in setOf("memory_save", "memory_update", "memory_delete", "memory_merge", "remember", "forget", "memory_search", "recall_memory")
            "browser" -> c.name.startsWith("browser_") || c.name in setOf("open_url", "navigate_to")
            // 0.46.0: music_ops / video_ops put their card on the Stage too; set_timer is the Clock app's timer.
            "stage" -> c.name.startsWith("stage_") || c.name in setOf("music_ops", "video_ops", "movies", "set_timer", "set_alarm", "media_control")
            else -> c.name in spec.tools
        }
    }

    /** The first view the reply claims to have changed with no succeeded call backing it, or null. */
    fun unbacked(reply: String, ok: List<Call>): ViewRouter.Spec? {
        if (reply.isBlank()) return null
        for (s in ViewRouter.specs) {
            if (s.id == "archive") continue
            if (claims(reply, s.id).isEmpty()) continue
            if (ok.none { backs(s, it) }) return s
        }
        return null
    }

    /**
     * v0.27.x: is a claim about [spec]'s view something this request is about? Only then is the model re-prompted to
     * do the work there. "show me your memories" answered with "Opened the solar panel diagram in Design" is not a
     * reason to go and open Design; the stray claim is dropped instead ([drop]).
     */
    fun relevant(spec: ViewRouter.Spec, userText: String, turnView: String? = null): Boolean {
        if (turnView == spec.id) return true
        if (ViewRouter.route(userText)?.id == spec.id) return true
        val n = nouns[spec.id] ?: return false
        return Regex("""\b(?:$n)\b""", ic).containsMatchIn(userText)
    }

    /** [reply] with the claim sentences about [spec] taken out ("" when nothing else was said). */
    fun drop(reply: String, spec: ViewRouter.Spec): String {
        val bad = claims(reply, spec.id).toSet()
        val rest = sentences(reply).filterNot { it in bad }.joinToString(" ").trim()
        return if (rest.length < 12) "" else rest
    }

    /** The one re-prompt: do it for real now. */
    fun nudge(spec: ViewRouter.Spec, current: Designs.Current?): String = "[${BrandName.NAME} check] Your reply says you made, changed or opened something in ${spec.name}, " +
        "but no ${spec.name} tool succeeded this turn, so nothing is there. Do it for real now: " + when (spec.id) {
            "design" -> if (current != null) "the current design is \u201c${current.title}\u201d (${current.kind}, ${current.path}); change it with design action=update " +
                "(3D: set_parts/remove_parts; pages: edits or html), or design action=open to show it, or design action=create for something new. "
                else "design action=create (3D: kind=3d with parts=[…]; pages and everything 2D: html; art: svg). "
            "learn" -> "flashcards action=add (deck, cards=[{front, back}]), then open_view view=learn. "
            "schedule" -> "create_routine / schedule_command / create_trigger (set_alarm or add_calendar_event for one-off times). "
            "gallery" -> "generate_image (or run_code saving under /root/phone/). "
            "skills" -> "save_workflow, write_skill or install_skill. "
            "projects" -> "open_view view=projects (or update_brief). "
            "memory" -> "memory_save / remember. "
            "browser" -> "browser_open and the other browser_ tools. "
            "stage" -> "stage_show (kind=timer minutes=N title=…, or kind=music / quiz / flashcards / plan). "
            else -> "${spec.tools.joinToString(" / ")}. "
        } + "Then reply in one or two lines about what the tool actually did (its real result: 2D or 3D, what it shows). If it can't be done, say so plainly."

    /** The claim sentences swapped for an honest line (the rest of the reply is kept). */
    fun honest(reply: String, spec: ViewRouter.Spec): String {
        val bad = claims(reply, spec.id).toSet()
        val rest = sentences(reply).filterNot { it in bad }.joinToString(" ").trim()
        val line = "I didn't manage to " + when (spec.id) {
            "design" -> "build or change it in Design this time, so nothing new is there yet"
            "learn" -> "make the deck in Learn this time, so there are no new cards yet"
            "schedule" -> "set that up in Schedule this time, so nothing is scheduled yet"
            "gallery" -> "make it in the Gallery this time, so nothing new is there yet"
            "memory" -> "save that to memory this time"
            "stage" -> "put it on the screen this time, so it isn't there yet"
            else -> "do that in ${spec.name} this time, so nothing changed there yet"
        } + ". Say \u201ctry again\u201d and I'll have another go."
        return if (rest.length < 12) line else "$line\n\n$rest"
    }

    // ---------------- 0.46.1: "open" claims about Stage cards ----------------

    /** Card words in a claim, by Stage kind id. */
    private val cardNouns: Map<String, String> = linkedMapOf(
        "browser" to """browser(?: card| widget)?|web ?browser""",
        "timer" to """timer(?: card)?|pomodoro|countdown""",
        "music" to """music (?:card|player)|mini ?player|player""",
        "quiz" to """quiz(?: card)?""",
        "flashcards" to """flash ?cards?(?: card)?""",
        "plan" to """plan(?: card)?|checklist""",
        "video" to """video(?: card| player)?""",
    )

    /**
     * 0.46.0 report: "pull up browser card" got "Browser's open, tap Exit…" and no card. A sentence that says a card is open /
     * up / on the screen ("Browser's open", "the timer card is up", "the browser is on your screen") is a card claim.
     */
    fun cardClaims(reply: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (s in sentences(reply)) {
            if (pastTime.containsMatchIn(s) || Regex("""\b(not|never|couldn't|can't|cannot|didn't|isn't|wasn't|unable|failed)\b|n't\b""", ic).containsMatchIn(s)) continue
            // "The browser is open in the background" is about background browsing, not a card.
            if (Regex("""\b(?:background|in (?:a|the|another) tab|chrome|on your pc)\b""", ic).containsMatchIn(s)) continue
            for ((kind, n) in cardNouns) {
                val claim = Regex("""\b(?:$n)\b(?:'s|\u2019s|\s+is|\s+are|\s+card is|\s+should be)?\s+(?:now\s+|already\s+|all\s+)?(?:open(?:ed)?|up|showing|ready|on (?:your |the )?(?:screen|stage|chat))\b|""" +
                    """\b(?:opened|pulled up|brought up|put up|showing|here's|here is)\s+(?:the\s+|your\s+|a\s+)?(?:$n)\b""", ic)
                if (claim.containsMatchIn(s)) { out.putIfAbsent(kind, s) }
            }
        }
        return out
    }

    /**
     * The first card kind the reply says is open with no card behind it: no stage_show of that kind succeeded this turn (and
     * for the browser, no browser_show either) and none is on the Stage now ([onStage] = kind ids). Null when all are backed.
     */
    fun unbackedCard(reply: String, ok: List<Call>, onStage: Set<String>): String? {
        for (kind in cardClaims(reply).keys) {
            if (kind in onStage) continue
            val shown = ok.any { c -> c.name == "stage_show" && com.blaze.assistant.stage.StageKind.of(c.args.optString("kind"))?.id == kind } ||
                (kind == "browser" && ok.any { it.name == "browser_show" || (it.name == "open_view" && it.args.optString("view").equals("browser", true)) }) ||
                (kind == "music" && ok.any { it.name == "music_ops" }) || (kind == "timer" && ok.any { it.name == "set_timer" })
            if (!shown) return kind
        }
        return null
    }

    /** The one re-prompt for an unbacked card claim. */
    fun cardNudge(kind: String): String = "[${BrandName.NAME} check] Your reply says the $kind card is open, but no $kind card is on the screen: nothing showed it this turn. " +
        "Show it for real now with stage_show kind=$kind" + (if (kind == "browser") " (url optional)" else "") + ", then reply in one short line about what the tool did."

    /** The claim sentences swapped for an honest line. */
    fun cardHonest(reply: String, kind: String): String {
        val bad = cardClaims(reply)[kind]
        val rest = sentences(reply).filterNot { it == bad }.joinToString(" ").trim()
        val line = "The $kind card isn't on the screen: I didn't manage to put it up this time. Say \u201ctry again\u201d and I'll have another go."
        return if (rest.length < 12) line else "$line\n\n$rest"
    }

    // ---------------- v0.28.7: sends ----------------

    /** Tools whose success means a message really left the phone. */
    val SEND_TOOLS = setOf("message_contact", "send_sms", "send_text", "reply_to_notification", "gmail_send", "send_email")

    private val sendClaimRe = Regex("""\b(?:message|text|picture|image|photo|it)\s+(?:was\s+|has been\s+)?sent\b|^\s*sent\b|\b(?:i've|i have|i just|i)\s+(?:sent|messaged|texted|delivered|forwarded)\b|\bsuccessfully sent\b""", ic)

    /** Is this request about sending something to someone (not a question about an earlier send)? */
    fun asksToSend(userText: String): Boolean =
        Regex("""\b(send|message|text|whatsapp|telegram|sms|forward|tell|reply)\b""", ic).containsMatchIn(userText) &&
            !Regex("""^\s*(did|have|has|was|were)\b""", ic).containsMatchIn(userText)

    /** Sentences in [reply] that say a message went out. */
    fun sendClaims(reply: String): List<String> = sentences(reply).filter { s ->
        sendClaimRe.containsMatchIn(s) && !Regex("""\b(not|never|couldn't|didn't|wasn't|haven't|unable|failed|once|when|after|if)\b""", ic).containsMatchIn(s)
    }

    /** A reply claiming a send with no send tool succeeding this turn → the honest version, else null. */
    fun unbackedSend(reply: String, ok: List<Call>): String? {
        val bad = sendClaims(reply)
        if (bad.isEmpty() || ok.any { it.name in SEND_TOOLS }) return null
        val rest = sentences(reply).filterNot { it in bad }.joinToString(" ").trim()
        val line = "I haven't actually sent it: no message left the phone this time. Say \u201ctry again\u201d and I'll send it properly."
        return if (rest.length < 12) line else "$line\n\n$rest"
    }
    // ---------------- P2-SF-07: command results claimed with no tool run (A team v2.9 unbackedClaims, v2.11 unranShellFence) ----------------
    // Ported from A team app/www/guard.js (owner grant, MIT). A reply that states command output or results when no tool ran
    // in that turn invented them. Hypotheticals ("if you run", "you should see"), negations ("I haven't run it") and offers
    // ("let me run") don't count. Cases: A team evals/test/v29.test.mjs + v211.test.mjs, as JUnit in ClaimGuardRunTest.
    // Complements [unbacked] (views), [unbackedCard] (Stage cards) and [unbackedSend] (messages): this one is about shell /
    // build / test results. The loop re-prompts once with [RUN_CLAIM_MSG] (or [FENCE_MSG]); a repeat is flagged.

    /** [flag] = the reply claims results no tool produced; [reasons] ⊂ ran, result, output, exit, state, terminal; [quote] = the first claim. */
    data class RunClaim(val flag: Boolean, val reasons: List<String> = emptyList(), val quote: String = "")

    private val runClaims: List<Pair<String, Regex>> = listOf(
        "ran" to Regex("""\b(i|we)(?:'ve| have)? (?:just |already )?(ran|run|executed|tested|re-?ran|compiled|built|checked|verified|installed|deleted|removed|created|wrote|written|saved|moved|copied|updated)\b(?! (?:it|this|that) (?:for|on) you\b)""", ic),
        "result" to Regex("""\b(the |that |this |your )?(command|script|test(?:s| suite)?|build|program|check|query|run|ping|curl|install(?:ation)?)\b[^.!?\n]{0,30}\b(returned|printed|outputs?|output(?:ted)?|shows?|showed|shown|produced|passed|succeeded|failed|exited|completed|finished|says|said|reports?|reported)\b""", ic),
        "output" to Regex("""\bhere(?:'s| is| are) (?:the )?(?:actual |real |full )?(output|results?|logs?)\b|(^|\n)\s*(output|result|stdout|stderr)s?\s*:\s*(\n|`)""", ic),
        "exit" to Regex("""\bexit(?:ed)? (?:with )?(?:code|status) \d+\b|\b\d+ (?:tests? )?(?:passed|failed)\b|\ball (?:\d+ )?tests? (?:passed|pass)\b|\btests? (?:all )?passed\b""", ic),
        "state" to Regex("""\b(there (?:are|is)|you have|it has|the (?:folder|directory|repo|file) has) \d+ (?:files?|folders?|director(?:y|ies)|lines?|processes|tests?|errors?|commits?|rows?|entries)\b""", ic),
        "terminal" to Regex("""(^|\n)\s*(total \d+\s*\n|[d-][rwx-]{9}[.@+]?\s+\d+\s|root@[\w.-]+:[^\n]*[#$]|\d+ packets transmitted|PING \S+ \()"""),
    )
    private val runHedge = Regex("""\b(if|when|once|after|you can|you could|you'?ll|you will|you should|you'd|would|should|could|might|may|expect(?:ed)?|example|e\.g\.|sample|like this|haven'?t|have not|hasn'?t|didn'?t|did not|can'?t|cannot|couldn'?t|not yet|never|unable|let me|i'?ll|i will|i'?m going to|going to|i can|try|plan|want to|need to|before)\b""", ic)
    private val exampleCtx = Regex("""\b(example|expected|sample|should look|would look|looks like|like this|you'?ll see|you should see|will (list|show|print))\b""", ic)

    /** A team sentences(): code fences dropped unless they are output-like (```text/output/console/log). */
    private fun runSentences(t: String): List<String> = Regex("""```[\s\S]*?```""").replace(t) { m ->
        if (Regex("""```(text|txt|output|console|log)?\n""").containsMatchIn(m.value)) m.value else " "
    }.split(Regex("""(?<=[.!?])\s+|\n{2,}"""))

    /** Does [text] claim command output / results that no tool produced? [ran] = a tool ran in this turn (then never). */
    fun unbackedRun(text: String, ran: Boolean = false): RunClaim {
        if (ran) return RunClaim(false)
        val reasons = ArrayList<String>(); var quote = ""
        for (s in runSentences(text)) {
            for ((k, re) in runClaims) {
                val m = re.find(s) ?: continue
                if (k != "terminal" && runHedge.containsMatchIn(s)) continue
                if (k == "terminal") {   // example output ("expected output looks like this:") is not a claim
                    val at = text.indexOf(s.trim().take(20))
                    val ctx = (if (at > 0) text.substring(maxOf(0, at - 240), at) else "") + s.substring(0, m.range.first)
                    if (exampleCtx.containsMatchIn(ctx)) continue
                }
                if (k !in reasons) reasons += k
                if (quote.isEmpty()) quote = s.trim().take(160)
            }
        }
        return RunClaim(reasons.isNotEmpty(), reasons, quote)
    }
    const val RUN_CLAIM_MSG = "CHECK: your reply states command output or results, but no tool ran in this turn, so the app can't back them up. If you need a result, run the command now (the shell tool) and report only what it really prints. If the result came from an earlier turn, say so. Otherwise say plainly that you have not run it. Do not repeat the unverified claim."

    /**
     * One turn's handling of unbacked run claims (A team eval loop): the first final reply that claims results with no tool
     * run is hidden and bounced once with [RUN_CLAIM_MSG]; a second one in the same turn is let through but [flagged].
     */
    class RunClaimTurn {
        enum class Action { OK, BOUNCE, FLAG }
        private var bounced = false
        var flagged = false; private set
        fun onFinal(text: String, ran: Boolean): Action {
            if (!unbackedRun(text, ran).flag) return Action.OK
            if (!bounced) { bounced = true; return Action.BOUNCE }
            flagged = true; return Action.FLAG
        }
    }

    private val shellFence = Regex("""```[ \t]*(bash|sh|shell|zsh|console|terminal)?[ \t]*\n([\s\S]*?)```""")
    private val cmdLine = Regex("""^\s*(?:\$ )?(python3?|pytest|node|npm|npx|pip3?|cd|ls|cat|git|make|bash|sh|sed|grep|find|mkdir|rm|cp|mv|chmod|curl|go|cargo|java|javac|gcc)\b""")
    private val runIntent = Regex("""\b(let me|let'?s|i'?ll|i will|i'?m going to|now i'?ll|going to) (run|execute|test|check|verify|try)\b|\b(running|executing)\b[^.\n]{0,40}(:|\bnow\b)|(^|\n)\s*(output|result)s?\s*:|\ball (checks|tests) pass|\bit (prints|printed|outputs|returned)\b""", ic)
    private val forUser = Regex("""\byou (can|could|should|may|might|'ll|will) (run|use|try|execute)\b|\bto run (it|this) yourself\b""", ic)

    /** [flag] = the reply shows a shell command in a non-run fence (```bash) and talks as if it ran or will run now. */
    data class FenceClaim(val flag: Boolean, val cmd: String = "")

    fun unranShellFence(text: String): FenceClaim {
        var cmd = ""
        for (m in shellFence.findAll(text)) {
            val body = m.groupValues[2]
            if (if (m.groupValues[1].isNotEmpty()) body.trim().isNotEmpty() else cmdLine.containsMatchIn(body)) { cmd = body.trim().split("\n")[0].take(120); break }
        }
        if (cmd.isEmpty()) return FenceClaim(false)
        val prose = shellFence.replace(text, " ")
        if (forUser.containsMatchIn(prose) && !Regex("""\b(let me|i'?ll|i will)\b""", ic).containsMatchIn(prose)) return FenceClaim(false)
        return FenceClaim(runIntent.containsMatchIn(text), cmd)
    }
    const val FENCE_MSG = "CHECK: you put a command in a ```bash block. That only displays it: nothing ran and there is no output yet. If you meant to run it, call the shell tool and report only what it prints. If it was only an example for the user, say so plainly."
}
