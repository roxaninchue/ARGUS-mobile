package com.blaze.assistant.setup

import com.blaze.assistant.BrandName

/**
 * 0.52.3 one-screen setup: everything ARGUS downloads or prepares on first run, as one list with one overall progress
 * bar. Nothing to configure: [SetupRunner] starts every item by itself, big ones wait for Wi-Fi, a failure retries with
 * a back-off, and the run continues in the background (and resumes on the next start) when the user goes on into the app.
 *
 * Pure (lists, maps and numbers in, state out), so the order, the weights, the overall progress, resume and retry rules
 * are pinned by SetupPlanTest without a phone.
 */
object SetupPlan {
    /** One thing to set up. [mb] weighs it in the overall bar; [wifi] = waits for an unmetered network; [after] = runs after that item. */
    data class Item(val id: String, val title: String, val sub: String, val mb: Int, val wifi: Boolean, val after: String? = null)

    val ITEMS = listOf(
        Item("linux", "Linux workspace", "A small Linux for files, scripts and code", 4, wifi = false),
        Item("tools", "Python, git and tools", "Ready for the tasks ${BrandName.NAME} runs", 60, wifi = false, after = "linux"),
        Item("skills", "Skills", "Research, docs, plans, code and more", 1, wifi = false),
        Item("brain", "On-device backup brain", "Answers when the cloud is out of reach", 78, wifi = true, after = "linux"),
        Item("voice", "Offline voice model", "\u201cHey ${BrandName.NAME}\u201d, captions and voice notes", 40, wifi = true),
        Item("quick", "Quick commands", "Instant offline phone commands", 35, wifi = true),
    )

    fun item(id: String): Item? = ITEMS.firstOrNull { it.id == id }

    enum class Phase { PENDING, RUNNING, DONE, WAITING_WIFI, OFFLINE, FAILED, SKIPPED }

    /** An item's state. [progress] 0..100 while running (-1 = unknown), [note] a short line, [tries] failed attempts. */
    data class Status(val phase: Phase = Phase.PENDING, val progress: Int = -1, val note: String = "", val tries: Int = 0, val retryAt: Long = 0L)

    /** Finished one way or another (done, or not for this phone). */
    fun settled(s: Status?): Boolean = s?.phase == Phase.DONE || s?.phase == Phase.SKIPPED

    /** 0..1 over every item that applies, weighted by size; a running item counts its own progress. */
    fun overall(st: Map<String, Status>): Float {
        val apply = ITEMS.filter { st[it.id]?.phase != Phase.SKIPPED }
        val total = apply.sumOf { it.mb }.toFloat()
        if (total <= 0f) return 1f
        val got = apply.sumOf { i ->
            val s = st[i.id]
            when {
                s?.phase == Phase.DONE -> i.mb.toDouble()
                s?.phase == Phase.RUNNING && s.progress in 0..100 -> i.mb * s.progress / 100.0
                else -> 0.0
            }
        }
        return (got / total).toFloat().coerceIn(0f, 1f)
    }

    fun percent(st: Map<String, Status>): Int = (overall(st) * 100f).toInt().coerceIn(0, 100)

    fun complete(st: Map<String, Status>): Boolean = ITEMS.all { settled(st[it.id]) }

    /** Items still to finish (not done, not skipped). */
    fun left(st: Map<String, Status>): List<Item> = ITEMS.filter { !settled(st[it.id]) }

    /** The headline over the bar. */
    fun headline(st: Map<String, Status>): String = when {
        complete(st) -> "${BrandName.UPPER} is ready"
        ITEMS.any { st[it.id]?.phase == Phase.RUNNING } -> "Setting up ${BrandName.UPPER}\u2026"
        left(st).all { st[it.id]?.phase == Phase.WAITING_WIFI } -> "Waiting for Wi-Fi to finish"
        left(st).all { st[it.id]?.phase == Phase.OFFLINE } -> "Waiting for a connection"
        else -> "Setting up ${BrandName.UPPER}\u2026"
    }

    /** The line for one row. */
    fun line(i: Item, s: Status?): String = when (s?.phase ?: Phase.PENDING) {
        Phase.PENDING -> "Waiting"
        Phase.RUNNING -> if ((s?.progress ?: -1) in 0..100) "${s!!.progress}%" else s?.note?.ifBlank { null } ?: "Working\u2026"
        Phase.DONE -> "Ready"
        Phase.WAITING_WIFI -> "On Wi-Fi \u00b7 ${i.mb} MB"
        Phase.OFFLINE -> "When you're online"
        Phase.FAILED -> "Retrying soon"
        Phase.SKIPPED -> s?.note?.ifBlank { null } ?: "Not needed on this phone"
    }

