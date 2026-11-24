package com.blaze.assistant.browser.phone.instances

import com.blaze.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject

/**
 * The catalog tools for Argus Phone's power and instances (docs/ARGUS-PHONE.md section 7, docs/LEAN-TOOLS.md): two
 * rows with an `action` enum, registered in Agent.tools() only (found with find_tools, never in LeanTurn.CORE).
 * Pure: they drive an [ArgusPhoneCore]; the caller's identity ([Owner]) comes from the runtime, never from the model.
 * Anything that would discard unsaved work, or break someone else's working lock, asks the user first through
 * [confirm] (the approval card); a plain close checkpoints first, so it asks nothing.
 */
object PhoneInstanceTools {
    const val POWER = "phone_power"
    const val INSTANCES = "phone_instances"
    val NAMES = setOf(POWER, INSTANCES)
    val ACTIONS = listOf("list", "new", "use", "lock", "unlock", "renew", "open", "close", "close_all", "checkpoint", "restore", "reset", "duplicate")

    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)
    private fun e(d: String, v: List<String>) = JSONObject().put("type", "string").put("description", d).put("enum", JSONArray(v))
    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))

    fun definitions(): List<JSONObject> = listOf(
        fn("phone_power", "${BrandName.NAME} Phone power: turn ${BrandName.NAME}'s own phone on (it keeps running in the background), off (checkpoints every instance first), or get its status.",
            JSONObject().put("action", e("on, off or status", listOf("on", "off", "status"))), listOf("action")),
        fn("phone_instances", "${BrandName.NAME} Phone instances: list them with their deep state (page, lock, waiting cards, errors) to reuse one or start a new one. " +
            "Also use, lock, unlock, renew, open (show it to the user), close, close_all, checkpoint, restore, reset, duplicate. phone_* tools act in the active instance.",
            JSONObject().put("action", e("What to do", ACTIONS))
                .put("name", p("string", "The instance's name or id (default: the active one); for new / duplicate, the new name"))
                .put("task", p("string", "What it is for (new, lock)"))
                .put("minutes", p("number", "Lock length for lock / renew (1-60, default 5)"))
                .put("break", p("boolean", "unlock: break another owner's lock (asks the user)")),
            listOf("action")),
    )

    fun describe(name: String, a: JSONObject): String = when (name) {
        POWER -> when (a.optString("action")) { "on" -> "Turning on ${BrandName.NAME} Phone"; "off" -> "Shutting down ${BrandName.NAME} Phone"; else -> "Checking ${BrandName.NAME} Phone" }
        else -> when (a.optString("action")) {
            "list" -> "Checking ${BrandName.NAME} Phone's instances"
            "new" -> "Starting a ${BrandName.NAME} Phone instance"
            "use" -> "Switching ${BrandName.NAME} Phone instance"
            "lock", "renew" -> "Locking a ${BrandName.NAME} Phone instance"
            "unlock" -> "Unlocking a ${BrandName.NAME} Phone instance"
            "open" -> "Opening ${BrandName.NAME} Phone"
            "close", "close_all" -> "Closing ${BrandName.NAME} Phone instances"
            "checkpoint" -> "Saving a ${BrandName.NAME} Phone checkpoint"
            "restore", "reset" -> "Restoring a ${BrandName.NAME} Phone instance"
            "duplicate" -> "Copying a ${BrandName.NAME} Phone instance"
            else -> "Working on ${BrandName.NAME} Phone"
        }
    }

    /** Runs one call. Never throws (but cancellation): a failure comes back as a line. */
    suspend fun run(core: ArgusPhoneCore, by: Owner, name: String, a: JSONObject, confirm: suspend (String, String, String) -> Boolean): String =
        core.registry.isolate(null, name) {
            when (name) {
                POWER -> power(core, by, a.optString("action"), confirm)
                INSTANCES -> instances(core, by, a, confirm)
                else -> "Unknown ${BrandName.NAME} Phone tool."
            }
        }

    /** Locks held and in use by someone other than [by]. */
    private fun othersWorking(core: ArgusPhoneCore, by: Owner): List<Instance> {
        val t = core.clock()
        return core.registry.all().filter { i -> Leases.working(i.lease, t) && !i.lease!!.owner.same(by) }
    }

    private suspend fun power(core: ArgusPhoneCore, by: Owner, action: String, confirm: suspend (String, String, String) -> Boolean): String = when (action) {
        "on" -> core.powerOn()
        "off" -> {
            val busy = othersWorking(core, by)
            if (busy.isNotEmpty() && !confirm("Shut down ${BrandName.NAME} Phone?", "${busy.joinToString { "\u201c${it.name}\u201d (${it.lease!!.owner.label()})" }} " +
                    "${if (busy.size == 1) "is" else "are"} in use. Every instance is checkpointed first; their work stops.", "Shut down"))
                "The user did NOT approve shutting down while ${busy.joinToString { it.lease!!.owner.label() }} works. Nothing changed."
            else core.powerOff()
        }
        "status" -> core.status()
        else -> "phone_power action is on, off or status."
    }

    private suspend fun instances(core: ArgusPhoneCore, by: Owner, a: JSONObject, confirm: suspend (String, String, String) -> Boolean): String {
        val action = a.optString("action").lowercase()
        if (action !in ACTIONS) return "Unknown action \u201c$action\u201d. Use one of: ${ACTIONS.joinToString()}."
        core.load()
        val nameArg = a.optString("name").trim()
        when (action) {
            "list" -> {
                val s = core.snapshot()
                return if (s.length() == 0) "${BrandName.NAME} Phone has no instances (${if (core.powered) "on" else "off"}). new makes one; phone_open makes one by itself."
                else core.status() + "\n" + s.toString()
            }
            "new" -> return core.create(nameArg.ifBlank { null }, a.optString("task"), by)
            "close_all" -> {
                val busy = othersWorking(core, by)
                val unsaved = core.registry.all().filter { it.unsaved }
                if ((busy.isNotEmpty() || unsaved.isNotEmpty()) && !confirm("Close all ${BrandName.NAME} Phone instances?",
                        listOfNotNull(busy.takeIf { it.isNotEmpty() }?.let { b -> "${b.joinToString { "\u201c${it.name}\u201d" }} in use by others" },
                            unsaved.takeIf { it.isNotEmpty() }?.let { u -> "${u.joinToString { "\u201c${it.name}\u201d" }} with typing not submitted" })
                            .joinToString("; ") + ". Each is checkpointed first and kept 7 days.", "Close all"))
                    return "The user did NOT approve closing every instance. Nothing changed."
                return core.closeAll()
            }
        }
        val found = core.registry.find(nameArg.ifBlank { null })
        val target = when {
            nameArg.isBlank() -> core.registry.get(core.registry.active) ?: core.registry.get(core.registry.watched)
            else -> found.instance
        } ?: return if (found.ambiguous.isNotEmpty()) "Which instance? \u201c$nameArg\u201d matches ${found.ambiguous.joinToString { "\u201c${it.name}\u201d" }}."
            else if (nameArg.isBlank()) "No active instance. phone_instances list shows them; name one, or new makes one."
            else "No ${BrandName.NAME} Phone instance named \u201c$nameArg\u201d. phone_instances list shows them."
        val t = core.clock()
        return when (action) {
            "use" -> core.use(target.id, by)
            "open" -> core.show(target.id, by)
            "checkpoint" -> core.checkpoint(target.id)
            "restore" -> core.restore(target.id, by)
            "duplicate" -> core.duplicate(target.id, if (nameArg.isNotBlank() && found.instance == null) nameArg else null)
            "reset" -> {
                if (target.unsaved && Leases.canAct(target, by, t) == null &&
                    !confirm("Reset \u201c${target.name}\u201d?", "It has typing that wasn't submitted. Its state is checkpointed first (restore undoes it).", "Reset"))
                    "The user did NOT approve the reset. Nothing changed."
                else core.reset(target.id, by)
            }
            "close" -> {
                if (target.unsaved && Leases.canAct(target, by, t) == null &&
                    !confirm("Close \u201c${target.name}\u201d?", "It has typing that wasn't submitted. It is checkpointed first and kept 7 days.", "Close"))
                    "The user did NOT approve closing it. Nothing changed."
                else core.close(target.id, by)
            }
            "lock" -> {
                val ttl = Leases.ttl(if (a.has("minutes")) a.optDouble("minutes") else null)
                core.setLease(target.id) { Leases.acquire(it, by, a.optString("task"), ttl, core.clock()) }
            }
            "renew" -> core.setLease(target.id) { Leases.renew(it, by, if (a.has("minutes")) Leases.ttl(a.optDouble("minutes")) else null, core.clock()) }
            "unlock" -> {
                val cur = Leases.current(target, t)
                if (cur != null && !cur.owner.same(by) && a.optBoolean("break", false)) {
                    if (!confirm("Break the lock on \u201c${target.name}\u201d?", "${cur.owner.label()} holds it" +
                            (if (cur.task.isNotBlank()) " for \u201c${cur.task.take(60)}\u201d" else "") +
                            (if (Leases.working(cur, t)) " and is working in it now" else "") + ". It is checkpointed first.", "Break lock"))
                        "The user did NOT approve breaking the lock. Nothing changed."
                    else core.setLease(target.id) { Leases.release(it, by, core.clock(), breakIt = true) }
                } else core.setLease(target.id) { Leases.release(it, by, core.clock()) }
            }
            else -> "Unknown action."
        }
    }
}
