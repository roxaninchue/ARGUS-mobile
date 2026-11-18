package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
/**
 * Views first (v0.23.0). Blazeinchue's rule: Blaze always uses its views. Output that fits a view (a deck of
 * flashcards, a page, a reminder, a picture, a memory…) is made THERE, the view is opened or linked,
 * and the chat gets a short answer, never the whole thing dumped as text.
 *
 *  - [PROMPT]: the VIEWS block of the system prompt, sent on every turn (each view, what belongs there,
 *    the tool that writes to it). Which view fits a message is the model's call (docs/MODEL-FIRST.md).
 *  - [route]: a keyword classifier. 2026-11-18: no longer read by the chat turn (it injected a
 *    "REQUIRED VIEW THIS TURN" order that overrode the user's words). Still used by ClaimGuard; retire with it.
 *  - [currentDesign]: a plain fact for the turn when a design is on screen.
 *  - [cardsFrom] / [deckName] / [shouldConvert]: the safety net for Learn. When the model still writes
 *    flashcards or a quiz as text without calling the flashcards tool, the agent turns that text into
 *    a Learn deck (or re-prompts once when nothing can be parsed).
 * Pure Kotlin (no Android), unit-tested in ViewRouterTest.
 */
object ViewRouter {

    /** Design cues that are clearly visual work on their own (v0.23.2: Design is for anything visual, not only pages). */
    const val DESIGN_STRONG = """\b(3-?d|three\.?js|webgl|render(?:ing)?|mock-?ups?|wire-?frames?|prototypes?|diagrams?|flow ?charts?|charts?|graphs? (?:of|for|showing)|""" +
        """infographics?|floor ?plans?|blueprints?|3d scenes?|scene of|ui|ux|app screens?|user interface|website|web ?site|web ?page|landing page|html|css|poster|flyer|portfolio|""" +
        """slides?|slide deck|presentation|(?:make|build|create) (?:a |an |me a )?model of|design (?:a|an|me|my|the|this))\b"""

    /** A thing to make or show ("make a rocket", "show me a car"): Design, unless picture words make it Gallery. */
    const val DESIGN_OBJECT = """\b(?:make|build|create|design|draw|model|sketch|visuali[sz]e|show me)\b.{0,40}\b(?:rockets?|spaceships?|space ?shuttles?|cars?|trucks?|bikes?|motorbikes?|""" +
        """planes?|aeroplanes?|airplanes?|jets?|drones?|ships?|boats?|buildings?|houses?|towers?|bridges?|rooms?|kitchens?|bedrooms?|offices?|robots?|chairs?|tables?|desks?|""" +
        """products?|games?|engines?|machines?)\b"""

    /** A view the agent writes to. [tools] = the tools that put things there; [groups] = ToolRouter groups to switch on. */
    data class Spec(val id: String, val name: String, val holds: String, val tools: List<String>, val groups: List<String>, val cue: Regex)

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** In priority order: the first that matches is the required view. */
    /** 0.46.0 Blaze Stage cue: a timer / study or focus time / pomodoro, or "let's play some music" (done in place on the chat). */
    const val STAGE_CUE = """\b(pomodoro|countdown|stopwatch|study timer|focus (?:session|timer|mode)|""" +
        """\d+(?:\.\d+)?\s*-?\s*(?:min|mins|minute|minutes|hr|hrs|hour|hours)\b.{0,24}\b(?:timer|study|studying|focus|work|session|revision|reading)|""" +
        """timer (?:for|of) (?:\d|an? |half)|""" +
        """(?:study|studying|focus|revise|revising|read|reading)\s+(?:for\s+)?(?:\d+(?:\.\d+)?|an?|half an?)\s*(?:min|mins|minute|minutes|hr|hrs|hour|hours)\b|""" +
        """(?:play|put on) (?:some |me some |a little )?(?:music|songs|tunes)|play (?:my|some) (?:mix|playlist|songs)|""" +
        // 0.46.1: the browser as a card on the chat ("pull up browser card", "show the browser here").
        """browser (?:card|widget)|(?:pull|bring) up (?:the |a |my )?(?:web )?browser)"""

