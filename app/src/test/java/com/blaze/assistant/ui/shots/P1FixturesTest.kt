package com.blaze.assistant.ui.shots

import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.Router
import com.blaze.assistant.ui.argus.Ground
import com.blaze.assistant.ui.team.ChatDrawerModel
import com.blaze.assistant.ui.team.DeskModel
import com.blaze.assistant.ui.team.JobsFilter
import com.blaze.assistant.ui.team.LifeModel
import com.blaze.assistant.ui.team.PlanSheetModel
import com.blaze.assistant.ui.team.thread.ThreadModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-QA-01 on the JVM: the shots matrix is the full 12 cells with stable, unique names; every P1 screen ticket is in it
 * (or named as pending); and the fixtures every picture draws are deterministic and show the states the owner reviews
 * (unread, overdue, waiting, failed with a log, a live turn, a pinned chat, a miss in the plan).
 */
class P1FixturesTest {
    // ---------------------------------------------------------------- matrix
    @Test fun matrixIsLightDarkTimesThreeGroundsTimesTwoFonts() {
        assertEquals(12, ShotMatrix.CELLS.size)
        assertEquals(12, ShotMatrix.CELLS.map { it.name }.toSet().size)
        assertEquals(setOf(false, true), ShotMatrix.CELLS.map { it.dark }.toSet())
        assertEquals(Ground.entries.toSet(), ShotMatrix.CELLS.map { it.ground }.toSet())
        assertEquals(setOf(1f, 2f), ShotMatrix.CELLS.map { it.fontScale }.toSet())
        for (dark in listOf(false, true)) for (g in Ground.entries) for (f in ShotMatrix.FONTS)
            assertEquals(1, ShotMatrix.CELLS.count { it.dark == dark && it.ground == g && it.font == f })
    }

    @Test fun cellNamesAreStableFileNames() {
        assertEquals("light-plain-100", ShotMatrix.CELLS.first().name)
        assertEquals("dark-cobalt-200", ShotMatrix.CELLS.last().name)
        assertEquals("P1ShotsTest/team/dark-orbit-200.png", ShotMatrix.file("team", ShotMatrix.cell("dark-orbit-200")))
        for (c in ShotMatrix.CELLS) {
            assertTrue(c.name, c.name.matches(Regex("(light|dark)-(plain|orbit|cobalt)-(100|200)")))
            assertEquals(c, ShotMatrix.cell(c.name))
        }
        assertEquals(ShotMatrix.CELLS.map { it.name }, ShotMatrix.params().map { it.single() })
    }

    @Test(expected = IllegalArgumentException::class) fun unknownCellIsRefused() { ShotMatrix.cell("sepia-plain-100") }

    @Test fun everyP1ScreenTicketIsShotOrPending() {
        val shot = ShotMatrix.SCREENS.map { it.ticket }.toSet()
        for (t in ShotMatrix.SCREEN_TICKETS) assertTrue("$t has no screen in the matrix", t in shot || t in ShotMatrix.PENDING)
        assertEquals(ShotMatrix.SCREENS.size, ShotMatrix.SCREENS.map { it.key }.toSet().size)
        for (s in ShotMatrix.SCREENS) assertTrue(s.key, s.key.matches(Regex("[a-z]+(-[a-z]+)*")))
        assertEquals(ShotMatrix.SCREENS.size * 12, ShotMatrix.TOTAL)
        assertTrue(ShotMatrix.PENDING.isEmpty())
        // the sheets are the ones that open in their own window
        assertEquals(setOf("new-bot", "bot-editor", "plan", "job-sheet", "new-job"), ShotMatrix.SCREENS.filter { it.sheet }.map { it.key }.toSet())
    }

    // ---------------------------------------------------------------- fixtures
    @Test fun clockIsThursdayElevenInLusaka() {
        assertEquals(java.time.DayOfWeek.THURSDAY, P1Fixtures.today.dayOfWeek)
        assertEquals(1_794_474_000_000L, P1Fixtures.now)   // 2026-11-12T09:00:00Z
    }

    @Test fun teamShowsManagerFirstPinsUnreadAndOverdue() {
        val s = P1Fixtures.teamState()
        assertEquals(BotRules.MANAGER_ID, s.manager!!.id)
        assertTrue(s.header.sub, s.header.sub.contains("overdue"))
        assertTrue(s.rows.any { it.unread > 0 })
        assertEquals(BotRules.MANAGER_ID, s.pins.first().id)
        assertFalse(s.empty)
        assertTrue(P1Fixtures.emptyTeamState().empty)
    }

