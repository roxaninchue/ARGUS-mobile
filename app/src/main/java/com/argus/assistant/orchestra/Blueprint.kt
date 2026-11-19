package com.blaze.assistant.orchestra

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.52.0 blueprint-driven orchestration, the data part.
 *
 * A [Blueprint] is ONE plan for a multi-part task, written by a planner agent: a graph of [Node]s (folders, files,
 * modules, searches, sections, reviews, a build, a verify step…) with their dependencies and the contracts
 * (interfaces) parallel agents must honour so they don't conflict. The [BlueprintStore] is the shared, thread-safe
 * state every agent of a run reads and updates: node states and outputs, shared notes (findings saved by research
 * agents, file contracts), and an event bus ("node ready", "node done", "node failed"…) the pill and the scheduler
 * listen to. Nodes can be added while the run goes: a folder node that finishes adds its file nodes, a sorter adds a
 * reviewer per claim, a verifier adds fix nodes — that is how nested agents start the moment their parent is ready.
 *
 * Dependency rule (dataflow): a node starts when each node it depends on is DONE **and so is everything that node
 * added** (its subtree). It never waits for its own siblings. A dependency that doesn't exist yet is waited for (a file
 * in another folder that hasn't been expanded yet); one that never appears fails the node when the run settles.
 *
 * Pure (kotlinx.coroutines + org.json); BlueprintTest, OrchestratorTest.
 */
data class Node(
    val id: String,
    /** plan, folder, file, module, search, sort, review, stitch, rank, curate, build, verify, fix, export, section, task… */
    val kind: String,
    val title: String,
    /** The specialist that runs it (researcher, coder, writer, planner, reviewer…; "" = general). */
    val role: String = "",
    val brief: String = "",
    val deps: List<String> = emptyList(),
    /** The node that added it ("" = part of the first plan). */
    val parent: String = "",
    /** The file or folder it owns ("" = none). */
    val path: String = "",
    /** Paths it writes: held exclusively (KeyedLocks) while it runs, so no two agents write one file at once. */
    val writes: List<String> = emptyList(),
    /** The interface / contract it must produce or honour. */
    val contract: String = "",
    /** Run through the terminal (builds, tests): ExecRoute / Blaze Terminal. */
    val terminal: Boolean = false,
    /** Planner payload for the pipeline (e.g. a folder's file list), JSON text. */
    val data: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("title", title).put("role", role).put("brief", brief)
        .put("deps", JSONArray(deps)).put("parent", parent).put("path", path).put("writes", JSONArray(writes)).put("contract", contract)
        .put("terminal", terminal).put("data", data)

    companion object {
        fun fromJson(o: JSONObject): Node? {
            val id = o.optString("id").trim(); if (id.isEmpty()) return null
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            return Node(id, o.optString("kind", "task").ifBlank { "task" }, o.optString("title").ifBlank { id }, o.optString("role"), o.optString("brief"),
                list("deps"), o.optString("parent"), o.optString("path"), list("writes"), o.optString("contract"), o.optBoolean("terminal"), o.optString("data"))
        }
    }
}

data class Blueprint(val id: String, val goal: String, val pipeline: String, val nodes: List<Node>, val contracts: Map<String, String> = emptyMap()) {
    /** Problems that make the plan unusable: duplicate ids, cycles, two nodes writing one path without an order. Empty = fine. */
    fun validate(): List<String> = Blueprints.validate(nodes)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("goal", goal).put("pipeline", pipeline)
        .put("nodes", JSONArray(nodes.map { it.toJson() })).put("contracts", JSONObject(contracts as Map<*, *>))

    companion object {
        fun fromJson(o: JSONObject): Blueprint {
            val a = o.optJSONArray("nodes") ?: JSONArray()
            val c = o.optJSONObject("contracts")
            return Blueprint(o.optString("id"), o.optString("goal"), o.optString("pipeline"),
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Node::fromJson) },
                c?.keys()?.asSequence()?.associateWith { c.optString(it) } ?: emptyMap())
        }
    }
}

object Blueprints {
    fun validate(nodes: List<Node>): List<String> {
        val out = ArrayList<String>()
        val ids = nodes.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { out += "duplicate node id $it" }
        val byId = nodes.associateBy { it.id }
        // cycles among known nodes (unknown deps are allowed: they may be added later)
        val state = HashMap<String, Int>()
        fun visit(id: String, stack: List<String>): Boolean {
            when (state[id]) { 1 -> { out += "cycle: " + (stack + id).joinToString(" -> "); return false }; 2 -> return true }
            state[id] = 1
            byId[id]?.deps?.filter { it in byId }?.forEach { if (!visit(it, stack + id)) return false }
            state[id] = 2; return true
        }
        for (id in ids) if (state[id] == null && !visit(id, emptyList())) break
        // the write contract: two nodes may write one path only if one (transitively) depends on the other
        val writers = HashMap<String, MutableList<String>>()
        nodes.forEach { n -> n.writes.forEach { writers.getOrPut(it) { ArrayList() } += n.id } }
        fun reaches(a: String, b: String, seen: HashSet<String> = HashSet()): Boolean {
            if (a == b) return true
            if (!seen.add(a)) return false
            return byId[a]?.deps?.any { reaches(it, b, seen) } == true
        }
        writers.filter { it.value.size > 1 }.forEach { (p, ws) ->
            for (i in ws.indices) for (j in i + 1 until ws.size) if (!reaches(ws[i], ws[j]) && !reaches(ws[j], ws[i]))
                out += "$p is written by both ${ws[i]} and ${ws[j]} with no order between them"
        }
        return out
    }
}

