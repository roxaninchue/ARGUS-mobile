package com.blazeinchue.argus.memory

import com.blazeinchue.argus.agent.AutoCompact
import com.blazeinchue.argus.agent.LongChat
import com.blazeinchue.argus.agent.Mind
import com.blazeinchue.argus.store.JdbcSql
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** TOC building, the chat tier's budget and paging, map-reduce, the cache tools and the long tier's relevant notes. */
class TieredMemoryTest {
    private fun store() = CacheStore(JdbcSql(), Files.createTempDirectory("cache").toFile())

    @After fun reset() { CacheTier.useForTests(null); CacheTier.ask = null }

    private fun sec(i: Int, chapter: String = "", title: String = "S$i") =
        CacheStore.Section("d1", i, title, chapter, 2, "gist $i", false, 0, 0, 100)
    private val doc = CacheStore.Doc("d1", "c", "paste", "Book", 400_000, 400_000, 0, 0, 0)

    // ---------------- TOC ----------------

    @Test fun smallDocsGetAFlatToc() {
        val t = CacheToc.toc(doc.copy(sections = 3), (0..2).map { sec(it) })
        assertTrue(t.startsWith("doc:d1 \"Book\" · 3 sections"))
        assertTrue(t.contains("§0 S0 — gist 0") && t.contains("§2 S2 — gist 2"))
        assertFalse(t.contains("ch 1"))
    }

    @Test fun bigDocsGetAHierarchyByChapter() {
        val secs = (0 until 60).map { sec(it, chapter = "Chapter ${it / 10 + 1}") }
        val top = CacheToc.toc(doc.copy(sections = 60), secs)
        assertTrue(top.contains("hierarchical"))
        assertTrue(top.contains("ch 1 · Chapter 1 · §0–§9"))
        assertTrue(top.contains("ch 6 · Chapter 6 · §50–§59"))
        assertFalse(top.contains("§15 S15"))
        val ch2 = CacheToc.toc(doc.copy(sections = 60), secs, chapter = 2)
        assertTrue(ch2.contains("§10 S10") && ch2.contains("§19 S19") && !ch2.contains("§20 "))
        assertTrue(CacheToc.toc(doc, secs, chapter = 9).startsWith("Error"))
    }

    @Test fun docsWithoutChaptersAreGroupedInRuns() {
        val secs = (0 until 50).map { sec(it) }
        val g = CacheToc.groups(secs)
        assertEquals(4, g.size)
        assertEquals(0 to 15, g[0].from to g[0].to)
        assertEquals(48 to 49, g[3].from to g[3].to)
    }

    @Test fun tocRespectsItsCharacterBudget() {
        val secs = (0 until 20).map { sec(it, title = "A long section title number $it with words") }
        val t = CacheToc.toc(doc.copy(sections = 20), secs, maxChars = 400)
        assertTrue(t.length <= 480)
        assertTrue(t.contains("more: cache_toc"))
    }

    // ---------------- chat tier ----------------

    @Test fun budgetIsSixKByDefaultAndScalesWithTheRoute() {
        assertEquals(6_000, ChatTier.budget(0))
        assertEquals(3_686, ChatTier.budget(8_192))        // a small 8k route: under half its window
        assertEquals(6_000, ChatTier.budget(32_768))
        assertEquals(16_000, ChatTier.budget(128_000))
        assertEquals(24_000, ChatTier.budget(1_000_000))  // capped
        assertEquals(4_200, ChatTier.tailBudget(6_000))
    }

    private fun msg(role: String, chars: Int, tag: String = "") = JSONObject().put("role", role).put("content", tag + "x".repeat(chars))

    @Test fun tailIsCappedInTokensButKeepsTheNewestUserTurn() {
        val h = JSONArray()
        repeat(6) { h.put(msg("user", 4_000, "u$it ")); h.put(msg("assistant", 8_000, "a$it ")) }   // 12 messages, ~18k tokens
        assertEquals(0, LongChat.tailStart(h, 12))                  // by count alone: everything
        val t = ChatTier.tailStart(h, 12, 4_200)
        assertTrue(t > 0)
        assertEquals("user", h.getJSONObject(t).getString("role"))
        assertTrue(LongChat.tokens(h, t) <= 4_200 || t == 10)
        // one enormous last turn: still kept (never cut after the newest user message)
        val h2 = JSONArray().put(msg("user", 100)).put(msg("assistant", 100)).put(msg("user", 80_000))
        assertEquals(2, ChatTier.tailStart(h2, 12, 4_200))
    }

