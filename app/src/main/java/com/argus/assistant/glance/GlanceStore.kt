package com.argus.assistant.glance

import android.content.Context
import com.argus.assistant.agent.BgAgents
import com.argus.assistant.agent.Processes
import com.argus.assistant.agent.Settings
import com.argus.assistant.routine.Routines
import com.argus.assistant.stage.StageStore
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
        return Glance.Inputs(now, ZoneId.systemDefault(), next(now), task(), StageStore.shelfSource, h24)
    }

    /**
     * Owner, round 2: the layer is endless and read a window at a time ([Glance.window]): the UI asks for the next
     * [Glance.PAGE] as the list scrolls near its end, so the shelf is never loaded whole. [total] = how many there are.
     */
    fun window(ctx: Context, offset: Int, limit: Int, now: Long = System.currentTimeMillis()): List<Glance.Item> =
        Glance.window(inputs(ctx, now), _prefs.value, offset, limit)

    fun total(ctx: Context, now: Long = System.currentTimeMillis()): Int = Glance.total(inputs(ctx, now), _prefs.value)

    /** One action from the layer's buttons or the tool; the answer is the tool's text. */
    fun apply(ctx: Context, op: Glance.Op, id: String, offset: Int = 0, limit: Int = Glance.LIST_DEFAULT): String {
        val o = Glance.act(op, id, inputs(ctx), _prefs.value, offset, limit)
        if (o.changed) save(o.prefs)
        o.dropShelf?.let { StageStore.shelfDrop(it) }
        val back = o.restore?.let { StageStore.restore(ctx, it) }
        return back ?: o.text
    }

    /** The `glance` tool. */
    suspend fun run(ctx: Context, args: JSONObject): String {
        val (parsed, err) = GlanceTool.parse(args)
        val (op, id) = parsed ?: return err
        val (offset, limit) = GlanceTool.paging(args)
        return withContext(Dispatchers.Main) { apply(ctx, op, id, offset, limit) }
    }
}
