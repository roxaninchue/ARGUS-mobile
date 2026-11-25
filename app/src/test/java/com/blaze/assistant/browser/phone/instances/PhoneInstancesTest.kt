package com.blaze.assistant.browser.phone.instances

import com.blaze.assistant.browser.phone.PhonePolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Argus Phone instances, steps 1 + 1a (docs/ARGUS-PHONE.md, docs/PHONE-INSTANCES.md): the registry's budgets and
 * eviction, leases on a fake clock, checkpoint round trips and corrupt-file recovery, crash isolation, the deep-state
 * snapshot's shape, the tools' lock refusals, and switching instances through one WebView (a fake [ArgusPhoneCore.BrowserPort]).
 */
class PhoneInstancesTest {
    private var now = 1_000_000L
    private val clock = { now }
    private val tmp: File = Files.createTempDirectory("phinst").toFile()
    private val argus = Owner(Owner.Kind.ARGUS, "chat-1")
    private val agent = Owner(Owner.Kind.AGENT, "bg-7", "Research")

    /** The one WebView: remembers what was loaded, can be told to fail. */
    class FakeBrowser : ArgusPhoneCore.BrowserPort {
        var page: BrowserState? = null
        var mode = PhonePolicy.Mode.OFF
        var loads = 0; var releases = 0
        var failNext: Throwable? = null
        override fun hasPage() = page != null
        override fun mode() = mode
        override suspend fun capture(): BrowserState? = page
        override suspend fun load(s: BrowserState): String {
            failNext?.let { failNext = null; throw it }
            loads++; page = s; mode = PhonePolicy.Mode.AGENT; return "ok"
        }
        override suspend fun release() { releases++; page = null; mode = PhonePolicy.Mode.OFF }
    }

    class FakeService : ArgusPhoneCore.ServicePort {
        var running = false; var changes = 0; var shown: String? = null
        override fun start(): String? { running = true; return null }
        override fun stop() { running = false }
        override fun changed() { changes++ }
        override fun show(id: String) { shown = id }
    }

    private fun core(lowRam: Boolean = true, b: FakeBrowser = FakeBrowser(), s: FakeService = FakeService(), root: File = File(tmp, "instances")) =
        ArgusPhoneCore(InstanceStore(root), b, s, clock, lowRam)

    private fun inst(id: String, tier: Tier, active: Long, mode: PhonePolicy.Mode = PhonePolicy.Mode.OFF) =
        Instance(id, id, createdAt = 0, lastActiveAt = active, tier = tier, mode = mode)

    private fun ok(r: Leases.Result): Instance = (r as Leases.Result.Ok).instance

    // ------------------------------------------------------------------ registry budgets and eviction
    @Test fun budgetsMatchTheDoc() {
        assertEquals(Budget(1, 2), Budget.of(lowRam = true))
        assertEquals(Budget(2, 4), Budget.of(lowRam = false))
    }

    @Test fun overBudgetTheOldestIdleStepsDownFirst() {
        val r = InstanceRegistry(clock, Budget.of(true))
        r.put(inst("ia0001", Tier.WARM, now - 3000)); r.put(inst("ia0002", Tier.WARM, now - 2000)); r.put(inst("ia0003", Tier.WARM, now - 1000))
        r.put(inst("ia0004", Tier.LIVE, now - 4000)); r.put(inst("ia0005", Tier.LIVE, now - 500))
        val m = r.enforce(now)
        // live 2 > 1: the older live goes warm; then warm 4 > 2: the two oldest warm go cold.
        assertTrue(m.toString(), InstanceRegistry.Move("ia0004", Tier.LIVE, Tier.WARM, "over the live budget") in m)
        assertEquals(setOf("ia0004", "ia0001"), m.filter { it.to == Tier.COLD }.map { it.id }.toSet())
        assertFalse(m.any { it.id == "ia0005" })
    }

