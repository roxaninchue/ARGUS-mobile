package com.blaze.assistant.browser.phone.instances

import com.blaze.assistant.BrandName
import com.blaze.assistant.browser.phone.PhonePolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * Argus Phone's instance manager (steps 1 + 1a of docs/ARGUS-PHONE.md): power on / off, instances that are made,
 * switched, checkpointed, restored, reset, duplicated and closed, locks, the budget, and crash isolation. Pure: the one
 * live WebView and the background service are reached through [BrowserPort] and [ServicePort], so the whole manager
 * runs on the JVM with fakes (PhoneInstancesTest). On the phone, [ArgusPhone] binds the ports to PhoneBrowser and
 * ArgusPhoneService.
 *
 * Only one instance is attached to the WebView at a time (it is the one phone_* tools act on, [InstanceRegistry.active]);
 * switching checkpoints the current one (url, title, site, scroll) and restores the other into the same WebView machinery,
 * so per-site profiles stay exactly as today. Every operation runs under one mutex, so two callers never switch at once.
 */
class ArgusPhoneCore(
    val store: InstanceStore,
    private val browser: BrowserPort,
    private val service: ServicePort,
    val clock: () -> Long,
    lowRam: Boolean,
    idleMs: Long = PhonePolicy.IDLE_MS,
    /** Step 1b: scratch folders and the Recovered folder (null in tests that don't need files). */
    val workspace: Workspace? = null,
) {
    /** Step 1b: one writer per shared file (docs/ARGUS-PHONE.md 5e). */
    val fileLeases = FileLeases()

    /** Moves [i]'s scratch to Recovered and frees its file leases; the sentence to add to the reply, or "". */
    private fun recoverLocked(i: Instance): String {
        fileLeases.releaseAll(i.id)
        val d = workspace?.recover(i.id, i.name) ?: return ""
        return " Its scratch files were moved to ~/${Workspace.RECOVERED}/${d.name}/."
    }

    /** The one WebView, as the manager sees it (PhoneBrowser on the phone, a fake in tests). */
    interface BrowserPort {
        fun hasPage(): Boolean
        fun mode(): PhonePolicy.Mode
        /** The live page now (url, title, site, scroll), or null with no page. */
        suspend fun capture(): BrowserState?
        /** Opens [s] (its site's own profile, as today) and scrolls back. Returns a short line; throws on failure. */
        suspend fun load(s: BrowserState): String
        /** Frees the WebView, its display and buffers. Site sign-ins stay on disk. */
        suspend fun release()
    }

    /** The background service and the Argus Phone screen. */
    interface ServicePort {
        /** Starts the foreground service. Null when it runs; otherwise why it couldn't (instances still work). */
        fun start(): String?
        fun stop()
        /** Something the notification shows changed. */
        fun changed()
        /** Opens the Argus Phone screen for the user on [id]. */
        fun show(id: String)
    }

    val registry = InstanceRegistry(clock, Budget.of(lowRam), idleMs)
    private val lock = Mutex()
    @Volatile var powered = false
        private set
    /** Why the service didn't start (Android refuses a foreground start from the background), or null. */
    @Volatile var serviceProblem: String? = null
        private set
    /** Instances came back from their checkpoints after the process ended while Argus Phone was on. */
    @Volatile var recovered = 0
        private set
    @Volatile private var loaded = false

    private fun now() = clock()

    // ------------------------------------------------------------------ disk
    /** Loads the index and every checkpoint (as COLD headers). Runs once; later calls do nothing. */
    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val idx = store.loadIndex()
            for (id in idx.order) store.load(id)?.let { registry.put(it.header()) }
            registry.watched = idx.watched?.takeIf { registry.get(it) != null }
            // After a process death nothing is attached: every instance is cold until it is used again.
            if (idx.powered) recovered = registry.size()
            powered = idx.powered
            store.prune(now())
            loaded = true
        }
    }

    private fun saveIndex() = store.saveIndex(InstanceStore.Index(registry.all().map { it.id }, registry.active, registry.watched, powered))

    /** Writes [id]'s checkpoint (capturing the live page first when it is the attached one). */
    private suspend fun checkpointLocked(id: String): Boolean {
        var i = registry.get(id) ?: return false
        if (!i.body) return store.exists(id)   // a cold header: its checkpoint on disk is already the newest
        if (id == registry.active && browser.hasPage()) {
            val cap = try { browser.capture() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }
            if (cap != null) i = registry.update(id) { it.copy(browser = cap) } ?: i
        }
        val t = now()
        val ok = store.save(i, t)
        if (ok) registry.update(id) { it.copy(lastCheckpointAt = t) }
        return ok
    }

    /** The full instance (its body read from disk when it is a cold header). */
    private fun body(id: String): Instance? {
        val i = registry.get(id) ?: return null
        if (i.body) return i
        val d = store.load(id) ?: return null
        // Keep what memory knows newer than the file (lock, name, error).
        return d.copy(name = i.name, task = i.task, lease = i.lease, lastError = i.lastError ?: d.lastError, restorable = i.restorable || d.restorable,
            tier = i.tier, lastCheckpointAt = maxOf(i.lastCheckpointAt, d.lastCheckpointAt))
    }

    /** Detaches the active instance from the WebView: capture, checkpoint, free. Leaves it WARM. */
    private suspend fun detachLocked() {
        val a = registry.active ?: return
        checkpointLocked(a)
        registry.active = null
        registry.update(a) { it.copy(tier = if (it.tier == Tier.COLD) Tier.COLD else Tier.WARM, mode = PhonePolicy.Mode.OFF, cards = emptyList()) }
        try { browser.release() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { }
    }

    private suspend fun toColdLocked(id: String) {
        if (id == registry.active) detachLocked()
        checkpointLocked(id)
        registry.update(id) { it.header() }
    }

    private suspend fun applyMoves(moves: List<InstanceRegistry.Move>) {
        for (m in moves) when (m.to) {
            Tier.COLD -> toColdLocked(m.id)
            Tier.WARM -> if (m.id == registry.active) detachLocked() else registry.update(m.id) { it.copy(tier = Tier.WARM) }
            Tier.LIVE -> {}
        }
    }

    // ------------------------------------------------------------------ power
    suspend fun powerOn(): String = lock.withLock {
        load()
        powered = true
        serviceProblem = service.start()
        if (registry.size() == 0) createLocked(null, "", null)
        saveIndex()
        service.changed()
        "${BrandName.NAME} Phone is on: ${registry.size()} instance${if (registry.size() == 1) "" else "s"}" +
            (serviceProblem?.let { " (background service not started: $it; it starts the next time ${BrandName.NAME} is open)" } ?: ", running in the background until shut down") + "."
    }

    /** Shut down: checkpoint every instance, free the WebView, everything COLD, the service stops. The list is kept. */
    suspend fun powerOff(): String = lock.withLock {
        load()
        detachLocked()
        for (i in registry.all()) toColdLocked(i.id)
        powered = false
        saveIndex()
        service.stop()
        "${BrandName.NAME} Phone is shut down. ${registry.size()} instance${if (registry.size() == 1) "" else "s"} checkpointed; they come back when it is turned on."
    }

    /** Close all instances: each is checkpointed and closed (kept 7 days in .closed); the service stays on. */
    suspend fun closeAll(): String = lock.withLock {
        load()
        detachLocked()
        val n = registry.size()
        var moved = 0
        for (i in registry.all()) { checkpointLocked(i.id); if (recoverLocked(i).isNotEmpty()) moved++; store.close(i.id, now()); registry.remove(i.id) }
        saveIndex(); service.changed()
        "Closed $n instance${if (n == 1) "" else "s"} (each checkpointed first" +
            (if (moved > 0) "; scratch files of $moved moved to ~/${Workspace.RECOVERED}/" else "") + ")." + " ${BrandName.NAME} Phone stays on; the next use starts one fresh instance."
    }

    // ------------------------------------------------------------------ instances
    private suspend fun createLocked(name: String?, task: String, from: Instance?): Instance? {
        if (registry.size() >= Budget.MAX_INSTANCES) return null
        val t = now()
        val i = Instance(registry.newId(), registry.freeName(name), task.take(120), t, t, Tier.WARM,
            browser = from?.browser, steps = from?.steps ?: emptyList())
        registry.put(i)
        store.save(i, t); registry.update(i.id) { it.copy(lastCheckpointAt = t) }
        applyMoves(registry.enforce(t))
        saveIndex()
        return registry.get(i.id)
    }

    suspend fun create(name: String?, task: String, by: Owner, switch: Boolean = true): String = lock.withLock {
        load()
        val i = createLocked(name, task, null) ?: return@withLock "Not made: ${BrandName.NAME} Phone keeps at most ${Budget.MAX_INSTANCES} instances. Close one first (phone_instances close)."
        if (!powered) { powered = true; serviceProblem = service.start() }
        service.changed()
        val sw = if (switch) switchLocked(i.id, by) else null
        "Made instance \u201c${i.name}\u201d" + (if (task.isNotBlank()) " for \u201c${task.take(60)}\u201d" else "") + "." +
            (if (sw == null) (if (switch) " It is the active one now: phone_* tools act in it." else "") else " Not switched to it: $sw")
    }

    /**
     * Makes [id] the active instance: the current one is checkpointed and detached, [id] is restored into the WebView
     * (from disk when cold). Null when done; otherwise the refusal / failure line. Caller holds [lock].
     */
    private suspend fun switchLocked(id: String, by: Owner): String? {
        val target = registry.get(id) ?: return "No such instance."
        Leases.canAct(target, by, now())?.let { return it }
        val cur = registry.active?.let { registry.get(it) }
        if (cur != null && cur.id != id) {
            val m = browser.mode()
            if (m == PhonePolicy.Mode.USER || m == PhonePolicy.Mode.WAITING)
                return "the user has ${BrandName.NAME} Phone's screen on \u201c${cur.name}\u201d (${if (m == PhonePolicy.Mode.USER) "in control" else "asked to take control"}); it switches after they hand back."
            Leases.current(cur, now())?.let { l -> if (!l.owner.same(by) && Leases.working(l, now()))
                return "${BrandName.NAME} Phone's screen is busy with \u201c${cur.name}\u201d, locked and in use by ${l.owner.label()}. Wait, or use web_search / fetch_page for this step." }
            detachLocked()
        }
        if (cur?.id == id && browser.hasPage()) { touchLocked(id); return null }
        val full = body(id) ?: return "Its checkpoint can't be read (${store.lastProblem ?: "missing"}). phone_instances reset starts it clean."
        registry.put(full.copy(tier = Tier.LIVE, body = true, lastActiveAt = now()))
        registry.active = id
        applyMoves(registry.enforce(now()))
        registry.active = id
        val b = full.browser
        if (b != null && !b.empty) {
            val r = registry.isolate(id, "restoring its page") { browser.load(b); "" }
            if (r.isNotEmpty()) return r
        }
        touchLocked(id)
        saveIndex(); service.changed()
        return null
    }

    private fun touchLocked(id: String) { registry.update(id) { it.copy(lastActiveAt = now(), tier = Tier.LIVE) } }

    suspend fun use(id: String, by: Owner): String = lock.withLock {
        load()
        val i = registry.get(id) ?: return@withLock "No such instance."
        switchLocked(id, by)?.let { return@withLock "Not switched: $it" }
        "Now using \u201c${i.name}\u201d" + (registry.get(id)?.browser?.takeIf { !it.empty }?.let { " (on ${DeepState.safeUrl(it.url)})" } ?: " (empty: phone_open a site)") + "."
    }

    suspend fun checkpoint(id: String): String = lock.withLock {
        load()
        val i = registry.get(id) ?: return@withLock "No such instance."
        if (checkpointLocked(id)) "Checkpointed \u201c${i.name}\u201d." else "Couldn't checkpoint \u201c${i.name}\u201d: ${store.lastProblem ?: "unknown"}."
    }

    /** Brings [id] back to its last checkpoint (after a crash or a bad step). */
    suspend fun restore(id: String, by: Owner): String = lock.withLock {
        load()
        val i = registry.get(id) ?: return@withLock "No such instance."
        Leases.canAct(i, by, now())?.let { return@withLock it }
        val d = store.load(id) ?: return@withLock "No readable checkpoint for \u201c${i.name}\u201d (${store.lastProblem ?: "none saved"}). Use reset."
        val wasActive = registry.active == id
        registry.put(d.copy(name = i.name, lease = i.lease, lastError = null, restorable = false, tier = if (wasActive) Tier.LIVE else Tier.WARM, body = true)
            .withStep("restored from its checkpoint", now()))
        if (wasActive) { d.browser?.takeIf { !it.empty }?.let { b -> registry.isolate(id, "restoring its page") { browser.load(b); "" }.let { if (it.isNotEmpty()) return@withLock it } } }
        saveIndex(); service.changed()
        "Restored \u201c${i.name}\u201d from its checkpoint" + (d.browser?.takeIf { !it.empty }?.let { " (${DeepState.safeUrl(it.url)})" } ?: "") + "."
    }

    /** Starts [id] clean. Its state is checkpointed first, so restore undoes a reset. */
    suspend fun reset(id: String, by: Owner): String = lock.withLock {
        load()
        val i = registry.get(id) ?: return@withLock "No such instance."
        Leases.canAct(i, by, now())?.let { return@withLock it }
        checkpointLocked(id)
        val rec = recoverLocked(i)
        if (registry.active == id) { registry.active = null; try { browser.release() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { } }
        registry.put(i.copy(browser = null, steps = emptyList(), lastError = null, restorable = false, unsaved = false, cards = emptyList(),
            mode = PhonePolicy.Mode.OFF, tier = Tier.WARM, body = true, lastActiveAt = now()).withStep("reset", now()))
        saveIndex(); service.changed()
        "Reset \u201c${i.name}\u201d: empty now. Its previous state is in its checkpoint (phone_instances restore undoes this).$rec"
    }

    suspend fun duplicate(id: String, name: String?): String = lock.withLock {
        load()
        if (registry.active == id) checkpointLocked(id)
        val src = body(id) ?: return@withLock "No such instance."
        val n = createLocked(name?.ifBlank { null } ?: (src.name + " copy"), src.task, src)
            ?: return@withLock "Not made: at most ${Budget.MAX_INSTANCES} instances. Close one first."
        service.changed()
        "Duplicated \u201c${src.name}\u201d as \u201c${n.name}\u201d (same page, no lock, no pending cards). phone_instances use name=\u201c${n.name}\u201d to work in it."
    }

    /** Closes [id]: checkpointed, then kept 7 days in `.closed`. Nothing on the shared workspace is touched. */
    suspend fun close(id: String, by: Owner): String = lock.withLock {
        load()
        val i = registry.get(id) ?: return@withLock "No such instance."
        Leases.canAct(i, by, now())?.let { return@withLock it }
        if (registry.active == id) detachLocked()
        checkpointLocked(id)
        val rec = recoverLocked(i)
        store.close(id, now()); registry.remove(id)
        saveIndex(); service.changed()
        "Closed \u201c${i.name}\u201d (checkpoint kept 7 days).$rec ${registry.size()} left."
    }

    suspend fun setLease(id: String, f: (Instance) -> Leases.Result): String = lock.withLock {
        load()
        val i = registry.get(id) ?: return@withLock "No such instance."
        when (val r = f(i)) {
            is Leases.Result.Refused -> r.text
            is Leases.Result.Ok -> {
                registry.put(r.instance)
                if (r.instance.lease == null && i.lease != null) checkpointLocked(id)
                saveIndex(); service.changed(); r.text
            }
        }
    }

    suspend fun show(id: String, by: Owner): String = lock.withLock {
        load()
        val i = registry.get(id) ?: return@withLock "No such instance."
        switchLocked(id, by)?.let { return@withLock "Not opened: $it" }
        registry.watched = id; saveIndex()
        service.show(id)
        "Opened \u201c${i.name}\u201d on the ${BrandName.NAME} Phone screen for the user."
    }

    // ------------------------------------------------------------------ the browser tools
    /**
     * Before a phone_* browser tool: powers Argus Phone on when a task needs it, makes sure there is an instance,
     * switches to [instanceName] when given, and checks the lock ([acts]: the tool acts, not only reads). Null = go;
     * otherwise the line the tool returns instead.
     */
    suspend fun beforeBrowserTool(by: Owner, instanceName: String?, acts: Boolean): String? = lock.withLock {
        load()
        if (!powered && acts) { powered = true; serviceProblem = service.start() }
        if (registry.size() == 0) { if (!acts) return@withLock null; createLocked(null, "", null) }
        val target = if (!instanceName.isNullOrBlank()) {
            val f = registry.find(instanceName)
            f.instance ?: return@withLock if (f.ambiguous.isNotEmpty()) "Which instance? \u201c$instanceName\u201d matches ${f.ambiguous.joinToString { "\u201c${it.name}\u201d" }}."
                else "No ${BrandName.NAME} Phone instance named \u201c$instanceName\u201d. phone_instances list shows them; new makes one."
        } else registry.get(registry.active) ?: registry.get(registry.watched) ?: registry.all().maxByOrNull { it.lastActiveAt }!!
        if (acts) Leases.canAct(target, by, now())?.let { return@withLock it }
        if (registry.active != target.id || (!browser.hasPage() && target.browser?.empty == false) || (!target.body && acts)) {
            if (acts || !instanceName.isNullOrBlank() || registry.active == null) switchLocked(target.id, by)?.let { return@withLock "Not done: $it" }
        }
        null
    }

    /** After a phone_* tool: its step, the owner's lock renewed, the page state, the unsaved flag. */
    suspend fun afterBrowserTool(by: Owner, step: String, typedNoSubmit: Boolean) = lock.withLock {
        val id = registry.active ?: return@withLock
        val t = now()
        registry.update(id) { i ->
            Leases.touch(i, by, t).withStep(step, t).copy(lastActiveAt = t, tier = Tier.LIVE, unsaved = typedNoSubmit || i.unsaved)
        }
        service.changed()
    }

    /**
     * The live page changed (PhoneBrowser.live): mirror mode, page and the hand-off card into the attached instance.
     * [mode] OFF with a page gone means the WebView was freed (idle release, or the user's own session ended): that
     * instance goes cold (checkpointed). A page that appears with no instance attached (the user opened Argus Phone)
     * is adopted by the instance they watched last, or a new one.
     */
    suspend fun onLive(mode: PhonePolicy.Mode, url: String, title: String, site: String, reason: String?) = lock.withLock {
        load()
        var id = registry.active
        if (id == null) {
            if (mode == PhonePolicy.Mode.OFF || url.isBlank()) return@withLock
            id = (registry.get(registry.watched) ?: registry.all().maxByOrNull { it.lastActiveAt })?.id ?: createLocked(null, "", null)?.id ?: return@withLock
            body(id)?.let { registry.put(it.copy(body = true)) }
            registry.active = id
        }
        if (mode == PhonePolicy.Mode.OFF) {
            // A stale OFF from our own switch (the new page is already up): nothing was freed.
            if (browser.hasPage()) return@withLock
            // Freed under us (idle release): keep the page we knew, then cold.
            registry.active = null
            registry.update(id) { it.copy(mode = PhonePolicy.Mode.OFF, cards = emptyList()) }
            checkpointLocked(id); registry.update(id) { it.header() }
            saveIndex(); service.changed(); return@withLock
        }
        val t = now()
        registry.update(id) { i ->
            val b = if (url.isBlank()) i.browser else BrowserState(url, title, site, if (i.browser?.url == url) i.browser.scrollY else 0)
            i.copy(mode = mode, browser = b, tier = Tier.LIVE, lastActiveAt = if (mode != i.mode) t else i.lastActiveAt,
                cards = if (mode == PhonePolicy.Mode.WAITING) listOf(PendingCard("handoff", "take_control", reason.orEmpty().ifBlank { "Waiting on you" }, t)) else emptyList(),
                unsaved = i.unsaved && i.browser?.url == url)
        }
        service.changed()
    }

    /** Lapsed locks (checkpoint, then free) and the budget. Run every half minute by the service, and after changes. */
    suspend fun sweep(): List<String> = lock.withLock {
        load()
        val t = now()
        val notes = ArrayList<String>()
        for (i in registry.all()) {
            val l = i.lease ?: continue
            if (!Leases.active(l, t)) {
                registry.update(i.id) { it.copy(lease = null).withStep("lock by ${l.owner.label()} ended", t) }
                checkpointLocked(i.id)
                notes += "Lock on \u201c${i.name}\u201d by ${l.owner.label()} ran out; it is free."
            }
        }
        val moves = registry.enforce(t)
        applyMoves(moves)
        if (moves.isNotEmpty() || notes.isNotEmpty()) { saveIndex(); service.changed() }
        notes + moves.map { m -> "\u201c${registry.get(m.id)?.name ?: m.id}\u201d ${m.from.name.lowercase()} -> ${m.to.name.lowercase()} (${m.why})" }
    }

    /** The user watches the live view (or stopped watching). The watched instance is never demoted. */
    fun watching(on: Boolean) { registry.watching = on; if (on) registry.active?.let { registry.watched = it } }

    // ------------------------------------------------------------------ reading
    fun snapshot(): JSONArray {
        load()
        val t = now()
        return JSONArray().apply {
            for (h in registry.all()) {
                val i = if (h.body) h else (store.load(h.id)?.let { d -> d.copy(name = h.name, task = h.task, lease = h.lease, tier = Tier.COLD,
                    lastError = h.lastError ?: d.lastError, restorable = h.restorable || d.restorable, lastCheckpointAt = maxOf(h.lastCheckpointAt, d.lastCheckpointAt)) } ?: h)
                put(DeepState.of(i, t, i.id == registry.active, registry.pinned(i, t)).also { o ->
                    workspace?.let { w -> o.put("scratch", w.scratch(i.id).absolutePath) } })
            }
        }
    }

    fun counts(): Triple<Int, Int, Int> { val a = registry.all(); return Triple(a.count { it.tier == Tier.LIVE }, a.count { it.tier == Tier.WARM }, a.count { it.tier == Tier.COLD }) }

    /** One line for the notification: what Argus is doing, or "Waiting on you". */
    fun doing(step: String): String {
        val a = registry.all()
        a.firstOrNull { it.mode == PhonePolicy.Mode.WAITING || it.cards.isNotEmpty() }?.let { return "Waiting on you: \u201c${it.name}\u201d" }
        val act = registry.get(registry.active)
        return when {
            act == null -> "Idle"
            act.mode == PhonePolicy.Mode.USER -> "You're in control of \u201c${act.name}\u201d"
            step.isNotBlank() -> "${act.name}: ${step.take(60)}"
            else -> act.name + (act.browser?.site?.takeIf { it.isNotBlank() }?.let { " \u00b7 $it" } ?: "")
        }
    }

    fun status(): String {
        load()
        val (l, w, c) = counts()
        val b = registry.budget
        return "${BrandName.NAME} Phone is ${if (powered) "on" else "off"}: ${registry.size()} instance${if (registry.size() == 1) "" else "s"} " +
            "($l live, $w warm, $c cold; budget ${b.live} live / ${b.warm} warm)" +
            (registry.get(registry.active)?.let { ". Active: \u201c${it.name}\u201d" } ?: ". None attached") +
            (if (recovered > 0) ". $recovered came back from checkpoints after Android closed ${BrandName.NAME}" else "") +
            (serviceProblem?.let { ". Background service: $it" } ?: "") + "."
    }
}
