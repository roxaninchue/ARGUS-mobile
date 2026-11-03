package com.blaze.assistant.orchestra

import android.content.Context
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.BgAgents
import com.blaze.assistant.composer.ModelChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 0.52.0: a blueprint run as a background agent. The chat agent calls `orchestrate` (goal + pipeline); that starts a
 * [BgAgents] entry with specialist [SPECIALIST], whose run is [run] here instead of a chat session:
 *
 *  - the planner agent writes the blueprint (Pipelines.skeleton + its JSON), then the [Orchestrator] runs every node
 *    as its own agent (Session.runNode: a specialist tool loop with the node's tier — planners on Inferno, coders and
 *    writers on Flare, searches on Spark, unless the user picked a tier), up to [BgAgents.cap] at once;
 *  - builds and tests go through Blaze Terminal (the terminal-first route), each in the run's own project folder;
 *  - the run's state is saved after every node (filesDir/orchestra/<id>.json), so a run cut by Blaze closing picks up
 *    where it was (BgAgents re-queues it with resume = true and the finished nodes are not redone);
 *  - every node event is one step on the run's work pill ("Blueprint · 14 steps · 3m 2s ›").
 *
 * The result (a document card, a gallery, the build summary) is posted in the chat as the agent's reply.
 */
object OrchestraRuns {
    const val SPECIALIST = "orchestrator"
    const val TOOL = "orchestrate"

    private lateinit var dir: File
    fun init(ctx: Context) { dir = File(ctx.applicationContext.filesDir, "orchestra").apply { mkdirs() } }
    private fun file(id: String): File? = if (::dir.isInitialized) File(dir, "$id.json") else null

    /** The task an orchestrate call is saved as (BgAgents.Bg.task). Pure. */
    fun encode(goal: String, pipeline: String, inputs: String): String =
        JSONObject().put("goal", goal.trim()).put("pipeline", pipeline.trim()).put("inputs", inputs.trim()).toString()

    data class Ask(val goal: String, val shape: Pipelines.Shape, val inputs: String)

    fun decode(task: String): Ask {
        val o = try { JSONObject(task) } catch (_: Exception) { JSONObject().put("goal", task) }
        val goal = o.optString("goal").ifBlank { task }
        return Ask(goal, Pipelines.Shape.of(o.optString("pipeline")) ?: Pipelines.pick(goal), o.optString("inputs"))
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", TOOL)
        .put("description", "Run a big multi-part job as a blueprint in the background and keep chatting: a planner writes ONE plan, then many agents work " +
            "in parallel (each part starts the moment the parts it needs are ready), checked and stitched at the end. Use it for: building an app / APK " +
            "(pipeline apk), deep research with a structured, fact-checked document (research), finding and curating songs / films / books / media with " +
            "artwork (media), writing a long document (document), or any job that splits into independent parts (generic). Don't use it for quick things. " +
            "The result (a document card, a gallery, a build summary) lands in the chat on its own; don't wait or poll.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("goal", JSONObject().put("type", "string").put("description", "The whole job, self-contained (the agents don't see this chat)"))
            .put("pipeline", JSONObject().put("type", "string").put("enum", JSONArray(Pipelines.Shape.values().map { it.id })))
            .put("inputs", JSONObject().put("type", "string").put("description", "Details the user gave (names, limits, style), optional"))
            .put("title", JSONObject().put("type", "string").put("description", "2-5 word pill title")))
            .put("required", JSONArray(listOf("goal")))))

