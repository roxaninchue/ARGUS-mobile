package com.blaze.assistant.ui.argus.icons

/** P0-SH-02: the pure rules around [LucidePaths] (LucidePathsTest): names, aliases, stroke by size, 04 §2.7 semantics. */
object LucideSpec {
    const val FALLBACK = "square"

    /** A-team `ic()`: 2.25 at ≤ 16 px, 2 at ≤ 20, 1.85 above (in the 24-unit viewBox). */
    fun strokeFor(sizeDp: Int): Float = when {
        sizeDp <= 16 -> 2.25f
        sizeDp <= 20 -> 2f
        else -> 1.85f
    }

    fun resolve(name: String): String {
        val n = name.trim().lowercase()
        val k = LucidePaths.ALIASES[n] ?: n
        return if (LucidePaths.ICONS.containsKey(k)) k else FALLBACK
    }

    /** 04 §2.7 sizes (dp). */
    object Size { const val CHIP = 15; const val TOOL = 16; const val BUTTON = 18; const val ICON_BUTTON = 20; const val COMPOSER = 21; const val MENU = 22; const val BACK = 24 }

    /** 04 §2.7 "keep consistent everywhere". */
    object Semantic {
        const val BOTS = "bot"; const val CHAT = "message-circle"; const val INBOX = "bell"; const val NEW = "plus"; const val SEARCH = "search"
        const val MEMORY = "brain"; const val SOUL = "scroll-text"; const val ENGINE = "cpu"; const val APPS = "blocks"; const val COMPUTER = "monitor"
        const val ROUTINES = "repeat"; const val JOBS = "list-checks"; const val LIBRARY = "library"; const val APPROVALS = "shield-check"
        const val PEOPLE = "users"; const val GRAPH = "workflow"; const val TERMINAL = "square-terminal"; const val BROWSER = "globe"
        const val CLOUD = "cloud"; const val VOICE = "mic"; const val LIVE_CALL = "audio-lines"; const val TAKE_OVER = "hand"; const val NEEDS_YOU = "flag"
        const val SKILLS = "sparkles"
        val ALL = listOf(BOTS, CHAT, INBOX, NEW, SEARCH, MEMORY, SOUL, ENGINE, APPS, COMPUTER, ROUTINES, JOBS, LIBRARY, APPROVALS, PEOPLE, GRAPH,
            TERMINAL, BROWSER, CLOUD, VOICE, LIVE_CALL, TAKE_OVER, NEEDS_YOU, SKILLS)
    }

    /** Life domains (ArgusTokens.DOMAIN_COLORS keys) → glyph. */
    val DOMAIN: Map<String, String> = linkedMapOf("health" to "heart-pulse", "money" to "wallet", "work" to "briefcase", "home" to "house",
        "people" to "users", "learning" to "graduation-cap", "travel" to "plane", "create" to "palette", "mind" to "notebook-pen", "phone" to "smartphone")
}
