package com.blaze.assistant.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-CN-03: the per-call decision for an argus-connect tool: run, card, or refuse. */
class ConnectGateTest {
    private val list = ConnectToolsCore.Meta("github_list_repos", "github", "GitHub", readOnly = true, connected = true)
    private val create = ConnectToolsCore.Meta("github_create_issue", "github", "GitHub", readOnly = false, connected = true)
    private val send = ConnectToolsCore.Meta("gmail_send_email", "gmail", "Gmail", readOnly = false, connected = true)
    private val g = AppGrants.Grant("github")

    @Test fun readsRun() {
        assertEquals(ConnectToolsCore.Gate.Run, ConnectToolsCore.gate(list.full, JSONObject(), list, g, tainted = false))
        assertEquals(ConnectToolsCore.Gate.Run, ConnectToolsCore.gate(list.full, JSONObject(), list, g, tainted = true))
    }

    @Test fun writesAskWithTheArgs() {
        val r = ConnectToolsCore.gate(create.full, JSONObject().put("title", "Bug"), create, g, false)
        assertTrue(r is ConnectToolsCore.Gate.Ask)
        r as ConnectToolsCore.Gate.Ask
        assertTrue(r.title.contains("GitHub")); assertTrue(r.title.contains("create issue"))
        assertTrue(r.detail.contains("\"title\": \"Bug\""))
        assertEquals("Allow", r.button)
        val s = ConnectToolsCore.gate(send.full, JSONObject(), send, AppGrants.Grant("gmail"), false) as ConnectToolsCore.Gate.Ask
        assertEquals("Send", s.button)
    }

    @Test fun alwaysOnTheGrantSkipsTheCardUnlessTainted() {
        val always = AppGrants.Grant("github", tools = mapOf("create_issue" to "always"))
        assertEquals(ConnectToolsCore.Gate.Run, ConnectToolsCore.gate(create.full, JSONObject(), create, always, false))
        assertTrue(ConnectToolsCore.gate(create.full, JSONObject(), create, always, true) is ConnectToolsCore.Gate.Ask)
    }

    @Test fun offRefusesAndNoGrantRefuses() {
        val off = AppGrants.Grant("github", tools = mapOf("list_repos" to "off"))
        assertTrue(ConnectToolsCore.gate(list.full, JSONObject(), list, off, false) is ConnectToolsCore.Gate.Refuse)
        val r = ConnectToolsCore.gate(list.full, JSONObject(), list, null, false)
        assertTrue(r is ConnectToolsCore.Gate.Refuse)
        assertTrue((r as ConnectToolsCore.Gate.Refuse).why.contains("isn't allowed"))
    }

    @Test fun unknownToolFallsBackToTheSlug() {
        assertEquals(ConnectToolsCore.Gate.Run, ConnectToolsCore.gate("notion_search_pages", JSONObject(), null, AppGrants.Grant("notion"), false))
        assertTrue(ConnectToolsCore.gate("notion_create_page", JSONObject(), null, AppGrants.Grant("notion"), false) is ConnectToolsCore.Gate.Ask)
    }

    @Test fun longArgsAreCutOnTheCard() {
        val a = JSONObject().put("body", "y".repeat(5000))
        val r = ConnectToolsCore.gate(create.full, a, create, g, false) as ConnectToolsCore.Gate.Ask
        assertTrue(r.detail.length < 1100)
    }
}
