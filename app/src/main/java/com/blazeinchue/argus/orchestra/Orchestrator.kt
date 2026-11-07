package com.blazeinchue.argus.orchestra

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * 0.52.0: the generic dataflow scheduler over a [BlueprintStore].
 *
 *  - A node starts the moment its own dependencies (and what they added) are done: never when "all siblings" are.
 *  - At most [Config.maxParallel] node agents run at once (AgentBudget picks it from the phone's RAM).
 *  - Each node holds exclusive locks on the paths it writes (shared [KeyedLocks], the same the tool scheduler uses),
 *    so two agents never write one file at once, even across blueprints or with the chat agent.
 *  - A node that fails is retried (with backoff and the error in its context) up to [Config.maxAttempts]; then
 *    [escalate] decides: [Escalation.Fix] adds fix nodes (a fixer agent) and retries the node after them,
 *    [Escalation.Skip] lets dependents go on without it, [Escalation.Fail] fails it, and everything that depends on
 *    it is cancelled.
 *  - Cancelling the coroutine that runs [run] cancels every node agent (and anything they started: nested
 *    orchestrators run inside their node's coroutine), and every unfinished node ends CANCELLED.
 *
 * The node work itself is [runner] (an LLM agent with tools in the app, a fake in OrchestratorTest). Pure.
 */
class Orchestrator(
    val store: BlueprintStore,
    private val runner: suspend (NodeContext) -> NodeResult,
    private val cfg: Config = Config(),
    private val escalate: suspend (Node, String) -> Escalation = { _, _ -> Escalation.Fail },
    private val locks: KeyedLocks = KeyedLocks.shared,
    private val save: ((BlueprintStore) -> Unit)? = null,
) {
    data class Config(
        val maxParallel: Int = 4,
        val maxAttempts: Int = 2,
        val backoffMs: Long = 400,
        val nodeTimeoutMs: Long = 10 * 60_000L,
        /** Escalations allowed per node (a fix round), so a broken node can't loop forever. */
        val maxEscalations: Int = 1,
        /** Nodes a run may grow to (children + fixes), a guard against a planner that never stops adding. */
        val maxNodes: Int = 200,
    )

    data class Outcome(val ok: Boolean, val done: Int, val failed: List<String>, val cancelled: List<String>, val outputs: Map<String, String>)

    private val escalations = HashMap<String, Int>()

    suspend fun run(): Outcome {
        try {
            coroutineScope {
                val sem = Semaphore(cfg.maxParallel.coerceAtLeast(1))
                val wake = Channel<Unit>(Channel.CONFLATED)
                val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
                while (true) {
                    // dependents of a broken node can never run
                    for ((n, dep) in store.blocked()) {
                        store.mark(n.id, NodeState.CANCELLED, error = "blocked: $dep did not finish")
                        store.emit(BpEvent.Cancelled(n.id))
                    }
                    val ready = store.ready()
                    for (n in ready) {
                        store.mark(n.id, NodeState.RUNNING)
                        store.emit(BpEvent.Ready(n.id))
                        inFlight.incrementAndGet()
                        launch {
                            try { sem.withPermit { runNode(n) } }
                            finally {
                                // an agent cancelled on its own (not the whole run) ends CANCELLED, never stuck RUNNING
                                if (store.state(n.id) == NodeState.RUNNING) { store.mark(n.id, NodeState.CANCELLED); store.emit(BpEvent.Cancelled(n.id)) }
                                inFlight.decrementAndGet(); wake.trySend(Unit)
                            }
                        }
                    }
                    if (inFlight.get() == 0 && store.ready().isEmpty()) {
                        if (store.blocked().isNotEmpty()) continue
                        break
                    }
                    wake.receive()
                }
                // settle: whatever still waits on a node that never appeared fails
                for ((n, dep) in store.orphans()) {
                    store.mark(n.id, NodeState.FAILED, error = "missing dependency $dep (never planned)")
                    store.emit(BpEvent.Failed(n.id, "missing dependency $dep", store.attempts(n.id), true))
                }
            }
        } catch (e: CancellationException) {
            for (n in store.all()) if (store.state(n.id)?.terminal != true) { store.mark(n.id, NodeState.CANCELLED); store.emit(BpEvent.Cancelled(n.id)) }
            persist()
            throw e
        }
        val all = store.all()
        val failed = all.filter { store.state(it.id) == NodeState.FAILED }.map { it.id }
        val cancelled = all.filter { store.state(it.id) == NodeState.CANCELLED }.map { it.id }
        val ok = failed.isEmpty() && cancelled.isEmpty()
        store.emit(BpEvent.Finished(ok = ok))
        persist()
        return Outcome(ok, store.count(NodeState.DONE), failed, cancelled, all.mapNotNull { n -> store.output(n.id)?.let { n.id to it } }.toMap())
    }

    private fun persist() { try { save?.invoke(store) } catch (_: Throwable) { } }

    private suspend fun runNode(n0: Node) {
        var attempt = store.attempts(n0.id)
        var lastError = ""
        while (true) {
            val n = store.node(n0.id) ?: n0
            store.emit(BpEvent.Started(n.id, attempt))
            val claims = (n.writes + listOfNotNull(n.path.takeIf { it.isNotEmpty() && n.kind == "file" })).distinct()
                .map { KeyedLocks.Claim("file:" + ToolScheduler.Resources.path(org.json.JSONObject().put("path", it)), true) }
            val res: NodeResult = try {
                locks.with(claims) {
                    withTimeout(cfg.nodeTimeoutMs) {
                        runner(NodeContext(n, store, store.inputsFor(n.deps), attempt, lastError) { store.emit(BpEvent.Step(n.id, it)) })
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                NodeResult.fail("timed out after ${cfg.nodeTimeoutMs / 1000}s")
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                NodeResult.fail(e.message ?: e.javaClass.simpleName)
            }
            if (res.ok) {
                val room = (cfg.maxNodes - store.all().size).coerceAtLeast(0)
                val kids = res.children.take(room).map { if (it.parent.isEmpty()) it.copy(parent = n.id) else it }
                // children are registered before the node is DONE, so its dependents see the whole subtree at once
                store.add(kids, n.id)
                res.notes.forEach { (k, v) -> store.note(k, v) }
                store.mark(n.id, NodeState.DONE, output = res.output)
                store.emit(BpEvent.Done(n.id))
                persist()
                return
            }
            lastError = res.error
            if (attempt < cfg.maxAttempts) {
                store.emit(BpEvent.Failed(n.id, res.error, attempt, false))
                delay(cfg.backoffMs * attempt)
                store.mark(n.id, NodeState.RUNNING)
                attempt = store.attempts(n.id)
                continue
            }
            val used = synchronized(escalations) { escalations[n.id] ?: 0 }
            val decision = if (used < cfg.maxEscalations) try { escalate(n, res.error) } catch (e: CancellationException) { throw e } catch (_: Throwable) { Escalation.Fail } else Escalation.Fail
            when (decision) {
                is Escalation.Fix -> {
                    synchronized(escalations) { escalations[n.id] = used + 1 }
                    val fixes = decision.fixes.map { it.copy(parent = n.id) }
                    val added = store.add(fixes, n.id)
                    store.addDeps(n.id, added)
                    store.emit(BpEvent.Escalated(n.id, added))
                    // back to PENDING: the scheduler starts it again once its fixes are done
                    store.mark(n.id, NodeState.PENDING, error = res.error)
                    persist()
                    return
                }
                Escalation.Skip -> { store.mark(n.id, NodeState.SKIPPED, error = res.error); store.emit(BpEvent.Failed(n.id, res.error, attempt, true)); persist(); return }
                Escalation.Fail -> { store.mark(n.id, NodeState.FAILED, error = res.error); store.emit(BpEvent.Failed(n.id, res.error, attempt, true)); persist(); return }
            }
        }
    }
}

/** What a node agent gets: its node, the shared store, the outputs of its deps (and their subtrees), its attempt. */
class NodeContext(
    val node: Node,
    val store: BlueprintStore,
    val inputs: Map<String, String>,
    val attempt: Int,
    /** The previous attempt's error ("" on the first). */
    val lastError: String,
    /** Streams one step line into the run's pill. */
    val report: (String) -> Unit,
)

data class NodeResult(
    val ok: Boolean,
    val output: String = "",
    val error: String = "",
    /** Nodes this node adds (nested agents that start as soon as their own deps are ready). */
    val children: List<Node> = emptyList(),
    /** Shared notes to save (findings, contracts). */
    val notes: Map<String, String> = emptyMap(),
) {
    companion object {
        fun ok(output: String, children: List<Node> = emptyList(), notes: Map<String, String> = emptyMap()) = NodeResult(true, output, "", children, notes)
        fun fail(error: String) = NodeResult(false, "", error.ifBlank { "failed" })
    }
}

sealed class Escalation {
    data class Fix(val fixes: List<Node>) : Escalation()
    object Skip : Escalation()
    object Fail : Escalation()
}
