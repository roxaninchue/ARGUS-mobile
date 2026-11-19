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
        Glance.Inputs(now, zone, next, task, ShelfSource.of(sh), h24 = true)
    private fun all(i: Glance.Inputs, p: Glance.Prefs) = Glance.window(i, p, 0, 1000)

    @Test fun defaultOrderClockReminderTaskThenCardsNewestFirst() {
        val items = all(inputs(), Glance.Prefs())
        assertEquals(listOf("clock", "reminder", "task", "stage:music3", "stage:timer1"), items.map { it.id })
        assertEquals("20:18", items[0].title)
        assertEquals("Thursday 19 November", items[0].sub)
        assertEquals("in 25 min", items[1].sub)
        assertEquals("music", items[3].icon)
        assertTrue(items[3].sub.startsWith("Music"))
    }

    @Test fun onlyWhatIsThereAndAtAGlance() {
        // no task, a reminder over a day off, an empty shelf: just the clock
        val far = all(inputs(Glance.Next("Dentist", now + 30 * 60 * min), null, emptyList()), Glance.Prefs())
        assertEquals(listOf("clock"), far.map { it.id })
        // past reminders and blank tasks don't show
        assertEquals(listOf("clock"), all(inputs(Glance.Next("Old", now - min), "  ", emptyList()), Glance.Prefs()).map { it.id })
        assertEquals("tomorrow 07:00", Glance.whenText(now, ZonedDateTime.of(2026, 11, 20, 7, 0, 0, 0, zone).toInstant().toEpochMilli(), zone, true))
        assertEquals("22:00", Glance.whenText(now, ZonedDateTime.of(2026, 11, 19, 22, 0, 0, 0, zone).toInstant().toEpochMilli(), zone, true))
        assertEquals("8:18", Glance.clockText(now, zone, h24 = false))
    }

    @Test fun pinsGoFirstAfterTheClockAndTheLayerIsEndless() {
        val p = Glance.Prefs(pins = listOf("stage:timer1", "task"))
        val items = all(inputs(), p)
        assertEquals(listOf("clock", "stage:timer1", "task", "reminder", "stage:music3"), items.map { it.id })
        assertTrue(items[1].pinned && items[2].pinned && !items[3].pinned)
        // owner, round 2: no cap, every card shows (read a window at a time)
        val many = (1..300).map { ShelfCard("note$it", StageKind.NOTE, emptyMap(), "n$it", now - it * min) }
        assertEquals(303, Glance.total(inputs(sh = many), Glance.Prefs()))
        assertEquals(303, all(inputs(sh = many), Glance.Prefs()).size)
        // a pin whose item isn't there now is skipped, not shown empty
        assertEquals(listOf("clock"), all(inputs(null, null, emptyList()), Glance.Prefs(pins = listOf("reminder"))).map { it.id })
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
        assertFalse(all(inputs(), o.prefs).any { it.id == "clock" })
        // pinning a hidden built-in brings it back
        val back = Glance.act(Glance.Op.PIN, "clock", inputs(), o.prefs)
        assertTrue(back.prefs.hidden.isEmpty()); assertEquals("clock", all(inputs(), back.prefs).first().id)
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
        assertTrue(d.getJSONObject("parameters").getJSONObject("properties").has("offset"))
        assertTrue("small schema", GlanceTool.def().toString().length < 1200)
        assertEquals(Glance.Op.PIN to "clock", GlanceTool.parse(JSONObject().put("action", "pin").put("id", " clock ")).first)
        assertEquals(Glance.Op.REMOVE, GlanceTool.parse(JSONObject().put("action", "hide")).first?.first)
        val bad = GlanceTool.parse(JSONObject().put("action", "explode"))
        assertNull(bad.first); assertTrue(bad.second.contains("list | pin | unpin | remove | restore"))
        assertEquals("Checking the glance layer", GlanceTool.step(JSONObject().put("action", "list")))
    }

    /** Owner, round 2: the layer is read a window at a time, newest first, clock first; pins are never repeated below. */
    @Test fun windowsPageThroughTheWholeLayer() {
        val many = (1..50).map { ShelfCard("note$it", StageKind.NOTE, emptyMap(), "n$it", now - it * min) }
        val p = Glance.Prefs(pins = listOf("stage:note30"))
        val i = inputs(sh = many)
        val whole = all(i, p).map { it.id }
        assertEquals(listOf("clock", "stage:note30", "reminder", "task", "stage:note1", "stage:note2"), whole.take(6))
        assertEquals(1, whole.count { it == "stage:note30" })
        assertEquals(Glance.total(i, p), whole.size)
        // pages of 7 stitched together are the whole layer, in order
        val paged = (0 until whole.size step 7).flatMap { Glance.window(i, p, it, 7).map { x -> x.id } }
        assertEquals(whole, paged)
        assertTrue(Glance.window(i, p, whole.size, 7).isEmpty())
        // the UI's pager: load more near the end of what is loaded, never past the total
        assertTrue(Glance.needMore(lastVisible = 16, loaded = 20, total = 53))
        assertFalse(Glance.needMore(lastVisible = 5, loaded = 20, total = 53))
        assertFalse(Glance.needMore(lastVisible = 52, loaded = 53, total = 53))
    }

    @Test fun toolListPagesAndStaysSmall() {
        val many = (1..100).map { ShelfCard("note$it", StageKind.NOTE, emptyMap(), "note number $it", now - it * min) }
        val i = inputs(sh = many)
        val first = Glance.act(Glance.Op.LIST, "", i, Glance.Prefs()).text
        assertTrue(first, first.contains("clock = 20:18") && first.contains("Items 1\u20138 of 103; more with offset=8."))
        assertFalse(first, first.contains("stage:note6 "))
        assertTrue("default page stays small", first.length < 700)
        val next = Glance.act(Glance.Op.LIST, "", i, Glance.Prefs(), offset = 8, limit = 5).text
        assertTrue(next, next.contains("stage:note6 = note number 6") && next.contains("Items 9\u201313 of 103; more with offset=13."))
        val last = Glance.act(Glance.Op.LIST, "", i, Glance.Prefs(), offset = 100, limit = 20).text
        assertTrue(last, last.contains("Items 101\u2013103 of 103.") && !last.contains("more with"))
        assertTrue(Glance.act(Glance.Op.LIST, "", i, Glance.Prefs(), offset = 500).text.contains("nothing at offset 500"))
        // a card deep in the shelf is still reached by id or title
        assertEquals("stage:note97", Glance.act(Glance.Op.PIN, "note97", i, Glance.Prefs()).prefs.pins.single())
        assertEquals("stage:note88", Glance.act(Glance.Op.PIN, "Note Number 88", i, Glance.Prefs()).prefs.pins.single())
        // tool args: offset / limit, small default, capped
        assertEquals(0 to Glance.LIST_DEFAULT, GlanceTool.paging(JSONObject().put("action", "list")))
        assertEquals(16 to 4, GlanceTool.paging(JSONObject().put("offset", 16).put("limit", 4)))
        assertEquals(0 to Glance.LIST_MAX, GlanceTool.paging(JSONObject().put("offset", -3).put("limit", 999)))
        assertEquals(0 to Glance.LIST_DEFAULT, GlanceTool.paging(JSONObject().put("limit", 0)))
        assertTrue(Glance.LIST_DEFAULT <= 10)
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
        assertTrue("whatever leaves the Stage is shelved", "if (gone.isNotEmpty()) shelfAdd(before.items.filter { it.id in gone }.map { StageShelf.card(it, System.currentTimeMillis()) })" in store)
        assertTrue("in its own database, unbounded", "ShelfDb(AndroidSql(f.path))" in store)
        assertTrue("the tool pages", "val (offset, limit) = GlanceTool.paging(args)" in src("glance/GlanceStore.kt"))
    }
}
