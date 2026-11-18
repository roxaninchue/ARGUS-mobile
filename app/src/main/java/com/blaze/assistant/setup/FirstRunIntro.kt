package com.blaze.assistant.setup

import com.blaze.assistant.BrandName

/**
 * 2026-11-18 first run: what ARGUS's introduction is made from (docs/MODEL-FIRST.md: the model writes it; the app only
 * states the facts of the moment). [kickoff] is the hidden turn's message, written as the app's note, not the user's
 * words; [FALLBACK] is posted when no model answers (offline, an error, [TIMEOUT_MS]). Pure, so FirstRunTest pins it.
 */
object FirstRunIntro {
    /** How long the hidden turn may take before the fixed introduction is posted instead. */
    const val TIMEOUT_MS = 60_000L

    /**
     * The facts and the ask, short and plain: first run, just signed in, nothing written yet; what the checklist in Life
     * holds; the app language they picked (so the introduction is in it).
     */
    fun kickoff(openSteps: List<String>, language: String): String = buildString {
        append("[A note from the ${BrandName.UPPER} app, not a message from the user: this is ${BrandName.UPPER}'s first run on this phone. ")
        append("The user has just signed in and hasn't written anything yet. ")
        append("The app put a \u201c${SetupChecklist.TITLE}\u201d checklist at the top of the Life tab")
        if (openSteps.isNotEmpty()) append(" with these steps still open: ").append(openSteps.joinToString("; "))
        append(". Tapping a step opens the screen that does it. ")
        append("They picked ").append(language.ifBlank { "English" }).append(" as the app's language.]\n")
        append("Introduce yourself to the user in a short first message: who you are, what you can do for them on this phone, ")
        append("and that a setup checklist is waiting for them in Life.")
    }

    val FALLBACK = "Hi, I'm ${BrandName.UPPER}. I live on this phone and work for you: I answer questions, search the web, " +
        "keep track of your calendar and reminders, read and draft messages and email, open and use apps for you, and run " +
        "longer jobs in the background while you get on with your day.\n\n" +
        "I've put a **${SetupChecklist.TITLE}** checklist at the top of Life. Each step takes a tap or two and lets me do more for you. " +
        "What would you like to start with?"

    /** The model's introduction when it wrote one, else [fallback]. */
    fun pick(written: String?, fallback: String): String = written?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}
