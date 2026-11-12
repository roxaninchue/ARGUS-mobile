package com.blaze.assistant.ui.team

import com.blaze.assistant.agent.ChatNav
import com.blaze.assistant.ui.argus.icons.LucidePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-TM-07: Desk v1's pure rules (segments, counts, the link pages, the Inbox placeholder) and the Projects move
 * ([DeskRoute]): the Projects list is Desk's Projects segment in the ARGUS shell, a project page and a project chat open
 * as before, and the 0.52 shell is untouched. Stands in for DeskShotsTest (Robolectric shots don't run in the local kit).
 */
class DeskModelTest {
    @Test fun segmentsInOrder() {
        assertEquals(listOf("Inbox", "Jobs", "Routines", "Library", "Projects", "Computers"), DeskModel.Segment.entries.map { it.label })
        assertEquals(DeskModel.Segment.JOBS, DeskModel.DEFAULT)
        assertEquals(DeskModel.Segment.PROJECTS, DeskModel.Segment.of(" Projects "))
        assertNull(DeskModel.Segment.of("calendar"))
    }

    @Test fun chipCountsOnlyWhereSomethingIs() {
        val chips = DeskModel.chips(inboxWaiting = 2, jobsNeedYou = 0, projects = 3)
        assertEquals(listOf("inbox", "jobs", "routines", "library", "projects", "computers"), chips.map { it.key })
        assertEquals(listOf(2, null, null, null, 3, null), chips.map { it.count })
    }

    @Test fun linkPagesOpenAmScreens() {
        assertEquals(listOf("schedule"), DeskModel.links(DeskModel.Segment.ROUTINES).map { it.target })
        assertEquals(listOf("gallery", "downloads"), DeskModel.links(DeskModel.Segment.LIBRARY).map { it.target })
        assertEquals(listOf("terminal", "browser"), DeskModel.links(DeskModel.Segment.COMPUTERS).map { it.target })
        assertTrue(DeskModel.links(DeskModel.Segment.JOBS).isEmpty() && DeskModel.links(DeskModel.Segment.PROJECTS).isEmpty())
        for (s in DeskModel.Segment.entries) if (s != DeskModel.Segment.JOBS && s != DeskModel.Segment.PROJECTS) assertTrue(s.name, DeskModel.intro(s).isNotBlank())
    }

    @Test fun everyIconIsDrawn() {
        val icons = DeskModel.Segment.entries.map { it.icon } + listOf(DeskModel.ROUTINES, DeskModel.LIBRARY, DeskModel.COMPUTERS).flatten().map { it.icon } +
            listOf("circle-check", "circle-alert", "play", "message-circle", "list-checks", "clock", "plus", "check")
        for (i in icons) assertTrue(i, i in LucidePaths.ICONS)
    }

    @Test fun inboxPlaceholder() {
        assertTrue(DeskModel.inboxBody(0).startsWith("Nothing waits on you."))
        assertEquals("Something waits for your answer in Chat.", DeskModel.inboxBody(1))
        assertEquals("3 things wait for your answer in Chat.", DeskModel.inboxBody(3))
    }

    // ---- Projects moved to Desk; behaviour unchanged ----

    @Test fun projectsListIsDeskInArgus() {
        val list = ChatNav().projectsList()
        assertTrue(DeskRoute.isProjectsList(list, argus = true))
        assertEquals(ChatNav(ChatNav.DESK), DeskRoute.shown(list, argus = true))
        assertEquals(DeskModel.Segment.PROJECTS, DeskRoute.segment(list, argus = true))
    }

    @Test fun projectPageAndChatStayAsBefore() {
        val page = ChatNav().openProject("p1")
        val chat = ChatNav().openChat("p1", "pc1")
        for (n in listOf(page, chat)) {
            assertFalse(DeskRoute.isProjectsList(n, true))
            assertEquals(n, DeskRoute.shown(n, true))
            assertNull(DeskRoute.segment(n, true))
        }
        assertTrue(DeskRoute.shown(chat, true).inProjectChat)
        assertEquals("pc1", DeskRoute.shown(chat, true).visibleChat)
    }

    @Test fun backFromAProjectChatEndsOnDeskProjects() {
        var n = ChatNav().openChat("p1", "pc1")
        n = n.back()!!                       // project page
        assertEquals(ChatNav(ChatNav.PROJECTS, "p1", ""), n)
        assertEquals(n, DeskRoute.shown(n, true))
        n = n.back()!!                       // the list → Desk
        assertEquals(ChatNav(ChatNav.DESK), DeskRoute.shown(n, true))
        assertNull(ChatNav(ChatNav.DESK).back())   // then the shell's root rule (Team)
    }

    @Test fun deletingAnOpenProjectLandsOnDesk() {
        val n = ChatNav().openProject("p1").projectDeleted("p1")
        assertEquals(ChatNav(ChatNav.DESK), DeskRoute.shown(n, true))
    }

    @Test fun oldShellUntouched() {
        for (n in listOf(ChatNav().projectsList(), ChatNav().openProject("p1"), ChatNav().openChat("p1", "c"), ChatNav(ChatNav.HOME))) {
            assertEquals(n, DeskRoute.shown(n, argus = false))
            assertNull(DeskRoute.segment(n, argus = false))
        }
    }

    @Test fun modesUnchanged() {
        assertEquals(listOf("team", "chat", "life", "desk"), ChatNav.MODES)
        assertTrue(ChatNav.isMode(DeskRoute.shown(ChatNav().projectsList(), true).tab))
        assertEquals("desk", ChatNav.modeCommand("/desk"))
    }
}
