package com.blaze.assistant.home

import com.blaze.assistant.store.JdbcSql
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 2026-11-18: life_todo adds, ticks, unticks, removes and lists Life to-do items one at a time (HomeStore on sqlite-jdbc). */
class LifeTodoTest {
    private lateinit var dir: File
    private val open = ArrayList<JdbcSql>()

    @Before fun setUp() { dir = Files.createTempDirectory("lifetodo").toFile() }
    @After fun tearDown() { open.forEach { try { it.close() } catch (_: Exception) { } }; dir.deleteRecursively() }

    private fun store(): HomeStore = HomeStore(JdbcSql("jdbc:sqlite:" + File(dir, "blaze.db").path).also { open += it })
    private fun run(s: HomeStore, vararg kv: Pair<String, Any>): String = HomeTools.run(s, LifeTodo.TOOL, JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } })
    private fun items(s: HomeStore, title: String) = HomeTodo.items(s.all().first { it.title == title }.cfg())

    @Test fun addMakesAListOnceThenAddsToIt() {
        val s = store()
        s.add(Widget("", WidgetTypes.NEWS, "Tech", "News"))
        val changed = ArrayList<String>()
        val r = HomeTools.run(s, LifeTodo.TOOL, JSONObject().put("action", "add").put("items", JSONArray(listOf("Buy milk", "Pay rent")))) { changed += it }
        assertTrue(r, r.startsWith("Added 2 item(s) to \u201cTo-do\u201d"))
        assertEquals("the new list goes to the top of Life", WidgetTypes.TODO, s.all().first().type)
        assertEquals(1, changed.size)
        run(s, "action" to "add", "items" to JSONArray(listOf("buy milk", "Call mum")))
        assertEquals(listOf("Buy milk", "Pay rent", "Call mum"), items(s, "To-do").map { it.text })
        assertEquals("one list, not two", 1, LifeTodo.lists(s).size)
        // a named list that doesn't exist yet is made
        run(s, "action" to "add", "item" to "Eggs", "list_title" to "Groceries")
        assertEquals(listOf("Eggs"), items(s, "Groceries").map { it.text })
        assertTrue(run(s, "action" to "add").startsWith("Nothing to add"))
    }

    @Test fun tickUntickRemoveByTextOrNumberAcrossLists() {
        val s = store()
        run(s, "action" to "add", "items" to JSONArray(listOf("Buy milk", "Pay rent")))
        run(s, "action" to "add", "items" to JSONArray(listOf("Eggs")), "list_title" to "Groceries")
        assertTrue(run(s, "action" to "done", "item" to "rent").startsWith("Ticked off \u201cPay rent\u201d"))
        assertTrue(items(s, "To-do")[1].done)
        assertTrue("found on whichever list has it", run(s, "action" to "done", "item" to "eggs").contains("Groceries"))
        assertTrue(run(s, "action" to "undo", "item" to "2").contains("open again"))
        assertFalse(items(s, "To-do")[1].done)
        assertTrue(run(s, "action" to "remove", "item" to "1", "list_title" to "to-do").startsWith("Removed \u201cBuy milk\u201d"))
        assertEquals(listOf("Pay rent"), items(s, "To-do").map { it.text })
        assertTrue(run(s, "action" to "done", "item" to "nothing like this").startsWith("No single item matches"))
        assertTrue(run(s, "action" to "done", "item" to "x", "list_title" to "Nope").startsWith("No to-do list called"))
        assertTrue(run(s, "action" to "fly").startsWith("Unknown action"))
        val list = run(s, "action" to "list")
        assertTrue(list, list.contains("\u201cTo-do\u201d") && list.contains("1. [ ] Pay rent") && list.contains("1. [x] Eggs"))
    }

    @Test fun theChecklistKeepsItsKeysWhenTicked() {
        val s = store()
        val r = com.blaze.assistant.setup.SetupChecklist.applyTo(s, emptyMap(), seededBefore = false, now = 1)
        assertTrue(r.added)
        run(s, "action" to "done", "item" to "notifications")
        val after = items(s, com.blaze.assistant.setup.SetupChecklist.TITLE)
        val n = after.first { it.key == "notifications" }
        assertTrue(n.done); assertEquals("notifications", n.open)
        assertTrue("still the checklist", com.blaze.assistant.setup.SetupChecklist.isChecklist(s.all().first().cfg()))
    }

    @Test fun argusPutsTheSetupChecklistInLifeWithSetup() {
        val s = store()
        s.add(Widget("", WidgetTypes.NEWS, "Tech", "News"), now = 1)
        val CL = com.blaze.assistant.setup.SetupChecklist
        var seeded = 0
        val changed = ArrayList<String>()
        val r = HomeTools.run(s, LifeTodo.TOOL, JSONObject().put("action", "setup"), now = 2, onChange = { changed += it },
            setupDone = { mapOf("notifications" to true) }, onSeeded = { seeded++ })
        assertTrue(r, r.startsWith("Put the \u201cSet up ARGUS\u201d checklist at the top of Life."))
        val top = s.all().first()
        assertTrue("at the top of Life, marked as the checklist", CL.isChecklist(top.cfg()))
        assertEquals(CL.TITLE, top.title)
        val items = HomeTodo.items(top.cfg())
        assertEquals("every setup step, keyed and opening its screen", CL.STEPS.map { it.key }, items.map { it.key })
        assertTrue(items.all { it.open.isNotBlank() })
        assertEquals("what the phone already has is ticked", listOf("notifications"), items.filter { it.done }.map { it.key })
        assertEquals(1, seeded); assertEquals(listOf(top.id), changed)
        // again: no second list, only ticks what the phone now reports
        val again = HomeTools.run(s, LifeTodo.TOOL, JSONObject().put("action", "setup"), now = 3,
            setupDone = { mapOf("notifications" to true, "assistant" to true) }, onSeeded = { seeded++ })
        assertTrue(again, again.contains("already in Life"))
        assertEquals(1, seeded)
        assertEquals(1, s.all().count { CL.isChecklist(it.cfg()) })
        assertTrue(HomeTodo.items(s.get(top.id)!!.cfg()).first { it.key == "assistant" }.done)
        assertEquals("Putting the setup checklist in Life", HomeTools.step(LifeTodo.TOOL, JSONObject().put("action", "setup")))
        assertTrue(LifeTodo.tool().toString().contains("setup"))
    }

    @Test fun plainAddsNeverLandOnTheSetupChecklist() {
        val s = store()
        com.blaze.assistant.setup.SetupChecklist.applyTo(s, emptyMap(), seededBefore = false, now = 1)
        val r = run(s, "action" to "add", "item" to "Buy milk")
        assertTrue(r, r.startsWith("Added 1 item(s) to \u201cTo-do\u201d"))
        assertEquals(listOf("Buy milk"), items(s, "To-do").map { it.text })
        assertTrue(items(s, com.blaze.assistant.setup.SetupChecklist.TITLE).none { it.text == "Buy milk" })
        // naming the checklist still reaches it
        run(s, "action" to "add", "item" to "Try a voice chat", "list_title" to "Set up ARGUS")
        assertTrue(items(s, com.blaze.assistant.setup.SetupChecklist.TITLE).any { it.text == "Try a voice chat" })
    }

    @Test fun theToolIsDeclaredAndInTheBoardGroup() {
        assertTrue(LifeTodo.TOOL in HomeTools.NAMES)
        assertTrue(HomeTools.tools().any { it.getJSONObject("function").getString("name") == LifeTodo.TOOL })
        fun c(a: String) = com.blaze.assistant.policy.ToolRegistry.classOf(LifeTodo.TOOL) { k -> if (k == "action") a else "" }
        assertEquals(com.blaze.assistant.team.ActionClass.READ, c("list"))
        assertEquals(com.blaze.assistant.team.ActionClass.WRITE_LOCAL, c("add"))
        assertEquals(com.blaze.assistant.team.ActionClass.WRITE_LOCAL, c("remove"))
        assertTrue(com.blaze.assistant.agent.ToolRouter.groups.first { it.id == "home" }.names(LifeTodo.TOOL))
    }
}
