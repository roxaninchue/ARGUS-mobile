package com.blaze.assistant.team

import java.io.File

/**
 * P3-AG-03 (05 §1.2): every bot's SOUL.md — one markdown file per bot with fixed headings, editable by hand and parsed
 * back. Pure rules in [Souls]; storage behind [SoulPort] ([FileSoulPort] = filesDir/bots/<id>/SOUL.md + its history);
 * [SoulStore] puts them together (version bump, 20-version history, Undo, regenerate, reset). SoulParseTest.
 *
 * ```
 * ---
 * id: money
 * name: Ledger
 * label: Money
 * version: 7
 * locked_sections: [Boundaries]
 * ---
 * # Who you are
 * # How you sound
 * # What you own
 * # Standing rules        ← the user's "always / never" lines (P3-AG-05 learn lands them here)
 * # Boundaries            ← locked template text
 * ```
 * Locked sections can't be changed by an edit: the saved text keeps the old body and the result says so. The Manager
 * (ARGUS) has a locked core (Who / How / What you own / Boundaries) and an editable Standing rules section (06 R6, D3).
 * Unknown headings are kept as they are (a user's own "# Notes" survives a round trip).
 */
object Souls {
    const val WHO = "Who you are"
    const val HOW = "How you sound"
    const val OWN = "What you own"
    const val RULES = "Standing rules"
    const val BOUNDARIES = "Boundaries"
    val HEADINGS = listOf(WHO, HOW, OWN, RULES, BOUNDARIES)

    const val HISTORY = 20
    const val MAX_CHARS = 8000
    const val RULE_MAX = 200

