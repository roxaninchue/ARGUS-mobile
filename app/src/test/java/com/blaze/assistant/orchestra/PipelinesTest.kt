package com.blaze.assistant.orchestra

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** The pipeline shapes end to end with fake agents / terminal / doc writer. */
class PipelinesTest {
    @Test fun picksAShapeFromTheGoal() {
        assertEquals(Pipelines.Shape.APK, Pipelines.pick("build an APK for a habit tracker"))
        assertEquals(Pipelines.Shape.APK, Pipelines.pick("make me an android app that counts steps"))
        assertEquals(Pipelines.Shape.RESEARCH, Pipelines.pick("deep dive into solar mini-grids in Zambia"))
        assertEquals(Pipelines.Shape.MEDIA, Pipelines.pick("find songs of the amapiano genre from 2025"))
        assertEquals(Pipelines.Shape.DOCUMENT, Pipelines.pick("write a business proposal for a bakery"))
        assertEquals(Pipelines.Shape.GENERIC, Pipelines.pick("plan my week and tidy my reminders"))
    }

    @Test fun skeletonsAreValid() {
        Pipelines.Shape.values().forEach { s -> assertTrue(s.id, Pipelines.skeleton(s, "goal").validate().isEmpty()) }
    }

    /** A fake LLM: answers by node kind. */
    private class FakeOps(val delayMs: Long = 100) : Pipelines.Ops {
        val live = AtomicInteger(); var peakSearch = 0
        val prompts = Collections.synchronizedList(ArrayList<Pair<String, String>>())
        val made = Collections.synchronizedList(ArrayList<String>())
        var builds = 0
        var saved: Pair<String, String>? = null
        override suspend fun agent(ctx: NodeContext, prompt: String): String {
            prompts += ctx.node.id to prompt
            val n = ctx.node
            if (n.kind == "search") { peakSearch = maxOf(peakSearch, live.incrementAndGet()); delay(delayMs); live.decrementAndGet() } else delay(10)
            return when (n.kind) {
                "plan" -> when (ctx.store.pipeline) {
                    "research" -> """Sure! ```json
{"queries":["solar cost zambia","mini-grid policy zambia","zesco tariffs"]}
```"""
                    "media" -> """{"sources":[{"name":"Spotify charts","query":"amapiano 2025"},{"name":"YouTube","query":"amapiano 2025"}]}"""
                    "apk" -> """{"contracts":{"Repo":"interface Repo { fun all(): List<Habit> }"},"folders":[
                        {"path":".","files":[{"path":"build.gradle.kts","deps":[]}]},
                        {"path":"app/src/main/java/x/data","files":[{"path":"app/src/main/java/x/data/Repo.kt","deps":[]}]},
                        {"path":"app/src/main/java/x/ui","files":[{"path":"app/src/main/java/x/ui/Main.kt","deps":["app/src/main/java/x/data/Repo.kt"]}]}]}"""
                    else -> """{"tasks":[{"id":"a","title":"A","brief":"do a"},{"id":"b","title":"B","brief":"do b","deps":["a"]}]}"""
                }
                "search" -> "- fact from ${n.brief} (https://example.org/${n.id})"
                "sort" -> """{"outline":["Costs","Policy"],"claims":["Solar costs fell 40%","ZESCO raised tariffs in 2025"]}"""
                "review" -> if (n.brief.contains("40%")) "VERIFIED\nIRENA 2025 (https://irena.org)" else "DISPUTED\nonly one source"
                "stitch" -> "# Solar in Zambia\n\nSummary.\n\n## Costs\nx\n\n## Policy\ny\n\n## Sources\n1. https://irena.org"
                "rank" -> """{"title":"Amapiano 2025","items":[{"title":"Song A","subtitle":"Artist A","image":"https://img/a.jpg","url":"https://s/a","why":"#1"},{"title":"Song B","subtitle":"Artist B","image":"","url":"https://s/b","why":"rising"}]}"""
                "file" -> "wrote ${n.path}"
                else -> "done ${n.id}"
            }
        }
        override suspend fun mkdir(path: String): Boolean { made += path; return true }
        override suspend fun exec(command: String): Pair<Int, String> { builds++; return 0 to "BUILD SUCCESSFUL" }
        override suspend fun saveDoc(title: String, markdown: String): String { saved = title to markdown; return "/root/phone/Documents/solar.md" }
    }

    @Test fun deepResearchRunsSearchesInParallelReviewsClaimsAndExportsADocCard() = runTest {
        val ops = FakeOps()
        val store = BlueprintStore(Pipelines.skeleton(Pipelines.Shape.RESEARCH, "deep dive into solar mini-grids in Zambia"))
        val out = Orchestrator(store, Pipelines.runner(ops), locks = KeyedLocks()).run()
        assertTrue(out.failed.toString(), out.ok)
        assertEquals(3, ops.peakSearch)                                          // the searches ran side by side
        assertEquals(3, store.notes("finding/").size)                            // each saved its findings to the shared store
        assertEquals(listOf("review:0", "review:1"), store.childrenOf("sort"))   // one reviewer per claim
        val stitchPrompt = ops.prompts.first { it.first == "stitch" }.second
        assertTrue(stitchPrompt.contains("DISPUTED"))                            // the stitcher saw the claim checks
        assertTrue(stitchPrompt.contains("fact from zesco tariffs"))             // and every finding
        assertEquals("Solar in Zambia", ops.saved!!.first)
        val card = store.output("export")!!
        assertTrue(card.startsWith("```doc\n"))
        val o = JSONObject(card.removePrefix("```doc\n").removeSuffix("\n```"))
        assertEquals("/root/phone/Documents/solar.md", o.getString("path"))
        assertEquals("3 sections \u00b7 ${ops.saved!!.second.split(Regex("\\s+")).count { it.isNotBlank() }} words", o.getString("summary"))
    }

