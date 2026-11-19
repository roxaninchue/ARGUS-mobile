package com.blaze.assistant.setup

import com.blaze.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-11-18 first run: what ARGUS's introduction is made from (docs/MODEL-FIRST.md: the model writes it; the app only
 * states the facts of the moment). Owner 2026-11-19, "faster intro": the checklist and the introduction arrive together.
 * The app puts the checklist in Life itself, then posts the introduction at once. In English there is no model call at all
 * ([needsModel]): [FALLBACK] is the introduction. Another language gets one model reply ([messages]), with no tools and so no
 * tool step, written from [FALLBACK]; [FALLBACK] (translated by the app's tables) is posted when no model answers in time
 * ([TIMEOUT_MS]). Pure, so SetupChecklistTest pins it.
 */
object FirstRunIntro {
    /** How long the one model reply may take before the fixed introduction is posted instead. */
    const val TIMEOUT_MS = 30_000L

    /** True when the introduction needs the model: any app language but English. */
    fun needsModel(langCode: String): Boolean {
        val base = langCode.trim().lowercase().substringBefore('-').substringBefore('_')
        return base.isNotEmpty() && base != "en"
    }

    /**
     * The one request, short and plain: first run, just signed in, nothing written yet; the checklist already in Life with
     * the steps still open on this phone; the app language they picked. ARGUS writes its first message in that language,
     * saying what [FALLBACK] says.
     */
    fun kickoff(openSteps: List<String>, language: String): String = buildString {
        val lang = language.ifBlank { "English" }
        append("[A note from the ${BrandName.UPPER} app, not a message from the user: this is ${BrandName.UPPER}'s first run on this phone. ")
        append("The user has just signed in and hasn't written anything yet. ")
        append("The \u201c${SetupChecklist.TITLE}\u201d checklist is already at the top of their Life tab; steps still open on this phone")
        if (openSteps.isNotEmpty()) append(": ").append(openSteps.joinToString("; ")) else append(": none")
        append(". ")
        append("They picked ").append(lang).append(" as the app's language.]\n")
        append("Introduce yourself to the user in $lang in a short first message: who you are, what you can do for them on this phone, ")
        append("that the checklist is waiting in Life and that tapping a step opens the screen that does it. ")
        append("Reply with the message alone. This is the English version, to say the same in $lang:\n\n")
        append(FALLBACK)
    }

    /** The request [kickoff] makes: one user turn, sent with no tools. */
    fun messages(openSteps: List<String>, language: String): JSONArray =
        JSONArray().put(JSONObject().put("role", "user").put("content", kickoff(openSteps, language)))

    val FALLBACK = "Hi, I'm ${BrandName.UPPER}. I live on this phone and work for you: I answer questions, search the web, " +
        "keep track of your calendar and reminders, read and draft messages and email, open and use apps for you, and run " +
        "longer jobs in the background while you get on with your day.\n\n" +
        "I've put a **${SetupChecklist.TITLE}** checklist at the top of Life. Each step takes a tap or two and lets me do more for you. " +
        "What would you like to start with?"

    /** The model's introduction when it wrote one, else [fallback]. */
    fun pick(written: String?, fallback: String): String = written?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}
