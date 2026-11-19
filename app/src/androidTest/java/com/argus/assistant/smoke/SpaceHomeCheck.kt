package com.argus.assistant.smoke

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.argus.assistant.agent.ChatMeta
import com.argus.assistant.agent.Project
import com.argus.assistant.ui.DarkColors
import com.argus.assistant.ui.LightColors
import com.argus.assistant.ui.LocalArgus
import com.argus.assistant.ui.argus.ArgusColors
import com.argus.assistant.ui.argus.ArgusGround
import com.argus.assistant.ui.argus.LocalArgusColors
import com.argus.assistant.ui.argus.kit.ArgusTopBar
import com.argus.assistant.ui.space.SpaceProjectsScreen
import com.argus.assistant.ui.team.LifeNav
import com.argus.assistant.ui.team.LifePage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Local check (NOT part of the official low-RAM gate, which runs LowRamSmokeTest): the ARGUS shell live on the low-RAM
 * emulator with animations on, in dark and light. ARGUS-only (16 Nov 2026): the ARGUS ground and top bar with Life (the
 * Home) instead of the 0.52 space Home. Each round goes Life (idle) -> Life working -> Life with the sign-in and setup
 * cards -> Projects -> Life, under the same 50 ms main-thread monitor
 * and 700 ms limit as the other checks (the first round of each mode is a warm-up: classes, the space image decode and
 * the emulator's software-GL programs). A screenshot of every screen goes to the app's files (space/) for the reviewers.
 * Run by .github/workflows/home-focus-check.yml. Replaces ArgusHomeCheck (the 3D Home is gone).
 */
@RunWith(AndroidJUnit4::class)
class SpaceHomeCheck {
    private val tag = "BlazeSpaceHome"
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
        }, "space-stall-monitor").apply { isDaemon = true }.start()
    }

    private fun mark(label: String) {
        val w = worst.getAndSet(0)
        Log.i(tag, "SPACE $label: worst main stall $w ms")
        if (!label.startsWith("warm-up") && w > worstMeasured) { worstMeasured = w; worstAt = label }
    }

    private val dir by lazy { File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "space").apply { mkdirs() } }

    private fun shot(name: String) {
        val b: Bitmap? = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (b != null) File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
        Log.i(tag, "SHOT $name ${if (b != null) "saved" else "failed"}")
    }

    private data class Life(val signedIn: Boolean = true, val working: Boolean = false, val setupLeft: Int = 0, val setupPercent: Int = 100)
    private val calm = Life()
    private val home = mutableStateOf(calm)
    private val tab = mutableStateOf(LIFE)
    private val chats = listOf(ChatMeta("c1", "Store copy", 0L, false, ""), ChatMeta("c2", "Price list", 0L, false, ""))
    private val projects = listOf(Project("p1", "ARGUS mobile", chats = listOf("c1")), Project("p2", "Valera market", chats = listOf("c2")))

    private fun launch(dark: Boolean): ActivityScenario<ComponentActivity> {
        val sc = ActivityScenario.launch(ComponentActivity::class.java)
        sc.onActivity { a ->
            // the window like MainActivity's: edge to edge, adjustResize, a black ground behind the space view
            a.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            a.enableEdgeToEdge(
                statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
            a.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF000000.toInt()))
            a.setContent {
                val h by home; val t by tab
                CompositionLocalProvider(LocalArgus provides if (dark) DarkColors else LightColors, LocalArgusColors provides ArgusColors.of(dark)) {
                    ArgusGround {
                        Column(Modifier.statusBarsPadding()) {
                            ArgusTopBar(initial = "G", onMe = {}, onMeLong = {}, onSearch = {}, onBell = {}, bellBadge = false, onPlus = {})
                            if (t == PROJECTS) SpaceProjectsScreen(projects, chats, busy = setOf("c1"), needsYou = emptySet(), openId = "",
                                onOpen = {}, onBack = {}, onCreate = {}, onChat = {}, onNewChat = {}, onInstructions = { _, _ -> }, onDelete = {})
                            else LifePage(LifeNav(), projects = listOf("p1" to "ARGUS mobile"), signedIn = h.signedIn, working = h.working,
                                setupLeft = h.setupLeft, setupPercent = h.setupPercent)
                        }
                    }
                }
            }
        }
        return sc
    }

    private fun round(label: String, measured: Boolean, mode: String) {
        val p = if (measured) label else "warm-up $label"
        fun step(name: String, ms: Long = 1_200, change: () -> Unit) {
            main.post(change); SystemClock.sleep(ms); mark("$p $name"); if (measured) shot("$mode-$name")
        }
        step("life") { tab.value = LIFE; home.value = calm }
        step("life-working") { home.value = calm.copy(working = true) }
        step("life-signed-out-setup") { home.value = calm.copy(signedIn = false, setupLeft = 3, setupPercent = 40) }
        step("projects") { tab.value = PROJECTS }
        step("back-life", 1_000) { tab.value = LIFE; home.value = calm }
    }

    private fun mode(dark: Boolean) {
        val m = if (dark) "dark" else "light"
        val sc = launch(dark)
        SystemClock.sleep(2_500); mark("warm-up $m launch")
        round(m, measured = false, mode = m)
        repeat(2) { r -> round(if (r == 0) m else "$m #$r", measured = true, mode = m) }
        sc.close()
        SystemClock.sleep(500); mark("warm-up $m close")
    }

    private companion object { const val LIFE = "life"; const val PROJECTS = "projects" }

    @Test fun spaceShellThroughEveryScreenStaysUnder700ms() {
        monitor()
        try {
            mode(dark = true)
            mode(dark = false)
        } finally { running = false }
        Log.i(tag, "SPACE worst main stall $worstMeasured ms during $worstAt (limit 700; warm-up excluded, logged above)")
        assertTrue("the ARGUS shell stalled $worstMeasured ms during $worstAt", worstMeasured <= 700)
    }
}
