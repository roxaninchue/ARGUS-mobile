package com.blaze.assistant.agent

/**
 * 0.48.0 RC5 (owner's A12, chat over Home): "can you control my phone?" -> Blaze offered to set it up -> "okay!" ->
 * "I'll open the accessibility settings now." -> "okay, do it!" -> "Opening accessibility settings for you now." and NOTHING
 * opened. Two holes, both in words, not in the UI:
 *  1. "okay, do it!" took the chat lane (one call, NO tools): it starts with "okay" (small talk) and is short, so
 *     [ChatLane.fits] let it through, and the chat lane's model just said it was opening the page.
 *  2. The full lane's "I'll open the accessibility settings now." had no tool call behind it, and the claim checks only
 *     look at past-tense claims ("I opened…") about what the user's own message asked for.
 * Pure (ActionPromiseTest): [consent] (a bare go-ahead), [offers] (Blaze's last message offered / announced an action),
 * [promises] (a reply announcing that it is doing something right now), and the one corrective [nudge] / [honest] line.
 */
object ActionPromise {
    private val ic = setOf(RegexOption.IGNORE_CASE)

    private val consentWords = setOf("ok", "okay", "okie", "okey", "k", "kk", "alright", "aight", "sure", "yes", "yeah", "yea", "ya", "yep", "yup",
        "please", "pls", "plz", "do", "it", "that", "go", "ahead", "for", "lets", "let's", "then", "now", "proceed", "continue", "start", "cool",
        "fine", "perfect", "great", "me", "right", "away", "sounds", "good", "thanks", "thank", "you", "yess", "yesss", "absolutely", "definitely", "course", "of")
    private val consentCore = setOf("ok", "okay", "okie", "okey", "k", "kk", "alright", "aight", "sure", "yes", "yeah", "yea", "ya", "yep", "yup", "yess", "yesss",
        "do", "go", "proceed", "continue", "start", "please", "absolutely", "definitely")

    /** "okay, do it!", "yes please", "go ahead", "sure, go for it" (a go-ahead and nothing else; "no" never is). */
    fun consent(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t.length > 48) return false
        val words = t.replace(Regex("""[^a-z' ]"""), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.trim('\'') }
        if (words.isEmpty() || words.size > 7) return false
        return words.all { it in consentWords } && words.any { it in consentCore }
    }

    private val offerRx = Regex("""\b(?:want me to|shall i|should i|would you like me to|do you want me to|i can (?:do|set|open|turn|walk|start|take|get)|i'?ll|i will|let me|i'?m going to|i am going to|first up|next up|ready when you are|say the word)\b""", ic)

    /** Blaze's previous message offered or announced an action (so a go-ahead answers it and needs the tools). */
    fun offers(lastBlaze: String): Boolean {
        val t = lastBlaze.takeLast(600)
        return t.isNotBlank() && (offerRx.containsMatchIn(t) || (t.trim().endsWith("?") && actionVerb.containsMatchIn(t)))
    }

    private val actionVerb = Regex("""\b(?:open|opening|launch|launching|set ?up|setting up|turn on|turning on|enable|enabling|switch on|start|starting|install|installing|play|playing|call|calling|send|sending|search|searching|create|creating|make|making|take you|bring up|pull up)\b""", ic)

    private const val THINGS = """settings?|accessibility|permissions?|phone control|control|app|apps|screen|page|browser|camera|alarm|timer|reminder|music|song|playlist|terminal|linux|wi-?fi|bluetooth|notifications?|contacts|calendar|gallery|design|video|link|site|website|youtube|spotify|whatsapp|maps|setup|overlay|battery|microphone|mic|it|that|them"""
    // "Opening accessibility settings for you now.", "Taking you to Wi-Fi settings."
    private val nowRx = Regex("""^(?:(?:ok(?:ay)?|sure|great|alright|got it|on it|done|perfect|right)[,!.\s—-]*)?(?:i'?m |i am |now )?(?:opening|launching|setting up|turning on|enabling|switching on|starting|taking you to|bringing up|pulling up|heading to|jumping to|installing|playing)\b[^.!?\n]{0,60}?\b(?:$THINGS)\b""", ic)
    // "I'll open the accessibility settings now.", "Let me open that for you right away."
    private val soonRx = Regex("""\b(?:i'?ll|i will|let me|i'?m going to|i am going to|i'?m about to)\s+(?:go ahead and\s+|just\s+)?(?:open|launch|set up|turn on|enable|switch on|start|take you|bring up|pull up|install|play)\b[^.!?\n]{0,60}?\b(?:$THINGS)\b[^.!?\n]{0,40}?\b(?:now|right now|right away|for you|immediately|straight away)\b""", ic)
    private val notNow = Regex("""\b(?:if you|once you|when you|after you|would you|do you|want me|shall i|should i|later|tomorrow)\b""", ic)

    private fun sentences(text: String): List<String> = text.split(Regex("""(?<=[.!?…])\s+|\n+""")).map { it.trim().trimStart('*', '-', '•', ' ') }.filter { it.isNotEmpty() }

    /** The sentences of [reply] that say Blaze is doing something on the phone right now (empty = none). */
    fun promises(reply: String): List<String> = sentences(reply).filter { s ->
        !s.endsWith("?") && !notNow.containsMatchIn(s) && (nowRx.containsMatchIn(s) || soonRx.containsMatchIn(s))
    }

    fun nudge(promise: String): String = "[Blaze check] You just told the user \u201c${promise.take(160)}\u201d, but no tool ran this turn, so nothing " +
        "happened on the phone. Do it now with the real tool call: blaze_self action=open target=accessibility (phone control), overlay, " +
        "app_permissions, notification_access, battery or a Blaze settings page; open_settings for any other phone settings page; open_app " +
        "for apps; browser_open / web_search for the web. Then tell the user what to switch on. If no tool can do it, say plainly that you couldn't."

    const val HONEST = "I haven't actually done that yet. Say \u201ctry again\u201d and I'll do it."

    /** The reply with its unbacked "doing it now" sentences replaced by one honest line. */
    fun honest(reply: String, promised: List<String>): String {
        var out = reply
        promised.forEach { out = out.replace(it, "") }
        out = out.replace(Regex("""\n{3,}"""), "\n\n").trim()
        return if (out.isBlank()) HONEST else "$out\n\n$HONEST"
    }
}
