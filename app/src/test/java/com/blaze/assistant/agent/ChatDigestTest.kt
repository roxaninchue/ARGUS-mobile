package com.blaze.assistant.agent

import com.blaze.assistant.BrandName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

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

/** Finn 23 Nov 2026: the skill keeper (Hermes-style background review) and the cut of the "teach" word gate. */
class SkillReviewTest {
    private val steps = "1. Call the screenshot endpoint with response_format=base64.\n2. Decode the image field and save it as a PNG.\n3. Open it in the gallery to check it loads."

    private fun saveCall(id: String, name: String, desc: String, body: String) = JSONObject().put("content", "").put("tool_calls", JSONArray().put(
        JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", SkillReview.SAVE)
            .put("arguments", JSONObject().put("name", name).put("description", desc).put("instructions", body).toString()))))

    private fun readCall(id: String, name: String) = JSONObject().put("content", "").put("tool_calls", JSONArray().put(
        JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", SkillReview.READ)
            .put("arguments", JSONObject().put("name", name).toString()))))

    private fun review(replies: List<JSONObject?>, saved: MutableList<SkillReview.Save> = mutableListOf(), reads: MutableList<String> = mutableListOf()): Pair<List<Pair<SkillReview.Save, String>>, Int> {
        var calls = 0
        val out = kotlinx.coroutines.runBlocking {
            SkillReview.run("[user] grab a screenshot of my cloud pc", listOf("orgo-screenshot: Take a screenshot of the Orgo PC"),
                call = { _, _ -> replies.getOrNull(calls++) }, read = { reads += it; "---\nname: $it\n---\n1. old" },
                save = { saved += it; "Skill ${it.name} saved." })
        }
        return out to calls
    }

    @Test fun dueEveryFewToolCalls() {
        assertFalse(SkillReview.due(0)); assertFalse(SkillReview.due(SkillReview.EVERY - 1))
        assertTrue(SkillReview.due(SkillReview.EVERY)); assertFalse(SkillReview.due(50, every = 0))
    }

    @Test fun onlyTwoTools() {
        val t = SkillReview.tools()
        assertEquals(listOf(SkillReview.READ, SkillReview.SAVE), (0 until t.length()).map { t.getJSONObject(it).getJSONObject("function").getString("name") })
    }

    @Test fun nothingToKeepSavesNothing() {
        val (done, calls) = review(listOf(JSONObject().put("content", "nothing")))
        assertTrue(done.isEmpty()); assertEquals(1, calls)
    }

    @Test fun noAnswerSavesNothing() { assertTrue(review(listOf(null)).first.isEmpty()) }

    @Test fun savesAGoodSkill() {
        val saved = mutableListOf<SkillReview.Save>()
        val (done, _) = review(listOf(saveCall("a", "Orgo Screenshot", "Take and save a screenshot of the Orgo cloud PC", steps), JSONObject().put("content", "done")), saved)
        assertEquals("orgo-screenshot", saved.single().name)
        assertEquals("Skill orgo-screenshot saved.", done.single().second)
    }

    @Test fun readsThenImproves() {
        val reads = mutableListOf<String>()
        val (done, calls) = review(listOf(readCall("r", "orgo-screenshot"), saveCall("s", "orgo-screenshot", "Take and save a screenshot of the Orgo cloud PC", steps),
            JSONObject().put("content", "done")), reads = reads)
        assertEquals(listOf("orgo-screenshot"), reads); assertEquals(1, done.size); assertEquals(3, calls)
    }

    @Test fun weakOrSecretSkillsAreRefused() {
        val saved = mutableListOf<SkillReview.Save>()
        review(listOf(saveCall("a", "x", "short", "do it"), saveCall("b", "api-thing", "Call the API with the saved key", "1. Send the key sk-abcdefghijklmnop\n2. Read the response and check the status code\n3. Done when it says ok")), saved)
        assertTrue(saved.isEmpty())
    }

    @Test fun stopsAfterMaxSteps() {
        val many = List(10) { readCall("r$it", "orgo-screenshot") }
        assertEquals(SkillReview.MAX_STEPS, review(many).second)
    }

    @Test fun atMostTwoSaves() {
        val saved = mutableListOf<SkillReview.Save>()
        review(listOf(saveCall("a", "one-skill", "Take and save a screenshot of the Orgo cloud PC", steps),
            saveCall("b", "two-skill", "Take and save a screenshot of the Orgo cloud PC", steps),
            saveCall("c", "three-skill", "Take and save a screenshot of the Orgo cloud PC", steps)), saved)
        assertEquals(SkillReview.MAX_SAVES, saved.size)
    }

    @Test fun agentWiresTheKeeperAndDropsTheWordGate() {
        val a = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
            .walkTopDown().first { it.name == "Agent.kt" && it.parentFile.name == "agent" }.readText()
        val learn = a.substringAfter("fun learnAfterTurn() {")
        assertTrue(learn.contains("SkillReview.due("))
        assertTrue(learn.contains("Learning.skillReview(digest)"))
        assertTrue(learn.contains(".copy(skill = false)"))
        assertFalse(a.substringAfter("suspend fun runLoop", "").substringBefore("fun learnAfterTurn()").contains("SkillReview"))
    }
}
