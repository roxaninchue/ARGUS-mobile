package com.blaze.assistant.agent

/**
 * 2026-11-14 build / craft mode (owner: "when I say make something, ARGUS always goes for the simplest version").
 * What pushed toward the minimum: the "one or two sentences" / "keep it short" lines (gone, see HarnessPrompt), a short
 * "make me a landing page" counted as a QUICK turn (ThinkBudget: < 80 chars) so it went out with low reasoning and a
 * 1,024-token cap, the 5-minute turn cap with no room for a review pass, 30 steps, and skill packs that start from "a
 * small game" / "a single-file page".
 *
 * A make / build / design / create request for an artifact (an app, site, page, game, design, document, deck, file…)
 * turns this mode on: the prompt block below (plan → build complete and polished with a real design system → self-review
 * against a checklist → one improvement round → deliver), normal-or-deep reasoning (never QUICK), a longer turn and more
 * steps. Not for actions that only sound like making ("make a call", "create an alarm", "set a reminder").
 * Pure; BuildModeTest.
 */
object BuildMode {
    /** Steps a build turn may take (normal turns: Agent.MAX_STEPS, 30). */
    const val MAX_STEPS = 60
    /** Hard length of a build turn (normal: TurnDeadline.TURN_MS, 5 min); long work is still handed to a background agent near the end. */
    const val TURN_MS = 20 * 60_000L

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val verb = r("""\b(make|build|design|create|craft|code|develop|write|generate|draft|prototype|redesign|rebuild|improve|polish)\b""")
    private val artifact = r("""\b(apps?|application|website|web ?site|site|web ?app|landing ?page|page|home ?page|portfolio|dashboard|ui|ux|interface|screens?|mock-?ups?|prototype|game|tool|extension|plugin|bot|script|program|api|backend|frontend|component|widget|design|logo|brand(?:ing)?|poster|flyer|banner|thumbnail|cover|card|invitation|menu|slides?|deck|presentation|pitch|document|doc|report|proposal|cv|resume|résumé|letter|essay|ebook|guide|manual|spreadsheet|template|form|chart|infographic|diagram|animation|3d|scene|model|beat|song|track|video|file|repo|project|clone)\b""")
    /** Things that are made with one tool call, not crafted: a call, a reminder, an alarm, a note, a contact, an event. */
    private val notCraft = r("""\b(make|create|set|add|schedule|place)\s+(?:an?\s+|the\s+|my\s+|me\s+(?:an?\s+)?)?(call|phone call|reminder|alarm|timer|note|contact|event|appointment|meeting|routine|trigger|calendar|group|playlist|backup|account|payment|order|booking|reservation)\b|\bmake (?:a |the )?(?:call|decision|choice|sense|it quick)\b""")
    private val quickAsk = r("""\b(quick|simple|basic|rough|minimal|tiny|one[- ]liner|just a (?:small|quick))\b""")

    /** A request to make an artifact (not a one-tool action). */
    fun wants(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 4_000) return false
        if (notCraft.containsMatchIn(t)) return false
        return verb.containsMatchIn(t) && artifact.containsMatchIn(t)
    }

    /** The user asked for a quick or simple one: build mode still plans and checks, but keeps to that scope. */
    fun userWantsSimple(text: String): Boolean = quickAsk.containsMatchIn(text)

    fun steps(normal: Int, build: Boolean): Int = if (build) maxOf(normal, MAX_STEPS) else normal

    /** The turn's prompt block ("" when this isn't a build turn). */
    fun block(text: String): String = if (!wants(text)) "" else PROMPT + if (userWantsSimple(text)) SCOPE_SIMPLE else SCOPE_FULL

    const val PROMPT = "\n\nBUILD MODE (this message asks you to make something). Work like a senior designer-engineer, not a demo writer:\n" +
        "1. PLAN first (briefly, to yourself or with goal): who it's for, what it must do, the screens / sections / parts, the files, and the design direction. Read any matching skill pack with use_skill; treat it as a floor, not a ceiling.\n" +
        "2. BUILD THE COMPLETE THING: every feature and section the request implies, real content (no lorem ipsum, no TODOs, no placeholder links), working interactions and error / empty / loading states. Split it into several files when that is the right structure (separate CSS / JS / modules, assets, a README); one file only when the thing is truly small. Write files with the file tools or the design tool; never paste a big artifact into the chat.\n" +
        "3. A REAL DESIGN SYSTEM, defined once and used everywhere: colour tokens (background, surface, text, muted, accent, border, danger) with AA contrast, a type scale (4-6 steps, line heights), a spacing scale (4 or 8 px steps), radii and shadows, component states (hover, focus-visible, active, disabled, selected), purposeful motion (150-300 ms easing, prefers-reduced-motion respected), responsive from 360 px phones up, accessible names and keyboard use.\n" +
        "4. SELF-REVIEW before you deliver: open or run what you made (preview, read_file, run the code or tests) and check it against: does it do everything asked? no broken or missing parts? consistent tokens and spacing? states and motion present? works at phone width? accessible? no console errors? Then make ONE improvement round on what fell short.\n" +
        "5. DELIVER: where it is (view, path or link), what it includes, and the one or two next improvements you'd make. Keep that message short; the work is in the artifact."

    const val SCOPE_FULL = "\nAim for a polished, production-grade result, not the smallest version that technically works."
    const val SCOPE_SIMPLE = "\nThe user asked for a quick or simple one: keep the scope small, but still finished, consistent and checked."
}
