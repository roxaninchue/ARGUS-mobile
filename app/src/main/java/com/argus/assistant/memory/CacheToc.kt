package com.argus.assistant.memory

/**
 * Tables of contents for cached documents (0.49.0). Only these ever reach a prompt; the text stays on the phone.
 *
 *  - Up to [FLAT_MAX] sections: one line each, `§i title — gist`.
 *  - More: hierarchical. Sections are grouped by chapter (the latest level 1–2 heading); a document with no usable
 *    chapters is grouped in runs of [GROUP] sections. The top level lists the groups (`ch k · title · §a–b · gist`);
 *    `cache_toc(doc, chapter=k)` lists the sections of group k.
 *
 * Pure; CacheTocTest.
 */
object CacheToc {
    const val FLAT_MAX = 24
    const val GROUP = 16
    /** A chapter list longer than this falls back to fixed runs of [GROUP] (too many tiny chapters). */
    const val CHAPTERS_MAX = 40

    data class Group(val n: Int, val title: String, val from: Int, val to: Int, val gist: String)

    fun groups(secs: List<CacheStore.Section>): List<Group> {
        if (secs.isEmpty()) return emptyList()
        val byChapter = ArrayList<Group>()
        var start = 0
        for (i in 1..secs.size) {
            if (i == secs.size || secs[i].chapter != secs[start].chapter) {
                val t = secs[start].chapter.ifBlank { secs[start].title }
                byChapter += Group(byChapter.size + 1, t, secs[start].idx, secs[i - 1].idx, secs[start].gist)
                start = i
            }
        }
        val useful = byChapter.size in 2..CHAPTERS_MAX && secs.any { it.chapter.isNotBlank() }
        if (useful) return byChapter
        return secs.chunked(GROUP).mapIndexed { k, c -> Group(k + 1, c.first().title, c.first().idx, c.last().idx, c.first().gist) }
    }

    fun line(s: CacheStore.Section): String = "§${s.idx} ${s.title}" + (if (s.gist.isNotBlank() && s.gist != s.title) " — ${s.gist}" else "")

    fun header(d: CacheStore.Doc): String = "doc:${d.id} \"${d.name}\" · ${d.sections} sections · ~${tok(d.chars)} tokens" +
        (if (d.truncated) " · (cut: file was too big)" else "")

    /** The TOC of [d]: flat, the top level of a hierarchy, or (with [chapter]) one group's sections. */
    fun toc(d: CacheStore.Doc, secs: List<CacheStore.Section>, chapter: Int = 0, maxChars: Int = 6_000): String {
        val sb = StringBuilder(header(d)).append('\n')
        if (secs.size <= FLAT_MAX && chapter == 0) {
            for (s in secs) if (!add(sb, line(s), maxChars)) return more(sb, d)
            return sb.toString().trimEnd()
        }
        val gs = groups(secs)
        if (chapter > 0) {
            val g = gs.getOrNull(chapter - 1) ?: return "Error: doc:${d.id} has chapters 1–${gs.size}."
            sb.append("ch $chapter · ${g.title} (§${g.from}–§${g.to})\n")
            for (s in secs.filter { it.idx in g.from..g.to }) if (!add(sb, "  " + line(s), maxChars)) return more(sb, d)
            return sb.toString().trimEnd()
        }
        sb.append("(hierarchical: cache_toc with chapter=k lists a chapter's sections)\n")
        for (g in gs) if (!add(sb, "ch ${g.n} · ${g.title} · §${g.from}–§${g.to}" + (if (g.gist.isNotBlank()) " — ${g.gist}" else ""), maxChars)) return more(sb, d)
        return sb.toString().trimEnd()
    }

    private fun add(sb: StringBuilder, l: String, max: Int): Boolean {
        if (sb.length + l.length + 1 > max) return false
        sb.append(l).append('\n'); return true
    }

    private fun more(sb: StringBuilder, d: CacheStore.Doc) = sb.append("… (more: cache_toc doc=${d.id} with chapter=k, or cache_search)").toString()

    fun tok(chars: Long): String = (chars / 4).let { if (it < 1000) "$it" else "${it / 1000}k" }
}