enum class NodeState { PENDING, RUNNING, DONE, FAILED, CANCELLED, SKIPPED;
    val terminal get() = this == DONE || this == FAILED || this == CANCELLED || this == SKIPPED
}

sealed class BpEvent {
    abstract val node: String
    data class Added(override val node: String, val by: String) : BpEvent()
    data class Ready(override val node: String) : BpEvent()
    data class Started(override val node: String, val attempt: Int) : BpEvent()
    data class Step(override val node: String, val text: String) : BpEvent()
    data class Done(override val node: String) : BpEvent()
    data class Failed(override val node: String, val error: String, val attempt: Int, val final: Boolean) : BpEvent()
    data class Escalated(override val node: String, val fixes: List<String>) : BpEvent()
    data class Cancelled(override val node: String) : BpEvent()
    data class Finished(override val node: String = "", val ok: Boolean) : BpEvent()
}

/** The shared blueprint state of one run. Every method is thread-safe. */
class BlueprintStore(bp: Blueprint) {
    val id = bp.id
    val goal = bp.goal
    val pipeline = bp.pipeline
    private val lock = Any()
    private val nodes = LinkedHashMap<String, Node>()
    private val states = HashMap<String, NodeState>()
    private val outputs = HashMap<String, String>()
    private val errors = HashMap<String, String>()
    private val attempts = HashMap<String, Int>()
    private val children = HashMap<String, MutableList<String>>()
    private val notes = LinkedHashMap<String, String>()
    private val contracts = LinkedHashMap<String, String>(bp.contracts)
    private val _events = MutableSharedFlow<BpEvent>(replay = 0, extraBufferCapacity = 512)
    val events: SharedFlow<BpEvent> = _events
    /** Extra listener called synchronously for every event (the pill, tests). */
    @Volatile var onEvent: ((BpEvent) -> Unit)? = null

    init { bp.nodes.forEach { put(it) } }

    private fun put(n: Node) {
        nodes[n.id] = n; states[n.id] = NodeState.PENDING
        if (n.parent.isNotEmpty()) children.getOrPut(n.parent) { ArrayList() }.let { if (n.id !in it) it += n.id }
    }

    fun emit(e: BpEvent) { _events.tryEmit(e); try { onEvent?.invoke(e) } catch (_: Throwable) { } }

    fun node(id: String): Node? = synchronized(lock) { nodes[id] }
    fun all(): List<Node> = synchronized(lock) { nodes.values.toList() }
    fun state(id: String): NodeState? = synchronized(lock) { states[id] }
    fun output(id: String): String? = synchronized(lock) { outputs[id] }
    fun error(id: String): String? = synchronized(lock) { errors[id] }
    fun attempts(id: String): Int = synchronized(lock) { attempts[id] ?: 0 }
    fun childrenOf(id: String): List<String> = synchronized(lock) { children[id]?.toList() ?: emptyList() }

    /** Adds nodes while the run goes (a parent's children, fixes). Ids already present are skipped. Returns the added ids. */
    fun add(list: List<Node>, by: String = ""): List<String> {
        val added = synchronized(lock) {
            list.filter { it.id !in nodes }.map { n -> val m = if (by.isNotEmpty() && n.parent.isEmpty()) n.copy(parent = by) else n; put(m); m.id }
        }
        added.forEach { emit(BpEvent.Added(it, by)) }
        return added
    }

    /** Adds deps to a node (an escalation's fix nodes run before its retry). */
    fun addDeps(id: String, deps: List<String>) = synchronized(lock) { nodes[id]?.let { nodes[id] = it.copy(deps = (it.deps + deps).distinct()) } }

    fun mark(id: String, s: NodeState, output: String? = null, error: String? = null) {
        synchronized(lock) {
            states[id] = s
            if (output != null) outputs[id] = output
            if (error != null) errors[id] = error
            if (s == NodeState.RUNNING) attempts[id] = (attempts[id] ?: 0) + 1
        }
    }

    /** Every node [id] added, and what those added, recursively. */
    fun subtree(id: String): List<String> = synchronized(lock) {
        val out = ArrayList<String>(); val q = ArrayDeque(children[id] ?: emptyList<String>())
        while (q.isNotEmpty()) { val c = q.removeFirst(); if (c in out) continue; out += c; children[c]?.let { q.addAll(it) } }
        out
    }

