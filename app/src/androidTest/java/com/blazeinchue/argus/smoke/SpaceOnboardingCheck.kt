package com.blazeinchue.argus.smoke

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blazeinchue.argus.setup.SetupPlan
import com.blazeinchue.argus.setup.SetupPlan.Phase as S
import com.blazeinchue.argus.ui.space.SpaceOnboarding
import com.blazeinchue.argus.ui.space.SpaceOnboardingScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.52.3 local check (NOT part of the official low-RAM gate): the new first run on the low-RAM emulator with animations on
 * and real touches injected into the window. Three rounds of: the welcome (logo + "Intelligence beyond the interface"),
 * a finger tap on Get started -> the one setup screen, every item moving through waiting / downloading / ready (the states
 * SetupRunner publishes; nothing is really downloaded here) with the overall bar and its percentage, then a tap on the
 * Continue pill -> onboarding over. Same 50 ms main-thread monitor and 700 ms limit as the other checks (round 1 is a
 * warm-up: classes, the logo and the space image decode); screenshots go to the app's files (space-onboarding/). A tap
 * that lands while the screen is still fading in is retried (BUILDING.md: a device check never hangs on one gesture).
 * Run by .github/workflows/home-intro-check.yml (tools/smoke/run-space-onboarding.sh). Replaces ArgusSpotlightCheck.
 */
@RunWith(AndroidJUnit4::class)
class SpaceOnboardingCheck {
    private val tag = "ArgusSpaceOnboarding"
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val main = Handler(Looper.getMainLooper())
    private val worst = AtomicLong(0)
    @Volatile private var running = false
    private var worstMeasured = 0L; private var worstAt = ""

    private fun monitor() {
        running = true
        Thread({
            while (running) {
                val sent = SystemClock.uptimeMillis(); val answered = AtomicLong(0)
                main.post { answered.set(SystemClock.uptimeMillis()) }
                while (running && answered.get() == 0L) { SystemClock.sleep(50); val lag = SystemClock.uptimeMillis() - sent; if (lag > worst.get()) worst.set(lag) }
                SystemClock.sleep(50)
            }
        }, "space-onboarding-monitor").apply { isDaemon = true }.start()
    }

    private fun mark(label: String, measured: Boolean) {
        val w = worst.getAndSet(0)
        Log.i(tag, "ONBOARD ${if (measured) "" else "warm-up "}$label: worst main stall $w ms")
        if (measured && w > worstMeasured) { worstMeasured = w; worstAt = label }
    }

    private val dir by lazy { File(inst.targetContext.getExternalFilesDir(null), "space-onboarding").apply { mkdirs() } }
    private fun shot(name: String) {
        val b: Bitmap? = inst.uiAutomation.takeScreenshot()
        if (b != null) File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
        Log.i(tag, "SHOT $name ${if (b != null) "saved" else "failed"}")
    }

    // ---- touches, in window pixels
    private var density = 1f
    private var bottomInset = 0
    private var w = 0; private var h = 0

