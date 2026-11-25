package com.blaze.assistant.browser.phone


import com.blaze.assistant.BrandName
import com.blaze.assistant.browser.SecureInput
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.45.19 Argus Phone browser: the agent's tools. Argus uses its own phone the way an agent uses a computer for the
 * user: browsing, logging in, booking, with a live view in the chat and Take control / Hand back.
 *
 *  - Anything that pays, books, sends, submits or deletes asks first (the confirm card, ConfirmGate always = true).
 *  - Card numbers are never typed by Argus: the user takes control for them.
 *  - Done means verified: every page read carries a VERIFIED / NOT VERIFIED line ([PhonePolicy.verify]); the tool text
 *    tells the agent never to say booked / confirmed without it.
 *
 * Pure of Android (it drives a [PhoneDriver]), so the JVM sims run the exact tool code.
 */
object PhoneBrowserTools {
    val names = PhonePolicy.TOOLS

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)
    private fun e(d: String, vararg v: String) = JSONObject().put("type", "string").put("description", d).put("enum", JSONArray(v.toList()))

    const val RULE = "${BrandName.NAME} Phone is ${BrandName.NAME}'s own phone screen: a real browser that keeps the user's sign-ins per site and runs in the background " +
        "while they use other apps (they can watch it live in the chat and take control). Use it to log in, book, order, fill forms and check accounts on websites. " +
        "Never say something is booked, ordered, paid or confirmed unless the page read says VERIFIED; otherwise say exactly what the page shows. " +
        "Users never give web addresses: pass what they called the site to phone_open as they said it ('the booking site', 'my bank', 'herokuapp login page', " +
        "'the-internet login'); ${BrandName.NAME} picks the site (ones they used before first, then a search that skips ads and look-alike sites, official domain) and the " +
        "sub-page (e.g. the login form). If it says it can't tell which site, ask the user; never guess or invent a URL. " +
        "No games; on banking sites open and read only, never move money."

    /** The phone_* tools that can name an Argus Phone instance (browser/phone/instances); without it they use the active one. */
    private val IN_INSTANCE = setOf("phone_open", "phone_read", "phone_act", "phone_screenshot", "phone_wait")

    fun definitions(): List<JSONObject> = defs().onEach { d ->
        val f = d.getJSONObject("function")
        if (f.getString("name") in IN_INSTANCE) f.getJSONObject("parameters").getJSONObject("properties")
            .put("instance", p("string", "${BrandName.NAME} Phone instance name or id (default: the active one)"))
    }

    private fun defs(): List<JSONObject> = listOf(
        fn("phone_open", "$RULE Opens a site on ${BrandName.NAME} Phone: a URL, or what the user called it (resolved for you), and returns the page: a VERIFIED/NOT VERIFIED line, " +
            "elements with ids like [e12], and the text.",
            JSONObject().put("url", p("string", "A URL, or the site as the user named it: 'the booking site', 'my bank', 'github login'")), listOf("url")),
        fn("phone_read", "Reads the ${BrandName.NAME} Phone page again: verification line, interactive elements with stable ids (password and card values hidden), headings and text. " +
            "Call it before saying anything is done."),
        fn("phone_act", "Acts on the ${BrandName.NAME} Phone page with real taps and keys. action: click (target), type (target + text; submit=true presses Enter), " +
            "key (key: enter/tab/back/escape/backspace/up/down…), scroll (direction), select (target + option), navigate (text = URL), back, forward, reload, " +
            "fill_login (fills this site's saved login, not submitted; text = which username if several). target = an element id from phone_read (e.g. e12) or its visible words. " +
            "Typing {{secret:name}} uses a value the user saved, never shown to you. Buttons that pay, book, send, submit or delete ask the user first. " +
            "Card numbers: never type them; use phone_handoff. Passwords and 2FA codes: phone_handoff kind=sign_in / kind=code.",
            JSONObject().put("action", e("What to do", *PhonePolicy.ACTIONS.toTypedArray()))
                .put("target", p("string", "Element id from phone_read (e12) or its visible words"))
                .put("text", p("string", "Text to type, URL for navigate, username for fill_login"))
                .put("option", p("string", "Option text for select"))
                .put("key", e("Key for action=key", *PhonePolicy.KEYS.toTypedArray()))
                .put("direction", e("Scroll direction", "down", "up"))
                .put("submit", p("boolean", "After typing, press Enter"))
                .put("clear", p("boolean", "Replace what's in the field (default true)")), listOf("action")),
        fn("phone_screenshot", "A picture of ${BrandName.NAME} Phone's screen, attached for you to look at (use it to check a confirmation page, a captcha, or a layout the read doesn't explain)."),
        fn("phone_wait", "Waits on ${BrandName.NAME} Phone until a CSS selector is visible, some text appears, or (gone=true) disappears; with neither, until the page loads. Timeout 0.5-30 s (default 10).",
            JSONObject().put("selector", p("string", "CSS selector")).put("text", p("string", "Text to wait for"))
                .put("gone", p("boolean", "Wait for it to go away")).put("timeout", p("number", "Seconds"))),
        fn("phone_handoff", "Asks the user for something only they can give, the easiest way first. kind=sign_in: a secure card in the chat for this page's " +
            "email / username and password fields (a saved login fills silently); the values go straight into the page and you only ever see placeholders. " +
            "kind=code: the same for a one-time code. kind=choice: the question in reason with the page's options as buttons; returns the pick. " +
            "kind=control (default): the user takes control of ${BrandName.NAME} Phone (a captcha, a drawing, a slider, a card form, a page that won't take a filled field) " +
            "and you wait until they hand back (up to 10 minutes), then get a fresh read. Never ask for a password or code in the chat.",
            JSONObject().put("reason", p("string", "One short line the user sees, e.g. 'Sign in to Demo Tables', 'Solve the captcha', 'Which time slot?'"))
                .put("kind", e("What to ask for (default control)", *Handoffs.KINDS.toTypedArray()))
                .put("options", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "kind=choice: 2 to 5 options as the page words them")),
            listOf("reason")),
        fn("phone_status", "What ${BrandName.NAME} Phone is doing: who is in control, the page, the site session, live view and memory."),
    )

    fun describe(name: String, a: JSONObject): String = when (name) {
        "phone_open" -> "Opening ${hostish(a.optString("url"))} on ${BrandName.NAME} Phone"
        "phone_read" -> "Reading ${BrandName.NAME} Phone's page"
        "phone_act" -> when (a.optString("action")) {
            "click" -> "Tapping " + readable(a.optString("target"), "on the page")
            "type" -> "Typing on ${BrandName.NAME} Phone"
            "key" -> "Pressing ${a.optString("key").ifBlank { "a key" }}"
            "scroll" -> "Scrolling ${BrandName.NAME} Phone"
            "select" -> "Choosing " + readable(a.optString("option"), "an option")
            "navigate" -> "Going to ${hostish(a.optString("text"))}"
            "fill_login" -> "Filling your saved login"
            else -> "Working on ${BrandName.NAME} Phone"
        }
        "phone_screenshot" -> "Looking at ${BrandName.NAME} Phone"
        "phone_wait" -> "Waiting for the page"
        "phone_handoff" -> when (a.optString("kind")) { "sign_in" -> "Asking you to sign in"; "code" -> "Asking you for the code"; "choice" -> "Asking you to choose"; else -> "Asking you to take control" }
        else -> "Checking ${BrandName.NAME} Phone"
    }

    /**
     * 0.45.19 (phone 10:07 "Tapping e61"): the page's element refs (e61, ref=e12, [e3]) and CSS / XPath selectors never
     * reach the status line; a visible label does ("Reserve Now"). Pure.
     */
    fun readable(target: String, fallback: String): String {
        val t = target.trim().trim('"', '\'')
        if (t.isEmpty()) return fallback
        val ref = Regex("^\\[?(ref\\s*[=:]\\s*)?#?e\\d+\\]?$", RegexOption.IGNORE_CASE)
        val selector = Regex("^(//|/html|[#.\\[]|\\w+\\[|\\w+[#.][\\w-]+)|[>~]\\s|:nth-|::|=\\s*['\"]")
        if (ref.matches(t) || selector.containsMatchIn(t) || t.length > 60) return fallback
        return t.take(40)
    }

    private fun hostish(u: String) = u.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/').take(40)

    /** The user's answer to a confirm card: true = go ahead. */
    // (a plain suspend function type: confirm(title, detail, action))

    internal const val NO_PAGE = "${BrandName.NAME} Phone has no page open. Use phone_open first."

    suspend fun run(d: PhoneDriver, name: String, a: JSONObject, shots: MutableList<String>, confirm: suspend (String, String, String) -> Boolean,
                    cards: HandoffCards? = null): String {
        // 0.45.19-speed: timings for phone_status (the user's approval time on a confirm card isn't Argus's).
        val t0 = d.now()
        var waited = 0L
        val timedConfirm: suspend (String, String, String) -> Boolean = { t, de, ac -> val c0 = d.now(); try { confirm(t, de, ac) } finally { waited += d.now() - c0 } }
        val out = runInner(d, name, a, shots, timedConfirm, cards)
        when (name) {
            "phone_read" -> PhonePerf.add(PhonePerf.READ, d.now() - t0)
            "phone_act" -> PhonePerf.add(PhonePerf.ACT, d.now() - t0 - waited)
        }
        return out
    }

    private suspend fun runInner(d: PhoneDriver, name: String, a: JSONObject, shots: MutableList<String>, confirm: suspend (String, String, String) -> Boolean,
                                 cards: HandoffCards?): String = try {
        d.step(describe(name, a))
        when (name) {
            // 0.45.19-speed: status always says plainly what was and wasn't completed (device test: "Booking confirmed!" mid-task).
            "phone_status" -> d.status() + "\n" + progress(d)
            "phone_open" -> {
                val url = a.optString("url").trim()
                if (url.isEmpty()) "Give a URL or the site's name." else if (SiteResolver.isUrlish(url)) d.open(url) else openByName(d, url)
            }
            "phone_read" -> if (!d.hasPage()) NO_PAGE else if (!d.awaitAgentTurn()) USER_HAS_IT else d.snapshot()?.let { PhonePolicy.render(it) } ?: readFallback(d, shots)
            "phone_screenshot" -> if (!d.hasPage()) NO_PAGE else {
                val shot = d.screenshot()
                val v = d.snapshot()?.let { PhonePolicy.verify(it).line() }.orEmpty()
                if (shot == null) "No picture on this phone (${BrandName.NAME} Phone runs offscreen here). Use phone_read. $v".trim()
                else { shots += shot; "Screenshot of ${BrandName.NAME} Phone taken; it is attached in the next message. $v".trim() }
            }
            "phone_wait" -> if (!d.hasPage()) NO_PAGE else d.waitFor(a.optString("selector").ifBlank { null }, a.optString("text").ifBlank { null },
                a.optBoolean("gone", false), PhonePolicy.waitMs(if (a.has("timeout")) a.optDouble("timeout") else null))
            "phone_handoff" -> Handoffs.run(d, a.optString("kind"), a.optString("reason").ifBlank { "${BrandName.NAME} needs you for a step" },
                a.optJSONArray("options")?.let { o -> (0 until o.length()).map { o.optString(it) } } ?: emptyList(), cards)
            "phone_act" -> act(d, a, confirm)
            else -> "Unknown ${BrandName.NAME} Phone tool."
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "${BrandName.NAME} Phone error: ${SecureInput.scrub(e.message.orEmpty().take(200))}" }

    /** What Argus Phone has and hasn't completed, from the page as it is now. Never claims more than the page shows. */
    suspend fun progress(d: PhoneDriver): String {
        if (!d.hasPage()) return "Nothing open on ${BrandName.NAME} Phone: nothing booked, ordered or paid."
        val s = try { d.snapshot() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            ?: return "Couldn't read the page right now: not booked, ordered or paid as far as ${BrandName.NAME} can see (no confirmation seen)."
        return "So far: " + PhonePolicy.verify(s).progress() + " Page: " + s.title.take(80) + "."
    }

    /** The DOM couldn't be read: only then a screenshot (never as a substitute for verification). */
    private suspend fun readFallback(d: PhoneDriver, shots: MutableList<String>): String {
        val shot = d.screenshot()
        return if (shot != null) { shots += shot
            "The page's elements couldn't be read right now (still loading, or the page blocks it); a screenshot of ${BrandName.NAME} Phone is attached in the next message. " +
                "NOT VERIFIED: nothing counts as done from a picture; phone_wait, then phone_read." }
        else "Couldn't read this page (still loading?). phone_wait, then phone_read."
    }

    /**
     * 0.45.19-speed: phone_open with what the user called a site ([SiteResolver]): the resolved page, then the sub-page
     * the words asked for (tapping the site's own "Log in" / "Sign up" / "Orders" link when the search had no deep link).
     */
    private suspend fun openByName(d: PhoneDriver, q: String): String {
        val r = d.resolve(q)
        val url = r.url ?: return "Couldn't tell which site \u201c${q.take(60)}\u201d is. ${r.ask ?: "Ask the user which site they mean."}"
        d.step("Opening ${hostish(url)}")
        val opened = d.open(url)
        val head = "\u201c${q.take(60)}\u201d \u2192 $url (${r.why})."
        val sub = r.subpage ?: return "$head\n$opened"
        val s = d.snapshot() ?: return "$head\n$opened"
        if (SiteResolver.onSubpage(sub, s.url, s.passwordVisible)) return "$head\n$opened"
        val re = SiteResolver.linkRe(sub) ?: return "$head\n$opened"
        val node = s.nodes.filter { (it.role == "link" || it.role == "button") && !it.disabled && re.containsMatchIn(it.name) }.minByOrNull { it.name.length }
            ?: return "$head There's no $sub link on this page; look for it with phone_read.\n$opened"
        val t = d.locate(node.id, false) ?: return "$head\n$opened"
        if (PhonePolicy.gate("click", t.label, t.node(), false) != PhonePolicy.Gate.PASS)
            return "$head \u201c${t.label.take(40)}\u201d may submit something; tap it with phone_act only if that's what the user wants.\n$opened"
        d.step("Tapping \u201c${t.label.take(40)}\u201d")
        val c = d.click(t)
        val now = d.snapshot()?.let { PhonePolicy.render(it) } ?: "Couldn't read the page yet; phone_read."
        return "$head Went to the $sub page (tapped \u201c${t.label.take(40)}\u201d). ${c.text}\n\nNow:\n$now"
    }

    internal const val USER_HAS_IT = "The user is in control of ${BrandName.NAME} Phone and hasn't handed back. Don't act; tell them you'll continue when they tap Hand back."

    private fun refused(label: String) = "The user did NOT approve \u201c${label.take(50)}\u201d. Don't retry; tell them it's ready for them to finish (phone_handoff)."

    private suspend fun after(d: PhoneDriver, r: PhoneDriver.ActResult, pageChange: Boolean): String {
        if (!r.ok || !pageChange) return r.text
        val s = d.snapshot() ?: return r.text
        return r.text + "\n\nNow:\n" + PhonePolicy.render(s, maxText = 1500, maxNodes = 60)
    }

    private suspend fun act(d: PhoneDriver, a: JSONObject, confirm: suspend (String, String, String) -> Boolean): String {
        val action = a.optString("action").lowercase()
        if (action !in PhonePolicy.ACTIONS) return "Unknown action \u201c$action\u201d. Use one of: ${PhonePolicy.ACTIONS.joinToString()}."
        if (action == "navigate") return a.optString("text").ifBlank { a.optString("target") }.let { if (it.isBlank()) "navigate needs text = the URL." else d.open(it) }
        if (!d.hasPage()) return NO_PAGE
        if (!d.awaitAgentTurn()) return USER_HAS_IT
        val site = d.site().ifBlank { "this site" }
        val target = a.optString("target").trim()
        return when (action) {
            "click" -> {
                if (target.isEmpty()) return "click needs a target (an id like e12, or its words)."
                val t = d.locate(target, false) ?: return "No element \u201c${target.take(40)}\u201d on the page now. phone_read for fresh ids."
                if (t.label.isNotBlank()) d.step("Tapping \u201c${t.label.take(40)}\u201d")
                val card = d.focusInfo().card
                when (PhonePolicy.gate("click", t.label, t.node(), card)) {
                    PhonePolicy.Gate.CONFIRM -> if (!confirm("Tap \u201c${t.label.take(40)}\u201d on $site?", "On ${BrandName.NAME} Phone. This looks like it books, pays, sends or submits something.", "Tap")) return refused(t.label)
                    PhonePolicy.Gate.REFUSE -> return "${BrandName.NAME} doesn't press that. Use phone_handoff."
                    PhonePolicy.Gate.PASS -> {}
                }
                after(d, d.click(t), true)
            }
            "type" -> {
                val text = a.optString("text")
                if (text.isEmpty()) return "type needs text."
                val t = (if (target.isNotEmpty()) d.locate(target, true) else d.locate(":focus", true))
                    ?: return "No field \u201c${target.take(40)}\u201d. phone_read for fresh ids."
                d.step("Typing into \u201c${t.label.take(40).ifBlank { "a field" }}\u201d")
                val submit = a.optBoolean("submit", false)
                val f = d.focusInfo()
                when (PhonePolicy.gate("type", if (submit) f.submit.ifBlank { t.label } else t.label, t.node(), f.card, submit)) {
                    PhonePolicy.Gate.REFUSE -> return "\u201c${t.label.take(40)}\u201d is a card field. ${BrandName.NAME} never types card details: call phone_handoff so the user enters them."
                    PhonePolicy.Gate.CONFIRM -> if (!confirm("Submit on $site?", "${BrandName.NAME} Phone will type into \u201c${t.label.take(40)}\u201d and press Enter, which submits \u201c${f.submit.ifBlank { "the form" }.take(40)}\u201d.", "Submit"))
                        return refused(f.submit.ifBlank { "submit" })
                    PhonePolicy.Gate.PASS -> {}
                }
                after(d, d.type(t, text, a.optBoolean("clear", true), submit), submit)
            }
            "key" -> {
                val k = a.optString("key").ifBlank { a.optString("text") }.lowercase()
                if (k !in PhonePolicy.KEYS) return "Unknown key \u201c$k\u201d. Use one of: ${PhonePolicy.KEYS.joinToString()}."
                val f = d.focusInfo()
                val isSubmit = k == "enter" && f.inForm && PhonePolicy.irreversible(f.submit)
                if (PhonePolicy.gate("key", k, null, f.card && k == "enter", isSubmit) == PhonePolicy.Gate.CONFIRM &&
                    !confirm("Press Enter on $site?", "On ${BrandName.NAME} Phone this submits \u201c${f.submit.ifBlank { "the form" }.take(40)}\u201d.", "Submit")) return refused(f.submit.ifBlank { "Enter" })
                d.key(k)
                after(d, PhoneDriver.ActResult(true, "Pressed ${k.replaceFirstChar { it.uppercase() }}."), k == "enter" || k == "back")
            }
            "select" -> {
                val opt = a.optString("option").ifBlank { a.optString("text") }
                if (target.isEmpty() || opt.isEmpty()) return "select needs target and option."
                val t = d.locate(target, true) ?: return "No dropdown \u201c${target.take(40)}\u201d. phone_read for fresh ids."
                if (PhonePolicy.gate("select", opt, t.node()) == PhonePolicy.Gate.CONFIRM &&
                    !confirm("Choose \u201c${opt.take(40)}\u201d on $site?", "On ${BrandName.NAME} Phone. This option looks like it confirms or pays.", "Choose")) return refused(opt)
                d.select(t, opt).text
            }
            "scroll" -> d.scroll(a.optString("direction", "down") != "up").text
            "back", "forward", "reload" -> after(d, d.navigate(action), true)
            "fill_login" -> d.fillLogin(a.optString("text").ifBlank { null }).text
            else -> "Unknown action."
        }
    }
}

/** Short names for the Secure Form types (the tests and the cards use them). */
typealias SecureField = SecureInput.Field
typealias SecureAnswer = SecureInput.Answer

/**
 * Argus Phone step 2 (docs/ARGUS-PHONE.md 4a, the handoff ladder): the chat cards phone_handoff can raise instead of
 * Take control. The agent supplies them; without them every kind falls back to Take control.
 */
interface HandoffCards {
    /** The Secure Form card for [site]; null = cancelled or no answer. Values go to the page, never to the model. */
    suspend fun secure(site: String, title: String, reason: String, fields: List<SecureInput.Field>): SecureInput.Answer?
    /** A choice card; the user's pick or what they typed instead. */
    suspend fun choose(question: String, options: List<String>): String
    /** A value saved for [site] (the per-site vault), or null. */
    fun saved(site: String, name: String): String? = null
    fun save(site: String, name: String, value: String) {}
}

/**
 * phone_handoff kinds (step 2). sign_in / code read the page's own fields (DomCache's password and sensitive flags),
 * fill silently from the vault when it has them, otherwise raise the Secure Form card and type the answers as
 * {{secret:name}} placeholders, so the model never sees a value. Card fields are never asked for (Take control).
 * Nothing is submitted: the model taps the page's own button, which the confirm gate still guards. Pure of Android
 * (PhoneHandoffTest drives it with the demo site).
 */
object Handoffs {
    val KINDS = listOf("control", "sign_in", "code", "choice")
    /** A Secure Form field and the page element it fills. */
    data class Slot(val field: SecureInput.Field, val node: String)

    private val codeWords = Regex("(?i)\\bcode\\b|otp|one.?time|verification|2fa|mfa|passcode|\\bpin\\b|token")
    private val whoWords = Regex("(?i)e-?mail|user ?name|login|account|\\bid\\b|phone|mobile")
    private fun input(n: PhonePolicy.Node) = n.role in setOf("textbox", "input", "searchbox") && !n.disabled && !n.card

    /** The sign-in fields on the page: the account field (email, phone or username) and the password, when shown. */
    fun signIn(s: PhonePolicy.Snapshot): List<Slot> {
        val ins = s.nodes.filter(::input)
        val pw = ins.firstOrNull { it.type == "password" } ?: ins.firstOrNull { it.sensitive && !codeWords.containsMatchIn(it.name) }
        val who = ins.firstOrNull { it !== pw && !it.sensitive && (it.type == "email" || it.type == "tel" || whoWords.containsMatchIn(it.name)) }
        val out = ArrayList<Slot>()
        if (who != null) {
            val k = SecureInput.kindOf(who.type, who.name).takeIf { it == SecureInput.Kind.EMAIL || it == SecureInput.Kind.PHONE } ?: SecureInput.Kind.USERNAME
            out += Slot(SecureInput.Field(k.id, who.name.ifBlank { k.label }.take(40), k, who.id), who.id)
        }
        if (pw != null) out += Slot(SecureInput.Field("password", "Password", SecureInput.Kind.PASSWORD, pw.id), pw.id)
        return out
    }

    /** The one-time code field: one named like a code, else the only field on the page. */
    fun code(s: PhonePolicy.Snapshot): Slot? {
        val ins = s.nodes.filter(::input)
        val n = ins.firstOrNull { it.type != "password" && codeWords.containsMatchIn(it.name) } ?: ins.singleOrNull() ?: return null
        return Slot(SecureInput.Field("code", "Code", SecureInput.Kind.OTP, n.id), n.id)
    }

    /** Sites whose card the user cancelled, and when: no new card there for [QUIET_MS] (the 0.30.5 rule). */
    private val cancelled = java.util.concurrent.ConcurrentHashMap<String, Long>()
    const val QUIET_MS = 10 * 60_000L
    fun forget() = cancelled.clear()

    suspend fun run(d: PhoneDriver, kind0: String, reason: String, options: List<String>, cards: HandoffCards?): String {
        val kind = kind0.trim().lowercase().replace('-', '_').ifBlank { "control" }
        if (kind !in KINDS) return "Unknown kind \u201c${kind.take(20)}\u201d. Use one of: ${KINDS.joinToString()}."
        if (kind == "control" || cards == null) return d.handoff(reason)
        if (kind == "choice") {
            val opts = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5)
            if (opts.size < 2) return "kind=choice needs 2 to 5 options, worded as the page has them."
            d.step("Waiting for you: ${reason.take(60)}")
            return "The user chose: \u201c${cards.choose(reason, opts).take(200)}\u201d. Act on it with phone_act."
        }
        if (!d.hasPage()) return PhoneBrowserTools.NO_PAGE
        if (!d.awaitAgentTurn()) return PhoneBrowserTools.USER_HAS_IT
        val s = d.snapshot() ?: return "Couldn't read the page right now. phone_wait, then phone_handoff again."
        val site = d.site().ifBlank { SecureInput.hostOf(s.url) }.ifBlank { "this site" }
        cancelled[site]?.let { if (d.now() - it < QUIET_MS) return "The user cancelled the card for $site a moment ago. Don't ask again: stop this sign-in and say in one line what you needed." }
        val slots = if (kind == "sign_in") signIn(s) else listOfNotNull(code(s))
        if (slots.isEmpty()) return (if (kind == "sign_in") "No sign-in field on this page." else "No code field on this page.") +
            " phone_read; if it's on the next step go there first, or use phone_handoff with kind=control."
        val fields = slots.map { it.field }
        val vault = if (kind == "sign_in") fields.associate { it.name to cards.saved(site, it.name) } else emptyMap()
        val fromVault = vault.isNotEmpty() && vault.values.all { !it.isNullOrEmpty() }
        val answer = if (fromVault) { d.step("Using your saved sign-in for $site"); SecureInput.Answer(vault.mapValues { it.value.orEmpty() }, false) }
        else {
            d.step("Waiting for you: ${reason.take(60)}")
            cards.secure(site, reason, if (kind == "code") "The code goes straight into $site." else "Your details go straight into $site and never through the chat.", fields)
                ?: run {
                    cancelled[site] = d.now()
                    return "The user cancelled the card (or didn't answer). STOP this sign-in now and don't ask again for $site. Say in one line what you needed; " +
                        "offer phone_handoff kind=control if they want to do it themselves."
                }
        }
        answer.values.forEach { (n, v) -> SecureInput.put(n, v) }
        if (answer.save) fields.filter { it.kind.saveable }.forEach { f -> answer.values[f.name]?.takeIf { it.isNotEmpty() }?.let { cards.save(site, f.name, it) } }
        val missed = ArrayList<String>()
        for (sl in slots) {
            if (answer.values[sl.field.name].isNullOrEmpty()) { missed += sl.field.label; continue }
            val t = d.locate(sl.node, true)
            val r = t?.let { d.type(it, SecureInput.placeholder(sl.field.name), true, false) }
            if (r == null || !r.ok) missed += sl.field.label
        }
        val filled = fields.filter { it.label !in missed }.joinToString(", ") { "${it.label} as ${SecureInput.placeholder(it.name)}" }
        val head = (if (fromVault) "Filled the saved sign-in for $site: " else "The user answered; filled on $site: ") + filled.ifBlank { "nothing" } + " (values hidden)." +
            (if (answer.save) " Saved for $site." else "") +
            (if (missed.isNotEmpty()) " Couldn't fill ${missed.joinToString()}: phone_read, then phone_act type with its placeholder." else "") +
            " Not submitted: tap the page's sign-in / continue button with phone_act."
        val now = d.snapshot()?.let { PhonePolicy.render(it, maxText = 1200, maxNodes = 40) }
        return SecureInput.scrub(if (now == null) head else "$head\n\nNow:\n$now")
    }
}

