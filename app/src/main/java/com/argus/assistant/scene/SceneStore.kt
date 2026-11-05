package com.argus.assistant.scene

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Spatial canvas, stage 1: the store behind the `scene` tool and the `spatial_canvas` feature flag (off by default).
 *
 * It owns the scene graph ([Scene]) on disk (`files/home/scenes/<slug>/scene.json` + `current.json`) and hands the
 * rendering to the Design view, which is the existing renderer: [Scene.spec] becomes the page's blaze-parts block and
 * `Designs.run` writes/updates `~/designs/<slug>/index.html`, so three.js, its WebGL fallback and the native drawing
 * all work unchanged, and the user can still orbit, pinch, exit and hand-edit in Design.
 *
 * Nothing here runs unless the flag is on, so chat behaviour, prompt size and frames are exactly as before for
 * everyone else. The Android-facing calls (Settings, Terminal.home, Designs) are only touched through lazy helpers,
 * so the whole store is unit-tested on the JVM with a temp root and a stubbed publisher (SceneTest).
 */
object SceneStore {
    const val TOOL = "scene"
    private const val POINTER = "current.json"
    private const val SCENE_FILE = "scene.json"
    private const val DESIGN_POINTER = "design.txt"

    /** Tests and the terminal set these; the app leaves them null. */
    @Volatile var rootOverride: File? = null
    @Volatile var flagOverride: (() -> Boolean)? = null
    /** Stage 1 hook: the Design view by default; tests pass a stub so no HTML is written. */
    @Volatile var publisher: ((Scene) -> Published)? = null

    /** What publishing produced: the design file the Design view should show, or the reason it couldn't. */
    data class Published(val path: String? = null, val note: String = "")

    data class Result(val text: String, val ok: Boolean, val path: String? = null)

    /** The feature flag: off unless `spatial_canvas` is set (Settings › Advanced, or adb). */
    val enabled: Boolean
        get() = flagOverride?.invoke() ?: try {
            com.argus.assistant.agent.Settings.bool(Scene.SETTING, false)
        } catch (_: Throwable) { false }

    fun root(): File = (rootOverride ?: File(com.argus.assistant.terminal.Terminal.home(), "scenes")).apply { mkdirs() }

    private fun pointer(): File = File(root(), POINTER)

    data class Current(val slug: String, val title: String, val revision: Int, val at: Long, val path: String)

    fun current(): Current? = try {
        pointer().takeIf { it.isFile }?.let { p ->
            val o = JSONObject(p.readText())
            val f = File(o.optString("path"))
            if (f.isFile) Current(o.optString("slug"), o.optString("title"), o.optInt("revision", 1), o.optLong("at", 0L), f.absolutePath) else null
        }
    } catch (_: Exception) { null }

    fun load(): Scene? = try { current()?.let { File(it.path) }?.takeIf { it.isFile }?.readText()?.let { Scene.fromJson(it) } } catch (_: Exception) { null }

    // ---------------- actions ----------------

    fun apply(args: JSONObject, now: Long = System.currentTimeMillis()): Result = try {
        when (args.optString("action").ifBlank { "update" }.lowercase()) {
            "create" -> create(args, now)
            "update", "edit" -> update(args, now)
            "read" -> load()?.let { Result(Scene.sceneJson(it).toString(2), true) }
                ?: Result("Error: there's no scene yet. Use action=create.", false)
            "open" -> open()
            else -> Result("Error: unknown action \u201c${args.optString("action")}\u201d; use create, update, read or open.", false)
        }
    } catch (e: Exception) {
        Result("Error: ${e.message ?: e.javaClass.simpleName}", false)
    }

    private fun create(args: JSONObject, now: Long): Result {
        val input = JSONObject()
            .put("title", args.optString("title").ifBlank { "Scene" })
            .put("units", args.optString("units", "m"))
            .put("nodes", args.optJSONArray("nodes") ?: args.optJSONArray("parts") ?: JSONArray())
        val (scene, problems) = Scene.normalize(input)
        if (scene.empty) return Result("Error: a scene needs at least one node (id, shape, position, size).", false)
        val slug = uniqueSlug(Scene.slug(scene.title))
        save(scene, slug, now)
        val published = publish(scene)
        return Result(resultText("Created", scene, published, problems), true, published.path)
    }

