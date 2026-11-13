package com.blaze.assistant.agent

/**
 * 2026-11-13 double-text fix. Owner (13 Nov 2026): "EVERY message the user sends must be answered, including new
 * messages typed while ARGUS is still replying, at any point. Nothing is ever dropped or left unanswered." Seen on his
 * phone: "Hey hey!!" then "Let's chat!" got only "Hey!" (the second went to the side lane, whose empty reply was dropped).
 *
 * A message that arrives while ARGUS is replying in the main chat (BusyRoute SIDE) no longer goes to the side lane:
 *  - [Lane.RESTART]: a quick follow-up while a plain chat reply has barely started (no tools yet, the turn is younger than
 *    [QUICK_MS], at most [SHORT_REPLY_CHARS] of it shown, one message in the turn, typed not spoken): the reply is cut and
 *    started again with both messages, so ONE short answer covers both.
 *  - [Lane.MAIN] (every other case): the message joins the running turn at its next step (the model reads it there), or,
 *    when the turn has no next step, the next turn starts with it the moment this one ends. Never waits for another send.
 * Safety net for the side lane that is still reachable elsewhere: an empty or failed side reply ([dropped]) goes back to
 * the main lane instead of being dropped.
 *
 * "Quick" = [QUICK_MS] 8 s after the first message was sent: a typed second text ("Hey hey!!" → "Let's chat!") lands in
 * 2–5 s, and a fast chat reply starts streaming in 1–3 s, so 8 s catches the double text without cutting a reply the
 * user has already been reading for a while. [SHORT_REPLY_CHARS] (160, about two lines) is the other half of the same
 * rule: once more than that is on screen, the reply stands and the follow-up is answered right after it. A burst
 * restarts at most [MAX_RESTARTS] times within [CHAIN_MS], so a stream of texts can't keep a reply from ever finishing.
 *
 * Pure Kotlin (ChatBurstTest). The Android glue is [ChatBurstLive]; Agent.kt calls it from one line in send().
 */
object ChatBurst {
    const val QUICK_MS = 8_000L
    const val SHORT_REPLY_CHARS = 160
    const val MAX_RESTARTS = 2
    const val CHAIN_MS = 20_000L

    enum class Lane { RESTART, MAIN }

    /** What the running turn looks like when the follow-up arrives. */
    data class Facts(
        /** ms since the turn's first message was sent. */
        val sinceTurnMs: Long,
        /** tools run so far this turn. */
        val toolsRun: Int,
        /** characters of the reply already on screen. */
        val replyChars: Int,
        /** user messages the running turn answers (1 = a single message). */
        val turnUsers: Int,
        /** restarts already done in this burst. */
        val restarts: Int,
        /** the follow-up or the running turn was spoken (the reply may already be playing aloud). */
        val voice: Boolean,
        /** the running turn's message is the last plain line of the history (it can be taken back and re-sent). */
        val plainHead: Boolean,
    )

    fun lane(f: Facts): Lane = if (
        f.plainHead && !f.voice && f.toolsRun == 0 && f.turnUsers == 1 &&
        f.sinceTurnMs in 0..QUICK_MS && f.replyChars <= SHORT_REPLY_CHARS && f.restarts < MAX_RESTARTS
    ) Lane.RESTART else Lane.MAIN

    /** Restarts done in a burst whose last restart was at [lastAt] (0 = none), seen at [now]. */
    fun chain(count: Int, lastAt: Long, now: Long): Int = if (lastAt <= 0L || now - lastAt > CHAIN_MS) 0 else count

    /** True when the history line [content] is exactly the turn's message [id] as the model got it (`[m7] …`, a string). */
    fun plainHead(role: String?, content: Any?, id: Long): Boolean =
        role == "user" && content is String && id > 0 && content.startsWith("[" + RapidMessages.tag(id) + "]")

    /** Safety net: a side reply that showed nothing (empty, failed, or a hand-off) goes back to the main lane. */
    fun dropped(shown: String): Boolean = shown.isBlank()

    /** Messages a restarted turn takes back off the screen: everything the cut reply showed (never a user message). */
    fun stale(ids: List<Pair<Long, Boolean>>, from: Long, upTo: Long): Set<Long> =
        ids.filter { (id, user) -> !user && id > from && id <= upTo }.map { it.first }.toSet()

    /** Full prompt (appended to [RapidMessages.PROMPT_RULE]). Static, so it stays in the cached prefix. */
    const val RULE = " Answer EVERY user message, including ones sent while you were replying; never leave one out. " +
        "Keep it short, like texting: one short bubble per message at most, and fewer when one answer covers several " +
        "(never more bubbles than messages)."

    /** User turns at the end of [roles] (oldest first) since the last assistant line. */
    fun trailingUsers(roles: List<String>): Int = roles.takeLastWhile { it == "user" }.size

    /** The chat lane's line (no message ids there): only when several messages wait since the last reply. */
    fun laneLine(waiting: Int): String = if (waiting < 2) "" else
        " The user sent $waiting messages in a row since your last reply: answer every one of them in one short reply " +
            "(one or two sentences), never just the last one."
}
