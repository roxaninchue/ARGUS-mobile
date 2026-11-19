package com.blaze.assistant.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Terminal checkpoints (rollbacks), pure JVM so it is tested without a phone.
 *
 * A checkpoint is a snapshot of one or more folders ("scopes": the phone shell's home, a Linux's user folders, a whole
 * Linux). It is stored like git does it: each file's content once, deflated, under objects/ by its SHA-256, and one
 * index per scope listing every path with its kind, exec bit, size, mtime and hash. Taking a checkpoint is incremental:
 * a file whose size and mtime match the newest index of its scope reuses that hash without being read, so a checkpoint
 * after a small change costs a stat walk plus the changed files. A checkpoint that would equal the newest one is not
 * taken twice ([snapshot] returns the existing one with created=false).
 *
 * [restore] puts every scope back exactly: changed files rewritten, new files deleted, deleted files brought back, and
 * it first takes a checkpoint of the current state, so a rollback can itself be rolled back. Files over [maxFileBytes]
 * are listed as skipped and left alone by a restore (never deleted, never rewritten).
 *
 * Layout: root/objects/ab/cdef…, root/cp/<id>/meta, root/cp/<id>/<scope>.idx, root/seq.
 */
class CheckpointStore(
    val root: File,
    val maxAuto: Int = 25,
    val maxManual: Int = 25,
    val maxFileBytes: Long = 64L * 1024 * 1024,
    /** New content one snapshot may add; past it, further new or changed files are left out (kind 's'), like big files. */
    val maxNewBytes: Long = 256L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * A folder to snapshot. [excludes] are paths relative to [dir] ("proc", "root/phone") skipped with all they hold;
     * [skipNames] are folder names skipped at any depth (caches that rebuild themselves: ".gradle", "node_modules").
     */
    data class Scope(val name: String, val dir: File, val excludes: Set<String> = emptySet(), val skipNames: Set<String> = emptySet())

    data class Entry(val path: String, val kind: Char, val exec: Boolean, val size: Long, val mtime: Long, val ref: String) {
        // kind: 'f' file (ref = sha256), 'd' folder, 'l' symlink (ref = target), 's' skipped big file (ref = "")
        fun line() = "$kind\t${if (exec) 1 else 0}\t$size\t$mtime\t${esc(ref)}\t${esc(path)}"
        fun sameStat(o: Entry) = kind == o.kind && size == o.size && mtime == o.mtime
        fun sameContent(o: Entry) = kind == o.kind && ref == o.ref && (kind != 'f' || exec == o.exec)
    }

    data class Checkpoint(
        val id: String, val created: Long, val label: String, val reason: String, val auto: Boolean,
        val scopes: List<String>, val files: Int, val bytes: Long, val skipped: Int,
    )

    data class Snap(val checkpoint: Checkpoint, val created: Boolean)

    data class Diff(val added: List<String>, val changed: List<String>, val deleted: List<String>) {
        val empty get() = added.isEmpty() && changed.isEmpty() && deleted.isEmpty()
        fun summary(max: Int = 20): String {
            if (empty) return "No changes."
            val sb = StringBuilder("${changed.size} changed, ${added.size} new, ${deleted.size} deleted since then")
            fun part(tag: String, l: List<String>) { l.take(max).forEach { sb.append("\n").append(tag).append(' ').append(it) }; if (l.size > max) sb.append("\n").append(tag).append(" …and ${l.size - max} more") }
            part("M", changed); part("+", added); part("-", deleted)
            return sb.toString()
        }
    }

    data class Restored(val rewritten: Int, val deleted: Int, val unchanged: Int, val safety: Checkpoint?, val errors: List<String>)

    private val rootAbs = root.absolutePath
    private val objects get() = File(root, "objects")
    private val cps get() = File(root, "cp")

    // ---------- listing ----------

    @Synchronized fun list(): List<Checkpoint> = (cps.listFiles() ?: emptyArray())
        .mapNotNull { readMeta(it) }
        .sortedWith(compareByDescending<Checkpoint> { it.created }.thenByDescending { seqOf(it.id) })

    @Synchronized fun get(id: String): Checkpoint? = readMeta(File(cps, id))

    fun latest(): Checkpoint? = list().firstOrNull()

    // ---------- snapshot ----------

    @Synchronized fun snapshot(scopes: List<Scope>, label: String = "", reason: String = "", auto: Boolean = false): Snap {
        require(scopes.isNotEmpty()) { "no scopes" }
        objects.mkdirs(); cps.mkdirs()
        val newest = list()
        val indexes = LinkedHashMap<String, List<Entry>>()
        for (s in scopes) {
            val prevCp = newest.firstOrNull { s.name in it.scopes }
            val prev = prevCp?.let { readIndex(it.id, s.name) }?.associateBy { it.path } ?: emptyMap()
            indexes[s.name] = walk(s, prev, trustBefore(prevCp?.id, s.name))
        }
        // Same scopes, same content as the newest checkpoint: don't take it twice.
        val last = newest.firstOrNull()
        if (last != null && last.scopes.toSet() == indexes.keys && indexes.all { (n, idx) -> same(readIndex(last.id, n), idx) })
            return Snap(last, false)
        val id = "cp-" + nextSeq()
        val dir = File(cps, id + ".tmp").apply { deleteRecursively(); mkdirs() }
        indexes.forEach { (n, idx) -> File(dir, "$n.idx").writeText(idx.joinToString("\n") { it.line() }) }
        val all = indexes.values.flatten()
        val cp = Checkpoint(id, clock(), label.trim().take(120), reason.trim().take(200), auto, indexes.keys.toList(),
            all.count { it.kind == 'f' }, all.filter { it.kind == 'f' }.sumOf { it.size }, all.count { it.kind == 's' })
        File(dir, "meta").writeText(meta(cp))
        if (!dir.renameTo(File(cps, id))) throw IllegalStateException("couldn't save checkpoint $id")
        prune()
        return Snap(cp, true)
    }

    private fun same(a: List<Entry>, b: List<Entry>): Boolean {
        if (a.size != b.size) return false
        val m = a.associateBy { it.path }
        return b.all { e -> m[e.path]?.let { it.sameContent(e) } == true }
    }

    /**
     * Racy-git rule: a size+mtime match only proves "unchanged" for files last modified clearly before that index was
     * written; a file touched in the same second (same size, same coarse mtime) is hashed again.
     */
    private fun trustBefore(id: String?, scope: String): Long =
        id?.let { File(File(cps, it), "$scope.idx").lastModified() - RACY_MS } ?: Long.MIN_VALUE

    private fun walk(s: Scope, prev: Map<String, Entry>, trust: Long): List<Entry> {
        val out = ArrayList<Entry>()
        if (!s.dir.isDirectory) return out
        val base = s.dir.toPath()
        var budget = maxNewBytes
        fun visit(dir: File, rel: String) {
            val kids = dir.listFiles() ?: return
            kids.sortBy { it.name }
            for (f in kids) {
                val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (excluded(r, s.excludes) || f.absolutePath == rootAbs) continue
                val p = f.toPath()
                if (Files.isSymbolicLink(p)) {
                    val t = try { Files.readSymbolicLink(p).toString() } catch (_: Exception) { continue }
                    out += Entry(r, 'l', false, 0, 0, t)
                } else if (f.isDirectory) {
                    if (f.name in s.skipNames) continue
                    out += Entry(r, 'd', false, 0, 0, "")
                    visit(f, r)
                } else if (f.isFile) {
                    val size = f.length(); val mtime = f.lastModified(); val exec = f.canExecute()
                    if (size > maxFileBytes) { out += Entry(r, 's', exec, size, mtime, ""); continue }
                    val old = prev[r]
                    val reuse = old != null && old.kind == 'f' && old.size == size && old.mtime == mtime && mtime < trust && has(old.ref)
                    if (!reuse && size > budget) { out += Entry(r, 's', exec, size, mtime, ""); continue }
                    val hash = if (reuse) old!!.ref
                        else try { val h = store(f); budget -= size; h } catch (_: Exception) { continue }   // unreadable (permissions): leave it out
                    out += Entry(r, 'f', exec, size, mtime, hash)
                }
            }
        }
        visit(base.toFile(), "")
        return out
    }

    private fun excluded(rel: String, ex: Set<String>) = ex.any { rel == it || rel.startsWith("$it/") }

    private fun has(hash: String) = hash.length > 2 && File(objects, hash.take(2) + "/" + hash.drop(2)).exists()

    /** Hash [f] and store its content once; returns the hash. */
    private fun store(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        val obj = File(objects, hash.take(2) + "/" + hash.drop(2))
        if (!obj.exists()) {
            obj.parentFile?.mkdirs()
            val tmp = File(obj.path + ".tmp")
            f.inputStream().use { i -> DeflaterOutputStream(tmp.outputStream(), Deflater(1)).use { o -> i.copyTo(o, 64 * 1024) } }
            if (!tmp.renameTo(obj)) { tmp.delete(); if (!obj.exists()) throw IllegalStateException("store failed") }
        }
        return hash
    }

    // ---------- diff / restore ----------

    /** What changed in [scopes] since checkpoint [id] (current state vs the checkpoint). */
    @Synchronized fun diff(id: String, scopes: List<Scope>): Diff {
        val cp = get(id) ?: throw IllegalArgumentException("No checkpoint $id.")
        val added = ArrayList<String>(); val changed = ArrayList<String>(); val deleted = ArrayList<String>()
        for (s in scopes.filter { it.name in cp.scopes }) {
            val then = readIndex(id, s.name).associateBy { it.path }
            val now = walkStatOnly(s, then, trustBefore(id, s.name))
            val pre = if (scopes.count { it.name in cp.scopes } > 1) s.name + ":" else ""
            for ((p, e) in now) { val t = then[p]; if (t == null) { if (e.kind != 'd') added += pre + p } else if (!t.sameContent(e) && e.kind != 'd' && t.kind != 's') changed += pre + p }
            for ((p, t) in then) if (p !in now && t.kind != 'd') deleted += pre + p
        }
        return Diff(added.sorted(), changed.sorted(), deleted.sorted())
    }

    /** A stat walk that hashes only the files whose size/mtime differ from [then] (nothing is stored). */
    private fun walkStatOnly(s: Scope, then: Map<String, Entry>, trust: Long): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        fun visit(dir: File, rel: String) {
            for (f in dir.listFiles() ?: return) {
                val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (excluded(r, s.excludes) || f.absolutePath == rootAbs) continue
                val p = f.toPath()
                when {
                    Files.isSymbolicLink(p) -> out[r] = Entry(r, 'l', false, 0, 0, try { Files.readSymbolicLink(p).toString() } catch (_: Exception) { "" })
                    f.isDirectory -> { if (f.name !in s.skipNames) { out[r] = Entry(r, 'd', false, 0, 0, ""); visit(f, r) } }
                    f.isFile -> {
                        val size = f.length(); val mtime = f.lastModified(); val exec = f.canExecute()
                        val t = then[r]
                        out[r] = when {
                            size > maxFileBytes -> Entry(r, 's', exec, size, mtime, "")
                            t != null && t.kind == 'f' && t.size == size && t.mtime == mtime && mtime < trust -> Entry(r, 'f', exec, size, mtime, t.ref)
                            else -> Entry(r, 'f', exec, size, mtime, try { hashOnly(f) } catch (_: Exception) { "?" })
                        }
                    }
                }
            }
        }
        if (s.dir.isDirectory) visit(s.dir, "")
        return out
    }

    private fun hashOnly(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Put [scopes] back to checkpoint [id]. Only scopes the checkpoint holds are touched. With [paths], only those paths
     * (and what is under them) are restored. A checkpoint of the current state is taken first unless [safety] is false.
     */
    @Synchronized fun restore(id: String, scopes: List<Scope>, paths: List<String> = emptyList(), safety: Boolean = true): Restored {
        val cp = get(id) ?: throw IllegalArgumentException("No checkpoint $id.")
        val targets = scopes.filter { it.name in cp.scopes }
        require(targets.isNotEmpty()) { "Checkpoint $id holds ${cp.scopes.joinToString()}, none of which is here now." }
        val before = if (safety) snapshot(targets, "Before rollback to $id", "taken automatically before rolling back", auto = true).checkpoint else null
        var rewritten = 0; var deleted = 0; var unchanged = 0
        val errors = ArrayList<String>()
        val want = paths.map { it.trim().trim('/') }.filter { it.isNotEmpty() }
        fun inPaths(scope: String, rel: String) = want.isEmpty() || want.any { w ->
            val p = if (w.startsWith("$scope:")) w.removePrefix("$scope:") else w
            rel == p || rel.startsWith("$p/") || p.startsWith("$rel/")
        }
        for (s in targets) {
            val then = readIndex(id, s.name)
            val thenMap = then.associateBy { it.path }
            val now = walkStatOnly(s, thenMap, trustBefore(id, s.name))
            // 1. Delete what didn't exist then (deepest first). Skipped big files are never deleted.
            for (p in now.keys.sortedByDescending { it.length }) {
                if (p in thenMap || !inPaths(s.name, p)) continue
                val e = now.getValue(p); if (e.kind == 's') continue
                val f = File(s.dir, p)
                // Deepest first, so a folder is empty by the time it comes up (unless it holds a skipped big file: kept).
                val ok = if (e.kind == 'd') (f.list()?.isEmpty() != false && f.delete()) else Files.deleteIfExists(f.toPath())
                if (ok) deleted++ else if (e.kind != 'd') errors += "couldn't delete $p"
            }
            // 2. Folders, then files and links.
            for (e in then) if (e.kind == 'd' && inPaths(s.name, e.path)) {
                val f = File(s.dir, e.path)
                if (Files.isSymbolicLink(f.toPath()) || f.isFile) Files.deleteIfExists(f.toPath())
                if (!f.isDirectory && !f.mkdirs()) errors += "couldn't make ${e.path}/"
            }
            for (e in then) {
                if (e.kind == 'd' || e.kind == 's' || !inPaths(s.name, e.path)) continue
                val cur = now[e.path]
                if (cur != null && cur.sameContent(e)) { if (cur.kind == 'f' && cur.mtime != e.mtime) File(s.dir, e.path).setLastModified(e.mtime); unchanged++; continue }
                val f = File(s.dir, e.path)
                try {
                    if (cur?.kind == 'd') f.deleteRecursively() else Files.deleteIfExists(f.toPath())
                    f.parentFile?.mkdirs()
                    if (e.kind == 'l') Files.createSymbolicLink(f.toPath(), Paths.get(e.ref))
                    else {
                        val obj = File(objects, e.ref.take(2) + "/" + e.ref.drop(2))
                        val tmp = File(f.parentFile, ".${f.name}.cp-restore")
                        InflaterInputStream(obj.inputStream()).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
                        f.setExecutable(e.exec, false)
                        f.setLastModified(e.mtime)
                    }
                    rewritten++
                } catch (x: Exception) { errors += "couldn't restore ${e.path}: ${x.message}" }
            }
        }
        return Restored(rewritten, deleted, unchanged, before, errors)
    }

    // ---------- housekeeping ----------

    @Synchronized fun delete(id: String): Boolean {
        val d = File(cps, id); if (!d.isDirectory) return false
        d.deleteRecursively(); gc(); return true
    }

    /** Keep the newest [maxAuto] automatic and [maxManual] saved checkpoints; drop unreferenced content. */
    @Synchronized fun prune() {
        val all = list()
        val drop = all.filter { it.auto }.drop(maxAuto) + all.filter { !it.auto }.drop(maxManual)
        if (drop.isEmpty()) return
        drop.forEach { File(cps, it.id).deleteRecursively() }
        gc()
    }

    private fun gc() {
        val live = HashSet<String>()
        (cps.listFiles() ?: emptyArray()).forEach { d -> (d.listFiles() ?: emptyArray()).filter { it.name.endsWith(".idx") }.forEach { idx ->
            idx.forEachLine { l -> parse(l)?.takeIf { it.kind == 'f' }?.let { live += it.ref } } } }
        (objects.listFiles() ?: emptyArray()).forEach { pre -> (pre.listFiles() ?: emptyArray()).forEach { o ->
            if (pre.name + o.name !in live) o.delete() }; if (pre.list()?.isEmpty() == true) pre.delete() }
    }

    /** Bytes the store takes on disk (content is shared between checkpoints). */
    fun diskBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---------- files ----------

    fun readIndex(id: String, scope: String): List<Entry> {
        val f = File(File(cps, id), "$scope.idx"); if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { parse(it) }
    }

    private fun parse(l: String): Entry? {
        val p = l.split('\t'); if (p.size != 6 || p[0].length != 1) return null
        return try { Entry(unesc(p[5]), p[0][0], p[1] == "1", p[2].toLong(), p[3].toLong(), unesc(p[4])) } catch (_: Exception) { null }
    }

    private fun meta(c: Checkpoint) = listOf("id=${c.id}", "created=${c.created}", "label=${esc(c.label)}", "reason=${esc(c.reason)}",
        "auto=${c.auto}", "scopes=${c.scopes.joinToString(",")}", "files=${c.files}", "bytes=${c.bytes}", "skipped=${c.skipped}").joinToString("\n")

    private fun readMeta(d: File): Checkpoint? {
        val f = File(d, "meta"); if (!d.isDirectory || d.name.endsWith(".tmp") || !f.exists()) return null
        val m = f.readLines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        return try {
            Checkpoint(m.getValue("id"), m.getValue("created").toLong(), unesc(m["label"].orEmpty()), unesc(m["reason"].orEmpty()),
                m["auto"] == "true", m["scopes"].orEmpty().split(',').filter { it.isNotBlank() }, m["files"]?.toInt() ?: 0,
                m["bytes"]?.toLong() ?: 0, m["skipped"]?.toInt() ?: 0)
        } catch (_: Exception) { null }
    }

    private fun nextSeq(): Int {
        val f = File(root, "seq")
        val n = (f.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0) + 1
        f.writeText(n.toString()); return n
    }

    private fun seqOf(id: String) = id.removePrefix("cp-").toIntOrNull() ?: 0

    companion object {
        /** Coarse filesystem mtimes (1–2 s on some phones' storage): files this close to an index are re-hashed. */
        const val RACY_MS = 2_000L
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        fun unesc(s: String): String {
            val sb = StringBuilder(); var i = 0
            while (i < s.length) { val c = s[i]; if (c == '\\' && i + 1 < s.length) { sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2 } else { sb.append(c); i++ } }
            return sb.toString()
        }
    }
}
