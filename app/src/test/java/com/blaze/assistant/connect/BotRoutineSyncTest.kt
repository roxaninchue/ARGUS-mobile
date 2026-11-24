package com.blaze.assistant.connect

import com.blaze.assistant.routine.Routine
import com.blaze.assistant.routine.Routines
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.55.17 (Finn: cloud sync covers bots and teams too): bots, teams and routines follow the account. */
class BotRoutineSyncTest {
    private class Bots(vararg e: BotSource.Entry) : BotSource {
        val m = e.associateBy { it.id }.toMutableMap()
        override fun all() = m.values.toList()
        override fun put(e: BotSource.Entry) { m[e.id] = e }
        override fun remove(id: String) { m.remove(id) }
    }

    private fun bot(id: String, kind: String = "CUSTOM", u: Long = 10, soul: String? = "You are $id.") =
        BotSource.Entry(id, kind, u, "{\"id\":\"$id\"}", soul)

    @Test fun botsAndTeamsSyncButArgusAndSubagentsNever() {
        val a = BotAdapter(Bots(bot("manager", "MANAGER"), bot("health-coach"), bot("health-team", "ROOM"), bot("tmp", "SUBAGENT")))
        assertEquals(setOf("health-coach", "health-team"), a.stamps().keys)
        assertNull(a.read("manager"))
        val it = a.read("health-coach")!!
        assertEquals("You are health-coach.", it.data!!.optString("soul"))
        assertTrue(SyncKinds.BOTS in SyncKinds.ALL)
    }

    @Test fun aBotFromAnotherPhoneArrivesAndTheNewestWins() {
        val src = Bots(bot("coach", u = 50, soul = "here"))
        var applied = 0
        val a = BotAdapter(src) { applied++ }
        a.apply(SyncMerge.Item("bot", "team", 20, JSONObject().put("kind", "ROOM").put("bot", "{}").put("soul", "a team")))
        assertEquals("a team", src.m["team"]!!.soul)
        // An older copy never overwrites a newer edit here.
        a.apply(SyncMerge.Item("bot", "coach", 40, JSONObject().put("kind", "CUSTOM").put("bot", "{}").put("soul", "old")))
        assertEquals("here", src.m["coach"]!!.soul)
        a.apply(SyncMerge.Item("bot", "coach", 60, JSONObject().put("kind", "CUSTOM").put("bot", "{}").put("soul", "new")))
        assertEquals("new", src.m["coach"]!!.soul)
        // Nobody can push an Argus over this phone's.
        a.apply(SyncMerge.Item("bot", "manager", 99, JSONObject().put("kind", "MANAGER").put("bot", "{}")))
        assertFalse("manager" in src.m)
        a.apply(SyncMerge.Item("bot", "team", 70, null))
        assertFalse("team" in src.m)
        assertEquals(3, applied)
    }

    private class Rts(vararg e: RoutineSource.Entry) : RoutineSource {
        val m = e.associateBy { it.uid }.toMutableMap()
        override fun all() = m.values.toList()
        override fun put(e: RoutineSource.Entry) { m[e.uid] = e }
        override fun remove(uid: String) { m.remove(uid) }
    }

    @Test fun routinesTravelWithoutShellJobsOrThisPhonesCalendar() {
        val check = Routine(3, "Health check-in", "Ask how I slept", 7, 0, 127, eventId = 555, uid = "rabc", u = 5).toJson()
        val shell = Routine(4, "Backup", "", 2, 0, 127, command = "tar czf ~/b.tgz ~/docs", uid = "rdef", u = 5).toJson()
        val a = RoutineAdapter(Rts(RoutineSource.Entry("rabc", 5, check), RoutineSource.Entry("rdef", 5, shell)))
        assertEquals(setOf("rabc"), a.stamps().keys)
        val d = a.read("rabc")!!.data!!
        assertEquals("Health check-in", d.getString("name"))
        assertFalse(d.has("event_id")); assertFalse(d.has("id")); assertFalse(d.has("command"))
    }

    @Test fun aRoutineFromAnotherPhoneArrivesAndAnOlderOneDoesNotWin() {
        val src = Rts(RoutineSource.Entry("rabc", 50, JSONObject().put("name", "Brief").put("hour", 7)))
        val a = RoutineAdapter(src)
        a.apply(SyncMerge.Item("routine", "rabc", 40, JSONObject().put("name", "Old").put("hour", 6)))
        assertEquals("Brief", src.m["rabc"]!!.data.getString("name"))
        a.apply(SyncMerge.Item("routine", "rnew", 60, JSONObject().put("name", "Walk").put("prompt", "Remind me to walk").put("command", "rm -rf /")))
        assertEquals("Walk", src.m["rnew"]!!.data.getString("name"))
        assertFalse(src.m["rnew"]!!.data.has("command"))   // a copy can never smuggle in a shell job
        a.apply(SyncMerge.Item("routine", "rnew", 70, null))
        assertFalse("rnew" in src.m)
    }

    @Test fun anEditedRoutineGetsANewStampAndACalendarLinkDoesNot() {
        val old = Routines.withUids(listOf(Routine(1, "Brief", "Brief me", 7, 0, 31)))
        assertTrue(old[0].uid.startsWith("r") && SyncKinds.ID_RE.matches(old[0].uid))
        val same = Routines.stamped(old, listOf(old[0].copy(eventId = 9)), 100)
        assertEquals(old[0].u, same[0].u)
        val moved = Routines.stamped(old, listOf(old[0].copy(hour = 8)), 100)
        assertEquals(100L, moved[0].u)
        assertEquals(old[0].uid, moved[0].uid)
        val added = Routines.stamped(old, old + Routine(2, "Walk", "Walk", 18, 0, 127), 200)
        assertNotEquals("", added[1].uid); assertEquals(200L, added[1].u)
    }
}
