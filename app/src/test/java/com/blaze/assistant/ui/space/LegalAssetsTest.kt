package com.blaze.assistant.ui.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.52.3 Settings › About › Licences reads app/src/main/assets/legal. The files ship in the APK and must stay identical
 * to the repo root's LICENSE.md and THIRD_PARTY_NOTICES.md (copy them again when either changes), and About lists them.
 */
class LegalAssetsTest {
    private val app = listOf(File("src/main"), File("app/src/main")).first { File(it, "assets").isDirectory }
    private val repo = app.absoluteFile.parentFile.parentFile.parentFile   // app/src/main -> repo root

    @Test fun everyListedFileShipsAndMatchesTheRepoRoot() {
        assertEquals(listOf("LICENSE.md", "THIRD_PARTY_NOTICES.md"), LegalAssets.FILES.map { it.first })
        val dir = File(app, "assets/" + LegalAssets.DIR)
        assertTrue(dir.isDirectory)
        assertEquals("only the licence files", LegalAssets.FILES.map { it.first }.toSet(), dir.list()!!.toSet())
        for ((f, title) in LegalAssets.FILES) {
            val shipped = File(dir, f)
            assertTrue(f, shipped.isFile && shipped.length() > 500)
            assertTrue(title.isNotBlank())
            val root = File(repo, f)
            assertTrue("repo root $f", root.isFile)
            assertEquals("assets/legal/$f is out of date with the repo root", root.readText(), shipped.readText())
        }
        assertTrue(File(dir, "LICENSE.md").readText().contains("PolyForm Noncommercial"))
    }

    /** P0-IL-02: every third-party source the ARGUS overhaul uses is credited, and the stale gateway/ copy says so. */
    @Test fun overhaulSourcesAreCredited() {
        val n = File(repo, "THIRD_PARTY_NOTICES.md").readText().substringAfter("## ARGUS overhaul")
        for (s in listOf("Haze", "Apache License 2.0", "Lucide", "ISC License", "Feather", "lucide-static 0.460.0",
                "Bot HQ", "Nick Vasilescu", "No Bot HQ artwork", "A-team", "7c4b459a", "copied into this project under the MIT License")) assertTrue("notices name $s", s in n)
        assertTrue(File(repo, "gateway/README.md").readText().startsWith("> **Read-only, stale copy"))
        assertTrue("evals provenance", "credited in THIRD_PARTY_NOTICES.md" in File(repo, "evals/README.md").readText())
    }

    @Test fun plainTextDropsMarkdown() {
        assertEquals("Title\nSome bold and code.\nline", LegalAssets.plain("# Title\nSome **bold** and `code`.\n```\nline\n```"))
        assertEquals("", LegalAssets.plain("```\n```"))
        val real = LegalAssets.plain(File(app, "assets/legal/THIRD_PARTY_NOTICES.md").readText())
        assertFalse(real.contains("```")); assertFalse(real.lines().any { it.startsWith("#") })
    }

    @Test fun aboutListsTheLicences() {
        val src = File(app, "java/com/blaze/assistant/ui/SetupColumns.kt").readText()
        val about = src.substringAfter("fun AboutPage()").substringBefore("\n}\n")
        assertTrue("About shows the licences", "LicencesSection()" in about)
        assertTrue(src.contains("LegalAssets.read(ctx, f)") && src.contains("LegalAssets.FILES.forEach"))
    }
}
