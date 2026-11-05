package com.argus.assistant.orchestra

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.52.0: pipeline shapes for the [Orchestrator]. The planner picks a shape for the goal ([pick], or the model names
 * it), [skeleton] lays out the first nodes, and [runner] turns each node into work plus the children it adds:
 *
 *  - APK (showcase): plan → one folder node per module/folder → (as soon as a folder exists) one coder per file, each
 *    starting when the files it imports are written → build in the terminal → fix agents on errors → verify.
 *  - RESEARCH: plan → parallel searches (each saves its findings to the shared store) → sorter orders the facts and
 *    picks the claims that matter → parallel reviewers verify each claim → stitcher writes the document → export
 *    (Markdown / PDF / doc card).
 *  - MEDIA (find songs / films / books…): plan → parallel source searches → dedupe + rank → curated gallery with artwork.
 *  - DOCUMENT: outline → sections written in parallel → stitch → export.
 *  - GENERIC: plan → tasks (with their own deps) → stitch.
 *
 * The work behind each node goes through [Ops] (an LLM agent with tools, the terminal, a doc writer in the app; fakes
 * in the tests), so the shapes are pure. PipelinesTest.
 */
object Pipelines {
    enum class Shape(val id: String) { APK("apk"), RESEARCH("research"), MEDIA("media"), DOCUMENT("document"), GENERIC("generic");
        companion object { fun of(id: String?): Shape? = values().firstOrNull { it.id.equals(id?.trim(), true) } }
    }

    private val apkRe = Regex("(?i)\\b(apk|android app|build (me )?an? app|make (me )?an? app|kotlin app|mobile app)\\b")
    private val mediaRe = Regex("(?i)\\b(songs?|tracks?|music|playlists?|albums?|artists?|films?|movies?|shows?|series|books?|podcasts?|games?|wallpapers?|beats?)\\b")
    private val researchRe = Regex("(?i)\\b(research|deep dive|investigate|report on|analy[sz]e|analysis|compare .+ (market|industry)|literature|state of|white ?paper|everything about)\\b")
    private val docRe = Regex("(?i)\\b(write|draft|compose)\\b.*\\b(document|essay|article|guide|proposal|plan|ebook|manual|chapter|thesis|report)\\b")

    fun pick(goal: String): Shape = when {
        apkRe.containsMatchIn(goal) -> Shape.APK
        researchRe.containsMatchIn(goal) -> Shape.RESEARCH
        docRe.containsMatchIn(goal) -> Shape.DOCUMENT
        mediaRe.containsMatchIn(goal) && Regex("(?i)\\b(find|recommend|suggest|discover|list|curate|best|top|new)\\b").containsMatchIn(goal) -> Shape.MEDIA
        else -> Shape.GENERIC
    }

    /** The first nodes of a run. Everything else is added by the nodes themselves as they finish. */
    fun skeleton(shape: Shape, goal: String, id: String = "bp" + Integer.toHexString(goal.hashCode()), inputs: String = ""): Blueprint {
        val g = goal.trim()
        val ctx = if (inputs.isBlank()) "" else "\nInputs from the user: $inputs"
        val nodes = when (shape) {
            Shape.APK -> listOf(
                Node("plan", "plan", "Blueprint", "planner", "Plan the Android app for: $g$ctx\n$APK_PLAN"),
                Node("build", "build", "Build", "coder", "Build the project in the terminal and report errors.", deps = listOf("plan"), terminal = true,
                    data = "./gradlew --no-daemon assembleDebug"),
                Node("verify", "verify", "Verify", "reviewer", "Check the built app against the goal: $g. Run the unit tests.", deps = listOf("build"), terminal = true,
                    data = "./gradlew --no-daemon testDebugUnitTest"),
            )
            Shape.RESEARCH -> listOf(
                Node("plan", "plan", "Research plan", "planner", "Plan web research for: $g$ctx\n$RESEARCH_PLAN"),
                Node("sort", "sort", "Sort findings", "planner", "Order the findings into an outline and pick the claims that need checking.\n$SORT_PLAN", deps = listOf("plan")),
                Node("stitch", "stitch", "Write the document", "writer", "Write the finished research document for: $g", deps = listOf("sort")),
                Node("export", "export", "Export", "writer", "Save the document.", deps = listOf("stitch")),
            )
            Shape.MEDIA -> listOf(
                Node("plan", "plan", "Pick sources", "planner", "Pick 3-5 different sources to search for: $g$ctx\n$MEDIA_PLAN"),
                Node("rank", "rank", "Dedupe and rank", "curator", "Merge the results, drop duplicates, rank the best 8-15 for: $g\n$RANK_FORMAT", deps = listOf("plan")),
                Node("curate", "curate", "Curated list", "curator", "Present the ranked list.", deps = listOf("rank")),
            )
            Shape.DOCUMENT -> listOf(
                Node("plan", "plan", "Outline", "planner", "Outline the document: $g$ctx\n$DOC_PLAN"),
                Node("stitch", "stitch", "Assemble", "writer", "Assemble the sections into one polished document for: $g", deps = listOf("plan")),
                Node("export", "export", "Export", "writer", "Save the document.", deps = listOf("stitch")),
            )
            Shape.GENERIC -> listOf(
                Node("plan", "plan", "Plan", "planner", "Split this into parallel tasks: $g$ctx\n$GENERIC_PLAN"),
                Node("stitch", "stitch", "Combine", "writer", "Combine the task results into the final answer for: $g", deps = listOf("plan")),
            )
        }
        return Blueprint(id, g, shape.id, nodes)
    }

