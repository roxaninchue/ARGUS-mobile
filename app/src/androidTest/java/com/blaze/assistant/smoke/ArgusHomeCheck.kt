package com.blaze.assistant.smoke

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blaze.assistant.ui.argus.ArgusAnswer
import com.blaze.assistant.ui.argus.ArgusDevice
import com.blaze.assistant.ui.argus.ArgusFps
import com.blaze.assistant.ui.argus.ArgusHomeScreen
import com.blaze.assistant.ui.argus.ArgusScene
import com.blaze.assistant.ui.composer.ComposerState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.52.2 local check (NOT part of the official low-RAM gate, which runs LowRamSmokeTest): the ARGUS living 3D Home live on
 * the low-RAM emulator with animations on. For each renderer (GL forced, Canvas forced, and what the device picks itself,
 * Canvas on this 1.5 GB emulator) the orb goes through idle -> listening (the mic level moving) -> thinking -> speaking ->
 * idle three times, the result card comes and goes, all under the same 50 ms main-thread monitor and the same 700 ms limit
 * as the other checks (the first pass of each renderer is a warm-up: classes and the emulator's software-GL shaders).
 * A screenshot of every state goes to the app's files (argus/) for the reviewers. Run by .github/workflows/home-focus-check.yml.
 * Replaces HomeFocusCheck (the old Home's card focus) and Rc4ShotsCheck (the old Home's screenshots).
 */
@RunWith(AndroidJUnit4::class)
class ArgusHomeCheck {
    private val tag = "BlazeArgusHome"
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
                while (running && answered.get() == 0L) {
                    SystemClock.sleep(50)
                    val lag = SystemClock.uptimeMillis() - sent
                    if (lag > worst.get()) worst.set(lag)
                }
                SystemClock.sleep(50)
            }
        }, "argus-stall-monitor").apply { isDaemon = true }.start()
    }

    /** The worst stall since the previous call, logged for [label]; warm-up phases are logged but not held to the limit. */
    private fun mark(label: String) {
        val w = worst.getAndSet(0)
        Log.i(tag, "ARGUS $label: worst main stall $w ms")
        if (!label.startsWith("warm-up") && w > worstMeasured) { worstMeasured = w; worstAt = label }
    }

    private val dir by lazy { File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "argus").apply { mkdirs() } }

    private fun shot(name: String) {
        val b: Bitmap? = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (b != null) File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
        Log.i(tag, "SHOT $name ${if (b != null) "saved" else "failed"}")
    }

    private val state = mutableStateOf(ArgusScene.State.IDLE)
    private val level = mutableFloatStateOf(0f)
    private val answer = mutableStateOf<ArgusAnswer.Card?>(null)

    private fun launch(mode: ArgusFps.Mode?): ActivityScenario<ComponentActivity> {
        val composer = ComposerState()
        val sc = ActivityScenario.launch(ComponentActivity::class.java)
        sc.onActivity { a ->
            // the window like MainActivity's: edge to edge, adjustResize, a black ground behind the scene
            a.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            a.enableEdgeToEdge(
                statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
            a.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF000000.toInt()))
            a.setContent {
                val s by state; val l by level; val c by answer
                ArgusHomeScreen(s, l, "Gaby", composer, onSend = {}, onMic = {}, onOpenChat = {}, onProfile = {},
                    answer = c, avatar = { click -> Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF1286FF)).clickable { click() }) },
                    forceMode = mode, hour = 19)
            }
        }
        return sc
    }

    /** One pass through every state (and the result card); [measured] = held to the limit and screenshotted. */
    private fun pass(label: String, measured: Boolean) {
        val p = if (measured) label else "warm-up $label"
        main.post { state.value = ArgusScene.State.IDLE; level.floatValue = 0f; answer.value = null }
        SystemClock.sleep(1_200); mark("$p idle"); if (measured) shot("$label-idle")
        main.post { state.value = ArgusScene.State.LISTENING }
        // the mic level moves like speech, 20 updates a second
        for (i in 0 until 30) { val v = ((i * 37) % 100) / 100f; main.post { level.floatValue = v }; SystemClock.sleep(50) }
        mark("$p listening"); if (measured) shot("$label-listening")
        main.post { state.value = ArgusScene.State.THINKING; level.floatValue = 0f; answer.value = ArgusAnswer.Card("What's the latest tech news?", "", true) }
        SystemClock.sleep(1_200); mark("$p thinking"); if (measured) shot("$label-thinking")
        main.post { state.value = ArgusScene.State.SPEAKING
            answer.value = ArgusAnswer.Card("What's the latest tech news?", "Here are the top headlines for you: a faster way to compile, a self-driving excavator, and a new handheld.", false) }
        SystemClock.sleep(1_200); mark("$p speaking"); if (measured) shot("$label-speaking-answer")
        main.post { state.value = ArgusScene.State.IDLE; answer.value = null }
        SystemClock.sleep(1_000); mark("$p back to idle")
    }

    private fun renderer(label: String, mode: ArgusFps.Mode?) {
        val sc = launch(mode)
        SystemClock.sleep(2_500); mark("warm-up $label launch")
        pass(label, measured = false)
        repeat(3) { round -> pass(if (round == 0) label else "$label #$round", measured = round == 0 || round == 2) }
        sc.close()
        SystemClock.sleep(500); mark("warm-up $label close")
    }

    @Test fun argusHomeLiveThroughEveryStateStaysUnder700ms() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val d = ArgusDevice.read(ctx)
        Log.i(tag, "ARGUS device: gles3=${d.gles3} ram=${d.totalMb} MB lowRamFlag=${d.lowRamFlag} -> auto mode " +
            "${ArgusFps.mode(d.gles3, d.totalMb, d.lowRamFlag, true)} at ${ArgusFps.cap(d.totalMb, d.lowRamFlag, false)} fps")
        monitor()
        try {
            // GL is forced so the shaders and the frame cap run here even though this emulator would pick the Canvas orb;
            // a GL failure falls back to Canvas by itself (logged by ArgusGl), which this check also counts as a pass.
            renderer("gl", ArgusFps.Mode.GL)
            renderer("canvas", ArgusFps.Mode.CANVAS)
            renderer("auto", null)
            // reduce motion: one still frame, nothing animates
            val sc = launch(ArgusFps.Mode.STATIC); SystemClock.sleep(2_000); mark("warm-up static launch")
            SystemClock.sleep(1_500); mark("static"); shot("static"); sc.close()
        } finally { running = false }
        Log.i(tag, "ARGUS worst main stall $worstMeasured ms during $worstAt (limit 700; warm-up excluded, logged above)")
        assertTrue("the ARGUS Home stalled $worstMeasured ms during $worstAt", worstMeasured <= 700)
    }
}
