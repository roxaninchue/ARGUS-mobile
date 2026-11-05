package com.argus.assistant.memory

import com.argus.assistant.store.Sql
import java.io.File
import java.io.RandomAccessFile
import java.io.Reader

/**
 * Tiered memory, cache tier (0.49.0): large text kept verbatim on the phone and read back a section at a time.
 *
 *  - Each document is one file `docs/<id>.txt` (the original text, written section by section as the [Chunker]
 *    streams it) plus rows in its own SQLite file (`cache.db`, never blaze.db: that schema is shared with Desktop/CLI).
 *  - `sections` keeps index, title, chapter, a one-line gist and the byte range of each section in the file;
 *    `sections_fts` (FTS5, contentless, so the body is not stored twice) ranks sections with BM25.
 *  - Only the table of contents (index, title, gist) ever goes into a prompt; [read] returns a section's text.
 *  - Total size is capped ([capBytes]); the least recently used documents go first ([evict]).
 *
 * Every call does disk I/O: callers run it off the main thread (CacheTier wraps it in Dispatchers.IO).
 * Runs on [Sql] so the phone uses the bundled SQLite and JVM tests use sqlite-jdbc. CacheStoreTest.
 */
class CacheStore(private val sql: Sql, val dir: File, var capBytes: Long = CAP_BYTES, private val clock: () -> Long = { System.currentTimeMillis() }) {

    data class Doc(val id: String, val chat: String, val kind: String, val name: String, val chars: Long, val bytes: Long,
                   val sections: Int, val created: Long, val used: Long, val truncated: Boolean = false)

    data class Section(val doc: String, val idx: Int, val title: String, val chapter: String, val level: Int, val gist: String,
                       val gisted: Boolean, val start: Long, val len: Int, val chars: Int)

    data class Hit(val doc: String, val idx: Int, val title: String, val score: Double, val snippet: String)

    /** FTS mode: 2 = contentless FTS5, 1 = plain FTS5 (stores the body), 0 = none (search scans the files). */
    var fts = 0; private set
    private val lock = Any()
    private val docsDir = File(dir, "docs")

