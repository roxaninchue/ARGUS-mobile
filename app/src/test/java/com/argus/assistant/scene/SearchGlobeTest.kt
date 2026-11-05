package com.argus.assistant.scene

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The search globe: built from real tool output, deterministic, capped, and off with the canvas. */
class SearchGlobeTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = Files.createTempDirectory("search-globe").toFile()
        SceneStore.rootOverride = root
        SceneStore.flagOverride = { true }
        SceneStore.publisher = { SceneStore.Published("/tmp/search-globe.html") }
        SearchGlobe.enabledOverride = null
        SearchGlobe.opener = { }
        SearchGlobe.reset()
    }

    @After fun tearDown() {
        SceneStore.rootOverride = null
        SceneStore.flagOverride = null
        SceneStore.publisher = null
        SearchGlobe.enabledOverride = null
        SearchGlobe.opener = null
        SearchGlobe.reset()
        root.deleteRecursively()
    }

    @Test fun urlsComeOutInOrderWithoutDuplicatesOrPunctuation() {
        val r = "Results: (1) https://kotlinlang.org/docs/coroutines-guide.html, " +
            "(2) https://developer.android.com/kotlin/coroutines. Again: https://kotlinlang.org/docs/coroutines-guide.html " +
            "and https://example.com/a?b=1#c."
        assertEquals(listOf("https://kotlinlang.org/docs/coroutines-guide.html",
            "https://developer.android.com/kotlin/coroutines", "https://example.com/a?b=1#c"), SearchGlobe.urlsIn(r))
    }

    @Test fun theGlobeHasEarthRingsTheQueryAndAMarkerPerPage() {
        val s = SearchGlobe.build("kotlin coroutines", listOf("https://a.com", "https://b.org"), setOf("https://b.org"))
        val ids = s.nodes.map { it.id }
        assertTrue("earth", "earth" in ids)
        assertTrue("query", "query" in ids)
        assertTrue("one marker per page", "page-1" in ids && "page-2" in ids)
        assertEquals("two sweeps", 2, s.nodes.count { it.shape == "torus" })
        assertTrue("the query names the scene", s.title.contains("kotlin coroutines"))
        assertEquals("m", s.units)
        assertEquals("kotlin coroutines", s.nodes.first { it.id == "query" }.label)
        assertEquals("b.org", s.nodes.first { it.id == "page-2" }.label)
        assertEquals("#FFFFFF", s.nodes.first { it.id == "page-2" }.material.color.uppercase())
        assertEquals("#9AA0AB", s.nodes.first { it.id == "page-1" }.material.color.uppercase())
    }

    @Test fun markersSitOnTheGlobeAndNeverMove() {
        val url = "https://kotlinlang.org/docs/coroutines-guide.html"
        val a = SearchGlobe.build("q", listOf(url)).nodes.first { it.id == "page-1" }
        val b = SearchGlobe.build("q", listOf(url)).nodes.first { it.id == "page-1" }
        assertEquals(a.position, b.position)
        val r = kotlin.math.sqrt((a.position.x * a.position.x + a.position.y * a.position.y + a.position.z * a.position.z).toDouble())
        assertTrue("on the sphere: " + r, kotlin.math.abs(r - SearchGlobe.MARKER_AT) < 0.02)
    }

    @Test fun theMarkersAreCapped() {
        val many = (1..20).map { "https://site" + it + ".example/page" }
        val s = SearchGlobe.build("q", many)
        assertEquals(SearchGlobe.MAX_PAGES, s.nodes.count { it.id.startsWith("page-") })
    }

    @Test fun aSearchPublishesTheGlobeUnderItsOwnSlug() {
        var published: Scene? = null
        SceneStore.publisher = { s -> published = s; SceneStore.Published("/tmp/web-search.html") }
        SearchGlobe.record("coroutines", "https://kotlinlang.org/docs/coroutines-guide.html and https://developer.android.com/kotlin/coroutines")
        val file = File(File(root, SearchGlobe.SLUG), "scene.json")
        assertTrue("scene written", file.isFile)
        val json = JSONObject(file.readText())
        assertTrue(json.getString("title").contains("coroutines"))
        assertEquals("earth, two sweeps, the query and two pages", 6, json.getJSONArray("nodes").length())
        assertTrue(published!!.nodes.any { it.label == "kotlinlang.org" })
    }

    @Test fun theGlobeIsBroughtUpOnTheDesignView() {
        var opened: String? = null
        SearchGlobe.opener = { opened = it }
        SearchGlobe.record("coroutines", "https://kotlinlang.org/docs/coroutines-guide.html")
        assertEquals("/tmp/web-search.html", opened)
    }

    @Test fun nothingHappensWhileTheCanvasIsOff() {
        SearchGlobe.enabledOverride = { false }
        SearchGlobe.record("q", "https://a.com")
        assertFalse(File(File(root, SearchGlobe.SLUG), "scene.json").isFile)
    }
}
