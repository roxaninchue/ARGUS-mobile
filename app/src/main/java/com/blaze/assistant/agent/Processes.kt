package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.blaze.assistant.proactive.Triggers
import com.blaze.assistant.routine.Routines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Process registry: everything Argus creates that keeps living after the chat (alarms, timers,
 * routines, triggers, calendar events, background jobs) gets a short unique ID like R4 or A12.
 *
 * The model works by ID only. `processes` returns one short line per item (ID, kind, title, when),
 * and `process` acts on an ID (pause, resume, delete, rename, reschedule). The full stored details
 * are only read with action=details, when the user actually asks for specifics, so tracking costs
 * a handful of tokens no matter how much each process holds.
 *
 * Routines and triggers keep their own stores; the registry mirrors them (sync) so anything the
 * user deletes in the UI disappears here too.
 */
object Processes {
    data class P(
        val id: String, val kind: String, val title: String, val ref: String,
        val created: Long, val due: Long = 0, val status: String = "active", val props: JSONObject = JSONObject()
    )

    private val prefix = mapOf("alarm" to "A", "timer" to "T", "routine" to "R", "trigger" to "G",
        "event" to "E", "gevent" to "C", "job" to "J", "watcher" to "W", "goal" to "N", "skill" to "K", "agent" to "B")
    val kinds get() = prefix.keys

    private lateinit var app: Context
    private lateinit var file: File
    private val counters = HashMap<String, Int>()
    private val _all = MutableStateFlow<List<P>>(emptyList())
    val all: StateFlow<List<P>> = _all

    fun init(ctx: Context) {
        app = ctx.applicationContext
        file = File(app.filesDir, "processes.json")
        val a0 = app
        nextSystemAlarm = { SystemAlarm.read(a0) }   // 0.55.4: only a clock app's alarm; another app's = SystemAlarm.UNKNOWN
        systemAlarmReadable = true
        try {
            val o = JSONObject(file.readText())
            o.optJSONObject("next")?.let { n -> for (k in n.keys()) counters[k] = n.getInt(k) }
            val a = o.optJSONArray("items") ?: JSONArray()
            _all.value = (0 until a.length()).map { a.getJSONObject(it) }.map {
                P(it.getString("id"), it.getString("kind"), it.optString("title"), it.optString("ref"), it.optLong("created"),
                    it.optLong("due"), it.optString("status", "active"), it.optJSONObject("props") ?: JSONObject())
            }
        } catch (_: Exception) { }
    }

    private fun ok() = ::file.isInitialized

    @Synchronized private fun save(list: List<P>) {
        _all.value = list
        try { TurnContextSource.invalidate() } catch (_: Throwable) { }   // v0.45.9: alarms/events feed the [context] block
        if (!ok()) return
        try {
            file.writeText(JSONObject()
                .put("next", JSONObject(counters as Map<*, *>))
                .put("items", JSONArray(list.map {
                    JSONObject().put("id", it.id).put("kind", it.kind).put("title", it.title).put("ref", it.ref)
                        .put("created", it.created).put("due", it.due).put("status", it.status).put("props", it.props)
                })).toString())
        } catch (_: Exception) { }
    }

    /** Reserve the next ID for a kind (so it can go into an alarm label before the alarm exists). */
    @Synchronized fun nextId(kind: String): String {
        val n = (counters[kind] ?: 0) + 1
        counters[kind] = n
        return (prefix[kind] ?: "X") + n
    }

    /** Record something Argus created. Returns its ID. */
    @Synchronized fun register(kind: String, title: String, ref: String, due: Long = 0, props: JSONObject = JSONObject(), id: String? = null): String {
        if (!ok()) return ""
        val pid = id ?: nextId(kind)
        save(_all.value.filterNot { it.kind == kind && it.ref == ref && ref.isNotBlank() } +
            P(pid, kind, title.take(60), ref, System.currentTimeMillis(), due, "active", props))
        return pid
    }

    fun idFor(kind: String, ref: String): String? { sync(); return _all.value.firstOrNull { it.kind == kind && it.ref == ref }?.id }

