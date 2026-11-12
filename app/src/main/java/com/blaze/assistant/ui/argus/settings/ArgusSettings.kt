package com.blaze.assistant.ui.argus.settings

import com.blaze.assistant.BrandName

/**
 * P1-SH-06 Settings re-tree (04 §4.11), pure so SettingsAliasTest checks it on the JVM. The root is the You page: the
 * name (tap = Profile, the ARGUS account), the plan card (Usage and plan), a top group, then TEAM, YOU & PHONE and
 * SYSTEM, Sign out and the floating "Search settings" pill. Every row opens an existing page id ([com.blaze.assistant.ui.settingsPages]),
 * so nothing moved: pages keep their prefs, and every pre-0.53 id still opens (old row ids through settingsAliases).
 *
 * The 0.52.3 profile (SpaceProfile / SpaceSettings) stays as is for the shell with `argus_look` off.
 *
 * Where 04's labels would promise something the page doesn't hold yet, the row says what the page holds (Deviations
 * in overhaul.md): "Approvals & app lock" (Security: app lock, approvals, Bank mode), "Wallet & logins", no People row
 * until the People graph (P5).
 */
object ArgusSettings {
    data class Row(
        val id: String,
        val label: String,
        /** Lucide name (ui/argus/icons). */
        val icon: String,
        /** The settings page it opens. */
        val page: String,
        /** Extra words search matches (04's parentheses). */
        val keywords: List<String> = emptyList(),
        /** A short value on the right ("Automatic"). */
        val value: String? = null,
    )

    data class Group(val title: String?, val rows: List<Row>)

    /** The name at the top opens Profile (the ARGUS account); the plan card opens Usage and plan. */
    const val ACCOUNT_PAGE = "account"
    const val PLAN_PAGE = "plan"

    val GROUPS: List<Group> = listOf(
        Group(null, listOf(
            Row("knows", "What the team knows", "brain", "memory", listOf("memory", "remember", "learned", "profile", "facts")),
            Row("holding", "What ${BrandName.NAME} is holding", "pin", "holding", listOf("pinned", "summary", "task")),
            Row("approvals", "Approvals & app lock", "shield-check", "privacy", listOf("security", "approvals", "ask before", "lock", "bank mode", "risky")),
        )),
        Group("Team", listOf(
            Row("team", "Team & bots", "bot", "specialists", listOf("agents", "bots", "templates", "specialists", "delegate")),
            Row("apps", "Apps & connectors", "plug", "connections", listOf("accounts", "google", "github", "slack", "notion", "spotify", "connectors")),
            Row("skills", "Skills", "sparkles", "skills"),
            Row("models", "Models & engines", "cpu", "ai", listOf("brain", "model", "cloud"), value = "Automatic"),
            Row("computers", "Computers & workspace", "monitor", "terminal", listOf("terminal", "shell", "linux", "jobs", "workspace")),
            Row("routines", "Scheduled tasks", "repeat", "routines", listOf("routines", "schedule", "reminders")),
        )),
        Group("You & phone", listOf(
            Row("voice", "Voice", "mic", "voice", listOf("hey argus", "wake word", "voices", "my voice", "spoken replies")),
            Row("notifications", "Notifications & proactivity", "bell", "proactive", listOf("proactive", "triggers", "check-ins", "speak up")),
            Row("phone", "Phone & permissions", "smartphone", "permissions", listOf("phone control", "accessibility", "overlay", "microphone", "battery", "notifications access")),
            Row("wallet", "Wallet & logins", "key", "secrets", listOf("keys", "tokens", "cards", "logins", "passwords")),
            Row("look", "Appearance", "palette", "look", listOf("theme", "dark", "light", "ground", "size", "handedness", "left-handed", "reduced motion", "animations")),
            Row("language", "Language", "globe", "language"),
        )),
        Group("System", listOf(
            Row("sync", "Sync & devices", "refresh-cw", "sync", listOf("cloud sync", "devices", "encrypted")),
            Row("backup", "Backup & import", "download", "backup", listOf("export", "import", "restore", "a-team")),
            Row("updates", "Updates", "arrow-up", "updates", listOf("version", "upgrade")),
            Row("diagnostics", "Diagnostics", "wrench", "device", listOf("doctor", "health", "self-check", "crash")),
            Row("about", "About", "info", "about", listOf("licences", "version", "privacy")),
            Row("feedback", "Submit feedback", "message-circle", "feedback", listOf("report", "bug")),
        )),
    )

