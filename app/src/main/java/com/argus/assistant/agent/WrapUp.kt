package com.argus.assistant.agent

/**
 * 0.53.6 (Finn's screenshot: "I think that's plenty, just give what you found" got "I couldn't confirm it: not checked
 * yet."). When the user tells ARGUS to stop and answer, the turn answers from what it already has: one plain line in the
 * system prompt says so, and the turn gets no corrective re-prompts (RetryGuard), so no guard sends the model back to work.
 * Pure (WrapUpTest).
 */
object WrapUp {
    private val ask = Regex("""(?i)\b(that'?s (?:plenty|enough|fine|good enough)|(?:just |now )?(?:give|tell|show|send) me (?:what|whatever) you(?:'ve)? (?:found|got|have)|""" +
        """just (?:give|tell|show|send)(?: me)? (?:what|whatever) you(?:'ve)? (?:found|got|have)|what(?:ever)? you(?:'ve)? (?:found|got|have) (?:so far )?is (?:fine|enough|ok)|""" +
        """stop (?:searching|looking|browsing|digging)|no need to (?:keep )?(?:search|look|dig)\w*|wrap (?:it |this )?up|enough (?:searching|looking|research))\b""")

    fun asks(userText: String): Boolean = ask.containsMatchIn(userText.replace('\u2019', '\''))

    const val HINT = "\nANSWER NOW: the user wants the answer from what you have already found. Don't search or browse again; " +
        "answer straight away from the tool results and pages above in this chat (if a background agent was on it, one check_agent call for its findings is fine), " +
        "and say in one short line what you didn't get to check."
}