    // ---- planner formats (asked for in the node briefs) ----
    const val APK_PLAN = "Return ONE JSON object only: {\"app\":\"name\",\"package\":\"com.example.app\",\"contracts\":{\"Name\":\"the interface / data class signatures other files rely on\"}," +
        "\"folders\":[{\"path\":\"app/src/main/java/com/example/app/data\",\"title\":\"Data layer\",\"files\":[{\"path\":\"app/src/main/java/com/example/app/data/Repo.kt\"," +
        "\"contract\":\"what this file exposes\",\"deps\":[\"paths of files it imports\"]}]}]}. Include the Gradle files and the manifest as files of the root folder \".\". " +
        "Every file belongs to exactly one folder; deps only name files in the plan."
    const val RESEARCH_PLAN = "Return ONE JSON object only: {\"queries\":[\"4-8 distinct web searches that together cover the question\"]}."
    const val SORT_PLAN = "Return ONE JSON object only: {\"outline\":[\"section titles in reading order\"],\"claims\":[\"the 2-6 most important factual claims that must be verified\"]}."
    const val MEDIA_PLAN = "Return ONE JSON object only: {\"sources\":[{\"name\":\"source\",\"query\":\"what to search there\"}]}."
    const val RANK_FORMAT = "Return ONE JSON object only: {\"title\":\"..\",\"items\":[{\"title\":\"..\",\"subtitle\":\"artist / year / author\",\"image\":\"https artwork url or empty\",\"url\":\"link\",\"why\":\"one line\"}]}. Use only real urls from the findings."
    const val DOC_PLAN = "Return ONE JSON object only: {\"title\":\"..\",\"sections\":[{\"title\":\"..\",\"brief\":\"what it covers\"}]}."
    const val GENERIC_PLAN = "Return ONE JSON object only: {\"tasks\":[{\"id\":\"t1\",\"title\":\"..\",\"role\":\"researcher|writer|coder|planner\",\"brief\":\"self-contained\",\"deps\":[\"ids of tasks it needs\"]}]}. Make independent tasks so they run in parallel."

    /** The work behind the nodes. */
    interface Ops {
        /** Runs [prompt] as an agent with tools for [ctx]'s role; returns its final text. */
        suspend fun agent(ctx: NodeContext, prompt: String): String
        /** Creates a folder (in the project root of the run). */
        suspend fun mkdir(path: String): Boolean = true
        /** Runs a shell command in the run's project folder (Blaze Terminal); (exit code, output). */
        suspend fun exec(command: String): Pair<Int, String> = 0 to ""
        /** Saves the finished document; returns where (a path the doc card opens). */
        suspend fun saveDoc(title: String, markdown: String): String = ""
    }

    /** The first JSON object in [text] (a fenced block or bare), or null. */
    fun jsonIn(text: String): JSONObject? {
        val fence = Regex("```(?:json)?\\s*\\n([\\s\\S]*?)```").find(text)?.groupValues?.get(1)
        for (cand in listOfNotNull(fence, text)) {
            val i = cand.indexOf('{'); val j = cand.lastIndexOf('}')
            if (i >= 0 && j > i) try { return JSONObject(cand.substring(i, j + 1)) } catch (_: Exception) { }
        }
        return null
    }

