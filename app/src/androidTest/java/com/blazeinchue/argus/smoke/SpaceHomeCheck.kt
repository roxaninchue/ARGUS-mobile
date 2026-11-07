package com.blazeinchue.argus.smoke

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
import com.blazeinchue.argus.agent.ChatMeta
import com.blazeinchue.argus.agent.Project
import com.blazeinchue.argus.ui.DarkColors
import com.blazeinchue.argus.ui.LightColors
import com.blazeinchue.argus.ui.LocalArgus
import com.blazeinchue.argus.ui.space.SpaceAnswer
import com.blazeinchue.argus.ui.space.SpaceBackdrop
import com.blazeinchue.argus.ui.space.SpaceHomeScreen
import com.blazeinchue.argus.ui.space.SpaceHomeState
import com.blazeinchue.argus.ui.space.SpaceProfileScreen
import com.blazeinchue.argus.ui.space.SpaceProjectsScreen
import com.blazeinchue.argus.ui.space.SpaceTabs
import com.blazeinchue.argus.ui.space.SpaceTopBar
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.52.3 local check (NOT part of the official low-RAM gate, which runs LowRamSmokeTest): the ARGUS x Hark shell live on
 * the low-RAM emulator with animations on, in dark (the space view, decoded at the screen's size) and light. Each round
 * goes Home (idle) -> Home working with the answer card -> Home with the sign-in and setup cards -> the profile page ->
 * Projects -> Home, switching through the top bar's state like the shell does, under the same 50 ms main-thread monitor
 * and 700 ms limit as the other checks (the first round of each mode is a warm-up: classes, the space image decode and
 * the emulator's software-GL programs). A screenshot of every screen goes to the app's files (space/) for the reviewers.
 * Run by .github/workflows/home-focus-check.yml. Replaces ArgusHomeCheck (the 3D Home is gone).
 */
@RunWith(AndroidJUnit4::class)
class SpaceHomeCheck {
    private val tag = "ArgusSpaceHome"
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

    private val calm = SpaceHomeState(name = "Gaby", hour = 19, signedIn = true, projects = listOf("p1" to "ARGUS mobile"))
    private val home = mutableStateOf(calm)
    private val tab = mutableStateOf(SpaceTabs.HOME)
    private val profile = mutableStateOf(false)
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
                val h by home; val t by tab; val p by profile
                CompositionLocalProvider(LocalArgus provides if (dark) DarkColors else LightColors) {
                    SpaceBackdrop {
                        Column(Modifier.statusBarsPadding()) {
                            SpaceTopBar(tab = if (p) "" else t, onTab = { tab.value = it; profile.value = false }, onLeft = {},
                                initial = SpaceTabs.initial("Gaby", null), onAvatar = { profile.value = true }, showLeft = !p)
                            when {
                                p -> SpaceProfileScreen("Gaby Alarcon", "gaby@example.com", signedIn = true, planLine = "Pro plan",
                                    onPage = {}, onSignOut = {}, onSearch = {}, version = "0.52.3")
                                t == SpaceTabs.PROJECTS -> SpaceProjectsScreen(projects, chats, busy = setOf("c1"), needsYou = emptySet(), openId = "",
                                    onOpen = {}, onBack = {}, onCreate = {}, onChat = {}, onNewChat = {}, onInstructions = { _, _ -> }, onDelete = {})
                                else -> SpaceHomeScreen(h, onAsk = {}, onOpenChat = {}, onOpenProject = {}, onOpenProjects = {}, onSignIn = {}, onSetup = {})
                            }
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
        step("home") { profile.value = false; tab.value = SpaceTabs.HOME; home.value = calm }
        step("home-working") { home.value = calm.copy(working = true, answer = SpaceAnswer.Card("What's on today?", "", true)) }
        step("home-answer") { home.value = calm.copy(answer = SpaceAnswer.Card("What's on today?", "Two meetings this afternoon and a parcel before six.", false)) }
        step("home-signed-out-setup") { home.value = calm.copy(signedIn = false, setupLeft = 3, setupPercent = 40) }
        step("profile") { profile.value = true }
        step("projects") { profile.value = false; tab.value = SpaceTabs.PROJECTS }
        step("back-home", 1_000) { tab.value = SpaceTabs.HOME; home.value = calm }
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
