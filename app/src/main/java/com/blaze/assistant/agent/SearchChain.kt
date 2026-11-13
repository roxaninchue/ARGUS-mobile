package com.blaze.assistant.agent

/**
 * 0.53.0 (owner, 13 Nov 2026: "web search must ALWAYS work"): the order [Web.search] tries its keyless sources in, and the
 * simpler-words retry. Pure (SearchChainTest).
 *
 *  - Every query: DuckDuckGo HTML → Bing RSS → Bing News RSS → Google News RSS → Wikipedia.
 *  - A news-shaped query (news, headlines, a CEO, an election, scores, who resigned…) asks the news feeds first: Bing News → Google
 *    News → Bing → DuckDuckGo → Wikipedia, so "latest news NVIDIA CEO" gets dated articles, not a company home page.
 *  - Nothing anywhere: the same sources once more with [simpler] words (filler, time words and "news" dropped).
 * A block (bot check, HTML instead of a feed) moves on to the next source; it is never read as "nothing exists".
 */
object SearchChain {
    enum class Source { DDG, BING, BING_NEWS, GOOGLE_NEWS, WIKIPEDIA }

    const val NAMES = "DuckDuckGo, Bing, Bing News, Google News or Wikipedia"

    private val news = Regex("""(?i)\b(?:news|headlines?|breaking|yesterday|this (?:week|morning|evening)|just (?:happened|announced)|announced?|resign(?:s|ed|ation)?|fired|sacked|appointed|ceo|president|prime minister|minister|election|elections|scores?|match|fixtures?|transfer|died|dies|death|scandal|arrested|lawsuit|strike|protests?)\b""")

    fun newsy(q: String): Boolean = news.containsMatchIn(q)

    fun order(newsy: Boolean): List<Source> =
        if (newsy) listOf(Source.BING_NEWS, Source.GOOGLE_NEWS, Source.BING, Source.DDG, Source.WIKIPEDIA)
        else listOf(Source.DDG, Source.BING, Source.BING_NEWS, Source.GOOGLE_NEWS, Source.WIKIPEDIA)

    /** The retry with simpler words skips DuckDuckGo (it blocks repeat queries hardest). */
    fun retryOrder(newsy: Boolean): List<Source> = order(newsy).filter { it != Source.DDG }

    private val drop = Regex("""(?i)\b(?:please|can you|could you|search(?: the web| online)?(?: for)?|look up|google|find|tell me|what(?:'s| is| are)?|who(?:'s| is)?|the|a|an|about|on|for|of|latest|newest|recent|current|news|headlines?|today|now|right now|updates?|info(?:rmation)?|me|any)\b""")

    /**
     * Fewer, stronger words for the retry: "latest news NVIDIA CEO" → "NVIDIA CEO". Null when nothing simpler is left
     * (the same words, or fewer than one real word).
     */
    fun simpler(q: String): String? {
        val s = q.replace(drop, " ").replace(Regex("""[?!.,"“”]+"""), " ").replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty() || s.equals(q.trim(), ignoreCase = true)) return null
        return s.takeIf { it.split(' ').any { w -> w.length >= 2 } }
    }
}
