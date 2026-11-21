package com.blaze.assistant.agent

/**
 * v0.26.1 (Jack): "draw a flowchart of how solar panels work" went through the diagrams skill, wrote a
 * Mermaid file and opened the Design preview, which showed the raw source and took minutes. A diagram, chart or other
 * visual asked for in chat is drawn INLINE in the reply (a ```mermaid / ```chart block, see RichReplies.PROMPT): no
 * files, no Design view or preview_server, no use_skill. The file route is only for asks to save, export, download,
 * edit or make a file (or a page, poster, slides…). Pure (VisualRouteTest); used by the in-chat turn hint (ViewRouter,
 * its other caller, was retired for the view shelf on 2026-11-21; this hint is the next word guess to retire, VIEW-SHELF.md §9). Round 5 (20 Nov 2026): `filter` / `skipSkill`, which hid the diagrams and data-analysis skills on
 * such turns, are gone; every skill stays on the shelf and the model chooses.
 */
object VisualRoute {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** The visual words: diagrams, flowcharts, charts, graphs, mind maps, "map out", "visualise". */
    val visual = r("""\b(diagrams?|flow ?charts?|charts?|graphs?|plots?|mind ?maps?|org(?:anisation|anization)? charts?|sequence diagrams?|timelines?|pie charts?|bar charts?|line charts?|map (?:it |this |that )?out|visuali[sz]e|visuali[sz]ation)\b""")

    /** Asks that really want a file, a saved / exported thing or the Design view. */
    val fileAsk = r("""\b(save|saved|export|download|file|files|\.mmd|\.svg|\.png|\.pdf|png|svg|pdf|jpe?g|docx?|pptx?|xlsx|csv|spreadsheet|excel|print(?:able)?|""" +
        """edit (?:the|my|that|this) (?:file|design|diagram|chart)|in (?:the )?design(?: view)?|open (?:it )?in design|preview|readme|markdown file|wallpaper|poster|slides?|deck|presentation|website|web ?page|landing page|html|""" +
        """3-?d|dashboard|send (?:it|this) to|share (?:it|this) (?:as|with))\b""")

    /** The per-turn line for an in-chat visual (dynamic part of the prompt, after the cached prefix). */
    const val HINT = "\nTHIS TURN: an in-chat visual. Draw it in your reply as a ```mermaid block (or ```chart for numbers) with 2-3 sentences. " +
        "Don't write a file, don't open Design or preview_server, don't call use_skill or run_code for it."

    fun isVisualAsk(text: String): Boolean = visual.containsMatchIn(text)
    fun wantsFile(text: String): Boolean = fileAsk.containsMatchIn(text)

    /** True when [text] asks for a visual that belongs inline in the chat reply. */
    fun inChat(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("/")) return false
        return isVisualAsk(t) && !wantsFile(t)
    }

    /** [text] without its visual words, to see whether something else in it still asks for Design. */
    fun withoutVisualWords(text: String): String = visual.replace(text, " ")
}
