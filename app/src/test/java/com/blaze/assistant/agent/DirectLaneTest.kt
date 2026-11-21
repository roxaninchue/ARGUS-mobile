package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.54.5: Direct lane client (gateway 0.9.35): which turns may go direct, ticket parsing and reuse, and the fallback wiring. 0.54.6: tool turns, covering tickets, the size / p95 first-token wait. */
class DirectLaneTest {
    private fun msgs(vararg m: JSONObject) = JSONArray().apply { m.forEach { put(it) } }
    private val hello = JSONObject().put("role", "user").put("content", "hello")

    private fun fn(name: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("parameters", JSONObject().put("type", "object")))
    private fun tools(vararg n: String) = JSONArray().apply { n.forEach { put(fn(it)) } }

    @Test fun streamingMainLaneTurnsWithOrWithoutTools() {
        val m = msgs(hello)
        assertTrue(DirectLane.eligible("chat", true, true, null, m))
        assertTrue(DirectLane.eligible("chat", true, true, JSONArray(), m))
        assertTrue(DirectLane.eligible("agent", true, true, tools("set_alarm", "web_search"), m))   // 0.54.6: tool turns go direct
        assertFalse(DirectLane.eligible("code", true, true, null, m))                        // no ticket for code / quick / long / vision
        assertFalse(DirectLane.eligible("chat", false, true, null, m))                       // non-streaming
        assertFalse(DirectLane.eligible("agent", true, false, tools("set_alarm"), m))        // background / steps lanes
        assertFalse(DirectLane.eligible("agent", true, true, tools("bad name!"), m))         // a name no ticket can carry
        assertFalse(DirectLane.eligible("agent", true, true, JSONArray().put(JSONObject().put("type", "function")), m))   // nameless tool
        val pic = JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "what is this"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:x"))))
        assertFalse(DirectLane.eligible("chat", true, true, null, msgs(pic)))                // pictures go to the vision lineup
    }

