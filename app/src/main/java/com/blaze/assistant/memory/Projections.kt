package com.blaze.assistant.memory

import com.blaze.assistant.store.BlazeDb

/**
 * P3-AG-04 (05 §4.5): MEMORY.md (a bot's own notes, `bot:<id>`) and USER.md (the profile, `user:*`) are **projections**
 * of blaze.db: rendered from the DB on change and parsed back after a hand edit. Pure (ProjectionRoundTripTest).
 *
 * Format: one memory per bullet; Hermes / Bot HQ `§` separators are accepted too (a block between two `§` lines is one
 * entry). Each rendered line ends with an id marker `<!-- #12 -->` so an edited line keeps its row (and its uses /
 * created); a line without a marker is new. "(inferred)" at the end of a line = not stated; deleting the word marks
 * it stated. In USER.md the `## ` heading a new line sits under decides its scope (`## People` → `user:people`).
 *
 * [parse] never writes: it returns a [Plan] (adds, edits, deletes, stated flips, moves) or a [Failed] with the reason and
 * a diff, and the caller keeps the DB as it is (05 §4.5 "a parse that fails keeps the DB and shows the diff").
 */
object Projections {
    const val INFERRED = "(inferred)"
    private val marker = Regex("""\s*<!--\s*#(\d{1,12})\s*-->\s*$""")
    private val bullet = Regex("""^\s*[-*]\s+(.*)$""")

    /** USER.md sections in order: heading → scope. */
    val USER_SECTIONS = linkedMapOf(
        "About you" to MemScopes.USER_CORE, "People" to "user:people", "Places and time zone" to "user:places",
        "Important dates" to "user:dates", "Goals" to "user:goals", "Projects" to "user:projects",
        "Preferences" to "user:preferences", "Other" to "user:notes",
    )

    fun headingFor(scope: String): String = USER_SECTIONS.entries.firstOrNull { it.value == scope }?.key
        ?: scope.removePrefix(MemScopes.USER_PREFIX).replaceFirstChar { it.uppercase() }

    private fun line(l: MemScopes.Line) = "- ${l.text.replace('\n', ' ')}${if (!l.stated) " $INFERRED" else ""} <!-- #${l.id} -->"

    /** MEMORY.md of one bot: its `bot:<id>` lines, oldest first. */
    fun renderMemory(botId: String, botName: String, lines: List<MemScopes.Line>): String {
        val mine = lines.filter { it.scope == MemScopes.BOT_PREFIX + botId }.sortedWith(compareBy({ it.created }, { it.id }))
        val sb = StringBuilder("# $botName's notes\n\n")
        sb.append("One note per line. Edit, delete or add lines; ARGUS saves them back. Secrets are never kept.\n\n")
        for (l in mine) sb.append(line(l)).append('\n')
        return sb.toString()
    }