    @Test fun neverDemotedWhileTheUserDrivesWaitsOrALockWorks() {
        val r = InstanceRegistry(clock, Budget(1, 0), idleMs = 1000)
        val l = Lease(agent, "long task", now - 10_000, now + 60_000, now - 1000)
        r.put(inst("ib0001", Tier.LIVE, 0, PhonePolicy.Mode.USER))
        r.put(inst("ib0002", Tier.WARM, 0, PhonePolicy.Mode.WAITING))
        r.put(inst("ib0003", Tier.WARM, 0).copy(cards = listOf(PendingCard("c", "approval", "Pay?", 0))))
        r.put(inst("ib0004", Tier.WARM, 0).copy(lease = l))
        r.put(inst("ib0005", Tier.WARM, 0))
        val m = r.enforce(now)
        assertEquals(listOf("ib0005"), m.map { it.id })
        assertEquals("the user drives it", r.pinned(r.get("ib0001")!!))
        assertEquals("its lock is active and working", r.pinned(r.get("ib0004")!!))
        // A lease that stopped working (no renew for WORKING_MS) no longer pins it.
        now += Leases.WORKING_MS
        assertNull(r.pinned(r.get("ib0004")!!))
    }

    @Test fun idleInstancesGoColdAfterIdleMsAndTheWatchedOneStays() {
        val r = InstanceRegistry(clock, Budget(2, 4))
        r.put(inst("ic0001", Tier.WARM, now - PhonePolicy.IDLE_MS)); r.put(inst("ic0002", Tier.LIVE, now - PhonePolicy.IDLE_MS)); r.put(inst("ic0003", Tier.WARM, now - 1000))
        r.active = "ic0002"; r.watching = true
        assertEquals(listOf("ic0001"), r.enforce(now).map { it.id })
        r.watching = false
        assertEquals(setOf("ic0001", "ic0002"), r.enforce(now).map { it.id }.toSet())
    }

    @Test fun namesAndFinding() {
        val r = InstanceRegistry(clock)
        assertEquals("Phone", r.freeName(null))
        r.put(inst("id0001", Tier.WARM, 0).copy(name = "Phone", task = "Book a table at Nando's"))
        assertEquals("Phone 2", r.freeName(null))
        r.put(inst("id0002", Tier.WARM, 0).copy(name = "Shopping", task = "order a phone case"))
        assertEquals("shopping 2", r.freeName("shopping"))
        assertEquals("id0002", r.find("SHOPPING").instance?.id)
        assertEquals("id0001", r.find("book a table at nando's").instance?.id)
        assertEquals("id0001", r.find("id0001").instance?.id)
        assertEquals("an exact name wins", "id0001", r.find("phone").instance?.id)
        assertEquals(2, r.find("pho").ambiguous.size)   // "Phone" by name and "phone case" by task: ambiguous
        assertNull(r.find("pho").instance)
        assertNull(r.find("").instance)
    }

    // ------------------------------------------------------------------ leases on a fake clock
    @Test fun leasesLockRenewExpireAndBreak() {
        var i = inst("ie0001", Tier.WARM, 0)
        i = ok(Leases.acquire(i, agent, "scrape prices", Leases.ttl(2.0), now))
        assertNull(Leases.canAct(i, agent, now))
        assertNotNull(Leases.canAct(i, argus, now))
        assertNull("the user may always act", Leases.canAct(i, Owner.USER, now))
        assertTrue(Leases.acquire(i, argus, "", Leases.DEFAULT_MS, now) is Leases.Result.Refused)
        assertTrue(Leases.renew(i, argus, null, now) is Leases.Result.Refused)
        assertTrue(Leases.release(i, argus, now) is Leases.Result.Refused)

        now += 100_000   // past WORKING_MS, inside the 2 min lease
        assertFalse(Leases.working(i.lease, now)); assertTrue(Leases.active(i.lease, now))
        i = Leases.touch(i, agent, now)   // the owner acts: renewed
        assertTrue(Leases.working(i.lease, now)); assertEquals(now + 120_000, i.lease!!.until)
        assertEquals("someone else's act renews nothing", i, Leases.touch(i, argus, now))

        now += 120_001
        assertFalse(Leases.active(i.lease, now))
        assertNull("a lapsed lease is no lock", Leases.canAct(i, argus, now))

        i = ok(Leases.acquire(i, agent, "again", Leases.DEFAULT_MS, now))
        assertTrue(Leases.release(i, argus, now, breakIt = true) is Leases.Result.Ok)
        assertNull(ok(Leases.release(i, Owner.USER, now)).lease)
        assertEquals(Leases.MIN_MS, Leases.ttl(0.0)); assertEquals(Leases.MAX_MS, Leases.ttl(500.0)); assertEquals(Leases.DEFAULT_MS, Leases.ttl(null))
    }

