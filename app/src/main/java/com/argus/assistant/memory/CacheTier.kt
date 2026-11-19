package com.blaze.assistant.memory

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Reader

/**
 * Tiered memory, cache tier facade (0.49.0). See docs/MEMORY.md.
 *
 *  - [pageHistory]: a pasted text, a text attachment or anything else over [PASTE_OVER] characters in the history is
 *    stored in the cache and replaced by a short reference (name, size, first lines). The model sees the TOC
 *    ([promptBlock]) and reads what it needs with the tools ([tools] / [run]).
 *  - [asideTool]: a tool result over [TOOL_OVER] characters goes the same way.
 *  - [pageOut]: older chat turns that fold into the rolling summary are kept verbatim in doc `chat-<id>`.
 *  - Gists: each section starts with its first sentence; [gists] asks the cheap 'quick' route for one-line gists in
 *    the background (offline it simply keeps the first sentence).
 *
 * Every store call runs on Dispatchers.IO; nothing here touches the main thread.
 */
object CacheTier {
    /** A message over this many characters (~3k tokens) is cached instead of sent. */
    const val PASTE_OVER = 12_000
    /** A tool result over this many characters is cached (smaller ones keep LongChat's artifact aside). */
    const val TOOL_OVER = 16_000
    /** The CACHED DOCUMENTS block in the system prompt (~600 tokens). */
    const val BLOCK_CHARS = 2_400
    /** Characters of the start of a cached text kept in the message itself. */
    const val HEAD_CHARS = 600
    /** Sections per document that get a model-written gist (the rest keep their first sentence). */
    const val GIST_MAX = 48
    const val GIST_BATCH = 6

    val names = setOf("cache_toc", "cache_read", "cache_search", "cache_summarize")

    const val PROMPT_LINE = "\nCACHED DOCUMENTS\n- Big pastes, files and long tool outputs are stored on the phone, not in this prompt. " +
        "You see only their table of contents. Read what you need: cache_search to find sections, cache_read for their text " +
        "(a few sections at a time), cache_toc for a chapter's sections, cache_summarize for whole-document summaries. " +
        "Quote from what you read; never guess the parts you did not read.\n"

    @Volatile private var store: CacheStore? = null
    @Volatile private var root: File? = null
    private val openLock = Mutex()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The model call for gists and summaries (set by the agent: the quick route). Null = no model (offline/tests). */
    @Volatile var ask: (suspend (String) -> String)? = null

    fun init(ctx: Context) { if (root == null) root = File(ctx.applicationContext.filesDir, "cache") }

    /** Tests: use this store. */
    fun useForTests(s: CacheStore?) { store = s; if (s != null) root = s.dir }

