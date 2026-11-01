package com.blaze.assistant.agent

/**
 * 0.49.0 (mem1-ui-browse): when does Blaze actually need the live web?
 *
 * Report: "certain questions and a couple of other styles automatically make Blaze look like it's online and it fetches
 * pages unrelated to the context". Two separate faults, both fixed here:
 *
 *  1. THE TRIGGER. [LookupLane.isLookup] used to accept any text starting with a bare interrogative or a soft verb
 *     (`is|are|was|were|do|does|did|can|check|tell me|where|why|latest|current`). So "check the time", "tell me
 *     something interesting", "where am I", "is that right?" and "can you do that again" all flipped Blaze into a
 *     browsing state and it went to the web for things it already knew or could see on the phone. A question word is
 *     not evidence that the answer lives on the internet.
 *
 *     The rule now: browsing fires only when the answer can CHANGE or the user asked to search. That means an explicit
 *     search verb ("search for", "look up", "google", "find out") or a live-data marker ("latest", "current price",
 *     "today", "news", "who won", "release date", "exchange rate"...). A static fact ("what is the capital of
 *     France") is answered from the model, not from a page. Anything about the phone, the clock, the weather card, a
 *     sum, a memory or Blaze itself never browses.
 *
 *  2. THE PAGES. The search query was whatever the model put in the tool call, and every hit was fetched and fed to the
 *     answer model regardless of whether it had anything to do with the request - which is how unrelated pages ended up
 *     in the context. [queryFor] builds the query from the request itself (conversational lead-ins stripped), and
 *     [pick] drops hits that share no content word with it.
 *
 * Pure Kotlin, no Android, no network: unit-tested in BrowseIntentTest.
 */
object BrowseIntent {

    /** What the turn needs. [INTERACTION] is the browser (sign-in, form, booking), never the lookup lane. */
    enum class Need { NONE, LIVE_WEB, INTERACTION }

    // ---------------------------------------------------------------- vocabulary

    /** The user asked to search, in so many words. */
    private val searchVerb = Regex(
        """(?i)\b(?:search(?:\s+(?:the\s+)?(?:web|internet|online))?(?:\s+for)?|look\s+up|lookup|google|find\s+out|find\s+me|""" +
            """browse\s+(?:for|the\s+web)|check\s+(?:online|the\s+web|the\s+internet))\b""")

    /**
     * The answer can change: freshness, a price, a score, a release, a rate. These are the only things worth a page
     * when the user did not explicitly ask to search.
     */
    private val liveMarker = Regex(
        """(?i)\b(?:latest|newest|current(?:ly)?|right\s+now|today|tonight|tomorrow|yesterday|this\s+(?:week|month|year)|""" +
            """news|headlines?|breaking|price(?:s|d)?|cost(?:s|ing)?|how\s+much\s+(?:is|are|does|do)|rate|rates|""" +
            """exchange\s+rate|stock|shares?|market|score|scores?|result|results|standings?|who\s+won|""" +
            """release\s+date|out\s+now|available|in\s+stock|open\s+now|closing|deadline|schedule|fixtures?|""" +
            """weather|forecast|traffic|flight|flights|status\s+of|update\s+on|when\s+(?:is|does|will)|""" +
            """how\s+(?:old|tall|big|far|fast|long)\s+is|population|ranking|version|changelog)\b""")

    /** Things Blaze answers without the web: itself, the clock, the weather card, sums, a chat, a memory. */
    private val notWeb = Regex(
        """(?i)\b(?:you|your|yours|yourself|blaze|halo|time\s+is\s+it|what\s+time|today'?s\s+date|what\s+day|""" +
            """how\s+are\s+you|joke|poem|story|translate|calculate|meaning\s+of\s+life|remember|""" +
            """my\s+(?:phone|files?|photos?|messages?|texts?|calendar|alarms?|contacts?|notes?|music|songs?|battery|""" +
            """storage|settings|apps?|email|inbox|screen|device))\b|^[\d\s+\-*/().x=?]+$""")

    /** The phone itself: never a web lookup, whatever the phrasing. */
    private val personal = Regex(
        """(?i)\b(?:remind|alarm|timer|text\s+(?:him|her|them|mom|dad)|call\s+\w|open\s+\w|play\s+\w|turn\s+(?:on|off)|""" +
            """set\s+(?:a|an|the)\s+\w|send\s+\w|screenshot|on\s+(?:my|this)\s+phone|you\s+(?:remember|know\s+about\s+me))\b""")

    /** Needs the browser (a sign-in, a form, a click), not a search. */
    private val needsInteraction = Regex(
        """(?i)\b(?:book|buy|order|sign\s+(?:me\s+)?(?:in|up)|log\s?in|fill\s+(?:in|out)|apply|checkout|reserve|""" +
            """subscribe|my\s+account|download|upload|pay\s+for|renew)\b""")

