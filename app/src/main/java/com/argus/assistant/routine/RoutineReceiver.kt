package com.argus.assistant.routine


import com.argus.assistant.BrandName
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.argus.assistant.R
import com.argus.assistant.agent.Agent
import com.argus.assistant.agent.Mood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Alarm fired, phone rebooted, or Argus updated: run the routine / reschedule. */
class RoutineReceiver : BroadcastReceiver() {
    companion object { const val FIRE = "com.argus.assistant.ROUTINE" }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != FIRE) { Routines.scheduleAll(); return }
        val id = intent.getIntExtra("id", -1)
        // Account lock: the routine is skipped (a repeating one stays scheduled for its next time).
        if (com.argus.assistant.connect.AccountGate.locked()) { Routines.ran(id); com.argus.assistant.connect.AccountGate.notice(ctx); return }
        val svc = Intent(ctx, RoutineService::class.java).putExtra("id", id)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
        } catch (_: Exception) {
            // Android refused a background service: run it in the broadcast's short window instead.
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Main + com.argus.assistant.device.CrashLog.guard).launch {
                withTimeoutOrNull(55_000) { RoutineService.runRoutine(ctx, id) }
                pending.finish()
            }
        }
    }
}

/** Keeps Argus alive while a routine runs, then stops. */
class RoutineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.argus.assistant.device.CrashLog.guard)

    companion object {
        /** [due] > 0: a run that waited for the network (RoutineLater); the prompt says when it was due. */
        suspend fun runRoutine(ctx: Context, id: Int, due: Long = 0L) {
            val r = Routines.get(id) ?: return
            if (com.argus.assistant.connect.AccountGate.locked()) { Routines.ran(id); return }   // account lock (locked on the way)
            if (r.isCommand) {
                val out = try { com.argus.assistant.terminal.Terminal.run(r.command, 600, r.linux, distro = "alpine") } catch (e: Exception) { "exit 1\nError: ${e.message}" }
                val failed = !out.startsWith("exit 0")
                com.argus.assistant.terminal.Terminal.logAgent("[scheduled: ${r.name}] $ ${r.command}", out)
                if (r.notify == "always" || (r.notify == "on_failure" && failed)) {
                    val tail = out.lines().drop(1).filter { it.isNotBlank() && !it.startsWith("[hint") }.takeLast(4).joinToString("\n").take(300)
                    com.argus.assistant.notify.Notifier.proactive("${if (failed) "\u26A0 " else ""}${r.name} ${if (failed) "failed" else "done"}${if (tail.isNotBlank()) "\n$tail" else ""}", "", failed)
                }
                Routines.ran(id)
                return
            }
            // 18 Nov 2026 (owner: "say I'm offline, how will I receive routines?"): the calendar alert (RoutineCalendar) has
            // already reached the user; ARGUS's own part needs a model, so offline it waits for the network and runs then.
            if (due == 0L && !com.argus.assistant.agent.FastLane.online(ctx)) {
                RoutineLater.queue(ctx, id, System.currentTimeMillis())
                com.argus.assistant.notify.Notifier.proactive(RoutineLater.offlineNote(r.name), "", false)
                Routines.ran(id)
                return
            }
            // Let anything already running finish first.
            withTimeoutOrNull(120_000) { while (Agent.mood.value != Mood.IDLE) delay(1000) }
            Agent.runRoutine(r.name, if (due > 0) RoutineLater.latePrompt(r.prompt, due) else r.prompt)
            delay(500)
            withTimeoutOrNull(180_000) { while (Agent.mood.value != Mood.IDLE) delay(700) }
            if (due == 0L) Routines.ran(id)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra("id", -1) ?: -1
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel("blaze_routine", "${BrandName.NAME} routines", NotificationManager.IMPORTANCE_MIN))
        val n: Notification = NotificationCompat.Builder(this, "blaze_routine")
            .setSmallIcon(R.drawable.ic_stat_blaze).setContentTitle("${BrandName.NAME} routine")
            .setContentText(Routines.get(id)?.name ?: "Running").setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(77, n)
        } catch (_: Exception) { }
        scope.launch {
            runRoutine(this@RoutineService, id)
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