    private fun b64u(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test fun aToolTicketIsUsedOnlyWhenItKeepsEveryTool() {
        val payload = """{"v":1,"sub":"u","tools":["web_search","gmail_send"],"dtools":["set_alarm"],"task":"agent"}"""
        val body = """{"ticket":"${b64u(payload)}.c2ln","exp":1795295502519,"task":"agent","models":[{"route":"r_a","tier":"strong","p95_ms":640},{"route":"r_b","tier":"strong"}],"tools":2,"budget":64000}"""
        val t = DirectLane.parseTicket(body, withTools = true)!!
        assertEquals(setOf("web_search", "gmail_send", "set_alarm"), t.allowed)
        assertEquals(mapOf("r_a" to 640L), t.p95)
        assertTrue(DirectLane.covers(t, listOf("set_alarm", "web_search")))
        assertFalse(DirectLane.covers(t, listOf("set_alarm", "make_document")))           // one missing: normal route, no tool lost
        assertTrue(DirectLane.covers(t, emptyList()))
        // a plain chat ticket (not asked with tools) never serves a tool turn, even if it lists names
        assertFalse(DirectLane.covers(t.copy(withTools = false), listOf("web_search")))
        // cached ticket: same task, fresh, covering
        val now = 1_795_295_502_519L - 60_000
        assertNotNull(DirectLane.usable(t, "agent", listOf("set_alarm"), now))
        assertNull(DirectLane.usable(t, "chat", listOf("set_alarm"), now))
        assertNull(DirectLane.usable(t, "agent", listOf("set_alarm"), 1_795_295_502_519L - 10_000))
        assertNull(DirectLane.usable(t, "agent", listOf("pay_bill"), now))
        // an unreadable payload lets nothing through
        assertEquals(emptySet<String>(), DirectLane.payloadTools("not-base64!.sig"))
    }

    @Test fun theTicketAsksForTheTurnsToolsFirstAndStaysSmall() {
        val plain = DirectLane.ticketBody("chat", 1200, emptyList())
        assertFalse(plain.has("tools") || plain.has("device_tools"))
        val b = DirectLane.ticketBody("agent", 9000, listOf("set_alarm", "web_search"), listOf("web_search", "older_tool", "bad name!"))
        assertEquals(true, b.getBoolean("tools"))
        assertEquals("agent", b.getString("task"))
        assertEquals(listOf("set_alarm", "web_search", "older_tool"), (0 until b.getJSONArray("device_tools").length()).map { b.getJSONArray("device_tools").getString(it) })
        val many = (1..400).map { "tool_number_$it" }
        val big = DirectLane.ticketBody("agent", 9000, many.take(30), many)
        assertTrue(big.toString().length <= DirectLane.TICKET_BODY_MAX)
        val sent = big.getJSONArray("device_tools")
        assertEquals(many.take(30), (0 until 30).map { sent.getString(it) })                // the turn's own names always fit first
    }

    @Test fun theFirstTokenWaitUsesTheModelsP95AndGrowsWithThePrompt() {
        assertEquals(1_500L, DirectLane.firstDeadline(null, 1_000))                          // short prompt, nothing measured
        assertEquals(1_500L + 1_000L, DirectLane.firstDeadline(null, 12_000))                // +100 ms per 1k tokens past 2k
        assertEquals(DirectLane.FIRST_MS_MAX, DirectLane.firstDeadline(null, 500_000))
        assertEquals(1_100L, DirectLane.firstDeadline(640, 6_000))                           // 640 x 1.25 + 300 hop
        assertEquals(1_100L + 1_200L, DirectLane.firstDeadline(640, 20_000))                 // a big prompt still gets its prefill time
        assertEquals(DirectLane.FIRST_MS_MIN, DirectLane.firstDeadline(100, 1_000))
        assertEquals(DirectLane.FIRST_MS_MAX, DirectLane.firstDeadline(9_000, 1_000))
    }

    @Test fun twoTimeoutsInARowRestThatTaskOnly() {
        DirectLane.resetForTests()
        assertFalse(DirectLane.noteSlow("agent", 1_000))
        assertTrue(DirectLane.noteSlow("agent", 2_000))
        assertTrue(DirectLane.resting("agent", 2_000 + DirectLane.SLOW_REST_MS - 1))
        assertFalse(DirectLane.resting("agent", 2_000 + DirectLane.SLOW_REST_MS))
        assertFalse(DirectLane.resting("chat", 2_000))
        DirectLane.resetForTests()
    }

    @Test fun parsesTheGatewayTicket() {
        val ok = """{"ticket":"eyJ2IjoxfQ.c2ln","exp":1795295502519,"task":"chat","models":[{"route":"r_1f5cad777cc4e24d","tier":"strong"},{"route":"r_a1425efa2c9e6d38","tier":"strong"}],"rules_hash":"x","tools":0,"budget":64000}"""
        val t = DirectLane.parseTicket(ok)
        assertNotNull(t)
        assertEquals(1795295502519L, t!!.exp)
        assertEquals(listOf("r_1f5cad777cc4e24d", "r_a1425efa2c9e6d38"), t.routes)
        assertEquals(emptySet<String>(), t.allowed)                                          // {"v":1}: no tools
        assertFalse(t.withTools)
        assertEquals(emptyMap<String, Long>(), t.p95)
        assertNull(DirectLane.parseTicket("""{"error":{"code":"direct_unavailable"}}"""))
        assertNull(DirectLane.parseTicket("""{"ticket":"no-dot","exp":1,"models":[{"route":"r_x"}]}"""))
        assertNull(DirectLane.parseTicket("""{"ticket":"a.b","exp":1,"models":[]}"""))
        assertNull(DirectLane.parseTicket("not json"))
    }

    @Test fun reusesATicketUntil30sBeforeItsExpiry() {
        val t = DirectLane.Ticket("a.b", 1_000_000L, listOf("r_x"), "chat")
        assertTrue(DirectLane.fresh(t, 1_000_000L - 31_000))
        assertFalse(DirectLane.fresh(t, 1_000_000L - 29_000))
        assertFalse(DirectLane.fresh(null, 0))
    }

    @Test fun aSwitchedOffLaneRestsLongerThanABusyOne() {
        assertEquals(DirectLane.OFF_MS, DirectLane.restFor(404))
        assertEquals(DirectLane.REST_MS, DirectLane.restFor(503))
        assertEquals(DirectLane.REST_MS, DirectLane.restFor(429))
        assertTrue(DirectLane.OFF_MS > DirectLane.REST_MS)
    }

    @Test fun deviceIdShape() {
        assertTrue(DirectLane.DEVICE_RE.matches("a" + "x".repeat(22)))
        assertFalse(DirectLane.DEVICE_RE.matches("short"))
        assertFalse(DirectLane.DEVICE_RE.matches("has space in it"))
    }

    /** Source check: the client falls back to the normal route with the busy line, never shows "unreachable", and never sends tools. */
    @Test fun wiring() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main").isDirectory || File(it, "src/main").isDirectory }
        val dir = File(root, if (File(root, "app/src/main").isDirectory) "app/src/main/java" else "src/main/java").walk().first { it.name == "DirectLane.kt" }.parentFile
        val llm = File(dir, "LlmClient.kt").readText()
        val dl = File(dir, "DirectLane.kt").readText()
        val hook = llm.substringAfter("DirectLane.enabled(task = task) && DirectLane.eligible(").substringBefore("var trimmed = false")
        assertTrue(hook.contains("DirectLane.tryChat("))
        assertTrue(hook.contains("DirectLane.BUSY_LINE"))
        assertEquals("Busy, retrying", DirectLane.BUSY_LINE)
        // 0.54.6: the tools sent direct are the normal route's (cap / adapt / choice), and only past a covering ticket
        assertTrue(hook.contains("ToolTruth.cap(msgs, tl)") && hook.contains("ToolTruth.choice(msgs, it)"))
        assertTrue(dl.contains("usable(tickets[task], task, names, now)"))
        assertTrue(dl.contains("if (!covers(t, names))"))
        assertTrue(dl.contains("X-Argus-Ticket") && dl.contains("X-Argus-Device"))
        assertTrue(dl.contains("!gotWord.get() && !st.gotFirst"))                                // thinking or a tool call counts as alive
        assertTrue(llm.contains("DirectLane.prewarm(tok)"))
        assertEquals(1_500L, DirectLane.FIRST_MS_DEFAULT)
    }
}
