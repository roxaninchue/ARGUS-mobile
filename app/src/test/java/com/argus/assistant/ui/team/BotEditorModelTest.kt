package com.blaze.assistant.ui.team

import com.blaze.assistant.agent.Specialists
import com.blaze.assistant.store.JdbcSql
import com.blaze.assistant.team.Avatar
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotDraft
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.TeamEdits
import com.blaze.assistant.team.TeamSchema
import com.blaze.assistant.team.TeamStore
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.OrbFace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-TM-04: the bot editor's rules (BotEditorModel + BotDraft + TeamEdits) on a real SQLite team.db. Ports A-team
 * agentSheet (Name, Email, Role, Portfolio, Instructions, Look = 6 faces × COLORS + any colour, Reports to, Delete;
 * the Manager's own locked card), Bot HQ's personal / teammate mode, and the 06 ticket's New bot choices (Blank / From a
 * template / Describe it).
 */
class BotEditorModelTest {
    private val sql = JdbcSql()
    private var now = 1_731_400_000_000L
    private val store = TeamStore(sql) { now }
    private val edits = TeamEdits(store) { now }

    init {
        sql.exec("PRAGMA foreign_keys = ON")
        sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
        store.ensure()
        listOf(Triple("nova", "Nova", "manager"), Triple("atlas", "Atlas", "nova"), Triple("echo", "Echo", "atlas")).forEachIndexed { i, (id, name, lead) ->
            store.insert(Bot(id = id, kind = BotKind.CUSTOM, name = name, label = "", avatar = Avatar("orb", "#22c55e"), soulPath = "",
                toolGroups = setOf("web"), reportsTo = lead, createdAt = now + 10 + i, u = now))
        }
    }

    @After fun close() = sql.close()

    @Test fun facesAndColoursAreTheOrbsAndTheTokens() {
        assertEquals(OrbFace.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, BotDraft.FACES)
        assertEquals(ArgusTokens.BOT_COLORS.keys.toList(), BotDraft.COLOR_NAMES)
        assertEquals(ArgusTokens.BOT_COLORS.values.toList(), BotDraft.COLORS.map { ArgusTokens.botColor(it) })
        assertEquals("#3b82f6", BotDraft.colorOf("Blue")); assertEquals("#3b82f6", BotDraft.colorOf("3B82F6")); assertNull(BotDraft.colorOf("#3b82"))
        assertEquals("#123456", BotEditorModel.wheelColor("#123456FF")); assertEquals("#abcdef", BotEditorModel.wheelColor("#ABCDEF"))
        assertEquals(3, BotDraft.faceOf("pure")); assertEquals(5, BotDraft.faceOf(5)); assertNull(BotDraft.faceOf(6))
        assertEquals("xhigh", BotDraft.effortOf("Max")); assertEquals("xhigh", BotDraft.effortOf("x-high")); assertNull(BotDraft.effortOf("turbo"))
        assertEquals(listOf("low", "medium", "high", "xhigh"), BotEditorModel.EFFORT_CHIPS.map { it.key })
        assertEquals(listOf("personal", "teammate"), BotEditorModel.MODE_CHIPS.map { it.key })
    }

    @Test fun newBotChoicesAndDescribe() {
        assertEquals(listOf("Blank", "From a template", "Describe it"), NewBotChoice.entries.map { it.title })
        assertEquals("Draft a new bot for me: keeps my reading list. Suggest its name, role, look and instructions, show me the draft, " +
            "and create it with agent_create once I say yes.", BotEditorModel.describePrompt("  keeps my reading list. "))
        val blank = BotEditorModel.open(store.all())
        assertTrue(blank.isNew); assertEquals("New bot", blank.title); assertEquals("Add bot", blank.primary)
        assertFalse("no name yet", blank.canSave); assertTrue("no problems shown before a name", blank.problems.isEmpty())
        assertEquals(BotDraft.COLORS[6], blank.draft.color)   // six on the roster: Manager, Tempo, Inbox, Nova, Atlas, Echo
        assertEquals(BotRules.MANAGER_ID, blank.draft.reportsTo); assertEquals("medium", blank.draft.effort); assertEquals("personal", blank.draft.mode)
        assertFalse(blank.canDelete)
    }

