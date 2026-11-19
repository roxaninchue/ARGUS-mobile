package com.blaze.assistant.team

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-MG-01: the Demo-team fixtures pass the rules in docs/overhaul/ateam-export.md §3, and each rule catches its break. */
class ATeamExportTest {
    private fun res(name: String): JSONObject = JSONObject(javaClass.getResource("/ateam/$name")!!.readText())
    private val backup get() = res("demo-team.backup.json")
    private val v1 get() = res("demo-team.export.v1.json")

    @Test fun detectsBothFormats() {
        assertEquals(ATeamExport.Kind.BACKUP, ATeamExport.detect(backup))
        assertEquals(ATeamExport.Kind.V1, ATeamExport.detect(v1))
        assertNull(ATeamExport.detect(JSONObject("""{"format":"argus-team-export","v":2,"bots":[]}""")))
        assertNull(ATeamExport.detect(JSONObject("""{"hello":1}""")))
    }

    @Test fun demoFixturesAreValid() {
        assertEquals(emptyList<String>(), ATeamExport.validate(backup))
        assertEquals(emptyList<String>(), ATeamExport.validate(v1))
    }

    @Test fun demoTeamShape() {
        val b = backup
        assertEquals(5, b.getJSONArray("agents").length())   // Nova, Atlas, Echo, Vega + Manager (kept last)
        assertEquals("manager", b.getJSONArray("agents").getJSONObject(4).getString("id"))
        assertEquals(9, b.getJSONArray("tasks").length())
        val x = v1
        assertEquals(7, x.getJSONArray("profile").length())
        assertEquals(4, x.getJSONArray("routines").length())
    }

    @Test fun facesFollowAteamAgentFace() {
        val bots = backup.getJSONArray("agents")
        val faces = (0 until bots.length()).map { ATeamExport.faceOf(bots.getJSONObject(it), ATeamExport.Kind.BACKUP) }
        // Nova, Atlas, Echo from the name (A-team agentFace), Vega set to Tilt, Manager locked to Twin (4)
        assertEquals(listOf(2, 3, 5, 1, 4), faces)
    }

    @Test fun schedulesAndScopes() {
        assertEquals("weekly mon 08:00", ATeamExport.schedule(JSONObject("""{"type":"weekly","time":"08:00","days":["Monday"]}""")))
        assertEquals("cron 0 17 * * 5", ATeamExport.schedule(JSONObject("""{"type":"cron","cron":"0 17 * * 5"}""")))
        assertEquals("once 2026-11-10 09:00", ATeamExport.schedule(JSONObject("""{"type":"once","date":"2026-11-10","time":"09:00"}""")))
        assertNull(ATeamExport.schedule(JSONObject("""{"type":"daily","time":"25:00"}""")))
        assertNull(ATeamExport.schedule(JSONObject("""{"type":"cron","cron":"0 17 *"}""")))
        assertEquals("user:core", ATeamExport.scopeFor("rule"))
        assertEquals("user:people", ATeamExport.scopeFor("person"))
        assertEquals("user:notes", ATeamExport.scopeFor("note"))
    }

    private fun broken(o: JSONObject, edit: (JSONObject) -> Unit): List<String> { edit(o); return ATeamExport.validate(o) }
    private fun assertCaught(problems: List<String>, part: String) =
        assertTrue("expected a problem containing '$part', got $problems", problems.any { it.contains(part) })

    @Test fun eachRuleCatchesItsBreak() {
        assertCaught(broken(backup) { it.getJSONArray("agents").remove(4) }, "exactly one Manager")
        assertCaught(broken(backup) { it.getJSONArray("agents").getJSONObject(1).put("id", "k3n9q2amhw1x") }, "duplicate id")
        assertCaught(broken(backup) { it.getJSONArray("agents").getJSONObject(1).put("lead", "nobody") }, "lead nobody")
        assertCaught(broken(backup) { it.getJSONArray("agents").getJSONObject(0).put("color", "purple") }, "#rrggbb")
        assertCaught(broken(backup) { it.getJSONArray("agents").getJSONObject(0).put("face", 9) }, "0..5")
        assertCaught(broken(backup) { it.getJSONArray("tasks").getJSONObject(0).put("agentId", "ghost") }, "bot ghost")
        assertCaught(broken(backup) { it.getJSONArray("tasks").getJSONObject(0).put("due", "Friday") }, "YYYY-MM-DD")
        assertCaught(broken(backup) { it.getJSONArray("tasks").getJSONObject(0).put("priority", "urgent") }, "priority urgent")
        assertCaught(broken(backup) { it.getJSONArray("tasks").getJSONObject(0).put("deps", org.json.JSONArray(listOf("t2b3c4dmhw31"))) }, "loop")
        assertCaught(broken(backup) { it.getJSONArray("tasks").getJSONObject(0).put("deps", org.json.JSONArray(listOf("nope"))) }, "unknown job")
        assertCaught(broken(backup) { it.getJSONArray("agents").getJSONObject(0).put("token", "bz_x") }, "credentials")
        assertCaught(broken(v1) { it.getJSONArray("profile").getJSONObject(0).put("kind", "secret-ish") }, "kind secret-ish")
        assertCaught(broken(v1) { it.getJSONArray("profile").getJSONObject(0).put("stated", "yes") }, "stated")
        assertCaught(broken(v1) { it.getJSONArray("profile").getJSONObject(0).put("text", "my password: hunter2hunter") }, "secret")
        assertCaught(broken(v1) { it.getJSONObject("memory").getJSONArray("team").getJSONObject(0).put("text", "card 4111 1111 1111 1111") }, "secret")
        assertCaught(broken(v1) { it.getJSONObject("memory").getJSONObject("bots").put("ghost", org.json.JSONArray()) }, "bot ghost")
        assertCaught(broken(v1) { it.getJSONArray("routines").getJSONObject(0).getJSONObject("sched").put("days", org.json.JSONArray(listOf("funday"))) }, "schedule")
        assertCaught(broken(v1) { it.getJSONArray("routines").getJSONObject(0).put("kind", "alarm") }, "kind alarm")
        assertCaught(broken(v1) { it.getJSONObject("proactivity").put("quiet", org.json.JSONArray(listOf("22:00"))) }, "quiet")
        assertCaught(broken(v1) { it.getJSONObject("chats").getJSONArray("manager").getJSONObject(0).put("role", "tool") }, "role tool")
        assertCaught(broken(v1) { it.getJSONObject("settings").put("pins", org.json.JSONArray(listOf("ghost"))) }, "pin ghost")
        assertCaught(broken(v1) { it.getJSONArray("bots").getJSONObject(0).remove("look") }, "no look")
    }
}
