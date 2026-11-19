package com.blaze.assistant.glance

import com.blaze.assistant.stage.ShelfCard
import com.blaze.assistant.stage.StageKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 2026-11-19 (owner spec): what the glance layer shows, and the `glance` tool's actions. */
class GlanceTest {
    private val zone = ZoneId.of("Africa/Lusaka")
    private val now = ZonedDateTime.of(2026, 11, 19, 20, 18, 0, 0, zone).toInstant().toEpochMilli()
    private val min = 60_000L
    private val shelf = listOf(
        ShelfCard("music3", StageKind.MUSIC, mapOf("query" to "Lo-fi"), "Lo-fi", now - 5 * min),
        ShelfCard("timer1", StageKind.TIMER, mapOf("minutes" to "25"), "Study", now - 60 * min),
    )
    private fun inputs(next: Glance.Next? = Glance.Next("Call mum", now + 25 * min), task: String? = "Booking the flight", sh: List<ShelfCard> = shelf) =
        Glance.Inputs(now, zone, next, task, sh, h24 = true)

    @Test fun defaultOrderClockReminderTaskThenCardsNewestFirst() {
        val items = Glance.select(inputs(), Glance.Prefs())
        assertEquals(listOf("clock", "reminder", "task", "stage:music3", "stage:timer1"), items.map { it.id })
        assertEquals("20:18", items[0].title)
        assertEquals("Thursday 19 November", items[0].sub)
        assertEquals("in 25 min", items[1].sub)
        assertEquals("music", items[3].icon)
        assertTrue(items[3].sub.startsWith("Music"))
    }

    @Test fun onlyWhatIsThereAndAtAGlance() {
        // no task, a reminder over a day off, an empty shelf: just the clock
        val far = Glance.select(inputs(Glance.Next("Dentist", now + 30 * 60 * min), null, emptyList()), Glance.Prefs())
        assertEquals(listOf("clock"), far.map { it.id })
        // past reminders and blank tasks don't show
        assertEquals(listOf("clock"), Glance.select(inputs(Glance.Next("Old", now - min), "  ", emptyList()), Glance.Prefs()).map { it.id })
        assertEquals("tomorrow 07:00", Glance.whenText(now, ZonedDateTime.of(2026, 11, 20, 7, 0, 0, 0, zone).toInstant().toEpochMilli(), zone, true))
        assertEquals("22:00", Glance.whenText(now, ZonedDateTime.of(2026, 11, 19, 22, 0, 0, 0, zone).toInstant().toEpochMilli(), zone, true))
        assertEquals("8:18", Glance.clockText(now, zone, h24 = false))
    }

    @Test fun pinsGoFirstAfterTheClockAndTheLayerIsCapped() {
        val p = Glance.Prefs(pins = listOf("stage:timer1", "task"))
        val items = Glance.select(inputs(), p)
        assertEquals(listOf("clock", "stage:timer1", "task", "reminder", "stage:music3"), items.map { it.id })
        assertTrue(items[1].pinned && items[2].pinned && !items[3].pinned)
        val many = (1..10).map { ShelfCard("note$it", StageKind.NOTE, emptyMap(), "n$it", now - it * min) }
        assertEquals(Glance.MAX, Glance.select(inputs(sh = many), Glance.Prefs()).size)
        // a pin whose item isn't there now is skipped, not shown empty
        assertEquals(listOf("clock"), Glance.select(inputs(null, null, emptyList()), Glance.Prefs(pins = listOf("reminder"))).map { it.id })
    }

    @Test fun toolPinUnpinAndList() {
        var p = Glance.Prefs()
        var o = Glance.act(Glance.Op.PIN, "music3", inputs(), p)   // bare card id works
        assertTrue(o.changed); p = o.prefs
        assertEquals(listOf("stage:music3"), p.pins)
        o = Glance.act(Glance.Op.PIN, "Study", inputs(), p)   // by title
        p = o.prefs
        assertEquals(listOf("stage:music3", "stage:timer1"), p.pins)
        assertEquals("Study is already pinned.", Glance.act(Glance.Op.PIN, "stage:timer1", inputs(), p).text)
        val list = Glance.act(Glance.Op.LIST, "", inputs(), p).text
        assertTrue(list, list.contains("stage:music3 = Lo-fi") && list.contains("[pinned]") && list.contains("clock = 20:18"))
        o = Glance.act(Glance.Op.UNPIN, "stage:music3", inputs(), p)
        assertEquals(listOf("stage:timer1"), o.prefs.pins)
        assertFalse(Glance.act(Glance.Op.UNPIN, "clock", inputs(), o.prefs).changed)
        // pins are capped
        val full = Glance.Prefs(pins = listOf("clock", "reminder", "task", "stage:timer1"))
        val over = Glance.act(Glance.Op.PIN, "stage:music3", inputs(), full)
        assertFalse(over.changed); assertTrue(over.text.contains("unpin one first"))
    }

