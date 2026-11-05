package com.argus.assistant.scene

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Spatial canvas stage 1: the pure scene graph, its JSON schema and the store's create/update/read cycle. */
class SceneTest {
    private lateinit var root: File
    private var published: Scene? = null
    private var publishCalls = 0

    @Before fun setUp() {
        root = Files.createTempDirectory("scenes").toFile()
        SceneStore.rootOverride = root
        SceneStore.flagOverride = { true }
        published = null
        publishCalls = 0
        SceneStore.publisher = { s -> publishCalls++; published = s; SceneStore.Published(File(root, "design.html").absolutePath) }
    }

    @After fun tearDown() {
        SceneStore.rootOverride = null
        SceneStore.flagOverride = null
        SceneStore.publisher = null
    }

    private fun n(id: String, vararg extra: Pair<String, Any>) = JSONObject().put("id", id).apply {
        extra.forEach { (k, v) -> if (v is Array<*>) put(k, JSONArray(v.toList())) else put(k, v) }
    }

    private fun scene(vararg nodes: JSONObject) = Scene.normalize(JSONObject().put("title", "Test").put("nodes", JSONArray(nodes.toList()))).first

    // ---------------- the model ----------------

    @Test fun unknownShapeIsABoxWithANote() {
        val (s, problems) = Scene.normalize(JSONObject().put("title", "x")
            .put("nodes", JSONArray(listOf(n("blob", "shape" to "wibble-wobble")))))
        assertEquals("box", s.nodes[0].shape)
        assertTrue(problems.toString(), problems.any { it.contains("unknown shape") })
    }

    @Test fun aliasesAndSlugsAreNormalised() {
        val (s, _) = Scene.normalize(JSONObject().put("nodes", JSONArray(listOf(
            n("Rocket Nose", "shape" to "nosecone", "size" to arrayOf(0.4, 0.8, 0.4)),
            n("nosecone", "shape" to "cone", "size" to arrayOf(0.4, 0.8, 0.4)),
        ))))
        assertEquals("cone", s.nodes[0].shape)
        assertEquals(2, s.nodes.map { it.id }.toSet().size)   // never two nodes with one id
        assertTrue(s.nodes[1].id.startsWith("nosecone"))
    }

    @Test fun theNodeCapKeepsLowEndPhonesAlive() {
        val many = (1..Scene.MAX_NODES + 12).map { n("n$it") }
        val (s, problems) = Scene.normalize(JSONObject().put("nodes", JSONArray(many)))
        assertEquals(Scene.MAX_NODES, s.nodes.size)
        assertTrue(problems.toString(), problems.any { it.contains("at most ${Scene.MAX_NODES}") })
    }

    @Test fun parentsMustExistAndNeverLoop() {
        val (s, problems) = Scene.normalize(JSONObject().put("nodes", JSONArray(listOf(
            n("cup", "parent" to "ghost"),
            n("a", "parent" to "a"),
        ))))
        assertNull(s.nodes.first { it.id == "cup" }.parent)
        assertNull(s.nodes.first { it.id == "a" }.parent)
        assertTrue(problems.size >= 2)
    }

    @Test fun jsonRoundTrips() {
        val s = scene(n("body", "shape" to "cylinder", "size" to arrayOf(0.6, 2.4, 0.6), "color" to "#0A84FF", "material" to "metal"),
            n("fin", "shape" to "box", "parent" to "body", "rotation" to arrayOf(0.0, 45.0, 0.0)))
        val back = Scene.fromJson(Scene.sceneJson(s).toString())!!
        assertEquals(s, back)
    }

    @Test fun numbersAndColoursAreClamped() {
        val (s, _) = Scene.normalize(JSONObject().put("nodes", JSONArray(listOf(
            n("big", "scale" to arrayOf(-4.0, 400.0, 1.0), "color" to "not-a-colour", "opacity" to 9.0)))))
        val node = s.nodes[0]
        assertEquals(0.01f, node.scale.x, 0.0001f)
        assertEquals(100f, node.scale.y, 0.0001f)
        assertEquals("#9AA0AB", node.material.color)
        assertEquals(1f, node.material.opacity, 0.0001f)
        assertEquals("#00FF88", Scene.color("#0f8"))
    }

