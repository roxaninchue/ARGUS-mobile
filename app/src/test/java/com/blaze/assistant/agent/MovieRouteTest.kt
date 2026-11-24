package com.blaze.assistant.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.55.13 (Finn: no trigger words, the model decides): movie requests are no longer matched by phrases. They go to the model,
 * which picks `movies` / `movie_sources` from the catalog.
 */
class MovieRouteTest {
    @Test fun moviePhrasesReachTheModel() {
        for (t in listOf("play the movie Nosferatu", "watch Sintel", "search movies about trains", "continue watching"))
            assertNull(t, InAppRoute.match(t))
    }

    @Test fun noMovieRegexLeft() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val src = java.io.File(root, "com/blaze/assistant/agent/InAppRoute.kt").readText()
        assertTrue(!src.contains("moviePlay") && !src.contains("movieSearch") && !src.contains("Route.Movie"))
        val tools = java.io.File(root, "com/blaze/assistant/movies/MovieTools.kt").readText()
        assertTrue("no regex on the user's words gates providers", !tools.contains("userAllows") && !tools.contains("Regex("))
    }
}
