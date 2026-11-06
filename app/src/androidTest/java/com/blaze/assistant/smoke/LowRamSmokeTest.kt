package com.blaze.assistant.smoke

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blaze.assistant.agent.CallScan
import com.blaze.assistant.movies.JsProviderSource
import com.blaze.assistant.movies.ProviderRepo
import com.blaze.assistant.movies.ProviderSandbox
import com.blaze.assistant.movies.SandboxHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.46.6 low-RAM release gate (Rocky: STABILITY FIRST). It runs on a small x86_64 API 31 emulator, inside the app's own process,
 * with the real Android QuickJS engine, Media3 and ART regex. Cold start of the release APK is checked separately by
 * tools/smoke/run-smoke.sh.
 * The steps: Video browse (3 sources x 200 items + concurrent loads, with callers giving up), details while browsing, 10 s of
 * video playback, music playback, 30 mocked tool-chat turns with DSML, and (0.52.3) the ARGUS x Hark shell (the space view,
 * the Home's cards, Spotlight, the profile page and Projects) in dark and light. Throughout, a main-thread monitor samples every 50 ms
 * and the gate fails on any main stall over 700 ms. Crashes and ANRs fail the instrumentation or the logcat check in the script.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LowRamSmokeTest {
    companion object {
        private const val TAG = "BlazeSmoke"
        private const val STALL_MS = 700L
        private val main = Handler(Looper.getMainLooper())
        private val worst = AtomicLong(0); private val worstAt = arrayOf("")
        @Volatile private var phase = "start"
        @Volatile private var running = false

        /**
         * Warms the emulator's software GL (SwiftShader) before the monitor starts. The first HWUI frame of a process
         * compiles Skia's programs on the RenderThread, and that first window draw holds the main thread until the frame
         * completes: on 2c7ef81d/d5ad8250 that wait was the whole "argus home (auto)" stall (878/1117 ms of RenderThread GL,
         * main's own work ~115-150 ms). A generic warm-up (10d5bd15) still left 644 ms of GL on the Home's first frame (its
         * own gradients and text), so the warm-up draws the Home itself. 0.52.3: that Home is the ARGUS x Hark shell (the
         * space view, the top bar, the Home's cards, then the answer card), dark, which also decodes the space image once.
         * run-smoke.sh already excludes this same render wait from the cold-start gate, and the app's real first frame stays
         * gated there. f_spaceHome keeps the 700 ms gate for everything after: both modes, the cards, Spotlight, profile, Projects.
         */
        private fun warmRenderer() {
            val home = androidx.compose.runtime.mutableStateOf(com.blaze.assistant.ui.space.SpaceHomeState(name = "Warm-up", hour = 9))
            val sc = androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
            sc.onActivity { a ->
                a.setContent { SpaceShell(dark = true, home = home.value, tab = com.blaze.assistant.ui.space.SpaceTabs.HOME, profile = false) }
            }
            SystemClock.sleep(2_500)
            main.post { home.value = home.value.copy(answer = com.blaze.assistant.ui.space.SpaceAnswer.Card("Warm-up ask", "Warm-up reply with a few words in it.", false)) }
            SystemClock.sleep(1_500)
            sc.close()
            SystemClock.sleep(500)
        }

        /** The shell as ChatScreen draws it (space view, top bar, the tab), with Spotlight over it when [composer] opens it. */
        @androidx.compose.runtime.Composable
        fun SpaceShell(dark: Boolean, home: com.blaze.assistant.ui.space.SpaceHomeState, tab: String, profile: Boolean,
                       composer: com.blaze.assistant.ui.composer.ComposerState? = null) {
            androidx.compose.runtime.CompositionLocalProvider(com.blaze.assistant.ui.LocalBlaze provides if (dark) com.blaze.assistant.ui.DarkColors else com.blaze.assistant.ui.LightColors) {
                com.blaze.assistant.ui.space.SpaceBackdrop {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.statusBarsPadding()) {
                        com.blaze.assistant.ui.space.SpaceTopBar(tab = if (profile) "" else tab, onTab = {}, onLeft = {}, initial = "S", onAvatar = {}, showLeft = !profile)
                        when {
                            profile -> com.blaze.assistant.ui.space.SpaceProfileScreen("Smoke Test", "smoke@example.com", signedIn = true, planLine = "Pro plan",
                                onPage = {}, onSignOut = {}, onSearch = {})
                            tab == com.blaze.assistant.ui.space.SpaceTabs.PROJECTS -> com.blaze.assistant.ui.space.SpaceProjectsScreen(
                                listOf(com.blaze.assistant.agent.Project("p1", "Smoke project", chats = listOf("c1"))),
                                listOf(com.blaze.assistant.agent.ChatMeta("c1", "Smoke chat", 0L, false, "")), busy = setOf("c1"), needsYou = emptySet(), openId = "",
                                onOpen = {}, onBack = {}, onCreate = {}, onChat = {}, onNewChat = {}, onInstructions = { _, _ -> }, onDelete = {})
                            else -> com.blaze.assistant.ui.space.SpaceHomeScreen(home, onAsk = {}, onOpenChat = {}, onOpenProject = {}, onOpenProjects = {}, onSignIn = {}, onSetup = {})
                        }
                    }
                    if (composer != null) com.blaze.assistant.ui.space.SpaceSpotlightLayer(composer, hour = 9)
                }
            }
        }

        /** Main-thread latency monitor: one ping in flight at a time, checked every 50 ms. */
        @BeforeClass @JvmStatic fun startMonitor() {
            ProviderSandbox.Gate.resetForTests()
            com.blaze.assistant.ui.HomeImages.configure(true)
            warmRenderer()
            running = true
            Thread({
                while (running) {
                    val sent = SystemClock.uptimeMillis(); val answered = AtomicLong(0)
                    main.post { answered.set(SystemClock.uptimeMillis()) }
                    while (running && answered.get() == 0L) {
                        SystemClock.sleep(50)
                        val lag = SystemClock.uptimeMillis() - sent
                        if (lag > worst.get()) synchronized(worstAt) { if (lag > worst.get()) { worst.set(lag); worstAt[0] = phase } }
                    }
                    SystemClock.sleep(50)
                }
            }, "smoke-stall-monitor").apply { isDaemon = true }.start()
        }
        @AfterClass @JvmStatic fun stopMonitor() { running = false; Log.i(TAG, "SMOKE worst main stall ${worst.get()} ms during ${worstAt[0]}") }

        fun assertNoStall(step: String) {
            Log.i(TAG, "SMOKE $step: worst main stall so far ${worst.get()} ms (${worstAt[0]})")
            assertTrue("main thread stalled ${worst.get()} ms during ${worstAt[0]} (limit $STALL_MS)", worst.get() <= STALL_MS)
        }
    }

    private class SiteHttp(val delayMs: Long, val body: (String) -> String) : SandboxHttp {
        val inFlight = AtomicInteger(0); val maxInFlight = AtomicInteger(0)
        override fun request(r: SandboxHttp.Req): SandboxHttp.Res {
            val n = inFlight.incrementAndGet(); maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            try { Thread.sleep(delayMs); return SandboxHttp.Res(200, "OK", r.url, mapOf("content-type" to "text/html"), body(r.url)) } finally { inFlight.decrementAndGet() }
        }
    }
    private fun page(n: Int) = "<html><body>" + (1..n).joinToString("") { i -> "<div class=\"film\"><a href=\"/film/$i\">Film number $i</a><img src=\"/img/$i.jpg\"></div>" } + "</body></html>"
    private val posts = """
        exports.getPosts = async function({ filter, providerContext }) {
          var r = await providerContext.axios.get('https://films.example/' + (filter || 'latest'));
          var ${'$'} = providerContext.cheerio.load(r.data); var out = [];
          ${'$'}('.film').each(function(i, el){ out.push({ title: ${'$'}(el).find('a').text().trim(), link: ${'$'}(el).find('a').attr('href'), image: ${'$'}(el).find('img').attr('src') }); });
          return out;
        };
        exports.getMeta = async function({ link, providerContext }) {
          var r = await providerContext.axios.get(link); var ${'$'} = providerContext.cheerio.load(r.data);
          return { title: ${'$'}('a').first().text(), synopsis: 'ok', linkList: [{ title: 'Movie', directLinks: [{ link: link + '/play', title: 'Play' }] }] };
        };
        exports.catalog = [{ title: 'Latest', filter: 'latest' }, { title: 'Popular', filter: 'popular' }, { title: 'New', filter: 'new' }, { title: 'Top', filter: 'top' }];
    """.trimIndent()

    @Test fun a_videoBrowse200ItemsWithConcurrentLoads() = runBlocking {
        phase = "video browse"
        val http = SiteHttp(150) { page(200) }
        val sources = (1..3).map { k -> JsProviderSource(ProviderRepo.Entry("smoke$k", "Smoke $k", "1.0"), { _ -> posts }, ProviderSandbox.ProviderEnv(), ProviderSandbox(http)) }
        val off0 = ProviderSandbox.Gate.offLane.get()
        // the crash pattern: 7 calls at once with callers giving up mid-fetch, 3 rounds
        val sb = ProviderSandbox(http)
        repeat(3) { round -> (1..7).map { i -> async { withTimeoutOrNull(100L + 60 * (i % 4)) { sb.invoke(posts, if (i % 3 == 0) null else "getPosts", JSONObject().put("filter", "r$round$i")) } } }.awaitAll() }
        // then every source's rows at once (catalog + 4 rows of 200 each)
        val rows = sources.map { s -> async { s.rows() } }.awaitAll()
        rows.forEach { rs -> assertEquals(4, rs.size); rs.forEach { (_, ps) -> assertEquals(200, ps.size) } }
        assertEquals("host calls off the engine thread", off0, ProviderSandbox.Gate.offLane.get())
        assertEquals("QuickJS engines alive at once", 1, ProviderSandbox.Gate.maxSeen.get())
        assertNoStall("video browse")
    }

    @Test fun b_detailsWhileBrowsing() = runBlocking {
        phase = "details"
        val slow = SiteHttp(1_200) { page(20) }
        val src = JsProviderSource(ProviderRepo.Entry("smokeD", "Smoke D", "1.0"), { _ -> posts }, ProviderSandbox.ProviderEnv(), ProviderSandbox(slow))
        val browsing = launch { src.rows() }
        delay(300)
        val t0 = SystemClock.uptimeMillis()
        val info = JsProviderSource(ProviderRepo.Entry("smokeE", "Smoke E", "1.0"), { _ -> posts }, ProviderSandbox.ProviderEnv(), ProviderSandbox(SiteHttp(20) { page(3) }))
            .info(com.blaze.assistant.movies.MoviePost("Film number 1", "https://films.example/film/1", "", "p:smokeE"))
        val ms = SystemClock.uptimeMillis() - t0
        Log.i(TAG, "SMOKE details opened in $ms ms while browsing")
        assertEquals("Film number 1", info.title)
        assertTrue("details took $ms ms", ms < 8_000)
        browsing.join()
        assertNoStall("details")
    }

    private fun asset(name: String): Uri {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        ctx.assets.open(name).use { i -> out.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(out)
    }

    /** Plays [uri] on the main thread for [ms]; returns the position reached. Fails on any player error. */
    private fun play(uri: Uri, ms: Long): Long {
        val inst = InstrumentationRegistry.getInstrumentation()
        var player: ExoPlayer? = null; var error: PlaybackException? = null
        inst.runOnMainSync {
            player = ExoPlayer.Builder(inst.targetContext).build().apply {
                addListener(object : Player.Listener { override fun onPlayerError(e: PlaybackException) { error = e } })
                setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true
            }
        }
        val t0 = SystemClock.uptimeMillis(); var pos = 0L
        while (SystemClock.uptimeMillis() - t0 < ms + 4_000) {
            SystemClock.sleep(250)
            inst.runOnMainSync { pos = player!!.currentPosition }
            if (error != null || pos >= ms) break
        }
        inst.runOnMainSync { player!!.release() }
        assertTrue("player error: ${error?.errorCodeName} ${error?.message}", error == null)
        return pos
    }

    @Test fun c_videoPlayback10s() {
        phase = "video playback"
        val pos = play(asset("smoke.mp4"), 10_000)
        Log.i(TAG, "SMOKE video reached $pos ms")
        assertTrue("video only reached $pos ms", pos >= 9_500)
        assertNoStall("video playback")
    }

    @Test fun d_musicPlay() {
        phase = "music playback"
        val pos = play(asset("smoke.m4a"), 5_000)
        Log.i(TAG, "SMOKE music reached $pos ms")
        assertTrue("music only reached $pos ms", pos >= 4_500)
        assertNoStall("music playback")
    }

    @Test fun e_thirtyToolChatTurnsWithDsml() {
        phase = "tool chat"
        val B = "\uFF5C"
        fun tool(n: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("parameters", JSONObject().put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))))
        val all = JSONArray().put(tool("web_search")).put(tool("memory_search")).put(tool("set_alarm"))
        fun dsml(name: String, q: String, bar: String = B) = "<${bar}DSML${bar}function_calls>\n<${bar}DSML${bar}invoke name=\"$name\">\n<${bar}DSML${bar}parameter name=\"query\" string=\"true\">$q</${bar}DSML${bar}parameter>\n</${bar}DSML${bar}invoke>\n</${bar}DSML${bar}function_calls>"
        var calls = 0; var worstTurn = 0L
        repeat(30) { i ->
            val reply = when (i % 4) { 0 -> "Turn $i.\n" + dsml("web_search", "q$i"); 1 -> "Turn $i.\n" + dsml("memory_search", "m$i", "|"); 2 -> "Turn $i: words only."; else -> "Turn $i.\n" + dsml("set_alarm", "07:0${i % 10}") }
            val t0 = SystemClock.uptimeMillis()
            // streaming: every prefix is cleaned the way the chat bubble does it, then the final text becomes calls
            var k = 0; while (k < reply.length) { k = minOf(reply.length, k + 23); val shown = CallScan.clean(reply.substring(0, k), final = false); assertTrue("turn $i leaked while streaming: $shown", !shown.contains("DSML")) }
            val r = CallScan.toCalls(reply, all)
            val words = r?.second ?: CallScan.clean(reply)
            calls += r?.first?.length() ?: 0
            worstTurn = maxOf(worstTurn, SystemClock.uptimeMillis() - t0)
            assertTrue("turn $i leaked: $words", !words.contains("DSML") && words.startsWith("Turn $i"))
        }
        Log.i(TAG, "SMOKE 30 tool-chat turns: $calls calls, worst turn $worstTurn ms")
        assertEquals(23, calls)
        assertNoStall("tool chat")
    }

    /**
     * 0.52.3: the ARGUS x Hark shell, the app's root (replaces the 3D Home's f_argusHome; still one scenario, so the gate
     * stays at 7 tests). In dark (the space view) and light: the Home idle, working with the answer card, signed out with
     * the setup card; Spotlight opens with a query and closes; the profile page; Projects; back Home. Twice per mode.
     */
    @Test fun f_spaceHome() {
        val inst = InstrumentationRegistry.getInstrumentation()
        for (dark in listOf(true, false)) {
            phase = "space home (${if (dark) "dark" else "light"})"
            val calm = com.blaze.assistant.ui.space.SpaceHomeState(name = "Smoke", hour = 9, projects = listOf("p1" to "Smoke project"))
            val home = androidx.compose.runtime.mutableStateOf(calm)
            val tab = androidx.compose.runtime.mutableStateOf(com.blaze.assistant.ui.space.SpaceTabs.HOME)
            val profile = androidx.compose.runtime.mutableStateOf(false)
            val composer = com.blaze.assistant.ui.composer.ComposerState()
            val sc = androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
            sc.onActivity { a -> a.setContent { SpaceShell(dark, home.value, tab.value, profile.value, composer) } }
            SystemClock.sleep(2_500)
            repeat(2) {
                main.post { home.value = calm.copy(working = true, answer = com.blaze.assistant.ui.space.SpaceAnswer.Card("Smoke ask", "", true)) }; SystemClock.sleep(1_000)
                main.post { home.value = calm.copy(answer = com.blaze.assistant.ui.space.SpaceAnswer.Card("Smoke ask", "Smoke reply with a few words in it.", false)) }; SystemClock.sleep(1_000)
                main.post { home.value = calm.copy(signedIn = false, setupLeft = 3, setupPercent = 40) }; SystemClock.sleep(1_000)
                main.post { composer.openSpotlight() }; SystemClock.sleep(1_000)
                main.post { composer.query = "set" }; SystemClock.sleep(1_000)
                main.post { composer.closeSpotlight() }; SystemClock.sleep(800)
                main.post { profile.value = true }; SystemClock.sleep(1_000)
                main.post { profile.value = false; tab.value = com.blaze.assistant.ui.space.SpaceTabs.PROJECTS }; SystemClock.sleep(1_000)
                main.post { tab.value = com.blaze.assistant.ui.space.SpaceTabs.HOME; home.value = calm }; SystemClock.sleep(800)
            }
            var open = true
            inst.runOnMainSync { open = composer.spotlightOpen }
            assertTrue("Spotlight closed", !open)
            sc.close()
            SystemClock.sleep(500)
        }
        assertNoStall("space home")
    }

    @Test fun z_noStallAnywhere() { assertNoStall("whole run") }
}