    @Test fun anExpiredLeaseIsCheckpointedAndFreedBySweep() = runBlocking {
        val c = core()
        c.powerOn()
        val id = c.registry.all().single().id
        c.setLease(id) { Leases.acquire(it, agent, "job", Leases.MIN_MS, now) }
        now += Leases.MIN_MS + 1
        val notes = c.sweep()
        assertTrue(notes.toString(), notes.any { it.contains("ran out") })
        assertNull(c.registry.get(id)!!.lease)
        assertNull(c.store.load(id)!!.lease)
    }

    // ------------------------------------------------------------------ checkpoints
    @Test fun checkpointRoundTripKeepsEverything() {
        val st = InstanceStore(File(tmp, "rt"))
        val i = Instance("if0001", "Shop", "buy shoes", 5, 6, Tier.LIVE, PhonePolicy.Mode.WAITING,
            BrowserState("https://shop.example/cart?x=1", "Cart", "shop.example", 840), unsaved = true,
            cards = listOf(PendingCard("handoff", "take_control", "Solve the captcha", 7)), lastError = "boom", restorable = true,
            steps = (1..20).map { Step(it.toLong(), "step $it") }.takeLast(Instance.STEPS),
            lease = Lease(agent, "buy shoes", 1, 2, 3))
        assertTrue(st.save(i, 99))
        val back = st.load("if0001")!!
        assertEquals(i.copy(tier = Tier.COLD, lastCheckpointAt = 99), back)
        assertEquals(Instance.STEPS, back.steps.size)
        val j = InstanceStore.encode(i, 99)
        assertEquals(InstanceStore.VERSION, j.getInt("v"))
        assertTrue("small", j.toString().length < 4000)
    }

    @Test fun aCorruptCheckpointFallsBackToThePreviousOne() {
        val root = File(tmp, "bad")
        val st = InstanceStore(root)
        val i = Instance("ig0001", "A", createdAt = 1, lastActiveAt = 1, browser = BrowserState("https://a.example/1"))
        assertTrue(st.save(i, 10))
        assertTrue(st.save(i.copy(browser = BrowserState("https://a.example/2")), 20))
        File(root, "ig0001/checkpoint.json").writeText("{\"v\":1,\"id\":\"ig0001\",\"brow")   // torn write
        val back = st.load("ig0001")!!
        assertEquals("https://a.example/1", back.browser!!.url)
        assertNotNull(st.lastProblem)
        assertTrue(File(root, "ig0001/checkpoint.bad.json").exists())
        // Both unreadable: null, never an exception.
        File(root, "ig0001/checkpoint.prev.json").writeText("not json")
        assertNull(st.load("ig0001"))
        // A newer version than this build reads, or an id that isn't ours, is not loaded.
        assertNull(InstanceStore.decode(JSONObject().put("v", InstanceStore.VERSION + 1).put("id", "ig0002")))
        assertNull(InstanceStore.decode(JSONObject().put("v", 1).put("id", "../../etc")))
        assertNull(st.load("../x"))
    }

    @Test fun aCorruptIndexIsRebuiltFromTheFolders() {
        val root = File(tmp, "idx")
        val st = InstanceStore(root)
        st.save(Instance("ih0001", "A", createdAt = 1, lastActiveAt = 1), 1)
        st.save(Instance("ih0002", "B", createdAt = 1, lastActiveAt = 1), 1)
        st.saveIndex(InstanceStore.Index(listOf("ih0002", "ih0001", "ih9999"), "ih0002", "ih0001", true))
        val x = st.loadIndex()
        assertEquals(listOf("ih0002", "ih0001"), x.order); assertEquals("ih0002", x.active); assertTrue(x.powered)
        File(root, "index.json").writeText("garbage")
        assertEquals(setOf("ih0001", "ih0002"), st.loadIndex().order.toSet())
    }

