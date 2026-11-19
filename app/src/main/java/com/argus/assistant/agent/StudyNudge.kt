package com.blaze.assistant.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blaze.assistant.MainActivity
import com.blaze.assistant.R
import java.util.concurrent.TimeUnit

/**
 * Flashcards phase 3 (2026-11-18): one evening reminder (6 pm local) when flashcards are waiting, e.g.
 * "12 cards waiting in NET103 session 1". Nothing waits, no notification. Off with Learn › Study reminder.
 * The text and the time come from [Flashcards.nudgeText] / [Flashcards.nudgeAt] (unit-tested).
 */
object StudyNudge {
    private const val CH = "study"
    private const val WORK = "study_nudge"
    const val SETTING = "study_reminder"

    var enabled: Boolean
        get() = Settings.bool(SETTING, true)
        set(v) = Settings.setBool(SETTING, v)

    /** Next wake at 6 pm (WorkManager, survives reboots). Off: the pending wake is cancelled. */
    fun schedule(ctx: Context) {
        try {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) { wm.cancelUniqueWork(WORK); return }
            val now = System.currentTimeMillis()
            wm.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<StudyNudgeWorker>()
                .setInitialDelay((Flashcards.nudgeAt(now) - now).coerceAtLeast(5_000), TimeUnit.MILLISECONDS).build())
        } catch (_: Throwable) { /* WorkManager absent in JVM tests */ }
    }

    fun fire(ctx: Context) {
        if (enabled) Flashcards.nudgeText()?.let { text ->
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Study reminders", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 78, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            try { nm.notify(4600, NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat_blaze)
                .setContentTitle("Time to study").setContentText(text).setContentIntent(open).setAutoCancel(true).build()) } catch (_: Throwable) { /* no notification permission */ }
        }
        schedule(ctx)
    }
}

class StudyNudgeWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result { try { StudyNudge.fire(applicationContext) } catch (_: Throwable) { /* next run reschedules */ }; return Result.success() }
}
