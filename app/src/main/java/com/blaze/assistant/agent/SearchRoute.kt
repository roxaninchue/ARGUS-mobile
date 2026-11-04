package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
/**
 * 0.50.0 (owner, 2026-11-01: asked "what "/" commands do I have available?" and Blaze searched the web and answered about
 * Windows help pages). The old fast lane ([LookupLane.isLookup], 0.46.3) took ANY message that started with a question word
 * ("what", "how", "is", "can", "who"…) to the web, pulling pages unrelated to the conversation. That keyword trigger is gone:
 *
 *  - [Route.LOCAL]: questions about Blaze itself, the app, its commands, this conversation, or the user's own data (memory,
 *    phone, files, calendar…) are answered from local context and the bundled docs (about_blaze, blaze_docs_search,
 *    memory_search, the command registry). web_search is refused for them ([webNote]).
 *  - [Route.COMMANDS]: "what / commands do I have" is answered straight from the slash-command registry ([SlashCommands]).
 *  - [Route.WEATHER]: weather goes to get_weather (the weather tool), not the web lane.
 *  - [Route.SEARCH]: the user explicitly asked to search ("search the web for…", "google…", "look up … online"): the quick
 *    lookup lane runs, with a query built from the conversation ([query]), not the raw sentence.
 *  - [Route.CHAT]: small talk.
 *  - [Route.MODEL]: everything else. No automatic search: the model sees web_search among its tools and calls it only when
 *    it needs fresh or external facts.
 * Pure (SearchRouteTest).
 */
object SearchRoute {
    enum class Route { LOCAL, COMMANDS, WEATHER, SEARCH, CHAT, MODEL }

    /** The model's rule (every prompt): search only when it needs the outside world, and search for the conversation's subject. */
    const val PROMPT = "\nWEB SEARCH: call web_search only when the answer needs fresh or external facts you don't have (news, prices, scores, " +
        "opening hours, recent releases, a page the user named). Never search for questions about ${BrandName.NAME}, this app, its / commands, " +
        "this conversation or the user's own data: answer those from your context, about_blaze, blaze_docs_search, memory_search or the " +
        "phone tools. When you do search, write the query about the conversation's actual subject (resolve \"it\", \"there\", \"that\" from " +
        "earlier turns), not the user's raw sentence. Weather: get_weather."

    private val slashWords = Regex("""(?i)(?:["'“”‘’`(]\s*/\s*["'“”‘’`)]|(?:^|\s)/(?=\s|$|\?)|\bslash\b|\bslash[- ]commands?\b)""")
    private val commandsAsk = Regex("""(?i)\b(?:commands?|shortcuts?)\b""")
    private val listAsk = Regex("""(?i)\b(?:what|which|list|show|tell|all|available|have|got|are there|can i (?:use|type))\b""")

    /** "what / commands do I have", "list the slash commands", "which commands can I type?", "/help". */
    fun isCommandsAsk(text: String): Boolean {
        val t = text.trim()
        if (t.length > 160) return false
        if (t.equals("/help", true) || t.equals("/commands", true)) return true
        if (!commandsAsk.containsMatchIn(t)) return false
        if (slashWords.containsMatchIn(t)) return true
        // "what commands do you have / does blaze support", "which commands can I use here"
        return listAsk.containsMatchIn(t) && Regex("""(?i)\b(?:you|your|blaze|halo|here|this app|the app|i have|can i|do i|i can|available|support)\b""").containsMatchIn(t)
    }

