package com.blaze.assistant.team

import com.blaze.assistant.agent.Playbooks
import com.blaze.assistant.memory.MemScopes
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** P5-AG-01: the 14 life-domain templates (05 §3), grouped into 10 Life areas (06 R5); private scopes for Pulse/Ledger/Clerk. */
class DomainTemplateTest {
    private val root = listOf(File("src/main/assets/domains"), File("app/src/main/assets/domains")).first { it.isDirectory }
    private val specialists = listOf(File("src/main/assets/specialists"), File("app/src/main/assets/specialists")).first { it.isDirectory }
    private val sharedSkills = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
    private val now = 1_700_000_000_000L

    @Before fun setUp() { Domains.loader = { p -> File(root, p).takeIf { it.isFile }?.readText() }; Domains.userLoader = null; Domains.userIds = null; Domains.reset(); Playbooks.resetDomains() }
    @After fun tearDown() { Domains.loader = null; Domains.userLoader = null; Domains.userIds = null; Domains.reset(); Playbooks.resetDomains() }

    private fun t(id: String) = Domains.get(id)!!

    @Test fun catalogHasAllFourteenInIndexOrder() {
        assertEquals(listOf("tempo", "inbox", "pulse", "ledger", "career", "forge", "hearth", "pantry", "atlas", "circle", "tutor", "clerk", "scout", "studio"), Domains.IDS)
        assertEquals(Domains.IDS, Domains.indexIds())
        val cat = Domains.catalog()
        assertEquals(14, cat.size)
        assertEquals(Domains.IDS, cat.map { it.id })
        for (id in Domains.IDS) {
            val p = Domains.parse(File(root, "$id/domain.json").readText())
            assertTrue("$id: $p", p is Domains.Parsed.Ok)
            assertEquals("folder = id", id, (p as Domains.Parsed.Ok).t.id)
        }
        // the folder holds exactly the catalog
        assertEquals(Domains.IDS.toSet(), root.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet())
        assertEquals(listOf("Tempo", "Inbox", "Pulse", "Ledger", "Career", "Forge", "Hearth", "Pantry", "Atlas", "Circle", "Tutor", "Clerk", "Scout", "Studio"), cat.map { it.defaultName })
    }

    @Test fun everyTemplateAndItsFilesValidate() {
        val cat = Domains.catalog()
        val domainBooks = cat.associate { it.id to File(root, "${it.id}/PLAYBOOK.md").readText() }
        val specBooks = specialists.listFiles()!!.filter { it.name.endsWith(".md") }.map { it.readText() }
        for (d in cat) {
            val c = Domains.validate(d, cat)
            assertTrue("${d.id}: ${c.problems}", c.ok)
            val others = domainBooks.filterKeys { it != d.id }.values.toList() + specBooks
            val f = Domains.validateFiles(d, Domains.soul(d), Domains.playbook(d), { Domains.skill(d, it) }, others)
            assertTrue("${d.id} files: ${f.problems}", f.ok)
            // the Playbooks validator itself, as the ticket says
            val pb = Playbooks.validateDomain(domainBooks.getValue(d.id), Domains.fieldOf(d), others)
            assertTrue("${d.id} playbook: ${pb.problems}", pb.ok)
            assertEquals(Playbooks.SECTIONS.map { it.key }.toSet(), Playbooks.sections(domainBooks.getValue(d.id)).keys)
            assertTrue(d.skills.isNotEmpty())
            for (s in d.sharedSkills) assertTrue("${d.id} shared skill $s exists in assets/skills", File(sharedSkills, "$s/SKILL.md").isFile)
            assertTrue("${d.id} has a default routine", d.routines.isNotEmpty())
            assertEquals("${d.id} spend cap starts at 0", 0L, d.permissions.spendCapMonthly)
        }
        assertEquals(14, domainBooks.size)
    }

