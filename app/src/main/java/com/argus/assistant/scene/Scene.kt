package com.argus.assistant.scene

import org.json.JSONArray
import org.json.JSONObject

/**
 * Spatial canvas, stage 1 (behind `Settings.bool("spatial_canvas", false)`): the scene graph the agent edits, in pure
 * Kotlin over org.json so every rule is JVM-tested (SceneTest) and nothing here touches Android.
 *
 * The graph is flat with optional parents (what the bundled three.js starter understands): each node is one primitive
 * with an id, a transform, a material and an optional parent. The agent talks to it in the Spatial DSL
 * ([SCHEMA_JSON]): create / set_nodes / remove / animate. [parts] bridges the graph to the Design view's blaze-parts
 * format, which is the first renderer (DesignServe, the bundled three.js and its native fallback), so this stage adds
 * no renderer of its own and no new frames to the chat.
 *
 * Deliberate choices for low-end phones: a hard [MAX_NODES] cap per scene, numbers clamped to sane ranges, unknown
 * shapes turned into boxes instead of null geometry, and a bounded [context] string so a big scene never crowds the
 * prompt.
 */
object Scene {
    /** The feature flag (off by default). */
    const val SETTING = "spatial_canvas"

    /** One scene = at most this many primitives (the Design view draws every node; low-RAM phones stay smooth). */
    const val MAX_NODES = 120
    /** How much of the scene graph may ride in a prompt. */
    const val MAX_CONTEXT_CHARS = 1_400

    val SHAPES = listOf("box", "sphere", "cylinder", "cone", "torus", "plane", "capsule", "group")
    val MATERIALS = listOf("matte", "metal", "glass", "plastic", "wood")
    val ANIMATIONS = listOf("spin", "bob", "pulse")
    val UNITS = listOf("mm", "cm", "m")

    /** Shape names models reach for, mapped to the primitives the starter builds. */
    private val ALIASES: Map<String, String> = mapOf(
        "cube" to "box", "cuboid" to "box", "block" to "box", "rect" to "box", "rectangle" to "box", "panel" to "box", "slab" to "box",
        "ball" to "sphere", "orb" to "sphere", "dome" to "sphere", "globe" to "sphere",
        "tube" to "cylinder", "pipe" to "cylinder", "rod" to "cylinder", "disc" to "cylinder", "disk" to "cylinder",
        "pyramid" to "cone", "nosecone" to "cone", "nose" to "cone",
        "ring" to "torus", "donut" to "torus", "doughnut" to "torus", "wheel" to "torus",
        "pill" to "capsule",
        "ground" to "plane", "floor" to "plane", "wall" to "plane",
    ) + SHAPES.associateWith { it }

    // ---------------- value types ----------------

