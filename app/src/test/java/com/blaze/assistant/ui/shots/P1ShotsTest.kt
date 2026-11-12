package com.blaze.assistant.ui.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * P1-QA-01 the P1 shots matrix on Robolectric (BLAZE_SHOTS=1 only, like every *ShotsTest): each P1 screen
 * ([ShotMatrix.SCREENS], drawn by [P1Screen] on [P1Fixtures]) in all 12 cells of [ShotMatrix.CELLS] — light / dark ×
 * Plain / Orbit / Cobalt night × font 100 / 200 % — to `build/shots/P1ShotsTest/<screen>/<cell>.png` (204 pictures).
 * The owner reviews them per phase (06 §9); a sheet's picture stacks its own window over the page.
 *
 * One test per screen, run once per cell by the parameterized runner, so a failure names both ("jobSheet[dark-orbit-200]").
 * The behaviour checks (TalkBack, 48 dp, callbacks) stay in each screen's own *ShotsTest; this class only draws.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-hdpi", application = android.app.Application::class)
class P1ShotsTest(private val cellName: String) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cells(): List<Array<Any>> = ShotMatrix.params()
    }

    // Checked before the compose rule starts its activity (and Robolectric's native graphics), as in ShotsTest.
    @get:Rule(order = 0) val onlyWithShots = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val cell = ShotMatrix.cell(cellName)

    private fun shoot(key: String) {
        val screen = ShotMatrix.SCREENS.first { it.key == key }
        rule.setContent { P1Screen(key, cell) }
        rule.mainClock.autoAdvance = false
        // a sheet slides up (ModalBottomSheet) and settles; pages only need their first frames
        rule.mainClock.advanceTimeBy(if (screen.sheet) 1_000 else 300); rule.waitForIdle()
        save(ShotMatrix.file(key, cell))
    }

    /** Draws the activity's window and every window over it (a sheet's) into one picture; captureToImage would wait on animations. */
    private fun save(rel: String) {
        val decor = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val at = IntArray(2)
        for (v in listOf(decor) + overWindows(decor)) {
            v.getLocationOnScreen(at)
            canvas.save(); canvas.translate(at[0].toFloat(), at[1].toFloat()); v.draw(canvas); canvas.restore()
        }
        val f = File(System.getProperty("blaze.shotsDir") ?: "build/shots", rel).apply { parentFile?.mkdirs() }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The other root views, oldest first (WindowManagerGlobal.mViews; empty if that ever moves). */
    private fun overWindows(decor: View): List<View> = try {
        val g = Class.forName("android.view.WindowManagerGlobal")
        val global = g.getMethod("getInstance").invoke(null)
        val f = g.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(global) as List<View>).toList().filter { it !== decor && it.isShown && it.width > 0 && it.height > 0 }
    } catch (_: Throwable) { emptyList() }

    @Test fun shell() = shoot("shell")
    @Test fun team() = shoot("team")
    @Test fun teamEmpty() = shoot("team-empty")
    @Test fun thread() = shoot("thread")
    @Test fun chat() = shoot("chat")
    @Test fun chatDrawer() = shoot("chat-drawer")
    @Test fun life() = shoot("life")
    @Test fun settingsYou() = shoot("settings-you")
    @Test fun settingsAppearance() = shoot("settings-appearance")
    @Test fun newBot() = shoot("new-bot")
    @Test fun botEditor() = shoot("bot-editor")
    @Test fun plan() = shoot("plan")
    @Test fun desk() = shoot("desk")
    @Test fun jobs() = shoot("jobs")
    @Test fun jobSheet() = shoot("job-sheet")
    @Test fun newJob() = shoot("new-job")
    @Test fun backupImport() = shoot("backup-import")

    /** Every screen in the matrix has its test above (one per key, by name). */
    @Test fun everyScreenIsShot() {
        val tests = javaClass.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) && it.name != "everyScreenIsShot" }.map { it.name }.toSet()
        val keys = ShotMatrix.SCREENS.map { s -> s.key.split('-').let { p -> p.first() + p.drop(1).joinToString("") { w -> w.replaceFirstChar(Char::uppercase) } } }.toSet()
        assertEquals(keys, tests)
    }
}