    private fun touch(action: Int, x: Float, y: Float, down: Long) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { inst.sendPointerSync(e) } finally { e.recycle() }
    }

    private fun tap(x: Float, y: Float) { val down = SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN, x, y, down); SystemClock.sleep(60); touch(MotionEvent.ACTION_UP, x, y, down) }

    /** The bottom pill's centre: [padDp] above the navigation bar, 56 dp tall (Get started: 24 dp; Continue: 18 dp). */
    private fun pillY(padDp: Int) = h - bottomInset - (padDp + 28) * density

    private fun waitFor(what: String, ms: Long = 2_500, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { var ok = false; inst.runOnMainSync { ok = cond() }; if (ok) return true; SystemClock.sleep(50) }
        Log.i(tag, "ONBOARD timed out waiting for $what"); return false
    }

    private fun attempt(what: String, tries: Int = 3, act: () -> Unit, done: () -> Boolean): Boolean {
        for (i in 1..tries) {
            inst.waitForIdleSync(); act()
            if (waitFor(what) { done() }) { if (i > 1) Log.i(tag, "ONBOARD $what took effect on try $i"); return true }
            SystemClock.sleep(300)
        }
        Log.i(tag, "ONBOARD $what did not take effect after $tries tries"); return false
    }

    private val step = mutableStateOf(SpaceOnboarding.Step.WELCOME)
    private val setup = mutableStateOf<Map<String, SetupPlan.Status>>(emptyMap())
    @Volatile private var finished = 0

    /** The setup screen's states, as SetupRunner publishes them: Linux first, the Wi-Fi items waiting, then everything ready. */
    private fun frames(): List<Map<String, SetupPlan.Status>> {
        fun s(p: SetupPlan.Phase, pr: Int = -1) = SetupPlan.Status(p, pr)
        val waiting = SetupPlan.ITEMS.associate { it.id to if (it.wifi) s(S.WAITING_WIFI) else s(S.PENDING) }
        return listOf(waiting,
            waiting + ("linux" to s(S.RUNNING, 30)) + ("skills" to s(S.RUNNING)),
            waiting + ("linux" to s(S.DONE)) + ("skills" to s(S.DONE)) + ("tools" to s(S.RUNNING, 45)),
            waiting + ("linux" to s(S.DONE)) + ("skills" to s(S.DONE)) + ("tools" to s(S.DONE)) + ("brain" to s(S.RUNNING, 60)) + ("voice" to s(S.RUNNING, 20)) + ("quick" to s(S.FAILED)),
            SetupPlan.ITEMS.associate { it.id to s(S.DONE) } + ("quick" to SetupPlan.Status(S.SKIPPED, note = "Not available on this phone")))
    }

    @Test fun welcomeThenOneSetupScreenThreeTimesUnder700ms() {
        val sc = ActivityScenario.launch(ComponentActivity::class.java)
        sc.onActivity { a ->
            a.enableEdgeToEdge(
                statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
            a.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF000000.toInt()))
            a.setContent {
                val s by step; val st by setup
                SpaceOnboardingScreen(s, signedIn = true, setup = st,
                    onNext = { val nx = SpaceOnboarding.next(step.value, signedIn = true); if (nx != null) step.value = nx else finished++ },
                    onRetry = {}, accountCard = {})
            }
        }
        SystemClock.sleep(2_500)
        sc.onActivity { a ->
            density = a.resources.displayMetrics.density
            val v = a.window.decorView
            w = v.width; h = v.height
            bottomInset = v.rootWindowInsets?.systemWindowInsetBottom ?: 0
        }
        Log.i(tag, "ONBOARD window ${w}x$h px, density $density, nav bar $bottomInset px")
        monitor()
        var failures = 0
        try {
            for (round in 0 until 3) {
                val measured = round > 0
                val r = "round ${round + 1}"
                main.post { step.value = SpaceOnboarding.Step.WELCOME; setup.value = emptyMap() }
                SystemClock.sleep(1_200); mark("$r welcome", measured); if (round == 1) shot("welcome")
                if (!attempt("setup after Get started", act = { tap(w / 2f, pillY(24)) }) { step.value == SpaceOnboarding.Step.SETUP }) failures++
                SystemClock.sleep(800); mark("$r into setup", measured)
                frames().forEachIndexed { i, f ->
                    main.post { setup.value = f }
                    SystemClock.sleep(700); mark("$r setup ${SetupPlan.percent(f)}%", measured); if (round == 1) shot("setup-$i")
                }
                val before = finished
                if (!attempt("done after Continue", act = { tap(w / 2f, pillY(18)) }) { finished > before }) failures++
                SystemClock.sleep(400); mark("$r continue", measured)
            }
        } finally { running = false; sc.close() }
        Log.i(tag, "ONBOARD worst main stall $worstMeasured ms during $worstAt (limit 700; round 1 is the warm-up); $failures tap(s) missed")
        assertEquals("every round finished onboarding", 3, finished)
        assertTrue("$failures tap(s) did not take effect (see logcat $tag)", failures == 0)
        assertTrue("onboarding stalled $worstMeasured ms during $worstAt", worstMeasured <= 700)
    }
}