    // ---------------- the bridge to the Design view ----------------

    @Test fun partsUseTheStarterShapeParameters() {
        val s = scene(
            n("cube", "shape" to "box", "size" to arrayOf(2.0, 3.0, 4.0)),
            n("ball", "shape" to "sphere", "size" to arrayOf(2.0, 2.0, 2.0)),
            n("pipe", "shape" to "cylinder", "size" to arrayOf(0.6, 2.0, 0.6)),
            n("ring", "shape" to "torus", "size" to arrayOf(1.0, 1.0, 0.4)),
            n("floor", "shape" to "plane", "size" to arrayOf(8.0, 0.1, 6.0)),
        )
        val p = Scene.parts(s)
        assertEquals(5, p.length())
        val box = p.getJSONObject(0); assertEquals(3, box.getJSONArray("size").length())
        val ball = p.getJSONObject(1); assertEquals(1.0, ball.getDouble("radius"), 1e-6)
        val pipe = p.getJSONObject(2)
        assertEquals(0.3, pipe.getDouble("radius"), 1e-6)
        assertEquals(2.0, pipe.getDouble("height"), 1e-6)
        val ring = p.getJSONObject(3); assertTrue(ring.has("tube"))
        val floor = p.getJSONObject(4); assertEquals(2, floor.getJSONArray("size").length())
    }

    @Test fun specCarriesTheSceneScale() {
        val s = scene(n("cube")).copy(units = "cm")
        val spec = Scene.spec(s)
        assertEquals(0.01, spec.getDouble("scale"), 1e-9)
        assertEquals("cm", spec.getString("units"))
        assertEquals(1, spec.getJSONArray("parts").length())
    }

    @Test fun schemaIsValidJsonAndMatchesTheDsl() {
        val schema = JSONObject(Scene.SCHEMA_JSON)
        assertEquals("object", schema.getString("type"))
        val actions = schema.getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
        assertEquals(listOf("create", "update", "read", "open"), (0 until actions.length()).map { actions.getString(it) })
        assertNotNull(schema.getJSONObject("\$defs").getJSONObject("node"))
        assertEquals(Scene.MAX_NODES, schema.getJSONObject("properties").getJSONObject("nodes").getInt("maxItems"))
    }

    @Test fun contextIsBounded() {
        val big = scene(*(1..Scene.MAX_NODES).map { n("node-$it", "shape" to "box", "label" to "a fairly long label for node $it") }.toTypedArray())
        val ctx = Scene.context(big)
        assertTrue("${ctx.length} chars", ctx.length <= Scene.MAX_CONTEXT_CHARS + 120)
        assertTrue(ctx.contains("Scene \u201cTest\u201d"))
    }

    // ---------------- the store ----------------

    @Test fun createUpdateRead() {
        val create = SceneStore.apply(JSONObject().put("action", "create").put("title", "Rocket")
            .put("nodes", JSONArray(listOf(
                n("body", "shape" to "cylinder", "size" to arrayOf(0.6, 2.4, 0.6)),
                n("nose", "shape" to "cone", "size" to arrayOf(0.6, 0.9, 0.6), "parent" to "body")))))
        assertTrue(create.text, create.ok)
        assertEquals(1, publishCalls)
        assertEquals(2, published!!.nodes.size)
        assertTrue(File(root, "current.json").isFile)
        assertEquals(1, SceneStore.current()!!.revision)

        val update = SceneStore.apply(JSONObject().put("action", "update")
            .put("set_nodes", JSONArray(listOf(n("nose", "shape" to "cone", "size" to arrayOf(0.6, 1.4, 0.6), "parent" to "body"),
                n("fin", "shape" to "box", "parent" to "body")))))
        assertTrue(update.text, update.ok)
        assertEquals(2, publishCalls)
        val scene = SceneStore.load()!!
        assertEquals(3, scene.nodes.size)
        assertEquals(1.4f, scene.nodes.first { it.id == "nose" }.size.y, 1e-4f)
        assertEquals(2, scene.revision)

        val remove = SceneStore.apply(JSONObject().put("action", "update").put("remove", JSONArray(listOf("body"))))
        assertTrue(remove.text, remove.ok)
        assertEquals(0, SceneStore.load()!!.nodes.size)   // children of a removed group go too

        val read = SceneStore.apply(JSONObject().put("action", "read"))
        assertTrue(read.ok)
        assertTrue(read.text.contains("\"revision\""))
    }

