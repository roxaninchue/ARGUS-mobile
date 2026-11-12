package com.blaze.assistant.team

import com.blaze.assistant.store.JdbcSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * P1-TM-04: the team tools on a real SQLite team.db (sqlite-jdbc) with a fake chat log, fake team notes and a fake
 * clock. Ports A-team manager.js t_agents_list / t_agent_create / t_agent_update / t_agent_delete (the Manager only;
 * the Manager is locked and never deleted; delete asks first and needs a yes in the user's next message or the card's
 * button), chat.js update_agent (yourself or a bot that reports to you) and note_team with its Undo; plus the 25-min
 * pending delete and agent_status for the whole team (JobTools, A-team t_agent_status).
 */
class ManagerToolsTest {
    private val sql = JdbcSql()
    private var now = 1_731_400_000_000L
    private var ids = 0
    private val team = TeamStore(sql) { now }
    private val store = JobStore(sql, { now }) { "j${++ids}" }
    private val chat = HashMap<String, MutableList<ChatLog.Line>>()
    private val asks = ArrayList<ManagerTools.Pending?>()
    private val undos = ArrayList<Pair<String, () -> String>>()
    private val changed = ArrayList<String>()

    private class Notes : TeamNotes {
        val lines = LinkedHashMap<Long, String>()
        var next = 100L
        override fun add(text: String): TeamNotes.Saved {
            if (lines.values.any { it.equals(text, true) }) return TeamNotes.Saved("Already remembered.", null)
            val id = next++; lines[id] = text; return TeamNotes.Saved("Remembered: $text", id)
        }
        override fun remove(id: Long): Boolean = lines.remove(id) != null
    }

    private val notes = Notes()
    private val mgr = ManagerTools(team, store, { chat[it].orEmpty() }, notes, { now }, { asks += it }, { l, u -> undos += l to u }, { changed += it })
    private val queue = JobQueue(store, team, object : JobQueue.Runner {
        override fun start(job: Job, bot: Bot, brief: String): String? = null
        override fun alive(runId: String) = false
        override fun report(job: Job, bot: Bot?, event: JobQueue.Report) {}
    }, { now })
    private val jobs = JobTools(store, queue, team, today = { LocalDate.of(2026, 11, 12) })
    private val tools = TeamToolSet(jobs, mgr)
    private val M = BotRules.MANAGER_NAME

    init {
        sql.exec("PRAGMA foreign_keys = ON")
        sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
        team.ensure()
        for ((id, name, lead) in listOf(Triple("nova", "Nova", "manager"), Triple("atlas", "Atlas", "nova"), Triple("vega", "Vega", "manager")))
            team.insert(Bot(id = id, kind = BotKind.CUSTOM, name = name, label = "", avatar = Avatar("orb", "#22c55e"), soulPath = "",
                toolGroups = setOf("web"), reportsTo = lead, createdAt = now, u = now))
    }

    @After fun close() = sql.close()

    private fun t(name: String, args: String = "{}", bot: String? = null, chatId: String = "main") = tools.run(chatId, bot, name, args)
    private fun souls(id: String) = sql.query("SELECT count(*) FROM bot_soul_versions WHERE bot_id=?", id) { it.long(0) }.single()
    private fun say(text: String, chatId: String = "main") { now += 1_000; chat.getOrPut(chatId) { ArrayList() } += ChatLog.Line(text, now) }

    @Test fun oneToolSetOverJobsAndTeam() {
        assertEquals(JobTools.NAMES + ManagerTools.NAMES, tools.names())
        val defined = tools.defs().map { (TeamJson.read(it) as Map<*, *>)["function"].let { f -> (f as Map<*, *>)["name"] as String } }.toSet()
        assertEquals(tools.names(), defined)
        assertTrue(ManagerTools.NAMES.all { com.blaze.assistant.agent.ToolRouter.groupOf(it) == "team" })
        assertTrue("team" in com.blaze.assistant.agent.ToolRouter.guess("make me a new bot for research"))
        assertTrue("team" in com.blaze.assistant.agent.ToolRouter.guess("save a note for the team"))
        // agent_status stays a jobs tool (reused, not duplicated)
        assertEquals("jobs", com.blaze.assistant.agent.ToolRouter.groupOf("agent_status"))
        assertTrue(t("agents_list").contains("- Nova (id nova)"))
    }