    @Test fun soulsParseWithSoulsAndLockBoundaries() {
        for (d in Domains.catalog()) {
            val s = (Souls.parse(Domains.soul(d)!!) as Souls.Parsed.Ok).soul
            assertEquals(d.id, s.id); assertEquals(d.defaultName, s.name); assertEquals(d.label, s.label); assertEquals(1, s.version)
            assertEquals(setOf(Souls.BOUNDARIES), s.locked)
            assertTrue(s.rules.isEmpty())
            // render → parse is stable (the SoulStore can save it as is)
            assertEquals(Souls.render(s), Souls.render((Souls.parse(Souls.render(s)) as Souls.Parsed.Ok).soul))
            // a user edit can't change the Boundaries
            val edited = Souls.render(s).replace(Souls.BOUNDARIES_TEXT[0], "Anything goes.").replace("# Standing rules\n", "# Standing rules\n- Never suggest crypto.\n")
            val e = Souls.edit(s, edited) as Souls.Edit.Saved
            assertTrue(e.keptLocked.contains(Souls.BOUNDARIES))
            assertTrue(e.soul.section(Souls.BOUNDARIES).contains(Souls.BOUNDARIES_TEXT[0]))
            assertEquals(listOf("Never suggest crypto"), e.soul.rules.map { it.removeSuffix(".") })
        }
    }

    @Test fun tenLifeAreasGroupTheFourteenAsR5Says() {
        assertEquals(10, Domains.AREAS.size)
        assertEquals(listOf("health", "money", "work", "home", "people", "learning", "travel", "create", "mind", "phone"), Domains.AREAS.map { it.id })
        val all = Domains.AREAS.flatMap { it.domains }
        assertEquals("every domain in exactly one area", Domains.IDS.sorted(), all.sorted())
        assertEquals(listOf("pulse"), Domains.area("health")!!.domains)
        assertEquals(listOf("ledger", "scout"), Domains.area("money")!!.domains)
        assertEquals(listOf("career", "inbox", "tempo", "forge"), Domains.area("work")!!.domains)
        assertEquals(listOf("hearth", "pantry", "clerk"), Domains.area("home")!!.domains)
        for (a in listOf("mind", "phone")) { assertTrue(Domains.area(a)!!.managerOwned); assertEquals(Bots.MANAGER_ID, Domains.area(a)!!.lead) }
        assertEquals("ledger", Domains.area("money")!!.lead)
        for (d in Domains.catalog()) assertEquals(d.id, Domains.areaOf(d.id)!!.id, d.area)
        // index.json carries the same areas (hues from 04 §3.3)
        val idx = JSONObject(File(root, "index.json").readText())
        val areas = idx.getJSONArray("areas")
        assertEquals(10, areas.length())
        for (i in 0 until areas.length()) {
            val o = areas.getJSONObject(i); val a = Domains.AREAS[i]
            assertEquals(a.id, o.getString("id")); assertEquals(a.label, o.getString("label")); assertEquals(a.hue, o.getString("hue"))
            assertEquals(a.glyph, o.getString("glyph")); assertEquals(a.lead, o.getString("lead"))
            assertEquals(a.domains, (0 until o.getJSONArray("domains").length()).map { o.getJSONArray("domains").getString(it) })
        }
        assertEquals(Domains.SEEDED, (0 until idx.getJSONArray("seeded").length()).map { idx.getJSONArray("seeded").getString(it) })
    }