    @Test fun createWithoutNodesIsRefused() {
        val r = SceneStore.apply(JSONObject().put("action", "create").put("title", "Empty"))
        assertFalse(r.ok)
        assertTrue(r.text.contains("at least one node"))
        assertEquals(0, publishCalls)
    }

    @Test fun updateWithoutASceneIsRefused() {
        val r = SceneStore.apply(JSONObject().put("action", "update").put("set_nodes", JSONArray(listOf(n("a")))))
        assertFalse(r.ok)
        assertTrue(r.text.contains("action=create"))
    }

    @Test fun unchangedUpdateSaysSo() {
        SceneStore.apply(JSONObject().put("action", "create").put("nodes", JSONArray(listOf(n("a")))))
        val r = SceneStore.apply(JSONObject().put("action", "update").put("set_nodes", JSONArray(listOf(n("a")))))
        assertFalse(r.ok)
        assertTrue(r.text, r.text.contains("changed nothing"))
    }

    @Test fun theFlagIsOffUntilItIsTurnedOn() {
        SceneStore.flagOverride = null
        assertFalse(SceneStore.enabled)   // Settings isn't initialised here: a settings read must never crash a turn
        SceneStore.flagOverride = { true }
        assertTrue(SceneStore.enabled)
    }

    @Test fun toolDefinitionMatchesTheSchema() {
        val f = SceneStore.tool().getJSONObject("function")
        assertEquals("scene", f.getString("name"))
        val props = f.getJSONObject("parameters").getJSONObject("properties")
        for (key in listOf("action", "title", "units", "nodes", "set_nodes", "remove")) assertTrue(key, props.has(key))
        assertTrue(f.getString("description").contains("${Scene.MAX_NODES}"))
    }

    @Test fun nothingIsWrittenWhenTheFlagIsOff() {
        SceneStore.flagOverride = { false }
        assertFalse(SceneStore.enabled)
        assertFalse(File(root, "current.json").exists())
        assertNull(SceneStore.load())
    }

    // ---------------- animation ----------------

    private fun template(): String =
        listOf(File("src/main/assets/skills/three-d-design/assets/mockup.html"),
            File("app/src/main/assets/skills/three-d-design/assets/mockup.html")).first { it.isFile }.readText()

    @Test fun partsCarryTheirAnimationBlock() {
        val s = scene(
            n("ring", "shape" to "torus", "animation" to JSONObject().put("kind", "spin").put("axis", "z").put("speed", 1.25)),
            n("sun", "shape" to "sphere"))
        val p = Scene.parts(s)
        val a = p.getJSONObject(0).getJSONObject("animation")
        assertEquals("spin", a.getString("kind"))
        assertEquals("z", a.getString("axis"))
        assertEquals(1.25, a.getDouble("speed"), 1e-9)
        assertFalse(p.getJSONObject(1).has("animation"))
    }

    @Test fun theStarterRunsTheStoredAnimations() {
        val t = template()
        for (kind in Scene.ANIMATIONS) assertTrue("$kind missing from the starter", t.contains("\"$kind\""))
        assertTrue("reduced motion must win", t.contains("prefers-reduced-motion"))
        assertTrue("the host must be able to switch motion off", t.contains("__blazeMotion"))
        assertTrue("Design's motion control reaches the page", t.contains("__blazeAnimSpeed"))
        assertTrue("the clock is integrated, so a pause holds the pose", t.contains("animTime"))
        assertTrue("exactly one render loop", t.split("setAnimationLoop").size - 1 == 1)
        val s = scene(n("ring", "shape" to "torus", "animation" to JSONObject().put("kind", "spin")))
        val page = com.argus.assistant.agent.Designs.render3d(t, Scene.spec(s))
        assertTrue(page.contains("\"animation\""))
        assertTrue(page.contains("\"spin\""))
    }
}
