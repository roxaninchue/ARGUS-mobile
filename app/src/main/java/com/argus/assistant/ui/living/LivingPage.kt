package com.blaze.assistant.ui.living

/**
 * Living UI 0.2.0: what a browser card shows of a page. The tools hand the model text made for the model ("Opened X
 * (url).", "Page: X — url", "Scroll: …", "On screen: …"); the card wants the page's own title and words. And the
 * picture: [pick] chooses which captured page picture (LivingShots) belongs to a card.
 *
 * Pure: LivingPageTest.
 */
object LivingPage {
    private val opened = Regex("^Opened (.+?) \\((https?://\\S+)\\)\\.?$")
    private val pageLine = Regex("^Page:\\s*(.+?)(?:\\s+[\u2014-]\\s+(https?://\\S+))?$")
    private val modelLines = Regex("^(Opened |Page:|Scroll:|On screen:|Tabs?:|\\[|URL:|Title:|Elements?:|Links?:|Buttons?:)")

    /** (title, the page's own words) from a page tool's output. */
    fun read(out: String): Pair<String, String> {
        val lines = out.trim().lines()
        var title = ""
        for (l in lines.take(4)) {
            val t = l.trim()
            opened.find(t)?.let { if (title.isBlank()) title = it.groupValues[1].trim() }
            pageLine.find(t)?.let { if (title.isBlank()) title = it.groupValues[1].trim() }
            if (t.startsWith("Title:", true) && title.isBlank()) title = t.substringAfter(':').trim()
        }
        if (title.isBlank()) {
            // no tool header: the first short line is the title (LivingModel.page), the rest is the page's words
            val (t, b) = LivingModel.page(out)
            return t to words(b.lines()).ifBlank { b }
        }
        return title to words(lines)
    }

    /** 0.2.4: the page's address from a browser tool's header ("Opened X (url).", "Page: X — url"), else "". */
    fun url(out: String): String {
        for (l in out.trim().lines().take(4)) {
            val t = l.trim()
            opened.find(t)?.let { return it.groupValues[2] }
            pageLine.find(t)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }?.let { return it }
            if (t.startsWith("URL:", true)) t.substringAfter(':').trim().takeIf { it.startsWith("http") }?.let { return it }
        }
        return ""
    }

    private val elementLine = Regex("^\\d{1,3}[.)]\\s*\\[")

    /** The page's own words: the model's header lines and element lists ("12. [button] Search") left out. */
    private fun words(lines: List<String>): String = lines.map { it.trim() }
        .filter { it.isNotEmpty() && !modelLines.containsMatchIn(it) && !elementLine.containsMatchIn(it) }
        .joinToString("\n")

    /** Site name for the masthead: "gsmarena.com" → "GSMArena" stays as the host, without www. and m. */
    fun site(url: String): String = LivingModel.host(url).removePrefix("m.").ifBlank { url }

    /** A short date-free line under the title: the first sentence of the page's words. */
    fun lead(body: String, max: Int = 160): String {
        val one = body.lineSequence().map { it.trim() }.firstOrNull { it.length >= 20 } ?: body.trim()
        return LivingModel.clip(one, max)
    }

    data class ShotRef(val url: String, val at: Long)

    /**
     * The picture for a browser card that started at [from] (until [until], the next browser card, or open-ended):
     * the newest shot taken in that window whose address matches the card's, else the newest in the window.
     * Returns the index in [shots], or null.
     */
    fun pick(shots: List<ShotRef>, url: String, from: Long, until: Long?): Int? {
        val inWin = shots.indices.filter { shots[it].at >= from - 500 && (until == null || shots[it].at < until) }
        if (inWin.isEmpty()) return null
        val want = norm(url)
        return inWin.lastOrNull { want.isNotBlank() && norm(shots[it].url).let { u -> u == want || u.startsWith(want) || want.startsWith(u) } } ?: inWin.last()
    }

    private fun norm(u: String) = u.trim().substringAfter("://").removePrefix("www.").removeSuffix("/").lowercase()

    /** A captured picture that is one flat colour (a page not painted yet) is not shown. */
    fun blank(samples: IntArray): Boolean = samples.isEmpty() || samples.all { it == samples[0] }
}