    /** Conversational lead-ins that carry no searchable content. */
    private val leadIn = Regex(
        """(?i)^\s*(?:hey\s+blaze|ok\s+blaze|okay\s+blaze|blaze|please|pls|so|and|but|um+|uh+|well|""" +
            """can\s+you|could\s+you|would\s+you|will\s+you|do\s+you\s+mind|i\s+want\s+you\s+to|i\s+need\s+you\s+to|""" +
            """i\s+want\s+to\s+know|i'?d\s+like\s+to\s+know|tell\s+me|let\s+me\s+know|help\s+me|""" +
            """what(?:'s|\s+is)|whats|who(?:'s|\s+is)|where(?:'s|\s+is)|when(?:'s|\s+is)|how(?:'s|\s+is))\b[\s,]*""")

    private val stop = setOf(
        "the", "and", "for", "with", "from", "that", "this", "these", "those", "what", "whats", "how", "who", "whom",
        "when", "where", "why", "which", "are", "is", "was", "were", "can", "could", "does", "did", "do", "will",
        "would", "should", "get", "got", "use", "about", "best", "new", "top", "free", "online", "near", "me", "my",
        "your", "you", "please", "tell", "give", "show", "find", "search", "look", "some", "any", "there", "here",
        "have", "has", "had", "not", "but", "just", "now", "today", "latest", "current", "much", "many", "more",
        "most", "very", "really", "actually", "also", "than", "then", "them", "they", "its", "it's", "a", "an", "of",
        "in", "on", "at", "to", "by", "or", "if", "as", "be", "been", "being", "i", "we", "he", "she", "it")

    // ---------------------------------------------------------------- the decision

    /**
     * What this turn needs. [hasLocalAnswer] lets a caller that already knows the answer (a cached fact, a card on Home)
     * suppress the lookup entirely.
     */
    fun decide(text: String, hasLocalAnswer: Boolean = false): Need {
        val t = text.trim()
        if (t.length < 6 || t.length > 240 || t.contains('\n')) return Need.NONE
        if (t.startsWith("http", true) || t.startsWith("@") || t.startsWith("/")) return Need.NONE
        // Greetings and small talk never browse ("what's up??").
        if (SmallTalk.ruleChat(t)) return Need.NONE
        // The phone, the clock, the weather card, a sum, a memory, Blaze itself: answered here.
        if (notWeb.containsMatchIn(t) || personal.containsMatchIn(t)) return Need.NONE
        if (hasLocalAnswer) return Need.NONE
        // A sign-in, a form, a booking: the browser, not the lookup lane.
        if (needsInteraction.containsMatchIn(t)) return Need.INTERACTION
        // The fix: a question word alone is NOT a reason to go online. The answer must be able to change, or the user
        // must have asked to search.
        if (searchVerb.containsMatchIn(t)) return Need.LIVE_WEB
        if (liveMarker.containsMatchIn(t)) return Need.LIVE_WEB
        return Need.NONE
    }

    /** Convenience for [LookupLane]: the fast lane takes only genuine live-web lookups. */
    fun isLiveLookup(text: String): Boolean = decide(text) == Need.LIVE_WEB

    // ---------------------------------------------------------------- the query

    /** Content words of [s], lowercased, stop words and lead-ins removed. */
    fun words(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stop }.toSet()

    /**
     * The search query for [text]: the request with its conversational lead-in and trailing politeness removed, so the
     * results are about what was asked and not about "can you please". Falls back to the trimmed text.
     */
    fun queryFor(text: String): String {
        var q = text.trim().replace(Regex("[?!.,]+$"), "")
        q = q.replace(leadIn, "")
        q = q.replace(Regex("(?i)[,.!]*\\s*(?:thanks|thank\\s+you|thx|pls|plz|please|bro|mate|boss|man|asap|real\\s+quick)[.!]*$"), "")
        q = q.replace(Regex("\\s+"), " ").trim()
        return q.ifBlank { text.trim() }
    }

    /**
     * Is [hit] about [query]? True when the title, url or snippet shares a content word with the query. A hit with no
     * overlap at all is the "unrelated page" the report describes.
     */
    fun relevant(hit: LookupLane.Hit, query: String): Boolean {
        val q = words(query)
        if (q.isEmpty()) return true
        val h = words(hit.title + " " + hit.url + " " + hit.snippet)
        return h.any { it in q }
    }

    /**
     * The hits worth fetching: the relevant ones, in the search engine's order. When nothing overlaps (a query made of
     * words the engine paraphrased), the original list is kept rather than returning nothing.
     */
    fun pick(hits: List<LookupLane.Hit>, query: String): List<LookupLane.Hit> {
        val kept = hits.filter { relevant(it, query) }
        return kept.ifEmpty { hits }
    }
}