    val specs = listOf(
        Spec("stage", "Stage", "live cards right on the chat (no screen change): timers and pomodoros, music playing, a video, a quiz or flashcards to go through, a plan or checklist",
            listOf("stage_show", "stage_update"), listOf("stage", "media"), r(STAGE_CUE)),
        Spec("learn", "Learn", "flashcards, decks, quizzes and anything to study or memorise (spaced repetition)",
            listOf("flashcards", "open_view"), listOf("study"),
            r("""\b(flash ?cards?|quiz(?:zes)?|test me|(?<!case )study|studying|revise|revision|memori[sz]e|spaced repetition|exam prep|cards? (?:on|about|for)|(?<!slide )(?<!pitch )deck)\b""")),
        Spec("schedule", "Schedule", "reminders, routines, repeating tasks, timed jobs and triggers",
            listOf("create_routine", "schedule_command", "create_trigger", "set_alarm", "add_calendar_event", "open_view"), listOf("schedule", "device"),
            r("""\b(remind(?:er|ers)? ?(?:me)?|every (?:day|morning|evening|night|week|weekday|monday|tuesday|wednesday|thursday|friday|saturday|sunday|hour|\d+)|daily|weekly|routine|schedule (?:a|this|it|my)|wake me)\b""")),
        Spec("design", "Design", "anything visual or interactive, live preview: 3D scenes and models (three.js, orbit to look around), 2D art, websites and landing pages, " +
            "UI mockups and app screens, diagrams and charts as files to save or export, posters, flyers, slides, floor plans, product mockups, games",
            listOf(Designs.TOOL, "file_write", "open_view"), listOf("terminal", "media"),
            r(DESIGN_STRONG + "|" + DESIGN_OBJECT)),
        Spec("gallery", "Gallery", "images, pictures, logos, wallpapers, beats, music and other media ${BrandName.NAME} makes",
            listOf("generate_image", "gallery"), listOf("media"),
            r("""\b(?:(?:make|generate|draw|create|paint|produce)\b.{0,40}\b(?:image|picture|pic|photo|logo|icon|wallpaper|art|artwork|illustration|beat|beats|song|instrumental|sticker|avatar)s?|my gallery|in (?:the|my) gallery)\b""")),
        Spec("skills", "Skills", "saved methods and workflows (skills), installing skill packs",
            listOf("save_workflow", "write_skill", "install_skill", "use_skill"), listOf("extras"),
            r("""\b((?:make|save|create|write|install|add)\b.{0,30}\b(?:skills?|workflows?)|skill library|my skills|my workflows|as a (?:skill|workflow))\b""")),
        Spec("projects", "Projects", "ongoing efforts with their own threads, brief and instructions",
            listOf("update_brief", "open_view"), emptyList(),
            r("""\b((?:new|start|create|open) (?:a )?project|my projects|project (?:for|about|called))\b""")),
        Spec("archive", "Archive", "past conversations, searched with search_chat (there is no chat list screen; answer in chat)",
            listOf("search_past_chats", "search_chat"), emptyList(),
            r("""\b((?:old|past|previous|earlier|archived) (?:chats?|conversations?)|chat history|what did (?:we|i) (?:say|talk|discuss)|we talked about|last time we)\b""")),
        Spec("memory", "Memory", "lasting facts about the user, their people, preferences and decisions",
            listOf("memory_save", "remember", "forget", "memory_search"), emptyList(),
            r("""\b(remember (?:that|this|my|me)|don'?t forget (?:that|my)|forget (?:that|my|about)|what do you (?:know|remember)|keep in mind|""" +
                """(?:show|list|see|view|read|display)\b.{0,20}\b(?:your |my |all |saved )?memories|(?:your|my) memories)\b""")),
        Spec("browser", "Browser", "websites ${BrandName.NAME} opens, fills in or signs in to (background tabs; browser_show only when the user must step in)",
            listOf("browser_open", "browser_click", "browser_type", "browser_secure_input"), listOf("browser"),
            r("""(\b(?:open|go to|visit|log ?in to|sign ?in to|sign up (?:on|for)|fill (?:in|out))\b.{0,40}(?:\bsite\b|\bwebsite\b|\bportal\b|\.com\b|\.org\b|\.zm\b|https?://)|https?://)""")),
    )

