package com.blaze.assistant.movies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 0.55.14 the Vega-style source adder: every form a shared source arrives in, and private tokens kept to their own source. */
class SourceInputTest {
    private fun spec(s: String) = SourceInput.parse(s).spec!!

    @Test fun authorNamesPickTheHost() {
        assertEquals("https://raw.githubusercontent.com/someone/providers/main", spec("someone").bases[0])
        assertEquals("someone", spec("someone").label)
        assertEquals("https://codeberg.org/someone/providers/raw/branch/main", spec("someone@cb").bases[0])
        assertEquals("https://bitbucket.org/someone/providers/raw/main", spec("someone@bb").bases[0])
        assertEquals("https://gitlab.com/someone/providers/-/raw/main", spec("someone@gl").bases[0])
        assertEquals("someone@cb", spec("someone@cb").label)
        assertTrue(SourceInput.parse("someone@zz").error.contains("isn't a host"))
    }

    @Test fun ownerRepoAndBranch() {
        val s = spec("o/r")
        assertEquals(listOf("https://raw.githubusercontent.com/o/r/main", "https://raw.githubusercontent.com/o/r/main/dist",
            "https://raw.githubusercontent.com/o/r/master", "https://raw.githubusercontent.com/o/r/master/dist"), s.bases)
        assertEquals(listOf("https://raw.githubusercontent.com/o/r/dev", "https://raw.githubusercontent.com/o/r/dev/dist"), spec("o/r@dev").bases)
        assertEquals("o/r (dev)", spec("o/r@dev").label)
        assertEquals(spec("o/r").key, spec("https://github.com/o/r").key)   // the same repo typed two ways is one source
    }

    @Test fun repoLinksOnEveryHost() {
        assertEquals("https://raw.githubusercontent.com/o/r/dev/sub", spec("https://github.com/o/r/tree/dev/sub").bases[0])
        assertEquals("https://raw.githubusercontent.com/o/r/main", spec("github.com/o/r.git").bases[0])
        assertEquals("https://raw.githubusercontent.com/o/r/dev/x", spec("https://raw.githubusercontent.com/o/r/refs/heads/dev/x/manifest.json").bases[0])
        assertEquals("https://codeberg.org/o/r/raw/branch/b", spec("https://codeberg.org/o/r/src/branch/b").bases[0])
        assertEquals("https://bitbucket.org/o/r/raw/b", spec("https://bitbucket.org/o/r/src/b/").bases[0])
        assertEquals("https://gitlab.com/g/sub/p/-/raw/b/dir", spec("https://gitlab.com/g/sub/p/-/tree/b/dir").bases[0])
        assertTrue(SourceInput.parse("https://github.com/o").error.contains("doesn't point to a repo"))
    }

    @Test fun otherLinksAndBadInput() {
        val m = spec("https://example.org/p/list.json?v=2")
        assertEquals("https://example.org/p/list.json?v=2", m.manifest); assertEquals(listOf("https://example.org/p"), m.bases)
        assertEquals("", spec("https://example.org/p/manifest.json").manifest)
        assertEquals(listOf("https://example.org/p", "https://example.org/p/dist"), spec("example.org/p").bases)
        assertEquals(SourceInput.EMPTY, SourceInput.parse("  ").error)
        assertEquals(SourceInput.NOT_A_SOURCE, SourceInput.parse("two words").error)
        assertFalse(SourceInput.parse("http://10.0.0.2/p").ok)
        assertFalse(SourceInput.parse("file:///sdcard/p").ok)
        assertFalse(SourceInput.parse("https://user:pw@example.org/p").ok)
    }

    @Test fun tokensStayWithTheirSource() {
        val gh = spec("o/r")
        assertTrue(gh.privateOk)
        assertTrue(SourceInput.authAllowed(gh.authPrefix, "https://raw.githubusercontent.com/o/r/main/manifest.json"))
        assertFalse(SourceInput.authAllowed(gh.authPrefix, "https://raw.githubusercontent.com/o/other/main/manifest.json"))
        assertFalse(SourceInput.authAllowed(gh.authPrefix, "https://evil.example/o/r/"))
        assertFalse(spec("o@cb").privateOk)
        assertFalse(spec("http://example.org/p").privateOk)
        assertNull(SourceInput.token("has space")); assertNull(SourceInput.token("")); assertEquals("abc", SourceInput.token(" abc "))
    }

    private class MemKv : ProviderStore.Kv { val m = HashMap<String, String>(); override fun get(key: String) = m[key].orEmpty(); override fun put(key: String, value: String) { m[key] = value } }

    @Test fun privateSourceSendsTheTokenOnlyToItsRepo_andSealsIt() {
        val dir = Files.createTempDirectory("src").toFile()
        val kv = MemKv()
        val authed = ArrayList<String>()
        val store = ProviderStore(dir, kv, seal = { "sealed:" + it.reversed() }, unseal = { it.removePrefix("sealed:").reversed() },
            fetchAuth = { u, h -> authed += u; assertEquals("token tok123", h["Authorization"])
                if (u.endsWith("/main/manifest.json")) """[{"value":"p1","version":"1"}]""" else throw IllegalStateException("404") }) { throw IllegalStateException("401") }
        val (base, entries) = store.addRepo("o/private-repo", "tok123")
        assertEquals("https://raw.githubusercontent.com/o/private-repo/main", base)
        assertEquals("p1", entries.single().value)
        assertTrue(authed.all { it.startsWith("https://raw.githubusercontent.com/o/private-repo/") })
        assertFalse(kv.m.values.any { it.contains("tok123") })   // never stored in the clear
        assertTrue(store.state.repos.single().isPrivate)
        assertEquals(1, store.manifest(base).size)                 // later reads use the saved token
        store.removeRepo(base)
        assertFalse(kv.m[ProviderStore.TOKENS_KEY].orEmpty().contains(base))
        assertTrue(runCatching { store.addRepo("o@cb", "tok123") }.exceptionOrNull()!!.message!!.contains("Private sources"))
    }

    @Test fun publicSourceLabelsAndCustomManifest() {
        val dir = Files.createTempDirectory("src2").toFile()
        val store = ProviderStore(dir, MemKv()) { u -> if (u == "https://example.org/p/list.json") """[{"value":"x"}]""" else throw IllegalStateException("404") }
        val (base, _) = store.addRepo("https://example.org/p/list.json")
        assertEquals("https://example.org/p", base)
        assertEquals("https://example.org/p/list.json", store.state.repos.single().manifest)
        assertEquals("x", store.manifest(base).single().value)
        val s2 = ProviderStore(Files.createTempDirectory("src3").toFile(), MemKv()) { u -> if (u.contains("/someone/providers/main/manifest.json")) """[{"value":"y"}]""" else throw IllegalStateException("404") }
        s2.addRepo("someone"); assertEquals("someone", s2.state.repos.single().label)
    }
}