    data class V3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
        companion object {
            val ZERO = V3(0f, 0f, 0f)
            val ONE = V3(1f, 1f, 1f)
        }
    }

    data class Material(val color: String = "#9AA0AB", val kind: String = "matte", val opacity: Float = 1f)

    /** [size] is the primitive's box in scene units: width, height, depth (sphere radius = x/2, cylinder radius = x/2). */
    data class Node(
        val id: String,
        val shape: String = "box",
        val label: String = "",
        val position: V3 = V3.ZERO,
        val rotation: V3 = V3.ZERO,
        val scale: V3 = V3.ONE,
        val size: V3 = V3.ONE,
        val material: Material = Material(),
        val parent: String? = null,
        val visible: Boolean = true,
        val animation: Animation? = null,
    )

    /** Drawn by the Design view (the starter applies it to the part) and kept for the native renderer later. */
    data class Animation(val kind: String = "spin", val axis: String = "y", val speed: Float = 0.4f, val amplitude: Float = 0f)

    data class Scene(
        val title: String = "Scene",
        val units: String = "m",
        val revision: Int = 1,
        val nodes: List<Node> = emptyList(),
    ) {
        val empty: Boolean get() = nodes.isEmpty()
    }

    // ---------------- small helpers ----------------

    fun slug(s: String): String =
        s.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "node" }

    /** #rgb, #rrggbb or #rrggbbaa, upper-cased; anything else falls back to [def]. */
    fun color(s: String, def: String = "#9AA0AB"): String {
        val t = s.trim()
        if (t.isEmpty()) return def
        return when {
            Regex("^#[0-9a-fA-F]{3}$").matches(t) ->
                "#" + t.drop(1).map { "$it$it" }.joinToString("").uppercase()
            Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$").matches(t) -> t.uppercase()
            Regex("^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$").matches(t) -> "#" + t.uppercase()
            else -> def
        }
    }

    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v.isNaN()) lo else v.coerceIn(lo, hi)

    fun shape(s: String): Pair<String, Boolean> {
        val raw = s.trim().lowercase().removePrefix("three.").replace(Regex("[^a-z]"), "")
        ALIASES[raw]?.let { return it to false }
        return "box" to s.isNotBlank()
    }

    // ---------------- JSON in / out ----------------

    private fun vec(a: JSONArray?, def: V3): V3 {
        if (a == null || a.length() < 2) return def
        return V3(a.optDouble(0, def.x.toDouble()).toFloat(), a.optDouble(1, def.y.toDouble()).toFloat(),
            if (a.length() > 2) a.optDouble(2, def.z.toDouble()).toFloat() else def.z)
    }

    private fun arr(v: V3): JSONArray = JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.z.toDouble())

    fun node(o: JSONObject, fallbackId: String = "node"): Node {
        val given = o.optString("shape").ifBlank { o.optString("type") }
        val (sh, _) = shape(given)
        val size = if (o.has("size")) vec(o.optJSONArray("size"), V3.ONE) else V3(
            o.optDouble("width", 1.0).toFloat(), o.optDouble("height", 1.0).toFloat(), o.optDouble("depth", 1.0).toFloat())
        val anim = o.optJSONObject("animation")?.let { a ->
            val kind = a.optString("kind").lowercase().let { if (it in ANIMATIONS) it else "spin" }
            Animation(kind, a.optString("axis").ifBlank { "y" }.take(1).lowercase().let { if (it in listOf("x", "y", "z")) it else "y" },
                clamp(a.optDouble("speed", 0.4).toFloat(), -4f, 4f), clamp(a.optDouble("amplitude", 0.0).toFloat(), 0f, 2f))
        }
        return Node(
            id = slug(o.optString("id").ifBlank { o.optString("label").ifBlank { fallbackId } }),
            shape = sh,
            label = o.optString("label").take(60),
            position = if (o.has("position") || o.has("at")) vec(o.optJSONArray("position") ?: o.optJSONArray("at"), V3.ZERO)
                else V3(o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(), o.optDouble("z", 0.0).toFloat()),
            rotation = vec(o.optJSONArray("rotation"), V3.ZERO),
            scale = vec(o.optJSONArray("scale"), V3.ONE).let { V3(clamp(it.x, 0.01f, 100f), clamp(it.y, 0.01f, 100f), clamp(it.z, 0.01f, 100f)) },
            size = V3(clamp(size.x, 0.001f, 10_000f), clamp(size.y, 0.001f, 10_000f), clamp(size.z, 0.001f, 10_000f)),
            material = Material(
                color(o.optString("color"), "#9AA0AB"),
                o.optString("material").lowercase().let { if (it in MATERIALS) it else "matte" },
                clamp(o.optDouble("opacity", 1.0).toFloat(), 0.05f, 1f)),
            parent = o.optString("parent").takeIf { it.isNotBlank() }?.let { slug(it) },
            visible = o.optBoolean("visible", true),
            animation = anim,
        )
    }

    fun nodeJson(n: Node): JSONObject {
        val o = JSONObject().put("id", n.id).put("shape", n.shape).put("label", n.label)
            .put("position", arr(n.position)).put("rotation", arr(n.rotation)).put("scale", arr(n.scale))
            .put("size", arr(n.size)).put("color", n.material.color).put("material", n.material.kind)
            .put("opacity", n.material.opacity.toDouble()).put("visible", n.visible)
        n.parent?.let { o.put("parent", it) }
        n.animation?.let { a ->
            o.put("animation", JSONObject().put("kind", a.kind).put("axis", a.axis)
                .put("speed", a.speed.toDouble()).put("amplitude", a.amplitude.toDouble()))
        }
        return o
    }

    fun sceneJson(s: Scene): JSONObject = JSONObject().put("title", s.title).put("units", s.units)
        .put("revision", s.revision).put("nodes", JSONArray(s.nodes.map { nodeJson(it) }))

    /** Parses a stored scene; null when the text isn't a scene. */
    fun fromJson(text: String): Scene? = try {
        val o = JSONObject(text)
        val (s, _) = normalize(o)
        s
    } catch (_: Exception) { null }

    /**
     * Reads a scene the model wrote: every node gets a unique id, a known shape, clamped numbers; unknown parents and
     * the overflow past [MAX_NODES] are dropped with a note instead of failing the whole edit.
     */
    fun normalize(o: JSONObject): Pair<Scene, List<String>> {
        val problems = ArrayList<String>()
        val title = o.optString("title").ifBlank { o.optString("name").ifBlank { "Scene" } }.take(60)
        val units = o.optString("units").lowercase().let { if (it in UNITS) it else "m" }
        val raw = o.optJSONArray("nodes") ?: o.optJSONArray("parts") ?: JSONArray()
        val out = ArrayList<Node>(); val seen = HashSet<String>(); var dropped = 0
        for (i in 0 until raw.length()) {
            val p = raw.optJSONObject(i) ?: continue
            if (out.size >= MAX_NODES) { dropped++; continue }
            val given = p.optString("shape").ifBlank { p.optString("type") }
            val (sh, guessed) = shape(given)
            if (guessed) problems += "node ${p.optString("id").ifBlank { "#${i + 1}" }}: unknown shape \u201c$given\u201d (use ${SHAPES.joinToString(", ")}), drawn as a $sh"
            var n = node(JSONObject(p.toString()).put("shape", sh), "node-${i + 1}")
            while (n.id in seen) n = n.copy(id = n.id + "-2")
            seen += n.id
            out += n
        }
        if (dropped > 0) problems += "$dropped node(s) left out: a scene holds at most $MAX_NODES (low-end phones draw them all)"
        // Parents must exist, never point at themselves, and never form a cycle.
        val byId = out.associateBy { it.id }
        val fixed = out.map { n ->
            var p = n.parent
            if (p != null && (p == n.id || p !in byId)) { problems += "node ${n.id}: parent \u201c$p\u201d is not in the scene, so it sits at the top"; p = null }
            var guard = 0
            var q = p
            while (q != null && guard++ < 8) {
                if (q == n.id) { problems += "node ${n.id}: parent chain loops back to it, so it sits at the top"; p = null; break }
                q = byId[q]?.parent
            }
            if (p == n.parent) n else n.copy(parent = p)
        }
        return Scene(title, units, o.optInt("revision", 1).coerceAtLeast(1), fixed) to problems
    }

    // ---------------- the operations ----------------

    /** Adds or changes nodes by id ([replace] = the list is the whole scene). Returns (scene, summary). */
    fun upsert(scene: Scene, set: List<Node>, replace: Boolean = false): Pair<Scene, String> {
        val map = LinkedHashMap<String, Node>()
        if (!replace) scene.nodes.forEach { map[it.id] = it }
        val added = ArrayList<String>(); val changed = ArrayList<String>()
        for (n in set) {
            if (map.containsKey(n.id)) changed += n.id else added += n.id
            map[n.id] = n
        }
        val all = map.values.toList()
        val kept = if (all.size > MAX_NODES) all.take(MAX_NODES) else all
        val summary = buildList {
            if (added.isNotEmpty()) add("added ${added.joinToString(", ")}")
            if (changed.isNotEmpty()) add("changed ${changed.joinToString(", ")}")
            if (all.size > kept.size) add("left out ${all.size - kept.size} over the $MAX_NODES-node cap")
        }.joinToString("; ").ifBlank { "no nodes changed" }
        return scene.copy(nodes = kept, revision = scene.revision + 1) to summary
    }

    /** Drops nodes (and their children) by id. Returns (scene, summary). */
    fun remove(scene: Scene, ids: Collection<String>): Pair<Scene, String> {
        val drop = ids.map { slug(it) }.toMutableSet()
        if (drop.isEmpty()) return scene to "no nodes removed"
        var grew = true
        while (grew) {
            grew = false
            for (n in scene.nodes) if (n.id !in drop && n.parent in drop) { drop += n.id; grew = true }
        }
        val kept = scene.nodes.filterNot { it.id in drop }
        val gone = scene.nodes.map { it.id }.filter { it in drop }
        return scene.copy(nodes = kept, revision = scene.revision + 1) to
            (if (gone.isEmpty()) "no node had that id" else "removed ${gone.joinToString(", ")}")
    }

    // ---------------- the first renderer: the Design view ----------------

    /** One node in the three.js starter's blaze-parts format (see Designs.partsBlock). */
    fun part(n: Node): JSONObject {
        val o = JSONObject().put("id", n.id).put("label", n.label.ifBlank { n.id }).put("shape", n.shape)
            .put("position", arr(n.position)).put("rotation", arr(n.rotation))
            .put("color", n.material.color).put("material", n.material.kind)
            .put("opacity", n.material.opacity.toDouble()).put("visible", n.visible)
        if (n.scale != V3.ONE) o.put("scale", arr(n.scale))
        n.parent?.let { o.put("parent", it) }
        n.animation?.let { a ->
            o.put("animation", JSONObject().put("kind", a.kind).put("axis", a.axis)
                .put("speed", a.speed.toDouble()).put("amplitude", a.amplitude.toDouble()))
        }
        when (n.shape) {
            "box" -> o.put("size", arr(n.size))
            "plane" -> o.put("size", JSONArray().put(n.size.x.toDouble()).put(n.size.z.toDouble()))
            "sphere" -> o.put("radius", (n.size.x / 2f).toDouble())
            "cylinder", "cone", "capsule" -> o.put("radius", (n.size.x / 2f).toDouble()).put("height", n.size.y.toDouble())
            "torus" -> o.put("radius", (n.size.x / 2f).toDouble()).put("tube", (n.size.z * 0.15f).toDouble())
        }
        return o
    }

    fun parts(scene: Scene): JSONArray = JSONArray(scene.nodes.map { part(it) })

    /** The blaze-parts spec the Design view renders (units keep the scene's own scale). */
    fun spec(scene: Scene): JSONObject =
        com.argus.assistant.agent.Designs.spec3d(scene.title, parts(scene), scene.units)

    // ---------------- the prompt side ----------------

    /** A compact, bounded view of the graph: what the model gets on each turn instead of the whole JSON. */
    fun context(scene: Scene, maxChars: Int = MAX_CONTEXT_CHARS): String {
        if (scene.empty) return "The scene is empty (0 nodes)."
        val sb = StringBuilder("Scene \u201c${scene.title}\u201d, ${scene.units}, revision ${scene.revision}, ${scene.nodes.size} node(s):\n")
        for (n in scene.nodes) {
            val line = "- ${n.id}: ${n.shape}" +
                " at (${fmt(n.position.x)}, ${fmt(n.position.y)}, ${fmt(n.position.z)})" +
                " size (${fmt(n.size.x)} \u00d7 ${fmt(n.size.y)} \u00d7 ${fmt(n.size.z)})" +
                (if (n.rotation != V3.ZERO) " rot (${fmt(n.rotation.x)}, ${fmt(n.rotation.y)}, ${fmt(n.rotation.z)})" else "") +
                (if (n.scale != V3.ONE) " scale (${fmt(n.scale.x)}, ${fmt(n.scale.y)}, ${fmt(n.scale.z)})" else "") +
                (if (n.parent != null) " in ${n.parent}" else "") +
                (if (!n.visible) " hidden" else "") +
                " ${n.material.color} ${n.material.kind}" + (if (n.label.isNotBlank() && n.label != n.id) " \u201c${n.label}\u201d" else "") + "\n"
            if (sb.length + line.length > maxChars) { sb.append("\u2026 (${scene.nodes.size - scene.nodes.indexOf(n)} more nodes; read the scene for all of them)\n"); break }
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }

    private fun fmt(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else String.format(java.util.Locale.US, "%.2f", f)

    // ---------------- the JSON schema the model fills in ----------------

    val SCHEMA_JSON: String = """
{
  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "Argus spatial scene",
  "description": "A 3D scene graph for the spatial canvas. Units are metres by default.",
  "type": "object",
  "properties": {
    "action": { "enum": ["create", "update", "read", "open"] },
    "title": { "type": "string", "maxLength": 60 },
    "units": { "enum": ["mm", "cm", "m"] },
    "nodes": { "type": "array", "maxItems": 120, "items": { "${'$'}ref": "#/${'$'}defs/node" } },
    "set_nodes": { "type": "array", "maxItems": 120, "items": { "${'$'}ref": "#/${'$'}defs/node" } },
    "remove": { "type": "array", "items": { "type": "string" } }
  },
  "required": ["action"],
  "${'$'}defs": {
    "vec3": { "type": "array", "minItems": 3, "maxItems": 3, "items": { "type": "number" } },
    "node": {
      "type": "object",
      "properties": {
        "id": { "type": "string", "description": "kebab-case, unique in the scene" },
        "shape": { "enum": ["box", "sphere", "cylinder", "cone", "torus", "plane", "capsule", "group"] },
        "label": { "type": "string", "maxLength": 60 },
        "position": { "${'$'}ref": "#/${'$'}defs/vec3" },
        "rotation": { "${'$'}ref": "#/${'$'}defs/vec3", "description": "degrees" },
        "scale": { "${'$'}ref": "#/${'$'}defs/vec3" },
        "size": { "${'$'}ref": "#/${'$'}defs/vec3", "description": "width, height, depth in scene units" },
        "color": { "type": "string", "pattern": "^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})${'$'}" },
        "material": { "enum": ["matte", "metal", "glass", "plastic", "wood"] },
        "opacity": { "type": "number", "minimum": 0.05, "maximum": 1 },
        "parent": { "type": "string" },
        "visible": { "type": "boolean" },
        "animation": {
          "type": "object",
          "properties": {
            "kind": { "enum": ["spin", "bob", "pulse"] },
            "axis": { "enum": ["x", "y", "z"] },
            "speed": { "type": "number", "minimum": -4, "maximum": 4 },
            "amplitude": { "type": "number", "minimum": 0, "maximum": 2 }
          }
        }
      },
      "required": ["id"]
    }
  }
}
"""
}