    @Test fun autoCompactFoldsByTokensWhenTheTailIsTooBig() {
        val h = JSONArray()
        repeat(5) { h.put(msg("user", 6_000)); h.put(msg("assistant", 6_000)) }   // 10 messages, ~15k tokens
        val st = LongChat.State()
        // old rule (12 messages kept word for word): nothing older than the tail, so it never folds
        assertFalse(AutoCompact.shouldCompact(h, st, 6_000, 1_000, 0))
        // tiered: the tail is capped at 4.2k tokens, so the older turns fold
        assertTrue(AutoCompact.shouldCompact(h, st, 6_000, 1_000, 0, tailTok = 4_200))
        val r = AutoCompact.range(h, st, false, 4_200)
        assertEquals(0, r.first)
        assertTrue(r.last + 1 >= 6)
        assertTrue(LongChat.tokens(h, r.last + 1) <= 6_000)
    }

    @Test fun pagedOutTurnsAreVerbatim() {
        val h = JSONArray().put(JSONObject().put("role", "user").put("content", "Plan the garden"))
            .put(JSONObject().put("role", "assistant").put("content", "Tomatoes in bed 4."))
        val t = ChatTier.pageText(h, 0, 2)
        assertTrue(t.contains("## turn 0 · user\n\nPlan the garden"))
        assertTrue(t.contains("## turn 1 · assistant\n\nTomatoes in bed 4."))
        assertEquals("chat-ab_c", ChatTier.docId("ab/c"))
    }

    @Test fun pagedOutHistoryIsSearchable() = runBlocking {
        CacheTier.useForTests(store())
        CacheTier.pageOut("chat1", ChatTier.pageText(JSONArray().put(JSONObject().put("role", "user").put("content", "my locker code word is pineapple")), 0, 1))
        val r = CacheTier.run("cache_search", JSONObject().put("doc", "all").put("query", "locker pineapple"), "chat1")
        assertTrue(r, r.contains("doc:chat-chat1 §0"))
    }

    // ---------------- cache tier: paging inputs and the tools ----------------