    @Test fun onlyTheManagerManagesTheTeam() {
        for (n in ManagerTools.MANAGER_ONLY) assertEquals("Error: only $M can use $n.", t(n, """{"agent":"Atlas","name":"X"}""", bot = "nova"))
        val list = t("agents_list")
        assertTrue(list, list.startsWith("- $M (id manager) [Lead, you]"))
        assertTrue(list, list.contains("- Atlas (id atlas) \u00b7 reports to Nova \u00b7 0 open jobs"))
        assertFalse("hidden workers aren't listed", list.contains("worker-"))
    }

    @Test fun createsABotWithItsFields() {
        val r = t("agent_create", """{"name":"Orion","role":"Research","email":"orion@argus.ai","portfolio":"Market intel","instructions":"Be brief.",
            |"face":"Twin","color":"blue","reports_to":"Nova","effort":"max","mode":"teammate"}""".trimMargin())
        assertEquals("Created Orion (id orion), reports to Nova, effort xhigh, teammate, with instructions.", r)
        val b = team.get("orion")!!
        assertEquals(BotKind.CUSTOM, b.kind); assertEquals("Research", b.label); assertEquals("orion@argus.ai", b.email)
        assertEquals("Market intel", b.portfolio); assertEquals(4, b.avatar.face); assertEquals("#3b82f6", b.avatar.color)
        assertEquals("nova", b.reportsTo); assertEquals("xhigh", b.engine.model.effort); assertEquals("teammate", b.mode)
        assertEquals(BotDraft.DEFAULT_GROUPS, b.toolGroups)
        assertEquals("Be brief.", team.soul("orion"))
        assertEquals(listOf("orion"), changed)
        // defaults: reports to the Manager, next swatch by roster size, no soul
        assertEquals("Created Lyra (id lyra), reports to $M.", t("agent_create", """{"name":"Lyra"}"""))
        assertEquals(BotDraft.COLORS[7], team.get("lyra")!!.avatar.color)   // roster: Manager, Tempo, Inbox, Nova, Atlas, Vega, Orion
        assertNull(team.soul("lyra"))
        // refusals
        assertEquals("Error: a name is required.", t("agent_create", """{"role":"x"}"""))
        assertEquals("Error: Another bot is already called nova.", t("agent_create", """{"name":"nova"}"""))
        assertEquals("Error: $M is $M's name.", t("agent_create", """{"name":"$M"}"""))
        assertEquals("Error: That email doesn't look right.", t("agent_create", """{"name":"Kai","email":"kai at home"}"""))
        assertEquals("Error: effort is low, medium, high or xhigh.", t("agent_create", """{"name":"Kai","effort":"turbo"}"""))
        assertEquals("Error: there's no bot called \"Zed\" on the team.", t("agent_create", """{"name":"Kai","reports_to":"Zed"}"""))
        assertEquals("Error: the look is Orbit, Tilt, Steep, Pure, Twin or Polar.", t("agent_create", """{"name":"Kai","face":"Square"}"""))
        assertNull(team.get("kai"))
        // A-team slices long text instead of refusing
        t("agent_create", """{"name":"Long","role":"${"r".repeat(90)}","portfolio":"${"p".repeat(700)}"}""")
        assertEquals(60, team.get("long")!!.label.length); assertEquals(600, team.get("long")!!.portfolio.length)
    }

    @Test fun agentUpdateNeverTouchesTheManager() {
        val locked = "Error: ${TeamEdits.locked()}"
        assertEquals(locked, t("agent_update", """{"agent":"manager","name":"Boss"}"""))
        assertEquals(locked, t("agent_update", """{"agent":"$M","role":"x"}"""))
        assertEquals(M, team.get("manager")!!.name)
        assertEquals("Nova Prime: name, role, instructions, effort changed.", t("agent_update", """{"agent":"nova","name":"Nova Prime","role":"Research lead","instructions":"Cite sources.","effort":"high"}"""))
        val b = team.get("nova")!!
        assertEquals("Nova Prime", b.name); assertEquals("Research lead", b.label); assertEquals("high", b.engine.model.effort)
        assertEquals("Cite sources.", team.soul("nova")); assertEquals(1L, souls("nova"))
        // the soul is saved only when it changed
        assertEquals("Nova Prime: role changed.", t("agent_update", """{"agent":"nova","role":"Lead","instructions":"Cite sources."}"""))
        assertEquals(1L, souls("nova"))
        assertEquals("Error: nothing to change.", t("agent_update", """{"agent":"nova","role":"Lead"}"""))
        assertEquals("Error: a bot can't report to itself.", t("agent_update", """{"agent":"nova","reports_to":"Nova Prime"}"""))
        assertEquals("Error: That would make a loop: Atlas already reports to this bot.", t("agent_update", """{"agent":"nova","reports_to":"Atlas"}"""))
        assertEquals("Error: there's no bot called \"Zed\" on the team.", t("agent_update", """{"agent":"Zed","name":"Z"}"""))
        assertEquals("Error: Another bot is already called vega.", t("agent_update", """{"agent":"atlas","name":"vega"}"""))
    }

