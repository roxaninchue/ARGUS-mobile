package com.argus.assistant.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 0.50.0: is Blaze controlling the screen right now? Drives the edge particles overlay ([com.argus.assistant.ui.ScreenGlow]):
 * on while any screen action runs (tap, type, scroll, a tap plan, opening an app…), off [LINGER_MS] after the last one ends
 * (so a run of steps shows one steady glow, not a flicker), and never stuck on: a step that never reports back is dropped
 * after [MAX_MS]. The bookkeeping is [Clock] (pure, ScreenWorkTest); the object wires it to the wall clock.
 */
object ScreenWork {
    /** Tools that act on (or move) the screen through phone control. */
    val TOOLS = setOf("tap", "type_text", "scroll", "press_button", "run_plan", "do_actions", "open_app", "open_url", "search_in_app",
        "navigate_to", "share_to_app", "phone_task", "open_settings", "settings_jump")

    fun isScreenTool(name: String): Boolean = name in TOOLS

    const val LINGER_MS = 1_200L
    const val MAX_MS = 120_000L

    /** Open steps by token and when they began; [visible] = something open, or the linger after the last close. */
    class Clock(private val linger: Long = LINGER_MS, private val max: Long = MAX_MS) {
        private val open = HashMap<Long, Long>()
        private var next = 1L
        private var lastEnd = Long.MIN_VALUE / 2
        @Synchronized fun begin(now: Long): Long { val t = next++; open[t] = now; return t }
        @Synchronized fun end(token: Long, now: Long) { if (open.remove(token) != null) lastEnd = now }
        @Synchronized fun visible(now: Long): Boolean {
            open.entries.removeAll { now - it.value > max }
            return open.isNotEmpty() || now - lastEnd < linger
        }
        @Synchronized fun openCount(): Int = open.size
    }

    private val clock = Clock()
    private val _active = MutableStateFlow(false)
    /** True while the edge particles should show. */
    val active: StateFlow<Boolean> = _active

    fun begin(): Long { val t = clock.begin(System.currentTimeMillis()); _active.value = true; return t }
    fun end(token: Long) { clock.end(token, System.currentTimeMillis()); refresh() }
    /** Re-reads [Clock.visible] (the overlay's watcher calls this after the linger). */
    fun refresh() { _active.value = clock.visible(System.currentTimeMillis()) }

    inline fun <T> during(name: String, block: () -> T): T {
        if (!isScreenTool(name)) return block()
        val t = begin()
        try { return block() } finally { end(t) }
    }
}