    @Test fun threadHasIntroWorkHandoffAndALiveLine() {
        val live = P1Fixtures.threadItems(live = true)
        assertTrue(live.first() is ThreadModel.ThreadItem.Intro)
        assertTrue(live.any { it is ThreadModel.ThreadItem.Work })
        assertTrue(live.last() is ThreadModel.ThreadItem.Live)
        val done = P1Fixtures.threadItems(live = false)
        assertFalse(done.any { it is ThreadModel.ThreadItem.Live })
        assertEquals(live.map { it.key }.toSet().size, live.size)
    }

    @Test fun drawerHasPinnedTodayYesterdayOlderAndAProjectChat() {
        val s = P1Fixtures.drawerState()
        assertNull(s.empty)
        assertEquals("Pinned", s.sections.first().name)
        assertTrue(s.sections.map { it.name }.containsAll(listOf("Today", "Yesterday")))
        assertTrue(s.entries.any { it.key is ChatDrawerModel.Key.Project })
        assertTrue(s.entries.any { it.live })
        assertTrue(P1Fixtures.drawerState("dentist").entries.isNotEmpty())
    }

    @Test fun lifeShowsTheDayStripTilesAndPanels() {
        val s = P1Fixtures.lifeState()
        assertTrue(s.dayLine, s.dayLine.startsWith("Thursday"))
        assertEquals(10, s.domains.size)
        assertNotNull(s.weather); assertNotNull(s.answer)
        assertTrue(LifeModel.Section.TODAY in s.sections && LifeModel.Section.PROJECTS in s.sections)
    }

    @Test fun editorOpensOnNovaWithNoProblems() {
        val e = P1Fixtures.editorState()
        assertEquals("nova", e.botId); assertEquals("Save", e.primary); assertTrue(e.canSave); assertTrue(e.canDelete)
    }

    @Test fun planHasThreeActionsAndAMiss() {
        val p = P1Fixtures.plan()
        assertEquals(3, p.real.size)
        assertFalse(p.clean); assertFalse(p.risky)
        assertEquals("Apply 3", PlanSheetModel.button(p))
        assertTrue(PlanSheetModel.lines(p).last().miss)
        assertTrue(p.actions.last() is Router.Act.Miss)
    }

    @Test fun jobsCoverEveryFilterAndTheSheetHasALog() {
        val counts = JobsFilter.counts(P1Fixtures.jobs, P1Fixtures.today)
        for (k in listOf(JobsFilter.NEEDS_YOU, JobsFilter.TODAY, JobsFilter.TOMORROW, JobsFilter.UPCOMING, JobsFilter.OVERDUE,
            JobsFilter.DONE, JobsFilter.ALL, JobsFilter.UNASSIGNED)) assertTrue("$k empty", (counts[k] ?: 0) > 0)
        val s = P1Fixtures.jobsState()
        assertEquals(JobsFilter.NEEDS_YOU, s.filter)
        assertTrue(s.rows.isNotEmpty()); assertNull(s.empty, s.empty)
        val sheet = P1Fixtures.jobSheet()
        assertEquals(4, sheet.log.size)
        assertTrue(sheet.log.toString(), sheet.log.any { it.bad })
        assertEquals("Retry", sheet.retry)
    }

    @Test fun deskChipsCarryCounts() {
        val chips = P1Fixtures.deskChips()
        assertEquals(DeskModel.Segment.entries.map { it.key }, chips.map { it.key })
        assertEquals(P1Fixtures.INBOX_WAITING, chips.first { it.key == DeskModel.Segment.INBOX.key }.count)
        assertEquals(P1Fixtures.PROJECTS, chips.first { it.key == DeskModel.Segment.PROJECTS.key }.count)
    }

    @Test fun backupPreviewIsTheDemoTeamOnANewInstall() {
        val f = P1Fixtures.backupFile()
        val r = P1Fixtures.backupPreview()
        assertFalse(r.applied)
        assertEquals(4, r.bots.added)
        assertTrue(r.jobs.added > 0)
        assertTrue(r.toConfirm > 0)
        assertEquals(listOf("Vega"), r.gemini.map { it.first })
        assertTrue(r.changes > 0)
        assertEquals(f.theme != null, r.theme != null)
        assertTrue(com.blaze.assistant.team.ATeamImport.lines(r).isNotEmpty())
        assertEquals(r, P1Fixtures.backupPreview())   // a fresh db each time: the same preview
    }

    @Test fun fixturesAreDeterministic() {
        assertEquals(P1Fixtures.teamState(), P1Fixtures.teamState())
        assertEquals(P1Fixtures.threadItems(), P1Fixtures.threadItems())
        assertEquals(P1Fixtures.drawerState(), P1Fixtures.drawerState())
        assertEquals(P1Fixtures.jobsState(), P1Fixtures.jobsState())
        assertEquals(P1Fixtures.lifeState(), P1Fixtures.lifeState())
    }
}
