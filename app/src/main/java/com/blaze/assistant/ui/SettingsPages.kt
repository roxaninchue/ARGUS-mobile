package com.blaze.assistant.ui

import com.blaze.assistant.BrandName

/*
 * P1-SH-06: the settings page ids, their old-id aliases and "open by name" moved here from SettingsSheet.kt and
 * agent/SelfSetup.kt unchanged (same names, same package), so SettingsAliasTest runs them on the JVM. Only additions:
 * the 0.52.3 profile row ids and the ARGUS tree's words (ArgusSettings) as aliases.
 */

/** Settings, grouped into pages. [page] null = the profile page (the root). */
data class SettingsPage(val id: String, val title: String, val sub: String)

/**
 * 0.52.3 (ARGUS x Hark): the root of Settings is the profile page ([com.blaze.assistant.ui.space.SpaceProfileScreen]):
 * Usage and plan; Profile, Scheduled Tasks, Accounts, Wallet; Security, System Preferences, Submit Feedback, About.
 * System Preferences lists Appearance, Language, Voice, Permissions and Updates. Back follows
 * [com.blaze.assistant.ui.space.SpaceSettings.PARENT] (everything else returns to the profile). The old Settings list,
 * Advanced, the Brain page's switches and the backup / offline brain sections are gone: the brain is picked
 * automatically. The pages below stay reachable by id (ARGUS's open_settings, /views, the + menu) and keep their prefs.
 */
val topPages = listOf(
    SettingsPage("system", "System Preferences", "Appearance, language, voice, permissions, updates"),
    SettingsPage("feedback", "Submit Feedback", "Tell the ${BrandName.NAME} team what works and what doesn't"),
    SettingsPage("look", "Appearance", "Light, Dark or Auto, and the accent colour"),
    SettingsPage("language", "Language", "Which language ${BrandName.NAME} replies in. Auto matches yours"),
    SettingsPage("doing", "Things ${BrandName.NAME} is doing", "Routines and reminders, on or off, and today's diary"),
    SettingsPage("voice", "Voice", "Hey ${BrandName.NAME}, spoken replies, default assistant"),
    SettingsPage("permissions", "Permissions", "Notifications, mic, camera, files"),
    SettingsPage("account", "Profile", "${BrandName.NAME} account and connected apps"),
    SettingsPage("updates", "Updates", "Check for a newer ${BrandName.NAME}; updates run in the background"),
    // 0.55.1: Developer > Speed stats (the readout that "speed stats" used to answer by phrase; no trigger words now).
    SettingsPage("developer", "Developer", "Speed stats and other diagnostics"),
    SettingsPage("speed", "Speed stats", "Where the time of recent replies went: first token, model calls, cloud and phone tools"),
    SettingsPage("about", "About", "Version, licences and what ${BrandName.NAME} keeps on this phone"),
    // Unreleased after 0.52.3: pinned facts, this chat's summary, the current task, the skills in use (HoldingPage).
    SettingsPage("holding", "What ${BrandName.NAME} is holding", "Pinned facts, this chat's summary, the current task and skills"),
)

/** Pages that left the Settings UI in 0.52.3 but still open by id (their prefs keep working). */
val advancedPages = listOf(
    SettingsPage("connections", "Accounts", "Google, GitHub, Spotify, Notion, Slack"),
    SettingsPage("desktop", "${BrandName.NAME} Desktop", "Pair with ${BrandName.NAME} on your computer"),
    SettingsPage("control", "Phone skills", "Calendar access"),
    SettingsPage("persona", "Personality", "How ${BrandName.NAME} talks"),
    SettingsPage("feel", "Taps, sounds & panel", "Tap highlights, sounds, compact panel"),
    SettingsPage("privacy", "Security", "App lock, approvals"),
    SettingsPage("secrets", "Wallet", "Keys, tokens, logins and cards from the Secure Form, only on this phone"),
    SettingsPage("logins", "Saved logins", "Sign-ins from ${BrandName.NAME}'s secure form, encrypted on this phone"),
    SettingsPage("phonebrowser", "${BrandName.NAME} Phone", "${BrandName.NAME}'s own phone browser: sign out of sites, clear its data"),
    SettingsPage("proactive", "Proactive & triggers", "Speak up on its own, when-X-do-Y"),
    SettingsPage("logs", "Life logs", "Transcribe what the mic hears into memory, brainstorms"),
    SettingsPage("usage", "Usage", "Tokens and estimated cost"),
    SettingsPage("backup", "Backup", "Export or import everything"),
    // P2-SF-04: opens from Security (Privacy); standing allows, autonomy, send scope and spending limits per bot.
    SettingsPage("policy", "Approvals & permissions", "Always-allowed actions, autonomy, sending and spending limits per bot"),
    // P2-SF-05: opens from Security (Privacy); the audit log by bot / team, its hash chain, export.
    SettingsPage("activity", "Activity", "What each bot did or asked to do, verified log, export"),
)

