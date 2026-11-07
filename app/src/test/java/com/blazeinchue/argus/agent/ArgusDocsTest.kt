package com.blazeinchue.argus.agent


import com.blazeinchue.argus.BrandName
import com.blazeinchue.argus.store.ArgusDb
import com.blazeinchue.argus.store.JdbcSql
import com.blazeinchue.argus.store.Sql
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.28.4 argus-docs: chunking by heading (300–800 tokens, doc + section path + version), the idempotent reindex in
 * argus.db next to the memories, bm25 search ("repo" → the exact URL), the tool reference, no secrets in what ships,
 * and the GitHub near-miss fix from Blazeinchue's screenshot.
 */
class ArgusDocsTest {
    private val sqls = ArrayList<Sql>()

    @After fun tearDown() { ArgusDocs.use(null); sqls.forEach { try { it.close() } catch (_: Exception) { } } }

    private fun sql(): JdbcSql = JdbcSql().also { sqls += it }

    private fun store(s: Sql = sql()): ArgusDocs.Store {
        val meta = HashMap<String, String>()
        return ArgusDocs.Store(s, { meta[it].orEmpty() }, { k, v -> meta[k] = v })
    }

    private fun first(vararg paths: String): File? = paths.map { File(it) }.firstOrNull { it.isFile }

    /** The docs as they ship (Gradle's generator output), or the repo's own files when run outside Gradle. */
    private fun docFiles(): List<Pair<String, String>> {
        val dir = System.getProperty("argus.docs.dir")?.let { File(it) }?.takeIf { File(it, "manifest.json").isFile }
        if (dir != null) {
            val names = JSONObject(File(dir, "manifest.json").readText()).getJSONArray("files")
            return (0 until names.length()).map { names.getString(it) }.map { it to File(dir, it).readText() }
        }
        return listOfNotNull(
            first("argus-docs/about.md", "app/argus-docs/about.md")?.let { "about.md" to it.readText() },
            first("../README.md", "README.md")?.let { "readme.md" to it.readText() },
            first("../STATUS.md", "STATUS.md")?.let { "status.md" to it.readText() },
            first("../CHANGELOG.md", "CHANGELOG.md")?.let { "changelog/all.md" to it.readText() },
        )
    }