    private fun update(args: JSONObject, now: Long): Result {
        val cur = current() ?: return Result("Error: there's no scene yet. Use action=create first.", false)
        val base = Scene.fromJson(File(cur.path).readText())
            ?: return Result("Error: the current scene file can't be read; create it again.", false)
        val problems = ArrayList<String>()
        var scene = base
        val all = args.optJSONArray("nodes")
        val set = args.optJSONArray("set_nodes") ?: args.optJSONArray("parts")
        val remove = args.optJSONArray("remove") ?: args.optJSONArray("remove_nodes")
        var summary = "no nodes changed"
        if (all != null) {
            val (s, pr) = Scene.normalize(JSONObject().put("title", scene.title).put("units", scene.units).put("nodes", all))
            problems += pr
            val (next, _) = Scene.upsert(scene, s.nodes, replace = true)
            scene = next; summary = if (all.length() == 0) "scene emptied" else "replaced all nodes"
        } else if (set != null && set.length() > 0) {
            val parsed = ArrayList<Scene.Node>()
            for (i in 0 until set.length()) set.optJSONObject(i)?.let { parsed += Scene.node(it, "node-${i + 1}") }
            val (next, sum) = Scene.upsert(scene, parsed)
            scene = next; summary = sum
        }
        if (remove != null) {
            val ids = (0 until remove.length()).map { remove.optString(it) }.filter { it.isNotBlank() }
            val (next, sum) = Scene.remove(scene, ids)
            scene = next
            summary = if (summary == "no nodes changed") sum else "$summary; $sum"
        }
        if (args.optString("title").isNotBlank()) scene = scene.copy(title = args.optString("title").take(60))
        if (args.optString("units").lowercase() in Scene.UNITS) scene = scene.copy(units = args.optString("units").lowercase())
        if (scene == base) return Result("Error: that changed nothing (current nodes: ${base.nodes.joinToString(", ") { it.id }}).", false)
        val slug = cur.slug
        save(scene, slug, now)
        val published = publish(scene)
        return Result(resultText("Updated", scene, published, problems).replaceFirst("Updated", "Updated ($summary)"), true, published.path)
    }

