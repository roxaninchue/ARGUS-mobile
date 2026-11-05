package com.argus.assistant.scene

import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.52.1: the search globe — what ARGUS is looking at, shown while it looks.
 *
 * With the spatial canvas on, every web search builds a scene of the search itself — a globe, the query above it and
 * one marker per page — and publishes it to the Design view through [SceneStore.publishOwn]. The model does not author
 * this scene: it is derived from the tool calls, so it cannot show a page that was never seen. `fetch_page` marks the
 * page it opened, so the globe shows what is being read, not only what was listed.
 */
object SearchGlobe {
    const val SLUG = "web-search"
    const val MAX_PAGES = 8

    private const val RADIUS = 1.2
    internal const val MARKER_AT = RADIUS + 0.06
    private const val EARTH = "#05070D"
    private const val STEEL = "#9AA0AB"
    private const val BLUE = "#0056FF"
    private const val WHITE = "#FFFFFF"

    /** Tests (and the terminal) can pin this; the app leaves it null and follows the spatial canvas flag. */
    @Volatile var enabledOverride: (() -> Boolean)? = null

    /** How the globe reaches the screen; tests stub it, the app opens the Design view. */
    @Volatile var opener: ((String) -> Unit)? = null

    private val pages = LinkedHashSet<String>()
    private val visited = LinkedHashSet<String>()
    private var query = ""

    fun enabled(): Boolean = enabledOverride?.invoke() ?: SceneStore.enabled

    /** A web search ran: the globe is (re)built from its query and the pages in [results]. */
    @Synchronized
    fun record(q: String, results: String) {
        if (!enabled()) return
        try {
            query = q.trim().take(60)
            pages.clear()
            pages.addAll(urlsIn(results).take(MAX_PAGES))
            show()
        } catch (_: Exception) {
            // Showing the search must never break the search.
        }
    }

    /** A page was opened: mark it on the globe. */
    @Synchronized
    fun visited(url: String) {
        if (!enabled()) return
        try {
            val u = url.trim()
            if (u.isNotBlank() && visited.add(u)) show()
        } catch (_: Exception) { }
    }

    @Synchronized
    fun reset() {
        pages.clear(); visited.clear(); query = ""
    }

    private fun show() {
        if (query.isBlank() && pages.isEmpty() && visited.isEmpty()) return
        // fetch_page can run before any search listed the page: show every page we know about.
        val all = (pages.toList() + visited.toList()).distinct().take(MAX_PAGES)
        val path = SceneStore.publishOwn(SLUG, build(query, all, visited.toSet())).path ?: return
        open(path)
    }

    /** Brings the globe up: the Design pane shows it while the search runs, and keeps showing it as pages arrive. */
    private fun open(path: String) {
        opener?.let { it(path); return }
        try {
            kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.Main) {
                if (com.argus.assistant.ui.Views.showing.value == "design") {
                    com.argus.assistant.ui.Views.designTarget.value = path
                } else {
                    com.argus.assistant.ui.Views.open("design", path)
                }
            }
        } catch (_: Exception) {
            // Never let showing the globe cost the search.
        }
    }

    /** Every http(s) URL in a search result, in order, without duplicates or trailing punctuation. */
    fun urlsIn(results: String): List<String> = try {
        Regex("""https?://[^\s"'<>()\[\]]+""").findAll(results)
            .map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}') }
            .filter { it.length > 8 }
            .distinct()
            .toList()
    } catch (_: Exception) { emptyList() }

    /** The globe scene for [query] and [pages]; a page in [visited] is white and pulsing, the rest steel. */
    fun build(query: String, pages: List<String>, visited: Set<String> = emptySet()): Scene {
        val nodes = JSONArray()
        nodes.put(JSONObject().put("id", "earth").put("shape", "sphere").put("label", "Earth")
            .put("size", arr(RADIUS * 2, RADIUS * 2, RADIUS * 2))
            .put("position", arr(0.0, 0.0, 0.0))
            .put("color", EARTH).put("material", "matte"))
        nodes.put(ring("orbit-1", 0.0))
        nodes.put(ring("orbit-2", 60.0))
        nodes.put(JSONObject().put("id", "query").put("shape", "sphere")
            .put("label", query.trim().take(40).ifBlank { "Searching" })
            .put("size", arr(0.14, 0.14, 0.14))
            .put("position", arr(0.0, MARKER_AT, 0.0))
            .put("color", BLUE).put("material", "metal")
            .put("animation", JSONObject().put("kind", "pulse").put("speed", 0.6).put("amplitude", 0.5)))
        val shown = pages.take(MAX_PAGES)
        shown.forEachIndexed { i, url ->
            val (x, y, z) = spot(url)
            val seen = url in visited
            val node = JSONObject().put("id", "page-" + (i + 1)).put("shape", "sphere")
                .put("label", host(url))
                .put("size", arr(0.08, 0.08, 0.08))
                .put("position", arr(x, y, z))
                .put("color", if (seen) WHITE else STEEL).put("material", "metal")
            if (seen) node.put("animation", JSONObject().put("kind", "pulse").put("speed", 0.9).put("amplitude", 0.4))
            nodes.put(node)
        }
        val title = if (query.isBlank()) "Web search" else "Web search \u2014 " + query.trim().take(50)
        return Scene.normalize(JSONObject().put("title", title).put("units", "m").put("nodes", nodes)).first
    }

    private fun arr(x: Double, y: Double, z: Double) = JSONArray().put(x).put(y).put(z)

    private fun ring(id: String, tilt: Double) = JSONObject().put("id", id).put("shape", "torus")
        .put("label", "Search sweep")
        .put("size", arr(RADIUS * 2.2, RADIUS * 2.2, 0.13))
        .put("rotation", arr(90.0, 0.0, tilt))
        .put("position", arr(0.0, 0.0, 0.0))
        .put("color", BLUE).put("material", "metal")
        .put("animation", JSONObject().put("kind", "spin").put("axis", "y").put("speed", 0.5))

    /** A deterministic spot on the globe for a URL, so the same page always sits in the same place. */
    fun spot(url: String): Triple<Double, Double, Double> {
        val h = url.hashCode()
        val lat = Math.toRadians(((h % 120) - 60).toDouble())
        val lon = Math.toRadians((((h / 120) % 360) - 180).toDouble())
        return Triple(MARKER_AT * cos(lat) * cos(lon), MARKER_AT * sin(lat), MARKER_AT * cos(lat) * sin(lon))
    }

    /** The site a result came from, for the marker's label in the parts list. */
    fun host(url: String): String = try {
        url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").ifBlank { "page" }.take(24)
    } catch (_: Exception) { "page" }
}
