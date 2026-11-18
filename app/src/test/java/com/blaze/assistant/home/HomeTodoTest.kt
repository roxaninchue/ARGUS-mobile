package com.blaze.assistant.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 2026-11-14: "create a to-do list on my dashboard" puts a checklist on Life. */
class HomeTodoTest {
    @Test fun aTodoAskNamesTheTodoType() {
        for (t in listOf("todo", "to do", "To-Do".replace('-', ' '), "checklist", "todo list", "tasks")) assertEquals(t, WidgetTypes.TODO, WidgetTypes.norm(t))
        assertEquals(WidgetTypes.NEWS, WidgetTypes.norm("headlines"))
        assertEquals("To-do list", WidgetTypes.label(WidgetTypes.TODO))
    }

    @Test fun itemsTickAndCount() {
        val cfg = JSONObject("""{"items":["Buy milk", {"t":"Call mum","done":true}, "", 5]}""")
        val items = HomeTodo.items(cfg)
        assertEquals(listOf(HomeTodo.Item("Buy milk", false), HomeTodo.Item("Call mum", true)), items)
        val ticked = HomeTodo.toggle(items, 0)
        assertTrue(ticked.all { it.done })
        val back = HomeTodo.items(JSONObject().put("items", HomeTodo.toJson(ticked)))
        assertEquals(ticked, back)
        val w = Widget("w1", WidgetTypes.TODO, "Groceries", "To-do", cfg.toString())
        val d = HomeTodo.data(w)
        assertEquals("1 left", d.value)
        assertEquals(listOf("", "done"), d.lines.map { it.note })
        assertTrue(HomeTodo.data(Widget("w2", WidgetTypes.TODO, "Empty", "To-do")).change.isNotBlank())
    }

    /** 2026-11-18: one item at a time (life_todo) and the first-run checklist's keys and targets survive every edit. */
    @Test fun addFindTickRemoveOneAtATime() {
        val l0 = listOf(HomeTodo.Item("Buy milk", false), HomeTodo.Item("Call mum", true))
        val l1 = HomeTodo.add(l0, listOf("  Pay rent ", "buy MILK", "", "Book flights"))
        assertEquals(listOf("Buy milk", "Call mum", "Pay rent", "Book flights"), l1.map { it.text })
        assertEquals("adding the same again changes nothing", l1, HomeTodo.add(l1, listOf("pay rent")))
        assertEquals(HomeTodo.MAX, HomeTodo.add(emptyList(), (1..80).map { "item $it" }).size)
        assertEquals(2, HomeTodo.find(l1, "pay rent"))
        assertEquals(0, HomeTodo.find(l1, "1"))
        assertEquals(3, HomeTodo.find(l1, "#4"))
        assertEquals(-1, HomeTodo.find(l1, "9"))
        assertEquals("a part only one item has", 3, HomeTodo.find(l1, "flights"))
        assertEquals("a part several items have", -1, HomeTodo.find(l1, "l"))
        assertEquals(-1, HomeTodo.find(l1, "   "))
        assertTrue(HomeTodo.set(l1, 2, true)[2].done)
        assertFalse(HomeTodo.set(l1, 1, false)[1].done)
        assertEquals(listOf("Buy milk", "Call mum", "Book flights"), HomeTodo.remove(l1, 2).map { it.text })
        val keyed = listOf(HomeTodo.Item("Allow notifications", false, "notifications", "notifications"))
        val back = HomeTodo.items(JSONObject().put("items", HomeTodo.toJson(HomeTodo.toggle(keyed, 0))))
        assertEquals(HomeTodo.Item("Allow notifications", true, "notifications", "notifications"), back[0])
        assertEquals("found by its key too", 0, HomeTodo.find(keyed, "notifications"))
        // an item without a key or target is saved as before ({t, done} only)
        assertEquals(setOf("t", "done"), HomeTodo.toJson(listOf(HomeTodo.Item("x", false))).getJSONObject(0).keySet())
    }
}
