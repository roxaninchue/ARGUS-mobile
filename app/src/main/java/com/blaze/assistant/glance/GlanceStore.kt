package com.blaze.assistant.glance

import android.content.Context
import com.blaze.assistant.agent.BgAgents
import com.blaze.assistant.agent.Processes
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.routine.Routines
import com.blaze.assistant.stage.StageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId

/**
 * 2026-11-19 (owner spec, feat/notch-glance): the glance layer on the phone. Reads only what the app already holds in
 * memory (Routines, Processes, BgAgents, the Stage's shelf); no network, no calendar query. The prefs (pins, hidden
 * built-ins) are one Settings string. The layer's own buttons and ARGUS's `glance` tool both go through [apply].
 */
object GlanceStore {
    private const val KEY = Glance.PREFS_KEY
    private val _prefs = MutableStateFlow(Glance.Prefs())
    val prefs: StateFlow<Glance.Prefs> = _prefs
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        StageStore.init(ctx)
        if (loaded) return
        loaded = true
        _prefs.value = try { Glance.Prefs.from(Settings.str(KEY, "")) } catch (_: Throwable) { Glance.Prefs() }
        // pinned Stage cards stay on the shelf past its cap (read from memory once loaded; StageStore reads Settings before)
        StageStore.shelfKeep = { _prefs.value.pins.filter { it.startsWith(Glance.STAGE) }.toSet() }
    }

    private fun save(p: Glance.Prefs) { _prefs.value = p; try { Settings.setStr(KEY, p.toJson().toString()) } catch (_: Throwable) { } }

    /** The next reminder or alarm (routines that tell the user, the app's alarms and timers), from memory. */
    private fun next(now: Long): Glance.Next? {
        val c = ArrayList<Glance.Next>()
        try {
            Routines.all.value
                .filter { it.enabled && it.everyMinutes <= 0 && (!it.isCommand || it.notify != "never") }
                .forEach { r -> c += Glance.Next(r.name, Routines.next(r, now)) }
        } catch (_: Throwable) { }
        try {
            Processes.all.value
                .filter { (it.kind == "alarm" || it.kind == "timer") && it.status == "active" && it.due > now }
                .forEach { p -> c += Glance.Next(p.title.ifBlank { p.kind.replaceFirstChar { ch -> ch.uppercase() } }, p.due) }
        } catch (_: Throwable) { }
        return c.filter { it.at > now }.minByOrNull { it.at }
    }

    /** The task in progress: a running helper first, else an active goal. */
    private fun task(): String? {
        try {
            BgAgents.all.value.filter { it.status == BgAgents.RUNNING }
                .maxByOrNull { it.started }?.let { return it.title.ifBlank { it.task } }
        } catch (_: Throwable) { }
        try {
            Processes.all.value.filter { it.kind == "goal" && it.status == "active" }
                .maxByOrNull { it.created }?.let { return it.title }
        } catch (_: Throwable) { }
        return null
    }

    fun inputs(ctx: Context, now: Long = System.currentTimeMillis()): Glance.Inputs {
        init(ctx)
        val h24 = try { android.text.format.DateFormat.is24HourFormat(ctx) } catch (_: Throwable) { true }
        return Glance.Inputs(now, ZoneId.systemDefault(), next(now), task(), StageStore.shelf.value, h24)
    }

    fun items(ctx: Context, now: Long = System.currentTimeMillis()): List<Glance.Item> = Glance.select(inputs(ctx, now), _prefs.value)

    /** One action from the layer's buttons or the tool; the answer is the tool's text. */
    fun apply(ctx: Context, op: Glance.Op, id: String): String {
        val o = Glance.act(op, id, inputs(ctx), _prefs.value)
        if (o.changed) save(o.prefs)
        o.dropShelf?.let { StageStore.shelfDrop(it) }
        val back = o.restore?.let { StageStore.restore(ctx, it) }
        return back ?: o.text
    }

    /** The `glance` tool. */
    suspend fun run(ctx: Context, args: JSONObject): String {
        val (parsed, err) = GlanceTool.parse(args)
        val (op, id) = parsed ?: return err
        return withContext(Dispatchers.Main) { apply(ctx, op, id) }
    }
}
