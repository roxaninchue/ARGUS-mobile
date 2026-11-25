package com.blaze.assistant.connect

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.blaze.assistant.BrandName
import com.blaze.assistant.MainActivity
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.connect.AccountLock.Reason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Account lock on the phone ([AccountLock] has the rules). One answer, [locked], read by every way into ARGUS:
 * MainActivity shows the lock screen (before the first-run language step), the other activities send you there, the
 * assist panel shows only a Sign in card, the recognition service refuses, the wake word stays off, the widget and the
 * quick tile open the lock screen, the floating orb hides, nothing posts notifications (only Stop and Don't still work),
 * Android Auto is turned away and playback pauses, routines and triggers are skipped. When something is refused while
 * ARGUS is closed, one quiet "Sign in to ARGUS" notification is the only sign of it ([notice]).
 *
 * State (plain prefs, never synced): acct_last_ok (last yes from blaze-auth, ms), acct_rejected (a refusal not cleared
 * yet), acct_verify_on (the server asks for confirmed emails), acct_upgraded (the upgrade grace was given).
 */
object AccountGate {
    private const val LAST_OK = "acct_last_ok"
    private const val REJECTED = "acct_rejected"
    private const val VERIFY_ON = "acct_verify_on"
    private const val UPGRADED = "acct_upgraded"
    private const val FIRST_RUN = "acct_first_run_signin"
    private const val CHANNEL = "argus_signin"
    private const val NOTICE_ID = 31
    /** Extra on the lock screen's intent (MainActivity just opens; the lock screen is what it shows while locked). */
    const val EXTRA_LOCK = "account_lock"

    private val _lock = MutableStateFlow<Reason?>(null)
    /** Why ARGUS is locked, or null. MainActivity draws the lock screen from it. */
    val lock: StateFlow<Reason?> = _lock
    @Volatile private var app: Context? = null
    @Volatile private var noticeShown = false
    @Volatile private var checkingAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** BlazeApp, right after BlazeAccount.start: the upgrade grace, then the first answer. */
    fun start(ctx: Context) {
        app = ctx.applicationContext
        runCatching {
            if (!Settings.bool(UPGRADED, false)) {
                val u = BlazeAccount.user()
                val v = AccountLock.upgradeLastOk(lastOk(), u != null, u?.emailVerified == true, System.currentTimeMillis())
                if (v > 0L) Settings.setStr(LAST_OK, v.toString())
                Settings.setBool(UPGRADED, true)
            }
        }
        recompute()
    }

    private fun lastOk(): Long = runCatching { Settings.str(LAST_OK).toLong() }.getOrDefault(0L)
    private fun rejected(): Reason? = runCatching { Reason.valueOf(Settings.str(REJECTED)) }.getOrNull()

    /** The current state, from the account and the saved marks. */
    fun state(): AccountLock.State {
        val u = BlazeAccount.user()
        return AccountLock.State(u != null, u?.emailVerified == true, u?.email.orEmpty(), lastOk(), rejected(),
            runCatching { Settings.bool(VERIFY_ON, false) }.getOrDefault(false))
    }

    /** Works the answer out again (the grace can run out while ARGUS is open) and publishes it. */
    fun recompute(): Reason? {
        val was = _lock.value
        val now = try { AccountLock.decide(state(), System.currentTimeMillis()) } catch (_: Throwable) { return was }
        _lock.value = now
        if (was == null && now != null) onLocked()
        if (was != null && now == null) onUnlocked()
        return now
    }

    /** True when the first run's sign-in happened on the lock screen and onboarding hasn't finished yet. */
    fun firstRun(): Boolean = runCatching { Settings.bool(FIRST_RUN, false) && !Settings.bool("onboarded", false) }.getOrDefault(false)

    /** True while ARGUS is locked. Cheap: prefs and the cached user. */
    fun locked(): Boolean = recompute() != null

    /** Every signed-in call to blaze-auth or the gateway reports its status here (only 401 and 403 can lock). */
    fun report(status: Int, body: String?) {
        if (status != 401 && status != 403) return
        val r = AccountLock.fromResponse(status, body) ?: return
        runCatching { Settings.setStr(REJECTED, r.name) }
        recompute()
        // A 401 may be a stale gateway cache or a signed-out session: /v1/me decides (at most once a minute).
        if (r == Reason.SESSION_REJECTED && System.currentTimeMillis() - checkingAt > 60_000L) {
            checkingAt = System.currentTimeMillis()
            scope.launch { runCatching { BlazeAccount.checkSession() } }
        }
    }

    /** blaze-auth said yes (a sign-in, or /v1/me answered with the user): the grace restarts and refusals clear. */
    fun meOk() {
        runCatching {
            // Signed in on the lock screen of a first run: onboarding (language, setup) still follows.
            if (_lock.value != null && !Settings.bool("onboarded", false)) Settings.setBool(FIRST_RUN, true)
            Settings.setStr(LAST_OK, System.currentTimeMillis().toString()); Settings.setStr(REJECTED, "")
        }
        recompute()
    }

    /** blaze-auth refused the account itself (account_locked / disposable_email): kept after the local sign-out. */
    fun refused(r: Reason) { runCatching { Settings.setStr(REJECTED, r.name) }; recompute() }

    /** /v1/providers: blaze-auth 0.10.0+ asks for confirmed emails. Once on, it stays on. */
    fun verifyOn(on: Boolean) { if (on) runCatching { if (!Settings.bool(VERIFY_ON, false)) Settings.setBool(VERIFY_ON, true) }; recompute() }

    // ---------------- side doors ----------------

    /** MainActivity, which shows the lock screen while locked. */
    fun lockIntent(ctx: Context): Intent = Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_LOCK, true)

    fun lockPending(ctx: Context, code: Int = 7131): PendingIntent =
        PendingIntent.getActivity(ctx, code, lockIntent(ctx), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** For an activity's onCreate: while locked it opens the lock screen instead and finishes. True = it did. */
    fun bounce(a: Activity): Boolean {
        if (!locked()) return false
        runCatching { a.startActivity(lockIntent(a)) }
        a.finish()
        return true
    }

    /**
     * Something was refused while ARGUS is closed: one quiet "Sign in to ARGUS" notification (once per lock; nothing
     * when ARGUS is on screen, which already shows the lock screen).
     */
    fun notice(ctx: Context? = app) {
        val c = ctx?.applicationContext ?: return
        if (noticeShown || !locked() || com.blaze.assistant.agent.Agent.inForeground) return
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        noticeShown = true
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sign-in", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "When ${BrandName.UPPER} needs you to sign in before it can do anything" })
            val n = androidx.core.app.NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(com.blaze.assistant.R.drawable.ic_stat_blaze)
                .setContentTitle(AccountLock.TITLE)
                .setContentText(_lock.value?.let { AccountLock.message(it) } ?: AccountLock.message(Reason.SIGNED_OUT))
                .setSilent(true).setOnlyAlertOnce(true).setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setContentIntent(lockPending(c)).build()
            nm.notify(NOTICE_ID, n)
        } catch (_: Throwable) { noticeShown = false }
    }

    /** Just locked: whatever ARGUS has showing or running outside its screen stops. */
    private fun onLocked() {
        val c = app ?: return
        noticeShown = false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { com.blaze.assistant.voice.WakeWordService.stop(c) }
            runCatching { com.blaze.assistant.mediaapps.MusicService.lockPause() }
            runCatching { com.blaze.assistant.notify.Notifier.clearReplies(); com.blaze.assistant.notify.Notifier.clearAction() }
        }
    }

    /** Open again: the sign-in notice goes. */
    private fun onUnlocked() {
        val c = app ?: return
        noticeShown = false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID) }
        }
    }
}