    @Test fun toolRemoveHidesBuiltInsAndForgetsCards() {
        var o = Glance.act(Glance.Op.REMOVE, "clock", inputs(), Glance.Prefs())
        assertEquals(setOf("clock"), o.prefs.hidden); assertNull(o.dropShelf)
        assertFalse(Glance.select(inputs(), o.prefs).any { it.id == "clock" })
        // pinning a hidden built-in brings it back
        val back = Glance.act(Glance.Op.PIN, "clock", inputs(), o.prefs)
        assertTrue(back.prefs.hidden.isEmpty()); assertEquals("clock", Glance.select(inputs(), back.prefs).first().id)
        o = Glance.act(Glance.Op.REMOVE, "stage:music3", inputs(), Glance.Prefs(pins = listOf("stage:music3")))
        assertEquals("stage:music3", o.dropShelf); assertTrue(o.prefs.pins.isEmpty()); assertTrue(o.prefs.hidden.isEmpty())
        // unknown ids answer with what is there
        val miss = Glance.act(Glance.Op.REMOVE, "weather", inputs(), Glance.Prefs())
        assertFalse(miss.changed); assertTrue(miss.text.startsWith("Nothing called \u201cweather\u201d"))
    }

    @Test fun toolRestoreOnlyForStageCards() {
        val o = Glance.act(Glance.Op.RESTORE, "timer1", inputs(), Glance.Prefs())
        assertEquals("stage:timer1", o.restore)
        val no = Glance.act(Glance.Op.RESTORE, "clock", inputs(), Glance.Prefs())
        assertNull(no.restore); assertTrue(no.text.startsWith("Only Stage cards"))
    }

    @Test fun prefsRoundTripAndSanitise() {
        val p = Glance.Prefs(listOf("stage:a", "clock"), setOf("task"))
        assertEquals(p, Glance.Prefs.from(p.toJson().toString()))
        assertEquals(Glance.Prefs(), Glance.Prefs.from("garbage"))
        // hidden only ever holds built-ins; pins are de-duplicated and capped
        val odd = Glance.Prefs.from("""{"pins":["a","a","b","c","d","e"],"hidden":["stage:x","reminder"]}""")
        assertEquals(listOf("a", "b", "c", "d"), odd.pins); assertEquals(setOf("reminder"), odd.hidden)
    }

    @Test fun oneSmallToolWithAFewActions() {
        val d = GlanceTool.def().getJSONObject("function")
        assertEquals("glance", d.getString("name"))
        val actions = d.getJSONObject("parameters").getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
        assertEquals(listOf("list", "pin", "unpin", "remove", "restore"), (0 until actions.length()).map { actions.getString(it) })
        assertTrue("small schema", GlanceTool.def().toString().length < 1200)
        assertEquals(Glance.Op.PIN to "clock", GlanceTool.parse(JSONObject().put("action", "pin").put("id", " clock ")).first)
        assertEquals(Glance.Op.REMOVE, GlanceTool.parse(JSONObject().put("action", "hide")).first?.first)
        val bad = GlanceTool.parse(JSONObject().put("action", "explode"))
        assertNull(bad.first); assertTrue(bad.second.contains("list | pin | unpin | remove | restore"))
        assertEquals("Checking the glance layer", GlanceTool.step(JSONObject().put("action", "list")))
    }

    /** Source scan: ARGUS reaches the tool through the agent loop, the policy registry and the Stage tool group. */
    @Test fun wiredIntoTheAgent() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        fun src(p: String) = root.walkTopDown().first { it.isFile && it.path.endsWith(p) }.readText()
        val agent = src("agent/Agent.kt")
        assertTrue("t.put(GlanceTool.def())" in agent)
        assertTrue("name == GlanceTool.NAME -> { add(Role.STEP, GlanceTool.step(args)); GlanceStore.run(app, args) }" in agent)
        assertTrue("s.state.value.items.isNotEmpty() || s.shelf.value.isNotEmpty() }) add(\"stage\")" in agent)
        assertTrue("one(\"glance\", d(WRITE_LOCAL, \"list\" to READ))" in src("policy/ActionClass.kt"))
        assertTrue("{ it.startsWith(\"stage_\") || it == \"glance\" }" in src("agent/ToolRouter.kt"))
        val store = src("stage/StageStore.kt")
        assertTrue("whatever leaves the Stage is shelved", "_shelf.value = StageShelf.took(_shelf.value, before.items.filter { it.id in gone }" in store)
    }
}
