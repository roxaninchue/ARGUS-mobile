package com.blaze.assistant.movies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 0.46.2 provider repos: manifest parsing, owner/repo shorthand resolution, add-source deep links, one-time notice, persistence. */
class ProviderRepoTest {
    private class MemKv : ProviderStore.Kv { val m = HashMap<String, String>(); override fun get(key: String) = m[key].orEmpty(); override fun put(key: String, value: String) { m[key] = value } }

    @Test fun manifestParses_dropsBadIds_andAcceptsObjectForm() {
        val e = ProviderRepo.parseManifest("""[{"value":"good","display_name":"Good","version":"1.2"},{"value":"../x"},{"value":"good"},{"value":"two","icon":"http://127.0.0.1/i.png"}]""")
        assertEquals(listOf("good", "two"), e.map { it.value })
        assertEquals("Good", e[0].name); assertEquals("1.2", e[0].version)
        assertEquals("", e[1].icon)   // private-network icon dropped
        assertEquals(1, ProviderRepo.parseManifest("""{"providers":[{"value":"a"}]}""").size)
        assertEquals(2, ProviderRepo.parseManifest("""[{"value":"a","display_name":"A","version":"1"},{"value":"b","display_name":"B","version":"1"}]""").size)
        assertTrue(runCatching { ProviderRepo.parseManifest("<html>") }.isFailure)
    }

    @Test fun shorthandAndRepoPagesResolve() {
        val (c, why) = ProviderRepo.candidates("someone/my-repo")
        assertEquals("", why)
        assertEquals("https://raw.githubusercontent.com/someone/my-repo/main", c[0])
        assertTrue(c.contains("https://raw.githubusercontent.com/someone/my-repo/main/dist"))
        assertTrue(c.contains("https://raw.githubusercontent.com/someone/my-repo/master"))
        assertEquals("https://raw.githubusercontent.com/o/r/dev/sub", ProviderRepo.candidates("https://github.com/o/r/tree/dev/sub").first[0])
        assertEquals("https://gitlab.com/g/p/-/raw/main", ProviderRepo.candidates("https://gitlab.com/g/p").first[0])
        assertEquals("https://example.org/repo", ProviderRepo.candidates("example.org/repo/manifest.json").first[0])
        assertTrue(ProviderRepo.candidates("http://192.168.1.2/repo").first.isEmpty())
        assertTrue(ProviderRepo.candidates("file:///sdcard/x").first.isEmpty())
        assertTrue(ProviderRepo.candidates("").first.isEmpty())
        assertTrue(ProviderRepo.newer("1.9", "1.10")); assertFalse(ProviderRepo.newer("2.0", "2.0"))
    }

    @Test fun resolveTriesCandidatesUntilAManifest() {
        val dir = Files.createTempDirectory("prov").toFile()
        val store = ProviderStore(dir, MemKv()) { url -> if (url == "https://raw.githubusercontent.com/o/r/main/dist/manifest.json") """[{"value":"p1"}]""" else throw IllegalStateException("404") }
        val (base, entries) = store.resolve("o/r")
        assertEquals("https://raw.githubusercontent.com/o/r/main/dist", base)
        assertEquals("p1", entries.single().value)
    }

    @Test fun deepLinks() {
        assertEquals("o/r", AddSourceLink.parse("blaze://add-source?url=o%2Fr"))
        assertEquals("https://example.org/repo", AddSourceLink.parse("https://blazeinchue.pages.dev/add-source?url=https%3A%2F%2Fexample.org%2Frepo"))
        assertEquals("https://example.org/repo", AddSourceLink.parse("https://blazeinchue.pages.dev/add-source/?url=https%3A%2F%2Fexample.org%2Frepo"))
        assertNull(AddSourceLink.parse("https://evil.example/add-source?url=o%2Fr"))
        assertNull(AddSourceLink.parse("blaze://add-source"))
        assertNull(AddSourceLink.parse("blaze://add-source?url=http%3A%2F%2F10.0.0.1%2Fx"))
        assertEquals("o/r", AddSourceLink.parse(AddSourceLink.make("o/r")))
        assertEquals("o/r", AddSourceLink.parse(AddSourceLink.make("o/r", https = false)))
    }

    @Test fun noticeShownOnceAndRemembered() {
        val dir = Files.createTempDirectory("prov").toFile(); val kv = MemKv()
        val s = ProviderStore(dir, kv) { throw IllegalStateException() }
        assertTrue(SourceNotice.shouldShow(s.state.noticeSeen))
        s.markNoticeSeen()
        assertFalse(SourceNotice.shouldShow(s.state.noticeSeen))
        assertFalse(SourceNotice.shouldShow(ProviderStore(dir, kv) { "" }.state.noticeSeen))   // survives a restart
    }

    @Test fun legacyOfficialRepoIsDropped_userReposKept_andStatePersists() {
        // 0.55.13: nothing is preinstalled; a phone that still has the old "Blaze Official" repo loses it once.
        val dir = Files.createTempDirectory("prov").toFile(); val kv = MemKv()
        val legacy = ProviderStore.State(repos = listOf(ProviderStore.Repo(ProviderRepo.LEGACY_OFFICIAL_BASE, "Old", 0L), ProviderStore.Repo("https://example.com/r", "Mine", 1L)),
            installed = listOf(ProviderStore.Installed(ProviderRepo.Entry("ia-films", "IA", "1"), ProviderRepo.LEGACY_OFFICIAL_BASE),
                ProviderStore.Installed(ProviderRepo.Entry("mine", "Mine", "1"), "https://example.com/r")), seeded = true)
        kv.put(ProviderStore.KEY, ProviderStore.encode(legacy))
        val s = ProviderStore(dir, kv) { throw IllegalStateException() }
        s.dropLegacyOfficial()
        assertEquals(listOf("https://example.com/r"), s.state.repos.map { it.base })
        assertEquals(listOf("mine"), s.state.installed.map { it.value })
        assertEquals(s.state, ProviderStore(dir, kv) { "" }.state)
        assertEquals(ProviderStore.State(), ProviderStore.decode("garbage"))
    }

    @Test fun nothingPreinstalled_andTheExampleIsNoRealRepo() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val movies = File(src, "com/blaze/assistant/movies/Movies.kt").readText()
        assertFalse(movies.contains("seedOfficial")); assertTrue(movies.contains("dropLegacyOfficial()"))
        assertFalse(ProviderRepo.EXAMPLE.contains("blazeinchue.pages.dev"))
        assertFalse(File(src.parentFile, "assets/movies").exists())
    }

    @Test fun libraryPersists_andResumeRules() {
        val post = MoviePost("Sintel", "archive:Sintel", "https://archive.org/services/img/Sintel", "blender", "2010")
        var l = MovieLibrary.toggleWatchlist(MovieLib(), post)
        assertTrue(l.inWatchlist(post.link, post.source))
        val back = MovieLibrary.decode(MovieLibrary.encode(l))
        assertEquals(l.watchlist, back.watchlist)
        assertFalse(MovieLibrary.toggleWatchlist(back, post).inWatchlist(post.link, post.source))
        assertTrue(MovieLibrary.unfinished(10 * 60_000L, 90 * 60_000L))
        assertFalse(MovieLibrary.unfinished(10_000L, 90 * 60_000L))
        assertFalse(MovieLibrary.unfinished(89 * 60_000L, 90 * 60_000L))
        assertEquals(MovieLib(), MovieLibrary.decode("nope"))
    }
}
