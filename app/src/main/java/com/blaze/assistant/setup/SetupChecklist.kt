package com.blaze.assistant.setup

import com.blaze.assistant.BrandName
import com.blaze.assistant.home.HomeStore
import com.blaze.assistant.home.HomeTodo
import com.blaze.assistant.home.Widget
import com.blaze.assistant.home.WidgetTypes
import org.json.JSONObject

/**
 * 2026-11-18 first run: the "Set up ARGUS" checklist ARGUS puts at the top of Life after sign-in. It is an ordinary Life
 * to-do panel ([HomeTodo], so a tap ticks it and life_todo can tick it), marked `checklist: setup` in its config, whose
 * items carry a key (what the app checks) and a target (what a tap on the row opens, a
 * [com.blaze.assistant.agent.SelfSetup.open] target). Pure: [FirstRun] feeds it what the phone reports.
 *
 * The steps are the ones this app can really check and open (SetupGate.deps, SelfSetup.audit, the Google connector);
 * Linux, tools and voice models are not here because they download by themselves (SetupPlan).
 */
object SetupChecklist {
    const val MARK = "setup"
    const val CONFIG_KEY = "checklist"
    /** Set once the list was added, so a list the user removed is never put back. */
    const val PREF_SEEDED = "setup_checklist_seeded"
    val TITLE = "Set up ${BrandName.UPPER}"
    const val CATEGORY = "Get started"

    data class Step(val key: String, val text: String, val open: String)

    val STEPS = listOf(
        Step("calendar", "Connect your calendar", "settings connections"),
        Step("google", "Connect your Google account for email", "settings connections"),
        Step("notifications", "Allow notifications", "notifications"),
        Step("wake_word", "Turn on \u201cHey ${BrandName.NAME}\u201d", "settings voice"),
        Step("assistant", "Make ${BrandName.UPPER} your default assistant", "assistant"),
        Step("desktop", "Pair with ${BrandName.NAME} Desktop", "settings desktop"),
    )

    fun step(key: String): Step? = STEPS.firstOrNull { it.key == key }

    /** What the phone says right now. Each value is what the app can check; nothing is guessed. */
    data class Probe(
        val calendarPermission: Boolean = false,
        val googleConnected: Boolean = false,
        val notifications: Boolean = false,
        val wakeWord: Boolean = false,
        val defaultAssistant: Boolean = false,
        val desktopPaired: Boolean = false,
    )

    /** Each step's key → done on this phone. The calendar counts as connected through the phone's calendar or Google. */
    fun detected(p: Probe): Map<String, Boolean> = mapOf(
        "calendar" to (p.calendarPermission || p.googleConnected),
        "google" to p.googleConnected,
        "notifications" to p.notifications,
        "wake_word" to p.wakeWord,
        "assistant" to p.defaultAssistant,
        "desktop" to p.desktopPaired,
    )

    /** The list as first added: every step, the ones already done ticked. */
    fun fresh(done: Map<String, Boolean>): List<HomeTodo.Item> = STEPS.map { HomeTodo.Item(it.text, done[it.key] == true, it.key, it.open) }

    /**
     * Later checks only tick: a step the phone now reports done is ticked, nothing is unticked (the user's own tick
     * stays), and items the user or ARGUS added, renamed or removed stay as they are. Running it twice changes nothing.
     */
    fun sync(items: List<HomeTodo.Item>, done: Map<String, Boolean>): List<HomeTodo.Item> =
        items.map { i -> if (!i.done && i.key.isNotBlank() && done[i.key] == true) i.copy(done = true) else i }

    fun isChecklist(cfg: JSONObject): Boolean = cfg.optString(CONFIG_KEY) == MARK

    fun config(items: List<HomeTodo.Item>): JSONObject = JSONObject().put(CONFIG_KEY, MARK).put("items", HomeTodo.toJson(items))

    enum class Plan { ADD, SYNC, NONE }

    /** A list on Life is kept in step; with none, it is added once (never again after the user removed it). */
    fun plan(hasList: Boolean, seededBefore: Boolean): Plan = when {
        hasList -> Plan.SYNC
        seededBefore -> Plan.NONE
        else -> Plan.ADD
    }

    /** The checklist panel on the board, if it is there (hidden or not). */
    fun find(store: HomeStore): Widget? = store.all().firstOrNull { it.type == WidgetTypes.TODO && isChecklist(it.cfg()) }

    /** What [applyTo] did: the list's items now ([items] empty when there is no list), and whether it was just added. */
    data class Result(val items: List<HomeTodo.Item>, val added: Boolean, val id: String?)

    /**
     * Runs [plan] on [store]: adds the list at the top of Life (first run), or ticks what [done] reports on the list that is
     * there; writes only when something changed. The caller saves [PREF_SEEDED] when [Result.added].
     */
    fun applyTo(store: HomeStore, done: Map<String, Boolean>, seededBefore: Boolean, now: Long = System.currentTimeMillis()): Result {
        val list = find(store)
        return when (plan(list != null, seededBefore)) {
            Plan.ADD -> {
                val items = fresh(done)
                val w = store.add(Widget("", WidgetTypes.TODO, TITLE, CATEGORY, config(items).toString()), now, atEnd = false)
                Result(items, true, w.id)
            }
            Plan.SYNC -> {
                val w = list!!
                val items = HomeTodo.items(w.cfg())
                val next = sync(items, done)
                if (next != items) { val c = w.cfg(); c.put("items", HomeTodo.toJson(next)); store.update(w.copy(config = c.toString()), now) }
                Result(next, false, w.id)
            }
            Plan.NONE -> Result(emptyList(), false, null)
        }
    }

    /** The steps still open, in order (for ARGUS's introduction). */
    fun open(items: List<HomeTodo.Item>): List<String> = items.filter { !it.done }.map { it.text }
}
