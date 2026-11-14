package com.blaze.assistant.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
