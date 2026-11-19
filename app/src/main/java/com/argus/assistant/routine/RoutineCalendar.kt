package com.argus.assistant.routine

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * Routines on the user's calendar (owner, 18 Nov 2026: "it would make more sense to me if the routine would add a reminder
 * to my calendar not in app … say I'm offline, how will I receive routines?").
 *
 * Every routine ARGUS runs from a prompt also lives as a calendar event with an alert, so the reminder reaches the user
 * from their calendar app: offline, with ARGUS closed, and on their other devices. The event repeats like the routine
 * (RRULE), is renamed, retimed, paused and deleted with it, and keeps the id in [Routine.eventId]. Shell jobs and
 * every-N-minutes routines stay off the calendar (they aren't reminders).
 *
 * Which calendar: the phone's Google calendar when a Google account is signed in on the phone (it syncs everywhere,
 * no ARGUS connection needed), else a local "ARGUS" calendar that ARGUS creates (owner: "if it's available then yes
 * Google calendar if not we go ARGUS calendar"). Pure rules ([rrule], [pick]) are tested in RoutineCalendarTest.
 */
object RoutineCalendar {
    const val GOOGLE = "com.google"
    const val ARGUS_NAME = "ARGUS"
    private const val ARGUS_ACCOUNT = "ARGUS"
    /** The event is short: it marks the time; the alert is what matters. */
    const val DURATION_MIN = 15

    /** Whether [r] belongs on the calendar. */
    fun wanted(r: Routine): Boolean = r.enabled && !r.isCommand && r.everyMinutes == 0

    /** The repeat rule for [r]'s days, or null for a one-off. Mon=1 … Sun=64, as in [Routine.days]. */
    fun rrule(r: Routine): String? {
        if (r.at > 0 || r.everyMinutes > 0) return null
        return ruleFor(r.days)
    }

    /** RRULE for a [Routine.days] mask; null for 0 (once). Also used by add_calendar_event's "repeat". */
    fun ruleFor(days: Int): String? {
        if (days == 0) return null
        if (days and 127 == 127) return "FREQ=DAILY"
        val names = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU").filterIndexed { i, _ -> days and (1 shl i) != 0 }
        return "FREQ=WEEKLY;BYDAY=" + names.joinToString(",")
    }

    /** add_calendar_event / gcal_create "repeat" → RRULE; blank or "once" → null. */
    fun repeatRule(repeat: String): String? = if (repeat.isBlank()) null else ruleFor(Routines.parseDays(repeat))

    /** A writable calendar on the phone, as [pick] reads it. */
    data class Cal(val id: Long, val accountType: String, val primary: Boolean, val visible: Boolean = true)

    /** The Google calendar to use: the primary Google one, else any Google one; null when there is none (→ ARGUS calendar). */
    fun pick(cals: List<Cal>): Long? {
        val g = cals.filter { it.accountType == GOOGLE }
        return (g.firstOrNull { it.primary } ?: g.firstOrNull { it.visible } ?: g.firstOrNull())?.id
    }

    fun allowed(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun writable(ctx: Context): List<Cal> = ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE),
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null, null)?.use { c ->
        buildList { while (c.moveToNext()) add(Cal(c.getLong(0), c.getString(1).orEmpty(), c.getInt(2) == 1, c.getInt(3) == 1)) }
    }.orEmpty()

    private fun asSync(uri: Uri): Uri = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ARGUS_ACCOUNT)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    /** The local ARGUS calendar, made the first time it's needed. */
    private fun argusCalendar(ctx: Context): Long? {
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.ACCOUNT_NAME} = ?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, ARGUS_ACCOUNT), null)?.use { if (it.moveToFirst()) return it.getLong(0) }
        val v = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ARGUS_ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, ARGUS_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, ARGUS_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF3B5BDB.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ARGUS_ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return ctx.contentResolver.insert(asSync(CalendarContract.Calendars.CONTENT_URI), v)?.let { ContentUris.parseId(it) }
    }

    /** The calendar routines go on: the phone's Google calendar, else ARGUS's own. */
    fun calendarId(ctx: Context): Long? = pick(writable(ctx)) ?: argusCalendar(ctx)

    /**
     * Puts [r] on the calendar (or updates its event) and returns the event id; 0 when it isn't wanted there and its old
     * event was removed; null when the calendar can't be written (no permission, no calendar): the routine still runs.
     */
    fun sync(ctx: Context, r: Routine, now: Long = System.currentTimeMillis()): Long? = try {
        if (!allowed(ctx)) null
        else if (!wanted(r)) { remove(ctx, r.eventId); 0L }
        else {
            val start = Routines.next(r, now)
            val rule = rrule(r)
            val v = ContentValues().apply {
                put(CalendarContract.Events.TITLE, r.name)
                put(CalendarContract.Events.DESCRIPTION, (if (r.prompt.isNotBlank()) r.prompt + "\n\n" else "") + "Set by ARGUS.")
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 1)
                if (rule != null) { put(CalendarContract.Events.RRULE, rule); put(CalendarContract.Events.DURATION, "PT${DURATION_MIN}M"); putNull(CalendarContract.Events.DTEND) }
                else { put(CalendarContract.Events.DTEND, start + DURATION_MIN * 60_000L); putNull(CalendarContract.Events.RRULE); putNull(CalendarContract.Events.DURATION) }
            }
            val existing = r.eventId.takeIf { it > 0 && exists(ctx, it) }
            val id = if (existing != null) {
                ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing), v, null, null); existing
            } else {
                val cal = calendarId(ctx) ?: return null
                v.put(CalendarContract.Events.CALENDAR_ID, cal)
                ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)?.let { ContentUris.parseId(it) }?.also { alert(ctx, it, 0) }
            }
            id
        }
    } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("RoutineCalendar.sync", e); null }

    /** An alert [minutes] before the event, shown by the calendar app. */
    fun alert(ctx: Context, eventId: Long, minutes: Int) {
        ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes.coerceAtLeast(0))
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
    }

    private fun exists(ctx: Context, id: Long): Boolean = ctx.contentResolver.query(
        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DELETED), null, null, null)
        ?.use { it.moveToFirst() && it.getInt(1) == 0 } ?: false

    fun remove(ctx: Context, eventId: Long) {
        if (eventId <= 0 || !allowed(ctx)) return
        try { ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null) }
        catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("RoutineCalendar.remove", e) }
    }
}
