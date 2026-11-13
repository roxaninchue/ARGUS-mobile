package com.blaze.assistant.policy

import com.blaze.assistant.agent.ToolTruth
import com.blaze.assistant.orchestra.ToolScheduler
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Permissions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.53.1 parallel tools: lookups, reads and edits of different files start together; a call that may put a card up
 * stays in the in-order loop (PolicyGate.quiet), so cards come one at a time in the model's order; the prompt asks the
 * model to batch independent calls.
 */
class ParallelCardsTest {
    private fun req(cls: ActionClass, enforce: Boolean = true, perms: Permissions = Permissions()) = PolicyGate.Request(
        "chat", "manager", "ARGUS", null, "tool_x", "{}", Policy.Call("tool_x", cls),
        Policy.Ctx("manager", perms, emptyList(), Policy.Trigger.USER, "", true, false, null, null, null, null, null, false), enforce)

    @Test fun readsRunAheadCardsStayInOrder() {
        assertTrue("no policy request = the tool's own path", PolicyGate.quiet(null))
        assertTrue("shadow never cards", PolicyGate.quiet(req(ActionClass.SEND, enforce = false)))
        assertTrue(PolicyGate.quiet(req(ActionClass.READ)))
        assertFalse("a send may card", PolicyGate.quiet(req(ActionClass.SEND)))
        assertFalse("money may card", PolicyGate.quiet(req(ActionClass.SPEND, perms = Permissions(autonomy = Autonomy.AUTOPILOT))))
        assertFalse("a delete may card", PolicyGate.quiet(req(ActionClass.DELETE)))
    }

    private fun call(n: String, vararg kv: Pair<String, String>) = ToolScheduler.Call(n + kv.joinToString(), n, JSONObject().apply { kv.forEach { put(it.first, it.second) } })

    @Test fun searchesReadsAndEditsOfDifferentFilesStartTogether() {
        val calls = listOf(call("web_search", "query" to "a"), call("web_search", "query" to "b"), call("fetch_page", "url" to "https://x.io"),
            call("file_read", "path" to "app/A.kt"), call("file_edit", "path" to "app/B.kt"), call("file_write", "path" to "app/C.kt"))
        assertEquals(1, ToolScheduler.waves(calls).size)
    }

    @Test fun sameFileKeepsItsOrderAndCardsNeverRunAhead() {
        val calls = listOf(call("file_edit", "path" to "app/A.kt"), call("file_read", "path" to "app/A.kt"), call("web_search", "query" to "x"))
        assertEquals(listOf(listOf(0, 2), listOf(1)), ToolScheduler.waves(calls))
        listOf("send_sms", "gmail_send", "ask_user", "request_app_connection", "tap").forEach { assertFalse(it, ToolScheduler.Resources.prefetchable(it)) }
        listOf("web_search", "fetch_page", "file_read", "file_edit").forEach { assertTrue(it, ToolScheduler.Resources.prefetchable(it)) }
    }

    @Test fun promptAsksForOneBatch() {
        assertTrue(ToolTruth.RULE.contains("go together in ONE reply"))
        assertTrue(ToolTruth.RULE.contains("needs the user's OK"))
    }
}
