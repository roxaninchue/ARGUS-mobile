package com.blaze.assistant.browser.phone.instances

import com.blaze.assistant.browser.phone.PhonePolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Instance checkpoints on disk: one small versioned JSON per instance under `<root>/<id>/checkpoint.json` (root is
 * `filesDir/phone/instances` on the phone), written atomically (temp file + rename) with the previous one kept as
 * `checkpoint.prev.json`, so a torn or corrupt write falls back one checkpoint instead of losing the instance. The
 * registry's order and the last watched instance are in `<root>/index.json`; a corrupt index is rebuilt from the
 * folders. Closed instances move to `<root>/.closed/<id>-<time>/` and are kept [KEEP_CLOSED_MS] (7 days, section 5e).
 * Pure java.io + org.json; never throws to the caller (errors come back as null / false plus [lastProblem]).
 */
class InstanceStore(val root: File) {
    companion object {
        const val VERSION = 1
        const val FILE = "checkpoint.json"
        const val PREV = "checkpoint.prev.json"
        const val INDEX = "index.json"
        const val CLOSED = ".closed"
        const val KEEP_CLOSED_MS = 7 * 24 * 3600_000L

        // ------------------------------------------------------------ the JSON codec (pure)
        fun encode(i: Instance, at: Long): JSONObject = JSONObject()
            .put("v", VERSION).put("id", i.id).put("name", i.name).put("task", i.task)
            .put("created", i.createdAt).put("active", i.lastActiveAt).put("cp", at)
            .put("mode", i.mode.name).put("unsaved", i.unsaved).put("restorable", i.restorable)
            .apply { i.lastError?.let { put("err", it) } }
            .apply { i.browser?.let { b -> put("browser", JSONObject().put("url", b.url).put("title", b.title).put("site", b.site).put("scroll", b.scrollY)) } }
            .put("cards", JSONArray().apply { i.cards.forEach { c -> put(JSONObject().put("id", c.id).put("kind", c.kind).put("text", c.text).put("at", c.at)) } })
            .put("steps", JSONArray().apply { i.steps.forEach { s -> put(JSONObject().put("at", s.at).put("t", s.text)) } })
            .apply { i.lease?.let { l -> put("lease", JSONObject().put("kind", l.owner.kind.name).put("owner", l.owner.id).put("oname", l.owner.name)
                .put("task", l.task).put("since", l.since).put("until", l.until).put("renewed", l.renewed)) } }

        /** Null when the JSON isn't a checkpoint this version can read (newer version, missing id, bad id). */
        fun decode(o: JSONObject): Instance? {
            val v = o.optInt("v", -1)
            if (v < 1 || v > VERSION) return null
            val id = o.optString("id")
            if (!Instance.validId(id)) return null
            val b = o.optJSONObject("browser")?.let { BrowserState(it.optString("url"), it.optString("title"), it.optString("site"), it.optInt("scroll")) }
            val cards = o.optJSONArray("cards")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                .map { PendingCard(it.optString("id"), it.optString("kind"), it.optString("text"), it.optLong("at")) } } ?: emptyList()
            val steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                .map { Step(it.optLong("at"), it.optString("t")) } }?.takeLast(Instance.STEPS) ?: emptyList()
            val lease = o.optJSONObject("lease")?.let { l ->
                val kind = try { Owner.Kind.valueOf(l.optString("kind")) } catch (_: Exception) { return@let null }
                Lease(Owner(kind, l.optString("owner"), l.optString("oname")), l.optString("task"), l.optLong("since"), l.optLong("until"), l.optLong("renewed"))
            }
            val mode = try { PhonePolicy.Mode.valueOf(o.optString("mode")) } catch (_: Exception) { PhonePolicy.Mode.OFF }
            return Instance(id, o.optString("name").ifBlank { id }, o.optString("task"), o.optLong("created"), o.optLong("active"),
                Tier.COLD, mode, b, o.optBoolean("unsaved"), cards, o.optLong("cp"), o.optString("err").ifBlank { null },
                o.optBoolean("restorable"), steps, lease, body = true)
        }
    }

    /** What went wrong last (a corrupt file read, a failed write), for status lines and tests. */
    @Volatile var lastProblem: String? = null
        private set

    private fun dir(id: String) = File(root, id)

    /** Writes [i]'s checkpoint atomically. True when it is on disk. */
    @Synchronized fun save(i: Instance, at: Long): Boolean = try {
        require(Instance.validId(i.id)) { "bad id" }
        val d = dir(i.id).apply { mkdirs() }
        val f = File(d, FILE); val prev = File(d, PREV); val tmp = File(d, "$FILE.tmp")
        tmp.writeText(encode(i, at).toString())
        if (f.exists()) { prev.delete(); if (!f.renameTo(prev)) f.copyTo(prev, overwrite = true) }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        true
    } catch (e: Exception) { lastProblem = "checkpoint of ${i.id} not saved: ${e.message?.take(120)}"; false }

    /**
     * The instance's newest readable checkpoint: the current file, else the previous one (a torn or corrupt write),
     * else null. A corrupt file is kept aside as `checkpoint.bad.json` for a later look, never read again.
     */
    @Synchronized fun load(id: String): Instance? {
        if (!Instance.validId(id)) return null
        val d = dir(id)
        for (name in listOf(FILE, PREV)) {
            val f = File(d, name)
            if (!f.isFile) continue
            val got = try { decode(JSONObject(f.readText())) } catch (_: Exception) { null }
            if (got != null) { if (name == PREV) lastProblem = "checkpoint of $id was unreadable; restored the one before it"; return got }
            lastProblem = "checkpoint $name of $id is unreadable"
            try { f.renameTo(File(d, "checkpoint.bad.json")) } catch (_: Exception) { }
        }
        return null
    }

    fun exists(id: String): Boolean = Instance.validId(id) && (File(dir(id), FILE).isFile || File(dir(id), PREV).isFile)

    /** The ids that have a folder here (valid ids only), oldest folder first. */
    fun ids(): List<String> = (root.listFiles() ?: emptyArray()).filter { it.isDirectory && Instance.validId(it.name) }
        .sortedBy { it.lastModified() }.map { it.name }

    /** Moves [id]'s folder to `.closed/`, where it stays [KEEP_CLOSED_MS]. */
    @Synchronized fun close(id: String, now: Long): Boolean = try {
        val d = dir(id)
        if (!Instance.validId(id) || !d.isDirectory) false else {
            val dst = File(File(root, CLOSED).apply { mkdirs() }, "$id-$now")
            d.renameTo(dst) || run { d.copyRecursively(dst, overwrite = true); d.deleteRecursively() }
        }
    } catch (e: Exception) { lastProblem = "close of $id: ${e.message?.take(120)}"; false }

    /** Deletes closed instances older than [KEEP_CLOSED_MS]. Returns how many went. */
    @Synchronized fun prune(now: Long): Int {
        val c = File(root, CLOSED).listFiles() ?: return 0
        var n = 0
        for (f in c) {
            val t = f.name.substringAfterLast('-').toLongOrNull() ?: f.lastModified()
            if (now - t > KEEP_CLOSED_MS && f.deleteRecursively()) n++
        }
        return n
    }

    // ------------------------------------------------------------ the index
    data class Index(val order: List<String>, val active: String?, val watched: String?, val powered: Boolean)

    @Synchronized fun saveIndex(x: Index): Boolean = try {
        root.mkdirs()
        val tmp = File(root, "$INDEX.tmp")
        tmp.writeText(JSONObject().put("v", VERSION).put("order", JSONArray(x.order)).put("active", x.active ?: "")
            .put("watched", x.watched ?: "").put("powered", x.powered).toString())
        val f = File(root, INDEX)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        true
    } catch (e: Exception) { lastProblem = "index not saved: ${e.message?.take(120)}"; false }

    /** The index, or one rebuilt from the folders when it is missing or unreadable. Unknown ids are dropped. */
    @Synchronized fun loadIndex(): Index {
        val onDisk = ids()
        val o = try { File(root, INDEX).takeIf { it.isFile }?.let { JSONObject(it.readText()) } } catch (_: Exception) { lastProblem = "index unreadable; rebuilt"; null }
        if (o == null || o.optInt("v", -1) !in 1..VERSION) return Index(onDisk, null, null, false)
        val a = o.optJSONArray("order") ?: JSONArray()
        val listed = (0 until a.length()).map { a.optString(it) }.filter { it in onDisk }
        val order = listed + onDisk.filter { it !in listed }
        fun pick(k: String) = o.optString(k).takeIf { it in order }
        return Index(order, pick("active"), pick("watched"), o.optBoolean("powered"))
    }
}