    // ---------- what runs next

    /** Back-off after [tries] failures: 30 s, 2 min, 8 min, then 30 min. */
    fun backoffMs(tries: Int): Long = when {
        tries <= 0 -> 0L
        tries == 1 -> 30_000L
        tries == 2 -> 120_000L
        tries == 3 -> 480_000L
        else -> 1_800_000L
    }

    /** Automatic tries per item per app run; after that only the Retry button (or the next start) tries again. */
    const val MAX_AUTO_TRIES = 4

    /**
     * Items that may start now: not settled, not running, their [Item.after] settled, Wi-Fi items only on an unmetered
     * network, nothing without a connection, and a failed item only once its back-off is over and under [MAX_AUTO_TRIES].
     */
    fun runnable(st: Map<String, Status>, online: Boolean, unmetered: Boolean, now: Long, useData: Boolean = false): List<Item> = ITEMS.filter { i ->
        val s = st[i.id] ?: Status()
        when {
            settled(s) || s.phase == Phase.RUNNING -> false
            i.after != null && !settled(st[i.after]) -> false
            !online -> false
            i.wifi && !unmetered && !useData -> false
            s.phase == Phase.FAILED -> s.tries < MAX_AUTO_TRIES && now >= s.retryAt
            else -> true
        }
    }

    /** What a waiting item shows while it can't start (no connection, or on mobile data for a Wi-Fi item). */
    fun waiting(i: Item, s: Status?, online: Boolean, unmetered: Boolean, useData: Boolean = false): Status? {
        val cur = s ?: Status()
        if (settled(cur) || cur.phase == Phase.RUNNING || cur.phase == Phase.FAILED) return null
        return when {
            !online -> cur.copy(phase = Phase.OFFLINE)
            i.wifi && !unmetered && !useData -> cur.copy(phase = Phase.WAITING_WIFI)
            else -> null
        }
    }

    // ---------- 0.52.4: finish on mobile data

    /** The pref set by "Download on mobile data": Wi-Fi items then also run on a metered network. */
    const val PREF_MOBILE_DATA = "setup_mobile_data"

    /** Megabytes still waiting for Wi-Fi (0 = nothing waits, so no mobile-data button). */
    fun waitingWifiMb(st: Map<String, Status>): Int = ITEMS.filter { st[it.id]?.phase == Phase.WAITING_WIFI }.sumOf { it.mb }

    /** The button that lets the user finish now on mobile data, with how much it will use. */
    fun mobileDataButton(mb: Int): String = "Download now on mobile data · $mb MB"

    /** After a failed attempt. */
    fun failed(s: Status?, note: String, now: Long): Status {
        val t = (s?.tries ?: 0) + 1
        return Status(Phase.FAILED, -1, note.take(120), t, now + backoffMs(t))
    }

    /** The Retry button: failed items start again at once, with their try count reset. */
    fun retryAll(st: Map<String, Status>): Map<String, Status> = st.mapValues { (_, s) -> if (s.phase == Phase.FAILED) Status() else s }

    // ---------- resume (only settled results are kept; anything running starts over next time)

    const val PREF = "space_setup_v1"
    const val PREF_STARTED = "space_setup_started"

    fun encode(st: Map<String, Status>): String = ITEMS.mapNotNull { i ->
        val s = st[i.id] ?: return@mapNotNull null
        if (!settled(s)) null else i.id + "=" + s.phase.name
    }.joinToString(",")

    fun decode(raw: String): Map<String, Status> = raw.split(',').mapNotNull { part ->
        val id = part.substringBefore('=', "").trim(); val ph = part.substringAfter('=', "").trim()
        val phase = Phase.values().firstOrNull { it.name == ph } ?: return@mapNotNull null
        if (item(id) == null || (phase != Phase.DONE && phase != Phase.SKIPPED)) null else id to Status(phase)
    }.toMap()

    /**
     * When the setup screen shows: on first run (after the welcome) until the user goes on. Afterwards the Home's setup card
     * shows what is left. Never shows again once every item is settled.
     */
    fun showScreen(onboarded: Boolean, started: Boolean, st: Map<String, Status>): Boolean = !onboarded && started && !complete(st)

    /** How long after the first frame BlazeApp starts what is left (people past onboarding): start-up stays quiet. */
    const val LATER_MS = 30_000L

    /** BlazeApp's later start: someone past onboarding (or who began setup) with something still left. */
    fun startLater(onboarded: Boolean, started: Boolean, st: Map<String, Status>): Boolean = (onboarded || started) && !complete(st)
}
