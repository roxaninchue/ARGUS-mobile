package com.blaze.assistant.policy

import com.blaze.assistant.team.ActionClass

/**
 * P2-SF-03: the ONE needs-you query. Everything that waits on the user (pending approvals and drafts, open question
 * cards, connect cards, failed jobs, unconfirmed suggestions) becomes one urgency-sorted list here, and every surface
 * that shows "something needs you" reads it: the bell dot, the NeedYouBanner, the Inbox sheet and Desk → Inbox (later
 * the widget / QS tile / launcher badge). No surface counts pending things on its own; NeedsYouSourceTest scans for that.
 *
 * Pure Kotlin (no android.* / org.json): the sources come in as plain data ([CardSrc], [JobSrc]) so NeedsYouTest runs on
 * the JVM. The Android side ([com.blaze.assistant.ui.team.NeedsYouFeed]) maps PolicyRuntime's cards and team.db jobs
 * onto these and publishes the list as a StateFlow.
 *
 * Decisions are not made here: an approval answered from the Inbox goes through PolicyRuntime.answer(card id, …), the
 * same path as tapping the card in the bot's thread, so the thread's card row changes state exactly as it would there.
 */
object NeedsYou {
    /** Approvals expire after 25 minutes (05 §7.3); time left against this orders the waiting approvals. */
    const val EXPIRY_MS = 25L * 60_000L

    enum class Kind { APPROVAL, QUESTION, CONNECT, FAILED, SUGGESTION }
    enum class Risk { LOW, MEDIUM, HIGH }

    /** The Inbox filter chips (All · Approvals · Questions · Connect · Failed · Suggestions). */
    enum class Filter(val label: String, val kind: Kind?) {
        ALL("All", null), APPROVALS("Approvals", Kind.APPROVAL), QUESTIONS("Questions", Kind.QUESTION),
        CONNECT("Connect", Kind.CONNECT), FAILED("Failed", Kind.FAILED), SUGGESTIONS("Suggestions", Kind.SUGGESTION);
    }

    data class Item(
        val id: String,
        val botId: String,
        val botName: String,
        val kind: Kind,
        val actionClass: ActionClass?,
        val title: String,
        val detail: String,
        val createdAt: Long,
        val expiresAt: Long = 0,
        val urgency: Int,
        val risk: Risk,
    )

    /** A card as PolicyRuntime shows it. [kind] is the card kind code (approval|draft|question|connect). */
    data class CardSrc(
        val id: String, val kind: String, val botId: String, val botName: String, val cls: ActionClass?,
        val title: String, val detail: String, val createdAt: Long, val expiresAt: Long = 0, val pending: Boolean = true,
    )

    /** A job from team.db: [failed] = run FAILED; [suggestion] = a proactive job still waiting for the user's yes. */
    data class JobSrc(
        val id: String, val botId: String, val botName: String, val title: String, val detail: String,
        val failed: Boolean, val suggestion: Boolean, val at: Long,
    )

    private val HIGH = setOf(ActionClass.SPEND, ActionClass.DELETE, ActionClass.CREDENTIAL)
    private val LOW = setOf(ActionClass.READ, ActionClass.WRITE_LOCAL)

    fun riskOf(cls: ActionClass?): Risk = when (cls) {
        null -> Risk.MEDIUM
        in HIGH -> Risk.HIGH
        in LOW -> Risk.LOW
        else -> Risk.MEDIUM
    }

    /** Urgency rank, lower first: SPEND/DELETE/CREDENTIAL approvals, other approvals, questions, connect, failed, suggestions. */
    fun rank(kind: Kind, cls: ActionClass?): Int = when (kind) {
        Kind.APPROVAL -> if (cls in HIGH) 0 else 1
        Kind.QUESTION -> 2
        Kind.CONNECT -> 3
        Kind.FAILED -> 4
        Kind.SUGGESTION -> 5
    }

    private fun kindOf(code: String): Kind? = when (code.lowercase()) {
        "approval", "draft" -> Kind.APPROVAL
        "question" -> Kind.QUESTION
        "connect" -> Kind.CONNECT
        else -> null
    }