    private fun update(id: String, f: (P) -> P) = save(_all.value.map { if (it.id == id) f(it) else it })
    private fun drop(id: String) = save(_all.value.filterNot { it.id == id })

    /** Mirror routines/triggers, and age alarms/timers/events whose time has passed. */
    @Synchronized fun sync() {
        if (!ok()) return
        var list = _all.value.toMutableList()
        val now = System.currentTimeMillis()
        try {
            val routines = Routines.all.value
            list.removeAll { it.kind == "routine" && routines.none { r -> r.id.toString() == it.ref } }
            for (r in routines) {
                val st = if (r.enabled) "active" else "paused"
                val props = JSONObject().put("prompt", r.prompt).put("time", r.timeLabel()).put("days", r.daysLabel())
                val i = list.indexOfFirst { it.kind == "routine" && it.ref == r.id.toString() }
                if (i < 0) list += P(nextId("routine"), "routine", r.name, r.id.toString(), now, 0, st, props)
                else list[i] = list[i].copy(title = r.name, status = st, props = props)
            }
        } catch (_: Exception) { }
        try {
            val triggers = Triggers.all.value
            list.removeAll { it.kind == "trigger" && triggers.none { t -> t.id.toString() == it.ref } }
            for (t in triggers) {
                val st = if (t.enabled) "active" else "paused"
                val props = JSONObject().put("event", t.event).put("match", t.match).put("prompt", t.prompt)
                val i = list.indexOfFirst { it.kind == "trigger" && it.ref == t.id.toString() }
                if (i < 0) list += P(nextId("trigger"), "trigger", t.name, t.id.toString(), now, 0, st, props)
                else list[i] = list[i].copy(title = t.name, status = st, props = props)
            }
        } catch (_: Exception) { }
        try {
            // 0.55.11: screen watchers went with phone control; any old rows go.
            list.removeAll { it.kind == "watcher" }
            val ks = SkillPacks.all().map { it.dir.name }.toSet()
            list.removeAll { it.kind == "skill" && it.ref !in ks }
            val gs = Autonomy.states()
            list.removeAll { it.kind == "goal" && it.ref !in gs }
            list = list.map { if (it.kind == "goal") it.copy(status = when (gs[it.ref]) { "done" -> "finished"; "paused" -> "paused"; else -> "active" }) else it }.toMutableList()
        } catch (_: Exception) { }
        // 0.55.2 (Finn 18:08: Home and the greeting showed a 19:30 alarm; the Clock app said "No alarms"): an alarm Argus
        // recorded that the Clock app doesn't have (refused, or deleted there) is dropped. If it existed, the system's
        // next alarm would ring at or before it.
        val sysNext = if (systemAlarmReadable) try { nextSystemAlarm() } catch (_: Throwable) { -1L } else -1L
        list = list.map {
            when {
                it.status == "active" && it.kind == "alarm" && sysNext != -1L &&
                    goneFromClock(it.due, it.props.optBoolean("repeats"), now, sysNext, it.created) -> it.copy(status = "deleted")
                it.status == "active" && it.kind == "timer" && it.due in 1..now -> it.copy(status = "finished")
                it.status == "active" && it.kind == "alarm" && it.due in 1..now && !it.props.optBoolean("repeats") -> it.copy(status = "rang")
                it.status == "active" && (it.kind == "event" || it.kind == "gevent") && it.due in 1..now -> it.copy(status = "past")
                it.kind == "job" && it.status == "active" && !com.blaze.assistant.terminal.Terminal.jobAlive(it.ref.toIntOrNull() ?: -1) -> it.copy(status = "ended")
                else -> it
            }
        }.toMutableList()
        // Finished one-offs are kept a day so "what did you set earlier" still works, then pruned.
        list.removeAll { it.status in setOf("finished", "rang", "ended", "deleted") && now - maxOf(it.due, it.created) > 86_400_000L }
        list.removeAll { it.status == "past" && now - it.due > 7 * 86_400_000L }
        save(list)
    }