    /** A dep is satisfied when it and its whole subtree are DONE (or SKIPPED). */
    private fun satisfied(dep: String): Boolean {
        val ok = { s: NodeState? -> s == NodeState.DONE || s == NodeState.SKIPPED }
        if (!ok(states[dep])) return false
        return subtreeUnlocked(dep).all { ok(states[it]) }
    }
    private fun subtreeUnlocked(id: String): List<String> {
        val out = ArrayList<String>(); val q = ArrayDeque(children[id] ?: emptyList<String>())
        while (q.isNotEmpty()) { val c = q.removeFirst(); if (c in out) continue; out += c; children[c]?.let { q.addAll(it) } }
        return out
    }
    /** A dep that failed or was cancelled (itself or in its subtree) blocks its dependents for good. */
    private fun broken(dep: String): Boolean {
        val bad = { s: NodeState? -> s == NodeState.FAILED || s == NodeState.CANCELLED }
        return bad(states[dep]) || subtreeUnlocked(dep).any { bad(states[it]) }
    }

    /** PENDING nodes whose deps are all satisfied. */
    fun ready(): List<Node> = synchronized(lock) {
        nodes.values.filter { states[it.id] == NodeState.PENDING && it.deps.all { d -> d in nodes && satisfied(d) } }
    }

    /** PENDING nodes that can never run (a dep failed / was cancelled), with the dep that blocks them. */
    fun blocked(): List<Pair<Node, String>> = synchronized(lock) {
        nodes.values.filter { states[it.id] == NodeState.PENDING }.mapNotNull { n -> n.deps.firstOrNull { d -> d in nodes && broken(d) }?.let { n to it } }
    }

    /** PENDING nodes waiting on deps that don't exist (only meaningful once nothing runs any more). */
    fun orphans(): List<Pair<Node, String>> = synchronized(lock) {
        nodes.values.filter { states[it.id] == NodeState.PENDING }.mapNotNull { n -> n.deps.firstOrNull { it !in nodes }?.let { n to it } }
    }

    fun count(s: NodeState): Int = synchronized(lock) { states.values.count { it == s } }
    fun running(): List<String> = synchronized(lock) { states.filter { it.value == NodeState.RUNNING }.keys.toList() }

    /** Outputs of [ids] and of everything they added, keyed by node id (a node's inputs). */
    fun inputsFor(ids: List<String>): Map<String, String> = synchronized(lock) {
        val out = LinkedHashMap<String, String>()
        for (d in ids) { outputs[d]?.let { out[d] = it }; subtreeUnlocked(d).forEach { c -> outputs[c]?.let { out[c] = it } } }
        out
    }

    // ---- shared notes (findings, contracts) ----
    fun note(key: String, value: String) = synchronized(lock) { notes[key] = value }
    fun notes(prefix: String = ""): Map<String, String> = synchronized(lock) { notes.filterKeys { it.startsWith(prefix) } }
    fun contract(name: String): String? = synchronized(lock) { contracts[name] }
    fun contracts(): Map<String, String> = synchronized(lock) { contracts.toMap() }
    fun putContract(name: String, text: String) = synchronized(lock) { contracts[name] = text }

    /** The run as JSON (saved after every change, so a restart picks up where it was: RUNNING goes back to PENDING). */
    fun snapshot(): JSONObject = synchronized(lock) {
        JSONObject().put("blueprint", Blueprint(id, goal, pipeline, nodes.values.toList(), contracts).toJson())
            .put("states", JSONObject(states.mapValues { it.value.name } as Map<*, *>))
            .put("outputs", JSONObject(outputs.mapValues { it.value.take(20_000) } as Map<*, *>))
            .put("errors", JSONObject(errors as Map<*, *>))
            .put("attempts", JSONObject(attempts as Map<*, *>))
            .put("notes", JSONObject(notes.mapValues { it.value.take(20_000) } as Map<*, *>))
    }

    companion object {
        fun restore(o: JSONObject): BlueprintStore {
            val s = BlueprintStore(Blueprint.fromJson(o.optJSONObject("blueprint") ?: JSONObject()))
            synchronized(s.lock) {
                o.optJSONObject("states")?.let { st -> st.keys().forEach { k ->
                    val v = try { NodeState.valueOf(st.optString(k)) } catch (_: Exception) { NodeState.PENDING }
                    if (k in s.nodes) s.states[k] = if (v == NodeState.RUNNING) NodeState.PENDING else v } }
                o.optJSONObject("outputs")?.let { m -> m.keys().forEach { s.outputs[it] = m.optString(it) } }
                o.optJSONObject("errors")?.let { m -> m.keys().forEach { s.errors[it] = m.optString(it) } }
                o.optJSONObject("attempts")?.let { m -> m.keys().forEach { s.attempts[it] = m.optInt(it) } }
                o.optJSONObject("notes")?.let { m -> m.keys().forEach { s.notes[it] = m.optString(it) } }
            }
            return s
        }
    }
}
