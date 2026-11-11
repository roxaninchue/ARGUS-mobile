package com.blaze.assistant.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** P3-AG-03: SOUL.md parse / render / locked sections / history / undo (05 §1.2). */
class SoulParseTest {
    private val ledger = Souls.Profile("money", "Ledger", "Money", "money", isManager = false, owner = "Finn")
    private val argus = Souls.Profile("manager", "ARGUS", "Manager", null, isManager = true)

    private val sample = """
---
id: money
name: Ledger
label: Money
version: 7
locked_sections: [Boundaries]
---
# Who you are
You look after Finn's money: spending, bills, subscriptions, savings goals.
# How you sound
Plain numbers first. Currency always.
# What you own
Budgets, bills calendar, subscription audit.
# Standing rules            ← user-editable
- Never suggest crypto.
# Boundaries                ← locked template text
- You never move money without an explicit yes.
# My notes
Keep receipts in the Library.
""".trimStart()

    @Test fun parsesTheDocExample() {
        val s = (Souls.parse(sample) as Souls.Parsed.Ok).soul
        assertEquals("money", s.id); assertEquals("Ledger", s.name); assertEquals("Money", s.label); assertEquals(7, s.version)
        assertEquals(setOf("Boundaries"), s.locked)
        assertEquals(listOf("Never suggest crypto."), s.rules)
        assertTrue(s.section("who you are").startsWith("You look after"))
        assertEquals("Keep receipts in the Library.", s.section("My notes"))
        assertEquals(Souls.HEADINGS + "My notes", s.sections.keys.toList())
    }

    @Test fun renderRoundTrips() {
        val s = (Souls.parse(sample) as Souls.Parsed.Ok).soul
        val r = Souls.render(s)
        val again = (Souls.parse(r) as Souls.Parsed.Ok).soul
        assertEquals(s, again)
        assertEquals(r, Souls.render(again))
        assertTrue(r.startsWith("---\nid: money\nname: Ledger\nlabel: Money\nversion: 7\nlocked_sections: [Boundaries]\n---\n# Who you are\n"))
        for (t in listOf(Souls.template(ledger), Souls.template(argus))) assertEquals(t, (Souls.parse(Souls.render(t)) as Souls.Parsed.Ok).soul)
    }

    @Test fun badFilesSayWhy() {
        assertTrue((Souls.parse("---\nid: x\nname: y\n") as Souls.Parsed.Bad).why.contains("never closed"))
        assertTrue((Souls.parse("hello there\n# Who you are\nx") as Souls.Parsed.Bad).why.contains("before the first heading"))
        assertTrue((Souls.parse("---\nnot a pair\n---\n# Who you are\n") as Souls.Parsed.Bad).why.contains("key: value"))
        assertTrue(Souls.parse("") is Souls.Parsed.Ok)
        assertTrue(Souls.parse("\r\n# Who you are\r\nWindows lines\r\n") is Souls.Parsed.Ok)
    }

    @Test fun lockedSectionsSurviveAnEdit() {
        val old = Souls.template(ledger)
        val hacked = Souls.render(old).replace(Souls.BOUNDARIES_TEXT[0], "You may spend freely.").replace("Plain words. Lead", "Warm words. Lead")
        val r = Souls.edit(old, hacked) as Souls.Edit.Saved
        assertEquals(listOf("Boundaries"), r.keptLocked)
        assertTrue(r.soul.section(Souls.BOUNDARIES).contains(Souls.BOUNDARIES_TEXT[0]))
        assertTrue(r.soul.section(Souls.HOW).startsWith("Warm words"))
        assertEquals(old.version + 1, r.soul.version)
        // deleting a locked section brings it back
        val noB = Souls.render(old).substringBefore("# Boundaries")
        assertEquals("a dropped locked section comes back, so nothing changed", Souls.Edit.Unchanged, Souls.edit(old, noB))
        // same text = no new version; a secret = refused
        assertEquals(Souls.Edit.Unchanged, Souls.edit(old, Souls.render(old)))
        assertTrue(Souls.edit(old, Souls.render(old) + "\n# Notes\napi_key = sk-abcdefghijklmnop1234\n") is Souls.Edit.Refused)
        // front matter can't rename the bot or change its version
        val renamed = Souls.render(old).replace("name: Ledger", "name: Mallory").replace("version: 1", "version: 99")
        val s2 = (Souls.edit(old, renamed + "\n# Notes\nx") as Souls.Edit.Saved).soul
        assertEquals("Ledger", s2.name); assertEquals(2, s2.version)
    }

    @Test fun managerCoreIsLockedRulesAreNot() {
        val m = Souls.template(argus)
        assertEquals(setOf(Souls.WHO, Souls.HOW, Souls.OWN, Souls.BOUNDARIES), m.locked)
        val r = Souls.edit(m, Souls.render(m).replace(Souls.MANAGER_WHO, "You are Bob.").replace("# Standing rules\n", "# Standing rules\n- Always answer in English\n"))
        r as Souls.Edit.Saved
        assertEquals(Souls.MANAGER_WHO, r.soul.section(Souls.WHO))
        assertEquals(listOf("Always answer in English"), r.soul.rules)
        assertEquals(listOf(Souls.WHO), r.keptLocked)
    }