    private fun tool(name: String, desc: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc))
    private fun sampleTools() = JSONArray().apply {
        ArgusDocs.tools().forEach { put(it) }
        put(tool("web_search", "Search the web for anything. Returns results with links."))
        put(tool("browser_open", "Open a web page (or search words) in ${BrandName.NAME}'s own built-in browser, in the background. No screen control needed."))
        put(tool("memory_search", "Search what you know about the user."))
    }

    // ---------------- chunking ----------------

    @Test fun chunksFollowHeadingsAndSizes() {
        val big = (1..400).joinToString(" ") { "Sentence number $it explains one more detail about installing ${BrandName.NAME} on a phone." }
        val md = "# Guide\nIntro line.\n\n## Install\n$big\n\n## Tiny one\nShort.\n\n## Tiny two\nAlso short.\n\n```\n# not a heading\n```\n\n### Deep\nDeep text."
        val cs = ArgusDocs.chunk("guide", md, "9.9.9")
        assertTrue(cs.size >= 4)
        cs.forEach { assertTrue("${it.id} has ${ArgusDocs.tokens(it.text)} tokens", ArgusDocs.tokens(it.text) <= ArgusDocs.MAX_TOKENS) }
        assertEquals((1..cs.size).map { "guide#$it" }, cs.map { it.id })
        assertTrue(cs.all { it.doc == "guide" && it.version == "9.9.9" })
        // The long section is split, every part names its heading and section path.
        val install = cs.filter { it.section.contains("Guide > Install") }
        assertTrue(install.size >= 3)
        assertTrue(install.drop(1).all { it.text.startsWith("## Install (continued)") })
        // Small neighbours are merged, keeping every heading in the section path; a fenced "# …" is not a heading.
        val small = cs.last()
        assertTrue(small.section, small.section.contains("Tiny one") && small.section.contains("Tiny two"))
        assertTrue(small.text.contains("# not a heading"))
        assertFalse(cs.any { it.section.contains("not a heading") })
    }

    @Test fun realDocsChunkWithinBudget() {
        val files = docFiles()
        assertTrue("docs found", files.any { it.first == "about.md" })
        val cs = ArgusDocs.chunksOf(files, "test", sampleTools())
        assertTrue(cs.size > 20)
        assertEquals(cs.size, cs.map { it.id }.toSet().size)
        cs.forEach { assertTrue("${it.id}: ${ArgusDocs.tokens(it.text)} tokens", ArgusDocs.tokens(it.text) <= ArgusDocs.MAX_TOKENS) }
        // Most chunks are real sections, not crumbs (only a doc's last piece may be small).
        val docs = cs.groupBy { it.doc }
        val crumbs = docs.values.sumOf { l -> l.dropLast(1).count { ArgusDocs.tokens(it.text) < ArgusDocs.MIN_TOKENS / 3 } }
        assertTrue("crumbs: $crumbs", crumbs <= cs.size / 5)
    }

    // ---------------- storage ----------------

    @Test fun reindexIsIdempotentAndLeavesMemoriesAlone() {
        val s = sql()
        val argus = ArgusDb(s); val mind = MindStore(argus, s)
        mind.save("profile", "You", "Lives in Ndola and studies computer science")
        val st = ArgusDocs.Store(s, { argus.meta(it) }, { k, v -> argus.setMeta(k, v) })
        val files = docFiles(); val tools = sampleTools()
        assertTrue(ArgusDocs.ensure(st, "h1", "0.0.1", { files }, tools))
        val n = st.count(); val ids = st.ids("about")
        assertTrue(n > 20)
        // Same build: nothing happens. Replace again by hand: the same rows, no duplicates.
        assertFalse(ArgusDocs.ensure(st, "h1", "0.0.1", { error("must not read the docs again") }, tools))
        st.replace(st.signature(), ArgusDocs.chunksOf(files, "0.0.1", tools))
        assertEquals(n, st.count()); assertEquals(ids, st.ids("about"))
        assertEquals(n, s.query("SELECT count(*) FROM argus_docs_fts") { it.long(0).toInt() }.first())
        // A new build (or another tool list) replaces everything.
        assertTrue(ArgusDocs.ensure(st, "h2", "0.0.2", { listOf("about.md" to "# About\nOnly this.") }, null))
        assertEquals(1, st.count())
        assertEquals("0.0.2", st.read("about#1")!!.version)
        assertTrue(ArgusDocs.ensure(st, "h2", "0.0.2", { listOf("about.md" to "# About\nOnly this.") }, sampleTools()))
        assertTrue(st.count() > 1)
        // Shared schema untouched: v1, memories still there, the signature in meta under a prefixed key.
        assertEquals(1, argus.version())
        assertEquals(1, mind.all().size)
        assertTrue(argus.meta(ArgusDocs.META_SIG).startsWith("0.0.2:h2:"))
        for (t in listOf("argus_docs", "argus_docs_fts")) assertEquals(1L, s.query("SELECT count(*) FROM sqlite_master WHERE name=?", t) { it.long(0) }.first())
        // Opening again (next launch) keeps the rows.
        assertEquals(st.count(), ArgusDocs.Store(s, { argus.meta(it) }, { k, v -> argus.setMeta(k, v) }).count())
    }

    // ---------------- search ----------------

    @Test fun repoQuestionsFindTheExactUrl() {
        val st = store()
        ArgusDocs.ensure(st, "h", "test", { docFiles() }, sampleTools())
        ArgusDocs.use(st)
        for (q in listOf("where is your repo", "take me to argus-agent repo in github", "where is your source code")) {
            val hits = st.search(q, 5)
            assertTrue(q, hits.isNotEmpty())
            assertTrue("$q → ${hits.first().id}", hits.first().id.startsWith("about#"))
            assertTrue(q, st.read(hits.first().id)!!.text.contains(ArgusDocs.REPO_URL))
            val out = ArgusDocs.search(JSONObject().put("query", q))
            assertTrue(out, out.contains(ArgusDocs.REPO_URL))
            assertTrue(out, out.contains("https://gitlab.com/blazeinchue/Blaze-dekstop"))
            assertFalse(out, out.contains("github.com/Blazeinchue/"))
        }
        // Limit ≤ 5, ids readable, other questions land in the right doc.
        assertTrue(st.search("settings", 50).size <= 5)
        assertTrue(st.search("how do I update argus", 3).any { st.read(it.id)!!.text.contains("/update") })
        assertTrue(st.search("what language is argus written in", 3).any { st.read(it.id)!!.text.contains("Kotlin") })
        assertTrue(st.search("which tool searches argus docs", 5).any { it.doc == "tools" })
        assertTrue(ArgusDocs.read(JSONObject().put("id", "nope#1")).startsWith("No docs section"))
        assertTrue(ArgusDocs.read(JSONObject().put("id", "about#1")).startsWith("[about#1]"))
    }

    @Test fun versionQuestionsFindTheChangelog() {
        val dir = System.getProperty("argus.docs.dir")?.let { File(it) }?.takeIf { File(it, "changelog/v0.28.1.md").isFile } ?: return
        val st = store()
        ArgusDocs.ensure(st, "h", "test", { docFiles() }, null)
        assertTrue(dir.path, st.search("what changed in 0.28.1", 3).any { it.doc == "changelog/v0.28.1" })
    }

    @Test fun toolReferenceComesFromTheToolList() {
        val doc = ArgusDocs.toolsDoc(sampleTools()) { if (it.startsWith("browser_")) "browser" else null }
        assertTrue(doc, doc.contains("- ${ArgusDocs.TOOL_SEARCH}: Search ${BrandName.NAME}'s own docs"))
        assertTrue(doc, doc.contains("## Tools: browser"))
        assertTrue(doc, doc.contains("- web_search: Search the web for anything."))
        assertFalse(doc, doc.contains("Returns results with links"))   // one line only
        assertEquals("Open a web page (or search words) in ${BrandName.NAME}'s own built-in browser, in the background.",
            ArgusDocs.oneLine("Open a web page (or search words) in ${BrandName.NAME}'s own built-in browser, in the background. No screen control needed."))
        assertTrue(ArgusDocs.oneLine("x".repeat(400)).length <= 160)
        assertTrue(ArgusDocs.toolsHash(sampleTools()) != ArgusDocs.toolsHash(JSONArray().put(tool("a", "b"))))
    }

    // ---------------- no secrets ----------------

    @Test fun noSecretsShip() {
        val samples = listOf("write to someone@example.com", "token ghp_" + "a".repeat(36), "project argus-511220", "id " + "0123456789abcdef".repeat(2),
            "key sk-" + "x".repeat(24), "AIza" + "B".repeat(35), "storePassword = \"android\"")
        for (s in samples) { assertTrue(s, ArgusDocs.hasSecret(s)); assertFalse(s, ArgusDocs.hasSecret(ArgusDocs.scrub(s))) }
        assertFalse(ArgusDocs.hasSecret("Argus v0.28.1 (versionCode 97), minSdk 26, https://gitlab.com/blazeinchue/Blaze-agent"))
        val files = docFiles()
        val cs = ArgusDocs.chunksOf(files, "test", sampleTools())
        for (c in cs) assertFalse("${c.id} has a secret-like string", ArgusDocs.hasSecret(c.text) || ArgusDocs.hasSecret(c.section))
        assertFalse(cs.any { it.text.contains("511220") })
        // The generated files themselves are clean too (scrubbed at build time).
        if (System.getProperty("argus.docs.dir")?.let { File(it, "manifest.json").isFile } == true)
            for ((n, t) in files) assertFalse("$n has a secret-like string", ArgusDocs.hasSecret(t))
        // about.md carries the build facts (filled in by Gradle when generated).
        val about = files.first { it.first == "about.md" }.second
        assertTrue(about.contains(ArgusDocs.REPO_URL))
        assertTrue(about.contains("https://gitlab.com/blazeinchue/Blaze-cli"))
        assertFalse(about.contains("github.com/"))
        assertTrue(about.contains("https://ai.blaze-app.workers.dev"))
    }

    // ---------------- prompt + routing ----------------

    @Test fun promptLineIsShortAndPinsTheRepo() {
        assertTrue(ArgusDocs.PROMPT_LINE.contains(ArgusDocs.REPO_URL))
        assertTrue(ArgusDocs.PROMPT_LINE.contains(ArgusDocs.TOOL_SEARCH))
        assertTrue(ArgusDocs.PROMPT_LINE.length < 420)
        assertTrue(ArgusDocs.isAboutArgus("take me to argus-agent repo in github"))
        assertTrue(ArgusDocs.isAboutArgus("where is your source code"))
        assertTrue(ArgusDocs.isAboutArgus("what version are you"))
        assertTrue(ArgusDocs.isAboutArgus("How do I change ${BrandName.NAME} settings?"))
        assertFalse(ArgusDocs.isAboutArgus("what's the weather in Ndola"))
        assertFalse(ArgusDocs.isAboutArgus("open my github notifications"))
        assertTrue(ArgusDocs.run(null, ArgusDocs.TOOL_SEARCH, JSONObject().put("query", "repo")).contains(ArgusDocs.REPO_URL))
    }

    @Test fun githubNearMissesOpenTheRealRepo() {
        assertEquals(ArgusDocs.REPO_URL, ToolLeak.fixArgusUrl("https://github.com/Blazeinchue/Blaze-Agent"))
        assertEquals(ArgusDocs.REPO_URL, ToolLeak.fixArgusUrl("github.com/blazeinchue/blaze-agent"))
        assertEquals(ArgusDocs.REPO_URL, ToolLeak.fixArgusUrl("https://github.com/Blazeinchue/BLAZE-AGENT"))
        assertEquals(ArgusDocs.REPO_URL + "/releases/latest", ToolLeak.fixArgusUrl("https://github.com/Blazeinchue/Blaze_Agent/releases/latest"))
        assertEquals("https://gitlab.com/blazeinchue/Blaze-dekstop", ToolLeak.fixArgusUrl("https://github.com/Blazeinchue/Blaze-Desktop"))
        assertEquals("https://gitlab.com/blazeinchue/Blaze-cli", ToolLeak.fixArgusUrl("https://www.github.com/blazeinchue/blaze-cli.git"))
        // Someone else's repo of the same name, and other URLs, are left alone.
        assertEquals("https://github.com/octocat/argus-agent", ToolLeak.fixArgusUrl("https://github.com/octocat/argus-agent"))
        assertEquals("https://example.com/Argus-Agent", ToolLeak.fixArgusUrl("https://example.com/Argus-Agent"))
        // The leaked github_open alias: any owner or none.
        assertEquals(ArgusDocs.REPO_URL, ToolLeak.githubUrl(JSONObject().put("repo", "argus-agent")))
        assertEquals(ArgusDocs.REPO_URL, ToolLeak.githubUrl(JSONObject().put("repo", "Blazeinchue/Blaze-Agent")))
        assertEquals(ArgusDocs.REPO_URL, ToolLeak.githubUrl(JSONObject().put("url", "https://github.com/Blazeinchue/Blaze-Agent")))
        assertEquals("https://gitlab.com/blazeinchue/Blaze-dekstop", ToolLeak.githubUrl(JSONObject().put("repo", "Argus-Desktop")))
        assertEquals("https://github.com/octocat/hello", ToolLeak.githubUrl(JSONObject().put("repo", "octocat/hello")))
    }
}
