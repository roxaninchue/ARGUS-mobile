package com.blaze.assistant.agent

/**
 * 0.50.0 (owner's screenshot 2026-11-01 22:51: a casual "need to make improvements to Blaze agent (an Android ai agent I'm
 * making). I'll try and sleep soon!" showed "Skills: android-app-builder, reverse-engine…"). Skills load only for a message
 * that clearly asks Blaze to DO something:
 *  - never for small talk, acks or a message that only tells Blaze how the user is / what they plan to do themselves;
 *  - a clear request: an imperative ("make…", "write…", "fix…"), a polite ask ("can you…", "please…", "help me…",
 *    "I want you to…"), a how-to ("how do I…"), or a question that names a task verb;
 *  - a terse subject-less request ("flashcards on photosynthesis") counts as a command;
 *  - the user naming a skill ("use the tutor skill") always counts.
 * Then [SkillMatch.selectForTurn] keeps only strong matches (a real name word or ≥ 2 distinct request words, and a higher
 * score) and at most [MAX_NAMES] skills in total. Pure (SkillIntentTest).
 */
object SkillIntent {
    /** At most this many skills per turn (full + by name). */
    const val MAX_NAMES = 3
    /** Minimum score for a skill to be mentioned at all on a turn (was SkillMatch.MIN_SCORE = 3). */
    const val TURN_MIN = 5

    private const val VERBS = "make|build|create|write|draft|compose|fix|debug|refactor|review|audit|design|plan|research|analy[sz]e|" +
        "summari[sz]e|translate|generate|draw|paint|sketch|code|program|implement|add|set ?up|deploy|install|convert|explain|teach|quiz|" +
        "study|test|compare|find|search|scrape|edit|improve|optimi[sz]e|organi[sz]e|schedule|list|show|give|prepare|record|port|" +
        "reverse[- ]engineer|decompile|disassemble|turn|put|send|open|play|start|run|check|track|remind|book|order|buy|price|rewrite|" +
        "proofread|outline|brainstorm|teach|tutor|solve|calculate|chart|graph|plot|diagram|mock ?up|prototype|scaffold|ship|publish|" +
        "upload|download|clone|commit|push|merge|update|upgrade|migrate|clean ?up|sort|format|style|animate|render|model|edit|cut|trim|mix|master"

    private val lead = Regex("""(?i)^\s*(?:(?:hey|hi|ok(?:ay)?|so|yo|alright|right|now|then|and|also|but)[,!.\s]+)*(?:@?(?:(?:argus|blaze)|halo)[,:!\s]+)?""")
    private val polite = Regex("""(?i)^(?:please|pls|can you|could you|would you|will you|can u|could u|help me|i want you to|i need you to|i'?d like you to|let'?s|lets|go ahead and|try to|try and|how (?:do|can|should|would) (?:i|we|you)|how to|show me how|teach me|walk me through)\b""")
    private val imperative = Regex("""(?i)^(?:$VERBS)\b""")
    private val askAnywhere = Regex("""(?i)\b(?:help me|i need (?:you to|help|a hand)|i want you to|i'?d like you to|can you|could you|would you|please)\b""")
    private val verbAnywhere = Regex("""(?i)\b(?:$VERBS)\b""")
    private val namedSkill = Regex("""(?i)\b(?:use|with|load|run|apply)\s+(?:the\s+|my\s+)?[\w-]+\s+skill\b|\bskill\s+[\w-]{3,}""")

    /** Does [text] clearly ask Blaze to do something (so skills may be looked up)? */
    fun taskIntent(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        if (SmallTalk.ruleChat(raw) || SmallTalk.ack(raw)) return false
        if (namedSkill.containsMatchIn(raw)) return true
        // judge each sentence: one clear ask is enough ("I'm tired. Can you make me a study plan?")
        val sentences = raw.split(Regex("""(?<=[.!?\n])\s+""")).map { it.replace(lead, "").trim() }.filter { it.isNotEmpty() }
        // a terse request with no subject ("flashcards on photosynthesis", "landing page for my bakery"): a command, not a chat
        val firstPerson = Regex("""(?i)\b(?:i|i'?m|im|i'?ll|i'?ve|i'?d|we|we'?re|we'?ll|me too)\b""")
        val statement = Regex("""(?i)\b(?:is|was|were|are|am|be|been|has|have|had|does|did|can'?t|cannot|couldn'?t|won'?t|lol|lmao|haha\w*|\w+'s)\b""")
        if (sentences.size == 1 && !raw.endsWith("?") && raw.split(Regex("\\s+")).size in 2..10 && !firstPerson.containsMatchIn(raw) &&
            !statement.containsMatchIn(raw)) return true
        return sentences.any { s ->
            polite.containsMatchIn(s) || imperative.containsMatchIn(s) ||
                (askAnywhere.containsMatchIn(s) && verbAnywhere.containsMatchIn(s)) ||
                (s.trimEnd().endsWith("?") && Regex("""(?i)^(?:can|could|would|will|how|what'?s the best way to)\b""").containsMatchIn(s) && verbAnywhere.containsMatchIn(s))
        }
    }
}
