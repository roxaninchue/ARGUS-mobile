package com.argus.assistant.routine

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * 18 Nov 2026 (owner: "say I'm offline, how will I receive routines?"). A routine that comes due while the phone is
 * offline still reaches the user through its calendar alert ([RoutineCalendar]); ARGUS's own part (the brief, the check)
 * needs a model, so it waits here for the network and runs as soon as the phone is back online, saying it's late.
 * One pending run per routine (a newer one replaces it). Pure text in [offlineNote] / [latePrompt]; RoutineCalendarTest.
 */
object RoutineLater {
    fun name(id: Int) = "argus-routine-late-$id"

    fun queue(ctx: Context, id: Int, due: Long) {
        val req = OneTimeWorkRequestBuilder<RoutineLaterWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("id" to id, "due" to due))
            .build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(name(id), ExistingWorkPolicy.REPLACE, req)
    }

    fun offlineNote(name: String) = "$name: you're offline, so I'll run it as soon as you're back online."

    fun latePrompt(prompt: String, due: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val f = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.ENGLISH).apply { timeZone = zone }
        return "[This was due ${f.format(java.util.Date(due))}; the phone was offline then, so it runs now. Say so if the timing matters.]\n$prompt"
    }
}

class RoutineLaterWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val id = inputData.getInt("id", -1)
        val due = inputData.getLong("due", System.currentTimeMillis())
        if (id < 0 || com.argus.assistant.connect.AccountGate.locked()) return Result.success()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { RoutineService.runRoutine(applicationContext, id, due) }
        return Result.success()
    }
}