    @Test fun mediaDiscoveryEndsInAGalleryWithArtwork() = runTest {
        val ops = FakeOps()
        val store = BlueprintStore(Pipelines.skeleton(Pipelines.Shape.MEDIA, "find songs of the amapiano genre"))
        assertTrue(Orchestrator(store, Pipelines.runner(ops), locks = KeyedLocks()).run().ok)
        assertEquals(2, ops.peakSearch)
        val g = store.output("curate")!!
        assertTrue(g.startsWith("```gallery\n"))
        val o = JSONObject(g.removePrefix("```gallery\n").removeSuffix("\n```"))
        assertEquals("https://img/a.jpg", o.getJSONArray("items").getJSONObject(0).getString("image"))
        assertEquals(2, o.getJSONArray("items").length())
    }

    @Test fun apkShowcasePlansFoldersFillsFilesBuildsAndVerifies() = runTest {
        val ops = FakeOps()
        val store = BlueprintStore(Pipelines.skeleton(Pipelines.Shape.APK, "build an APK for a habit tracker"))
        val out = Orchestrator(store, Pipelines.runner(ops), locks = KeyedLocks()).run()
        assertTrue(out.failed.toString(), out.ok)
        assertEquals(setOf(".", "app/src/main/java/x/data", "app/src/main/java/x/ui"), ops.made.toSet())
        assertEquals("interface Repo { fun all(): List<Habit> }", store.contract("Repo"))
        // the UI file's agent was given the shared contract and the Repo file's output
        val ui = ops.prompts.first { it.first == "file:app/src/main/java/x/ui/Main.kt" }.second
        assertTrue(ui.contains("interface Repo"))
        assertTrue(ui.contains("wrote app/src/main/java/x/data/Repo.kt"))
        assertTrue(ui.contains("You own: app/src/main/java/x/ui/Main.kt"))
        assertEquals(2, ops.builds)   // build + verify in the terminal
    }

    @Test fun genericTasksKeepTheirOwnOrder() = runTest {
        val ops = FakeOps()
        val store = BlueprintStore(Pipelines.skeleton(Pipelines.Shape.GENERIC, "plan my week"))
        assertTrue(Orchestrator(store, Pipelines.runner(ops), locks = KeyedLocks()).run().ok)
        assertEquals(listOf("task:a"), store.node("task:b")!!.deps)
        val ids = ops.prompts.map { it.first }
        assertTrue(ids.indexOf("task:a") < ids.indexOf("task:b"))
        assertTrue(ids.indexOf("task:b") < ids.indexOf("stitch"))
    }

    @Test fun aPlannerThatReturnsNoJsonFailsAndIsRetried() = runTest {
        var calls = 0
        val ops = object : Pipelines.Ops { override suspend fun agent(ctx: NodeContext, prompt: String): String { calls++; return if (calls == 1) "I think we should..." else """{"tasks":[{"id":"a","brief":"x"}]}""" } }
        val store = BlueprintStore(Pipelines.skeleton(Pipelines.Shape.GENERIC, "x"))
        assertTrue(Orchestrator(store, Pipelines.runner(ops), Orchestrator.Config(backoffMs = 1), locks = KeyedLocks()).run().ok)
        assertTrue(ops.toString().isNotEmpty())
        assertEquals(2, store.attempts("plan"))
    }

    @Test fun aMessageToARunningBlueprintReachesEveryLaterAgent() {
        val store = BlueprintStore(Pipelines.skeleton(Pipelines.Shape.GENERIC, "plan my week"))
        store.note("steer/1", "keep Fridays free")
        val p = Pipelines.prompt(NodeContext(store.node("plan")!!, store, emptyMap(), 1, "") { })
        assertTrue(p.contains("keep Fridays free"))
    }

    @Test fun budgetFollowsRam() {
        assertEquals(1, AgentBudget.cap(8L shl 30, lowRamDevice = true))
        assertEquals(4, AgentBudget.cap(6L shl 30))
        assertEquals(3, AgentBudget.cap(4L shl 30))
        assertEquals(2, AgentBudget.cap(3L shl 30))
    }

    @Test fun jsonInFindsFencedAndBareObjects() {
        assertEquals("b", Pipelines.jsonIn("x ```json\n{\"a\":\"b\"}\n``` y")!!.getString("a"))
        assertEquals(1, Pipelines.jsonIn("plan: {\"n\":1} done")!!.getInt("n"))
        assertEquals(null, Pipelines.jsonIn("no json here"))
    }
}
