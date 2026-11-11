package com.blaze.assistant.agent.guards

/**
 * P2-SF-07 (AT-32 no filler, AT-70 interrogation bounce): reply-shape guards ported from A team `app/www/guard.js`
 * (`interrogates`, `isNarration`, `asksInsteadOfLooking`) and `app/www/chat.js` (`FILLER` / `dropFiller`), owner grant, MIT.
 * Cases: A team `evals/test/v215.test.mjs` (interrogates, narration) and the no-filler / one-bounce checks of
 * `app/tests/v27.test.js` §4 and `v215.test.js`, as JUnit in ReplyShapeTest.
 *
 *  - [dropFiller]: stock sign-offs go ("Let me know if you need anything else", "Hope this helps", a bare "Done.") when
 *    they trail real content; a reply that is only filler is kept unless something came before it in the turn.
 *  - [interrogates]: two or more bulleted questions, or three or more questions, get one bounce ([INTERROG_MSG]).
 *  - [isNarration]: a short "Let me check…" line with no tool call after it gets one nudge ([NARRATE_MSG]).
 *  - [asksInsteadOfLooking]: "which file?" / "I can't find it" before looking gets one bounce ([LOOK_MSG]).
 * The agent loop sends each bounce at most once per turn (hook in the P2-SF-07 report). Pure Kotlin.
 */
object ReplyShape {
    private val ic = RegexOption.IGNORE_CASE

    val FILLER = Regex("""^(?:(?:got it|okay|ok|sure|great|done|alright|all set|noted|perfect|understood)[.!,]?\s*)?(?:(?:let me know|feel free to|is there anything else|anything else (?:i can|you need)|if you (?:need|have|want) (?:anything|any|more|other))[^\n]*|hope (?:this|that) helps[^\n]*|(?:got it|okay|ok|sure|done|noted|understood)[.!]?)\s*$""", ic)

    /**
     * [text] without trailing filler paragraphs. [before] = the turn already showed something (an earlier text or tool
     * step), so even a filler-only text may go. Returns "" when nothing is left.
     */
    fun dropFiller(text: String, before: Boolean = false): String {
        val paras = text.split(Regex("""\n{2,}""")).toMutableList()
        while (paras.isNotEmpty() && FILLER.containsMatchIn(paras.last().trim()) && (paras.size > 1 || before)) paras.removeAt(paras.size - 1)
        return paras.joinToString("\n\n").trim()
    }

    /** Is [text] only filler (nothing worth showing)? */
    fun onlyFiller(text: String): Boolean = text.isNotBlank() && dropFiller(text, before = true).isEmpty()

    data class Interrogation(val flag: Boolean, val questions: Int, val bulletQs: Int)

    /** A reply that interrogates: two or more bulleted questions, or three or more questions. */
    fun interrogates(text: String?): Interrogation {
        val t = text ?: ""
        val qs = Regex("""\?(\s|$)""").findAll(t).count()
        val bulletQs = t.split("\n").count { Regex("""^\s*([-*•]|\d+[.)])\s+.*\?\s*$""").containsMatchIn(it) }
        return Interrogation(bulletQs >= 2 || qs >= 3, qs, bulletQs)
    }
    const val INTERROG_MSG = "Your reply asked several questions. Read the conversation again: a short message usually answers your last question. Answer with what you can do now and ask at most one short question, only if the answer would change the result. No bullet lists of questions."

    /** A short "here is what I'll do" line with no tool call after it (the turn would end on it). */
    fun isNarration(text: String?): Boolean {
        val t = (text ?: "").trim()
        if (t.isEmpty() || t.length > 240 || Regex("""\?\s*$""").containsMatchIn(t) || t.split("\n").size > 3) return false
        return Regex("""^(let me|let's|i'll|i will|i'm going to|first,?|next,?|now,?|then,?|checking|installing|setting up|starting|creating|writing|building|running|looking)\b""", ic).containsMatchIn(t) &&
            Regex("""[.:…]\s*$""").containsMatchIn(t)
    }
    const val NARRATE_MSG = "Go ahead: do that now with your tools."
    const val NARRATE_PROMPT = "NARRATE. Before a batch of tool calls, write one short sentence saying what you are about to do (e.g. \"Let me check what is in the workspace.\"). When the plan changes, one short status line (e.g. \"Flask is missing, installing it in the background.\"). No filler. Put the final answer last."

    private val LOOK_RE = listOf(
        Regex("""\bI(?:'| a)?m not (?:seeing|finding)\b""", ic),
        Regex("""\bI (?:can(?:'|no)?t|couldn'?t|could not|don'?t|do not) (?:see|find|locate|spot)\b(?! (?:images?|pictures?|photos?|the (?:image|picture|photo|screenshot)|your screen))""", ic),
        Regex("""\b(?:no|not seeing any|don'?t have any) (?:record|sign|trace) of\b""", ic),
        Regex("""\b(?:could|can) you (?:share|tell me|remind me|point me|let me know)\b[^?]*\b(?:which|what|where|name|called|path|folder|link)\b""", ic),
        Regex("""\bwhich (?:site|website|page|file|folder|project|app|repo|script|document)\b[^?]*\?""", ic),
        Regex("""\bwhat (?:name|was it called|did we call|is it called)\b[^?]*\?""", ic),
        Regex("""\bwhere (?:is it|did (?:you|we) (?:save|put|keep))\b[^?]*\?""", ic),
    )
    /** A reply that asks the user where or what something is (or says it can't see it) before looking. */
    fun asksInsteadOfLooking(text: String?): Boolean = LOOK_RE.any { it.containsMatchIn(text ?: "") }
    const val LOOK_MSG = "Check from the app: you told the user you can't find something, or asked them where or what it is, without looking. Look first: run ls and find in /root, and use recall or the memory tools. Then answer with what you found. Ask the user only if nothing fits, and say where you looked."
}
