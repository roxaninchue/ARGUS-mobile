package com.blaze.assistant.agent

import android.app.AlarmManager
import android.content.Context
import java.io.File
import java.util.Calendar

/**
 * v0.44.0: the phone side of [ToolVerify.Probes]: what is in front (phone control), the next system alarm, and whether
 * a written file is there. Each check is cheap and returns null when it can't tell, so the result
 * stays "couldn't confirm" instead of guessing.
 */
class VerifyProbes(private val ctx: Context) : ToolVerify.Probes {
    override val self: String get() = ctx.packageName

    // 0.55.11: no phone control, so the app in front, the Clock app's list and the screen's words can't be read (null = can't tell).
    override suspend fun foreground(): String? = null

    override suspend fun nextAlarm(): Pair<Int, Int>? = try {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.nextAlarmClock?.triggerTime?.let { t -> Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.HOUR_OF_DAY) to it.get(Calendar.MINUTE) } }
    } catch (_: Throwable) { null }

    override suspend fun nextAlarmAt(): Long? = try {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime
    } catch (_: Throwable) { null }

    override suspend fun settingOn(setting: String): Boolean? = try {
        when (setting) {
            "wifi" -> (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
            "bluetooth" -> (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled
            "airplane" -> android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            "do_not_disturb" -> (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).currentInterruptionFilter.let {
                it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL && it != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            "mobile_data" -> android.provider.Settings.Global.getInt(ctx.contentResolver, "mobile_data", -1).takeIf { it >= 0 }?.let { it != 0 }
            else -> null
        }
    } catch (_: Throwable) { null }

    // ---------- v0.45.13 verify-after-act ----------

    override suspend fun clockAlarmOn(pid: String, hhmm: String): Boolean? = null

    override suspend fun calendarHas(title: String, startMs: Long): Boolean? = try {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) null
        else {
            val b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(b, startMs - 60_000L); android.content.ContentUris.appendId(b, startMs + 60_000L)
            ctx.contentResolver.query(b.build(), arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN), null, null, null)?.use { c ->
                var hit = false
                while (c.moveToNext()) { if (c.getString(0).orEmpty().trim().equals(title.trim(), true) && Math.abs(c.getLong(1) - startMs) <= 60_000L) { hit = true; break } }
                hit
            }
        }
    } catch (_: Throwable) { null }

    override suspend fun screenWords(): String? = null

    override fun fileSize(path: String): Long? = try {
        val f = File(path)
        if (f.isFile) f.length() else if (f.parentFile?.canRead() == true) -1L else null
    } catch (_: Throwable) { null }
}