    /** About Blaze, the app, its features, settings, commands or views. */
    private val aboutBlaze = Regex("""(?i)\b(?:blaze|halo|you|your|yourself|this app|the app|this chat|this conversation|our (?:chat|conversation)|earlier (?:in|today)|you said|i (?:said|told you|asked)|we (?:talked|discussed|said)|(?:spotlight|composer|chat box|home screen|on-phone brain|terminal|skills?|views?|projects?|routines?|settings?|stage|halo) in blaze)\b""")
    private val aboutBlazeTopic = Regex("""(?i)\b(?:feature|features|setting|settings|command|commands|view|views|skill|skills|mode|modes|version|update|changelog|memory|remember|forget|can (?:you|blaze) (?:do|see|read|control|open)|how do (?:i|you) (?:use|turn|switch|open|set)|work|works|support|supports|able)\b""")
    /** The user's own data on this phone or in their connected accounts. */
    private val ownData = Regex("""(?i)\b(?:my|mine|i have|do i have|did i|have i|am i)\b.{0,40}\b(?:phone|files?|photos?|pictures?|gallery|messages?|texts?|sms|whatsapp|calendar|events?|meetings?|alarms?|reminders?|timers?|contacts?|notes?|music|songs?|playlists?|battery|storage|settings?|apps?|email|emails|inbox|mail|memory|memories|tasks?|to-?dos?|routines?|projects?|chats?|downloads?|documents?|skills?|secrets?|keys?|accounts?|budget|spending|balance|schedule|agenda)\b""")
    private val ownDataAfter = Regex("""(?i)\b(?:photos?|pictures?|files?|messages?|texts?|alarms?|reminders?|contacts?|notes?|songs?|apps?|emails?|tasks?|to-?dos?|routines?|projects?|chats?|downloads?|documents?|skills?|secrets?|accounts?|events?|meetings?)\b.{0,30}\b(?:do i have|have i got|i have|i'?ve got|did i)\b""")
    private val conversation = Regex("""(?i)\b(?:what did (?:i|we|you) (?:say|ask|talk|discuss|decide)|what were we|remind me what|earlier you|you (?:just )?said|summari[sz]e (?:this|our|the) (?:chat|conversation)|in this chat|so far)\b""")

    /** A question answered from Blaze's own context, docs or the user's data: never the web. */
    fun isLocal(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (isCommandsAsk(t)) return true
        if (BlazeDocs.isAboutBlaze(t)) return true
        if (conversation.containsMatchIn(t) || ownData.containsMatchIn(t) || ownDataAfter.containsMatchIn(t)) return true
        return aboutBlaze.containsMatchIn(t) && aboutBlazeTopic.containsMatchIn(t) && !worldFact.containsMatchIn(t)
    }

    /** Words that make a "you"-question about the world after all ("can you find the price of…"). */
    private val worldFact = Regex("""(?i)\b(?:price|prices|news|score|scores|exchange rate|stock|bitcoin|election|population|capital of|weather in|forecast for|flights?|hotel|restaurant|near me|opening hours)\b""")

    private val weather = Regex("""(?i)\b(?:weather|forecast|temperature|rain(?:ing)?|sunny|humid(?:ity)?|wind(?:y)?|storm|snow(?:ing)?|degrees)\b""")
    private val weatherQ = Regex("""(?i)\b(?:what'?s|what is|how'?s|how is|will it|is it|going to|gonna|today|tomorrow|tonight|this week|weekend|in [A-Z]|in \w+|\?)""")
    fun isWeather(text: String): Boolean = weather.containsMatchIn(text) && weatherQ.containsMatchIn(text) && !ownData.containsMatchIn(text)

    /** Only an explicit request to search the web (the user's words, not a guess from "what" or "how"). */
    private val explicit = Regex("""(?i)^\s*(?:(?:please|can you|could you|would you|pls|hey blaze,?|blaze,?)\s+)*(?:search(?: the web| online| the internet| google)?(?: for)?|google|look up|lookup|web search|find (?:online|on the web)|check online|browse for)\b\s*(.+)$""")
    private val explicitTail = Regex("""(?i)^(.+?)\s*[,-]?\s*(?:search (?:it |that )?(?:online|on the web|the web)|look (?:it |that )?up online|google (?:it|that))\s*[.!?]*$""")

    /** The thing to search for, or null when the message is not an explicit search request. */
    fun explicitTopic(text: String): String? {
        val t = text.trim()
        if (t.length < 4 || t.length > 300 || t.contains('\n') || t.startsWith("/")) return null
        explicit.find(t)?.let { m -> val q = m.groupValues[1].trim().trimEnd('?', '.', '!'); return q.takeIf { it.length >= 2 } }
        explicitTail.find(t)?.let { m -> val q = m.groupValues[1].trim().trimEnd('?', '.', '!'); return q.takeIf { it.length >= 2 } }
        return null
    }

    fun route(text: String): Route {
        val t = text.trim()
        if (t.isEmpty()) return Route.CHAT
        if (isCommandsAsk(t)) return Route.COMMANDS
        if (SmallTalk.ruleChat(t)) return Route.CHAT
        if (explicitTopic(t) != null && !isLocal(t)) return Route.SEARCH
        if (isLocal(t)) return Route.LOCAL
        if (isWeather(t)) return Route.WEATHER
        return Route.MODEL
    }

    private val questionShape = Regex("""(?i)^\s*(?:what|which|who|whom|whose|when|where|why|how|is|are|was|were|does|did|do|can|could|will|would|should|have|has|tell me|explain|list|show me)\b|\?\s*$""")

