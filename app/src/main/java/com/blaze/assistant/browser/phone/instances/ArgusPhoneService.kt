package com.blaze.assistant.browser.phone.instances

import android.app.Notification
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.blaze.assistant.BrandName
import com.blaze.assistant.R
import com.blaze.assistant.browser.phone.PhoneBrowser
import com.blaze.assistant.browser.phone.PhoneBrowserActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps Argus Phone running in the background until the user shuts it down (docs/ARGUS-PHONE.md 5d). A foreground
 * service with one notification: "Argus Phone · N instances running" and what Argus is doing, or "Waiting on you".
 * Tapping it opens Argus Phone on the instance watched last; expanded, it offers Shut down (checkpoint everything,
 * close, stop) and Close all instances (checkpoint and close them, stay on). It holds no surface and draws nothing:
 * the instances follow the cold / warm / live budget, and the WebView is still freed after PhonePolicy.IDLE_MS.
 *
 * Service type: `specialUse` (Android 14+), with the PROPERTY_SPECIAL_USE_FGS_SUBTYPE explanation in the manifest. Not
 * `dataSync`: it is capped at 6 hours a day from Android 15 and this service syncs nothing; not `mediaPlayback`: it
 * plays nothing. Not sticky: after Android ends the process, Argus Phone comes back from its checkpoints the next time
 * it is opened or a task needs it (open question 5 in docs/ARGUS-PHONE.md).
 */
class ArgusPhoneService : Service() {
    companion object {
        private const val ID = 4710
        private const val CH = "argus_phone"
        const val ACT_SHUT_DOWN = "argus.phone.SHUT_DOWN"
        const val ACT_CLOSE_ALL = "argus.phone.CLOSE_ALL"
        @Volatile var running = false
            private set
        private val tick = MutableStateFlow(0L)

        /** Starts it. Null when it runs; otherwise why not (Android 12+ refuses a foreground start from the background). */
        fun start(ctx: Context): String? = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ArgusPhoneService::class.java)); null
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 31 && e is android.app.ForegroundServiceStartNotAllowedException) "Android doesn't let it start while ${BrandName.NAME} is in the background"
            else (e.javaClass.simpleName + ": " + e.message.orEmpty()).take(120)
        }

        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, ArgusPhoneService::class.java)) } catch (_: Exception) { } }

        /** Something the notification shows changed (it redraws at most once a second). */
        fun refresh() { tick.value = tick.value + 1 }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(i: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        running = true
        foreground()
        scope.launch {
            combine(tick, PhoneBrowser.live) { _, l -> l.step }.sample(1000).collect { step ->
                try { getSystemService(NotificationManager::class.java).notify(ID, note(step)) } catch (_: Exception) { }
            }
        }
    }

    private fun foreground() {
        try {
            ServiceCompat.startForeground(this, ID, note(PhoneBrowser.live.value.step),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("ArgusPhoneService.start", e); stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_SHUT_DOWN -> ArgusPhone.shutDown(this)
            ACT_CLOSE_ALL -> ArgusPhone.closeAll(this)
        }
        return START_NOT_STICKY
    }

    private fun pending(action: String, req: Int): PendingIntent = PendingIntent.getService(this, req,
        Intent(this, ArgusPhoneService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun note(step: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "${BrandName.NAME} Phone", NotificationManager.IMPORTANCE_LOW).apply {
            description = "${BrandName.NAME} Phone running in the background: its instances and what ${BrandName.NAME} is doing"; setShowBadge(false) })
        val c = ArgusPhone.core(this)
        val n = c.registry.size()
        val title = "${BrandName.NAME} Phone \u00b7 $n instance${if (n == 1) "" else "s"} running"
        // Account lock: no instance names or steps on the lock screen's shade.
        val locked = try { com.blaze.assistant.connect.AccountGate.locked() } catch (_: Throwable) { false }
        val text = if (locked) "Running" else c.doing(step)
        val watched = c.registry.watched ?: c.registry.active
        val open = PendingIntent.getActivity(this, 4711, PhoneBrowserActivity.instanceIntent(this, watched),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_argus)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Shut down", pending(ACT_SHUT_DOWN, 4712))
            .addAction(0, "Close all instances", pending(ACT_CLOSE_ALL, 4713))
            .build()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }
}
