package com.blazeinchue.argus.agent


import com.blazeinchue.argus.BrandName
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blazeinchue.argus.memory.CacheStore
import com.blazeinchue.argus.memory.CacheTier
import com.blazeinchue.argus.store.JdbcSql
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * 0.49.0 (RC5 a + tiered memory): a Home-chat tool turn end to end through the agent loop's policy, with the real parts:
 * the lane choice (ChatLane.fits, incl. the go-ahead fix), the chat lane's rerun when its reply announces an action
 * (ActionPromise), the full lane's one re-ask for a "doing it now" reply with no tool, the honest fallback, the real
 * argus_self tool (SelfControl → SelfSetup.open → ActivityStart) firing ACTION_ACCESSIBILITY_SETTINGS, and the cache
 * tools reading a cached document. Like AgentLoopScriptTest, the model is a script; Agent.runLoop itself needs the full
 * Android session (no model seam), so [Loop] runs the same steps in the same order.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)   // no drawing here; runs on ARM hosts too
class HomeChatToolLoopTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { Settings.init(app); SelfControl.activity = null }
    @After fun tearDown() { SelfControl.activity = null; CacheTier.useForTests(null) }

    private fun call(name: String, args: JSONObject) = JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
        .put("tool_calls", JSONArray().put(JSONObject().put("id", "c" + name.hashCode()).put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", args.toString()))))
    private fun say(t: String) = JSONObject().put("role", "assistant").put("content", t)

    /** One turn: [lite] script answers the chat lane (no tools); [full] answers the tool lane. */
    inner class Loop(val lite: MutableList<JSONObject>, val full: MutableList<JSONObject>, val chat: String = "main") {
        val history = JSONArray()
        val ran = ArrayList<String>()
        val steps = ArrayList<String>()
        var lane = ""
        var followups = emptyList<String>()

        suspend fun tool(name: String, args: JSONObject): String = when (name) {
            "argus_self" -> SelfControl.run(app, args.optString("action"), args)
            in CacheTier.names -> CacheTier.run(name, args, chat)
            else -> "Error: there is no tool called \"$name\"."
        }

        suspend fun turn(user: String, lastArgus: String): String {
            history.put(JSONObject().put("role", "user").put("content", user))
            var useLite = ChatLane.fits(user, lastArgus) && !CacheTier.referenced(history)
            lane = if (useLite) "chat" else "tools"
            var promiseNudged = false
            repeat(8) {
                val reply = (if (useLite) lite else full).removeAt(0)
                if (useLite) {
                    useLite = false
                    val said = reply.optString("content")
                    if (ChatLane.escaped(said) || ActionPromise.promises(said).isNotEmpty()) { lane = "chat→tools"; return@repeat }
                    return said
                }
                history.put(reply)
                val calls = reply.optJSONArray("tool_calls")
                if (calls != null && calls.length() > 0) {
                    for (i in 0 until calls.length()) {
                        val f = calls.getJSONObject(i).getJSONObject("function")
                        val r = tool(f.getString("name"), JSONObject(f.getString("arguments")))
                        ran += f.getString("name")
                        history.put(JSONObject().put("role", "tool").put("tool_call_id", calls.getJSONObject(i).getString("id")).put("content", r))
                    }
                    return@repeat
                }
                var text = reply.optString("content")
                val promised = if (ran.isEmpty()) ActionPromise.promises(text) else emptyList()
                if (promised.isNotEmpty()) {
                    if (!promiseNudged) {
                        promiseNudged = true; steps += "Doing what I said"
                        history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(ActionPromise.nudge(promised.first()), user)))
                        return@repeat
                    }
                    text = ActionPromise.honest(text, promised); followups = listOf(NudgePolicy.TRY_AGAIN)
                }
                return text
            }
            return "Stopped."
        }
    }

    private fun accessibilityOpened(fromActivity: android.app.Activity? = null): Intent? =
        (if (fromActivity != null) shadowOf(fromActivity).nextStartedActivity else shadowOf(app).nextStartedActivity)
            ?.takeIf { it.action == android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS }

    @Test fun goAheadAfterAnOfferTakesTheToolLane() {
        val offer = "I'll open the accessibility settings now."
        assertFalse(ChatLane.fits("okay, do it!", offer))
        assertFalse(ChatLane.fits("go ahead", "Sure."))
        assertTrue(ChatLane.fits("thanks!", "Lusaka is sunny today."))
    }

    @Test fun accessibilitySettingsIntentFires() {
        val r = SelfSetup.open(app, "accessibility")
        assertTrue(r, r.startsWith("Opened the accessibility screen"))
        val i = accessibilityOpened()
        assertNotNull(i); assertTrue(i!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun withArgusOnScreenTheActivityStartsIt() {
        val a = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        SelfControl.activity = java.lang.ref.WeakReference(a)
        assertEquals("activity", com.blazeinchue.argus.device.ActivityStart.start(app, Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)))
        val i = accessibilityOpened(a)
        assertNotNull(i); assertEquals(0, i!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun ownersConversationNowOpensTheSettings() = runBlocking {
        // "okay, do it!" after the offer: tool lane; the model first only SAYS it (no call), is re-asked once, then calls argus_self.
        val loop = Loop(lite = mutableListOf(), full = mutableListOf(
            say("Opening accessibility settings for you now."),
            call("argus_self", JSONObject().put("action", "open").put("target", "accessibility")),
            say("Done. Switch on ${BrandName.NAME} under Installed apps.")))
        val out = loop.turn("okay, do it!", "I'll open the accessibility settings now.")
        assertEquals("tools", loop.lane)
        assertEquals(listOf("Doing what I said"), loop.steps)
        assertEquals(listOf("argus_self"), loop.ran)
        assertNotNull(accessibilityOpened())
        assertEquals("Done. Switch on ${BrandName.NAME} under Installed apps.", out)
    }

    @Test fun chatLaneReplyThatAnnouncesAnActionReruns() = runBlocking {
        val loop = Loop(lite = mutableListOf(say("Opening accessibility settings for you now.")), full = mutableListOf(
            call("argus_self", JSONObject().put("action", "open").put("target", "accessibility")), say("It's open.")))
        // a plain "sure" that the chat lane takes
        val out = loop.turn("sure", "Lusaka is sunny today.")
        assertEquals("chat→tools", loop.lane)
        assertNotNull(accessibilityOpened())
        assertEquals("It's open.", out)
    }

    @Test fun stillNoToolGivesTheHonestLine() = runBlocking {
        val loop = Loop(lite = mutableListOf(), full = mutableListOf(say("Opening accessibility settings for you now."), say("Opening accessibility settings now.")))
        val out = loop.turn("open accessibility settings", "")
        assertEquals(ActionPromise.HONEST, out)
        assertEquals(listOf(NudgePolicy.TRY_AGAIN), loop.followups)
        assertTrue(loop.ran.isEmpty())
    }

    @Test fun cacheToolsWorkThroughTheLoop() = runBlocking {
        val s = CacheStore(JdbcSql(), Files.createTempDirectory("cache").toFile()); CacheTier.useForTests(s)
        val big = "Contract\n\n" + (1..30).joinToString("") { "## Clause $it\n\n" + "Standard terms and conditions apply here. ".repeat(60) + "\n\n" } +
            "## Termination\n\nEither party may end this contract with 90 days written notice.\n"
        val h = JSONArray().put(JSONObject().put("role", "user").put("content", big))
        CacheTier.pageHistory("main", h, 0).forEach { (b, ref) -> CacheTier.apply(h, b, ref) }
        val id = CacheTier.docIds(h.getJSONObject(0).getString("content")).single()
        assertTrue(CacheTier.promptBlock("main").contains("doc:$id"))
        val loop = Loop(lite = mutableListOf(), full = mutableListOf(
            call("cache_search", JSONObject().put("doc", id).put("query", "termination notice")),
            call("cache_read", JSONObject().put("doc", id).put("index", s.sections(id).last().idx)),
            say("You can end it with 90 days written notice.")))
        loop.history.put(h.getJSONObject(0))   // the paged message is in this chat's history
        val out = loop.turn("how do I end the contract?", "")
        assertEquals("tools", loop.lane)   // the chat lane can't read documents
        assertEquals(listOf("cache_search", "cache_read"), loop.ran)
        assertTrue(loop.history.toString().contains("90 days written notice"))
        assertEquals("You can end it with 90 days written notice.", out)
    }
}
