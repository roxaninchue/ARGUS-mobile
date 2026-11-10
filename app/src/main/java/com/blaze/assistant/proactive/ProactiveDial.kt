package com.blaze.assistant.proactive

import com.blaze.assistant.BrandName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unreleased round after 0.52.3, explainable heads-ups + the proactivity dial. Finn: "every proactive heads-up carries a
 * short 'why you got this' reason" and "a proactivity level setting Off / Low / Normal / High".
 *
 * - Every heads-up names its trigger ([Source]) and a one-line reason ([Why]): "You usually open WhatsApp around 8:00",
 *   "Follow-up you asked for on Tue 10 Nov", "New notification from Slack". The notification shows it as its small sub
 *   line; the Heads-ups chat stores it in the bubble's meta ([meta]) and shows a "Why?" a tap opens ([whyOf] / [chipOf]).
 * - The dial ([Level], Settings key [KEY], default Normal) is applied where Proactive.say() decides to drop ([allows]):
 *   Off = only reminders the user scheduled; Low = urgent heads-ups and follow-ups the user asked for; Normal = as before;
 *   High = as before plus habit offers at a lower bar ([habitDays]: 3 of the last 7 days instead of 4).
 * Pure Kotlin, unit-tested in ProactiveDialTest.
 */
object ProactiveDial {
    /** The Settings key (stored as [Level.id]). */
    const val KEY = "proactive_level"

    enum class Level(val id: String, val label: String) {
        OFF("off", "Off"), LOW("low", "Low"), NORMAL("normal", "Normal"), HIGH("high", "High");
        companion object {
            /** The stored id back to a level; anything unknown is [NORMAL]. */
            fun of(id: String?): Level = values().firstOrNull { it.id == id?.trim()?.lowercase() } ?: NORMAL
        }
    }

    /** What set a heads-up off. */
    enum class Source {
        /** A reminder the user scheduled themselves. */
        REMINDER,
        /** A follow-up the user said yes to ("Yes, I'm going" / a reminder was set). */
        FOLLOW_UP_ASKED,
        /** A follow-up Argus noticed on its own (a plan in a chat, a reply still owed). */
        FOLLOW_UP,
        /** The background check (notifications, calendar, phone health), including urgent-looking messages. */
        CHECK,
        /** The morning brief / evening wrap-up. */
        BRIEF,
        /** "You open X around 8:00 most days" offers. */
        HABIT,
        /** Jarvis getting-to-know-you questions and call offers. */
        CHECK_IN,
        /** Jarvis screen and camera glances. */
        GLANCE,
        /** Routines Jarvis offers or sets up. */
        ROUTINE_IDEA,
        /** Phone health (battery). */
        DEVICE,
        /** New items on Home widgets. */
        NEWS,
    }

    /** True when a heads-up from [source] ([urgent] as the caller judged it) may reach the user at [level]. */
    fun allows(level: Level, source: Source, urgent: Boolean): Boolean = when (level) {
        Level.OFF -> source == Source.REMINDER
        Level.LOW -> urgent || source == Source.REMINDER || source == Source.FOLLOW_UP_ASKED
        Level.NORMAL, Level.HIGH -> true
    }

    /** False when the background check would only find heads-ups the dial drops anyway (no model call is spent). */
    fun runsChecks(level: Level): Boolean = level != Level.OFF

    /** Days out of the last 7 an app must be opened around the same hour before the habit offer. */
    fun habitDays(level: Level): Int = if (level == Level.HIGH) 3 else 4

    /** The line under the dial in Settings. */
    fun note(level: Level): String = when (level) {
        Level.OFF -> "Only reminders you scheduled yourself."
        Level.LOW -> "Urgent things and follow-ups you asked for."
        Level.NORMAL -> "Heads-ups when something matters, as now."
        Level.HIGH -> "Everything in Normal, plus routine offers sooner."
    }

    /** One-line reasons, in the user's words. Each is at most [MAX] characters. */
    object Why {
        fun habit(app: String, hour: Int): String = clip("You usually open ${app.trim()} around $hour:00")
        fun followUpAsked(at: Long, locale: Locale = Locale.getDefault()): String = clip("Follow-up you asked for on ${day(at, locale)}")
        fun followUp(person: String, app: String): String = clip("${person.trim().substringBefore(' ').ifBlank { "Someone" }} is waiting on a reply on ${app.trim()}")
        fun newPlan(person: String, app: String): String = clip("New message from ${person.trim().ifBlank { "someone" }} on ${app.trim()}")
        fun notification(app: String): String = clip("New notification from ${app.trim().ifBlank { "an app" }}")
        fun check(): String = clip("Regular check of your notifications, calendar and phone")
        fun brief(morning: Boolean): String = if (morning) "Your morning brief" else "Your evening wrap-up"
        fun battery(level: Int): String = "Battery dropped to $level%"
        fun checkIn(): String = "A check-in question (Jarvis mode)"
        fun glance(app: String): String = clip("You were in ${app.trim().ifBlank { "an app" }} (Jarvis screen glance)")
        fun camera(): String = "A camera look while ${BrandName.NAME} was open (Jarvis mode)"
        fun routine(): String = "From your habits and app usage (Jarvis mode)"
        fun news(): String = "New items on your Home"

        private fun day(at: Long, locale: Locale): String = SimpleDateFormat("EEE d MMM", locale).format(Date(at))
        private fun clip(s: String): String = if (s.length <= MAX) s else s.take(MAX - 1).trimEnd() + "\u2026"
    }

    /** Longest reason kept. */
    const val MAX = 80

    private const val SEP = " \u00b7 Why: "

    /** A heads-up bubble's meta: "Heads-up · Tue 09:10", with " · Why: …" when there is a reason. */
    fun meta(stamp: String, why: String): String = "Heads-up \u00b7 $stamp" + (if (why.isBlank()) "" else SEP + why.trim().replace(" \u00b7 ", ", "))

    /** The reason stored in a bubble's [meta] ("" = none). */
    fun whyOf(meta: String): String {
        val at = meta.indexOf(SEP)
        if (at < 0) return ""
        return meta.substring(at + SEP.length).substringBefore(" \u00b7 ").trim()
    }

    /** The chip text of a bubble's [meta] without its reason (the reason has its own "Why?"). */
    fun chipOf(meta: String): String {
        val at = meta.indexOf(SEP)
        if (at < 0) return meta
        val rest = meta.substring(at + SEP.length)
        val after = rest.indexOf(" \u00b7 ").let { if (it < 0) "" else rest.substring(it) }
        return meta.substring(0, at) + after
    }
}
