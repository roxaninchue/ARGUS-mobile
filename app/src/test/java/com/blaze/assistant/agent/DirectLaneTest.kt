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

/** 0.54.5: Direct lane client (gateway 0.9.35): which turns may go direct, ticket parsing and reuse, and the fallback wiring. */
class DirectLaneTest {
    private fun msgs(vararg m: JSONObject) = JSONArray().apply { m.forEach { put(it) } }
    private val hello = JSONObject().put("role", "user").put("content", "hello")

    @Test fun onlyPlainStreamingChatOnTheMainLane() {
        val m = msgs(hello)
        assertTrue(DirectLane.eligible("chat", true, true, null, m))
        assertTrue(DirectLane.eligible("chat", true, true, JSONArray(), m))
        assertFalse(DirectLane.eligible("agent", true, true, null, m))                       // action turns keep the normal route
        assertFalse(DirectLane.eligible("chat", false, true, null, m))                       // non-streaming
        assertFalse(DirectLane.eligible("chat", true, false, null, m))                       // background lanes
        assertFalse(DirectLane.eligible("chat", true, true, JSONArray().put(JSONObject().put("type", "function")), m))   // tools
        val pic = JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "what is this"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:x"))))
        assertFalse(DirectLane.eligible("chat", true, true, null, msgs(pic)))                // pictures go to the vision lineup
    }

    @Test fun parsesTheGatewayTicket() {
        val ok = """{"ticket":"eyJ2IjoxfQ.c2ln","exp":1795295502519,"task":"chat","models":[{"route":"r_1f5cad777cc4e24d","tier":"strong"},{"route":"r_a1425efa2c9e6d38","tier":"strong"}],"rules_hash":"x","tools":0,"budget":64000}"""
        val t = DirectLane.parseTicket(ok)
        assertNotNull(t)
        assertEquals(1795295502519L, t!!.exp)
        assertEquals(listOf("r_1f5cad777cc4e24d", "r_a1425efa2c9e6d38"), t.routes)
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
        val hook = llm.substringAfter("DirectLane.enabled() && DirectLane.eligible(").substringBefore("var trimmed = false")
        assertTrue(hook.contains("DirectLane.tryChat("))
        assertTrue(hook.contains("DirectLane.BUSY_LINE"))
        assertEquals("Busy, retrying", DirectLane.BUSY_LINE)
        assertFalse(dl.contains("put(\"tools\""))
        assertTrue(dl.contains("X-Argus-Ticket") && dl.contains("X-Argus-Device"))
        assertEquals(1_500L, DirectLane.FIRST_MS_DEFAULT)
    }
}