    private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).mapNotNull { i -> it.opt(i)?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } } } ?: emptyList()

    /** The prompt a node agent gets: its brief, the contracts, its inputs, and the last error on a retry. */
    fun prompt(ctx: NodeContext, extra: String = ""): String = buildString {
        val n = ctx.node
        append("[Part of a larger job: ").append(ctx.store.goal.take(300)).append("]\n")
        append("Your part (").append(n.kind).append("): ").append(n.title).append('\n').append(n.brief).append('\n')
        if (n.path.isNotEmpty()) append("You own: ").append(n.path).append(" (write only this path; other agents own the other files)\n")
        if (n.contract.isNotEmpty()) append("Contract you must honour: ").append(n.contract).append('\n')
        val contracts = ctx.store.contracts()
        if (contracts.isNotEmpty()) append("Shared contracts (other files rely on these exactly):\n").append(contracts.entries.joinToString("\n") { "- ${it.key}: ${it.value.take(600)}" }).append('\n')
        if (ctx.inputs.isNotEmpty()) append("What the parts before you produced:\n").append(ctx.inputs.entries.joinToString("\n\n") { "## ${it.key}\n${it.value.take(4000)}" }).append('\n')
        val steer = ctx.store.notes("steer/")
        if (steer.isNotEmpty()) append("The user added while the job runs (follow it):\n").append(steer.values.joinToString("\n") { "- $it" }).append('\n')
        if (extra.isNotEmpty()) append(extra).append('\n')
        if (ctx.lastError.isNotEmpty()) append("Your previous attempt failed: ").append(ctx.lastError.take(1500)).append("\nFix that this time.\n")
    }

    /** Runs a node through [ops] and returns its result with the children it adds. */
    fun runner(ops: Ops): suspend (NodeContext) -> NodeResult = { ctx -> run(ops, ctx) }

    suspend fun run(ops: Ops, ctx: NodeContext): NodeResult {
        val n = ctx.node
        val shape = Shape.of(ctx.store.pipeline) ?: Shape.GENERIC
        return when (n.kind) {
            "plan" -> plan(shape, ops, ctx)
            "folder" -> {
                ctx.report("Creating ${n.path}")
                if (!ops.mkdir(n.path)) return NodeResult.fail("couldn't create ${n.path}")
                NodeResult.ok("created ${n.path}", children = filesOf(n))
            }
            "build", "verify" -> {
                ctx.report(if (n.kind == "build") "Building" else "Running the tests")
                val (code, out) = ops.exec(n.data.ifBlank { "./gradlew --no-daemon assembleDebug" })
                if (code == 0) NodeResult.ok(out.takeLast(2000)) else NodeResult.fail("exit $code\n" + out.takeLast(3000))
            }
            "search" -> {
                ctx.report("Searching: ${n.brief.take(60)}")
                val out = ops.agent(ctx, prompt(ctx, "Search the web for this, open the best 2-3 sources and write the findings as short bullet points, each with its source url."))
                if (out.isBlank()) NodeResult.fail("no findings") else NodeResult.ok(out, notes = mapOf("finding/${n.id}" to out))
            }
            "sort" -> {
                val findings = ctx.store.notes("finding/")
                val out = ops.agent(ctx, prompt(ctx, "Findings from the searches:\n" + findings.values.joinToString("\n\n") { it.take(3000) }))
                val o = jsonIn(out) ?: return NodeResult.fail("the sorter didn't return the outline JSON")
                val claims = strings(o.optJSONArray("claims")).take(6)
                NodeResult.ok(o.toString(), children = claims.mapIndexed { i, c ->
                    Node("review:$i", "review", "Check: ${c.take(50)}", "reviewer", c)
                })
            }
            "review" -> {
                ctx.report("Checking a claim")
                val out = ops.agent(ctx, prompt(ctx, "Verify this claim against independent sources. Answer with VERIFIED, DISPUTED or UNCLEAR on the first line, then one line why with a source url."))
                NodeResult.ok(out.ifBlank { "UNCLEAR\nno answer" }, notes = mapOf("review/${n.id}" to out))
            }
            "stitch" -> {
                val extra = buildString {
                    val f = ctx.store.notes("finding/"); if (f.isNotEmpty()) append("All findings:\n").append(f.values.joinToString("\n\n") { it.take(2500) }).append('\n')
                    val r = ctx.store.notes("review/"); if (r.isNotEmpty()) append("Claim checks (drop or flag DISPUTED claims):\n").append(r.values.joinToString("\n") { it.take(400) }).append('\n')
                    append("Write it as a well-structured Markdown document: a title, a 3-line summary, headed sections, tables or ```chart blocks where numbers allow, and a numbered Sources list.")
                }
                val out = ops.agent(ctx, prompt(ctx, extra))
                if (out.isBlank()) NodeResult.fail("empty document") else NodeResult.ok(out)
            }
            "export" -> {
                val doc = ctx.inputs.values.lastOrNull().orEmpty()
                val title = Regex("(?m)^#\\s+(.+)$").find(doc)?.groupValues?.get(1)?.trim() ?: ctx.store.goal.take(60)
                val path = ops.saveDoc(title, doc)
                NodeResult.ok(DocCard.block(title, path, doc))
            }
            "rank" -> {
                val out = ops.agent(ctx, prompt(ctx, RANK_FORMAT))
                val o = jsonIn(out) ?: return NodeResult.fail("the ranker didn't return the list JSON")
                NodeResult.ok(o.toString())
            }
            "curate" -> {
                val o = jsonIn(ctx.inputs.values.lastOrNull().orEmpty()) ?: return NodeResult.fail("nothing to curate")
                NodeResult.ok(gallery(o))
            }
            else -> {
                val out = ops.agent(ctx, prompt(ctx))
                if (out.isBlank()) NodeResult.fail("no result") else NodeResult.ok(out)
            }
        }
    }

    private suspend fun plan(shape: Shape, ops: Ops, ctx: NodeContext): NodeResult {
        val out = ops.agent(ctx, prompt(ctx))
        val o = jsonIn(out) ?: return NodeResult.fail("the planner didn't return the plan JSON")
        return when (shape) {
            Shape.APK -> {
                o.optJSONObject("contracts")?.let { c -> c.keys().forEach { k -> ctx.store.putContract(k, c.optString(k)) } }
                val folders = o.optJSONArray("folders") ?: return NodeResult.fail("plan has no folders")
                val kids = (0 until folders.length()).mapNotNull { folders.optJSONObject(it) }.map { f ->
                    val p = f.optString("path").trim().ifEmpty { "." }
                    Node("dir:$p", "folder", f.optString("title").ifBlank { p }, "coder", "Create the folder $p.", path = p,
                        data = (f.optJSONArray("files") ?: JSONArray()).toString())
                }
                if (kids.isEmpty()) NodeResult.fail("plan has no folders") else NodeResult.ok(o.toString(), kids)
            }
            Shape.RESEARCH -> {
                val q = strings(o.optJSONArray("queries")).take(8)
                if (q.isEmpty()) NodeResult.fail("no queries") else NodeResult.ok(o.toString(), q.mapIndexed { i, s -> Node("search:$i", "search", s.take(60), "researcher", s) })
            }
            Shape.MEDIA -> {
                val a = o.optJSONArray("sources") ?: JSONArray()
                val kids = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.take(6).mapIndexed { i, s ->
                    Node("search:$i", "search", s.optString("name").ifBlank { "Source ${i + 1}" }, "researcher",
                        "Search ${s.optString("name")} for: ${s.optString("query")}. List each item with title, artist/author, year, artwork image url and link.")
                }
                if (kids.isEmpty()) NodeResult.fail("no sources") else NodeResult.ok(o.toString(), kids)
            }
            Shape.DOCUMENT -> {
                val a = o.optJSONArray("sections") ?: JSONArray()
                val kids = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.take(12).mapIndexed { i, s ->
                    Node("section:$i", "section", s.optString("title").ifBlank { "Section ${i + 1}" }, "writer",
                        "Write the section \"${s.optString("title")}\": ${s.optString("brief")}. Markdown, starting with its ## heading.")
                }
                if (kids.isEmpty()) NodeResult.fail("no sections") else NodeResult.ok(o.toString(), kids)
            }
            Shape.GENERIC -> {
                val a = o.optJSONArray("tasks") ?: JSONArray()
                val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.take(10)
                val ids = list.mapIndexed { i, t -> t.optString("id").ifBlank { "t$i" } }
                val kids = list.mapIndexed { i, t ->
                    Node("task:${ids[i]}", "task", t.optString("title").ifBlank { ids[i] }, t.optString("role"), t.optString("brief"),
                        deps = strings(t.optJSONArray("deps")).filter { it in ids && it != ids[i] }.map { "task:$it" })
                }
                if (kids.isEmpty()) NodeResult.fail("no tasks") else NodeResult.ok(o.toString(), kids)
            }
        }
    }

    /** A folder's file nodes: each a coder that owns one path and waits only for the files it imports. */
    fun filesOf(folder: Node): List<Node> {
        val a = try { JSONArray(folder.data.ifBlank { "[]" }) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { f ->
            val p = f.optString("path").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Node("file:$p", "file", p.substringAfterLast('/'), "coder",
                "Write the complete file $p (no placeholders). Save it with file_write.", path = p, writes = listOf(p),
                contract = f.optString("contract"), deps = strings(f.optJSONArray("deps")).filter { it != p }.map { "file:$it" })
        }
    }

    /** The ranked list as a ```gallery reply block (song list with artwork, films with posters…). */
    fun gallery(o: JSONObject): String {
        val items = o.optJSONArray("items") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until minOf(items.length(), 24)) {
            val it = items.optJSONObject(i) ?: continue
            out.put(JSONObject().put("title", it.optString("title")).put("subtitle", it.optString("subtitle"))
                .put("image", it.optString("image")).put("url", it.optString("url")).put("note", it.optString("why")))
        }
        return "```gallery\n" + JSONObject().put("title", o.optString("title")).put("items", out).toString() + "\n```"
    }
}