    @Test fun pulseLedgerAndClerkOwnPrivateScopes() {
        val priv = Domains.catalog().filter { it.private }.map { it.id }.toSet()
        assertEquals(setOf("pulse", "ledger", "clerk"), priv)
        assertEquals(Domains.PRIVATE, priv)
        assertEquals("health", t("pulse").memory.owns); assertEquals("money", t("ledger").memory.owns); assertEquals("admin", t("clerk").memory.owns)
        for (d in Domains.catalog()) assertEquals(d.id, d.private, MemScopes.isPrivateDomain(d.memory.owns))
        assertEquals(listOf("health:mind"), t("pulse").memory.optional)
        assertTrue(MemScopes.isPrivateDomain("health:mind"))

        // On the roster: Ledger reads its money line; Atlas (reads money:budget-caps only) and the Manager can't, the Manager sees it exists.
        val bots = Domains.catalog().map { Domains.toBot(it, now) }
        val roster = bots.map { Triple(it.id, it.domain, it.reportsTo) } + Triple(Bots.MANAGER_ID, null, null)
        fun viewer(b: Bot) = MemScopes.viewer(b.id, b.domain, false, b.permissions.dataScopes, roster)
        val ledger = bots.first { it.id == "ledger" }; val atlas = bots.first { it.id == "atlas" }; val pulse = bots.first { it.id == "pulse" }
        val manager = MemScopes.viewer(Bots.MANAGER_ID, null, true, setOf("user:*", "team"), roster)
        val money = MemScopes.Line(1, "Salary lands on the 25th", "money")
        val caps = MemScopes.Line(2, "Trips under K15,000", "money:budget-caps")
        val health = MemScopes.Line(3, "Knee injury, no running", "health")
        assertTrue(MemScopes.visible(viewer(ledger), money))
        assertFalse(MemScopes.visible(viewer(atlas), money))
        assertTrue(MemScopes.visible(viewer(atlas), caps))
        assertFalse(MemScopes.visible(manager, money))
        assertFalse(MemScopes.visible(viewer(ledger), health))
        assertTrue(MemScopes.visible(viewer(pulse), health))
        assertEquals(mapOf("health" to 1, "money" to 1), MemScopes.hiddenCounts(manager, listOf(money, health)))
        // Private domains only draft and list sharing under approvals; their souls say so.
        for (id in Domains.PRIVATE) {
            val d = t(id)
            assertTrue(d.permissions.sendScope in setOf("NONE", "DRAFT_ONLY"))
            assertTrue(d.approvals.any { it.contains("shar", true) })
            val s = (Souls.parse(Domains.soul(d)!!) as Souls.Parsed.Ok).soul
            assertTrue(id, s.section(Souls.BOUNDARIES).contains("private"))
        }
        // A template claiming a non-private scope is private (or the reverse) is refused.
        val bad = Domains.validate(t("pulse").copy(memory = t("pulse").memory.copy(private = false)), emptyList())
        assertFalse(bad.ok); assertTrue(bad.problems.toString(), bad.problems.any { it.contains("memory.private") })
        val sneaky = Domains.validate(t("atlas").copy(memory = t("atlas").memory.copy(reads = listOf("user:core", "money"))), emptyList())
        assertTrue(sneaky.problems.toString(), sneaky.problems.any { it.contains("private scope") })
    }

    @Test fun seededTempoAndInboxMatchTheRoster() {
        assertEquals(Bots.SEED_DOMAINS, Domains.SEEDED)
        for (id in Domains.SEEDED) {
            val d = t(id); assertTrue(d.seeded)
            val fromCatalog = Domains.toBot(d, now)
            val seeded = Bots.seed(id, now)!!
            assertTrue("$id catalog = Bots.seed identity", Bots.sameIdentity(fromCatalog, seeded))
        }
        assertTrue(Domains.catalog().filter { it.id !in Domains.SEEDED }.none { it.seeded })
        assertEquals(listOf("founder"), t("forge").proposeFor)
    }

    @Test fun toBotCarriesPermissionsScopesAndSkills() {
        val b = Domains.toBot(t("ledger"), now)
        assertEquals(BotKind.DOMAIN, b.kind); assertEquals("Ledger", b.name); assertEquals("Money", b.label); assertEquals("money", b.domain)
        assertEquals(Bots.MANAGER_ID, b.reportsTo); assertFalse(b.enabled); assertEquals("bots/ledger/SOUL.md", b.soulPath)
        assertEquals(Rule.ASK, b.permissions.actions[ActionClass.SPEND]); assertEquals(Rule.NEVER, b.permissions.actions[ActionClass.SEND])
        assertEquals(SendScope.NONE, b.permissions.sendScope); assertEquals(0L, b.permissions.spendCapMonthlyMinor)
        assertTrue(b.permissions.dataScopes.containsAll(setOf("user:core", "team", "money")))
        assertTrue(b.skills.contains("domain:ledger/bills-calendar"))
        assertEquals("Ledger \u00b7 Money", Bots.display(b))
        val f = Domains.toBot(t("forge"), now, name = "Anvil", enabled = true)
        assertEquals("Anvil", f.name); assertTrue(f.enabled); assertEquals("work:argus", f.domain)
        assertEquals(Rule.NEVER, f.permissions.actions[ActionClass.CREDENTIAL])
        for (d in Domains.catalog()) {
            val x = Domains.toBot(d, now)
            for (k in listOf(ActionClass.SPEND, ActionClass.SEND, ActionClass.DELETE, ActionClass.CREDENTIAL)) assertTrue("${d.id} $k", x.permissions.actions[k] != Rule.ALLOW)
        }
    }