    @Test fun bigPasteIsCachedAndReplacedByAReference() = runBlocking {
        val s = store(); CacheTier.useForTests(s)
        val paste = "Meeting notes\n\n" + ("Budget line item about the bridge repair costs. ".repeat(40) + "\n\n").repeat(20)
        val h = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
            .put(JSONObject().put("role", "user").put("content", paste))
            .put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "short"))))
        val done = CacheTier.pageHistory("chat1", h, 0)
        assertEquals(1, done.size)
        done.forEach { (b, ref) -> CacheTier.apply(h, b, ref) }
        val content = h.getJSONObject(1).getString("content")
        assertTrue(content.startsWith("[Cached document doc:"))
        assertTrue(content.length < 1_500)
        assertTrue(content.contains("Meeting notes"))
        val id = CacheTier.docIds(content).single()
        assertEquals(paste, s.sections(id).joinToString("") { s.text(it) })
        // a second pass changes nothing
        assertTrue(CacheTier.pageHistory("chat1", h, 0).isEmpty())
        // the prompt block lists its TOC
        val block = CacheTier.promptBlock("chat1")
        assertTrue(block.contains("CACHED DOCUMENTS") && block.contains("doc:$id"))
        assertTrue(block.length <= CacheTier.BLOCK_CHARS + CacheTier.PROMPT_LINE.length)
        assertEquals("", CacheTier.promptBlock("other"))
    }

    @Test fun cacheToolsReadSearchAndToc() = runBlocking {
        val s = store(); CacheTier.useForTests(s)
        val d = s.ingest("c", "file", "manual.md", "# Manual\n\n" + (1..12).joinToString("") { "## Step $it\n\n" + "Turn the valve $it times. ".repeat(120) + "\n\n" } + "## Warranty\n\nThe warranty lasts 24 months.\n")
        val toc = CacheTier.run("cache_toc", JSONObject().put("doc", d.id), "c")
        assertTrue(toc, toc.contains("§0") && toc.contains("Warranty"))
        val hit = CacheTier.run("cache_search", JSONObject().put("doc", d.id).put("query", "warranty months"), "c")
        val idx = Regex("""§(\d+) """).find(hit)!!.groupValues[1].toInt()
        val read = CacheTier.run("cache_read", JSONObject().put("doc", "doc:${d.id}").put("index", idx), "c")
        assertTrue(read, read.contains("The warranty lasts 24 months."))
        assertTrue(read.contains("(end of document)"))
        val two = CacheTier.run("cache_read", JSONObject().put("doc", d.id).put("index", 0).put("count", 9), "c")
        assertTrue(two.startsWith("§0 ") && two.contains("\n§2 ") && !two.contains("\n§3 "))   // count capped at 3
        assertTrue(CacheTier.run("cache_read", JSONObject().put("doc", "nope").put("index", 0), "c").startsWith("Error: no cached document"))
        assertTrue(CacheTier.run("cache_read", JSONObject().put("doc", d.id).put("index", 999), "c").startsWith("Error"))
        assertTrue(CacheTier.run("cache_summarize", JSONObject().put("doc", d.id), "c", ask = null).contains("need a model"))
    }

    @Test fun summarizeMapsAndReducesThroughTheModel() = runBlocking {
        val s = store(); CacheTier.useForTests(s)
        val d = s.ingest("c", "file", "long.txt", ("Chapter text about rivers. ".repeat(400) + "\n\n").repeat(12))
        val calls = ArrayList<String>()
        val out = CacheTier.run("cache_summarize", JSONObject().put("doc", d.id).put("focus", "rivers"), "c") { p -> calls += p; if (p.startsWith("Below are notes")) "FINAL" else "note" }
        assertEquals("FINAL", out)
        assertTrue(calls.count { it.startsWith("You are reading part") } >= 3)
        assertTrue(calls.any { it.startsWith("Below are notes") })
    }

    @Test fun toolOutputsOverTheLimitAreCached() = runBlocking {
        CacheTier.useForTests(store())
        assertEquals(null, CacheTier.asideTool("c", "web_search", "short"))
        val big = (1..400).joinToString("\n") { "row $it: value ${it * 3} and some description text for the row" }
        val out = CacheTier.asideTool("c", "terminal_run", big)!!
        assertTrue(out.length < 4_000)
        assertTrue(out.contains("cached as doc:"))
        assertTrue(out.startsWith("row 1: value 3"))
    }

    @Test fun gistsComeFromTheModelInBatchesAndStopWhenOffline() = runBlocking {
        val s = store(); CacheTier.useForTests(s)
        val d = s.ingest("c", "file", "g.txt", ("Some section text here. ".repeat(400) + "\n\n").repeat(8))
        CacheTier.gists(d.id) { p -> val n = Regex("### Section").findAll(p).count(); JSONArray((1..n).map { "gist $it" }).toString() }
        assertTrue(s.sections(d.id).all { it.gisted && it.gist.startsWith("gist") })
        val d2 = s.ingest("c", "file", "h.txt", ("Other text. ".repeat(800) + "\n\n").repeat(3))
        CacheTier.gists(d2.id) { throw java.io.IOException("offline") }
        assertTrue(s.sections(d2.id).none { it.gisted })
        assertTrue(s.sections(d2.id).all { it.gist.startsWith("Other text.") })
        assertEquals(listOf("a", "b"), CacheTier.parseGists("```json\n[\"a\",\"b\",\"c\"]\n```", 2))
    }

    @Test fun mapReduceBatchesFitTheBudget() {
        val b = MapReduce.batches(listOf(4_000, 4_000, 4_000, 20_000, 100, 100), maxTok = 3_000)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3), listOf(4, 5)), b)
    }

    // ---------------- long tier ----------------

    @Test fun relevantNotesSkipCoreAndStayInBudget() {
        val hits = listOf(Mind.Mem(1, "person", "Gloria", "Sister, lives in Ndola"), Mind.Mem(2, "preference", "You", "Likes jazz"),
            Mind.Mem(3, "fact", "Car", "Toyota needs service in March"))
        val core = "- [preference] You: Likes jazz\n"
        val b = Mind.relevantLines(hits, core)
        assertTrue(b.startsWith("RELEVANT NOTES"))
        assertTrue(b.contains("#1 [person] Gloria") && b.contains("#3 [fact] Car"))
        assertFalse(b.contains("jazz"))
        assertEquals("", Mind.relevantLines(emptyList(), core))
        assertTrue(Mind.relevantLines(hits, "", max = 120).length <= 120 + 80)
        assertEquals("sister ndola", Mind.relevantQuery("What's my sister in Ndola doing?"))
        assertEquals("", Mind.relevantQuery("ok thanks"))
    }
}