    init {
        docsDir.mkdirs()
        sql.exec("PRAGMA busy_timeout = 5000")
        sql.exec("CREATE TABLE IF NOT EXISTS docs(id TEXT PRIMARY KEY, chat TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, " +
            "chars INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL DEFAULT 0, sections INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, " +
            "used INTEGER NOT NULL, truncated INTEGER NOT NULL DEFAULT 0)")
        sql.exec("CREATE INDEX IF NOT EXISTS docs_chat ON docs(chat)")
        sql.exec("CREATE TABLE IF NOT EXISTS sections(id INTEGER PRIMARY KEY AUTOINCREMENT, doc TEXT NOT NULL, idx INTEGER NOT NULL, " +
            "title TEXT NOT NULL, chapter TEXT NOT NULL DEFAULT '', level INTEGER NOT NULL DEFAULT 0, gist TEXT NOT NULL DEFAULT '', " +
            "gisted INTEGER NOT NULL DEFAULT 0, start INTEGER NOT NULL, len INTEGER NOT NULL, chars INTEGER NOT NULL, UNIQUE(doc, idx))")
        sql.exec("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        // 0.51.0 BlazeQuery: one row per fetched topic (short-term, expires), pointing at its cache document
        sql.exec("CREATE TABLE IF NOT EXISTS bq(topic TEXT PRIMARY KEY, doc TEXT NOT NULL, chat TEXT NOT NULL, created INTEGER NOT NULL, " +
            "expires INTEGER NOT NULL, pack TEXT NOT NULL, judged INTEGER NOT NULL DEFAULT 0, hits INTEGER NOT NULL DEFAULT 0)")
        fts = openFts()
    }

    private fun openFts(): Int {
        val have = sql.query("SELECT sql FROM sqlite_master WHERE name='sections_fts'") { it.text(0) }.firstOrNull()
        if (have != null) return if (have.contains("content=''") || have.contains("content=\"\"")) 2 else 1
        return try {
            sql.exec("CREATE VIRTUAL TABLE sections_fts USING fts5(title, body, content='', contentless_delete=1, tokenize='unicode61 remove_diacritics 2')"); 2
        } catch (_: Exception) {
            try { sql.exec("CREATE VIRTUAL TABLE sections_fts USING fts5(title, body, tokenize='unicode61 remove_diacritics 2')"); 1 }
            catch (_: Exception) { 0 }
        }
    }

    fun close() = sql.close()

    fun file(id: String) = File(docsDir, "$id.txt")

    // ---------------- writing ----------------

    /**
     * Streams [reader] into a new document of [chat]. [maxChars] caps one document (the rest is dropped, [Doc.truncated]).
     * Gists start as the section's first sentence ([Chunker.fallbackGist]); a model may improve them later ([setGist]).
     */
    fun ingest(chat: String, kind: String, name: String, reader: Reader, maxChars: Long = MAX_DOC_CHARS,
               chunker: Chunker = Chunker(), id: String = newId()): Doc {
        val f = file(id)
        var chars = 0L; var bytes = 0L; var n = 0; var cut = false
        val now = clock()
        synchronized(lock) {
            sql.exec("INSERT INTO docs(id, chat, kind, name, created, used) VALUES(?,?,?,?,?,?)", id, chat, kind, name.take(120), now, now)
        }
        try {
            f.outputStream().buffered().use { out ->
                chunker.chunk(reader, stop = { (chars >= maxChars).also { if (it) cut = true } }) { p ->
                    val b = p.text.toByteArray(Charsets.UTF_8)
                    out.write(b)
                    addSection(id, n, p, bytes, b.size)
                    bytes += b.size; chars += p.text.length; n++
                }
            }
        } catch (e: Exception) {
            delete(id); throw e
        }
        synchronized(lock) {
            sql.exec("UPDATE docs SET chars=?, bytes=?, sections=?, truncated=? WHERE id=?", chars, bytes, n, if (cut) 1 else 0, id)
        }
        return doc(id)!!
    }

    fun ingest(chat: String, kind: String, name: String, text: String): Doc = ingest(chat, kind, name, text.reader())

    /** Appends [text] to document [id] as new sections (paged-out chat history grows this way). Creates it when missing. */
    fun append(id: String, chat: String, kind: String, name: String, text: String, chunker: Chunker = Chunker()): Doc {
        val existing = doc(id)
        if (existing == null) return ingest(chat, kind, name, text.reader(), id = id, chunker = chunker)
        var bytes = existing.bytes; var chars = existing.chars; var n = existing.sections
        java.io.FileOutputStream(file(id), true).buffered().use { out ->
            chunker.chunk(text.reader()) { p ->
                val b = p.text.toByteArray(Charsets.UTF_8)
                out.write(b)
                addSection(id, n, p, bytes, b.size)
                bytes += b.size; chars += p.text.length; n++
            }
        }
        synchronized(lock) { sql.exec("UPDATE docs SET chars=?, bytes=?, sections=?, used=? WHERE id=?", chars, bytes, n, clock(), id) }
        return doc(id)!!
    }

    private fun addSection(doc: String, idx: Int, p: Chunker.Piece, start: Long, len: Int) = synchronized(lock) {
        sql.tx {
            sql.exec("INSERT INTO sections(doc, idx, title, chapter, level, gist, gisted, start, len, chars) VALUES(?,?,?,?,?,?,0,?,?,?)",
                doc, idx, p.title, p.chapter, p.level, Chunker.fallbackGist(p.text), start, len, p.text.length)
            if (fts > 0) {
                val rowid = sql.query("SELECT last_insert_rowid()") { it.long(0) }.first()
                sql.exec("INSERT INTO sections_fts(rowid, title, body) VALUES(?,?,?)", rowid, p.title, p.text)
            }
        }
    }

    fun setGist(doc: String, idx: Int, gist: String) = synchronized(lock) {
        val g = gist.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().removePrefix("- ").take(200)
        if (g.isNotEmpty()) sql.exec("UPDATE sections SET gist=?, gisted=1 WHERE doc=? AND idx=?", g, doc, idx)
    }

    /** A document found in a message gets its chat (attachments are cached before the chat is known). */
    fun claim(id: String, chat: String) = synchronized(lock) { sql.exec("UPDATE docs SET chat=? WHERE id=? AND chat=''", chat, id) }

    // ---------------- reading ----------------

    private fun docOf(r: Sql.Row) = Doc(r.text(0), r.text(1), r.text(2), r.text(3), r.long(4), r.long(5), r.long(6).toInt(), r.long(7), r.long(8), r.long(9) != 0L)
    private val docCols = "id, chat, kind, name, chars, bytes, sections, created, used, truncated"

    fun doc(id: String): Doc? = sql.query("SELECT $docCols FROM docs WHERE id=?", id) { docOf(it) }.firstOrNull()
    fun docs(chat: String? = null): List<Doc> = if (chat == null) sql.query("SELECT $docCols FROM docs ORDER BY created") { docOf(it) }
        else sql.query("SELECT $docCols FROM docs WHERE chat=? ORDER BY created", chat) { docOf(it) }

    private fun secOf(r: Sql.Row) = Section(r.text(0), r.long(1).toInt(), r.text(2), r.text(3), r.long(4).toInt(), r.text(5), r.long(6) != 0L, r.long(7), r.long(8).toInt(), r.long(9).toInt())
    private val secCols = "doc, idx, title, chapter, level, gist, gisted, start, len, chars"

    fun sections(doc: String): List<Section> = sql.query("SELECT $secCols FROM sections WHERE doc=? ORDER BY idx", doc) { secOf(it) }
    fun section(doc: String, idx: Int): Section? = sql.query("SELECT $secCols FROM sections WHERE doc=? AND idx=?", doc, idx) { secOf(it) }.firstOrNull()
    fun ungisted(doc: String, limit: Int): List<Section> =
        sql.query("SELECT $secCols FROM sections WHERE doc=? AND gisted=0 ORDER BY idx LIMIT ?", doc, limit) { secOf(it) }

    /** The text of one section, read from its byte range (the rest of the file is never loaded). */
    fun text(s: Section): String {
        val f = file(s.doc)
        if (!f.isFile) return ""
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(s.start)
            val b = ByteArray(s.len)
            raf.readFully(b)
            return String(b, Charsets.UTF_8)
        }
    }

