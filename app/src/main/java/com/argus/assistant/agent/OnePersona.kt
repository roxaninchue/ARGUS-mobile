package com.argus.assistant.agent


import com.argus.assistant.BrandName
/**
 * 0.51.0: Halo and Blaze are one — there is only Blaze. (Owner: "merge Halo and Blaze into one; no separate Halo persona
 * for now.")
 *
 *  - Routing: "@halo", "@all", "@both" and the automatic TEAM split all collapse to a normal Blaze turn ([collapse]); the
 *    tags are still stripped from what the model reads, so an old habit of typing "@halo …" just talks to Blaze.
 *  - The side lane (what answers while a task runs) stays, but it is Blaze answering in the first person ([sideSystem]):
 *    "I'm still on …", never "Halo" or "my partner".
 *  - Saved state: a stored "halo" / "all" pick or speaker reads as Blaze ([who], [speaker]); [migrate] turns off the duo
 *    team setting and the Halo persona once ([MIGRATED]).
 *  - The pure Duo rules (Duo.kt) are kept unchanged so older chats still parse; nothing routes to them any more.
 * Pure (OnePersonaTest).
 */
object OnePersona {
    const val MIGRATED = "persona_merged_051"

    /** Any Duo route becomes a plain Blaze turn; [Duo.Route.explicit] stays so the @tags are still taken out of the text. */
    fun collapse(r: Duo.Route): Duo.Route = if (r.mode == Duo.Mode.BLAZE) r else Duo.Route(Duo.Mode.BLAZE, r.text, r.explicit || r.mode != Duo.Mode.TEAM)

    /** A saved talk-to pick ("blaze", "halo", "all") → always "blaze". */
    fun who(saved: String?): String = "blaze"

    /** A stored message speaker ("halo", "blaze", "") → "" (Blaze). */
    fun speaker(saved: String?): String = ""

    /** No partner line in Blaze's prompt any more. */
    fun awareness(): String = ""

    /** The history prefix for a side-lane line: none (the model never sees "[Halo]" / "[Blaze]" tags from new turns). */
    fun tag(): String = ""

    /**
     * One-time settings migration. [getStr] / [setStr] are Settings.str / setStr, [getBool] / [setBool] Settings.bool / setBool
     * (boolean prefs must stay booleans: PrefCoerce). Returns the keys changed. Keeps `duo_on` / `duo_split`: they also gate
     * the side lane, which stays (as Blaze).
     */
    fun migrate(getStr: (String) -> String?, setStr: (String, String) -> Unit, getBool: (String, Boolean) -> Boolean, setBool: (String, Boolean) -> Unit): List<String> {
        if (getBool(MIGRATED, false)) return emptyList()
        val changed = ArrayList<String>()
        if (getBool("duo_team", true)) { setBool("duo_team", false); changed += "duo_team" }
        if (!getStr("halo_persona_active").isNullOrEmpty()) { setStr("halo_persona_active", ""); changed += "halo_persona_active" }
        for (k in listOf("talk_to", "composer_talk_to", "assist_talk_to")) { val v = getStr(k); if (!v.isNullOrEmpty() && v != "blaze") { setStr(k, "blaze"); changed += k } }
        setBool(MIGRATED, true)
        return changed
    }

    /** The side lane's system prompt: Blaze, first person, while its own task keeps running. */
    fun sideSystem(userName: String?, task: String, step: String): String = Duo.BLAZE_SOUL + Duo.ASSISTANT_RULES +
        "\n" + SecretRules.PROMPT +
        (if (!userName.isNullOrBlank()) "\nThe user's name is $userName." else "") +
        "\nRIGHT NOW you are already working on the user's task in the background: \"${task.take(600)}\"" +
        (if (step.isNotBlank()) " (current step: ${step.take(120)})" else "") + ". That keeps running. Meanwhile you answer this new message yourself, " +
        "always in the first person (\"I'm still on it\", \"I'll finish that first\"), as ${BrandName.NAME} — there is no partner or second assistant. Help with anything: chat, " +
        "answers, advice, ideas, research (you have web search, pages, memory and skills; use them when facts matter; when you have calendar_events, " +
        "list_routines or create_routine, use them for calendar and reminder asks and never say you can't see the calendar). You can't touch the phone's " +
        "screen or apps from this reply because the running task is using them.\n" +
        "If the message is meant for the running task (a correction, extra detail, 'stop', 'use X instead', 'also do Y'), or it needs a phone action, " +
        "reply with only the tag [[relay]] and nothing else: the running task picks it up and answers. Never write a handoff line. Otherwise just answer, " +
        "with no tag. If they ask how the task is going, say in the first person what you're doing on it. Never write speaker tags like [${BrandName.NAME}] or [Halo]. " +
        "HONESTY: never say something is running, open, sent or done unless the app confirmed it. Never write tool calls as text."
}