    private val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
    private fun whenOf(p: P): String = when (p.kind) {
        "routine" -> "${p.props.optString("time")} ${p.props.optString("days")}"
        "trigger" -> "on ${p.props.optString("event")}"
        "watcher" -> "sees ${p.props.optString("when_visible")}"
        "job" -> ""
        else -> if (p.due > 0) fmt.format(Date(p.due)) else ""
    }

    /** One short line per process. This is all the model normally sees. */
    fun list(kind: String = "", status: String = ""): String {
        sync()
        val l = _all.value.filter { (kind.isBlank() || it.kind == kind) && (status.isBlank() || it.status == status || (status == "live" && it.status in setOf("active", "paused"))) }
        val extra = untrackedLine(kind, _all.value)
        if (l.isEmpty()) return extra ?: if (kind.isBlank()) "Nothing tracked." else "No ${kind}s tracked."
        return l.takeLast(60).joinToString("\n") { p ->
            listOf(p.id, p.kind, p.title, whenOf(p), p.status).filter { it.isNotBlank() }.joinToString(" · ")
        } + (extra?.let { "\n$it" } ?: "")
    }

    /** v0.45.13: an alarm in the Clock app with no registry row: "A2" (found by its label) or "clock@18:15" (by its time). */
    private fun untracked(id: String, action: String): P? {
        if (action !in setOf("delete", "pause", "resume")) return null
        Regex("^A\\d+$").matchEntire(id)?.let { return P(id, "alarm", "Alarm $id", "", 0, props = JSONObject().put("label", "${BrandName.NAME} $id")) }
        Regex("^clock@([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(id)?.let { m ->
            val t = "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2])
            return P(id, "alarm", "Clock alarm $t", "", 0, props = JSONObject().put("time", t).put("label", "alarm at $t"))
        }
        return null
    }

    /** v0.45.13: the phone's next Clock alarm when Argus tracks none at that time (only the next one is visible to apps).
     *  0.55.4: null = none, [SystemAlarm.UNKNOWN] = another app's alarm clock is next (can't tell). */
    @Volatile var nextSystemAlarm: () -> Long? = { null }

    /** True once [init] wired [nextSystemAlarm] to AlarmManager (so null there means "no alarm", not "can't tell"). */
    @Volatile var systemAlarmReadable = false

    /**
     * 0.55.2: a one-off alarm still ahead that the system doesn't know: no next alarm at all, or the next one rings more
     * than a minute after it, two minutes after Argus set it (the Clock app registers it asynchronously). Repeating alarms are left alone (their next ring isn't [due]). Pure; ProcessesAlarmTest.
     */
    fun goneFromClock(due: Long, repeats: Boolean, now: Long, next: Long?, created: Long = 0L): Boolean =
        !repeats && due > now + 60_000L && now - created > 120_000L && (next == null || next > due + 60_000L)

    private fun untrackedLine(kind: String, tracked: List<P>): String? {
        if (kind.isNotBlank() && kind != "alarm") return null
        val t = try { nextSystemAlarm() } catch (_: Throwable) { null } ?: return null
        if (t == SystemAlarm.UNKNOWN) return null   // 0.55.4: a calendar/reminder app's alarm clock is not a Clock alarm
        if (tracked.any { it.kind == "alarm" && it.status == "active" && Math.abs(it.due - t) < 60_000L }) return null
        val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
        return "clock@$hm · alarm · next Clock alarm (not tracked by ${BrandName.NAME}) · ${fmt.format(Date(t))} · on. To switch it off or delete it use process with id clock@$hm, or A<n> if its label says (${BrandName.NAME} A<n>)."
    }

    /** Short count line for the system prompt, e.g. "3 routines, 1 alarm". */
    fun countLine(): String = try {
        val live = _all.value.filter { it.status == "active" || it.status == "paused" }
        if (live.isEmpty()) "" else live.groupBy { it.kind }.entries.joinToString(", ") { "${it.value.size} ${it.key}${if (it.value.size > 1) "s" else ""}" }
    } catch (_: Exception) { "" }