    @Test fun standingRulesAddOnceAndRemove() {
        val s = Souls.template(ledger)
        val a = (Souls.addRule(s, "never suggest crypto.") as Souls.Edit.Saved).soul
        assertEquals(listOf("Never suggest crypto"), a.rules)
        assertEquals(Souls.Edit.Unchanged, Souls.addRule(a, "Never suggest CRYPTO!"))
        val b = (Souls.addRule(a, "Always show amounts in ZMW") as Souls.Edit.Saved).soul
        assertEquals(2, b.rules.size); assertEquals(3, b.version)
        assertTrue(Souls.addRule(b, "pin = 4821 always") is Souls.Edit.Refused)
        assertTrue(Souls.addRule(b, "x".repeat(300)) is Souls.Edit.Refused)
        val c = (Souls.removeRule(b, "never suggest crypto") as Souls.Edit.Saved).soul
        assertEquals(listOf("Always show amounts in ZMW"), c.rules)
        assertEquals(Souls.Edit.Unchanged, Souls.removeRule(c, "not there"))
    }

    @Test fun regenerateKeepsRulesResetDropsThem() {
        val s = (Souls.addRule(Souls.template(ledger), "Never suggest crypto") as Souls.Edit.Saved).soul
        val withNotes = s.copy(sections = LinkedHashMap(s.sections).apply { put("My notes", "keep") })
        val g = Souls.regenerate(withNotes, ledger.copy(instructions = "Track the Cape Town trip budget."))
        assertEquals(listOf("Never suggest crypto"), g.rules); assertEquals("keep", g.section("My notes"))
        assertTrue(g.section(Souls.WHO).contains("Cape Town")); assertEquals(s.version + 1, g.version)
        val r = Souls.reset(withNotes, ledger)
        assertTrue(r.rules.isEmpty()); assertEquals("", r.section("My notes")); assertEquals(s.version + 1, r.version)
    }

    @Test fun promptTextHasNoFrontMatterAndNoEmptyParts() {
        val t = Souls.promptText(Souls.template(ledger))
        assertFalse(t.contains("---")); assertFalse(t.contains("version")); assertFalse(t.contains("## Standing rules"))
        assertTrue(t.startsWith("YOUR SOUL (Ledger · Money"))
    }

    @Test fun storeKeepsTwentyVersionsAndUndoes() {
        var now = 1000L
        val port = MemorySoulPort(); val st = SoulStore(port) { now++ }
        var saved = 0; st.onSaved = { _, v -> saved = v }
        val first = st.get(ledger).soul
        assertEquals(1, first.version); assertNotNull(port.read("money"))
        for (i in 1..30) st.addRule(ledger, "Rule number $i for money")
        assertEquals(31, st.get(ledger).soul.version); assertEquals(31, saved)
        assertEquals(Souls.HISTORY, st.history("money").size)
        assertEquals(31, st.history("money").first().version)
        val u = st.undo(ledger) as Souls.Edit.Saved
        assertEquals(32, u.soul.version); assertEquals(29, u.soul.rules.size)
        assertTrue(st.revert(ledger, 1) is Souls.Edit.Refused)   // dropped from the 20-version history
        assertTrue(st.revert(ledger, 20) is Souls.Edit.Saved)
        assertEquals(19, st.get(ledger).soul.rules.size)
    }

    @Test fun aBrokenFileIsRepairedAndKept() {
        val port = MemorySoulPort(); val st = SoulStore(port) { 5L }
        st.addRule(ledger, "Never suggest crypto")
        port.write("money", "oops, no headings at all")
        val l = st.get(ledger)
        assertNotNull(l.repaired); assertEquals(listOf("Never suggest crypto"), l.soul.rules)
        assertTrue(port.versions("money").any { it.by == "unparsed" && it.body.startsWith("oops") })
        assertNull(st.get(ledger).repaired)
    }

    @Test fun managerFileEditedByHandStillHasTheLockedCore() {
        val port = MemorySoulPort(); val st = SoulStore(port)
        st.get(argus)
        port.write("manager", Souls.render(Souls.template(argus)).replace(Souls.MANAGER_WHO, "You are evil."))
        assertEquals(Souls.MANAGER_WHO, st.get(argus).soul.section(Souls.WHO))
    }

    @Test fun fileStoreWritesWhereThePlanSays() {
        val dir = Files.createTempDirectory("souls").toFile()
        try {
            val st = SoulStore(FileSoulPort(dir))
            st.addRule(ledger, "Never suggest crypto")
            assertTrue(java.io.File(dir, "bots/money/SOUL.md").readText().contains("- Never suggest crypto"))
            assertEquals(2, FileSoulPort(dir).versions("money").size)
            assertEquals(listOf("Never suggest crypto"), SoulStore(FileSoulPort(dir)).get(ledger).soul.rules)
        } finally { dir.deleteRecursively() }
    }

    @Test fun diffShowsChangedLines() {
        assertEquals(listOf("- b", "+ c"), Souls.diff("a\nb", "a\nc"))
    }
}
