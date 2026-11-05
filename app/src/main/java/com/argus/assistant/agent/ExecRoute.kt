package com.argus.assistant.agent


import com.argus.assistant.BrandName
/**
 * 0.51.0 terminal-first routing (owner: "many tasks are faster in Blaze Terminal than by opening apps or tapping").
 *
 * For each request, which surface should do the work:
 *  - [Exec.TERMINAL]: the task is easy as a command — web lookups (curl / http_request / fetch_page), file work, sums and
 *    conversions, git, package / version lookups, API calls, text processing, downloads, network checks, hashes. Blaze uses
 *    run_shell / run_code / http_request / fetch_page instead of opening apps, tapping or driving the browser.
 *  - [Exec.HTTP]: the same kind of task on a phone where the terminal should not be started (low-RAM: the low-RAM rule wins;
 *    or Linux not installed and the task needs more than BusyBox): the built-in HTTP tools (web_search, fetch_page,
 *    http_request), still never browser taps.
 *  - [Exec.PHONE]: needs a GUI app or a signed-in app session (send a WhatsApp message, call, post on Instagram, settings
 *    toggles, play music, take a photo).
 *  - [Exec.BROWSER]: needs a website session or interaction (log in, book, buy, fill a form, my account).
 *  - [Exec.ANY]: nothing to prefer (chat, writing, questions the model answers itself).
 *
 * It only adds a routing hint and the terminal tool group; it never removes a tool, and every command still goes through
 * CommandPolicy / the confirm card ([needsConfirm] is the router's view of that rule: destructive or sending commands ask).
 * Pure (ExecRouteTest).
 */
object ExecRoute {
    enum class Exec { TERMINAL, HTTP, PHONE, BROWSER, ANY }

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** Needs an app's GUI or the user's signed-in app session on the phone. */
    private val phone = r("""\b(?:whatsapp|telegram|instagram|tiktok|facebook|messenger|snapchat|signal|sms|text (?:him|her|them|mom|dad|my)|send (?:a )?(?:message|text|voice note|photo|pic)|message (?:my|to)|call (?:my|him|her|them|mom|dad|\w+ now)|dial|ring|facetime|video call|take a (?:photo|picture|selfie|screenshot)|selfie|camera|alarm|timer|torch|flashlight|bluetooth|wi-?fi|hotspot|brightness|volume|do not disturb|airplane mode|play (?:music|a song|my playlist|\w+ on spotify)|spotify|youtube music|open (?:the )?(?:app|settings|camera|gallery|maps|whatsapp|instagram|youtube|play store)|tap|swipe|scroll|on (?:my|the) screen|navigate to|directions to|uber|yango|bolt ride)\b""")
    /** Needs a website session or a form. */
    private val browser = r("""\b(?:log ?in|sign ?in|sign ?up|my account|book (?:a|an|the|me)|reserve|checkout|check out|buy|order (?:a|an|the|me|food)|purchase|pay (?:for|my)|fill (?:in|out)|apply (?:for|to)|submit (?:the |a )?form|subscribe|cancel (?:my )?subscription|add to cart|in the browser|on the website)\b""")
    /** Easy as a command. */
    private val terminal = r("""\b(?:search(?: the web| online| for)?|look up|lookup|google|latest (?:version|release)|what version|which version|release notes|changelog|download|curl|wget|http request|api|endpoint|json|xml|csv|yaml|fetch (?:the |this )?(?:page|url|site)|scrape|git|clone|commit|repo(?:sitory)?|pip|npm|cargo|gradle|maven|apk add|package|dependency|library version|calculate|compute|convert|how many (?:days|hours|minutes|bytes)|percentage|hash|sha256|md5|base64|encode|decode|regex|grep|sed|awk|sort|count (?:the )?(?:words|lines)|word count|unzip|zip|extract (?:the )?(?:zip|archive|tar)|rename (?:the )?files?|move (?:the )?files?|copy (?:the )?files?|find (?:the )?files?|delete (?:the )?files?|file size|disk (?:space|usage)|storage used|ping|traceroute|dns|ip address|my ip|is \S+ (?:up|down)|network speed|speed test|port|ssl cert|certificate|whois|timestamp|epoch|unix time|python|script|run (?:this |the )?(?:code|command)|terminal|shell|linux)\b""")

    /**
     * The surface for [text]. [terminalReady]: Blaze Terminal can run it (BusyBox shell is built in; Alpine for curl,
     * Python, git). [lowRam]: the low-RAM rule (no Linux start on low-RAM phones) — the HTTP tools are used instead.
     */
    fun route(text: String, terminalReady: Boolean = true, lowRam: Boolean = false): Exec {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("/")) return Exec.ANY
        val p = phone.containsMatchIn(t)
        val b = browser.containsMatchIn(t)
        val k = terminal.containsMatchIn(t)
        return when {
            p -> Exec.PHONE
            b -> Exec.BROWSER
            k && terminalReady && !lowRam -> Exec.TERMINAL
            k -> Exec.HTTP
            else -> Exec.ANY
        }
    }

    /** The router's view of the confirm rule: a destructive or sending command never runs without the card. */
    private val destructiveWords = r("""\b(?:delete|remove|erase|wipe|format|factory|reset|uninstall|truncate|drop\s+table|send|sms|email|mail|post|publish|pay|transfer|push\s+(?:-f|--force)|force-push)\b""")
    fun needsConfirm(cmd: String): Boolean = destructiveWords.containsMatchIn(cmd) || Regex("""(?i)\brm\s|\bmkfs|\bdd\s+if=|>\s*/dev/|\bchmod\s+-R\s+777|\bcurl\b[^|]*\|\s*(?:ba)?sh""").containsMatchIn(cmd)

    /** Every prompt carries the rule; the turn hint names the route for this message. */
    const val PROMPT = "\nTERMINAL FIRST: when a task is easy as a command (web lookups with curl / http_request / fetch_page, files, sums and conversions, " +
        "git, package or version lookups, API calls, text processing, downloads, network checks), do it in ${BrandName.NAME} Terminal (run_shell, run_code) or with the " +
        "HTTP tools instead of opening apps, tapping the screen or driving the browser. Use phone control only for GUI apps or a signed-in app session, and " +
        "the browser only for sites that need a login, a form or a click. Destructive or sending commands still need the user's OK (the confirm card)."

    fun hint(e: Exec): String = when (e) {
        Exec.TERMINAL -> "\nROUTE FOR THIS MESSAGE: terminal. Do it with run_shell / run_code / http_request / fetch_page; don't open apps or the browser for it."
        Exec.HTTP -> "\nROUTE FOR THIS MESSAGE: the built-in HTTP tools (web_search, fetch_page, http_request); this phone shouldn't start Linux now, and don't drive the browser for it."
        else -> ""
    }

    /** The tool groups the route adds (never removes any). */
    fun groups(e: Exec): Set<String> = if (e == Exec.TERMINAL) setOf("terminal") else emptySet()
}