    /**
     * What web_search returns instead of searching, or null to search. Refused only for a short QUESTION that is about Blaze,
     * this conversation or the user's own data (a task like "check my email, then search flights" still searches) and for
     * the "/ commands" question.
     */
    fun webNote(request: String): String? {
        val t = request.trim()
        if (t.isEmpty() || t.startsWith("[")) return null
        return when (route(t)) {
            Route.COMMANDS -> "Not run: the user asked about ${BrandName.NAME}'s own / commands. Answer from this list, not the web:\n" + SlashCommands.answer()
            Route.LOCAL -> if (t.length <= 200 && !t.contains('\n') && questionShape.containsMatchIn(t) && !worldFact.containsMatchIn(t))
                "Not run: this is about ${BrandName.NAME}, this conversation or the user's own data, which the web doesn't know. " +
                    "Answer from your context, about_blaze, blaze_docs_search, memory_search or the phone tools." else null
            // small talk never reaches the tools (ChatLane); a bare "yes" / "ok" may approve a search Blaze offered, so it is not refused here
            else -> null
        }
    }

    // ---------------- the search query, from the conversation ----------------

    private val filler = Regex("""(?i)\b(?:please|can you|could you|would you|pls|for me|real quick|quickly|hey|blaze|halo|i want to know|tell me|do you know|i wonder|search(?: the web| online| google)?(?: for)?|google|look up|lookup|online|on the web|on google)\b""")
    private val pronoun = Regex("""(?i)\b(?:it|its|it's|that|this|those|these|they|them|there|he|she|him|her|the same|that one|this one)\b""")
    private val stop = setOf("the", "and", "for", "with", "from", "that", "this", "what", "how", "who", "when", "where", "why", "which", "are", "is",
        "was", "can", "does", "did", "get", "use", "about", "you", "your", "me", "my", "i", "a", "an", "of", "to", "in", "on", "at", "it", "its",
        "do", "be", "so", "but", "or", "if", "we", "our", "us", "just", "ok", "okay", "yes", "no", "there", "they", "them", "then", "now", "here",
        "want", "need", "know", "think", "like", "some", "any", "more", "also", "too", "very", "really", "will", "would", "should", "could", "has", "have",
        "had", "been", "being", "were", "am", "let", "lets", "please", "thanks", "sure", "good", "great", "nice", "one", "out", "up", "same", "those", "these",
        "thinking", "buying", "going", "getting", "looking", "trying", "using", "making", "much", "many", "choice", "plenty", "maybe", "actually", "probably",
        "i'm", "i'll", "i've", "i'd", "it's", "that's", "what's", "how's", "there's", "let's", "don't", "can't", "won't", "isn't")

    private fun proper(w: String) = w.any { it.isDigit() } || (w.first().isUpperCase() && w.length >= 2)
    private fun keywords(s: String): List<String> = s.split(Regex("[^\\p{L}\\p{N}'+.#-]+")).map { it.trim('.', '\'', '-') }
        .filter { w -> w.isNotEmpty() && w.lowercase() !in stop && (w.length >= 3 || (w.length >= 2 && proper(w))) }

    /**
     * The search query for [text], read in the light of the conversation: filler and command words dropped, and when the
     * message leans on earlier turns ("how much does it cost there?", "and the latest version?") the subject words of the most
     * recent turn that has them ([recent], newest LAST) are added. Never longer than [max] characters.
     */
    fun query(text: String, recent: List<String> = emptyList(), max: Int = 120): String {
        val topic = explicitTopic(text) ?: text
        val core = topic.replace(filler, " ").replace(Regex("\\s+"), " ").trim().trim(',', '.', '?', '!', ' ')
        val own = keywords(core)
        val leans = pronoun.containsMatchIn(core) || own.size <= 2
        val out = LinkedHashSet<String>()
        own.forEach { out += it }
        if (leans) {
            // names and products (capitalised or with digits) from the newest turns first, then other words in order
            val lowOwn = own.map { it.lowercase() }.toSet()
            val cand = recent.asReversed().flatMap { t -> keywords(t) }
                .filter { it.lowercase() !in lowOwn }.distinctBy { it.lowercase() }
            (cand.filter { proper(it) } + cand.filterNot { proper(it) }).take(4).forEach { out += it }
        }
        val q = out.joinToString(" ").ifBlank { core }
        return if (q.length <= max) q else q.take(max).substringBeforeLast(' ')
    }
}
