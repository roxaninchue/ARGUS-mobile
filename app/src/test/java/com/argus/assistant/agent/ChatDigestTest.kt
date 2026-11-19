package com.argus.assistant.agent

import com.argus.assistant.BrandName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 16 Nov 2026: the whole-chat digest the curator reads. */
class ChatDigestTest {
    private fun trace(vararg tools: TurnTrace.Tool, errors: List<String> = emptyList()) =
        TurnTrace.toJson(TurnTrace.Trace(tools = tools.toList(), errors = errors))

    private val chat = listOf(
        ChatMessage(1, Role.USER, "Deploy my site to Cloudflare Pages"),
        ChatMessage(2, Role.STEP, "Running a command"),
        ChatMessage(3, Role.STEP, "Running a command"),
        ChatMessage(4, Role.BLAZE, "Deployed. It's live.", trace = trace(
            TurnTrace.Tool("run_shell", "wrangler pages deploy dist", "Success! Uploaded 12 files", true),
            TurnTrace.Tool("http_request", "GET https://site.pages.dev", "404", false), errors = listOf("first deploy timed out"))),
        ChatMessage(5, Role.USER, "thanks"),
        ChatMessage(6, Role.ERROR, "Network went away"),
    )

    @Test fun digestHasPromptsRepliesToolsAndErrors() {
        val d = ChatDigest.build(chat)
        assertTrue(d.startsWith("[user] Deploy my site to Cloudflare Pages"))
        assertTrue("  tool run_shell(wrangler pages deploy dist) -> ok: Success! Uploaded 12 files" in d)
        assertTrue("  tool http_request(GET https://site.pages.dev) -> FAILED: 404" in d)
        assertTrue("  error: first deploy timed out" in d)
        assertTrue("[${BrandName.NAME.lowercase()}] Deployed. It's live." in d)
        assertTrue("[error] Network went away" in d)
        assertEquals(1, Regex("step: Running a command").findAll(d).count())
        // tools come before the reply they led to
        assertTrue(d.indexOf("tool run_shell") < d.indexOf("Deployed."))
    }

    @Test fun longChatsKeepTheFirstPromptAndTheNewest() {
        val long = listOf(ChatMessage(0, Role.USER, "Plan the launch")) + (1..400L).map { ChatMessage(it, Role.USER, "message number $it with some words") }
        val d = ChatDigest.build(long, max = 2_000)
        assertTrue(d.length <= 2_100)
        assertTrue(d.startsWith("[user] Plan the launch"))
        assertTrue("earlier lines left out" in d)
        assertTrue(d.endsWith("message number 400 with some words"))
    }

    @Test fun statsAndWorth() {
        val s = ChatDigest.stats(chat)
        assertEquals(2, s.prompts); assertEquals(2, s.tools); assertEquals(1, s.failed); assertEquals(2, s.errors)
        assertTrue(ChatDigest.worthReview(chat))
        assertFalse(ChatDigest.worthReview(listOf(ChatMessage(1, Role.USER, "hi"), ChatMessage(2, Role.BLAZE, "Hey!"))))
    }
}