    @Test fun closedInstancesAreKeptSevenDays() {
        val root = File(tmp, "closed")
        val st = InstanceStore(root)
        st.save(Instance("ij0001", "A", createdAt = 1, lastActiveAt = 1), 1)
        assertTrue(st.close("ij0001", now))
        assertFalse(st.exists("ij0001"))
        assertEquals(0, st.prune(now + InstanceStore.KEEP_CLOSED_MS))
        assertEquals(1, st.prune(now + InstanceStore.KEEP_CLOSED_MS + 1))
    }

    @Test fun afterProcessDeathInstancesComeBackColdFromCheckpoints() = runBlocking {
        val root = File(tmp, "death")
        val c1 = core(root = root)
        c1.powerOn()
        c1.beforeBrowserTool(argus, null, acts = true)
        c1.onLive(PhonePolicy.Mode.AGENT, "https://news.example/a", "A", "news.example", null)
        c1.create("Second", "research", argus)
        // The process dies here: a new core over the same folder.
        val c2 = core(root = root)
        c2.load()
        assertTrue(c2.powered)
        assertEquals(2, c2.recovered)
        assertTrue(c2.registry.all().all { it.tier == Tier.COLD && !it.body })
        val snap = c2.snapshot()
        val first = (0 until snap.length()).map { snap.getJSONObject(it) }.first { it.getString("name") == "Phone" }
        assertEquals("https://news.example/a", first.getJSONArray("apps").getJSONObject(0).getString("url"))
    }

    // ------------------------------------------------------------------ switching through the one WebView
    @Test fun switchingCheckpointsTheCurrentAndRestoresTheOther() = runBlocking {
        val b = FakeBrowser(); val s = FakeService()
        val c = core(lowRam = false, b = b, s = s)
        c.powerOn(); assertTrue(s.running)
        assertNull(c.beforeBrowserTool(argus, null, acts = true))
        val first = c.registry.active!!
        b.page = BrowserState("https://shop.example/item/1", "Item", "shop.example", 1200)
        c.onLive(PhonePolicy.Mode.AGENT, b.page!!.url, "Item", "shop.example", null)
        c.create("Bank", "read balance", argus)   // switches: the shop is checkpointed with its scroll
        val second = c.registry.active!!
        assertTrue(first != second)
        assertEquals(1200, c.store.load(first)!!.browser!!.scrollY)
        assertFalse(b.hasPage())
        assertEquals(Tier.WARM, c.registry.get(first)!!.tier)
        // Back to the shop: loaded into the WebView again, at its scroll.
        assertTrue(c.use(first, argus).startsWith("Now using"))
        assertEquals(BrowserState("https://shop.example/item/1", "Item", "shop.example", 1200), b.page)
        assertEquals(Tier.LIVE, c.registry.get(first)!!.tier)
    }

    @Test fun anIdleReleaseSendsTheActiveInstanceCold() = runBlocking {
        val b = FakeBrowser(); val c = core(b = b)
        c.powerOn(); c.beforeBrowserTool(argus, null, true)
        val id = c.registry.active!!
        b.page = BrowserState("https://a.example/", "A", "a.example"); c.onLive(PhonePolicy.Mode.AGENT, "https://a.example/", "A", "a.example", null)
        b.page = null; c.onLive(PhonePolicy.Mode.OFF, "", "", "", null)   // PhoneBrowser freed the WebView after IDLE_MS
        assertNull(c.registry.active)
        assertEquals(Tier.COLD, c.registry.get(id)!!.tier)
        assertEquals("https://a.example/", c.store.load(id)!!.browser!!.url)
        // The next read brings the page back.
        assertNull(c.beforeBrowserTool(argus, null, acts = false))
        assertEquals("https://a.example/", b.page?.url)
    }