    @Test fun templates() {
        val ts = BotEditorModel.templates()
        assertEquals(Specialists.builtIn.size + 4 + 2, ts.size)
        assertEquals(Specialists.builtIn.map { it.name }, ts.filter { it.group == BotEditorModel.GROUP_SPECIALISTS }.map { it.title })
        val researcher = ts.first { it.id == "spec:researcher" }.draft
        assertEquals(Specialists.builtIn.first { it.id == "researcher" }.groups, researcher.toolGroups)
        assertTrue(researcher.instructions.startsWith("You research on the web."))
        assertEquals(listOf("Nova", "Atlas", "Echo", "Vega"), ts.filter { it.group == BotEditorModel.GROUP_DEMO }.map { it.title })
        val vega = ts.first { it.id == "demo:vega" }.draft
        assertEquals("vega@argus.ai", vega.email); assertEquals("Engineering", vega.label); assertEquals(1, vega.face)
        assertEquals(listOf("personal", "teammate"), ts.filter { it.group == BotEditorModel.GROUP_MODES }.map { it.draft.mode })
        for (t in ts) assertTrue("${t.id}: ${t.draft.copy(name = "Fresh").problems(store.all())}", t.draft.copy(name = "Fresh").problems(store.all()).isEmpty())
        // a template whose name is taken gets a free one
        assertEquals("Nova 2", BotEditorModel.fromTemplate(ts.first { it.id == "demo:nova" }, store.all()).name)
        assertEquals("Atlas 2", BotEditorModel.uniqueName("Atlas", store.all()))
        assertEquals("${BotRules.MANAGER_NAME} 2", BotEditorModel.uniqueName(BotRules.MANAGER_NAME, store.all()))
    }

    @Test fun problemsInTheUsersWords() {
        val team = store.all()
        fun p(d: BotDraft, self: String? = null) = d.problems(team, self)
        val ok = BotDraft(name = "Kai")
        assertTrue(p(ok).isEmpty())
        assertEquals(listOf("Give it a name."), p(ok.copy(name = " ")))
        assertEquals(listOf("Another bot is already called nova."), p(ok.copy(name = "nova")))
        assertEquals(listOf("Keep the label under 60 characters."), p(ok.copy(label = "x".repeat(61))))
        assertEquals(listOf("That email doesn't look right."), p(ok.copy(email = "kai@home")))
        assertTrue(p(ok.copy(email = "kai@argus.ai")).isEmpty())
        assertEquals(listOf("That email doesn't look right."), p(ok.copy(email = "k".repeat(116) + "@a.io")))
        assertEquals(listOf("Keep the portfolio under 600 characters."), p(ok.copy(portfolio = "x".repeat(601))))
        assertEquals(listOf("Keep the instructions under 4000 characters."), p(ok.copy(instructions = "x".repeat(4001))))
        assertEquals(listOf("Pick one of the six looks."), p(ok.copy(face = 6)))
        assertEquals(listOf("Pick a colour like #3b82f6."), p(ok.copy(color = "teal")))
        assertEquals(listOf("Effort is low, medium, high or xhigh."), p(ok.copy(effort = "max")))   // stored words only; effortOf maps "max"
        assertEquals(listOf("Mode is personal or teammate."), p(ok.copy(mode = "boss")))
        assertEquals(listOf("Pick who it reports to from the team."), p(ok.copy(reportsTo = "zed")))
        assertEquals(listOf("A bot can't report to itself."), p(BotDraft.of(store.get("nova")!!, null).copy(reportsTo = "nova"), "nova"))
        assertEquals(listOf("That would make a loop: Echo already reports to this bot."), p(BotDraft.of(store.get("nova")!!, null).copy(reportsTo = "echo"), "nova"))
        assertTrue(p(BotDraft.of(store.get("echo")!!, null).copy(reportsTo = "nova"), "echo").isEmpty())
    }