    /** Marks [id] as just used (LRU). */
    fun touch(id: String) = synchronized(lock) { sql.exec("UPDATE docs SET used=? WHERE id=?", clock(), id) }

    // ---------------- search ----------------

    /** BM25-ranked sections matching [query] in [doc] (null = every document of [chat], or all when [chat] is null too). */
    fun search(query: String, doc: String? = null, chat: String? = null, limit: Int = 8): List<Hit> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        val rows: List<Triple<Section, Double, Unit>> = if (fts > 0) try {
            val q = terms.joinToString(" OR ") { "\"$it\"*" }
            val where = when { doc != null -> " AND s.doc=?"; chat != null -> " AND s.doc IN (SELECT id FROM docs WHERE chat=?)"; else -> "" }
            val args = listOfNotNull(q, doc ?: chat, limit)
            sql.query("SELECT s.doc, s.idx, s.title, s.chapter, s.level, s.gist, s.gisted, s.start, s.len, s.chars, bm25(sections_fts, 4.0, 1.0) AS r " +
                "FROM sections_fts JOIN sections s ON s.id = sections_fts.rowid WHERE sections_fts MATCH ?$where ORDER BY r LIMIT ?", *args.toTypedArray()) { r ->
                Triple(secOf(r), -(r.text(10).toDoubleOrNull() ?: 0.0), Unit)
            }
        } catch (_: Exception) { scan(terms, doc, chat, limit) } else scan(terms, doc, chat, limit)
        return rows.map { (s, score, _) -> Hit(s.doc, s.idx, s.title, score, snippet(text(s), terms)) }
    }

    /** No FTS on this phone: score each section by how often the terms occur (slow, but correct). */
    private fun scan(terms: List<String>, doc: String?, chat: String?, limit: Int): List<Triple<Section, Double, Unit>> {
        val ds = if (doc != null) listOfNotNull(doc(doc)) else docs(chat)
        val out = ArrayList<Triple<Section, Double, Unit>>()
        for (d in ds) for (s in sections(d.id)) {
            val t = (s.title + "\n" + text(s)).lowercase()
            var score = 0.0
            for (w in terms) { var i = t.indexOf(w); while (i >= 0) { score += 1; i = t.indexOf(w, i + w.length) } }
            if (score > 0) out += Triple(s, score, Unit)
        }
        return out.sortedByDescending { it.second }.take(limit)
    }

    // ---------------- deleting ----------------

    fun delete(id: String) = synchronized(lock) {
        if (fts == 2 || fts == 1) try {
            sql.exec("DELETE FROM sections_fts WHERE rowid IN (SELECT id FROM sections WHERE doc=?)", id)
        } catch (_: Exception) { }
        sql.exec("DELETE FROM sections WHERE doc=?", id)
        sql.exec("DELETE FROM docs WHERE id=?", id)
        sql.exec("DELETE FROM bq WHERE doc=?", id)
        file(id).delete()
        Unit
    }

    /** Every document of a deleted chat. Returns how many went. */
    fun deleteChat(chat: String): Int { val l = docs(chat); l.forEach { delete(it.id) }; return l.size }

    fun totalBytes(): Long = sql.query("SELECT COALESCE(SUM(bytes),0) FROM docs") { it.long(0) }.first()

    /**
     * LRU eviction: while the cache is over [capBytes], the least recently used document goes (never one in [keep]).
     * Returns the ids removed.
     */
    fun evict(keep: Set<String> = emptySet()): List<String> {
        val gone = ArrayList<String>()
        var total = totalBytes()
        if (total <= capBytes) return gone
        for (d in sql.query("SELECT $docCols FROM docs ORDER BY used, created") { docOf(it) }) {
            if (total <= capBytes) break
            if (d.id in keep) continue
            delete(d.id); gone += d.id; total -= d.bytes
        }
        return gone
    }

    // ---------------- 0.51.0 BlazeQuery topics (short-term, with TTL) ----------------

    /** A BlazeQuery topic: [pack] = the judged evidence as JSON (BlazeQuery.encode), [doc] = the document with the full text. */
    data class Bq(val topic: String, val doc: String, val chat: String, val created: Long, val expires: Long, val pack: String, val judged: Boolean, val hits: Int)

    private fun bqOf(r: Sql.Row) = Bq(r.text(0), r.text(1), r.text(2), r.long(3), r.long(4), r.text(5), r.long(6) != 0L, r.long(7).toInt())
    private val bqCols = "topic, doc, chat, created, expires, pack, judged, hits"

    /** Stores (or replaces) [topic]; the previous document of the same topic is deleted (dedupe). */
    fun bqPut(topic: String, doc: String, chat: String, ttlMs: Long, pack: String, judged: Boolean) {
        val old = bqRow(topic)
        val now = clock()
        synchronized(lock) {
            sql.exec("INSERT OR REPLACE INTO bq(topic, doc, chat, created, expires, pack, judged, hits) VALUES(?,?,?,?,?,?,?,?)",
                topic, doc, chat, now, now + ttlMs, pack, if (judged) 1 else 0, old?.hits ?: 0)
        }
        if (old != null && old.doc != doc && old.doc.isNotEmpty()) delete(old.doc)
    }

    private fun bqRow(topic: String): Bq? = sql.query("SELECT $bqCols FROM bq WHERE topic=?", topic) { bqOf(it) }.firstOrNull()

    /** [topic] if it has not expired (counts a hit). */
    fun bqGet(topic: String): Bq? {
        val b = bqRow(topic)?.takeIf { it.expires > clock() } ?: return null
        synchronized(lock) { sql.exec("UPDATE bq SET hits=hits+1 WHERE topic=?", topic) }
        touch(b.doc)
        return b
    }

    /** Every topic still alive, newest first. */
    fun bqAlive(): List<Bq> = sql.query("SELECT $bqCols FROM bq WHERE expires>? ORDER BY created DESC LIMIT 200", clock()) { bqOf(it) }

    /** Drops expired topics and their documents. Returns how many went. */
    fun bqExpire(): Int {
        val gone = sql.query("SELECT $bqCols FROM bq WHERE expires<=?", clock()) { bqOf(it) }
        for (b in gone) { synchronized(lock) { sql.exec("DELETE FROM bq WHERE topic=?", b.topic) }; if (b.doc.isNotEmpty()) delete(b.doc) }
        return gone.size
    }

    companion object {
        /** Whole cache on the phone (LRU beyond this). */
        const val CAP_BYTES = 48L * 1024 * 1024
        /** One document (a bigger file is cut here and says so). */
        const val MAX_DOC_CHARS = 16L * 1024 * 1024

        fun newId(): String = "d" + java.lang.Long.toHexString(System.nanoTime() xor (Math.random() * Long.MAX_VALUE).toLong()).takeLast(8).padStart(8, '0')

        /** Query words, lower-case letters/digits only (safe inside an FTS5 string), at most 10. */
        fun terms(q: String): List<String> = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.distinct().take(10)

        /** ~[n] chars around the first term found. */
        fun snippet(text: String, terms: List<String>, n: Int = 240): String {
            val low = text.lowercase()
            val at = terms.map { low.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
            val from = Chunker.safe(text, (at - n / 3).coerceAtLeast(0))
            val to = Chunker.safe(text, minOf(text.length, from + n))
            return (if (from > 0) "…" else "") + text.substring(from, to).replace(Regex("\\s+"), " ").trim() + (if (to < text.length) "…" else "")
        }
    }
}
