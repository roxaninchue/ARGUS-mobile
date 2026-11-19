package com.argus.assistant.home

import com.argus.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-11-18 (first-run onboarding): the life_todo tool. ARGUS adds, ticks off, unticks, removes and lists items on the
 * to-do lists in Life (the board's to-do panels, [HomeTodo]) one item at a time, instead of resending a whole list with
 * home_widget_update. Pure over a [HomeStore] (LifeTodoTest runs it on sqlite-jdbc); [HomeTools.run] dispatches it, so
 * it rides the same path, group and policy as the other board tools.
 */
object LifeTodo {
    const val TOOL = "life_todo"
    /** The list made when none exists yet. */
    const val DEFAULT_TITLE = "To-do"

    private fun s(d: String) = JSONObject().put("type", "string").put("description", d)

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", TOOL)
        .put("description", "The user's to-do lists in the Life tab. add puts items on a list (making a list called \u201c$DEFAULT_TITLE\u201d " +
            "when there is none), done ticks one off, undo unticks it, remove takes it off, list shows every list with its items. " +
            "setup puts the \u201cSet up ${BrandName.UPPER}\u201d checklist at the top of Life (every first-run setup step: calendar, Google, " +
            "notifications, wake word, default assistant, Desktop, phone control; a tap on a step opens its screen, steps the phone " +
            "already has are ticked); it is made once, and when it is there setup only updates it. Tick a setup step off when the user has done it.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", s("add | done | undo | remove | list | setup"))
            .put("items", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "add: the items' text"))
            .put("item", s("done / undo / remove: the item's text, part of it, or its number on the list (1 = first)"))
            .put("list_title", s("Which list (its title, e.g. Groceries). Leave out for the first to-do list. add makes a new list with this title when none has it.")))
            .put("required", JSONArray(listOf("action")))))

    fun step(args: JSONObject): String = when (args.optString("action").trim().lowercase()) {
        "add" -> "Adding to your to-do list"
        "done" -> "Ticking off \u201c${args.optString("item").take(40)}\u201d"
        "undo" -> "Unticking \u201c${args.optString("item").take(40)}\u201d"
        "remove" -> "Taking \u201c${args.optString("item").take(40)}\u201d off the list"
        "setup" -> "Putting the setup checklist in Life"
        else -> "Checking your to-do lists"
    }

    /** The to-do panels, top of Life first; hidden ones count (a hidden list still holds the user's items). */
    fun lists(store: HomeStore): List<Widget> = store.all().filter { it.type == WidgetTypes.TODO }

    /**
     * The list [title] names (any case), else the first one; null when there is none (or none with that title). With no
     * title the "Set up ARGUS" checklist is skipped: the user's own items never land among the setup steps.
     */
    fun pick(lists: List<Widget>, title: String): Widget? {
        val t = title.trim().lowercase()
        return if (t.isEmpty()) lists.firstOrNull { !com.argus.assistant.setup.SetupChecklist.isChecklist(it.cfg()) }
        else lists.firstOrNull { it.title.trim().lowercase() == t }
    }

    /**
     * setup: ARGUS puts the first-run checklist in Life ([com.argus.assistant.setup.SetupChecklist]): made at the top with
     * every step (those [done] reports ticked) when there is none, else the one there is ticked in step with the phone.
     * Asked for explicitly, so it is made even when an earlier one was removed; [onSeeded] records that it was made.
     */
    private fun setup(store: HomeStore, done: Map<String, Boolean>, now: Long, onChange: (String) -> Unit, onSeeded: () -> Unit): String {
        val r = com.argus.assistant.setup.SetupChecklist.applyTo(store, done, seededBefore = false, now = now)
        if (r.added) onSeeded()
        r.id?.let(onChange)
        val w = r.id?.let { store.get(it) } ?: return "Couldn't put the setup checklist in Life."
        return (if (r.added) "Put the \u201c${w.title}\u201d checklist at the top of Life." else "The \u201c${w.title}\u201d checklist is already in Life; ticked what the phone reports done.") +
            " The user taps a step to open its screen.\n" + lines(w)
    }

    private fun texts(a: JSONObject): List<String> {
        val out = ArrayList<String>()
        when (val v = a.opt("items")) {
            is JSONArray -> for (i in 0 until v.length()) v.opt(i)?.let { x -> (if (x is JSONObject) x.optString("t").ifBlank { x.optString("text") } else x.toString()).let(out::add) }
            is String -> out += v.split('\n')
        }
        a.optString("item").takeIf { it.isNotBlank() }?.let { out += it }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun lines(w: Widget): String {
        val items = HomeTodo.items(w.cfg())
        if (items.isEmpty()) return "\u201c${w.title}\u201d (id ${w.id}): empty."
        return "\u201c${w.title}\u201d (id ${w.id}), ${items.count { !it.done }} left:\n" +
            items.withIndex().joinToString("\n") { (i, it) -> "  ${i + 1}. [${if (it.done) "x" else " "}] ${it.text}" }
    }

    private fun save(store: HomeStore, w: Widget, items: List<HomeTodo.Item>, now: Long) {
        val c = w.cfg(); c.put("items", HomeTodo.toJson(items))
        store.update(w.copy(config = c.toString(), hidden = false), now)
    }

    /**
     * [setupDone] is what the phone reports for each setup step (FirstRun.probe on a phone; nothing in tests unless given);
     * [onSeeded] runs when setup made the checklist.
     */
    fun run(store: HomeStore, args: JSONObject, now: Long = System.currentTimeMillis(),
            setupDone: () -> Map<String, Boolean> = { emptyMap() }, onSeeded: () -> Unit = {}, onChange: (String) -> Unit = {}): String {
        val action = args.optString("action").trim().lowercase()
        val title = args.optString("list_title").trim().take(60)
        if (action == "setup") return setup(store, setupDone(), now, onChange, onSeeded)
        val all = lists(store)
        if (action == "list") return if (all.isEmpty()) "There are no to-do lists in Life yet. add makes one." else all.joinToString("\n") { lines(it) }
        if (action == "add") {
            val add = texts(args)
            if (add.isEmpty()) return "Nothing to add: give the items' text in items."
            val w = pick(all, title) ?: store.add(Widget("", WidgetTypes.TODO, title.ifBlank { DEFAULT_TITLE }, "To-do", JSONObject().toString()), now, atEnd = false)
            val before = HomeTodo.items(w.cfg())
            val after = HomeTodo.add(before, add)
            save(store, w, after, now)
            onChange(w.id)
            val added = after.size - before.size
            val skipped = add.size - added
            return "Added $added item(s) to \u201c${w.title}\u201d in Life" + (if (skipped > 0) " ($skipped already there or over the ${HomeTodo.MAX}-item limit)" else "") + ".\n" + lines(store.get(w.id) ?: w)
        }
        if (action !in setOf("done", "undo", "remove")) return "Unknown action \u201c$action\u201d. Use add, done, undo, remove, list or setup."
        val ref = args.optString("item").trim()
        if (ref.isEmpty()) return "Say which item in item (its text or its number)."
        // Without a title: the first list that has the item, so "tick off milk" finds it wherever it is.
        val candidates = if (title.isNotEmpty()) listOfNotNull(pick(all, title)) else all
        if (candidates.isEmpty()) return if (title.isNotEmpty()) "No to-do list called \u201c$title\u201d. Lists: ${all.joinToString { it.title }.ifBlank { "none" }}." else "There are no to-do lists in Life yet."
        for (w in candidates) {
            val items = HomeTodo.items(w.cfg())
            val i = HomeTodo.find(items, ref)
            if (i < 0) continue
            val text = items[i].text
            val next = when (action) { "done" -> HomeTodo.set(items, i, true); "undo" -> HomeTodo.set(items, i, false); else -> HomeTodo.remove(items, i) }
            save(store, w, next, now)
            onChange(w.id)
            return when (action) {
                "done" -> "Ticked off \u201c$text\u201d on \u201c${w.title}\u201d."
                "undo" -> "\u201c$text\u201d on \u201c${w.title}\u201d is open again."
                else -> "Removed \u201c$text\u201d from \u201c${w.title}\u201d."
            } + " ${next.count { !it.done }} left."
        }
        return "No single item matches \u201c$ref\u201d. The lists:\n" + candidates.joinToString("\n") { lines(it) }
    }
}