/**
 * Pages that are views: opened by id from /views, the + menu, open_view and the "Open in …?" chip. "ai" (Brain) is
 * only the status card now: ARGUS Cloud picks the model for every message, nobody picks one.
 */
val viewPages = listOf(
    SettingsPage("ai", "Brain", "${BrandName.NAME} Cloud picks the best model for every message"),
    SettingsPage("memory", "Memory", "What ${BrandName.NAME} remembers and has learned"),
    SettingsPage("routines", "Scheduled Tasks", "Things ${BrandName.NAME} does on a schedule"),
    SettingsPage("skills", "Skills", "Research, email, plans, docs, code: on, off, restore"),
    SettingsPage("learn", "Learn", "Flashcards, Google Classroom, low-data mode"),
    SettingsPage("seller", "Seller", "Brand kit, weekly store digest, replies from your PC"),
    SettingsPage("dashboard", "Dashboard", "Local web dashboard and analytics"),
    SettingsPage("plan", "Usage and plan", "${BrandName.NAME} Cloud plans, monthly or yearly"),
    SettingsPage("sync", "Cloud sync", "Chats, memory, settings and designs on all your phones, end-to-end encrypted"),
)

val settingsPages = topPages + advancedPages + viewPages

/** 0.52.3: page ids from before the declutter that now land on another page ("" = the profile). */
internal val settingsAliases = mapOf("router" to "ai", "backup_brain" to "ai", "advanced" to "", "settings" to "") +
    // 16 Nov 2026 (Finn): Team & bots (specialists, always on) and Jobs (the terminal page) left Settings; old ids open the root.
    mapOf("specialists" to "", "terminal" to "", "device" to "", "commands" to "") +
    // P1-SH-06: the 0.52.3 profile's row ids (they were ids of rows, not pages) open the page the row opened.
    mapOf("profile" to "account", "scheduled" to "routines", "accounts" to "connections", "wallet" to "secrets", "security" to "privacy")

/** The page a start id opens: a known page, an alias, or what [settingsPageByName] makes of it; null = the profile. */
internal fun settingsStart(start: String?): String? {
    val s = start?.trim().orEmpty()
    if (s.isEmpty()) return null
    settingsAliases[s]?.let { return it.ifEmpty { null } }
    if (settingsPages.any { it.id == s }) return s
    return settingsPageByName(s).takeIf { it.isNotEmpty() }
}

/**
 * Turns whatever the model wrote ("settings voice", "Voice & wake word", "ai/control/voice/term")
 * into a real settings page id. Unknown → "" (the settings list), never a blank page.
 * (agent.settingsPageFor delegates here.)
 */
fun settingsPageByName(raw: String): String {
    val ids = settingsPages.map { it.id }
    val alias = mapOf("router" to "ai", "blazerouter" to "ai", "update" to "updates", "upgrade" to "updates", "permission" to "permissions", "version" to "about", "brain" to "ai", "model" to "ai", "models" to "ai", "keys" to "ai", "provider" to "ai",
        "wake" to "voice", "speech" to "voice", "tts" to "voice", "call" to "voice", "mic" to "voice", "speak" to "voice",
        "look" to "feel", "theme" to "feel", "sounds" to "feel", "accounts" to "connections", "connected" to "connections",
        "personality" to "persona", "phone" to "control", "skills" to "control",
        "library" to "skills", "signin" to "account", "login" to "account", "signup" to "account", "lock" to "privacy", "safety" to "privacy", "export" to "backup",
        "brainstorm" to "logs", "lifelogs" to "logs", "transcripts" to "logs") + com.blaze.assistant.ui.argus.settings.ArgusSettings.NAME_ALIASES
    val words = raw.lowercase().replace("settings", " ").split(Regex("[^a-z_]+")).filter { it.isNotBlank() && it !in com.blaze.assistant.ui.argus.settings.ArgusSettings.STOP_WORDS }
    if (words.isEmpty()) return ""
    // Several pages listed (the model copied the example): only trust it when they all point to one page.
    val hits = words.mapNotNull { w -> if (w in ids) w else alias[w] ?: settingsPages.firstOrNull { p -> p.title.lowercase().split(Regex("[^a-z]+")).contains(w) }?.id }.distinct()
    return if (hits.size == 1) hits.first() else ""
}
