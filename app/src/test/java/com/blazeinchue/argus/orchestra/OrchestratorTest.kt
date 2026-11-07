package com.blazeinchue.argus.orchestra

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The orchestrator with fake agents: dataflow starts (children before siblings finish), one writer per file, retries
 * and escalation to fix agents, blocked dependents, cancellation cascading into every agent and nested runs.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OrchestratorTest {
    private fun files(vararg f: Pair<String, List<String>>) = JSONArray(f.map { (p, deps) -> JSONObject().put("path", p).put("deps", JSONArray(deps)) }).toString()

    @Test fun childrenStartBeforeTheirParentsSiblingsFinish() = runTest {
        // plan → folder A (fast) and folder B (slow); A's files must start while B is still working.
        val bp = Blueprint("t", "app", "apk", listOf(
            Node("plan", "plan", "plan"),
            Node("build", "build", "build", deps = listOf("plan")),
        ))
        val store = BlueprintStore(bp)
        val started = ConcurrentHashMap<String, Long>(); val ended = ConcurrentHashMap<String, Long>()
        val orch = Orchestrator(store, { ctx ->
            val n = ctx.node; started[n.id] = currentTime
            val r = when (n.kind) {
                "plan" -> { delay(100); NodeResult.ok("plan", listOf(
                    Node("dir:A", "folder", "A", path = "A", data = files("A/1.kt" to emptyList(), "A/2.kt" to listOf("A/1.kt"))),
                    Node("dir:B", "folder", "B", path = "B", data = files("B/1.kt" to emptyList())))) }
                "folder" -> { delay(if (n.id == "dir:A") 100 else 2000); NodeResult.ok("made", Pipelines.filesOf(n)) }
                "file" -> { delay(300); NodeResult.ok("code") }
                else -> { delay(50); NodeResult.ok("built") }
            }
            ended[n.id] = currentTime; r
        }, Orchestrator.Config(maxParallel = 8), locks = KeyedLocks())
        val out = orch.run()
        assertTrue(out.ok)
        // A's first file started long before folder B (its parent's sibling) finished
        assertTrue(started.getValue("file:A/1.kt") < ended.getValue("dir:B"))
        // A/2.kt waited for A/1.kt (its own dep) and nothing else
        assertTrue(started.getValue("file:A/2.kt") >= ended.getValue("file:A/1.kt"))
        assertTrue(started.getValue("file:A/2.kt") < ended.getValue("dir:B"))
        // build waited for the whole plan subtree
        assertTrue(started.getValue("build") >= ended.filterKeys { it.startsWith("file:") }.values.maxOrNull()!!)
        assertEquals(7, out.done)
    }

    @Test fun aFileInAnotherFolderIsWaitedForUntilItIsPlannedAndWritten() = runTest {
        val store = BlueprintStore(Blueprint("t", "g", "apk", listOf(Node("plan", "plan", "p"))))
        val started = ConcurrentHashMap<String, Long>(); val ended = ConcurrentHashMap<String, Long>()
        Orchestrator(store, { ctx ->
            val n = ctx.node; started[n.id] = currentTime
            val r = when (n.kind) {
                "plan" -> NodeResult.ok("", listOf(
                    Node("dir:ui", "folder", "ui", path = "ui", data = files("ui/Screen.kt" to listOf("data/Repo.kt"))),
                    Node("dir:data", "folder", "data", path = "data", data = files("data/Repo.kt" to emptyList()))))
                "folder" -> { delay(if (n.id == "dir:data") 1000 else 10); NodeResult.ok("", Pipelines.filesOf(n)) }
                else -> { delay(200); NodeResult.ok("") }
            }
            ended[n.id] = currentTime; r
        }, locks = KeyedLocks()).run().also { assertTrue(it.ok) }
        assertTrue(started.getValue("file:ui/Screen.kt") >= ended.getValue("file:data/Repo.kt"))
    }

    @Test fun noTwoAgentsWriteTheSameFileAtOnce() = runBlocking {
        // 12 agents, 3 files, real threads: the per-path writer count never goes above 1.
        val nodes = (0 until 12).map { Node("n$it", "file", "n$it", path = "f${it % 3}.kt", writes = listOf("f${it % 3}.kt")) }
        val live = ConcurrentHashMap<String, AtomicInteger>(); val peak = AtomicInteger()
        val out = withContext(Dispatchers.Default) {
            Orchestrator(BlueprintStore(Blueprint("t", "g", "generic", nodes)), { ctx ->
                val p = ctx.node.path
                val n = live.getOrPut(p) { AtomicInteger() }.incrementAndGet()
                peak.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                Thread.sleep(15); delay(10)
                live.getValue(p).decrementAndGet()
                NodeResult.ok("w")
            }, Orchestrator.Config(maxParallel = 6), locks = KeyedLocks()).run()
        }
        assertTrue(out.ok)
        assertEquals(1, peak.get())
    }

    @Test fun validateCatchesUnorderedWritersCyclesAndDuplicates() {
        val errs = Blueprints.validate(listOf(
            Node("a", "file", "a", writes = listOf("x.kt")), Node("b", "file", "b", writes = listOf("x.kt")),
            Node("c", "task", "c", deps = listOf("d")), Node("d", "task", "d", deps = listOf("c")),
            Node("a", "task", "dup")))
        assertTrue(errs.any { it.contains("x.kt is written by both") })
        assertTrue(errs.any { it.startsWith("cycle") })
        assertTrue(errs.any { it.contains("duplicate node id a") })
        assertTrue(Blueprints.validate(listOf(Node("a", "file", "a", writes = listOf("x")), Node("b", "fix", "b", deps = listOf("a"), writes = listOf("x")))).isEmpty())
    }

    @Test fun aFailingNodeIsRetriedWithItsError() = runTest {
        val seenErrors = mutableListOf<String>()
        val store = BlueprintStore(Blueprint("t", "g", "generic", listOf(Node("a", "task", "a"), Node("b", "task", "b", deps = listOf("a")))))
        val out = Orchestrator(store, { ctx ->
            seenErrors += ctx.lastError
            if (ctx.node.id == "a" && ctx.attempt == 1) NodeResult.fail("network blip") else NodeResult.ok("ok")
        }, Orchestrator.Config(maxAttempts = 2, backoffMs = 10), locks = KeyedLocks()).run()
        assertTrue(out.ok)
        assertEquals(2, store.attempts("a"))
        assertEquals(listOf("", "network blip", ""), seenErrors)
    }

    @Test fun aBuildThatKeepsFailingGetsAFixAgentThenSucceeds() = runTest {
        val store = BlueprintStore(Blueprint("t", "g", "apk", listOf(Node("build", "build", "Build"), Node("verify", "verify", "Verify", deps = listOf("build")))))
        var fixed = false
        val order = mutableListOf<String>()
        val out = Orchestrator(store, { ctx ->
            order += ctx.node.id
            when (ctx.node.kind) {
                "build" -> if (fixed) NodeResult.ok("BUILD SUCCESSFUL") else NodeResult.fail("e: file:///w/app/src/Main.kt:3:1 Unresolved reference")
                "fix" -> { assertEquals(listOf("app/src/Main.kt"), ctx.node.writes); fixed = true; NodeResult.ok("fixed") }
                else -> NodeResult.ok("tests pass")
            }
        }, Orchestrator.Config(maxAttempts = 2, backoffMs = 1), escalate = AgentBudget.escalation("/w/"), locks = KeyedLocks()).run()
        assertTrue(out.ok)
        assertEquals(listOf("build", "build", "fix:build", "build", "verify"), order)
        assertEquals(NodeState.DONE, store.state("fix:build"))
    }

    @Test fun aNodeThatFailsForGoodCancelsItsDependentsButNotOthers() = runTest {
        val store = BlueprintStore(Blueprint("t", "g", "generic", listOf(
            Node("a", "task", "a"), Node("b", "task", "b", deps = listOf("a")), Node("c", "task", "c", deps = listOf("b")), Node("z", "task", "z"))))
        val out = Orchestrator(store, { ctx -> if (ctx.node.id == "a") NodeResult.fail("broken") else NodeResult.ok("ok") },
            Orchestrator.Config(maxAttempts = 1), locks = KeyedLocks()).run()
        assertFalse(out.ok)
        assertEquals(listOf("a"), out.failed)
        assertEquals(setOf("b", "c"), out.cancelled.toSet())
        assertEquals(NodeState.DONE, store.state("z"))
    }

    @Test fun skipLetsDependentsGoOn() = runTest {
        val store = BlueprintStore(Blueprint("t", "g", "generic", listOf(Node("a", "review", "a"), Node("b", "stitch", "b", deps = listOf("a")))))
        val out = Orchestrator(store, { ctx -> if (ctx.node.id == "a") NodeResult.fail("unclear") else NodeResult.ok("doc") },
            Orchestrator.Config(maxAttempts = 1), escalate = { _, _ -> Escalation.Skip }, locks = KeyedLocks()).run()
        assertTrue(out.ok)
        assertEquals(NodeState.SKIPPED, store.state("a")); assertEquals(NodeState.DONE, store.state("b"))
    }

    @Test fun cancellationCascadesIntoEveryAgentAndNestedRuns() = runTest {
        val cancelledAgents = AtomicInteger()
        val store = BlueprintStore(Blueprint("t", "g", "generic", listOf(Node("a", "task", "a"), Node("b", "task", "b"), Node("nested", "task", "n"), Node("after", "task", "x", deps = listOf("a")))))
        var innerStore: BlueprintStore? = null
        val job = launch {
            Orchestrator(store, { ctx ->
                try {
                    if (ctx.node.id == "nested") {
                        val inner = BlueprintStore(Blueprint("i", "inner", "generic", listOf(Node("i1", "task", "i1"), Node("i2", "task", "i2"))))
                        innerStore = inner
                        Orchestrator(inner, { _ -> try { awaitCancellation() } finally { cancelledAgents.incrementAndGet() } }, locks = KeyedLocks()).run()
                        NodeResult.ok("")
                    } else awaitCancellation()
                } finally { cancelledAgents.incrementAndGet() }
            }, Orchestrator.Config(maxParallel = 8), locks = KeyedLocks()).run()
        }
        delay(1000)
        assertEquals(setOf("a", "b", "nested"), store.running().toSet())
        job.cancel(); job.join()
        // a, b, nested + the nested run's i1, i2
        assertEquals(5, cancelledAgents.get())
        store.all().forEach { assertEquals(it.id, NodeState.CANCELLED, store.state(it.id)) }
        innerStore!!.all().forEach { assertEquals(NodeState.CANCELLED, innerStore!!.state(it.id)) }
    }

    @Test fun parallelismIsCapped() = runTest {
        val nodes = (0 until 10).map { Node("n$it", "search", "n$it") }
        val live = AtomicInteger(); var peak = 0
        Orchestrator(BlueprintStore(Blueprint("t", "g", "research", nodes)), { _ ->
            peak = maxOf(peak, live.incrementAndGet()); delay(100); live.decrementAndGet(); NodeResult.ok("")
        }, Orchestrator.Config(maxParallel = 3), locks = KeyedLocks()).run()
        assertEquals(3, peak)
    }

    @Test fun aRestoredRunPicksUpWhereItWas() = runTest {
        val store = BlueprintStore(Blueprint("t", "g", "generic", listOf(Node("a", "task", "a"), Node("b", "task", "b", deps = listOf("a")))))
        store.mark("a", NodeState.DONE, output = "A out"); store.mark("b", NodeState.RUNNING)
        val again = BlueprintStore.restore(JSONObject(store.snapshot().toString()))
        assertEquals(NodeState.PENDING, again.state("b"))
        val ran = mutableListOf<String>()
        Orchestrator(again, { ctx -> ran += ctx.node.id; assertEquals(mapOf("a" to "A out"), ctx.inputs); NodeResult.ok("B") }, locks = KeyedLocks()).run()
        assertEquals(listOf("b"), ran)
    }

    @Test fun anOrphanDependencyFailsWhenTheRunSettles() = runTest {
        val store = BlueprintStore(Blueprint("t", "g", "generic", listOf(Node("a", "task", "a", deps = listOf("ghost")))))
        val out = Orchestrator(store, { NodeResult.ok("") }, locks = KeyedLocks()).run()
        assertEquals(listOf("a"), out.failed)
        assertTrue(store.error("a")!!.contains("ghost"))
    }

    @Test fun eventsTellTheStory() = runTest {
        val store = BlueprintStore(Blueprint("t", "g", "generic", listOf(Node("a", "task", "a"))))
        val seen = mutableListOf<String>()
        store.onEvent = { seen += it.javaClass.simpleName }
        Orchestrator(store, { ctx -> ctx.report("working"); NodeResult.ok("", listOf(Node("kid", "task", "kid"))) }, locks = KeyedLocks()).run()
        assertTrue(seen.containsAll(listOf("Ready", "Started", "Step", "Added", "Done", "Finished")))
    }

    @Test fun cancellationExceptionFromAnAgentIsNotSwallowedAsAFailure() = runTest {
        val store = BlueprintStore(Blueprint("t", "g", "generic", listOf(Node("a", "task", "a"))))
        val job = launch { Orchestrator(store, { throw CancellationException("stop") }, locks = KeyedLocks()).run() }
        job.join()
        assertEquals(NodeState.CANCELLED, store.state("a"))
    }
}
