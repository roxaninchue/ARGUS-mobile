package com.blaze.assistant.phone


import com.blaze.assistant.BrandName
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.blaze.assistant.agent.Agent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Calendar and quick settings. Work without phone control. (Messages, calls and contacts left in the 2026-11-25 permission trim.) */
object Skills {
    /** Which runtime permissions each skill group needs (Settings → Skills grants them). */
    val groups = linkedMapOf(
        "Calendar" to arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    )

    fun granted(ctx: Context, perms: Array<String>) =
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    private fun missing(group: String) =
        "${BrandName.NAME} doesn't have the $group permission yet. Tell the user to open ${BrandName.NAME} → Settings → Skills and turn on $group."

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))))
    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    val names = setOf("calendar_events", "add_calendar_event", "device_setting")

    fun definitions(): List<JSONObject> = listOf(
        fn("calendar_events", "List the phone calendar's events from about an hour ago through the next N days (title, time, place, event id). Find an event here before move_event reschedules it or delete_event removes it (for example one that is over or cancelled).",
            JSONObject().put("days", p("integer", "Days ahead, default 1 (today)"))),
        // 18 Nov 2026 (owner): a reminder belongs on the calendar, where it reaches the user offline and on every device.
        fn("add_calendar_event", "Add an event to the user's calendar. With reminder_minutes the calendar app alerts the user itself, offline and on their other devices; repeat makes it recurring.",
            JSONObject().put("title", p("string", "Event title"))
                .put("start", p("string", "Start, local time, format yyyy-MM-dd HH:mm"))
                .put("end", p("string", "End, yyyy-MM-dd HH:mm (optional, default 1 hour)"))
                .put("location", p("string", "Optional"))
                .put("reminder_minutes", p("integer", "Optional: alert this many minutes before (0 = at the start)"))
                .put("repeat", p("string", "Optional: daily, weekdays, weekends, or days like 'mon,wed,fri'")), listOf("title", "start")),
        fn("device_setting", "Quick settings: flashlight on/off, volume up/down/mute/0-100, battery level, or open the Wi-Fi, Bluetooth, mobile data, Do Not Disturb, or brightness panel for the user.",
            JSONObject().put("setting", JSONObject().put("type", "string").put("enum",
                JSONArray(listOf("flashlight", "volume", "battery", "wifi", "bluetooth", "mobile_data", "do_not_disturb", "brightness", "airplane"))))
                .put("value", p("string", "on/off, up/down/mute, or a number 0-100")), listOf("setting"))
    )

    fun describe(name: String, a: JSONObject): String = when (name) {
        "calendar_events" -> "Checking your calendar"
        "add_calendar_event" -> "Adding \"${a.optString("title")}\" to your calendar"
        "device_setting" -> "Adjusting ${a.optString("setting").replace('_', ' ')}"
        else -> name
    }

    /** The runtime permissions each tool needs: asked for in-app the first time instead of failing. */
    private val needs = mapOf(
        "calendar_events" to arrayOf(Manifest.permission.READ_CALENDAR),
        "add_calendar_event" to arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
    )

    /** v0.45.9: never "I can't access your calendar": calling calendar_events again shows Android's one-tap prompt. */
    const val CALENDAR_OFF = "Calendar access is off: the user didn't allow it in the one-tap prompt (yet). Say that in one short line and offer to ask again; " +
        "if they say yes, call calendar_events again (it shows the prompt and then lists the events). Never say you can't access calendars."

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = try {
        val want = needs[name]
        val denied = if (want != null && !com.blaze.assistant.device.Perms.ensure(ctx, *want) && name != "add_calendar_event")
            (if (name == "calendar_events") CALENDAR_OFF else com.blaze.assistant.device.Perms.denied(name.replace('_', ' '))) else null
        denied ?: when (name) {
            "calendar_events" -> events(ctx, a.optInt("days", 1))
            "add_calendar_event" -> addEvent(ctx, a)
            "device_setting" -> setting(ctx, a.getString("setting"), a.optString("value"))
            else -> "Unknown skill $name"
        }
    } catch (e: SecurityException) { "Permission denied: ${e.message}" } catch (e: Exception) { "Error: ${e.message}" }

    // ---------- Calendar ----------

    private fun events(ctx: Context, days: Int): String {
        if (!has(ctx, Manifest.permission.READ_CALENDAR)) return missing("Calendar")
        val now = System.currentTimeMillis()
        val endCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days.coerceIn(1, 31) - 1); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(b, now - 3_600_000)
        android.content.ContentUris.appendId(b, endCal.timeInMillis)
        val evs = mutableListOf<EventLines.Event>()
        ctx.contentResolver.query(b.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.EVENT_ID),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use {
            while (it.moveToNext() && evs.size < 40) {
                evs += EventLines.Event(it.getString(0).orEmpty(), it.getLong(1), it.getLong(2), it.getInt(3) == 1, it.getString(4), it.getLong(5))
            }
        }
        // 18 Nov 2026: the result opens with today's date and marks each event's day (today / tomorrow / in N days), so
        // the first listed day is never read as today (EventLines).
        val nowZ = java.time.ZonedDateTime.now()
        return eventsResult(EventLines.lines(evs, nowZ), nowZ)
    }

    /**
     * v0.45.9 replay fix (rpG schedule-wednesday 4/5: after an empty calendar_events the reply said "Nothing scheduled today"
     * although the [context] schedule line had CIT 08:00 and NET 14:00): an empty result says it is only the phone calendar.
     */
    const val EVENTS_NONE = "No events in the phone's calendar app for that range. That is only the Android calendar, not the whole day: " +
        "the schedule line in [context] still holds the day's classes and reminders, so when asked what's on, name every item on it with its time."

    /**
     * v0.45.9 replay fix (live 0.8.7, "View calendar" 1 of 5 answered only "Open the calendar?"): the result says what to do
     * with the events, so the reply lists them. Pure; HaloReplayRequestTest builds its fixture with it.
     */
    fun eventsResult(lines: List<String>, now: java.time.ZonedDateTime? = null): String {
        // 18 Nov 2026: with [now], the result starts with "Today is Wed 18 Nov 2026, 10:51 (Africa/Lusaka)." (EventLines.header).
        val head = now?.let { EventLines.header(it) + "\n" }.orEmpty()
        if (lines.isEmpty()) return head + EVENTS_NONE
        // 0.55.14: with ids listed, say what they mean: one line per event, so two reads of this list are never "a duplicate".
        return head + EventLines.idNote(lines) + "Calendar events (tell the user each one with its time and place):\n" + lines.joinToString("\n")
    }

    /** 0.55.2: same title (case and spaces ignored) and a start within a minute. Pure; SameEventTest. */
    fun sameEvent(t1: String, s1: Long, t2: String, s2: Long): Boolean {
        fun n(t: String) = t.trim().lowercase().replace(Regex("\\s+"), " ")
        return n(t1) == n(t2) && Math.abs(s1 - s2) < 60_000L
    }

    private fun sameEventAt(ctx: Context, title: String, start: Long): String? = try {
        if (!has(ctx, Manifest.permission.READ_CALENDAR)) null else {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                android.content.ContentUris.appendId(it, start - 60_000L); android.content.ContentUris.appendId(it, start + 60_000L) }.build()
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN), null, null, null)?.use { c ->
                var hit: String? = null
                while (hit == null && c.moveToNext()) if (sameEvent(title, start, c.getString(0) ?: "", c.getLong(1)))
                    hit = "Already on the calendar: \"$title\" on ${SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(start))}. Not added again."
                hit
            }
        }
    } catch (_: Exception) { null }

    private fun addEvent(ctx: Context, a: JSONObject): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val start = f.parse(a.getString("start"))?.time ?: return "Couldn't read the start time."
        val end = a.optString("end").takeIf { it.isNotBlank() }?.let { f.parse(it)?.time } ?: (start + 3_600_000)
        if (!has(ctx, Manifest.permission.WRITE_CALENDAR)) {
            ctx.startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, a.getString("title"))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .putExtra(CalendarContract.Events.EVENT_LOCATION, a.optString("location"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Opened the calendar with the event filled in; the user taps Save."
        }
        // The user's primary writable calendar.
        val calId = ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null, null)?.use {
            var first: Long? = null; var primary: Long? = null
            while (it.moveToNext()) { if (first == null) first = it.getLong(0); if (it.getInt(1) == 1) primary = it.getLong(0) }
            primary ?: first
        } ?: com.blaze.assistant.routine.RoutineCalendar.calendarId(ctx) ?: return "No writable calendar on this phone."   // 18 Nov 2026: else the ARGUS calendar
        val rule = com.blaze.assistant.routine.RoutineCalendar.repeatRule(a.optString("repeat"))
        // 0.55.2 (Finn 18:08: "Pack for COM102 exam" twice at 20:00, "Leave for COM102" twice): never add an event that is
        // already there (same title, same start), however the turn got here (a retry, a resend, a second ask).
        if (rule == null) sameEventAt(ctx, a.getString("title"), start)?.let { return it }
        val remind = if (a.has("reminder_minutes")) a.optInt("reminder_minutes", -1) else -1
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, a.getString("title"))
            put(CalendarContract.Events.DTSTART, start)
            if (rule != null) { put(CalendarContract.Events.RRULE, rule); put(CalendarContract.Events.DURATION, "PT${((end - start) / 60_000L).coerceAtLeast(1)}M") }
            else put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (remind >= 0) put(CalendarContract.Events.HAS_ALARM, 1)
            a.optString("location").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return "The calendar refused the event."
        if (remind >= 0) com.blaze.assistant.routine.RoutineCalendar.alert(ctx, android.content.ContentUris.parseId(uri), remind)
        val pid = com.blaze.assistant.agent.Processes.register("event", a.getString("title"), android.content.ContentUris.parseId(uri).toString(), start,
            JSONObject().put("location", a.optString("location")).put("start", a.getString("start")).put("end", a.optString("end")))
        return "Added \"${a.getString("title")}\" on ${SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(start))}" +
            (if (rule != null) ", repeating ($rule)" else "") + (if (remind >= 0) ", alert $remind min before" else "") + ". [ID $pid]"
    }

    // ---------- Quick settings ----------

    private fun panel(ctx: Context, action: String, fallback: String): String {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); fallback } catch (_: Exception) {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opened Settings."
        }
    }

    private suspend fun setting(ctx: Context, s: String, value: String): String {
        val v = value.trim().lowercase()
        val want = when (v) { "on", "enable", "true", "1" -> true; "off", "disable", "false", "0" -> false; else -> null }
        // v0.28.2: changing a phone setting always shows the confirm card, except a command the user typed or said
        // themselves (the fast lane, ConfirmGate.Direct). Reading (battery) and opening a settings panel don't ask.
        suspend fun approved(): Boolean = kotlin.coroutines.coroutineContext[com.blaze.assistant.agent.ConfirmGate.DirectKey] != null ||
            Agent.confirm("Change a phone setting?", settingLine(s, v), "Change", always = true)
        return when (s) {
            "flashlight" -> {
                if (!approved()) return "The user declined. The setting was NOT changed."
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: return "This phone has no flashlight."
                val on = v != "off"
                cm.setTorchMode(id, on); "Flashlight ${if (on) "on" else "off"}."
            }
            "volume" -> {
                if (v.isNotEmpty() && !approved()) return "The user declined. The setting was NOT changed."
                val am = ctx.getSystemService(AudioManager::class.java)
                val st = AudioManager.STREAM_MUSIC
                when {
                    v == "up" -> am.adjustStreamVolume(st, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    v == "down" -> am.adjustStreamVolume(st, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    v == "mute" || v == "off" -> am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    v == "unmute" || v == "on" -> am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    v.toIntOrNull() != null -> am.setStreamVolume(st, am.getStreamMaxVolume(st) * v.toInt().coerceIn(0, 100) / 100, AudioManager.FLAG_SHOW_UI)
                }
                "Media volume is ${am.getStreamVolume(st) * 100 / am.getStreamMaxVolume(st).coerceAtLeast(1)}%."
            }
            "battery" -> {
                val bm = ctx.getSystemService(BatteryManager::class.java)
                "Battery ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%${if (bm.isCharging) ", charging" else ""}."
            }
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) panel(ctx, android.provider.Settings.Panel.ACTION_WIFI, "Opened the Wi-Fi panel. Flip Wi-Fi there.")
                else panel(ctx, android.provider.Settings.ACTION_WIFI_SETTINGS, "Opened Wi-Fi settings.")
            "bluetooth" -> panel(ctx, android.provider.Settings.ACTION_BLUETOOTH_SETTINGS, "Opened Bluetooth settings.")
            "mobile_data" -> if (Build.VERSION.SDK_INT >= 29) panel(ctx, android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "Opened the internet panel.")
                else panel(ctx, android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS, "Opened mobile data settings.")
            "do_not_disturb" -> panel(ctx, "android.settings.ZEN_MODE_SETTINGS", "Opened Do Not Disturb settings.")
            "brightness" -> panel(ctx, android.provider.Settings.ACTION_DISPLAY_SETTINGS, "Opened display settings.")
            "airplane" -> panel(ctx, android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Opened airplane mode settings.")
            else -> "Unknown setting."
        }
    }

    /** The confirm card's line for a setting change ("Wi-Fi: off", "Media volume: 40"). */
    private fun settingLine(s: String, v: String): String {
        val name = when (s) { "wifi" -> "Wi-Fi"; "do_not_disturb" -> "Do Not Disturb"; "mobile_data" -> "Mobile data"; "airplane" -> "Airplane mode"
            "volume" -> "Media volume"; else -> s.replace('_', ' ').replaceFirstChar { it.uppercase() } }
        return if (v.isBlank()) "$name: switch" else "$name: $v"
    }

}