    @Test fun routingHintsAreUniqueAndPickTheOwner() {
        val all = Domains.catalog().flatMap { it.routingHints }
        assertEquals(all.size, all.toSet().size)
        val cases = mapOf(
            "move my meeting to tomorrow" to "tempo", "any unread email from Sam?" to "inbox", "plan my gym training" to "pulse",
            "can I afford this, what's my budget" to "ledger", "update my cv for this job" to "career", "why did the pipeline fail on the merge request" to "forge",
            "find a plumber for the leak" to "hearth", "what's for dinner, need a recipe" to "pantry", "book a flight and hotel for my trip" to "atlas",
            "it's my friend's birthday, gift ideas" to "circle", "make flashcards for my exam" to "tutor", "my passport renewal form" to "clerk",
            "cheapest deal on this product" to "scout", "design a poster and caption" to "studio",
        )
        for ((text, want) in cases) {
            val scores = Domains.catalog().associate { it.id to Domains.hintHits(text, it.routingHints) }
            val best = scores.maxByOrNull { it.value }!!
            assertEquals(text, want, best.key)
            assertEquals("one winner for \"$text\"", 1, scores.values.count { it == best.value })
        }
    }

    @Test fun renderParseRoundTrip() {
        for (d in Domains.catalog()) {
            val back = (Domains.parse(Domains.render(d)) as Domains.Parsed.Ok).t
            assertEquals(d, back)
        }
    }

    @Test fun validatorRefusesBrokenTemplates() {
        val l = t("ledger"); val cat = Domains.catalog()
        fun probs(x: Domains.Template) = Domains.validate(x, cat).problems.toString()
        assertTrue(probs(l.copy(permissions = l.permissions.copy(actions = mapOf("SPEND" to "ALLOW")))).contains("SPEND may not be ALLOW"))
        assertTrue(probs(l.copy(routines = listOf(Domains.RoutineT("Bad", "CRON", "every day", "Check the bills due soon.")))).contains("doesn't fit kind"))
        assertTrue(probs(l.copy(toolGroups = l.toolGroups + "teleport")).contains("unknown tool groups"))
        assertTrue(probs(l.copy(area = "space")).contains("area"))
        assertTrue(probs(l.copy(id = "scout2", defaultName = "Scout")).contains("defaultName"))
        assertTrue(probs(l.copy(id = "money2", defaultName = "Coins", memory = l.memory)).contains("also owned by ledger"))
        assertTrue(probs(l.copy(defaultName = "ARGUS")).contains("Manager"))
        assertTrue(probs(l.copy(permissions = l.permissions.copy(spendCapMonthly = 500))).contains("spendCapMonthly"))
        assertTrue(probs(l.copy(workers = listOf("wizard"))).contains("unknown workers"))
        // a copied playbook is refused by the Playbooks gate
        val atlas = t("atlas")
        val copied = Domains.validateFiles(atlas, Domains.soul(atlas), Domains.playbook(l), { Domains.skill(atlas, it) }, listOf(Domains.playbook(l)!!))
        assertFalse(copied.ok); assertTrue(copied.problems.toString(), copied.problems.any { it.contains("Copied") || it.contains("Not specific") })
        // a soul that doesn't match the template is refused
        val wrongSoul = Domains.validateFiles(atlas, Domains.soul(l), Domains.playbook(atlas), { Domains.skill(atlas, it) })
        assertTrue(wrongSoul.problems.toString(), wrongSoul.problems.any { it.contains("SOUL.md id") })
        assertTrue(Domains.parse("{nope") is Domains.Parsed.Bad)
        assertTrue(Domains.parse("{\"id\":\"x\"}") is Domains.Parsed.Bad)
    }

    @Test fun playbooksDomainGateAddsTitleAndApprovals() {
        val pb = Domains.playbook(t("ledger"))!!
        val f = Domains.fieldOf(t("ledger"))
        assertTrue(Playbooks.validateDomain(pb, f).ok)
        val noTitle = Playbooks.validateDomain(pb.replace("# Ledger playbook", "# Ledger notes"), f)
        assertTrue(noTitle.problems.toString(), noTitle.problems.any { it.contains("title") })
        val noApproval = Playbooks.validateDomain(pb.replace(Regex("(?i)approval card|approval"), "say-so"), f)
        assertTrue(noApproval.problems.toString(), noApproval.problems.any { it.contains("approval") })
        // the specialist gate is unchanged: the same text passes validate()
        assertTrue(Playbooks.validate(pb, f).ok)
        assertTrue(Playbooks.forDomain("ledger").startsWith("# Ledger playbook"))
        assertEquals("", Playbooks.forDomain("nobody"))
    }