    suspend fun act(ctx: Context, idRaw: String, action: String, value: String): String {
        sync()
        val id0 = idRaw.trim().removePrefix("#")
        val id = if (id0.startsWith("clock@", true)) "clock@" + id0.substringAfter('@') else id0.uppercase()
        // v0.45.13: a Clock alarm Argus no longer tracks (0.45.9 dropped A2 after an unconfirmed delete; the system's
        // next alarm) can still be switched off / deleted by its label "(Blaze A2)" or its time ("clock@18:15").
        val p = _all.value.firstOrNull { it.id == id } ?: untracked(id, action) ?: return "No process $id. Call processes to see the IDs."
        return when (action) {
            "details" -> JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("status", p.status)
                .put("created", fmt.format(Date(p.created))).apply { if (p.due > 0) put("when", fmt.format(Date(p.due))) }
                .put("details", p.props).toString(1)
            "delete" -> delete(ctx, p)
            "pause", "resume" -> if (p.kind == "alarm") alarmPause(ctx, p, action == "pause") else pause(p, action == "pause")
            "rename" -> rename(p, value)
            "reschedule" -> reschedule(ctx, p, value)
            else -> "Unknown action $action."
        }
    }

    private suspend fun delete(ctx: Context, p: P): String {
        val msg = when (p.kind) {
            "routine" -> { Routines.delete(p.ref.toInt()); "Deleted routine ${p.id} \u201c${p.title}\u201d." }
            "trigger" -> { Triggers.remove(p.ref.toLong()); "Deleted trigger ${p.id} \u201c${p.title}\u201d." }
            "job" -> com.blaze.assistant.terminal.Terminal.stopJob(p.ref.toInt())
            "goal" -> { Autonomy.remove(p.ref); "Deleted goal ${p.id}." }
            "skill" -> { SkillPacks.delete(p.ref); "Deleted skill ${p.id} \u201c${p.title}\u201d." }
            "event" -> try {
                val n = ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, p.ref.toLong()), null, null)
                if (n > 0) "Deleted calendar event ${p.id} \u201c${p.title}\u201d." else "That event was already gone from the calendar."
            } catch (e: Exception) { "Couldn't delete the event: ${e.message}" }
            "gevent" -> com.blaze.assistant.connect.Connectors.gcalDelete(p.ref)
            "alarm" -> return alarmOff(ctx, p, delete = true)
            "timer" -> {
                if (p.status == "active" && android.os.Build.VERSION.SDK_INT >= 28) try {
                    ctx.startActivity(Intent(AlarmClock.ACTION_DISMISS_TIMER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
                // v0.45.13: the Clock app gets the cancel; nothing reads it back here.
                "Asked the Clock app to cancel timer ${p.id}. " + ActCheck.notConfirmed("nothing read the Clock app back", "check the Clock app's timer screen")
            }
            else -> "Removed ${p.id}."
        }
        if (!msg.startsWith("Couldn't") && !msg.startsWith("Error")) drop(p.id)
        return msg
    }

    /**
     * Delete (or switch off) an alarm Argus set. 0.55.11: phone control is gone, so nothing can switch it in the Clock app
     * or read the list back; the Clock app is asked to dismiss it by its label (works on some clocks for an alarm that is
     * about to ring) and the result says plainly that it isn't confirmed ([ActCheck] markers). The registry row stays.
     */
    private fun alarmOff(ctx: Context, p: P, delete: Boolean): String {
        val label = p.props.optString("label")
        val hhmm = p.props.optString("time").ifBlank { null } ?: if (p.due > 0) SimpleDateFormat("HH:mm", Locale.US).format(Date(p.due)) else null
        val what = "alarm ${p.id}" + (hhmm?.let { " ($it" + (if (label.isNotBlank()) ", \u201c$label\u201d" else "") + ")" } ?: "")
        val trace = ArrayList<String>()
        try {
            ctx.startActivity(Intent(AlarmClock.ACTION_DISMISS_ALARM)
                .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "${BrandName.NAME} ${p.id}" }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            trace += "sent ACTION_DISMISS_ALARM (label)"
        } catch (_: Exception) { trace += "no app handles ACTION_DISMISS_ALARM" }
        lastTrace = trace
        return "Asked the Clock app to dismiss $what. " + ActCheck.notConfirmed("nothing can read the Clock app back",
            if (delete) "delete it in the Clock app" else "switch it off in the Clock app")
    }

    /** The Clock trace of the last alarm action, for the self-test report. */
    @Volatile var lastTrace: List<String> = emptyList()

    private fun alarmPause(ctx: Context, p: P, off: Boolean): String {
        if (off) return alarmOff(ctx, p, delete = false)
        lastTrace = emptyList()
        return "I can't switch alarm ${p.id} back on from here. " + ActCheck.notConfirmed("nothing can switch the Clock app's alarms", "switch it on in the Clock app")
    }

    private fun pause(p: P, off: Boolean): String = when (p.kind) {
        "routine" -> { Routines.setEnabled(p.ref.toInt(), !off); sync(); "Routine ${p.id} ${if (off) "paused" else "resumed"}." }
        "trigger" -> {
            val t = Triggers.all.value.firstOrNull { it.id.toString() == p.ref }
            if (t != null && t.enabled == off) Triggers.toggle(t.id)
            sync(); "Trigger ${p.id} ${if (off) "paused" else "resumed"}."
        }
        "goal" -> { Autonomy.setStatus(p.ref, if (off) "paused" else "open"); sync(); "Goal ${p.id} ${if (off) "paused" else "resumed"}." }
        else -> "A ${p.kind} can't be paused, only deleted or rescheduled."
    }

    private fun rename(p: P, value: String): String {
        if (value.isBlank()) return "Give the new name in value."
        when (p.kind) {
            "routine" -> Routines.rename(p.ref.toInt(), value)
            "trigger" -> Triggers.rename(p.ref.toLong(), value)
        }
        update(p.id) { it.copy(title = value.take(60)) }
        return "Renamed ${p.id} to \u201c$value\u201d."
    }

    private suspend fun reschedule(ctx: Context, p: P, value: String): String {
        val m = Regex("""(\d{1,2})[:.](\d{2})""").find(value) ?: return "Give the new time as HH:mm in value."
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return "That isn't a valid time."
        return when (p.kind) {
            "routine" -> { Routines.retime(p.ref.toInt(), h, mi); sync(); "Routine ${p.id} now runs at %02d:%02d.".format(h, mi) }
            "alarm" -> {
                // v0.45.13: the old one is deleted (or switched off) and read back first; the new one is checked by set_alarm's own probe.
                val old = alarmOff(ctx, p, delete = true)
                val r = com.blaze.assistant.phone.PhoneTools.run(ctx, "set_alarm", JSONObject().put("hour", h).put("minute", mi)
                    .put("label", p.props.optString("name").ifBlank { p.title }))
                "Old alarm: $old\nNew alarm: $r"
            }
            else -> "Rescheduling a ${p.kind} isn't supported yet; delete it and create a new one."
        }
    }

    /** Next time a clock alarm at h:m will ring. */
    fun nextAt(h: Int, m: Int): Long {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    // ---------- Tools ----------

    val names = setOf("processes", "process")

    fun tools(): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "processes")
            .put("description", "List the things you created that are still tracked (alarms, timers, routines, triggers, calendar events, background jobs) as one line each: ID · kind · title · when · status. Use it to find an ID before changing or deleting something.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(prefix.keys.toList())))
                .put("status", JSONObject().put("type", "string").put("description", "live (active or paused), active, paused, finished…"))))),
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "process")
            .put("description", "Act on one tracked process by its ID (e.g. R4, A12). details returns everything stored about it: only use it when the user asks for specifics.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string"))
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("delete", "pause", "resume", "rename", "reschedule", "details"))))
                .put("value", JSONObject().put("type", "string").put("description", "New name for rename, new time HH:mm for reschedule")))
                .put("required", JSONArray(listOf("id", "action")))))
    )

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = when (name) {
        "processes" -> list(a.optString("kind"), a.optString("status"))
        "process" -> act(ctx, a.optString("id"), a.optString("action"), a.optString("value"))
        else -> "Unknown tool"
    }
}