    @Test fun updateAgentIsYourselfOrYourReports() {
        assertEquals("Your name, role changed: name = Nova 2; role = Researcher", t("update_agent", """{"name":"Nova 2","role":"Researcher"}""", bot = "nova"))
        assertEquals("Researcher", team.get("nova")!!.label)
        assertEquals("Atlas's portfolio changed: portfolio = 14 accounts", t("update_agent", """{"for":"Atlas","portfolio":"14 accounts"}""", bot = "nova"))
        assertEquals("Error: you can change only yourself or a bot that reports to you.", t("update_agent", """{"for":"Vega","name":"V"}""", bot = "nova"))
        assertEquals("Error: you can change only yourself or a bot that reports to you.", t("update_agent", """{"for":"$M","name":"V"}""", bot = "nova"))
        assertEquals("Error: $M is locked. Its name, role, email and portfolio can't be changed.", t("update_agent", """{"name":"Boss"}"""))
        assertEquals("Error: nothing to change. Give a name, role, email or portfolio.", t("update_agent", "{}", bot = "atlas"))
        assertEquals("Error: That email doesn't look right.", t("update_agent", """{"email":"nope"}""", bot = "atlas"))
        assertEquals("Your email changed: email = atlas@argus.ai", t("update_agent", """{"email":"atlas@argus.ai"}""", bot = "atlas"))
        // in every bot's subset whatever its groups (ToolSubset.SELF), even though it lives in the team group
        val atlas = team.get("atlas")!!
        assertTrue(com.blaze.assistant.agent.engines.ToolSubset.allows(atlas, "update_agent", "team"))
        assertTrue(com.blaze.assistant.agent.engines.ToolSubset.allows(atlas, "note_team", "team"))
        assertFalse(com.blaze.assistant.agent.engines.ToolSubset.allows(atlas, "agent_create", "team"))
    }

    @Test fun deleteNeedsAYesInTheNextMessage() {
        say("delete Atlas")
        store.add("Follow up with Zamtel", "", "atlas")
        val ask = t("agent_delete", """{"agent":"Atlas"}""")
        assertTrue(ask, ask.startsWith("NOT deleted yet. Ask the user plainly whether to delete Atlas and stop"))
        val p = mgr.pending()!!
        assertEquals(ManagerTools.Pending("main", "atlas", "Atlas", now), p)
        assertEquals(listOf<ManagerTools.Pending?>(p), asks)
        // confirm in the same turn, before the user answered: nothing happens, the ask stays
        assertTrue(t("agent_delete", """{"agent":"Atlas","confirm":true}""").startsWith("NOT deleted yet: the user hasn't answered."))
        assertNotNull(team.get("atlas")); assertNotNull(mgr.pending())
        say("Yes please")
        assertEquals("Deleted Atlas; their jobs are unassigned.", t("agent_delete", """{"agent":"atlas","confirm":true}"""))
        assertNull(team.get("atlas")); assertNull(mgr.pending()); assertNull(asks.last())
        assertNull(store.all().single().botId)
        assertTrue("atlas" in changed)
    }

    @Test fun anythingButAYesKeepsTheBot() {
        say("remove vega")
        t("agent_delete", """{"agent":"Vega"}""")
        say("no wait, keep it")
        say("yes")   // only the first message after the ask counts
        assertEquals("NOT deleted: the user didn't say yes, so Vega stays. Don't delete it unless they ask again.", t("agent_delete", """{"agent":"Vega","confirm":true}"""))
        assertNotNull(team.get("vega")); assertNull(mgr.pending())
        // a yes said before the ask doesn't count, and confirm without an ask only asks
        say("yes")
        assertTrue(t("agent_delete", """{"agent":"Vega","confirm":true}""").startsWith("NOT deleted yet. Ask the user"))
        assertNotNull(team.get("vega"))
        // an ask in another chat isn't answered here
        say("yes", chatId = "other")
        assertTrue(t("agent_delete", """{"agent":"Vega","confirm":true}""", chatId = "other").startsWith("NOT deleted yet. Ask the user"))
        assertEquals("other", mgr.pending()!!.chatId)
    }

