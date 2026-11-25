package com.blaze.assistant.phone

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.blaze.assistant.device.Perms
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Move/delete calendar events on the phone itself. Approval is asked by the agent. (Contacts left in the 2026-11-25 permission trim.) */
object PimEdit {
    val names = setOf("move_event", "delete_event")

    private val fmt get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun describe(name: String, a: JSONObject): Pair<String, String> = when (name) {
        "move_event" -> "Move event?" to "${a.optString("title").ifBlank { "event " + a.optString("event_id") }} → ${a.optString("new_start")}"
        else -> "Delete event?" to listOf(a.optString("title"), a.optString("day")).filter { it.isNotBlank() }.joinToString(" \u00b7 ").ifBlank { "event ${a.optString("event_id")}" }
    }

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = try {
        when (name) {
            "move_event" -> moveEvent(ctx, a)
            "delete_event" -> deleteEvent(ctx, a)
            else -> "Unknown tool."
        }
    } catch (e: SecurityException) { Perms.denied("that") } catch (e: Exception) { "Error: ${e.message}" }

    // ---------- calendar ----------

    /**
     * 0.55.14 (phone 24 Nov 02:20: "Deleted the duplicate" removed the only "Go home"): events are found with their ids and
     * all-day flag. [day] narrows to one day; with [id] the event is fetched by id whatever its title.
     */
    private fun findEvents(ctx: Context, title: String, day: String, id: Long): List<EventLines.Event> {
        val from: Long; val to: Long
        if (day.isNotBlank() && id <= 0) {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day.take(10)) ?: return emptyList()
            // all-day events are UTC midnights: widen by a day each side so the local/UTC offset never hides one
            from = d.time - 86_400_000L; to = d.time + 2 * 86_400_000L
        } else { from = System.currentTimeMillis() - 2 * 86_400_000L; to = System.currentTimeMillis() + 366L * 86_400_000L }
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from); ContentUris.appendId(b, to)
        val (where, args) = if (id > 0) "${CalendarContract.Instances.EVENT_ID} = ?" to arrayOf(id.toString())
            else "${CalendarContract.Instances.TITLE} LIKE ?" to arrayOf("%$title%")
        val out = ArrayList<EventLines.Event>()
        ctx.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
            where, args, "${CalendarContract.Instances.BEGIN} ASC")?.use {
            while (it.moveToNext() && out.size < 50) out += EventLines.Event(it.getString(1) ?: title, it.getLong(2), it.getLong(3), it.getInt(4) == 1, it.getString(5), it.getLong(0))
        }
        // a day was named: keep the events on that day (all-day by their UTC date, others by the phone's)
        if (day.isNotBlank() && id <= 0) {
            val want = day.take(10)
            return out.filter { e ->
                val z = if (e.allDay) java.time.ZoneOffset.UTC else java.time.ZoneId.systemDefault()
                java.time.Instant.ofEpochMilli(e.begin).atZone(z).toLocalDate().toString() == want
            }
        }
        return out
    }

    private fun idOf(a: JSONObject): Long = a.optString("event_id").trim().removePrefix("id").trim().toLongOrNull() ?: a.optLong("event_id", 0L)

    private fun pickEvent(ctx: Context, a: JSONObject, verb: String): Pair<EventLines.Event?, String> {
        val id = idOf(a); val title = a.optString("title").trim()
        if (id <= 0 && title.isBlank()) return null to "Which event? Give its event_id from calendar_events (or its title)."
        val zone = java.time.ZoneId.systemDefault()
        return when (val p = EventLines.pick(findEvents(ctx, title, a.optString("day"), id), id)) {
            is EventLines.Pick.One -> p.event to ""
            is EventLines.Pick.Many -> null to EventLines.manyText(verb, title, p.events, zone)
            EventLines.Pick.None -> null to (if (id > 0) "No calendar event with id $id (it may already be gone). Call calendar_events to see what's there."
                else "No event matching \u201c$title\u201d" + (a.optString("day").takeIf { it.isNotBlank() }?.let { " on $it" } ?: "") + ".")
        }
    }

    private suspend fun moveEvent(ctx: Context, a: JSONObject): String {
        if (!Perms.ensure(ctx, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) return Perms.denied("calendar")
        val (ev, why) = pickEvent(ctx, a, "move_event"); if (ev == null) return why
        val start = fmt.parse(a.optString("new_start"))?.time ?: return "new_start must be yyyy-MM-dd HH:mm."
        val len = a.optInt("duration_minutes", 0).takeIf { it > 0 }?.times(60_000L) ?: (ev.end - ev.begin).coerceAtLeast(15 * 60_000L)
        val n = ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.id),
            ContentValues().apply { put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, start + len)
                if (ev.allDay) { put(CalendarContract.Events.ALL_DAY, 0); put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id) } }, null, null)
        val f = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
        return if (n > 0) "Moved \u201c${ev.title}\u201d to ${f.format(Date(start))}\u2013${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start + len))}." else "Couldn't move \u201c${ev.title}\u201d (it may belong to a read-only calendar)."
    }

    private suspend fun deleteEvent(ctx: Context, a: JSONObject): String {
        if (!Perms.ensure(ctx, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) return Perms.denied("calendar")
        val (ev, why) = pickEvent(ctx, a, "delete_event"); if (ev == null) return why
        val n = ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.id), null, null)
        val zone = java.time.ZoneId.systemDefault()
        // what's left with that title, so the reply never claims a "duplicate" was removed when it was the only one
        val left = runCatching { findEvents(ctx, ev.title, "", 0).count { it.title.equals(ev.title, true) && it.id != ev.id } }.getOrDefault(0)
        return if (n > 0) "Deleted \u201c${ev.title}\u201d (${EventLines.whenText(ev, zone)}) [id ${ev.id}]. " +
            (if (left == 0) "No other event with that title is left on the calendar." else "$left other event${if (left == 1) "" else "s"} with that title remain${if (left == 1) "s" else ""}.") +
            (ev.location?.takeIf { it.isNotBlank() }?.let { " It was at $it." } ?: "")
        else "Couldn't delete \u201c${ev.title}\u201d (read-only calendar?)."
    }

    fun tools(): List<JSONObject> {
        fun fn(n: String, d: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("description", d)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
        fun s(d: String) = JSONObject().put("type", "string").put("description", d)
        return listOf(
            fn("move_event", "Move a phone calendar event to a new time. Asks the user first.", JSONObject().put("event_id", s("The event's id from calendar_events ([id N]); best")).put("title", s("Event title (or part)")).put("day", s("yyyy-MM-dd it's on now, if known"))
                .put("new_start", s("yyyy-MM-dd HH:mm")).put("duration_minutes", JSONObject().put("type", "integer").put("description", "New length; default keeps it")), listOf("new_start")),
            fn("delete_event", "Delete one phone calendar event, for example one that is cancelled or no longer wanted. Find it with calendar_events and pass its event_id. Asks the user first.",
                JSONObject().put("event_id", s("The event's id from calendar_events ([id N])")).put("title", s("Event title (or part), when there's no id")).put("day", s("yyyy-MM-dd, if known")), emptyList()),
        )
    }
}
