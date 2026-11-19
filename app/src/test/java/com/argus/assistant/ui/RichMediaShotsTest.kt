package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.BgAgents
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.52.0 screenshots (BLAZE_SHOTS=1): the new reply blocks (song gallery with artwork, poster grid, link cards, document
 * card, collapsible section, a pie chart) and the work pills (a turn's work pill, background agent pills live and
 * done). Pictures come from a local stand-in loader (no network in tests).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h780dp-xhdpi", application = android.app.Application::class)
class RichMediaShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @org.junit.Before fun initSettings() { try { com.blaze.assistant.agent.Settings.init(androidx.test.core.app.ApplicationProvider.getApplicationContext()) } catch (_: Throwable) { } }

    private fun capture(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "RichMediaShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }
    private fun distinct(b: Bitmap): Int { val s = HashSet<Int>(); var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { s += b.getPixel(x, y); x += 7 }; y += 7 }; return s.size }

    /** A flat colour square per url, so artwork shows without the network. */
    private val fakeArt: (String) -> ImageBitmap? = { url ->
        val colors = intArrayOf(0xFFE4572E.toInt(), 0xFF29335C.toInt(), 0xFFF3A712.toInt(), 0xFF669BBC.toInt(), 0xFF2EC4B6.toInt())
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(colors[(url.hashCode() and 0x7fffffff) % colors.size]) }.asImageBitmap()
    }

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalBlaze provides DarkColors, RichImages.Local provides fakeArt) {
                Column(Modifier.fillMaxSize().background(Color(0xFF0E1424)).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
            }
        }
        rule.waitForIdle()
        assertTrue(name, distinct(capture(name)) > 12)
    }

    @Test fun galleryAndCards() = shoot("rich-gallery-links") {
        Text("Here are this year's standout amapiano tracks.", color = Color.White)
        RichGallery(RichMedia.gallery("""{"title":"Amapiano 2025","items":[
            {"title":"Tshwala Bam","subtitle":"TitoM & Yuppe · 2024","image":"https://img/1.jpg","url":"https://s/1","note":"Still everywhere"},
            {"title":"Mnike","subtitle":"Tyler ICU · 2023","image":"https://img/2.jpg","url":"https://s/2"},
            {"title":"Imithandazo","subtitle":"Kabza De Small · 2025","image":"https://img/3.jpg","url":"https://s/3","note":"Rising"},
            {"title":"Untitled demo","subtitle":"Unknown","image":"","url":""}]}""")!!)
        RichLinks(RichMedia.links("""{"title":"Read more","items":[{"title":"How amapiano took over","url":"https://www.bbc.com/a","summary":"The log drum, explained."},{"title":"Charts this week","url":"https://open.spotify.com/x","image":"https://img/4.jpg"}]}""")!!)
    }

    @Test fun posterGridDocAndDetails() = shoot("rich-grid-doc-details") {
        RichGallery(RichMedia.gallery("""{"title":"Films to watch","items":[{"title":"Film one","image":"https://p/1.jpg"},{"title":"Film two","image":"https://p/2.jpg"},{"title":"Film three","image":"https://p/3.jpg"}]}""")!!)
        RichDoc(RichMedia.doc("""{"title":"Solar mini-grids in Zambia","path":"/storage/emulated/0/Download/Blaze/solar.md","format":"md","summary":"6 sections · 1,240 words"}""")!!)
        RichDetails(RichMedia.details("""{"title":"How I checked the claims","body":"Two claims verified, one disputed."}""")!!) { Text(it, color = Color.White) }
        RichChart(RichKit.chart("""{"type":"pie","title":"Energy mix","labels":["Hydro","Solar","Diesel"],"series":[{"name":"Share","values":[80,12,8]}],"unit":"%"}""")!!)
    }

    @Test fun workAndAgentPills() {
        val now = System.currentTimeMillis()
        BgAgents.preview(listOf(
            BgAgents.Bg("B7", "Compare solar kits", "t", "c", "bg-x", status = BgAgents.RUNNING, created = now - 95_000, started = now - 95_000, steps = 6,
                last = "Searching: solar kits under K5,000", specialist = "researcher", log = listOf("Searching: solar kits", "Reading 3 pages", "Searching: solar kits under K5,000")),
            BgAgents.Bg("B8", "Amapiano 2025 list", "t", "c", "bg-y", status = BgAgents.DONE, created = now - 215_000, started = now - 215_000, ended = now, steps = 57,
                specialist = com.blaze.assistant.orchestra.OrchestraRuns.SPECIALIST)))
        val steps = (0 until 4).map { ChatMessage(100L + it, Role.STEP, listOf("Searching the web", "Reading a page", "Writing notes", "Checking a claim")[it], at = now - 40_000 + it * 9_000L) }
        shoot("pills") {
            WorkCard(steps, live = true, startAt = now - 40_000, endAt = now, now = now)
            WorkCard(steps, live = false, startAt = now - 215_000, endAt = now)
            AgentPill("B7")
            AgentPill("B8")
        }
    }
}