    /** USER.md: every `user:*` line, grouped under its heading, oldest first in each. */
    fun renderUser(name: String, lines: List<MemScopes.Line>): String {
        val user = lines.filter { MemScopes.parse(it.scope).let { s -> s is MemScopes.Scope.UserCore || s is MemScopes.Scope.User } }
        val sb = StringBuilder("# What the team knows about ${name.ifBlank { "you" }}\n\n")
        sb.append("One fact per line, under the heading it belongs to. \"$INFERRED\" means a bot worked it out; delete the word once it's right.\n")
        val scopes = USER_SECTIONS.values.toList() + user.map { it.scope }.distinct().filter { it !in USER_SECTIONS.values }.sorted()
        for (sc in scopes) {
            val g = user.filter { it.scope == sc }.sortedWith(compareBy({ it.created }, { it.id }))
            if (g.isEmpty()) continue
            sb.append("\n## ").append(headingFor(sc)).append('\n')
            for (l in g) sb.append(line(l)).append('\n')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------------------------------------ parse back

    data class Entry(val id: Long?, val text: String, val stated: Boolean, val scope: String)

    sealed interface Parsed

    data class Plan(
        val adds: List<Entry> = emptyList(),
        val edits: List<Entry> = emptyList(),      // id + new text (stated as written)
        val stated: List<Pair<Long, Boolean>> = emptyList(),
        val moves: List<Pair<Long, String>> = emptyList(),
        val deletes: List<Long> = emptyList(),
        val secretsDropped: Int = 0,
    ) : Parsed {
        val empty get() = adds.isEmpty() && edits.isEmpty() && stated.isEmpty() && moves.isEmpty() && deletes.isEmpty()
    }

    data class Failed(val why: String, val diff: List<String>) : Parsed

    /** Splits a projection into entries. [scopeOf] gives the scope for a heading ("" = the file's default scope). */
    fun entries(md: String, defaultScope: String, scopeOf: (String) -> String?): Pair<List<Entry>, String?> {
        val out = ArrayList<Entry>(); var scope = defaultScope
        val text = md.replace("\r\n", "\n")
        // Hermes / Bot HQ: entries separated by "§" (on its own line or inline). Each block may itself be a bullet.
        val chunks: List<String> = if (text.contains('§')) text.split('§').flatMap { blk ->
            val t = blk.trim(); if (t.isEmpty()) emptyList() else if (t.lines().any { bullet.matches(it) || it.startsWith("#") }) t.lines() else listOf("- " + t.replace('\n', ' '))
        } else text.lines()
        for (raw in chunks) {
            val l = raw.trimEnd()
            if (l.isBlank()) continue
            if (l.startsWith("## ")) {
                val h = l.removePrefix("## ").trim()
                scope = scopeOf(h) ?: return out to "unknown heading \"## $h\"; use one of: ${USER_SECTIONS.keys.joinToString(", ")}"
                continue
            }
            if (l.startsWith("#")) continue                  // the title
            val b = bullet.find(l) ?: continue               // prose (the help line) is not a memory
            var t = b.groupValues[1]
            val id = marker.find(t)?.groupValues?.get(1)?.toLongOrNull()
            t = t.replace(marker, "").trim()
            val inferred = t.endsWith(INFERRED, ignoreCase = true)
            if (inferred) t = t.dropLast(INFERRED.length).trim()
            t = BlazeDb.clean(t)
            if (t.isEmpty()) continue
            out += Entry(id, t, !inferred, scope)
        }
        return out to null
    }

    /**
     * The changes [md] makes to [current] (the lines of the projected scopes). A line whose id marker names a row outside
     * the projection, or the same marker twice, fails the parse; so does a file whose every line vanished while more than
     * [MASS_DELETE] lines existed (use "Forget everything" for that). Secret lines are dropped and counted.
     */
    fun plan(md: String, current: List<MemScopes.Line>, defaultScope: String, scopeOf: (String) -> String? = { null }): Parsed {
        val (es, err) = entries(md, defaultScope, scopeOf)
        val rendered = { current.sortedBy { it.id }.joinToString("\n") { line(it) } }
        if (err != null) return Failed(err, diff(rendered(), md))
        val byId = current.associateBy { it.id }
        val seen = HashSet<Long>()
        for (e in es) if (e.id != null) {
            if (e.id !in byId) return Failed("line \"${e.text.take(40)}\" points at note #${e.id}, which isn't in this file; remove the <!-- #${e.id} --> marker to add it as new", diff(rendered(), md))
            if (!seen.add(e.id)) return Failed("note #${e.id} appears twice; keep one of the lines", diff(rendered(), md))
        }
        if (es.isEmpty() && current.size > MASS_DELETE) return Failed("every line is gone; to clear everything use Forget everything", diff(rendered(), md))
        var secrets = 0
        val adds = ArrayList<Entry>(); val edits = ArrayList<Entry>(); val stated = ArrayList<Pair<Long, Boolean>>(); val moves = ArrayList<Pair<Long, String>>()
        val byNorm = current.associateBy { BlazeDb.norm(it.text) }
        val kept = HashSet<Long>()
        for (e in es) {
            if (MemMigration.isSecret(e.text)) { secrets++; continue }
            val old = e.id?.let { byId[it] } ?: byNorm[BlazeDb.norm(e.text)]?.takeIf { it.id !in seen && it.id !in kept }
            if (old == null) { adds += e; continue }
            kept += old.id
            if (BlazeDb.norm(old.text) != BlazeDb.norm(e.text) || old.text != e.text) edits += e.copy(id = old.id)
            if (old.stated != e.stated) stated += old.id to e.stated
            if (old.scope != e.scope) moves += old.id to e.scope
        }
        val deletes = current.map { it.id }.filter { it !in kept }
        return Plan(adds, edits, stated, moves, deletes, secrets)
    }

    const val MASS_DELETE = 3

    /** What a plan does, applied through [ops] (Mind / MemScopeStore on the phone; BlazeDb in tests). */
    interface Ops {
        fun add(text: String, scope: String, stated: Boolean): Long
        fun edit(id: Long, text: String)
        fun setStated(id: Long, stated: Boolean)
        fun move(id: Long, scope: String)
        fun delete(id: Long)
    }

    fun apply(p: Plan, ops: Ops): Int {
        var n = 0
        for (d in p.deletes) { ops.delete(d); n++ }
        for (e in p.edits) { ops.edit(e.id!!, e.text); n++ }
        for ((id, s) in p.stated) { ops.setStated(id, s); n++ }
        for ((id, sc) in p.moves) { ops.move(id, sc); n++ }
        for (e in p.adds) { if (ops.add(e.text, e.scope, e.stated) > 0) n++ }
        return n
    }

    /** USER.md heading → scope (case forgiven); unknown → null. */
    fun userScope(h: String): String? = USER_SECTIONS.entries.firstOrNull { it.key.equals(h.trim(), true) }?.value
        ?: h.trim().lowercase().takeIf { Regex("^[a-z][a-z0-9_-]{1,30}$").matches(it) && MemScopes.parse("user:$it") != null }?.let { "user:$it" }

    fun diff(a: String, b: String, max: Int = 40): List<String> {
        val al = a.lines().map { it.replace(marker, "").trimEnd() }; val bl = b.lines().map { it.replace(marker, "").trimEnd() }
        val inB = bl.toHashSet(); val inA = al.toHashSet()
        return (al.filter { it.isNotBlank() && it !in inB }.map { "- $it" } + bl.filter { it.isNotBlank() && it !in inA && !it.startsWith("#") }.map { "+ $it" }).take(max)
    }
}

/**
 * The projection files on disk (05 §4.2): filesDir/bots/<id>/MEMORY.md for every bot with notes and filesDir/USER.md.
 * Re-rendered after a forget or an edit from Settings; read back by [Projections.plan] when the user edits one by hand
 * (Bot profile → Memory → Files). The DB stays the source of truth: these are copies.
 */
class ProjectionFiles(private val root: java.io.File) {
    fun userFile() = java.io.File(root, "USER.md")
    fun memoryFile(botId: String) = java.io.File(java.io.File(java.io.File(root, "bots"), botId.replace(Regex("[^A-Za-z0-9_.-]"), "_")), "MEMORY.md")

    /** Writes USER.md and one MEMORY.md per bot in [names] (id → display name); returns the files written. */
    fun write(lines: List<MemScopes.Line>, userName: String, names: Map<String, String>): List<java.io.File> {
        val out = ArrayList<java.io.File>()
        fun put(f: java.io.File, s: String) { f.parentFile?.mkdirs(); if (!f.isFile || f.readText() != s) { val t = java.io.File(f.path + ".tmp"); t.writeText(s); if (!t.renameTo(f)) { f.writeText(s); t.delete() } }; out += f }
        put(userFile(), Projections.renderUser(userName, lines))
        val withNotes = lines.mapNotNull { (MemScopes.parse(it.scope) as? MemScopes.Scope.BotNotes)?.botId }.toSet()
        for (id in names.keys + withNotes) put(memoryFile(id), Projections.renderMemory(id, names[id] ?: id, lines))
        return out
    }
}
