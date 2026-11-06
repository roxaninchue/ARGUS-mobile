package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.52.1 (owner: "biggest thing is memory of tasks and old context"). A durable record of each task Blaze works on:
 * the goal, where it stands, which surface it used (Browser / Blaze Phone / screen / terminal), the sign-ins done for it,
 * what it found, and the next step. Saved with the app (TaskMemoryStore), so it survives turns and restarts.
 *
 * When the user refers back vaguely ("I signed you in", "retry it", "continue", "check now", "same as before but for
 * Lusaka", "that one"), [resolve] picks the task they mean and [block] puts it into the system prompt, so the model
 * continues THAT task on THAT surface instead of starting over (the owner had to sign in twice because the next turn
 * opened Blaze Phone after the sign-in happened in the Browser). [surfaceGuard] keeps a task on its surface.
 *
 * Pure (org.json only): TaskMemoryTest, VaguePromptTest (40+ elliptical follow-ups against prior-conversation fixtures).
 */
object TaskMemory {
    enum class State { ACTIVE, WAITING_USER, DONE, FAILED, STOPPED }
    enum class Surface { NONE, BROWSER, PHONE, SCREEN, TERMINAL }
    enum class Kind { RETRY, CONTINUE, SIGNED_IN, CHECK, AGAIN, SAME_FOR, THAT_ONE, STATUS, NEW }

    data class Rec(
        val id: String, val chat: String, val goal: String, val state: State = State.ACTIVE,
        val surface: Surface = Surface.NONE, val signIns: List<String> = emptyList(), val needsSignIn: String = "",
        val findings: List<String> = emptyList(), val next: String = "", val created: Long = 0L, val updated: Long = 0L,
        val tools: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("chat", chat).put("goal", goal.take(600)).put("state", state.name)
            .put("surface", surface.name).put("sign_ins", JSONArray(signIns.takeLast(8))).put("needs_sign_in", needsSignIn)
            .put("findings", JSONArray(findings.takeLast(MAX_FINDINGS).map { it.take(400) })).put("next", next.take(300))
            .put("created", created).put("updated", updated).put("tools", JSONArray(tools.takeLast(16)))
    }

    const val MAX_FINDINGS = 6
    const val KEEP = 60
    /** Tasks older than this are only picked when the words match. */
    const val RECENT_MS = 7L * 24 * 3600_000

    fun fromJson(o: JSONObject?): Rec? {
        if (o == null) return null
        val id = o.optString("id").ifBlank { return null }
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        return Rec(id, o.optString("chat"), o.optString("goal"),
            runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.DONE),
            runCatching { Surface.valueOf(o.optString("surface")) }.getOrDefault(Surface.NONE),
            list("sign_ins"), o.optString("needs_sign_in"), list("findings"), o.optString("next"), o.optLong("created"), o.optLong("updated"), list("tools"))
    }

    fun encode(list: List<Rec>): String = JSONArray(list.takeLast(KEEP).map { it.toJson() }).toString()
    fun decode(s: String?): List<Rec> = try {
        val a = JSONArray(s ?: "[]"); (0 until a.length()).mapNotNull { fromJson(a.optJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    // ------------------------------------------------------------------ what kind of reference

    private val ic = setOf(RegexOption.IGNORE_CASE)
    private val signedRe = Regex("""\b(i(?:'ve| have)?\s+(?:just\s+)?(?:signed|logged)\s*(?:you\s+)?(?:in|on)|i(?:'m| am)\s+(?:signed|logged)\s+in|(?:signed|logged)\s+(?:you\s+)?in(?: now| already)?|done (?:signing|logging) in|login (?:done|worked)|i(?:'ve| have)?\s+(?:entered|put in|typed) (?:the |my |in )?(?:password|code|otp|pin)|sign[- ]?in (?:is )?done)\b""", ic)
    private val retryRe = Regex("""\b(retry|try (?:it |that |this )?again|give it another (?:go|try|shot)|one more (?:time|try)|redo|re-?run|again\?*$)""", ic)
    private val continueRe = Regex("""^\s*(?:ok(?:ay)?[, ]+)?(?:continue|carry on|keep going|go on|resume|proceed|finish (?:it|that|the job|up)|pick (?:it|that) (?:back )?up|where were we|go ahead(?: then)?|you can (?:continue|go on|proceed) now)\b""", ic)
    private val checkRe = Regex("""\b(check (?:it |that |again |now|back)|check (?:the |my |on )?[\w' ]{1,30}? (?:now|again)\b|check now|look again|any (?:news|update|change)s?|has it (?:changed|arrived|come)|is it (?:there|back|in stock|out) (?:yet|now)|refresh it|status now)\b""", ic)
    private val statusRe = Regex("""\b(how(?:'s| is) (?:it|that|the task) going|is it done|are you done|did it work|did you finish|what happened(?: with (?:it|that))?|any luck|where are we)\b""", ic)
    private val sameForRe = Regex("""\b(same (?:as before|thing|again|one)?\s*(?:but )?(?:for|with|in|on)\s+(.+)|do (?:the same|that|it) (?:for|with|in|on)\s+(.+)|now (?:for|with|in|do)\s+(.+)|what about\s+(.+))""", ic)
    private val againRe = Regex("""\b(do (?:it|that|this) again|same as (?:before|last time)|like (?:before|last time)|the usual|again please|repeat (?:it|that))\b""", ic)
    private val thatOneRe = Regex("""\b(that one|this one|the (?:first|second|third|last|other) one|that (?:link|file|song|page|result|report|thing|app)|the one you (?:found|sent|mentioned)|open (?:it|that)|send (?:it|that)|download (?:it|that))\b""", ic)

    fun kind(text: String): Kind {
        val t = text.trim()
        if (t.isEmpty()) return Kind.NEW
        return when {
            signedRe.containsMatchIn(t) -> Kind.SIGNED_IN
            sameForRe.containsMatchIn(t) && t.length < 120 -> Kind.SAME_FOR
            againRe.containsMatchIn(t) -> Kind.AGAIN
            retryRe.containsMatchIn(t) -> Kind.RETRY
            continueRe.containsMatchIn(t) -> Kind.CONTINUE
            statusRe.containsMatchIn(t) -> Kind.STATUS
            checkRe.containsMatchIn(t) -> Kind.CHECK
            thatOneRe.containsMatchIn(t) && t.length < 100 -> Kind.THAT_ONE
            else -> Kind.NEW
        }
    }

    /** The new subject in "same as before but for X" ("" when there is none). */
    fun substitution(text: String): String {
        val m = sameForRe.find(text.trim()) ?: return ""
        return (2..5).asSequence().mapNotNull { m.groups[it]?.value }.firstOrNull()?.trim()?.trimEnd('.', '?', '!').orEmpty().take(80)
    }

    // ------------------------------------------------------------------ which task

    private val stop = setOf("the", "a", "an", "it", "that", "this", "one", "to", "for", "of", "and", "or", "on", "in", "with", "my", "me", "you",
        "i", "is", "was", "be", "do", "did", "done", "again", "now", "please", "same", "before", "but", "can", "could", "would", "will", "just",
        "check", "retry", "try", "continue", "go", "ok", "okay", "yes", "thing", "time", "last", "as", "at", "up", "so", "what", "about", "signed",
        "logged", "login", "sign", "log", "have", "has", "its", "it's", "your", "we", "were", "where", "how", "going", "still", "back", "then",
        "there", "here", "first", "second", "other", "already", "finish", "keep", "carry", "resume", "repeat", "like", "usual", "any", "news")

    fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    fun words(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in stop }
        .map { stem(it) }.filter { it.length > 1 && it !in stop }.toSet()

    private fun overlap(text: String, r: Rec): Int {
        val q = words(text); if (q.isEmpty()) return 0
        val g = words(r.goal + " " + r.findings.joinToString(" ") + " " + r.needsSignIn + " " + r.signIns.joinToString(" "))
        return q.count { it in g }
    }

    private val pronounRe = Regex("(?i)\\b(it|that|this|one|them|those|these|too|also|as well)\\b")
    private val properRe = Regex("\\b\\p{Lu}\\p{Ll}+")

    private fun shapeMatch(sub: String, goal: String): Boolean = when {
        sub.any { it.isDigit() } -> goal.any { it.isDigit() }
        sub.firstOrNull()?.isUpperCase() == true -> properRe.findAll(goal).drop(1).any()
        else -> false
    }

    private val checkGoalRe = Regex("""(?i)\b(check|track|status|monitor|price|stock|delivery|order|package|parcel|inbox|email|score|weather|balance|flight)\b""")

    data class Resolution(val kind: Kind, val rec: Rec?, val substitution: String = "", val score: Int = 0)

    /**
     * The task [text] refers to among [recs] (any order; this chat's records). Words that name a task win (the newest
     * on a tie); otherwise the reference's kind picks: a sign-in → the task waiting for one; retry → the newest that
     * failed or stopped; continue → the newest unfinished; check → the newest checking task; again / same-for → the
     * newest finished. NEW (not a reference back) → no task, unless its words clearly name one.
     */
    fun resolve(text: String, recs: List<Rec>, now: Long = System.currentTimeMillis()): Resolution {
        val k = kind(text)
        val all = recs.sortedBy { it.updated }
        if (all.isEmpty()) return Resolution(k, null)
        val sub = if (k == Kind.SAME_FOR) substitution(text) else ""
        val scored = all.map { it to overlap(if (sub.isNotEmpty()) text.replace(sub, "", true) else text, it) }
        val best = scored.filter { it.second > 0 }.maxWithOrNull(compareBy<Pair<Rec, Int>> { it.second }.thenBy { it.first.updated })
        if (k == Kind.NEW) {
            // A short line that points back ("book the Marlin one", "play it") is a reference when one word names a task.
            val pointer = pronounRe.containsMatchIn(text) && text.trim().split(Regex("\\s+")).size <= 10
            return if (best != null && (best.second >= 2 || (pointer && best.second >= 1))) Resolution(k, best.first, score = best.second) else Resolution(k, null)
        }
        if (best != null) return Resolution(k, best.first, sub, best.second)
        val recent = all.filter { now - it.updated <= RECENT_MS }.ifEmpty { return Resolution(k, null, sub) }
        fun newest(p: (Rec) -> Boolean) = recent.lastOrNull(p)
        val pick = when (k) {
            Kind.SIGNED_IN -> newest { it.needsSignIn.isNotBlank() && it.state != State.DONE } ?: newest { it.state == State.WAITING_USER }
                ?: newest { it.needsSignIn.isNotBlank() } ?: newest { it.surface == Surface.BROWSER || it.surface == Surface.PHONE }
            Kind.RETRY -> newest { it.state == State.FAILED || it.state == State.STOPPED }
            Kind.CONTINUE -> newest { it.state == State.STOPPED || it.state == State.WAITING_USER || it.state == State.ACTIVE || it.state == State.FAILED }
            Kind.STATUS -> newest { it.state == State.ACTIVE || it.state == State.WAITING_USER } ?: newest { it.state != State.DONE }
            Kind.CHECK -> newest { checkGoalRe.containsMatchIn(it.goal) }
            Kind.SAME_FOR -> (if (sub.isNotEmpty()) newest { it.state == State.DONE && shapeMatch(sub, it.goal) } else null) ?: newest { it.state == State.DONE }
            Kind.AGAIN -> newest { it.state == State.DONE }
            Kind.THAT_ONE -> newest { it.findings.isNotEmpty() }
            Kind.NEW -> null
        } ?: recent.last()
        return Resolution(k, pick, sub)
    }

    // ------------------------------------------------------------------ the prompt block

    fun surfaceName(s: Surface): String = when (s) {
        Surface.BROWSER -> "the in-app Browser (browser_* tools)"
        Surface.PHONE -> "${BrandName.NAME} Phone (phone_* tools)"
        Surface.SCREEN -> "the phone screen (screen control)"
        Surface.TERMINAL -> "${BrandName.NAME} Terminal"
        Surface.NONE -> ""
    }

    /** The system-prompt block for a resolved reference ("" when nothing was resolved). */
    fun block(r: Resolution?): String {
        val t = r?.rec ?: return ""
        val sb = StringBuilder("\nTASK MEMORY (the user is referring back to an earlier task; continue THIS one, don't start over or ask what they mean): ")
        sb.append("goal \u201c").append(t.goal.take(300)).append("\u201d; state: ").append(t.state.name.lowercase().replace('_', ' '))
        if (t.surface != Surface.NONE) sb.append("; it was done in ").append(surfaceName(t.surface))
            .append(". Keep using that same surface (sign-ins live there); never switch to another surface for this task unless the user asks")
        if (t.signIns.isNotEmpty()) sb.append("; already signed in to: ").append(t.signIns.takeLast(4).joinToString(", "))
        if (t.needsSignIn.isNotBlank()) sb.append("; it was waiting for the user to sign in to ").append(t.needsSignIn)
        if (t.findings.isNotEmpty()) sb.append("; found so far: ").append(t.findings.takeLast(3).joinToString(" | ") { it.take(200) })
        if (t.next.isNotBlank()) sb.append("; next step: ").append(t.next.take(200))
        when (r.kind) {
            Kind.SIGNED_IN -> sb.append(". The user says they signed in: re-read the page on the same surface and carry on from the next step")
            Kind.RETRY -> sb.append(". Retry the step that failed (check first what already happened; never repeat a send or payment blindly)")
            Kind.CONTINUE -> sb.append(". Continue from the next step; don't redo finished steps")
            Kind.CHECK -> sb.append(". Check it again now and say what changed")
            Kind.SAME_FOR -> sb.append(". Do the same task again, but for \u201c").append(r.substitution).append("\u201d")
            Kind.AGAIN -> sb.append(". Do the same task again")
            Kind.THAT_ONE -> sb.append(". \u2018That one\u2019 means something from this task's findings")
            Kind.STATUS -> sb.append(". Answer how it stands from this record")
            Kind.NEW -> sb.append(". Their words name this task")
        }
        return sb.append(".").toString()
    }

    /** One standing line for the lead prompt: which surface to choose for web work. */
    const val SURFACE_RULE = "\nWEB SURFACES: a single-page web task (one site: log in, check, fill one form) goes to ${BrandName.NAME} Phone (phone_*); a task that " +
        "needs several tabs or pages side by side goes to the Browser (browser_*). Once a task has used one surface, stay on it for that task " +
        "(the user's sign-ins live there) and never switch mid-task unless the user asks."

    // ------------------------------------------------------------------ from a turn's tools

    /** The surface a turn's tools used (the newest web surface wins; screen / terminal only when no web surface was used). */
    fun surfaceOf(toolNames: List<String>): Surface {
        var web = Surface.NONE; var other = Surface.NONE
        for (n in toolNames) when {
            n.startsWith("browser_") || n == "open_url" -> web = Surface.BROWSER
            n.startsWith("phone_") -> web = Surface.PHONE
            n in setOf("tap", "type_text", "scroll", "swipe", "open_app", "read_screen", "look_at_screen", "press_button", "do_actions", "run_plan") -> other = Surface.SCREEN
            n in setOf("run_shell", "termux_run", "terminal_run") && other == Surface.NONE -> other = Surface.TERMINAL
        }
        return if (web != Surface.NONE) web else other
    }

    private val signInPage = Regex("""(?i)\b(sign[ -]?in|log[ -]?in|password|two-step|verification code|one-time code|otp)\b""")

    /** The host a web tool result showed a sign-in wall on ("" when none). */
    fun signInWall(toolName: String, args: JSONObject, result: String): String {
        if (!(toolName.startsWith("browser_") || toolName.startsWith("phone_"))) return ""
        if (!signInPage.containsMatchIn(result.take(1500))) return ""
        val url = args.optString("url").ifBlank { Regex("""https?://[^\s"')]+""").find(result)?.value.orEmpty() }
        return host(url).ifBlank { args.optString("url").take(60) }
    }

    fun host(url: String): String = try { java.net.URI(url.trim()).host?.removePrefix("www.").orEmpty() } catch (_: Exception) { "" }

    /** Is this a request to change surface ("use Blaze Phone", "do it in the browser")? */
    fun asksSwitch(text: String, to: Surface): Boolean = when (to) {
        Surface.PHONE -> Regex("""(?i)\b((?:argus|blaze) phone|use (?:the )?phone|on (?:the )?phone|phone browser)\b""").containsMatchIn(text)
        Surface.BROWSER -> Regex("""(?i)\b(use (?:the )?browser|in (?:the )?browser|(?:argus|blaze) browser|open (?:it )?in (?:a )?tab)\b""").containsMatchIn(text)
        else -> false
    }

    /**
     * The note to return instead of running [toolName] when it would move [task] to another web surface, or null to run
     * it. Only while the task is unfinished or was touched in the last hour, and never when the user asked to switch.
     */
    fun surfaceGuard(toolName: String, task: Rec?, userText: String, now: Long = System.currentTimeMillis()): String? {
        if (task == null) return null
        val to = when { toolName.startsWith("phone_") -> Surface.PHONE; toolName.startsWith("browser_") -> Surface.BROWSER; else -> return null }
        if (task.surface != Surface.BROWSER && task.surface != Surface.PHONE) return null
        if (to == task.surface) return null
        if (task.state == State.DONE && now - task.updated > 3600_000L) return null
        if (asksSwitch(userText, to)) return null
        return "Not run: this task (\u201c${task.goal.take(80)}\u201d) is being done in ${surfaceName(task.surface)}" +
            (if (task.signIns.isNotEmpty() || task.needsSignIn.isNotBlank()) " and the user's sign-in is there" else "") +
            ". Keep using ${if (task.surface == Surface.BROWSER) "browser_*" else "phone_*"} tools for it; switch only if the user asks."
    }

    // ------------------------------------------------------------------ updating a record

    /** The record after a turn on it ended. */
    fun afterTurn(r: Rec, tools: List<String>, state: State, finding: String, next: String, signInWall: String, signedIn: String, now: Long): Rec {
        val s = surfaceOf(tools).takeIf { it != Surface.NONE } ?: r.surface
        // A task keeps the web surface it started on (the guard stops a switch; this keeps a stray call from flipping it).
        val surface = if ((r.surface == Surface.BROWSER || r.surface == Surface.PHONE) && (s == Surface.SCREEN || s == Surface.TERMINAL)) r.surface else s
        val ins = (r.signIns + listOfNotNull(signedIn.takeIf { it.isNotBlank() })).distinct()
        val wall = when { signInWall.isNotBlank() -> signInWall; signedIn.isNotBlank() && signedIn == r.needsSignIn -> ""; else -> r.needsSignIn }
        val st = if (wall.isNotBlank() && state != State.DONE && state != State.FAILED) State.WAITING_USER else state
        return r.copy(state = st, surface = surface, signIns = ins, needsSignIn = wall,
            findings = (r.findings + listOfNotNull(finding.trim().takeIf { it.isNotBlank() }?.take(400))).takeLast(MAX_FINDINGS),
            next = next.ifBlank { if (st == State.DONE) "" else r.next }, updated = now, tools = (r.tools + tools).takeLast(16))
    }
}
