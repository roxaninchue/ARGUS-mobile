package com.blaze.assistant.browser.phone.instances

import com.blaze.assistant.BrandName

/**
 * Instance locks as leases (docs/ARGUS-PHONE.md 5g). Argus, a background agent, a team member or a routine can lock an
 * instance for a long task; others may read it but not act in it; the lease runs out unless its owner renews it (every
 * act by the owner renews it), and then the instance is free again after a checkpoint; the user can always break a lock.
 * Pure: every function takes `now` from the caller's injected clock.
 */
object Leases {
    /** Default lock length, and the bounds a caller may ask for. */
    const val DEFAULT_MS = 5 * 60_000L
    const val MIN_MS = 60_000L
    const val MAX_MS = 60 * 60_000L
    /** A lease counts as working while its owner renewed or acted this recently. */
    const val WORKING_MS = 90_000L

    fun ttl(minutes: Double?): Long = if (minutes == null || minutes.isNaN()) DEFAULT_MS else (minutes * 60_000).toLong().coerceIn(MIN_MS, MAX_MS)

    fun active(l: Lease?, now: Long): Boolean = l != null && now < l.until

    fun working(l: Lease?, now: Long): Boolean = active(l, now) && now - l!!.renewed < WORKING_MS

    /** The lease if it is still running, else null (a lapsed lease is no lock). */
    fun current(i: Instance, now: Long): Lease? = i.lease?.takeIf { active(it, now) }

    sealed class Result {
        data class Ok(val instance: Instance, val text: String) : Result()
        data class Refused(val text: String) : Result()
    }

    /** Locks [i] for [by]; the same owner locking again renews and may change the task. */
    fun acquire(i: Instance, by: Owner, task: String, ttlMs: Long, now: Long): Result {
        val cur = current(i, now)
        if (cur != null && !cur.owner.same(by)) return Result.Refused(refusal(i, cur, now))
        val l = Lease(by, task.ifBlank { cur?.task ?: i.task }.take(120), cur?.since ?: now, now + ttlMs, now)
        return Result.Ok(i.copy(lease = l), "Locked \u201c${i.name}\u201d for ${by.label()} until ${mins(l.until - now)} from now (renewed by every step you take in it).")
    }

    fun renew(i: Instance, by: Owner, ttlMs: Long?, now: Long): Result {
        val cur = current(i, now) ?: return Result.Refused("\u201c${i.name}\u201d has no lock to renew. Use action=lock.")
        if (!cur.owner.same(by)) return Result.Refused(refusal(i, cur, now))
        val keep = ttlMs ?: (cur.until - cur.renewed).coerceIn(MIN_MS, MAX_MS)
        val l = cur.copy(until = now + keep, renewed = now)
        return Result.Ok(i.copy(lease = l), "Renewed the lock on \u201c${i.name}\u201d for ${mins(keep)}.")
    }

    /** The owner acted: quietly extend its lease (no lease, or someone else's: unchanged). */
    fun touch(i: Instance, by: Owner, now: Long): Instance {
        val cur = current(i, now) ?: return i
        if (!cur.owner.same(by)) return i
        val keep = (cur.until - cur.renewed).coerceIn(MIN_MS, MAX_MS)
        return i.copy(lease = cur.copy(until = maxOf(cur.until, now + keep), renewed = now))
    }

    /** Unlock by its owner. [breakIt]: the user (or Argus on the user's approval) breaks someone else's lock. */
    fun release(i: Instance, by: Owner, now: Long, breakIt: Boolean = false): Result {
        val cur = current(i, now) ?: return Result.Ok(i.copy(lease = null), "\u201c${i.name}\u201d wasn't locked.")
        if (!cur.owner.same(by) && !breakIt && by.kind != Owner.Kind.USER) return Result.Refused(refusal(i, cur, now) +
            " Only its owner can unlock it; the user can break the lock (action=unlock with break=true asks them).")
        val what = if (cur.owner.same(by)) "Unlocked \u201c${i.name}\u201d." else "Broke ${cur.owner.label()}'s lock on \u201c${i.name}\u201d."
        return Result.Ok(i.copy(lease = null), what)
    }

    /** May [by] act in [i] now? Null = yes; otherwise the refusal line the tool returns. The user may always act. */
    fun canAct(i: Instance, by: Owner, now: Long): String? {
        val cur = current(i, now) ?: return null
        if (cur.owner.same(by) || by.kind == Owner.Kind.USER) return null
        return refusal(i, cur, now)
    }

    fun refusal(i: Instance, l: Lease, now: Long): String =
        "Not done: ${BrandName.NAME} Phone instance \u201c${i.name}\u201d is locked by ${l.owner.label()}" +
            (if (l.task.isNotBlank()) " for \u201c${l.task.take(60)}\u201d" else "") + " (lock ends in ${mins(l.until - now)} unless renewed). " +
            "You can read it (phone_instances list) but not act in it. Use another instance (phone_instances new or use), or wait."

    private fun mins(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 90) "${s}s" else "${(s + 30) / 60} min"
    }
}