    @Test fun resetIsUndoneByRestoreAndDuplicateCopiesThePage() = runBlocking {
        val b = FakeBrowser(); val c = core(lowRam = false, b = b)
        c.powerOn(); c.beforeBrowserTool(argus, null, true)
        val id = c.registry.active!!
        b.page = BrowserState("https://docs.example/p", "P", "docs.example"); c.onLive(PhonePolicy.Mode.AGENT, b.page!!.url, "P", "docs.example", null)
        assertTrue(c.duplicate(id, "Copy").contains("\u201cCopy\u201d"))
        assertEquals("https://docs.example/p", c.registry.find("Copy").instance!!.browser!!.url)
        c.reset(id, argus)
        assertNull(c.registry.get(id)!!.browser)
        c.restore(id, argus)
        assertEquals("https://docs.example/p", c.registry.get(id)!!.browser!!.url)
    }

    @Test fun shutDownAndCloseAll() = runBlocking {
        val b = FakeBrowser(); val s = FakeService(); val c = core(b = b, s = s)
        c.powerOn(); c.beforeBrowserTool(argus, null, true); c.create("Two", "", argus, switch = false)
        b.page = BrowserState("https://x.example/"); c.onLive(PhonePolicy.Mode.AGENT, "https://x.example/", "X", "x.example", null)
        c.powerOff()
        assertFalse(s.running); assertFalse(c.powered); assertFalse(b.hasPage())
        assertEquals(2, c.registry.size()); assertTrue(c.registry.all().all { it.tier == Tier.COLD })
        c.powerOn()
        assertEquals(2, c.registry.size())
        assertTrue(c.closeAll().startsWith("Closed 2"))
        assertEquals(0, c.registry.size()); assertTrue(s.running)
        // The next use starts one fresh instance.
        c.beforeBrowserTool(argus, null, true)
        assertEquals(1, c.registry.size())
    }

    @Test fun theInstanceCapRefusesPlainly() = runBlocking {
        val c = core()
        repeat(Budget.MAX_INSTANCES) { c.create(null, "", argus, switch = false) }
        assertTrue(c.create(null, "", argus).startsWith("Not made"))
        assertTrue("cold ones cost disk only", c.counts().third >= Budget.MAX_INSTANCES - 3)
    }

    // ------------------------------------------------------------------ crash isolation
    @Test fun aCrashStaysInItsInstance() = runBlocking {
        val b = FakeBrowser(); val c = core(lowRam = false, b = b)
        c.powerOn(); c.beforeBrowserTool(argus, null, true)
        val a = c.registry.active!!
        c.create("Other", "", argus, switch = false)
        val other = c.registry.find("Other").instance!!
        val r = c.registry.isolate(a, "phone_act") { throw OutOfMemoryError("renderer") }
        assertTrue(r, r.startsWith("This instance stopped"))
        assertTrue(c.registry.get(a)!!.restorable)
        assertTrue(c.registry.get(a)!!.lastError!!.contains("OutOfMemoryError"))
        assertEquals(other, c.registry.find("Other").instance)
        // A page that fails to restore marks only that instance.
        c.registry.update(other.id) { it.copy(browser = BrowserState("https://broken.example/")) }
        b.failNext = IllegalStateException("load failed")
        val u = c.use(other.id, argus)
        assertTrue(u, u.contains("This instance stopped"))
        assertTrue(c.registry.get(other.id)!!.restorable)
        assertNull(c.registry.get(a)!!.lastError?.takeIf { it.contains("load failed") })
        // The tools never throw either.
        val t = PhoneInstanceTools.run(c, argus, "phone_instances", JSONObject().put("action", "list")) { _, _, _ -> true }
        assertTrue(t.contains("restorable"))
    }

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        val r = InstanceRegistry(clock)
        try { r.isolate(null, "x") { throw kotlinx.coroutines.CancellationException("stop") }; throw AssertionError("swallowed") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }

    // ------------------------------------------------------------------ the deep-state snapshot
    @Test fun snapshotShapeIsCompactAndHidesSecrets() {
        val i = Instance("ik0001", "Bank", "check balance", 0, now - 30_000, Tier.LIVE, PhonePolicy.Mode.WAITING,
            BrowserState("https://bank.example/login?session=abc123&next=/home&token=zzz", "Sign in", "bank.example", 0),
            cards = listOf(PendingCard("handoff", "take_control", "Enter your code", now)), lastCheckpointAt = now - 5000,
            steps = (1..8).map { Step(it.toLong(), "step $it") }, lease = Lease(agent, "check balance", now - 1000, now + 60_000, now - 1000))
        val o = DeepState.of(i, now, active = true, pinnedWhy = "it waits on the user")
        assertEquals(setOf("id", "name", "task", "tier", "active", "mode", "lock", "apps", "cards", "mem_kb", "checkpoint_s_ago", "steps", "kept_because", "idle_s"), o.keySet())
        assertEquals("live", o.getString("tier")); assertEquals("waiting_on_user", o.getString("mode"))
        val url = o.getJSONArray("apps").getJSONObject(0).getString("url")
        assertFalse(url, url.contains("abc123") || url.contains("zzz")); assertTrue(url.contains("next=/home"))
        assertEquals(DeepState.STEPS_SHOWN, o.getJSONArray("steps").length())
        assertEquals(60L, o.getJSONObject("lock").getLong("ends_s")); assertTrue(o.getJSONObject("lock").getBoolean("working"))
        assertEquals(5L, o.getLong("checkpoint_s_ago"))
        assertTrue(o.toString().length < 900)
        // A cold, empty instance says almost nothing and costs no RAM.
        val cold = DeepState.of(Instance("ik0002", "Idle", createdAt = 0, lastActiveAt = now, tier = Tier.COLD), now, false)
        assertEquals(setOf("id", "name", "tier", "mode", "mem_kb", "idle_s"), cold.keySet()); assertEquals(0L, cold.getLong("mem_kb"))
        assertEquals("https://a.example/p", DeepState.safeUrl("https://a.example/p"))
        assertEquals("https://a.example/r?code=***&state=1", DeepState.safeUrl("https://a.example/r?code=9911&state=1"))
    }

    // ------------------------------------------------------------------ the tools and locks
    private fun call(c: ArgusPhoneCore, by: Owner, name: String, a: JSONObject, yes: Boolean = true, asked: MutableList<String>? = null) =
        runBlocking { PhoneInstanceTools.run(c, by, name, a) { t, _, _ -> asked?.add(t); yes } }

    @Test fun aNonOwnerIsRefusedInALockedInstanceAndMayStillRead() = runBlocking {
        val b = FakeBrowser(); val c = core(lowRam = false, b = b)
        call(c, agent, "phone_instances", JSONObject().put("action", "new").put("name", "Research").put("task", "prices"))
        assertTrue(call(c, agent, "phone_instances", JSONObject().put("action", "lock").put("minutes", 10)).startsWith("Locked"))
        // Argus (another owner) can't act in it, through the tools or through the browser tools.
        for (a in listOf("use", "reset", "close", "restore", "lock")) {
            val r = call(c, argus, "phone_instances", JSONObject().put("action", a).put("name", "Research"))
            assertTrue("$a: $r", r.contains("is locked by background agent Research"))
        }
        val before = c.beforeBrowserTool(argus, null, acts = true)
        assertTrue(before!!, before.contains("locked by background agent Research"))
        assertNull("reading a locked instance is allowed", c.beforeBrowserTool(argus, null, acts = false))
        assertTrue(call(c, argus, "phone_instances", JSONObject().put("action", "list")).contains("\"lock\""))
        // Unlock by a non-owner is refused; breaking it asks the user.
        assertTrue(call(c, argus, "phone_instances", JSONObject().put("action", "unlock")).startsWith("Not done"))
        val asked = ArrayList<String>()
        assertTrue(call(c, argus, "phone_instances", JSONObject().put("action", "unlock").put("break", true), yes = false, asked = asked).contains("did NOT approve"))
        assertEquals(1, asked.size)
        assertTrue(call(c, argus, "phone_instances", JSONObject().put("action", "unlock").put("break", true)).startsWith("Broke"))
        assertNull(c.beforeBrowserTool(argus, null, acts = true))
    }

