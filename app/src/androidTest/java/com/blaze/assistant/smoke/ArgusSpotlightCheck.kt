package com.blaze.assistant.smoke

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blaze.assistant.ui.argus.ArgusHomeScreen
import com.blaze.assistant.ui.argus.ArgusScene
import com.blaze.assistant.ui.composer.ComposerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.52.2 local check (NOT part of the official low-RAM gate): the ARGUS Home's pull-down Spotlight on the low-RAM emulator
 * with animations on, with real touches injected into the window. Three rounds of: pull the notch down (finger drag) ->
 * open, type a query, tap the scrim -> closed; tap the notch -> open, Back -> closed; pull down, then pull the panel's
 * handle back up -> closed. Same 50 ms main-thread monitor and 700 ms limit as the other checks (round 1 is a warm-up);
 * screenshots go to the app's files (argus-spotlight/). Run by .github/workflows/home-intro-check.yml (which ran the old
 * Home's HomeIntroCheck).
 */
@RunWith(AndroidJUnit4::class)
class ArgusSpotlightCheck {
    private val tag = "BlazeArgusSpot"
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
        }, "argus-spot-monitor").apply { isDaemon = true }.start()
    }

    private fun mark(label: String, measured: Boolean) {
        val w = worst.getAndSet(0)
        Log.i(tag, "SPOT ${if (measured) "" else "warm-up "}$label: worst main stall $w ms")
        if (measured && w > worstMeasured) { worstMeasured = w; worstAt = label }
    }

    private val dir by lazy { File(inst.targetContext.getExternalFilesDir(null), "argus-spotlight").apply { mkdirs() } }
    private fun shot(name: String) {
        val b: Bitmap? = inst.uiAutomation.takeScreenshot()
        if (b != null) File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
        Log.i(tag, "SHOT $name ${if (b != null) "saved" else "failed"}")
    }

    // ---- touches, in window pixels
    private var density = 1f
    private var topInset = 0
    private var w = 0; private var h = 0

    private fun touch(action: Int, x: Float, y: Float, down: Long) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { inst.sendPointerSync(e) } finally { e.recycle() }
    }

    private fun drag(x: Float, y0: Float, y1: Float, ms: Long = 260L, steps: Int = 14) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x, y0, down)
        for (i in 1..steps) { SystemClock.sleep(ms / steps); touch(MotionEvent.ACTION_MOVE, x, y0 + (y1 - y0) * i / steps, down) }
        touch(MotionEvent.ACTION_UP, x, y1, down)
    }

    private fun tap(x: Float, y: Float) { val down = SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN, x, y, down); SystemClock.sleep(60); touch(MotionEvent.ACTION_UP, x, y, down) }

    /** The notch: top centre, under the status bar, 10 dp down, 30 dp tall. */
    private fun notchY() = topInset + (10 + 15) * density

    private fun waitFor(what: String, ms: Long = 2_500, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { var ok = false; inst.runOnMainSync { ok = cond() }; if (ok) return true; SystemClock.sleep(50) }
        Log.i(tag, "SPOT timed out waiting for $what"); return false
    }

    private var scenario: ActivityScenario<ComponentActivity>? = null

    /** True while the soft keyboard is on screen (the window's IME insets). */
    private fun imeShown(): Boolean {
        var shown = false
        scenario?.onActivity { a -> shown = androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
        return shown
    }

    /**
     * Spotlight raises the keyboard; put it away so taps and Back reach the Home (the IME window is not ours to touch).
     * 0.52.2: waits until the IME insets say it is really gone (on the cold low-RAM emulator 400 ms was not always enough
     * and the first Back only closed the keyboard), then for the main thread to go idle.
     */
    private fun hideKeyboard() {
        scenario?.onActivity { a -> runCatching { a.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(a.window.decorView.windowToken, 0) } }
        val end = SystemClock.uptimeMillis() + 2_000
        SystemClock.sleep(150)
        while (imeShown() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
        if (imeShown()) Log.i(tag, "SPOT keyboard still up after 2 s")
        inst.waitForIdleSync()
        SystemClock.sleep(150)
    }

    /**
     * 0.52.2: one open / close gesture with up to [tries] attempts. Touch positions are computed from the window
     * (notch under the status bar, scrim at the bottom), and on a cold low-RAM emulator a gesture can land while the
     * panel is still animating or the keyboard is going away; a retry after the UI is idle is not a missed gesture.
     * Only a gesture that never takes effect counts as a failure.
     */
    private fun attempt(what: String, tries: Int = 3, before: () -> Unit = {}, act: () -> Unit, done: () -> Boolean): Boolean {
        for (i in 1..tries) {
            before(); act()
            if (waitFor(what) { done() }) { if (i > 1) Log.i(tag, "SPOT $what took effect on try $i"); return true }
            inst.waitForIdleSync(); SystemClock.sleep(300)
            var already = false; inst.runOnMainSync { already = done() }
            if (already) { Log.i(tag, "SPOT $what took effect late (try $i)"); return true }
        }
        Log.i(tag, "SPOT $what did not take effect after $tries tries"); return false
    }

    @Test fun pullOpenAndCloseThreeTimesUnder700ms() {
        val composer = ComposerState()
        val sc = ActivityScenario.launch(ComponentActivity::class.java)
        scenario = sc
        sc.onActivity { a ->
            a.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            a.enableEdgeToEdge(
                statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
            a.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF000000.toInt()))
            a.setContent {
                ArgusHomeScreen(ArgusScene.State.IDLE, 0f, "Gaby", composer, onSend = {}, onMic = {}, onOpenChat = {}, onProfile = {},
                    avatar = { _ -> Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF1286FF))) }, hour = 19)
            }
        }
        SystemClock.sleep(2_500)
        sc.onActivity { a ->
            density = a.resources.displayMetrics.density
            val v = a.window.decorView
            w = v.width; h = v.height
            topInset = v.rootWindowInsets?.systemWindowInsetTop ?: 0
        }
        Log.i(tag, "SPOT window ${w}x$h px, density $density, status bar $topInset px")
        monitor()
        var failures = 0
        try {
            for (round in 0 until 3) {
                val measured = round > 0
                val r = "round ${round + 1}"
                // 1. pull the notch down with a finger
                inst.waitForIdleSync()
                val opened = attempt("open after the pull", act = { drag(w / 2f, notchY(), notchY() + 420 * density) }) { composer.spotlightOpen }
                SystemClock.sleep(600); mark("$r pull open", measured); if (round == 1) shot("pulled-open")
                if (!opened) failures++
                main.post { composer.query = "set" }
                SystemClock.sleep(900); mark("$r type", measured); if (round == 1) shot("query")
                // tap the scrim near the bottom (the panel sits in the top two thirds)
                if (!attempt("closed by the scrim", before = { hideKeyboard() }, act = { tap(w / 2f, h * 0.90f) }) { !composer.spotlightOpen }) failures++
                SystemClock.sleep(600); mark("$r scrim close", measured)
                // 2. tap the notch, then Back
                if (!attempt("open after the tap", before = { inst.waitForIdleSync() }, act = { tap(w / 2f, notchY()) }) { composer.spotlightOpen }) failures++
                SystemClock.sleep(600); mark("$r tap open", measured); if (round == 1) shot("tapped-open")
                if (!attempt("closed by Back", before = { hideKeyboard() }, act = { inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }) { !composer.spotlightOpen }) failures++
                SystemClock.sleep(600); mark("$r back close", measured)
                // 3. pull down, then pull the panel back up by the field row (by its search icon, clear of the text field)
                if (!attempt("open for the pull-up", before = { inst.waitForIdleSync() }, act = { drag(w / 2f, notchY(), notchY() + 420 * density) }) { composer.spotlightOpen }) failures++
                SystemClock.sleep(600)
                val fieldY = topInset + (8 + 30) * density
                // ends above the window: the gesture stays ours
                if (!attempt("closed by the pull-up", before = { hideKeyboard() }, act = { drag((10 + 18 + 12) * density, fieldY, fieldY - 360 * density, ms = 180) }) { !composer.spotlightOpen }) failures++
                SystemClock.sleep(600); mark("$r pull-up close", measured); if (round == 1) shot("closed")
            }
        } finally { running = false; sc.close() }
        Log.i(tag, "SPOT worst main stall $worstMeasured ms during $worstAt (limit 700; round 1 is the warm-up); $failures gesture(s) missed")
        assertFalse("Spotlight left open", composer.spotlightOpen)
        assertTrue("$failures open / close gesture(s) did not take effect (see logcat BlazeArgusSpot)", failures == 0)
        assertTrue("Spotlight stalled $worstMeasured ms during $worstAt", worstMeasured <= 700)
    }
}
