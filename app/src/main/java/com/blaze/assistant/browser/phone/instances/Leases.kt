package com.blaze.assistant.browser.phone.instances

import com.blaze.assistant.BrandName
import java.io.File

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

/**
 * Step 1b (docs/ARGUS-PHONE.md 5e): work lives in the shared workspace, never inside an instance. Each instance gets a
 * scratch folder (`<instances root>/<id>/scratch/`); the shared workspace is the agent's home (`filesDir/home`, what the
 * terminal and file tools call `~`), seen by every instance and the main app. Closing or resetting an instance moves
 * whatever is still in its scratch to `~/Recovered/<instance name>/` first, so closing one never deletes work; only an
 * explicit delete of the recovered folder removes it. Pure java.io; never throws to the caller.
 */
class Workspace(val shared: File, val instancesRoot: File) {
    companion object {
        const val RECOVERED = "Recovered"
        /** A folder name from an instance name: letters, digits, space, dot, dash and underscore; 60 chars at most. */
        fun safeName(name: String): String = name.map { if (it.isLetterOrDigit() || it in " ._-") it else '_' }.joinToString("")
            .trim(' ', '.', '_').take(60).ifBlank { "instance" }
    }

    fun scratch(id: String): File = File(File(instancesRoot, id), "scratch")
    fun recoveredRoot(): File = File(shared, RECOVERED)

    /** Moves [id]'s scratch (if it holds anything) to `Recovered/<name>/`, or `<name> 2`… when that exists. Returns the
     *  folder it went to, or null when there was nothing to keep or the move failed (the scratch then stays put). */
    fun recover(id: String, name: String): File? = try {
        val s = scratch(id)
        if (!s.isDirectory || (s.listFiles() ?: emptyArray()).isEmpty()) null else {
            val root = recoveredRoot().apply { mkdirs() }
            val base = safeName(name)
            var dst = File(root, base); var n = 2
            while (dst.exists()) dst = File(root, "$base ${n++}")
            if (s.renameTo(dst) || run { s.copyRecursively(dst, overwrite = false); s.deleteRecursively() }) dst else null
        }
    } catch (_: Exception) { null }
}

/**
 * Step 1b: one writer per shared file at a time. An instance takes a short write lease on a path before writing; another
 * instance gets read-only (its edit goes to a conflict copy). [save] never writes over a file someone else changed since
 * the writer read it: the second edit lands next to it as a conflict copy and the caller tells the user (or Argus) to
 * merge. Pure apart from the file write; `now` comes from the caller's clock.
 */
class FileLeases(val ttlMs: Long = 5 * 60_000L) {
    data class Hold(val instance: String, val until: Long)
    private val map = HashMap<String, Hold>()
    private fun key(f: File): String = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }

    /** Null when [instance] may write [f] now (the lease is taken or renewed); else the instance holding it. */
    @Synchronized fun acquire(f: File, instance: String, now: Long): String? {
        val k = key(f); val h = map[k]
        if (h != null && h.instance != instance && now < h.until) return h.instance
        map[k] = Hold(instance, now + ttlMs); return null
    }
    @Synchronized fun release(f: File, instance: String) { val k = key(f); if (map[k]?.instance == instance) map.remove(k) }
    @Synchronized fun releaseAll(instance: String) { map.entries.removeAll { it.value.instance == instance } }
    @Synchronized fun holder(f: File, now: Long): String? = map[key(f)]?.takeIf { now < it.until }?.instance

    data class Saved(val file: File, val conflict: Boolean)

    /** Writes [text] to [f] for [instance] ([who] names it in a conflict copy). [readMtime] is the file's lastModified
     *  when the writer read it (0 for a new file). Another holder, or a change since the read, makes a conflict copy. */
    fun save(f: File, text: String, instance: String, who: String, readMtime: Long, now: Long): Saved {
        val busy = acquire(f, instance, now) != null
        val changed = f.exists() && f.lastModified() != readMtime
        val dst = if (busy || changed) conflictCopy(f, who, now) else f
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, ".${dst.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(dst)) { dst.writeText(text); tmp.delete() }
        return Saved(dst, dst != f)
    }

    /** "notes (conflict from Shop 2026-11-25 0612).md" next to [f]; a number is added if that exists too. */
    fun conflictCopy(f: File, who: String, now: Long): File {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val stamp = "%04d-%02d-%02d %02d%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        val ext = f.name.substringAfterLast('.', "").let { if (it.isEmpty() || it == f.name) "" else ".$it" }
        val stem = f.name.removeSuffix(ext)
        var out = File(f.parentFile, "$stem (conflict from ${Workspace.safeName(who)} $stamp)$ext"); var n = 2
        while (out.exists()) out = File(f.parentFile, "$stem (conflict from ${Workspace.safeName(who)} $stamp $n)$ext").also { n++ }
        return out
    }
}