    @Test fun theOwnersActsRenewItsLease() = runBlocking {
        val c = core()
        call(c, agent, "phone_instances", JSONObject().put("action", "new"))
        call(c, agent, "phone_instances", JSONObject().put("action", "lock").put("minutes", 1))
        val id = c.registry.active!!
        now += 50_000
        assertNull(c.beforeBrowserTool(agent, null, true)); c.afterBrowserTool(agent, "Tapping \u201cNext\u201d", typedNoSubmit = false)
        now += 50_000   // 100 s after the lock, 50 s after the act: still locked
        assertTrue(Leases.active(c.registry.get(id)!!.lease, now))
        assertEquals("Tapping \u201cNext\u201d", c.registry.get(id)!!.steps.last().text)
    }

    @Test fun shuttingDownOverSomeoneElsesWorkAsksFirst() = runBlocking {
        val c = core()
        call(c, agent, "phone_instances", JSONObject().put("action", "new"))
        call(c, agent, "phone_instances", JSONObject().put("action", "lock"))
        val asked = ArrayList<String>()
        assertTrue(call(c, argus, "phone_power", JSONObject().put("action", "off"), yes = false, asked = asked).contains("did NOT approve"))
        assertTrue(c.powered); assertEquals(1, asked.size)
        assertTrue(call(c, argus, "phone_power", JSONObject().put("action", "status")).contains("is on"))
        assertTrue(call(c, agent, "phone_power", JSONObject().put("action", "off")).contains("shut down"))
    }

    @Test fun closingUnsavedWorkAsksAndAPlainCloseDoesNot() = runBlocking {
        val c = core()
        call(c, argus, "phone_instances", JSONObject().put("action", "new").put("name", "Form"))
        c.afterBrowserTool(argus, "Typing", typedNoSubmit = true)
        val asked = ArrayList<String>()
        assertTrue(call(c, argus, "phone_instances", JSONObject().put("action", "close").put("name", "Form"), yes = false, asked = asked).contains("did NOT approve"))
        call(c, argus, "phone_instances", JSONObject().put("action", "new").put("name", "Plain"))
        assertTrue(call(c, argus, "phone_instances", JSONObject().put("action", "close").put("name", "Plain"), yes = false, asked = asked).startsWith("Closed"))
        assertEquals(1, asked.size)
    }

    @Test fun toolDefinitionsAreCatalogShaped() {
        val defs = PhoneInstanceTools.definitions()
        assertEquals(PhoneInstanceTools.NAMES, defs.map { it.getJSONObject("function").getString("name") }.toSet())
        for (d in defs) {
            val desc = d.getJSONObject("function").getString("description")
            assertTrue(desc.length <= 400); assertFalse(desc.contains("ALWAYS") || desc.contains("MUST"))
            assertTrue(desc.contains("Phone"))
        }
        val actions = defs.first { it.getJSONObject("function").getString("name") == "phone_instances" }
            .getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
        assertEquals(PhoneInstanceTools.ACTIONS, (0 until actions.length()).map { actions.getString(it) })
        assertFalse("not the browser tools' set", PhoneInstanceTools.NAMES.any { it in PhonePolicy.TOOLS })
    }

    @Test fun theNewToolsStayOutOfTheCoreAndThePrompt() {
        // docs/LEAN-TOOLS.md: catalog only. LeanTurn.CORE and the prompt must not name them; Agent.tools() registers them.
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val lean = File(root, "com/blaze/assistant/agent/LeanTurn.kt").readText()
        val core = Regex("""val CORE\s*=\s*(?:linkedSetOf|setOf|listOf)\(([^)]*)\)""").find(lean)?.groupValues?.get(1).orEmpty()
        assertTrue(core.isNotBlank())
        for (n in PhoneInstanceTools.NAMES) assertFalse(n, core.contains("\"$n\""))
        val agentKt = File(root, "com/blaze/assistant/agent/Agent.kt").readText()
        assertTrue(agentKt.contains("PhoneInstanceTools.definitions()"))
    }
}
