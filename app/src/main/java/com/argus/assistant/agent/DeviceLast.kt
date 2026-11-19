package com.argus.assistant.agent

/**
 * 0.53.7 (Finn, 2026-11-15: "I prefer if it uses its browser, phone and tools first before reaching for the device"):
 * Argus's own tools come first: web_search / fetch_page, its background browser, connectors, the terminal and the phone
 * tools that need no screen (alarms, SMS, calls, calendar). Driving the device's screen (open_app, run_plan, tap,
 * read_screen…) is the last resort, used when one of these holds:
 * - the user named the app, or asked for the phone or screen itself ("open YouTube", "on my phone", "in WhatsApp");
 * - Argus already tried one of its own tools for this request;
 * - the task already got this nudge once (the model chose the screen anyway, so it's let through).
 * Otherwise the first screen call of a request isn't run; it gets [NUDGE] back, so the turn carries on in the browser.
 * Nothing is asked of the user, and phone control is never requested for a task the browser can do.
 * Pure Kotlin, tested in DeviceLastTest.
 */
object DeviceLast {
    /** Calls that drive the device's screen or open another app on it. */
    val SCREEN: Set<String> = Orient.acting + Orient.looking + setOf("open_app", "search_in_app", "long_press", "swipe")

    /** Argus's own tools: running one of these first means a later screen call is a considered fallback. */
    val OWN: Set<String> = setOf("web_search", "fetch_page", "http_request", "run_shell", "run_code",
        "browser_open", "browser_read", "browser_click", "browser_type", "browser_show", "browser_tabs", "browser_find")

    private val VENDOR = setOf("google", "android", "samsung", "apps", "mobile", "lite")
    private val PHONE_WORDS = Regex("\\b(app|apps|phone|screen|device|tap|open)\\b", RegexOption.IGNORE_CASE)

    /** The app the call names, from its arguments ("app", "name", "package"). */
    fun appOf(argsJson: String): String =
        Regex("\"(?:app|name|package)\"\\s*:\\s*\"([^\"]+)\"").find(argsJson)?.groupValues?.get(1)?.trim().orEmpty()

    /** True when the user's own words ask for the device: a phone word, or the app's name (or its last word: "Google Maps" → "maps"). */
    fun userAsked(userText: String, app: String): Boolean {
        val t = userText.lowercase()
        if (PHONE_WORDS.containsMatchIn(t)) return true
        val a = app.lowercase().trim()
        if (a.isEmpty()) return false
        if (t.contains(a)) return true
        // "Google Maps" → maps, "com.spotify.music" → spotify / music: any word of 4+ letters that isn't a vendor word
        return a.split(' ', '.', '_', '-').filter { it.length >= 4 && it !in VENDOR }.any { t.contains(it) }
    }

    /** The result to return instead of running [tool], or null to run it. */
    fun nudge(tool: String, argsJson: String, userText: String, triedOwn: Boolean, alreadyNudged: Boolean): String? {
        if (tool !in SCREEN || triedOwn || alreadyNudged) return null
        if (userText.isBlank() || userAsked(userText, appOf(argsJson))) return null
        return NUDGE
    }

    const val NUDGE = "Not run: use your own tools before the phone's screen. For this, try web_search / fetch_page, or your " +
        "background browser (browser_open, e.g. https://www.google.com/maps/search/<query> for places, " +
        "https://www.youtube.com/results?search_query=<query> for videos), or a connected service. Use the phone's apps " +
        "only if those can't do it, the user asks for an app, or it needs their signed-in app. Don't mention this note."
}