    val ROWS: List<Row> get() = GROUPS.flatMap { it.rows }

    /** Every page the You page reaches. */
    val TREE: Set<String> get() = ROWS.map { it.page }.toSet() + ACCOUNT_PAGE + PLAN_PAGE

    /** Pages that open from another page; everything else goes back to the You page. */
    val PARENT: Map<String, String> = mapOf("logins" to "secrets")

    fun parentOf(page: String?): String? = page?.let { PARENT[it] }

    /** Words that don't name a page ("open the team and bots settings"). */
    val STOP_WORDS = setOf("and", "the", "my", "to", "open", "show", "page", "a", "of", "go")

    /** "Open by name" words for the new rows (added to settingsPageByName's aliases). */
    val NAME_ALIASES: Map<String, String> = mapOf(
        "approvals" to "privacy", "approval" to "privacy", "notifications" to "proactive", "proactivity" to "proactive",
        "wallet" to "secrets", "bots" to "specialists", "team" to "specialists", "connectors" to "connections",
        "engines" to "ai", "computers" to "terminal", "workspace" to "terminal", "devices" to "sync",
        "handedness" to "look", "motion" to "look", "ground" to "look", "appearance" to "look", "knows" to "memory",
        "feedback" to "feedback", "diagnostics" to "device", "ateam" to "backup", "a-team" to "backup",
    )

    /**
     * The floating pill's search: rows whose label, keywords or page title / subtitle hold every word of [query]
     * (case-insensitive), in tree order. [pageText] = id → "title sub" of the page (settingsPages).
     */
    fun search(query: String, pageText: Map<String, String> = emptyMap()): List<Row> {
        val words = query.lowercase().split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return ROWS.filter { r ->
            val hay = (listOf(r.label, pageText[r.page].orEmpty()) + r.keywords).joinToString(" ").lowercase()
            words.all { it in hay }
        }
    }

    // ---------------- Appearance (04 §4.11, 06 row 6) ----------------

    /** Match phone / Dark / Light, stored in ThemeMode ("phone" is new; "system" was always dark, so it reads as Dark). */
    enum class ThemeChoice(val mode: String, val label: String) {
        MATCH_PHONE("phone", "Match phone"), DARK("dark", "Dark"), LIGHT("light", "Light");

        companion object {
            /** null = "auto" (by the clock, from the 0.52 page): none of the three is lit. */
            fun of(mode: String?): ThemeChoice? = when (mode?.trim()) {
                "phone" -> MATCH_PHONE
                "light" -> LIGHT
                "auto" -> null
                else -> DARK
            }
        }
    }

    enum class Hand(val value: String, val label: String) {
        RIGHT("right", "Right-handed"), LEFT("left", "Left-handed");
        companion object { fun of(v: String?): Hand = if (v?.trim() == "left") LEFT else RIGHT }
    }

    const val HAND_KEY = "argus_hand"
    const val REDUCED_MOTION_KEY = "argus_reduced_motion"

    /** Reduced motion is on when the setting is on, or the phone's animator scale is 0 (animations off). */
    fun reducedMotion(setting: Boolean, animatorScale: Float): Boolean = setting || animatorScale == 0f

    /** 04 §5.1.3: primary on the thumb side. Right-handed → [secondary, primary]; left-handed → [primary, secondary]. */
    fun <T> buttonOrder(primary: T, secondary: T, hand: Hand): List<T> =
        if (hand == Hand.LEFT) listOf(primary, secondary) else listOf(secondary, primary)

    /** The Appearance grounds (ArgusTokens.Ground) with their labels. */
    val GROUNDS = listOf("PLAIN" to "Plain", "ORBIT" to "Orbit", "COBALT_NIGHT" to "Cobalt night")
}