    data class Soul(
        val id: String, val name: String, val label: String, val version: Int,
        val locked: Set<String>, val sections: LinkedHashMap<String, String>,
    ) {
        fun section(h: String): String = sections[canon(h) ?: h].orEmpty()
        val rules: List<String> get() = section(RULES).lines().map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("* ") }.map { it.drop(2).trim() }.filter { it.isNotEmpty() }
    }

    sealed class Parsed {
        data class Ok(val soul: Soul) : Parsed()
        data class Bad(val why: String, val line: Int) : Parsed()
    }

    /** The heading as written in [HEADINGS] (case and spacing forgiven), or null for a heading of the user's own. */
    fun canon(h: String): String? { val k = h.trim().lowercase().replace(Regex("\\s+"), " "); return HEADINGS.firstOrNull { it.lowercase() == k } }

    // ------------------------------------------------------------------------------------------------ parse / render

    fun parse(md0: String): Parsed {
        val md = md0.replace("\r\n", "\n").replace('\r', '\n')
        if (md.length > MAX_CHARS * 2) return Parsed.Bad("SOUL.md is too long (${md.length} characters; keep it under $MAX_CHARS)", 1)
        val lines = md.split('\n')
        var i = 0
        while (i < lines.size && lines[i].isBlank()) i++
        val meta = LinkedHashMap<String, String>()
        if (i < lines.size && lines[i].trim() == "---") {
            val start = i; i++
            while (i < lines.size && lines[i].trim() != "---") {
                val l = lines[i]; if (l.isNotBlank()) {
                    val c = l.indexOf(':'); if (c <= 0) return Parsed.Bad("front matter line ${i + 1} has no \"key: value\"", i + 1)
                    meta[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
                }
                i++
            }
            if (i >= lines.size) return Parsed.Bad("front matter opened on line ${start + 1} is never closed with ---", start + 1)
            i++
        }
        val sections = LinkedHashMap<String, String>()
        var cur: String? = null; val buf = StringBuilder()
        fun flush() { cur?.let { k -> sections[k] = ((sections[k]?.let { "$it\n" } ?: "") + buf.toString().trim('\n')).trimEnd() }; buf.clear() }
        var preamble = false
        while (i < lines.size) {
            val l = lines[i]
            val h = Regex("^#\\s+(.+?)\\s*(?:←.*)?$").find(l)
            if (h != null) { flush(); val name = h.groupValues[1].trim(); cur = canon(name) ?: name }
            else if (cur == null) { if (l.isNotBlank()) preamble = true }
            else buf.append(l).append('\n')
            i++
        }
        flush()
        if (preamble) return Parsed.Bad("text before the first heading; start each part with a heading like \"# $WHO\"", 1)
        val version = meta["version"]?.toIntOrNull() ?: 1
        val locked = meta["locked_sections"].orEmpty().trim().removePrefix("[").removeSuffix("]").split(',')
            .map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }.map { canon(it) ?: it }.toSet()
        return Parsed.Ok(Soul(meta["id"].orEmpty(), meta["name"].orEmpty(), meta["label"].orEmpty(), version.coerceAtLeast(1), locked, sections))
    }

    fun render(s: Soul): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("id: ").append(s.id).append('\n')
        sb.append("name: ").append(s.name).append('\n')
        if (s.label.isNotEmpty()) sb.append("label: ").append(s.label).append('\n')
        sb.append("version: ").append(s.version).append('\n')
        sb.append("locked_sections: [").append(HEADINGS.filter { it in s.locked }.plus(s.locked.filter { it !in HEADINGS }.sorted()).joinToString(", ")).append("]\n")
        sb.append("---\n")
        val order = HEADINGS.filter { it in s.sections } + s.sections.keys.filter { it !in HEADINGS }
        for (h in order) {
            sb.append("# ").append(h).append('\n')
            val body = s.sections[h].orEmpty().trim('\n')
            if (body.isNotEmpty()) sb.append(body).append('\n')
        }
        return sb.toString()
    }

    /** The text that goes into the system prompt: the sections, no front matter, no empty parts. Stable (cache-safe). */
    fun promptText(s: Soul): String {
        val sb = StringBuilder("YOUR SOUL (${s.name}${if (s.label.isNotEmpty()) " · ${s.label}" else ""}; the user can edit it)\n")
        for (h in HEADINGS.filter { it in s.sections } + s.sections.keys.filter { it !in HEADINGS }) {
            val body = s.sections[h].orEmpty().trim()
            if (body.isEmpty()) continue
            sb.append("## ").append(h).append('\n').append(body).append('\n')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------------------------------------ templates

    /** Inputs for a template, so this file needs nothing from Bot beyond its fields. */
    data class Profile(val id: String, val name: String, val label: String, val domain: String?, val isManager: Boolean,
                       val instructions: String = "", val owns: String = "", val owner: String = "the user")

    const val MANAGER_WHO = "You are ARGUS, the Manager: the front door of the user's team of bots. You answer directly when you can, and hand work to the right bot as a job when it belongs to one."
    const val MANAGER_HOW = "Plain words, short first answer, then the detail. One recommendation with the reason. Never name the model or the tools you used."
    const val MANAGER_OWN = "The roster, routing and jobs; the user's core profile (user:core) and accepting memory proposals from other bots; the daily picture."
    val BOUNDARIES_TEXT = listOf(
        "You never spend money, send messages for the user, delete their things or change settings without a yes on an approval card.",
        "You never save secrets (passwords, keys, card or ID numbers) in memory or in this file.",
        "You never share another bot's private notes unless the user shared them with you.",
    )

    fun template(p: Profile, now: Long = 0): Soul {
        val sections = LinkedHashMap<String, String>()
        if (p.isManager) {
            sections[WHO] = MANAGER_WHO; sections[HOW] = MANAGER_HOW; sections[OWN] = MANAGER_OWN
        } else {
            val area = p.label.ifBlank { p.domain ?: "" }
            sections[WHO] = (if (area.isNotBlank()) "You are ${p.name}, the ${area.lowercase()} bot on ${p.owner}'s team. You report to ARGUS." else "You are ${p.name}, a bot on ${p.owner}'s team. You report to ARGUS.") +
                (if (p.instructions.isNotBlank()) "\n" + p.instructions.trim() else "")
            sections[HOW] = "Plain words. Lead with the answer; keep it short unless asked for more."
            sections[OWN] = p.owns.ifBlank { if (area.isNotBlank()) "Everything about ${area.lowercase()} that ARGUS or the user hands you." else "The jobs ARGUS or the user hands you." }
        }
        sections[RULES] = ""
        sections[BOUNDARIES] = BOUNDARIES_TEXT.joinToString("\n") { "- $it" }
        val locked = if (p.isManager) setOf(WHO, HOW, OWN, BOUNDARIES) else setOf(BOUNDARIES)
        return Soul(p.id, p.name, if (p.isManager) "Manager" else p.label, 1, locked, sections)
    }

    // ------------------------------------------------------------------------------------------------ edits

    sealed class Edit {
        data class Saved(val soul: Soul, val keptLocked: List<String>) : Edit()
        object Unchanged : Edit()
        data class Refused(val why: String, val line: Int = 0) : Edit()
    }

    private val secretRe = Regex("""(?i)\b(gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{20,}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+)\b|\b(password|passwd|api[_ -]?key|secret|token|pin)\s*[:=]\s*\S{4,}|\b(?:\d[ -]?){13,19}\b""")

    /** True when [s] carries something that looks like a key, password or card number (A-team SECRET). */
    fun hasSecret(s: String) = secretRe.containsMatchIn(s)

    /**
     * The user (or a bot) edited the whole file. Identity and locked sections come from [old]; the version goes up by
     * one. A parse failure keeps [old] (the caller shows why); so does a secret anywhere in the text.
     */
    fun edit(old: Soul, text: String): Edit {
        val p = parse(text)
        if (p is Parsed.Bad) return Edit.Refused(p.why, p.line)
        val n = (p as Parsed.Ok).soul
        if (hasSecret(text)) return Edit.Refused("that looks like a secret (a key, password or card number); keep those in the vault, not in a soul")
        val kept = ArrayList<String>()
        val sections = LinkedHashMap<String, String>()
        for ((h, b) in n.sections) {
            if (h in old.locked) { if (b.trim() != old.section(h).trim()) kept += h; sections[h] = old.section(h) } else sections[h] = b
        }
        for (h in old.locked) if (h !in sections) { kept += h; sections[h] = old.section(h) }
        val next = old.copy(sections = sections, version = old.version + 1)
        if (render(next.copy(version = old.version)) == render(old)) return Edit.Unchanged
        if (render(next).length > MAX_CHARS) return Edit.Refused("SOUL.md is too long (keep it under $MAX_CHARS characters)")
        return Edit.Saved(next, kept.distinct())
    }

    /** Adds a standing rule ("Never suggest crypto"), once (case and punctuation forgiven). Unchanged when present. */
    fun addRule(old: Soul, rule0: String): Edit {
        val rule = rule0.trim().removePrefix("- ").trim().removeSuffix(".").let { if (it.isEmpty()) it else it[0].uppercaseChar() + it.substring(1) }
        if (rule.length < 3) return Edit.Refused("empty rule")
        if (rule.length > RULE_MAX) return Edit.Refused("a standing rule is one line (under $RULE_MAX characters)")
        if (hasSecret(rule)) return Edit.Refused("that looks like a secret, so it isn't saved")
        if (RULES in old.locked) return Edit.Refused("$RULES is locked for ${old.name}")
        val key = norm(rule)
        if (old.rules.any { norm(it) == key }) return Edit.Unchanged
        val body = old.section(RULES).trimEnd()
        val s = LinkedHashMap(old.sections); s[RULES] = (if (body.isEmpty()) "" else "$body\n") + "- $rule"
        return Edit.Saved(old.copy(sections = reorder(s), version = old.version + 1), emptyList())
    }

    fun removeRule(old: Soul, rule: String): Edit {
        val key = norm(rule)
        val kept = old.section(RULES).lines().filterNot { l -> val t = l.trim(); (t.startsWith("- ") || t.startsWith("* ")) && norm(t.drop(2)) == key }
        if (kept.joinToString("\n") == old.section(RULES)) return Edit.Unchanged
        val s = LinkedHashMap(old.sections); s[RULES] = kept.joinToString("\n").trim('\n')
        return Edit.Saved(old.copy(sections = s, version = old.version + 1), emptyList())
    }

    /** Regenerate from the bot's profile fields: a fresh template that keeps the user's standing rules and own sections. */
    fun regenerate(old: Soul, p: Profile): Soul {
        val t = template(p)
        val s = LinkedHashMap(t.sections)
        s[RULES] = old.section(RULES)
        for ((h, b) in old.sections) if (h !in HEADINGS) s[h] = b
        return t.copy(sections = s, version = old.version + 1)
    }

    /** Back to the template (standing rules dropped too), as the next version. */
    fun reset(old: Soul, p: Profile): Soul = template(p).copy(version = old.version + 1)

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun reorder(s: Map<String, String>): LinkedHashMap<String, String> =
        LinkedHashMap<String, String>().apply { HEADINGS.filter { it in s }.forEach { put(it, s.getValue(it)) }; s.keys.filter { it !in HEADINGS }.forEach { put(it, s.getValue(it)) } }

    // ------------------------------------------------------------------------------------------------ history

    data class Version(val botId: String, val version: Int, val body: String, val at: Long, val by: String)

    /** The [HISTORY] newest versions, newest first (team.db bot_soul_versions keeps the same rows on the phone). */
    fun trim(versions: List<Version>): List<Version> = versions.sortedByDescending { it.version }.take(HISTORY)

    /** A short line-level diff ("+ line" / "- line"), for the revert sheet and for a failed parse. */
    fun diff(a: String, b: String, max: Int = 40): List<String> {
        val al = a.lines(); val bl = b.lines()
        val out = ArrayList<String>()
        val inB = bl.toHashSet(); val inA = al.toHashSet()
        for (l in al) if (l !in inB && l.isNotBlank()) out += "- $l"
        for (l in bl) if (l !in inA && l.isNotBlank()) out += "+ $l"
        return out.take(max)
    }
}

/** Where souls live. The phone: [FileSoulPort] under filesDir (P0 team.db `bot_soul_versions` can back [versions] later). */
interface SoulPort {
    fun read(botId: String): String?
    fun write(botId: String, body: String)
    fun versions(botId: String): List<Souls.Version>
    fun putVersion(v: Souls.Version)
    fun dropVersion(botId: String, version: Int)
    fun delete(botId: String)
}

/** filesDir/bots/<id>/SOUL.md, history in filesDir/bots/<id>/soul-history/<version>.md (first line: "<at> <by>"). */
class FileSoulPort(private val root: File) : SoulPort {
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifEmpty { "_" }
    private fun dir(id: String) = File(File(root, "bots"), safe(id))
    private fun hist(id: String) = File(dir(id), "soul-history")
    override fun read(botId: String): String? = File(dir(botId), "SOUL.md").takeIf { it.isFile }?.readText()
    override fun write(botId: String, body: String) {
        val d = dir(botId); d.mkdirs()
        val tmp = File(d, "SOUL.md.tmp"); tmp.writeText(body)
        if (!tmp.renameTo(File(d, "SOUL.md"))) { File(d, "SOUL.md").writeText(body); tmp.delete() }
    }
    override fun versions(botId: String): List<Souls.Version> = (hist(botId).listFiles() ?: emptyArray()).mapNotNull { f ->
        val v = f.name.removeSuffix(".md").toIntOrNull() ?: return@mapNotNull null
        val t = f.readText(); val nl = t.indexOf('\n'); if (nl < 0) return@mapNotNull null
        val head = t.substring(0, nl).split(' ', limit = 2)
        Souls.Version(botId, v, t.substring(nl + 1), head[0].toLongOrNull() ?: 0, head.getOrElse(1) { "user" })
    }.sortedByDescending { it.version }
    override fun putVersion(v: Souls.Version) { hist(v.botId).mkdirs(); File(hist(v.botId), "${v.version}.md").writeText("${v.at} ${v.by}\n${v.body}") }
    override fun dropVersion(botId: String, version: Int) { File(hist(botId), "$version.md").delete() }
    override fun delete(botId: String) { dir(botId).deleteRecursively() }
}

/** In-memory [SoulPort] for tests and previews. */
class MemorySoulPort : SoulPort {
    private val files = HashMap<String, String>(); private val vs = HashMap<String, MutableMap<Int, Souls.Version>>()
    @Synchronized override fun read(botId: String) = files[botId]
    @Synchronized override fun write(botId: String, body: String) { files[botId] = body }
    @Synchronized override fun versions(botId: String) = vs[botId]?.values?.sortedByDescending { it.version } ?: emptyList()
    @Synchronized override fun putVersion(v: Souls.Version) { vs.getOrPut(v.botId) { HashMap() }[v.version] = v }
    @Synchronized override fun dropVersion(botId: String, version: Int) { vs[botId]?.remove(version) }
    @Synchronized override fun delete(botId: String) { files.remove(botId); vs.remove(botId) }
}

/**
 * Souls for the team: read (template on first read, repair of a file that no longer parses), save with history, Undo,
 * add a standing rule, regenerate, reset. Every save writes the file and records the version; the history keeps the
 * newest [Souls.HISTORY]. [onSaved] lets the TeamStore bump Bot.soulVersion (P1-TM-01 allows that on the Manager too).
 */
class SoulStore(private val port: SoulPort, private val clock: () -> Long = System::currentTimeMillis) {
    var onSaved: ((String, Int) -> Unit)? = null

    data class Loaded(val soul: Souls.Soul, val repaired: String?)

    /** The soul of [p.id]. A missing file gets the template; one that no longer parses is kept as a version and replaced. */
    @Synchronized fun get(p: Souls.Profile): Loaded {
        val raw = port.read(p.id)
        if (raw == null) { val t = Souls.template(p); persist(t, "app"); return Loaded(t, null) }
        return when (val r = Souls.parse(raw)) {
            is Souls.Parsed.Ok -> {
                var s = r.soul.copy(id = p.id, name = p.name, label = if (p.isManager) "Manager" else p.label)
                if (p.isManager) {   // the Manager's core can't be edited even by hand in the file
                    val t = Souls.template(p)
                    val sec = LinkedHashMap(s.sections); for (h in t.locked) sec[h] = t.section(h)
                    s = s.copy(sections = sec, locked = t.locked)
                }
                Loaded(s, null)
            }
            is Souls.Parsed.Bad -> {
                val prev = port.versions(p.id).firstOrNull()?.let { (Souls.parse(it.body) as? Souls.Parsed.Ok)?.soul }
                val fixed = (prev ?: Souls.template(p)).let { it.copy(version = maxOf(it.version, (port.versions(p.id).maxOfOrNull { v -> v.version } ?: 0)) + 1) }
                port.putVersion(Souls.Version(p.id, fixed.version - 1, raw, clock(), "unparsed"))
                persist(fixed, "app"); Loaded(fixed, r.why)
            }
        }
    }

    private fun persist(s: Souls.Soul, by: String) {
        val body = Souls.render(s)
        port.write(s.id, body)
        port.putVersion(Souls.Version(s.id, s.version, body, clock(), by))
        val all = port.versions(s.id)
        for (v in all.drop(Souls.HISTORY)) port.dropVersion(s.id, v.version)
        onSaved?.invoke(s.id, s.version)
    }

    @Synchronized fun save(p: Souls.Profile, text: String, by: String = "user"): Souls.Edit {
        val r = Souls.edit(get(p).soul, text)
        if (r is Souls.Edit.Saved) persist(r.soul, by)
        return r
    }

    @Synchronized fun addRule(p: Souls.Profile, rule: String, by: String = "learn"): Souls.Edit {
        val r = Souls.addRule(get(p).soul, rule); if (r is Souls.Edit.Saved) persist(r.soul, by); return r
    }

    @Synchronized fun removeRule(p: Souls.Profile, rule: String, by: String = "user"): Souls.Edit {
        val r = Souls.removeRule(get(p).soul, rule); if (r is Souls.Edit.Saved) persist(r.soul, by); return r
    }

    @Synchronized fun regenerate(p: Souls.Profile): Souls.Soul = Souls.regenerate(get(p).soul, p).also { persist(it, "regenerate") }
    @Synchronized fun reset(p: Souls.Profile): Souls.Soul = Souls.reset(get(p).soul, p).also { persist(it, "reset") }

    fun history(botId: String): List<Souls.Version> = Souls.trim(port.versions(botId))

    /** Undo the last edit: the previous version's text becomes the newest version (history is never rewritten). */
    @Synchronized fun undo(p: Souls.Profile): Souls.Edit {
        val cur = get(p).soul
        val prev = history(p.id).firstOrNull { it.version < cur.version } ?: return Souls.Edit.Refused("nothing to undo")
        return revert(p, prev.version, "undo")
    }

    /** Back to version [version] (from the history sheet), as a new version. Locked sections stay as they are now. */
    @Synchronized fun revert(p: Souls.Profile, version: Int, by: String = "revert"): Souls.Edit {
        val v = history(p.id).firstOrNull { it.version == version } ?: return Souls.Edit.Refused("version $version is no longer kept")
        val r = Souls.edit(get(p).soul, v.body)
        if (r is Souls.Edit.Saved) persist(r.soul, by)
        return r
    }

    fun delete(botId: String) = port.delete(botId)
}