    private fun open(): Result {
        val scene = load() ?: return Result("Error: there's no scene yet. Use action=create.", false)
        val published = publish(scene)
        return if (published.path != null) Result("Opened \u201c${scene.title}\u201d (${scene.nodes.size} nodes) in Design. The user can orbit with one finger and zoom with two.", true, published.path)
        else Result("Error: the scene is saved but Design couldn't draw it: ${published.note.ifBlank { "the 3D starter isn't available" }}", false)
    }

    private fun resultText(verb: String, scene: Scene, published: Published, problems: List<String>): String {
        val where = published.path?.let { " and showed it in Design ($it)" }
            ?: published.note.takeIf { it.isNotBlank() }?.let { ". It is saved but not drawn: $it" } ?: ""
        val cap = if (scene.nodes.size >= Scene.MAX_NODES) " The scene is at the ${Scene.MAX_NODES}-node cap." else ""
        val notes = problems.joinToString("") { "\nNote: $it" }
        return "$verb \u201c${scene.title}\u201d: ${scene.nodes.size} node(s)${where}.${cap}" +
            " Follow-on changes go through scene action=update (set_nodes / remove).$notes"
    }

    // ---------------- disk ----------------

    private fun uniqueSlug(base: String): String {
        var slug = base; var n = 2
        while (File(File(root(), slug), SCENE_FILE).isFile) slug = "$base-${n++}"
        return slug
    }

    private fun save(scene: Scene, slug: String, now: Long) {
        val dir = File(root(), slug).apply { mkdirs() }
        val f = File(dir, SCENE_FILE)
        f.writeText(Scene.sceneJson(scene).toString(2))
        pointer().writeText(JSONObject().put("slug", slug).put("title", scene.title)
            .put("revision", scene.revision).put("at", now).put("path", f.absolutePath).toString())
    }

    private fun designFile(): File? = try {
        File(root(), DESIGN_POINTER).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
    } catch (_: Exception) { null }

    private fun writeDesignPointer(f: File) {
        try { File(root(), DESIGN_POINTER).writeText(f.absolutePath) } catch (_: Exception) { }
    }

    /**
     * v0.52.1: publishes a scene the app built itself (the search globe, not the model's current scene) under its own
     * slug: its own scene file and its own design pointer, so it never overwrites what the user is editing.
     */
    fun publishOwn(slug: String, scene: Scene): Published {
        val dir = File(root(), slug).apply { mkdirs() }
        try { File(dir, SCENE_FILE).writeText(Scene.sceneJson(scene).toString(2)) } catch (_: Exception) { }
        val pointer = File(dir, DESIGN_POINTER)
        val existing = pointer.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
            ?.let { File(it) }?.takeIf { it.isFile }
        return publishInto(scene, existing, pointer)
    }

    /** Hands the scene to the first renderer: the Design view (three.js page + its existing fallbacks). */
    private fun publish(scene: Scene): Published = publishInto(scene, designFile(), null)

    /**
     * The one publish path: updates [existing] or creates the design, then remembers where it went — in the scene's own
     * [pointer] for a scene the app made, or in the root pointer for the current scene (null [pointer]).
     */
    private fun publishInto(scene: Scene, existing: File?, pointer: File?): Published {
        publisher?.let { return it(scene) }
        return try {
            val parts = Scene.parts(scene)
            val out = if (existing != null) {
                com.argus.assistant.agent.Designs.run(JSONObject().put("action", "update")
                    .put("path", existing.absolutePath).put("title", scene.title).put("parts", parts).put("units", scene.units))
            } else {
                com.argus.assistant.agent.Designs.run(JSONObject().put("action", "create").put("kind", "3d")
                    .put("title", scene.title).put("parts", parts).put("units", scene.units))
            }
            val open = out.open
            when {
                open != null -> {
                    val target = pointer ?: File(root(), DESIGN_POINTER)
                    try { target.writeText(open) } catch (_: Exception) { }
                    Published(open)
                }
                existing != null && out.text.startsWith("Error: that changed nothing") -> Published(existing.absolutePath, "unchanged")
                else -> Published(null, out.text.removePrefix("Error: ").take(200))
            }
        } catch (e: Exception) {
            Published(null, e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------- the tool ----------------

    private fun vec(description: String): JSONObject =
        JSONObject().put("type", "array").put("description", description)
            .put("items", JSONObject().put("type", "number")).put("minItems", 3).put("maxItems", 3)

    fun tool(): JSONObject {
        val node = JSONObject().put("type", "object")
            .put("description", "One primitive. box: size=[w,h,d]; sphere: diameter = size[0]; cylinder/cone/capsule: size[0] = diameter, size[1] = height; torus: size[0] = diameter; plane: size=[w,d]; group: no geometry, parents other nodes.")
            .put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string").put("description", "kebab-case, unique in the scene"))
                .put("shape", JSONObject().put("type", "string").put("enum", JSONArray(Scene.SHAPES)))
                .put("label", JSONObject().put("type", "string").put("description", "short human name"))
                .put("position", vec("centre in scene units, relative to the parent; y is up"))
                .put("rotation", vec("degrees, x/y/z"))
                .put("scale", vec("multiplier per axis, default 1"))
                .put("size", vec("width, height, depth in scene units"))
                .put("color", JSONObject().put("type", "string").put("description", "\"#RRGGBB\""))
                .put("material", JSONObject().put("type", "string").put("enum", JSONArray(Scene.MATERIALS)))
                .put("opacity", JSONObject().put("type", "number").put("description", "0.05–1"))
                .put("parent", JSONObject().put("type", "string").put("description", "id of a group node"))
                .put("visible", JSONObject().put("type", "boolean"))
                .put("animation", JSONObject().put("type", "object").put("description",
                    "optional motion: kind (spin | bob | pulse), axis (x | y | z), speed, amplitude")))
            .put("required", JSONArray(listOf("id")))
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
            .put("description", "The spatial canvas: a 3D scene that lives in ${com.argus.assistant.BrandName.NAME}'s Design view, kept as a scene graph so " +
                "follow-on edits are exact. create builds a new scene (nodes, units, title); update changes the CURRENT scene (set_nodes adds or changes " +
                "nodes by id, remove drops them and their children, nodes replaces everything); read returns the scene JSON; open shows it again. " +
                "Use it for anything the user should see in space (a model, a product, a room, a diagram as objects, a comparison side by side). " +
                "Keep it under ${Scene.MAX_NODES} nodes: real proportions, parts that should touch do, prefer a few well-placed primitives over many tiny ones. " +
                "Nothing is shown unless this tool succeeds: never say a scene was made or changed otherwise.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("create", "update", "read", "open"))))
                .put("title", JSONObject().put("type", "string").put("description", "create: short scene name"))
                .put("units", JSONObject().put("type", "string").put("enum", JSONArray(Scene.UNITS)).put("description", "default m (metres)"))
                .put("nodes", JSONObject().put("type", "array").put("items", node)
                    .put("description", "create: every node; update: the whole new node list"))
                .put("set_nodes", JSONObject().put("type", "array").put("items", node)
                    .put("description", "update: nodes to add or change, matched by id; only the keys you give change"))
                .put("remove", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                    .put("description", "update: node ids to delete")))
                .put("required", JSONArray(listOf("action")))))
    }

    /** The one-line step shown while the scene tool runs (same shape as the other tools' step text). */
    fun step(args: JSONObject): String = when (args.optString("action").lowercase()) {
        "create" -> "Building \u201c${args.optString("title").ifBlank { "the scene" }.take(40)}\u201d"
        "update", "edit" -> "Changing the scene"
        "read" -> "Reading the scene"
        "open" -> "Opening the scene"
        else -> "Working on the scene"
    }
}