/** The ```doc reply block for a produced document (open / share card). */
object DocCard {
    fun block(title: String, path: String, markdown: String): String {
        val words = markdown.split(Regex("\\s+")).count { it.isNotBlank() }
        val sections = Regex("(?m)^##\\s+").findAll(markdown).count()
        return "```doc\n" + JSONObject().put("title", title).put("path", path).put("format", path.substringAfterLast('.', "md"))
            .put("summary", "$sections sections \u00b7 $words words").toString() + "\n```"
    }
}

/** 0.52.0: how many agents may work at once on this phone, and the default escalation (fix agents). Pure; PipelinesTest. */
object AgentBudget {
    /** 4 on 6 GB+ phones, 3 on 4 GB+, 2 below; 1 when Android flags the phone as low-RAM. */
    fun cap(totalMemBytes: Long, lowRamDevice: Boolean = false): Int = when {
        lowRamDevice -> 1
        totalMemBytes >= 5_200L * 1024 * 1024 -> 4
        totalMemBytes >= 3_600L * 1024 * 1024 -> 3
        else -> 2
    }

    private val pathRe = Regex("""(?:file://)?((?:/|[A-Za-z0-9_.-]+/)[A-Za-z0-9_./-]+\.(?:kt|java|xml|kts|gradle|json|md|py|js|ts))(?::\d+)?""")

    /** Files named in a build / test error (the fix agent's write set). */
    fun pathsIn(error: String, root: String = ""): List<String> = pathRe.findAll(error).map { it.groupValues[1] }
        .map { if (root.isNotEmpty() && it.startsWith(root)) it.removePrefix(root).trimStart('/') else it }.distinct().take(8).toList()

    /** Build / verify failures get one fix agent with the errors; other nodes fail plainly (their dependents are cancelled). */
    fun escalation(root: String = ""): suspend (Node, String) -> Escalation = { n, err ->
        if (n.kind == "build" || n.kind == "verify" || n.kind == "file") {
            val paths = pathsIn(err, root).ifEmpty { if (n.kind == "file") listOf(n.path) else emptyList() }
            Escalation.Fix(listOf(Node("fix:${n.id}", "fix", "Fix ${if (n.kind == "file") n.title else "build errors"}", "coder",
                "Fix these errors so ${n.title} succeeds. Change only what's needed.\n" + err.take(3000), writes = paths)))
        } else Escalation.Fail
    }
}
