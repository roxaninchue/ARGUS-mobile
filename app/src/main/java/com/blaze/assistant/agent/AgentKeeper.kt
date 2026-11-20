package com.blaze.assistant.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blaze.assistant.BrandName
import com.blaze.assistant.R
import com.blaze.assistant.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 0.54.1: keeps background agents and blueprint runs alive once Argus is left (a dataSync foreground service holds the
 * process, like terminal/JobKeeper) and shows exactly where each one is: its name, "Part 6 of 14" (or "Step 23"), the
 * time it has run, and what it is doing now (AgentNote). Open goes to its chat, Stop cancels it. Stops itself when no
 * run is left; the run's result still lands in the chat as before.
 */
class AgentKeeper : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var shown = ""

    companion object {
        private const val ID = 79
        private const val CH = "argus_agents"
        private const val ACT_STOP = "argus.agent.STOP_RUN"
        private const val EXTRA_ID = "run_id"

        private const val CH_END = "argus_agent_results"
        private val ICON = R.drawable.ic_stat_blaze

        /**
         * A run ended: say so in a notification of its own (done, partly done or stopped, with which parts didn't finish),
         * unless you're looking at Argus right now or you stopped it yourself.
         */
        fun ended(ctx: Context, bg: BgAgents.Bg) {
            val t = AgentNote.ended(bg) ?: return
            try {
                if (Notifier.isVisible()) return
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH_END, "Background run results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When research, a build or an agent finishes or stops, and why" })
                val n = NotificationCompat.Builder(ctx, CH_END).setSmallIcon(ICON)
                    .setContentTitle(t.title).setContentText(t.body).setStyle(NotificationCompat.BigTextStyle().bigText(t.body))
                    .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setContentIntent(Notifier.openChat(bg.parent)).build()
                nm.notify(8000 + (bg.id.filter { it.isDigit() }.toIntOrNull() ?: 0) % 900, n)
            } catch (_: Throwable) { }
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, AgentKeeper::class.java)
            // Android 12+ refuses a foreground start from the background (e.g. a run resumed at boot); the run still goes on.
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (_: Exception) { }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun live() = BgAgents.all.value.filter { it.live }

    private fun note(live: List<BgAgents.Bg>): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Background runs", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Research, builds and agents ${BrandName.NAME} is running for you, with their progress"; setShowBadge(false) })
        val t = AgentNote.build(live)
        val first = live.firstOrNull()
        val b = NotificationCompat.Builder(this, CH).setSmallIcon(ICON)
            .setContentTitle(t.title).setContentText(t.body)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(try { Notifier.openChat(first?.parent) } catch (_: Throwable) { null })
        // Elapsed time ticks on its own (no per-second updates): counted from when the oldest running one started.
        live.filter { it.started > 0 }.minOfOrNull { it.started }?.let { b.setWhen(it).setShowWhen(true).setUsesChronometer(true) }
        if (t.lines.isNotEmpty()) b.setStyle(NotificationCompat.InboxStyle().also { s -> t.lines.take(6).forEach { s.addLine(it) } })
        else b.setStyle(NotificationCompat.BigTextStyle().bigText(t.body))
        if (t.total > 0) b.setProgress(t.total, t.done.coerceAtMost(t.total), false)
        else if (first?.status == BgAgents.RUNNING && live.size == 1) b.setProgress(0, 0, true)
        if (live.size == 1 && first != null) {
            val stop = PendingIntent.getService(this, 7901, Intent(this, AgentKeeper::class.java).setAction(ACT_STOP).putExtra(EXTRA_ID, first.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, "Stop", stop)
        }
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_STOP) intent.getStringExtra(EXTRA_ID)?.let { id -> try { BgAgents.cancel(id) } catch (_: Throwable) { } }
        val now = live()
        try {
            val n = note(now.ifEmpty { listOf(BgAgents.Bg("-", "Finishing up", "", "", "", status = BgAgents.RUNNING)) })
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (loop?.isActive != true) loop = scope.launch {
            while (true) {
                val l = live()
                if (l.isEmpty()) { finish(); break }
                // Only re-post when the words or numbers changed, at most once a second.
                val key = l.joinToString("|") { "${it.id}:${it.status}:${it.waiting}:${it.done}/${it.total}:${it.steps}:${it.last}" }
                if (key != shown) { shown = key; try { getSystemService(NotificationManager::class.java).notify(ID, note(l)) } catch (_: Exception) { } }
                delay(1000)
            }
        }
        // Not sticky: after Argus is killed, BgAgents.init re-queues the runs and starts this service again.
        return START_NOT_STICKY
    }

    private fun finish() {
        try { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() { loop?.cancel(); super.onDestroy() }
}