    /** Runs blueprint [bg] to the end; returns the agent's reply (Markdown with its rich block). Throws on cancel. */
    suspend fun run(bg: BgAgents.Bg, step: (String, Int, List<String>) -> Unit): Pair<Boolean, String> {
        val ask = decode(bg.task)
        val root = "/root/phone/Blaze/blueprints/${bg.id.lowercase()}"
        val saved = file(bg.id)?.takeIf { it.exists() }?.let { f -> try { BlueprintStore.restore(JSONObject(withContext(Dispatchers.IO) { f.readText() })) } catch (_: Exception) { null } }
        val store = saved ?: BlueprintStore(Pipelines.skeleton(ask.shape, ask.goal, bg.id, ask.inputs)).also { it.note("meta/root", root) }
        val log = ArrayList<String>()
        var count = 0
        store.onEvent = { e ->
            val line = when (e) {
                is BpEvent.Started -> { count++; (store.node(e.node)?.let { pillLine(it) } ?: e.node) + if (e.attempt > 1) " (try ${e.attempt})" else "" }
                is BpEvent.Step -> e.text
                is BpEvent.Escalated -> "Sending a fixer for ${store.node(e.node)?.title ?: e.node}"
                is BpEvent.Failed -> if (e.final) "${store.node(e.node)?.title ?: e.node} couldn't finish" else null
                else -> null
            }
            if (line != null) synchronized(log) { log += line.take(160); if (log.size > BgAgents.LOG_MAX) log.removeAt(0); step(line, count, log.toList()) }
        }
        val session = Agent.agentSession(bg)
        val ops = object : Pipelines.Ops {
            override suspend fun agent(ctx: NodeContext, prompt: String): String {
                val fam = ModelChoice.wire(ModelChoice.forRole(ctx.node.role)) ?: ""
                val where = if (ctx.node.kind == "file" || ctx.node.kind == "fix") "\nProject folder: $root. Write files with file_write at $root/<path>." else ""
                return session.runNode(ctx.node.role, prompt + where, fam)
            }
            override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
                exec0("mkdir -p '" + (root + "/" + path).replace("'", "") + "'").first == 0
            }
            override suspend fun exec(command: String): Pair<Int, String> = exec0("cd '$root' && $command", 900)
            override suspend fun saveDoc(title: String, markdown: String): String {
                val r = session.runNode("", "", "", saveDoc = markdown to title)
                return com.blaze.assistant.agent.SaveFile.pathIn(r) ?: r.take(200)
            }
        }
        val cfg = Orchestrator.Config(maxParallel = try { BgAgents.cap() } catch (_: Throwable) { 2 })
        val outcome = Orchestrator(store, Pipelines.runner(ops), cfg, AgentBudget.escalation(root), save = { s ->
            file(bg.id)?.let { f -> try { f.writeText(s.snapshot().toString()) } catch (_: Exception) { } }
        }).run()
        return outcome.ok to result(store, outcome)
    }

    /** Blaze Terminal (the terminal-first route): (exit code, output). */
    private suspend fun exec0(cmd: String, timeoutSec: Int = 120): Pair<Int, String> {
        val out = com.blaze.assistant.terminal.Terminal.run("$cmd; echo \"__BLAZE_EXIT=\$?\"", timeoutSec, linux = true)
        val code = Regex("__BLAZE_EXIT=(\\d+)").findAll(out).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return code to out.replace(Regex("__BLAZE_EXIT=\\d+\\s*$"), "").trimEnd()
    }

    /** A node as one plain pill step ("Searching: solar costs", "Writing Repo.kt"). Pure. */
    fun pillLine(n: Node): String = when (n.kind) {
        "plan" -> "Planning the blueprint"
        "folder" -> "Making ${n.title}"
        "file" -> "Writing ${n.title}"
        "search" -> "Searching: ${n.title}"
        "sort" -> "Sorting the findings"
        "review" -> n.title
        "stitch" -> "Writing it up"
        "export" -> "Saving the document"
        "rank" -> "Ranking the results"
        "curate" -> "Laying out the list"
        "build" -> "Building in the terminal"
        "verify" -> "Running the tests"
        "fix" -> n.title
        "section" -> "Writing ${n.title}"
        else -> n.title
    }

    /** The agent's reply for a finished run (pure: the rich block first, then one line on what failed). */
    fun result(store: BlueprintStore, o: Orchestrator.Outcome): String {
        val shape = Pipelines.Shape.of(store.pipeline) ?: Pipelines.Shape.GENERIC
        val main = when (shape) {
            Pipelines.Shape.RESEARCH, Pipelines.Shape.DOCUMENT -> listOfNotNull(store.output("export"), store.output("stitch")?.let { summaryOf(it) }).joinToString("\n\n")
            Pipelines.Shape.MEDIA -> store.output("curate").orEmpty()
            Pipelines.Shape.APK -> {
                val files = store.all().count { it.kind == "file" && store.state(it.id) == NodeState.DONE }
                val built = store.state("build") == NodeState.DONE
                "**${store.goal.take(80)}**\n\n" + "- $files files written in ${store.notes("meta/root").values.firstOrNull().orEmpty()}\n" +
                    "- Build: " + (if (built) "succeeded" else "didn't succeed") + "\n- Tests: " + (if (store.state("verify") == NodeState.DONE) "passed" else "not passed")
            }
            Pipelines.Shape.GENERIC -> store.output("stitch").orEmpty()
        }
        val bad = (o.failed + o.cancelled).mapNotNull { store.node(it)?.title }.take(4)
        return (main.ifBlank { "The blueprint run didn't produce a result." } + if (bad.isNotEmpty()) "\n\nNot finished: " + bad.joinToString(", ") else "").trim()
    }

    /** The document's title and summary lines (the full text is in the doc card). */
    private fun summaryOf(md: String): String = md.lines().filter { it.isNotBlank() }.take(4).joinToString("\n")

    fun forget(id: String) { file(id)?.delete() }
}