    @Test fun anAskLastsTwentyFiveMinutes() {
        t("agent_delete", """{"agent":"Vega"}""")
        now += ManagerTools.PENDING_MS - 1
        assertNotNull(mgr.pending())
        now += 2
        assertNull(mgr.pending()); assertNull(asks.last())
        say("yes")
        assertTrue(t("agent_delete", """{"agent":"Vega","confirm":true}""").startsWith("NOT deleted yet. Ask the user"))
        assertNotNull(team.get("vega"))
    }

    @Test fun theManagerIsNeverDeletedAndDomainsAreSwitchedOff() {
        assertEquals("Error: $M can never be deleted.", t("agent_delete", """{"agent":"manager"}"""))
        assertEquals("Error: $M can never be deleted.", t("agent_delete", """{"agent":"$M","confirm":true}"""))
        assertEquals("Error: $M can never be deleted.", mgr.confirmByButton("manager"))
        assertNull(mgr.pending())
        t("agent_delete", """{"agent":"Tempo"}""")
        say("ok")
        assertEquals("Switched Tempo off; it left the team and its jobs are unassigned.", t("agent_delete", """{"agent":"tempo","confirm":true}"""))
        val tempo = team.get("tempo")!!
        assertFalse(tempo.enabled); assertTrue(tempo.hidden)
    }

    @Test fun theCardsButtons() {
        assertEquals("Error: nothing is waiting to be deleted.", mgr.confirmByButton("vega"))
        t("agent_delete", """{"agent":"Vega"}""")
        assertEquals("Kept it. Nothing was deleted.", mgr.keep("vega"))
        assertNull(mgr.pending()); assertNotNull(team.get("vega"))
        t("agent_delete", """{"agent":"Vega"}""")
        assertEquals("Error: nothing is waiting to be deleted.", mgr.confirmByButton("nova"))
        assertEquals("Deleted Vega; their jobs are unassigned.", mgr.confirmByButton("vega"))
        assertNull(team.get("vega")); assertNull(mgr.pending())
    }

    @Test fun yesWords() {
        for (y in listOf("yes", "Y", "yeah sure", "yep", "yup", "sure", "OK", "okay then", "confirm", "confirmed", "do it", "go ahead",
            "delete it", "delete them", "please do", "yes please", "  Yes, delete him")) assertTrue(y, ManagerTools.YES.containsMatchIn(y))
        for (n in listOf("no", "nope", "keep it", "wait", "not yet", "yesterday was fine", "okayish", "maybe", "delete Vega? no")) assertFalse(n, ManagerTools.YES.containsMatchIn(n))
    }

    @Test fun teamNotesWithUndo() {
        assertEquals("Remembered: Team note: Lusaka office closes at 5", t("note_team", """{"text":"Lusaka office closes at 5"}""", bot = "nova"))
        assertEquals(listOf("Team note: Lusaka office closes at 5"), notes.lines.values.toList())
        assertEquals("Saved to team notes", undos.single().first)
        assertEquals("Removed from team notes.", undos.single().second())
        assertTrue(notes.lines.isEmpty())
        assertEquals("That note was already gone.", undos.single().second())
        // a note already saved offers no Undo (it would delete the older line)
        t("note_team", """{"text":"Team note: Fridays are short"}""")
        assertEquals("Already remembered.", t("note_team", """{"text":"Fridays are short"}"""))
        assertEquals(2, undos.size)
        assertEquals("Error: give the note's text.", t("note_team", "{}"))
    }

    @Test fun agentStatusForTheWholeTeam() {
        assertEquals("The team has no jobs yet.", t("agent_status"))
        t("add_job", """{"title":"Competitor pricing scan","agent":"Nova"}"""); now += 1
        t("add_job", """{"title":"Cooldown dashboard","agent":"Vega"}"""); now += 1
        t("add_job", """{"title":"Loose end"}""")
        val all = t("agent_status")
        assertEquals("The team's recent jobs:\n#j3 \u00abLoose end\u00bb (unassigned): todo\n#j2 \u00abCooldown dashboard\u00bb (Vega): todo\n" +
            "#j1 \u00abCompetitor pricing scan\u00bb (Nova): todo", all)
        assertTrue(t("agent_status", """{"agent":"Nova"}""").startsWith("Nova's recent jobs:\n#j1 \u00abCompetitor pricing scan\u00bb: todo"))
        assertTrue(t("agents_list").contains("- Nova (id nova) \u00b7 reports to $M \u00b7 1 open jobs"))
        assertEquals("Error: there's no bot called \"Zed\" on the team.", t("agent_status", """{"agent":"Zed"}"""))
    }
}
