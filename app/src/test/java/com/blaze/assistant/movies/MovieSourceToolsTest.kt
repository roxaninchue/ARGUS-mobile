package com.blaze.assistant.movies

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.55.13 Argus Movie tools: movie_sources text, provider lookup, the provider gate, YouTube streams, and the lean rule. */
class MovieSourceToolsTest {
    private val st = ProviderStore.State(
        repos = listOf(ProviderStore.Repo("https://raw.githubusercontent.com/o/r/main", "github.com/o/r", 1L)),
        installed = listOf(
            ProviderStore.Installed(ProviderRepo.Entry("alpha", "Alpha Films", "1"), "https://raw.githubusercontent.com/o/r/main", ProviderStore.Status.WORKING),
            ProviderStore.Installed(ProviderRepo.Entry("beta", "Beta", "1"), "https://raw.githubusercontent.com/o/r/main", enabled = false)))

    @Test fun listNamesBuiltInsAndEveryProvider() {
        val t = MovieSourceTools.listText(st, null)
        assertTrue(t.startsWith("Built in: YouTube, the phone's videos."))
        assertTrue(t.contains("Alpha Films [alpha] on, working")); assertTrue(t.contains("Beta [beta] off, not tested"))
        assertTrue(MovieSourceTools.listText(ProviderStore.State(), "https://jf.example").contains("Jellyfin (https://jf.example)"))
        assertTrue(MovieSourceTools.listText(ProviderStore.State(), null).endsWith("No added sources."))
        assertFalse(MovieSourceTools.listText(st, null).contains("Internet Archive"))
    }

    @Test fun providerFoundByIdOrName() {
        assertEquals("alpha", MovieSourceTools.findProvider(st, "ALPHA")?.value)
        assertEquals("alpha", MovieSourceTools.findProvider(st, "alpha films")?.value)
        assertEquals("beta", MovieSourceTools.findProvider(st, "bet")?.value)
        assertNull(MovieSourceTools.findProvider(st, "")); assertNull(MovieSourceTools.findProvider(st, "gamma"))
    }

    @Test fun failedAddTellsTheModelWhatASourceIs() {
        val t = MovieSourceTools.addFailed("vega-app.in", "No manifest.json found there (The source answered 404)")
        assertTrue(t.contains("manifest.json")); assertTrue(t.contains("owner/repo")); assertTrue(t.contains("isn't one"))
        assertTrue(t.contains("web_search")); assertTrue(t.contains("ask the user"))
    }

    @Test fun providersJoinOnlyWhenTheModelAsks() {
        val names = listOf("Alpha Films")
        assertFalse(MovieTools.allowProviders(JSONObject().put("action", "play"), names))
        assertTrue(MovieTools.allowProviders(JSONObject().put("use_providers", true), names))
        assertTrue(MovieTools.allowProviders(JSONObject().put("source", "alpha films"), names))
        assertFalse(MovieTools.allowProviders(JSONObject().put("source", "youtube"), names))
    }

    @Test fun toolDescriptionsAreShortAndSearchable() {
        for (t in listOf(MovieTools.tool(), MovieSourceTools.tool())) {
            val d = t.getJSONObject("function").getString("description")
            assertTrue(d.length <= 400); assertTrue(d.contains("Movie"))
            assertFalse(d.contains("ALWAYS") || d.contains("MUST"))
            assertFalse(d.contains("Internet Archive") || d.contains("Blender"))
        }
    }

    @Test fun movieToolsStayOutOfTheCore() {
        // docs/LEAN-TOOLS.md: new tools live in the catalog; LeanTurn.CORE must not name them.
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val lean = java.io.File(root, "com/blaze/assistant/agent/LeanTurn.kt").readText()
        val core = Regex("""val CORE\s*=\s*(?:linkedSetOf|setOf|listOf)\(([^)]*)\)""").find(lean)?.groupValues?.get(1).orEmpty()
        assertTrue("LeanTurn.CORE not found", core.isNotBlank())
        for (n in listOf("movies", "movie_sources", "video_ops")) assertFalse(n, core.contains("\"$n\""))
    }
}
