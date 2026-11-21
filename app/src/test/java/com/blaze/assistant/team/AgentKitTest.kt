package com.blaze.assistant.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentKitTest {
    private fun bot(id: String, lead: String? = BotRules.MANAGER_ID, kind: BotKind = BotKind.CUSTOM, members: List<String> = emptyList()) =
        Bot(id = id, kind = kind, name = id.replaceFirstChar { it.uppercase() }, label = "", avatar = Avatar("orb", "#22c55e"),
            soulPath = "", toolGroups = setOf("web"), reportsTo = lead, members = members, createdAt = 0, u = 0)

    @Test fun groupsAreCheckedAndCapped() {
        assertEquals(listOf("web", "browser"), AgentKit.groups("web, browser"))
        assertEquals("terminal is everyone's, not a pick", listOf("web"), AgentKit.groups("web, terminal"))
        assertEquals(listOf("web"), AgentKit.groups("Web, nonsense"))
        assertEquals(AgentKit.MAX_GROUPS, AgentKit.groups("web,browser,apps,schedule,messages,google").size)
        assertEquals(listOf("browser", "web"), AgentKit.groups(listOf("browser", "web", "browser")))
        assertEquals(listOf("nonsense"), AgentKit.dropped("web,nonsense"))
        assertEquals(emptyList<String>(), AgentKit.groups(null))
    }

    @Test fun terminalAgentsGetTheTerminal() {
        val t = AgentKit.toolSet(listOf("web"), terminal = true)
        assertTrue("terminal" in t && "web" in t && "memory" in t && "jobs" in t)
        assertTrue("every agent has the terminal", "terminal" in AgentKit.toolSet(listOf("web"), terminal = false))
        assertTrue(AgentKit.toolSet(listOf("web", "browser", "schedule", "messages"), terminal = true).size <= AgentKit.MAX_GROUPS + AgentKit.BASE_GROUPS.size)
        assertTrue(AgentKit.terminalSoul().contains("terminal agent"))
    }

    @Test fun chatKitIsSmallAndKeepsTheWayOut() {
        assertTrue(AgentKit.CHAT_CORE.size <= 41)   // 41 since make_document (PDF / Word / PowerPoint in one call) rides every turn
        assertTrue("the skill shelf's reader rides with it", "use_skill" in AgentKit.CHAT_CORE)
        assertTrue("run_shell" in AgentKit.CHAT_CORE && "agent_watch" in AgentKit.CHAT_CORE)
        assertTrue("load_tools" in AgentKit.CHAT_CORE && "find_tool" in AgentKit.CHAT_CORE && "agent_create" in AgentKit.CHAT_CORE && "web_search" in AgentKit.CHAT_CORE)
    }

    @Test fun watchShowsStepsAndNext() {
        val rows = listOf(
            Row("nova", 1, 5, 7, "tool-call", "{\"name\":\"run_shell\",\"state\":\"done\",\"args\":{\"command\":\"./gradlew assembleDebug\"},\"output\":\"BUILD SUCCESSFUL\"}", 0),
            Row("nova", 1, 6, 6, "message", "{\"text\":\"Build finished\"}", 0))
        val t = JobTools.watchText("Nova", rows, 4, running = false)
        assertTrue(t.contains("./gradlew assembleDebug") && t.contains("BUILD SUCCESSFUL") && t.contains("Build finished"))
        assertTrue(t.endsWith("next=7"))
        assertTrue(JobTools.watchText("Nova", emptyList(), 7, running = true).contains("still working"))
    }

    @Test fun teamsGroupUnderTheirLead() {
        val roster = listOf(bot(BotRules.MANAGER_ID, null, BotKind.MANAGER), bot("nova"), bot("atlas", "nova"), bot("echo", "nova"), bot("vega"))
        val g = AgentKit.teams(roster)
        assertEquals(1, g.teams.size)
        assertEquals("nova", g.teams[0].lead.id)
        assertEquals(listOf("atlas", "echo"), g.teams[0].members.map { it.id })
        assertEquals("alone agents keep just their name", listOf("vega"), g.solo.map { it.id })
        val room = AgentKit.teams(roster + bot("room1", kind = BotKind.ROOM, members = listOf("vega")))
        assertEquals(2, room.teams.size); assertTrue(room.solo.isEmpty())
    }
}