    @Test fun createDomainFromTemplate() {
        val cat = Domains.catalog()
        val r = Domains.fromTemplate(Domains.NewDomain("Paws", "Pet care", "Vet visits, feeding, walks and the dog's medicine.", hints = listOf("dog", "vet", "pet", "walks", "home")), t("hearth"), cat, listOf("manager", "tempo", "inbox"))
        assertTrue(r.toString(), r is Domains.Created.Ok)
        val ok = r as Domains.Created.Ok
        val p = ok.template
        assertEquals("paws", p.id); assertEquals("Paws", p.defaultName); assertEquals("Pet care", p.label); assertEquals("pet-care", p.memory.owns)
        assertEquals("home", p.area); assertEquals("hearth", p.basedOn); assertFalse(p.builtIn); assertFalse(p.private); assertFalse(p.seeded)
        assertFalse("\"home\" is Hearth's hint, dropped", "home" in p.routingHints)
        assertTrue(p.routingHints.containsAll(listOf("dog", "vet", "pet")))
        assertEquals(t("hearth").toolGroups, p.toolGroups)
        assertTrue(Domains.validate(p, cat).ok)
        assertNull(ok.playbook)
        val s = (Souls.parse(ok.soul) as Souls.Parsed.Ok).soul
        assertEquals("paws", s.id); assertEquals("Paws", s.name); assertTrue(Souls.BOUNDARIES in s.locked)
        assertTrue(s.section(Souls.WHO).contains("Paws")); assertTrue(s.section(Souls.OWN).contains("Vet visits"))
        assertTrue(s.section(Souls.BOUNDARIES).contains(Souls.BOUNDARIES_TEXT[0]))
        assertFalse(s.section(Souls.BOUNDARIES).contains("Hearth"))

        // refusals
        assertTrue(Domains.fromTemplate(Domains.NewDomain("ARGUS", "Boss", "Runs everything for everyone."), null, cat, emptyList()) is Domains.Created.Refused)
        assertTrue(Domains.fromTemplate(Domains.NewDomain("Ledger", "Money 2", "More money things."), null, cat, emptyList()) is Domains.Created.Refused)
        assertTrue(Domains.fromTemplate(Domains.NewDomain("Echo", "", "x"), null, cat, emptyList()) is Domains.Created.Refused)
        // a label that would take a private or taken scope gets its own
        val m = Domains.fromTemplate(Domains.NewDomain("Coins", "Money", "Pocket money for the kids and their chores.", hints = listOf("pocket money", "chores", "allowance")), null, cat, emptyList()) as Domains.Created.Ok
        assertEquals("x-coins", m.template.memory.owns); assertFalse(m.template.private)

        // saved in filesDir/domains and merged into the catalog after the built-ins
        val dir = Files.createTempDirectory("domains").toFile()
        try {
            val ud = UserDomains(dir)
            ud.save(p, ok.soul, null)
            ud.install()
            assertEquals(listOf("paws"), ud.ids())
            val cat2 = Domains.catalog()
            assertEquals(15, cat2.size); assertEquals("paws", cat2.last().id); assertFalse(cat2.last().builtIn)
            assertEquals("home", Domains.areaOf("paws")!!.id)
            assertNotNull(Domains.soul(Domains.get("paws")!!))
            ud.delete("tempo"); assertEquals(15, Domains.catalog().size)   // built-ins can't be deleted from here
            ud.delete("paws"); Domains.reset(); assertEquals(14, Domains.catalog().size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun installSoulSeedsFromTheTemplateOnce() {
        val port = MemorySoulPort()
        val d = t("atlas")
        val b = Domains.toBot(d, now, id = "atlas", name = "Compass")
        assertTrue(Domains.installSoul(port, d, b, now))
        val s = (Souls.parse(port.read("atlas")!!) as Souls.Parsed.Ok).soul
        assertEquals("Compass", s.name); assertEquals("Travel", s.label); assertTrue(s.section(Souls.WHO).startsWith("You are Compass"))
        assertEquals(1, port.versions("atlas").size)
        assertFalse("never overwrites the user's soul", Domains.installSoul(port, d, b, now))
        // SoulStore then reads it as is, and the profile keeps the domain's "What you own"
        val store = SoulStore(port) { now }
        val loaded = store.get(Domains.profile(d, b))
        assertNull(loaded.repaired); assertEquals("Compass", loaded.soul.name)
        assertTrue(loaded.soul.section(Souls.OWN).contains("itineraries"))
        assertEquals(d.about, Domains.profile(d, b).owns)
    }
}