    /** The one list. Approvals by time left (soonest expiry first), everything else newest first, within each rank. */
    fun build(cards: List<CardSrc>, jobs: List<JobSrc>, now: Long): List<Item> {
        val out = ArrayList<Item>()
        for (c in cards) {
            if (!c.pending) continue
            val k = kindOf(c.kind) ?: continue
            val cls = if (k == Kind.APPROVAL && c.kind.equals("draft", true)) (c.cls ?: ActionClass.SEND) else c.cls
            val exp = if (k == Kind.APPROVAL) (if (c.expiresAt > 0) c.expiresAt else c.createdAt + EXPIRY_MS) else 0L
            if (k == Kind.APPROVAL && exp <= now) continue
            out += Item(c.id, c.botId, c.botName, k, cls, c.title, c.detail, c.createdAt, exp, rank(k, cls), riskOf(cls))
        }
        for (j in jobs) {
            val k = when { j.failed -> Kind.FAILED; j.suggestion -> Kind.SUGGESTION; else -> continue }
            out += Item(j.id, j.botId, j.botName, k, null, j.title, j.detail, j.at, 0L, rank(k, null), Risk.MEDIUM)
        }
        return out.distinctBy { it.kind to it.id }.sortedWith(ORDER)
    }

    val ORDER: Comparator<Item> = Comparator { a, b ->
        if (a.urgency != b.urgency) return@Comparator a.urgency.compareTo(b.urgency)
        if (a.kind == Kind.APPROVAL && a.expiresAt != b.expiresAt) return@Comparator a.expiresAt.compareTo(b.expiresAt)
        if (a.createdAt != b.createdAt) return@Comparator b.createdAt.compareTo(a.createdAt)
        a.id.compareTo(b.id)
    }

    /** Minutes left before an approval expires (0 when none / gone). */
    fun minutesLeft(i: Item, now: Long): Int = if (i.expiresAt <= now) 0 else (((i.expiresAt - now) + 59_999) / 60_000).toInt()

    fun filter(items: List<Item>, f: Filter): List<Item> = if (f.kind == null) items else items.filter { it.kind == f.kind }

    fun counts(items: List<Item>): Map<Filter, Int> = Filter.entries.associateWith { filter(items, it).size }

    fun count(items: List<Item>): Int = items.size

    /** The bell's unread dot. */
    fun dot(items: List<Item>): Boolean = items.isNotEmpty()

    /** What the NeedYouBanner shows: the same list's size, bot names in urgency order, amber when only approvals wait. */
    data class Banner(val count: Int, val bots: List<String>, val approvalsOnly: Boolean)

    fun banner(items: List<Item>): Banner = Banner(
        count = items.size,
        bots = items.map { it.botName.ifBlank { it.botId } }.distinct(),
        approvalsOnly = items.isNotEmpty() && items.all { it.kind == Kind.APPROVAL },
    )

    /** Bulk Allow/Deny only for one bot + one kind, and never when SPEND or DELETE is in the selection. */
    fun bulkAllowed(selection: List<Item>): Boolean =
        selection.size >= 2 &&
            selection.map { it.botId }.distinct().size == 1 &&
            selection.map { it.kind }.distinct().size == 1 &&
            selection.none { it.actionClass == ActionClass.SPEND || it.actionClass == ActionClass.DELETE }

    /** Swipe-to-approve only for a low-risk approval (READ / WRITE_LOCAL); never SEND/SPEND/DELETE/CREDENTIAL/SETTINGS. */
    fun swipeApprove(i: Item): Boolean = i.kind == Kind.APPROVAL && i.actionClass in LOW

    /** Money and delete ask for the device biometric before Allow. */
    fun needsBiometric(i: Item): Boolean = i.actionClass == ActionClass.SPEND || i.actionClass == ActionClass.DELETE

    /** "Always" is offered only where a standing allow is possible (never SPEND/DELETE/CREDENTIAL/COMPUTE_PAID/SEND). */
    fun alwaysOffered(i: Item): Boolean = i.kind == Kind.APPROVAL && i.actionClass != null &&
        i.actionClass !in setOf(ActionClass.SPEND, ActionClass.DELETE, ActionClass.CREDENTIAL, ActionClass.COMPUTE_PAID, ActionClass.SEND)
}