    @Test fun reportsToNeverItselfNorALoop() {
        val team = store.all()
        assertEquals(listOf("manager", "tempo", "inbox", "nova", "atlas", "echo"), BotEditorModel.reportsToOptions(team, null).map { it.id })
        assertEquals(BotRules.MANAGER_NAME, BotEditorModel.reportsToOptions(team, null).first().name)
        assertEquals(listOf("manager", "tempo", "inbox"), BotEditorModel.reportsToOptions(team, "nova").map { it.id })   // Atlas and Echo report up to Nova
        assertEquals(listOf("manager", "tempo", "inbox", "nova"), BotEditorModel.reportsToOptions(team, "atlas").map { it.id })
    }

    @Test fun theManagerOpensReadOnly() {
        val m = store.get(BotRules.MANAGER_ID)!!
        assertTrue(BotEditorModel.readOnly(m)); assertFalse(BotEditorModel.readOnly(store.get("nova")))
        val card = BotEditorModel.managerCard(m)
        assertEquals(BotRules.MANAGER_NAME, card.name); assertEquals(BotRules.MANAGER_PORTFOLIO, card.portfolio)
        assertTrue(card.note.contains("can't be deleted"))
        assertFalse(EditorState(BotDraft(name = "x"), BotRules.MANAGER_ID).canDelete)
        assertEquals(TeamEdits.locked(), (edits.update(BotRules.MANAGER_ID, BotDraft.of(m, null).copy(name = "Boss"), "user") as TeamStore.Result.Refused).why)
        assertEquals(BotRules.MANAGER_NAME, store.get(BotRules.MANAGER_ID)!!.name)
    }

    @Test fun createAndEditThroughTeamEdits() {
        val d = BotDraft(name = "Orion", label = "Research", email = "orion@argus.ai", portfolio = "Market intel", instructions = "Cite sources.",
            face = 2, color = "#ef4444", reportsTo = "nova", effort = "high", mode = "teammate")
        val made = (edits.create(d, "user") as TeamStore.Result.Ok).bot
        assertEquals("orion", made.id)
        val back = BotDraft.of(store.get("orion")!!, store.soul("orion"))
        assertEquals(d, back)
        // editing: the editor state round-trips, problems update as you type, the soul is saved only when it changed
        var s = BotEditorModel.open(store.all(), store.get("orion"), store.soul("orion"))
        assertEquals("Edit Orion", s.title); assertEquals("Save", s.primary); assertTrue(s.canSave); assertTrue(s.canDelete)
        s = BotEditorModel.edit(s, store.all()) { it.copy(email = "nope") }
        assertEquals(listOf("That email doesn't look right."), s.problems); assertFalse(s.canSave)
        s = BotEditorModel.edit(s, store.all()) { it.copy(email = "", label = "Research lead") }
        assertTrue(s.canSave)
        edits.update("orion", s.draft, "user")
        assertEquals(1L, souls("orion"))
        edits.update("orion", s.draft.copy(instructions = "Cite two sources."), "user")
        assertEquals(2L, souls("orion")); assertEquals("Cite two sources.", store.soul("orion"))
        val o = store.get("orion")!!
        assertEquals("Research lead", o.label); assertEquals("", o.email); assertEquals("high", o.engine.model.effort)
        // a refused draft never reaches the store
        assertEquals("Another bot is already called Nova.", (edits.create(BotDraft(name = "Nova"), "user") as TeamStore.Result.Refused).why)
        // delete: a custom bot is removed, the Manager never
        assertTrue(edits.delete("orion") is TeamStore.Result.Ok); assertNull(store.get("orion"))
        assertTrue(edits.delete(BotRules.MANAGER_ID) is TeamStore.Result.Refused)
    }

    private fun souls(id: String) = sql.query("SELECT count(*) FROM bot_soul_versions WHERE bot_id=?", id) { it.long(0) }.single()
}