    fun byId(id: String): Spec? = specs.firstOrNull { it.id == id }

    /**
     * The view a request needs, or null (plain chat). Slash commands and routines are left alone.
     * [followOn] = the last turn touched a design (v0.23.2): a short edit ("show engine", "add fins",
     * "make it taller", "open it so I can see") then goes to Design to change that same design.
     */
    fun route(text: String, followOn: Boolean = false): Spec? {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("/") || t.startsWith("[Scheduled routine")) return null
        var first = specs.firstOrNull { (it.id != "stage" || com.blaze.assistant.stage.StageStore.enabled) && it.cue.containsMatchIn(t) }
        // v0.26.1: a diagram / chart asked for in chat is drawn inline in the reply (VisualRoute), not in Design,
        // unless something else in the ask is clearly Design work (a page, a poster, 3D…) or it asks for a file.
        if (first?.id == "design" && VisualRoute.inChat(t) && !designStrong.containsMatchIn(VisualRoute.withoutVisualWords(t))) first = null
        // "make a picture of a car": a thing to draw, with picture words, is Gallery unless it's clearly a design (3D, mockup, page…).
        if (first?.id == "design" && !designStrong.containsMatchIn(t) && byId("gallery")!!.cue.containsMatchIn(t)) first = byId("gallery")
        if (followOn && (first == null || first.id == "design") && isFollowOnEdit(t)) return byId("design")
        return first
    }

    private val designStrong = r(DESIGN_STRONG)
    private val editLead = r("""^(?:(?:ok(?:ay)?|now|and|also|please|pls|then|great|nice|cool|good|perfect|can you|could you|would you|will you|just|yeah|yes|hey (?:argus|blaze)|(?:argus|blaze))[,!.]?\s+)*""" +
        """(?:show|hide|add|remove|delete|drop|make|change|turn|rotate|spin|move|put|give|use|swap|replace|resize|scale|zoom|colou?r|paint|fix|undo|redo|open|let me see|see|view|""" +
        """bigger|smaller|taller|shorter|wider|narrower|thinner|thicker|longer|more|less|darker|lighter|brighter|try|flip|mirror|cent(?:er|re)|align|increase|decrease|raise|lower|lift|""" +
        """extend|shrink|enlarge|attach|include|without|with|no|switch|set|label|animate|place)\b""")
    private val notDesign = r("""\b(calendar|e-?mails?|inbox|messages?|whatsapp|sms|texts?|calls?|alarms?|timers?|weather|news|battery|notifications?|music|songs?|playlist|volume|wi-?fi|bluetooth|reminders?|flash ?cards?|memory|memories|remember|know about me|contacts?|torch|brightness)\b""")

    /** A short change to what was just designed ("show engine", "make it taller", "add fins", "open it for me so I can see"). */
    fun isFollowOnEdit(text: String): Boolean {
        val t = text.trim().trimEnd('.', '!', '?')
        if (t.isEmpty() || t.startsWith("/") || t.split(Regex("\\s+")).size > 14) return false
        return editLead.containsMatchIn(t) && !notDesign.containsMatchIn(t)
    }

    /**
     * 2026-11-18 (model-first): a plain fact for the turn when a design was just made or is on screen. It replaces the
     * keyword-gated required-view order for Design follow-ons; whether the message is about this design is the model's call.
     */
    fun currentDesign(title: String, kind: String, path: String): String =
        "\nOn screen now: the design \u201c" + title + "\u201d (" + kind + ", " + path + "). If the user's newest message is about it, change it with design action=update " +
            (if (kind == "3d") "(set_parts for parts to add or change by id, remove_parts for ids to drop; action=read shows the part ids) "
            else "(edits=[{find, replace}] or a new html; action=read shows it first) ") +
            "and keep the rest as it was; design action=open shows it."

    /** All views a request touches, first = required. */
    fun routeAll(text: String): List<Spec> = if (route(text) == null) emptyList() else specs.filter { it.cue.containsMatchIn(text) }

    /**
     * The VIEWS block, the same on every turn (2026-11-18: it used to appear only when [route] matched a keyword, next to a
     * per-turn "REQUIRED VIEW THIS TURN" order). Short and factual; no absolutes (ModelFirstTest guards it).
     */
    val PROMPT: String = buildString {
        append("\n\nVIEWS. ${BrandName.NAME} has views (screens the user opens from /views) and the chat Stage (live cards on the chat itself). ")
        append("When what the user asks for belongs in one, make it there with its tool, show or name it, and keep the chat reply to a line or two. ")
        append("Whether a message asks for a view is your call from the user's newest message: earlier topics in the chat are background, ")
        append("and a word like flashcards or memory inside another request doesn't make it a request for that view.\n")
        for (s in specs) append("- ${s.name} (${s.id}): ${s.holds}. Write with ${s.tools.filter { it != "open_view" }.ifEmpty { listOf("open_view") }.joinToString(" / ")}.\n")
        append("- Learn: a deck is flashcards action=add (deck = topic or course code, cards=[{front, back}], one fact each); go through it on the Stage with stage_show kind=flashcards deck=<name> " +
            "or kind=quiz, or open_view view=learn. To quiz from a deck: flashcards action=due, one card at a time.\n")
        append("- Stage: a timer or focus session is stage_show kind=timer minutes=N (pomodoro=true for a pomodoro), not a reminder or alarm; music is stage_show kind=music; " +
            "a browser card is stage_show kind=browser. When the Stage can host something it is shown there; its view still keeps the data.\n")
        append("- Design is for anything visual or interactive: 3D models and scenes (design action=create kind=3d parts=[…]), 2D art (svg), pages, UI mockups, diagrams, " +
            "charts, posters, slides, floor plans, games (html). A diagram or chart asked for in chat: draw it inline in the reply (```mermaid / ```chart) unless they want a file. " +
            "A change to the current design is design action=update.\n")
        append("- Memory: memory_save / remember / forget, and memory_search (query=all lists everything to show the user in chat). " +
            "Archive: search_chat / search_past_chats, answered in chat. Schedule: create_routine / schedule_command / create_trigger, or set_alarm / add_calendar_event for a one-off time.\n")
        append("- Say you made, changed or opened something in a view only when its tool succeeded this turn.\n")
        append("- Other views (Jobs, Accounts, Agents, Usage, Settings…) open with open_view ask=true unless the user asked for that view.")
    }

    // ---------------- Learn safety net ----------------

    /** The user asked for cards themselves (not just "help me study"), so a reply with no deck gets one re-prompt. */
    private val wantsCards = r("""\b(flash ?cards?|cards? (?:on|about|for|from)|(?:a |new )?deck|quiz(?:zes)?|test me)\b""")
    fun asksForCards(userText: String) = wantsCards.containsMatchIn(userText)

    private val strip = Regex("""^\s*(?:[-*•>]\s+|\d{1,3}[.)]\s+|#{1,6}\s*)*""")
    private fun clean(s: String) = s.replace("**", "").replace("__", "").replace(Regex("""(?<!\w)\*(?!\s)|(?<!\s)\*(?!\w)"""), "").replace("`", "").trim().trim('|').trim()

    private val qRe = Regex("""^(?:q(?:uestion)?|front|term|card\s*\d+\s*(?:front)?)\s*\d*\s*[:.\-–—]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val aRe = Regex("""^(?:a(?:nswer|ns)?|back|definition|def)\s*\d*\s*[:.\-–—]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val dashRe = Regex("""^(.{2,80}?)\s+[–—-]\s+(.{2,})$""")

    /**
     * Flashcard / quiz pairs written as text: "Q: … / A: …" (also Question/Answer, Front/Back, Term/Definition,
     * numbered and bold), two-column markdown tables, and "**Term** – definition" lines. Empty when it isn't cards.
     */
    fun cardsFrom(text: String): List<Pair<String, String>> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Pair<String, String>>()
        // 1) Q / A pairs (the answer may be on the same line after the question: "Q: x A: y").
        var pendingQ: String? = null
        for (raw in lines) {
            val l = clean(raw.replace(strip, ""))
            val inline = Regex("""^(?:q(?:uestion)?|front)\s*\d*\s*[:.]\s*(.+?)\s+(?:a(?:nswer)?|back)\s*[:.]\s*(.+)$""", RegexOption.IGNORE_CASE).find(l)
            if (inline != null) { out += inline.groupValues[1].trim() to inline.groupValues[2].trim(); pendingQ = null; continue }
            val q = qRe.find(l); val a = aRe.find(l)
            when {
                q != null && a == null -> pendingQ = q.groupValues[1].trim()
                a != null && pendingQ != null -> { out += pendingQ!! to a.groupValues[1].trim(); pendingQ = null }
            }
        }
        if (out.size >= 2) return out.filter { it.first.isNotBlank() && it.second.isNotBlank() }
        out.clear()
        // 2) A two-column table: | front | back |
        val rows = lines.filter { it.startsWith("|") && it.count { c -> c == '|' } == 3 }
        if (rows.size >= 3) {
            val body = rows.filterNot { it.replace(Regex("[|:\\-\\s]"), "").isEmpty() }
            val cells = body.map { it.trim('|').split('|').map { c -> clean(c) } }
            val data = if (cells.isNotEmpty() && cells[0].any { it.lowercase() in setOf("front", "question", "term", "q", "back", "answer", "definition", "a") }) cells.drop(1) else cells
            data.filter { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }.forEach { out += it[0] to it[1] }
            if (out.size >= 2) return out
            out.clear()
        }
        // 3) "**Term** – definition" (only bold terms, so ordinary sentences with dashes don't count)
        for (raw in lines) {
            val l = raw.replace(strip, "")
            if (!l.startsWith("**")) continue
            val m = dashRe.find(l) ?: Regex("""^\*\*(.{2,80}?)\*\*\s*[:–—-]\s*(.{2,})$""").find(l) ?: continue
            out += clean(m.groupValues[1]).trimEnd(':') to clean(m.groupValues[2])
        }
        return if (out.size >= 3) out else emptyList()
    }

    /** "make flashcards on the French revolution" → "The French revolution". */
    fun deckName(userText: String): String {
        val m = Regex("""\b(?:on|about|for|from|of|covering)\s+(?:the\s+topic\s+)?(.{2,60}?)(?:[.?!,]|\s+(?:please|for me|so|and quiz|then)\b|$)""", RegexOption.IGNORE_CASE)
            .find(userText.substringAfter(Regex("""(?i)flash ?cards?|quiz(?:zes)?|deck|test me|cards""").find(userText)?.value ?: "", userText))
        val raw = m?.groupValues?.get(1)?.trim()?.trim('"', '\'', '“', '”')?.removePrefix("my ")?.trim().orEmpty()
        return raw.replaceFirstChar { it.uppercase() }.take(40).ifBlank { "Cards" }
    }

    /** What the agent does with a final text reply on a Learn turn that never called the flashcards tool. */
    enum class Fix { NONE, CONVERT, REPROMPT }

    fun shouldConvert(userText: String, reply: String, calledFlashcards: Boolean, alreadyNudged: Boolean): Fix {
        if (calledFlashcards) return Fix.NONE
        if (route(userText)?.id != "learn") return Fix.NONE
        val cards = cardsFrom(reply)
        if (cards.size >= 2) return Fix.CONVERT
        // One quiz question at a time (interactive) is fine; a refusal or plain explanation of "make cards" is not.
        if (asksForCards(userText) && !alreadyNudged && !Regex("""(?i)\bquiz me\b|\btest me\b""").containsMatchIn(userText)) return Fix.REPROMPT
        return Fix.NONE
    }

    const val NUDGE = "You answered in chat, but flashcards belong in the Learn view (the user's standing rule). " +
        "Call flashcards action=add now with deck and cards=[{front, back}], then open_view view=learn, then reply in one or two lines. Don't list the cards in chat."

    fun converted(deck: String, added: Int, total: Int): String =
        "Made the **$deck** deck in Learn: $added card${if (added == 1) "" else "s"}" +
            (if (added < total) " (${total - added} were already there)" else "") +
            ". It's open now; cards come back right before you'd forget them (/learn)."
}
