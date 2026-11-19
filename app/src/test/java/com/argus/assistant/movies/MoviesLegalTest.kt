package com.blaze.assistant.movies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.46.2 legal guard: Blaze ships, suggests and links no third-party provider repo or streaming site, and no anti-censorship
 * tunnel. The only Vega link allowed anywhere is the vega-app source repo in THIRD_PARTY_NOTICES.md (Apache-2.0 attribution).
 */
class MoviesLegalTest {
    private val app = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }.absoluteFile
    private val repoRoot = app.parentFile.parentFile.parentFile
    private val banned = Regex("""(?i)zenda|vega-org|vega-providers|vegamovies|moviesmod|hdhub|multimovies|uhdmovies|katmovie|goodbyedpi|byedpi|zapret|warp-plus|cloudflare warp""")
    private val okHosts = setOf("archive.org", "blazeinchue.pages.dev", "github.com", "raw.githubusercontent.com", "gitlab.com", "192.168.1.20", "schemas.android.com")

    private fun scanned(): List<File> = (listOf(File(app, "java/com/blaze/assistant/movies"), File(app, "assets/movies"), File(app, "assets/skills/reverse-engineer-anything"))
        .flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() } + File(app, "java/com/blaze/assistant/mediaapps/MoviesUi.kt") + File(app, "java/com/blaze/assistant/mediaapps/VideoShell.kt") +
        File(app, "java/com/blaze/assistant/mediaapps/VideoHub.kt") + File(app, "AndroidManifest.xml"))

    @Test fun noThirdPartyProviderNamesOrUrls() {
        val files = scanned()
        assertTrue(files.size > 15)
        for (f in files) {
            val t = f.readText()
            assertTrue("${f.name} names a third-party provider/tunnel", !banned.containsMatchIn(t))
            Regex("""https?://([A-Za-z0-9.-]+)""").findAll(t).map { it.groupValues[1].lowercase().trimEnd('.') }.filter { it.isNotEmpty() }.forEach { h ->
                assertTrue("${f.name} links $h", h in okHosts || h.endsWith(".archive.org"))
            }
        }
    }

    @Test fun wholeAppNamesNoThirdPartyProviderRepo() {
        // 0.52.3: About › Licences ships THIRD_PARTY_NOTICES.md in assets/legal. That copy is the one place allowed to carry
        // the vega-app attribution link, and only while it is byte-for-byte the repo root's notices (whose only Vega link is
        // pinned by noticesHoldOnlyTheVegaAppLink below).
        val notices = File(repoRoot, "THIRD_PARTY_NOTICES.md")
        val shipped = File(app, "assets/legal/THIRD_PARTY_NOTICES.md")
        assertTrue("the shipped notices match the repo root's", !shipped.exists() || shipped.readText() == notices.readText())
        app.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "json", "xml", "md") && it.absoluteFile != shipped.absoluteFile }.forEach { f ->
            assertTrue("${f.path} names a third-party provider repo", !Regex("""(?i)zenda-cross|vega-org|vega-providers""").containsMatchIn(f.readText()))
        }
    }

    @Test fun noticesHoldOnlyTheVegaAppLink() {
        val n = listOf(File(repoRoot, "THIRD_PARTY_NOTICES.md"), File("THIRD_PARTY_NOTICES.md"), File("../THIRD_PARTY_NOTICES.md")).first { it.isFile }.readText()
        val vegaLinks = Regex("""https?://\S*(?i:vega|zenda)\S*""").findAll(n).map { it.value.trimEnd(')', '.', ',') }.toSet()
        assertEquals(setOf("https://github.com/Zenda-Cross/vega-app"), vegaLinks)
        assertTrue(n.contains("9ccc0c8") && n.contains("quickjs-kt") && n.contains("Jsoup") && n.contains("REA"))
    }
}
