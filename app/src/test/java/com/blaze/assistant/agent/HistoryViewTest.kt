package com.blaze.assistant.agent

import com.blaze.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryViewTest {
    private fun u(t: String) = JSONObject().put("role", "user").put("content", t)
    private fun a(t: String) = JSONObject().put("role", "assistant").put("content", t)
    private fun call() = JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
        .put("tool_calls", JSONArray().put(JSONObject().put("id", "c1").put("type", "function").put("function", JSONObject().put("name", "web_search").put("arguments", "{}"))))
    private fun tool() = JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "results")

    private val nudge = RetryGuard.anchor("[${BrandName.NAME} check] Your reply says you did this, but no tool ran.", "text mum")

    @Test fun pastNudgesAndTheRepliesTheyRejectedAreLeftOut() {
        val h = JSONArray().put(u("[m1] text mum")).put(a("Sent!")).put(u(nudge)).put(call()).put(tool()).put(a("Sent to Mum."))
            .put(u("[m2] thanks"))
        val sent = (0 until h.length()).filterNot { HistoryView.skip(h, it, 6) }
        assertEquals(listOf(0, 3, 4, 5, 6), sent)
    }

    @Test fun theCurrentTurnKeepsItsNudge() {
        val h = JSONArray().put(u("[m1] text mum")).put(a("Sent!")).put(u(nudge))
        assertTrue((0 until h.length()).none { HistoryView.skip(h, it, 0) })
    }

    @Test fun toolWorkAndUserWordsStay() {
        val h = JSONArray().put(u("[m1] what's new")).put(call()).put(tool()).put(a("Here's what's new.")).put(u("[The user adds, while you work: also prices]"))
        assertTrue((0 until h.length()).none { HistoryView.skip(h, it, h.length()) })
        assertFalse(HistoryView.isNudge(u("[m3] [Argus check] is a band name")))
    }

    /** The openings of the app's re-prompts as written in EmptyReply, AutoContinue, ViewRouter, ToolTruth, ToolLeak, TextCalls, GoalCheck, EchoGuard, FakeCall, Autonomy. */
    @Test fun everyKnownRepromptIsRecognised() {
        val n = BrandName.NAME
        listOf("[$n] Your last reply came back empty (no answer text).", "[Autopilot] Continue with the next unfinished step of the task.",
            "You answered in chat, but flashcards belong in the Learn view", "[Check: you just said you can't do that, but your tool list has a tool for it.]",
            "Your last reply contained tool-call text (like [/name key=\"value\"])", "Your last reply was function-call text (like name(...))",
            "[$n check] Opening the app is only the first step", "[$n] Your last reply repeated $n's own context", "[$n check] Your last reply wrote a command",
            "[Quick lookup didn't settle it: no pages. Use web_search.]")
            .forEach { assertTrue(it.take(40), HistoryView.isNudge(u(it))) }
    }
}