    suspend fun store(): CacheStore? = store ?: withContext(Dispatchers.IO) {
        openLock.withLock {
            store ?: run {
                val r = root ?: return@run null
                try {
                    r.mkdirs()
                    CacheStore(com.blaze.assistant.store.AndroidSql(File(r, "cache.db").absolutePath), r).also { store = it }
                } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("CacheTier: open", e); null }
            }
        }
    }

    // ---------------- references ----------------

    private val refRe = Regex("""\[Cached document doc:([A-Za-z0-9_-]+)""")

    fun ref(d: CacheStore.Doc, head: String): String = "[Cached document doc:${d.id} \"${d.name}\" · ${d.sections} sections · ~${CacheToc.tok(d.chars)} tokens. " +
        "It is stored on the phone, not in this message: its table of contents is under CACHED DOCUMENTS; read it with cache_search / cache_read.]\n" +
        "Starts with:\n" + head.take(Chunker.safe(head, minOf(head.length, HEAD_CHARS))).trimEnd() + (if (head.length > HEAD_CHARS) " …" else "")

    fun docIds(text: String): List<String> = refRe.findAll(text).map { it.groupValues[1] }.toList()

    /** Pure: [h] mentions a cached document (a paged message or a cached tool output), so its turns need the cache tools. */
    fun referenced(h: JSONArray): Boolean {
        for (i in 0 until h.length()) {
            val m = h.optJSONObject(i) ?: continue
            val t = when (val c = m.opt("content")) { is String -> c; is JSONArray -> c.toString(); else -> "" }
            if (t.contains("[Cached document doc:") || t.contains("cached as doc:")) return true
        }
        return false
    }

    /** One text in a history message that should be cached: message [index], content part [part] (-1 = string content). */
    data class Big(val index: Int, val part: Int, val text: String)

    /** Pure: the big texts in history[from…] (user and system messages; tool results are cached when they arrive). */
    fun candidates(h: JSONArray, from: Int, over: Int = PASTE_OVER): List<Big> {
        val out = ArrayList<Big>()
        for (i in from.coerceAtLeast(0) until h.length()) {
            val m = h.optJSONObject(i) ?: continue
            if (m.optString("role") !in setOf("user", "system")) continue
            when (val c = m.opt("content")) {
                is String -> if (c.length > over && !c.startsWith("[Cached document")) out += Big(i, -1, c)
                is JSONArray -> for (k in 0 until c.length()) {
                    val p = c.optJSONObject(k) ?: continue
                    val t = p.optString("text")
                    if (p.optString("type") == "text" && t.length > over && !t.startsWith("[Cached document")) out += Big(i, k, t)
                }
            }
        }
        return out
    }

    /** Pure: [h] with [b]'s text replaced by [replacement]. */
    fun apply(h: JSONArray, b: Big, replacement: String) {
        val m = h.optJSONObject(b.index) ?: return
        if (b.part < 0) { if (m.opt("content") is String) m.put("content", replacement); return }
        m.optJSONArray("content")?.optJSONObject(b.part)?.put("text", replacement)
    }

    /** A short name for a pasted text: a file header ("File x.txt:") or its first words. */
    fun nameOf(text: String): String {
        Regex("""^File ([^\n:]{1,100}):""").find(text)?.let { return it.groupValues[1] }
        return "Pasted text: " + Chunker.firstWords(text, 40)
    }

    /**
     * Caches the big texts in history[from…] for [chat]; returns (index-part → reference) for the caller to apply on
     * its own thread, plus the ids of the documents now referenced (attachments cached earlier are claimed).
     */
    suspend fun pageHistory(chat: String, h: JSONArray, from: Int): List<Pair<Big, String>> = withContext(Dispatchers.IO) {
        val s = store() ?: return@withContext emptyList()
        val out = ArrayList<Pair<Big, String>>()
        for (b in candidates(h, from)) {
            try {
                val d = s.ingest(chat, "paste", nameOf(b.text), b.text)
                out += b to ref(d, b.text.substringAfter(":\n", b.text).let { if (it.length < 40) b.text else it })
                startGists(d.id)
            } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("CacheTier: page", e) }
        }
        // documents cached before the chat was known (text attachments) join this chat
        for (i in from.coerceAtLeast(0) until h.length()) {
            val t = h.optJSONObject(i)?.let { com.blaze.assistant.agent.LongChat.text(it) } ?: continue
            for (id in docIds(t)) try { s.claim(id, chat) } catch (_: Exception) { }
        }
        if (out.isNotEmpty()) try { s.evict(out.map { docIds(it.second) }.flatten().toSet()) } catch (_: Exception) { }
        out
    }

    /** Streams a big attachment into the cache (chat claimed later). Returns the reference text, or null on failure. */
    suspend fun cacheAttachment(name: String, reader: Reader): String? = withContext(Dispatchers.IO) {
        val s = store() ?: return@withContext null
        try {
            val d = s.ingest("", "file", name, reader)
            val head = s.sections(d.id).firstOrNull()?.let { s.text(it) }.orEmpty()
            startGists(d.id)
            s.evict(setOf(d.id))
            ref(d, head)
        } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("CacheTier: attachment", e); null }
    }

    /** A tool result over [TOOL_OVER]: cached, and the model gets its head, the TOC and the doc id. Null = keep as is. */
    suspend fun asideTool(chat: String, tool: String, result: String): String? {
        if (result.length <= TOOL_OVER) return null
        return withContext(Dispatchers.IO) {
            val s = store() ?: return@withContext null
            try {
                val d = s.ingest(chat, "tool", "Output of $tool", result)
                startGists(d.id)
                s.evict(setOf(d.id))
                result.take(Chunker.safe(result, 1_200)) + "\n… [${result.length - 1_200} more characters cached as doc:${d.id}]\n" +
                    CacheToc.toc(d, s.sections(d.id), maxChars = 1_500) + "\nRead more with cache_read {doc:\"${d.id}\", index} or cache_search."
            } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("CacheTier: aside", e); null }
        }
    }

    /** Older turns folding into the summary are kept verbatim (doc `chat-<id>`). */
    suspend fun pageOut(chat: String, text: String) {
        if (text.isBlank()) return
        withContext(Dispatchers.IO) {
            val s = store() ?: return@withContext
            try { s.append(ChatTier.docId(chat), chat, "history", "Earlier turns of this chat", text); s.evict(setOf(ChatTier.docId(chat))) }
            catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("CacheTier: pageOut", e) }
        }
    }

    /** The CACHED DOCUMENTS block for [chat] ("" when it has none). */
    suspend fun promptBlock(chat: String, maxChars: Int = BLOCK_CHARS): String = withContext(Dispatchers.IO) {
        val s = store() ?: return@withContext ""
        val ds = try { s.docs(chat) } catch (_: Exception) { emptyList() }
        if (ds.isEmpty()) return@withContext ""
        val sb = StringBuilder(PROMPT_LINE)
        // newest first; the paged-out history doc is listed by its header only
        for (d in ds.sortedByDescending { it.created }) {
            val room = maxChars - sb.length
            if (room < 120) { sb.append("… more documents: cache_search doc=all\n"); break }
            val t = if (d.kind == "history") CacheToc.header(d) + " (earlier turns; search_chat or cache_search)"
                else CacheToc.toc(d, s.sections(d.id), maxChars = minOf(room, maxChars / 2))
            sb.append(if (t.length <= room) t else CacheToc.header(d) + " (cache_toc for its contents)").append('\n')
        }
        sb.toString()
    }

    suspend fun hasDocs(chat: String): Boolean = withContext(Dispatchers.IO) { try { store()?.docs(chat)?.isNotEmpty() == true } catch (_: Exception) { false } }

    suspend fun deleteChat(chat: String) = withContext(Dispatchers.IO) { try { store()?.deleteChat(chat) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("CacheTier: delete", e) } }

    // ---------------- gists ----------------

    fun gistPrompt(parts: List<Pair<Int, String>>): String = "For each numbered section below, write a one-line gist (at most 15 words) saying what it covers. " +
        "Answer ONLY a JSON array of strings, one per section, in order.\n\n" +
        parts.joinToString("\n\n") { (i, t) -> "### Section $i\n" + t.take(1_500) }

    /** Parses the gist answer: a JSON array of strings (code fences and text around it tolerated). */
    fun parseGists(raw: String, n: Int): List<String> {
        val s = raw.substringAfterLast("</think>")
        val a = s.indexOf('['); val b = s.lastIndexOf(']')
        if (a < 0 || b <= a) return emptyList()
        return try { val arr = JSONArray(s.substring(a, b + 1)); (0 until minOf(n, arr.length())).map { arr.optString(it).trim() } } catch (_: Exception) { emptyList() }
    }

    private fun startGists(doc: String) { val f = ask ?: return; bg.launch { try { gists(doc, f) } catch (_: Throwable) { } } }

    /** Model gists for the first [GIST_MAX] sections of [doc], [GIST_BATCH] per call; stops at the first failure (offline). */
    suspend fun gists(doc: String, f: suspend (String) -> String) {
        val s = store() ?: return
        var done = 0
        while (done < GIST_MAX) {
            val batch = withContext(Dispatchers.IO) { s.ungisted(doc, GIST_BATCH) }
            if (batch.isEmpty()) return
            val parts = withContext(Dispatchers.IO) { batch.map { it.idx to s.text(it) } }
            val got = try { parseGists(f(gistPrompt(parts)), batch.size) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return }
            if (got.isEmpty()) return
            withContext(Dispatchers.IO) { batch.zip(got).forEach { (sec, g) -> if (g.isNotBlank()) s.setGist(doc, sec.idx, g) } }
            done += batch.size
        }
    }

    // ---------------- tools ----------------

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun int(d: String) = JSONObject().put("type", "integer").put("description", d)

    fun tools(): List<JSONObject> = listOf(
        fn("cache_toc", "Table of contents of a cached document (big paste, file or long output): sections with titles and gists. chapter=k lists one chapter of a long document.",
            JSONObject().put("doc", str("Document id, e.g. d1a2b3c4")).put("chapter", int("Optional chapter number from the top-level TOC")), listOf("doc")),
        fn("cache_read", "Read the full text of sections of a cached document, by index from its TOC. count reads that many sections in a row (max 3).",
            JSONObject().put("doc", str("Document id")).put("index", int("Section index (§n)")).put("count", int("How many sections, default 1")), listOf("doc", "index")),
        fn("cache_search", "Search cached documents (BM25) for sections about something; doc=all searches every document of this chat.",
            JSONObject().put("doc", str("Document id, or all")).put("query", str("Words to look for")), listOf("doc", "query")),
        fn("cache_summarize", "Summarise a whole cached document (or answer a question over all of it) by reading every section in batches. Slower: use it only when the whole document matters.",
            JSONObject().put("doc", str("Document id")).put("focus", str("Optional question or focus")), listOf("doc")),
    )

    /** Runs a cache tool for [chat]. [ask] is the model call cache_summarize uses (null = not available). */
    suspend fun run(name: String, args: JSONObject, chat: String, ask: (suspend (String) -> String)? = this.ask): String {
        val s = store() ?: return "The document cache isn't available on this phone."
        val docArg = args.optString("doc").trim().removePrefix("doc:").trim()
        fun docOrErr(): Pair<CacheStore.Doc?, String> {
            val d = s.doc(docArg) ?: return null to "Error: no cached document \"$docArg\". " + s.docs(chat).joinToString(", ") { "doc:${it.id} (${it.name})" }.ifEmpty { "This chat has none." }
            return d to ""
        }
        return when (name) {
            "cache_toc" -> withContext(Dispatchers.IO) {
                val (d, err) = docOrErr(); if (d == null) return@withContext err
                s.touch(d.id); CacheToc.toc(d, s.sections(d.id), args.optInt("chapter", 0))
            }
            "cache_read" -> withContext(Dispatchers.IO) {
                val (d, err) = docOrErr(); if (d == null) return@withContext err
                val from = args.optInt("index", 0).coerceAtLeast(0)
                val count = args.optInt("count", 1).coerceIn(1, 3)
                if (from >= d.sections) return@withContext "Error: doc:${d.id} has sections §0–§${d.sections - 1}."
                s.touch(d.id)
                val sb = StringBuilder()
                for (i in from until minOf(d.sections, from + count)) {
                    val sec = s.section(d.id, i) ?: continue
                    sb.append("§${sec.idx} ${sec.title}").append(if (sec.chapter.isNotBlank() && sec.chapter != sec.title) " (in ${sec.chapter})" else "").append("\n")
                    sb.append(s.text(sec).trimEnd()).append("\n\n")
                }
                val next = from + count
                sb.append(if (next < d.sections) "(next: §$next of ${d.sections - 1})" else "(end of document)")
                sb.toString()
            }
            "cache_search" -> withContext(Dispatchers.IO) {
                val q = args.optString("query")
                val all = docArg.isEmpty() || docArg.equals("all", true)
                if (!all && s.doc(docArg) == null) return@withContext docOrErr().second
                val hits = s.search(q, doc = if (all) null else docArg, chat = if (all) chat else null)
                if (hits.isEmpty()) "Nothing in ${if (all) "this chat's documents" else "doc:$docArg"} matches \"$q\"."
                else hits.joinToString("\n") { "doc:${it.doc} §${it.idx} ${it.title}: ${it.snippet}" } + "\ncache_read {doc, index} returns a section in full."
            }
            "cache_summarize" -> {
                val f = ask ?: return "Summaries need a model connection; read sections with cache_read instead."
                val (d, err) = withContext(Dispatchers.IO) { docOrErr() }; if (d == null) return err
                val secs = withContext(Dispatchers.IO) { s.sections(d.id) }
                val (text, n) = MapReduce.run(d.name, args.optString("focus"), secs.map { it.chars },
                    texts = { idx -> withContext(Dispatchers.IO) { idx.map { s.text(secs[it]) } } }, ask = f)
                text + if (n < secs.size) "\n(Read §0–§${n - 1} of ${secs.size}; the rest was not read. Use cache_search for later parts.)" else ""
            }
            else -> "Unknown tool"
        }
    }
}
