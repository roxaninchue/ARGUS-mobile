package com.blaze.assistant.browser.phone.instances

import com.blaze.assistant.BrandName
import com.blaze.assistant.browser.phone.PhonePolicy
import kotlin.coroutines.cancellation.CancellationException

/**
 * The instances in memory, in creation order, with the budget policy of docs/ARGUS-PHONE.md section 6:
 * at most [Budget.live] LIVE and [Budget.warm] WARM; over budget, or idle for [idleMs], the oldest idle instance steps
 * down first (LIVE -> WARM -> COLD). Never demoted: an instance the user drives or that waits on the user (mode USER /
 * WAITING, or a pending card), one whose lock is active and working, and the one the user is watching.
 * Pure: [clock] is injected; [enforce] only decides (it returns the moves), the caller does the checkpoints and the
 * WebView work, then calls [put]. Thread-safe (one lock; values are immutable).
 */
class InstanceRegistry(val clock: () -> Long, var budget: Budget = Budget.of(false), val idleMs: Long = PhonePolicy.IDLE_MS) {
    private val map = LinkedHashMap<String, Instance>()
    private var seq = 0
    /** The instance attached to the one WebView (the one phone_* tools act on), if any. */
    @Volatile var active: String? = null
    /** The instance the user looked at last (the notification's tap opens it). */
    @Volatile var watched: String? = null
    /** Someone is looking at the live view now (the full screen or the chat tile). */
    @Volatile var watching: Boolean = false

    @Synchronized fun all(): List<Instance> = map.values.toList()
    @Synchronized fun get(id: String?): Instance? = id?.let { map[it] }
    @Synchronized fun size(): Int = map.size
    @Synchronized fun put(i: Instance) { map[i.id] = i }
    @Synchronized fun remove(id: String): Instance? { if (active == id) active = null; if (watched == id) watched = null; return map.remove(id) }
    @Synchronized fun clear() { map.clear(); active = null; watched = null }
    @Synchronized fun update(id: String, f: (Instance) -> Instance): Instance? = map[id]?.let(f)?.also { map[id] = it }

    /** A fresh id: "i" + base-36 time + a counter (never a path, never reused in one run). */
    @Synchronized fun newId(): String = "i" + clock().coerceAtLeast(0).toString(36) + (seq++).toString(36)

    /** A name nobody else has: [want] as given, else "Phone 2", "Phone 3"... */
    @Synchronized fun freeName(want: String?): String {
        val taken = map.values.map { it.name.lowercase() }.toSet()
        val w = want?.trim()?.take(40).orEmpty()
        if (w.isNotEmpty() && w.lowercase() !in taken) return w
        val base = w.ifEmpty { "Phone" }
        var n = 2; while ("$base $n".lowercase() in taken) n++
        return if (w.isEmpty() && "phone" !in taken) "Phone" else "$base $n"
    }

    /**
     * Finds an instance the model named: its id, its exact name (any case), then an exact task match, then a name or
     * task that contains [q]. Exact matching on what the model passed; it reads no user text (docs/MODEL-FIRST.md).
     * Ambiguous contains-matches return null with [ambiguous] set.
     */
    data class Found(val instance: Instance?, val ambiguous: List<Instance> = emptyList())

    @Synchronized fun find(q0: String?): Found {
        val q = q0?.trim().orEmpty()
        if (q.isEmpty()) return Found(null)
        map[q]?.let { return Found(it) }
        val l = q.lowercase()
        map.values.firstOrNull { it.name.lowercase() == l }?.let { return Found(it) }
        map.values.filter { it.task.isNotBlank() && it.task.lowercase() == l }.let { if (it.size == 1) return Found(it[0]) }
        val loose = map.values.filter { it.name.lowercase().contains(l) || (it.task.isNotBlank() && it.task.lowercase().contains(l)) }
        return if (loose.size == 1) Found(loose[0]) else Found(null, loose)
    }

    /** Why [i] must not step down now, or null. */
    fun pinned(i: Instance, now: Long = clock()): String? = when {
        i.mode == PhonePolicy.Mode.USER -> "the user drives it"
        i.mode == PhonePolicy.Mode.WAITING || i.cards.isNotEmpty() -> "it waits on the user"
        Leases.working(i.lease, now) -> "its lock is active and working"
        watching && i.id == active -> "the user is watching it"
        else -> null
    }

    data class Move(val id: String, val from: Tier, val to: Tier, val why: String)

    /** The moves that bring the registry inside its budget (and idle instances down). Doesn't apply them. */
    @Synchronized fun enforce(now: Long = clock(), extraLive: Int = 0): List<Move> {
        val moves = ArrayList<Move>()
        val tier = HashMap<String, Tier>().apply { map.values.forEach { put(it.id, it.tier) } }
        val free = map.values.filter { pinned(it, now) == null }
        // 1. idle: warm or live and unused for idleMs (PhonePolicy.IDLE_MS, the same 5 minutes the WebView is freed after)
        for (i in free) if (i.tier != Tier.COLD && now - i.lastActiveAt >= idleMs) { moves += Move(i.id, i.tier, Tier.COLD, "idle"); tier[i.id] = Tier.COLD }
        // 2. live over budget (extraLive: room for one that is about to go live), oldest idle first
        fun over(t: Tier, cap: Int, to: Tier) {
            val now0 = tier.count { it.value == t } + (if (t == Tier.LIVE) extraLive else 0)
            var excess = now0 - cap
            if (excess <= 0) return
            for (i in free.filter { tier[it.id] == t }.sortedBy { it.lastActiveAt }) {
                if (excess <= 0) break
                moves += Move(i.id, t, to, "over the ${t.name.lowercase()} budget"); tier[i.id] = to; excess--
            }
        }
        over(Tier.LIVE, budget.live, Tier.WARM)
        over(Tier.WARM, budget.warm, Tier.COLD)
        return moves
    }

    /**
     * Runs [block] for instance [id] so that a failure stays in that instance: any Throwable (but cancellation) is caught,
     * recorded as its [Instance.lastError], and it is marked restorable. Returns the block's text, or a plain line saying
     * the instance stopped and how to bring it back. Never throws into the agent loop.
     */
    suspend fun isolate(id: String?, what: String, block: suspend () -> String): String = try {
        block()
    } catch (e: CancellationException) { throw e } catch (t: Throwable) {
        val msg = (t.javaClass.simpleName + ": " + (t.message ?: "")).take(200)
        val name = id?.let { i -> update(i) { it.copy(lastError = "$what: $msg", restorable = true).withStep("stopped: $what", clock()) }?.name }
        if (name == null) "${BrandName.NAME} Phone hit an error ($what): $msg. Nothing else was affected."
        else "This instance stopped (\u201c$name\u201d, during $what: $msg). Nothing else was affected. " +
            "phone_instances restore name=\u201c$name\u201d brings back its last checkpoint; reset starts it clean."
    }
}
