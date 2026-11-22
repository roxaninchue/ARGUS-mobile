package com.blaze.assistant.agent

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * 0.55.4 (Finn 19:53, again: Home showed "20:00 Alarm" while the Clock app said "No alarms"). AlarmManager.nextAlarmClock
 * is the next "alarm clock" ANY app registered (a calendar or reminder app uses the same API), not the Clock app's next
 * alarm. It only counts as an alarm when a clock app created it (its show intent's creator handles SHOW_ALARMS or is a
 * known clock package). Pure parts tested in SystemAlarmTest.
 */
object SystemAlarm {
    /** Another app's alarm clock is next, so the Clock app's next alarm (if any) can't be seen. */
    const val UNKNOWN = -1L

    /** Clock apps by package, for phones where SHOW_ALARMS doesn't resolve (Samsung, Pixel, AOSP, Xiaomi, Oppo, Transsion...). */
    val KNOWN_CLOCKS = setOf(
        "com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock", "com.oneplus.deskclock",
        "com.coloros.alarmclock", "com.oplus.alarmclock", "com.huawei.deskclock", "com.hihonor.deskclock", "com.transsion.deskclock",
        "com.sonyericsson.organizer", "com.motorola.blur.alarmclock", "com.lge.clock", "com.vivo.alarmclock", "com.asus.deskclock",
        "com.zte.deskclock", "com.htc.android.worldclock", "com.realme.alarmclock", "com.nothing.deskclock")

    fun fromClock(creator: String?, clocks: Set<String>): Boolean = !creator.isNullOrBlank() && (creator in clocks || creator in KNOWN_CLOCKS)

    /**
     * The Clock app's next alarm: [trigger] when a clock app made it, null when no app has one, [UNKNOWN] when another app's
     * alarm clock is next (that one hides the Clock's).
     */
    fun decide(trigger: Long?, creator: String?, clocks: Set<String>): Long? = when {
        trigger == null || trigger <= 0L -> null
        fromClock(creator, clocks) -> trigger
        else -> UNKNOWN
    }

    /** Packages that open the alarm list or set an alarm (needs the SHOW_ALARMS / SET_ALARM <queries> on Android 11+). */
    fun clockPackages(ctx: Context): Set<String> = try {
        val pm = ctx.packageManager
        (pm.queryIntentActivities(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0) + pm.queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), 0))
            .mapNotNull { it.activityInfo?.packageName }.filter { it != ctx.packageName }.toSet()
    } catch (_: Throwable) { emptySet() }

    /** [decide] on the phone; UNKNOWN when it can't be read at all. */
    fun read(ctx: Context): Long? = try {
        val n = ctx.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock
        if (n == null) null else decide(n.triggerTime, n.showIntent?.creatorPackage, clockPackages(ctx))
    } catch (_: Throwable) { UNKNOWN }
}
