package com.blaze.assistant.ui.space

import com.blaze.assistant.BrandName

/*
 * 0.52.3 "ARGUS x Hark" shell: the pure rules behind the new look (no Compose, no Android), so the unit tests pin them
 * without a phone. The look: the Hark app's layout (a top bar with a round button, a centred Home | Chat | Projects pill
 * and the avatar; large rounded cards with a serif headline; a floating "Message ARGUS" pill) in the ARGUS palette over
 * the "space view" (the Earth's blue-lit limb on a black sky, res/drawable-nodpi/argus_space.webp).
 */

/** The ARGUS palette (docs/BRAND.md) and the shell's surfaces, as ARGB. Compose colours are made from these in SpaceTheme. */
object SpacePalette {
    const val OBSIDIAN = 0xFF000000L
    const val ARGUS_BLUE = 0xFF0056FFL
    const val ELECTRIC = 0xFF1286FFL
    const val STEEL = 0xFF9AA0B3L
    const val WHITE = 0xFFFFFFFFL
    /** Electric Blue lifted for text and icons on the dark cards (Electric itself is a fill / ring colour). */
    const val ELECTRIC_TEXT = 0xFF5AA9FFL

    // ---- dark (the space view)
    /** Over the space image: keeps the stars and the limb but gives text a steady dark ground. */
    const val SCRIM_DARK = 0x8C000000L
    /** The large cards: dark translucent, like Hark's, a touch of blue in the black. */
    const val CARD_DARK = 0xD90B0F1AL
    /** Inner tiles on a card and the user's bubble: a step lighter than the card. */
    const val TILE_DARK = 0xE61A2030L
    const val HAIR_DARK = 0x1FFFFFFFL
    /** The segmented pill's track and the round buttons. */
    const val TRACK_DARK = 0xCC10141FL
    /**
     * Unification: the one solid card material, for surfaces that must not show the space view through them (the profile
     * groups, Spotlight, the composer, dialogs). Same blue-black as [CARD_DARK], nearly opaque.
     */
    const val CARD_SOLID_DARK = 0xF20B0F1AL
    /** Menus and pop-ups: a step above the solid card, practically opaque, so a menu reads over any card. */
    const val MENU_DARK = 0xFA10141FL

    // ---- light (the light ARGUS variant; the space view stays at the top as a band)
    const val GROUND_LIGHT = 0xFFF4F6FBL
    const val CARD_LIGHT = 0xFFFFFFFFL
    /** Light mode: the light card is already opaque, so the solid card and the menu are white too (the hairline edges them). */
    const val CARD_SOLID_LIGHT = CARD_LIGHT
    const val MENU_LIGHT = 0xFFFFFFFFL
    const val TILE_LIGHT = 0xFFEDF1F8L
    const val HAIR_LIGHT = 0x1A0A1020L
    const val TRACK_LIGHT = 0xFFE4E9F3L
    const val INK_LIGHT = 0xFF0A0F1AL
    /** Steel deepened for small text on white (Steel itself is 2.6:1 on white). */
    const val SUB_LIGHT = 0xFF4C5468L
    /** Argus Blue is the text / button blue on light grounds (6.1:1 on white). */
    const val BLUE_LIGHT = ARGUS_BLUE

    /** Scrim alpha over the space image: darker in light mode's top band so the light cards' edge never fights the stars. */
    fun scrimAlpha(dark: Boolean): Float = if (dark) 0.55f else 0.35f
}

/** The three tabs of the segmented pill, in Hark's order, and the round button left of it on each. */
object SpaceTabs {
    const val HOME = "home"
    const val CHAT = "chat"
    const val PROJECTS = "projects"
    val ALL = listOf(HOME to "Home", CHAT to "Chat", PROJECTS to "Projects")

    enum class Left { PLUS, SEARCH, MENU }

    /** Home: "+" (new: Spotlight's actions); Chat: search (Spotlight over the chat); Projects: the menu (the projects list). */
    fun leftButton(tab: String): Left = when (tab) { CHAT -> Left.SEARCH; PROJECTS -> Left.MENU; else -> Left.PLUS }

    fun label(tab: String): String = ALL.firstOrNull { it.first == tab }?.second ?: "Home"

    fun leftLabel(l: Left): String = when (l) {
        Left.PLUS -> "New: search actions, apps and settings"
        Left.SEARCH -> "Search"
        Left.MENU -> "All projects"
    }

    /** The avatar's letter: the first letter of the name, else of the e-mail, else the brand's. */
    fun initial(name: String?, email: String?): String {
        val n = name.orEmpty().trim().ifBlank { email.orEmpty().trim() }
        return n.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: BrandName.UPPER.take(1)
    }

    /** Hark's pill: the selected segment is a white pill with dark text (dark mode) or Argus Blue with white text (light mode). */
    fun selectedFill(dark: Boolean): Long = if (dark) SpacePalette.WHITE else SpacePalette.ARGUS_BLUE
    fun selectedInk(dark: Boolean): Long = if (dark) SpacePalette.INK_LIGHT else SpacePalette.WHITE
}

/**
 * 0.52.4 Hark-sized chrome: nothing touches the screen's sides, top or bottom. The top bar and the composer sit
 * [EDGE_DP] / [COMPOSER_EDGE_DP] in from the sides; the composer pill at least [COMPOSER_MIN_DP] tall.
 *
 * Unification: ONE control height, [CONTROL_DP]. The round buttons and the avatar are [ROUND_DP] (= [CONTROL_DP]); the
 * segmented pill is [SEGMENT_DP] plus its [PILL_FRAME_DP] frame on each side (= [CONTROL_DP]), so the three tops line up;
 * the pill buttons are at least [CONTROL_DP] tall. Every screen's content sits [GUTTER_DP] in (= [EDGE_DP], under the
 * round button); cards pad [CARD_PAD_DP] across and [CARD_PAD_V_DP] down; list rows are at least [ROW_MIN_DP] tall
 * ([ROW_MIN_LARGE_DP] on the profile's roomy groups) with [ROW_PAD_V_DP] above and below. [COMPOSER_EDGE_DP] is the one
 * deliberate exception to the gutter: the composer floats a little further in.
 */
object SpaceInsets {
    const val EDGE_DP = 20
    const val GUTTER_DP = EDGE_DP
    const val CONTROL_DP = 44
    const val ROUND_DP = CONTROL_DP
    const val PILL_FRAME_DP = 4
    const val SEGMENT_DP = CONTROL_DP - 2 * PILL_FRAME_DP
    const val CARD_PAD_DP = 22
    const val CARD_PAD_V_DP = 20
    const val ROW_MIN_DP = 56
    const val ROW_MIN_LARGE_DP = 64
    const val ROW_PAD_V_DP = 14
    const val COMPOSER_EDGE_DP = 28
    const val COMPOSER_MIN_DP = 50
    const val COMPOSER_BOTTOM_DP = 12
}

/**
 * Unification: the shell's type scale in sp, so no screen picks its own size. Serif steps ([DISPLAY], [TITLE], [SECTION])
 * are headlines; the rest are the grotesk ([ROW_TITLE] medium weight). SpaceType turns these into text styles.
 */
object SpaceTypeScale {
    const val DISPLAY = 32f
    const val TITLE = 24f
    const val SECTION = 20f
    const val ROW_TITLE = 17f
    const val BODY = 16f
    const val META = 14f
    const val CAPTION = 12f
    val ALL = listOf(DISPLAY, TITLE, SECTION, ROW_TITLE, BODY, META, CAPTION)
}

/**
 * Unification: the one icon style (0.52.4 icons everywhere), in dp. List rows lead with a round tinted tile [TILE_DP]
 * across holding a [TILE_ICON_DP] icon; compact rows use the [SMALL_DP] tile or a bare [ROW_ICON_DP] icon; buttons and
 * chips lead with a [BUTTON_ICON_DP] icon, [GAP_DP] before the text. The tile's tint shows at [fillAlpha].
 */
object SpaceIcons {
    const val TILE_DP = 40
    const val TILE_ICON_DP = 20
    const val SMALL_DP = 32
    const val SMALL_ICON_DP = 17
    const val ROW_ICON_DP = 20
    const val BUTTON_ICON_DP = 18
    const val GAP_DP = 14
    fun fillAlpha(dark: Boolean): Float = if (dark) 0.18f else 0.12f
}

/** How the pill fits the room it has (dp) at the phone's font scale: 15 sp labels, smaller on narrow screens. */
object SpacePillFit {
    data class Fit(val padDp: Int, val fontSp: Float)

    /**
     * ~0.58 em per character of "HomeChatProjects" (16 chars) + padding per segment + the 8 dp frame + the selected
     * segment's icon and its gap ([iconDp], 0.52.4).
     */
    fun need(padDp: Int, fontSp: Float, fontScale: Float): Float = 16 * fontSp * fontScale * 0.58f + 3 * 2 * padDp + 8 + iconDp(fontSp) + ICON_GAP_DP

    /** The selected segment's icon: a little larger than its label's cap height. */
    fun iconDp(fontSp: Float): Float = fontSp + 3f
    const val ICON_GAP_DP = 6f

    /** The selected segment's icon shows only when the picked step really fits (the last-resort step drops it). */
    fun showIcon(fit: Fit, width: Float, fontScale: Float): Boolean = need(fit.padDp, fit.fontSp, fontScale) <= width

    fun pick(width: Float, fontScale: Float): Fit = when {
        need(18, 15f, fontScale) <= width -> Fit(18, 15f)
        need(12, 14f, fontScale) <= width -> Fit(12, 14f)
        need(8, 13f, fontScale) <= width -> Fit(8, 13f)
        else -> Fit(6, 12f)
    }
}

/** The Home's serif headline: time-aware, with the first name when known (moved from the orb Home's greeting). */
object SpaceGreeting {
    fun hello(hour: Int): String = when (((hour % 24) + 24) % 24) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..23 -> "Good evening"
        else -> "Still up"
    }

    /** "Good evening, Gaby" when a first name is known (only the first word of [name], never an e-mail). */
    fun hello(hour: Int, name: String?): String {
        val first = name.orEmpty().trim().substringBefore(' ').takeIf { it.isNotBlank() && '@' !in it && it.length <= 24 }
        return if (first == null) hello(hour) else hello(hour) + ", " + first
    }

    /** The local hour (0..23) at [epochMs] in [zone]: kept out of the composable so tests can pin it. */
    fun hourOf(epochMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int =
        java.util.Calendar.getInstance(zone).apply { timeInMillis = epochMs }.get(java.util.Calendar.HOUR_OF_DAY)

    fun hourNow(): Int = hourOf(System.currentTimeMillis())

    const val HOUR_MS = 3_600_000L

    /** Milliseconds from [epochMs] to the next full local hour in [zone] (at least 1 s); half-hour zones included. */
    fun msToNextHour(epochMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        val local = epochMs + zone.getOffset(epochMs)
        return (HOUR_MS - Math.floorMod(local, HOUR_MS)).coerceAtLeast(1_000L)
    }

    /** The line under the headline. */
    fun sub(working: Boolean): String = if (working) "${BrandName.UPPER} is working on it. You can keep going." else "What would you like to do?"

    /** Asks the Home suggests as white pill chips (Hark's action chips), tapped = sent. */
    val SUGGESTIONS = listOf("What's on today?", "Summarise my notifications", "Plan my evening", "What's the weather?")
}

/** The Home's "latest answer" card: the newest ask and ARGUS's reply to it, in plain text (pure, fed the chat's messages). */
object SpaceAnswer {
    data class Msg(val id: Long, val fromArgus: Boolean, val text: String)
    data class Card(val ask: String, val text: String, val working: Boolean)

    const val MAX_CHARS = 280

    /** The card for the newest ask after [askFrom] and the newest reply to it; null with no ask, or nothing back and nothing running. */
    fun card(askFrom: Long, msgs: List<Msg>, busy: Boolean): Card? {
        if (askFrom < 0L) return null
        val after = msgs.filter { it.id > askFrom }
        val ask = after.lastOrNull { !it.fromArgus } ?: return null
        val reply = after.lastOrNull { it.fromArgus && it.id > ask.id && it.text.isNotBlank() }
        if (reply == null && !busy) return null
        return Card(plain(ask.text, 90), reply?.let { plain(it.text, MAX_CHARS) }.orEmpty(), busy)
    }

    /** Markdown and code fences out, whitespace folded, cut at a word with an ellipsis. */
    fun plain(text: String, max: Int = MAX_CHARS): String {
        var t = text.replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+\\.)\\s+"), "")
            .replace(Regex("[*_`~|]"), "")
            .replace(Regex("\\s+"), " ").trim()
        if (t.length > max) {
            val cut = t.substring(0, max)
            val sp = cut.lastIndexOf(' ')
            t = (if (sp > max / 2) cut.substring(0, sp) else cut).trimEnd(',', '.', ';', ':', ' ') + "\u2026"
        }
        return t
    }
}

/**
 * Which cards the Home stacks, top to bottom (Hark's Home: a hero card with a serif headline, then what needs you, then
 * panels, then a pill button). Pure so the order is pinned by tests.
 */
object SpaceHomeCards {
    enum class Card { HERO, SIGN_IN, SETUP, ANSWER, PROJECTS, SUGGESTIONS, NEW_CHAT }

    data class Input(val signedIn: Boolean, val hasAnswer: Boolean, val projects: Int, val setupLeft: Int)

    fun plan(i: Input): List<Card> = buildList {
        add(Card.HERO)
        if (!i.signedIn) add(Card.SIGN_IN)
        if (i.setupLeft > 0) add(Card.SETUP)
        if (i.hasAnswer) add(Card.ANSWER)
        if (i.projects > 0) add(Card.PROJECTS)
        add(Card.SUGGESTIONS)
        add(Card.NEW_CHAT)
    }

    /** At most this many projects on the Home's card (the rest are one tap away in Projects). */
    const val MAX_PROJECTS = 3
}

/**
 * The profile page (Hark's settings): a big name + e-mail header, a plan card, then two grouped cards of icon + label +
 * chevron rows, a sign-out row, and the floating Search pill. Each row opens an existing settings page by id
 * ([com.blaze.assistant.ui.SettingsScreen]). 0.52.3: this replaces the old Settings list, Advanced and the brain pages.
 */
object SpaceProfile {
    enum class Icon { PROFILE, SCHEDULE, ACCOUNTS, WALLET, SECURITY, SYSTEM, FEEDBACK, ABOUT, PLAN, SIGN_OUT }

    data class Row(val id: String, val label: String, val icon: Icon, val page: String)

    val PLAN = Row("plan", "Usage and plan", Icon.PLAN, "plan")

    val GROUPS: List<List<Row>> = listOf(
        listOf(
            Row("profile", "Profile", Icon.PROFILE, "account"),
            Row("scheduled", "Scheduled Tasks", Icon.SCHEDULE, "routines"),
            Row("accounts", "Accounts", Icon.ACCOUNTS, "connections"),
            Row("wallet", "Wallet", Icon.WALLET, "secrets"),
        ),
        listOf(
            Row("security", "Security", Icon.SECURITY, "privacy"),
            Row("system", "System Preferences", Icon.SYSTEM, SYSTEM_PAGE),
            Row("feedback", "Submit Feedback", Icon.FEEDBACK, FEEDBACK_PAGE),
            Row("about", "About", Icon.ABOUT, "about"),
        ),
    )

    const val SYSTEM_PAGE = "system"
    const val FEEDBACK_PAGE = "feedback"

    /** Every page the profile can open (the settings screen must know each one). */
    val PAGES: Set<String> get() = (GROUPS.flatten() + PLAN).map { it.page }.toSet()

    fun row(id: String): Row? = (GROUPS.flatten() + PLAN).firstOrNull { it.id == id }

    /** The header: the account's name, else the name ARGUS calls you, else "Your ARGUS". */
    fun title(accountName: String?, userName: String?): String =
        accountName?.trim()?.takeIf { it.isNotEmpty() } ?: userName?.trim()?.takeIf { it.isNotEmpty() } ?: "Your ${BrandName.UPPER}"

    fun subtitle(email: String?, signedIn: Boolean): String =
        if (signedIn) email.orEmpty().trim().ifBlank { "Signed in" } else "Not signed in \u00b7 tap Profile to sign in"
}

/**
 * The settings tree after the 0.52.3 declutter: the profile's rows, the pages System Preferences lists, and each page's
 * parent for Back. Pages outside the tree left the Settings UI (their prefs keep their defaults and the app keeps reading
 * them; ARGUS can still open one by name). The brain is picked automatically (BrainOrder / ModelChoice AUTO): there is no
 * model or brain picker anywhere.
 */
object SpaceSettings {
    /** System Preferences: appearance, language, voice, permissions, updates (each an existing page). */
    val SYSTEM_ROWS = listOf("look" to "Appearance", "language" to "Language", "voice" to "Voice",
        "permissions" to "Permissions", "updates" to "Updates")

    /** Pages that open from another page (Back returns there); everything else returns to the profile. */
    val PARENT: Map<String, String> = SYSTEM_ROWS.associate { it.first to SpaceProfile.SYSTEM_PAGE } +
        mapOf("sync" to "account", "plan" to "account", "logins" to "secrets")

    fun parentOf(page: String?): String? = page?.let { PARENT[it] }

    /** Every page reachable from the profile. */
    val TREE: Set<String> get() = SpaceProfile.PAGES + SYSTEM_ROWS.map { it.first } + PARENT.keys

    /** Pages that were listed in Settings before 0.52.3 and are not any more (named in the handoff). */
    val REMOVED_FROM_SETTINGS = listOf("ai", "backup_brain", "advanced", "feel", "orb", "commands", "watches", "usage",
        "dashboard", "replies", "logs", "proactive", "persona", "device", "backup", "desktop", "phonebrowser", "doing", "control")

    /** Pages nobody may remove (permissions, privacy, account, about / licences, feedback). */
    val REQUIRED = setOf("permissions", "privacy", "account", "about", SpaceProfile.FEEDBACK_PAGE)
}

/**
 * 0.52.3 first run (replaces SetupFlow, the dependency gate and the old intro): the ARGUS logo with the tagline, the
 * account sign-in only when not signed in yet (skippable: the cloud brain needs it, nothing else does), then ONE setup
 * screen where everything downloads by itself ([com.blaze.assistant.setup.SetupPlan]). No permission screens: the
 * notification prompt comes from Android at first launch and every other permission is asked when a task needs it.
 */
object SpaceOnboarding {
    enum class Step { WELCOME, SIGN_IN, SETUP }

    const val TAGLINE = "Intelligence beyond the interface"
    const val PREF_ONBOARDED = "onboarded"

    fun first(): Step = Step.WELCOME

    /** The step after [step]; null = onboarding is over (into the app). */
    fun next(step: Step, signedIn: Boolean): Step? = when (step) {
        Step.WELCOME -> if (signedIn) Step.SETUP else Step.SIGN_IN
        Step.SIGN_IN -> Step.SETUP
        Step.SETUP -> null
    }

    /** First run only: someone who already used ARGUS (onboarded, signed in, or with a chat) never sees it. */
    fun show(onboarded: Boolean, signedIn: Boolean, hasMessages: Boolean): Boolean = !onboarded && !signedIn && !hasMessages

    fun signInButton(signedIn: Boolean): String = if (signedIn) "Continue" else "Skip for now"

    fun setupButton(complete: Boolean): String = if (complete) "Start using ${BrandName.UPPER}" else "Continue \u00b7 finishes in the background"
}

/**
 * 0.52.3 Projects tab (replaces the glass ProjectsPane): Hark's look, a serif "Projects" headline, one large card per
 * project (its initial, name, chat count and a Needs you / Working chip), a "+ New project" pill; a project opens as a
 * page of cards (New chat, Instructions, Brief, its chats). Pure: what each project row says.
 */
object SpaceProjectRows {
    enum class Status { NEEDS_YOU, WORKING, IDLE }

    fun status(chats: List<String>, busy: Set<String>, needsYou: Set<String>): Status = when {
        chats.any { it in needsYou } -> Status.NEEDS_YOU
        chats.any { it in busy } -> Status.WORKING
        else -> Status.IDLE
    }

    fun chip(s: Status): String = when (s) { Status.NEEDS_YOU -> "Needs you"; Status.WORKING -> "Working"; Status.IDLE -> "" }

    /** Only chats that still exist are counted (an id whose file is gone isn't a chat). */
    fun count(chatIds: List<String>, existing: Set<String>): Int = chatIds.count { it in existing }

    fun countLabel(n: Int): String = if (n == 1) "1 chat" else "$n chats"

    /** The project's round badge letter: the first letter or digit of its name, else "#". */
    fun initial(name: String): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

    const val INTRO = "Group chats around one goal, with instructions ${BrandName.UPPER} follows in every chat inside it."
}

/** Submit Feedback's note: the user's words plus the version and the phone (nothing personal, nothing else). */
object SpaceFeedback {
    const val SUBJECT = "${BrandName.UPPER} feedback"

    fun body(text: String, version: String, model: String, sdk: Int): String =
        text.trim() + "\n\n\u2014\n" + BrandName.UPPER + " " + version.ifBlank { "?" } + " \u00b7 " + model.ifBlank { "Android" } + " \u00b7 Android API " + sdk
}

/** Test tags of the new shell (Modifier.testTag): shots, the wiring test and the device checks find parts by these. */
object SpaceTags {
    const val ROOT = "space_root"
    const val BACKDROP = "space_backdrop"
    const val TOP_BAR = "space_top_bar"
    const val LEFT_BUTTON = "space_left_button"
    const val PILL = "space_pill"
    const val TAB_PREFIX = "space_tab_"
    const val AVATAR = "space_avatar"
    const val HOME = "space_home"
    const val HERO = "space_home_hero"
    const val ANSWER = "space_home_answer"
    const val PROJECTS_CARD = "space_home_projects"
    const val SUGGESTIONS = "space_home_suggestions"
    const val NEW_CHAT = "space_home_new_chat"
    const val SETUP_CARD = "space_home_setup"
    const val SIGN_IN = "space_home_sign_in"
    const val COMPOSER = "space_composer"
    const val COMPOSER_FIELD = "space_composer_field"
    const val COMPOSER_PLUS = "space_composer_plus"
    const val COMPOSER_MIC = "space_composer_mic"
    const val COMPOSER_SEND = "space_composer_send"
    const val SPOTLIGHT = "space_spotlight"
    const val SPOTLIGHT_FIELD = "space_spotlight_field"
    const val SCRIM = "space_spotlight_scrim"
    const val PROFILE = "space_profile"
    const val PROFILE_ROW_PREFIX = "space_profile_row_"
    const val PROFILE_SEARCH = "space_profile_search"
    const val ONBOARDING = "space_onboarding"
    const val ONBOARDING_LOGO = "space_onboarding_logo"
    const val ONBOARDING_START = "space_onboarding_start"
    const val SETUP = "space_setup"
    const val SETUP_PROGRESS = "space_setup_progress"
    const val SETUP_ROW_PREFIX = "space_setup_row_"
    const val SETUP_CONTINUE = "space_setup_continue"
    const val PROJECTS = "space_projects"
    const val PROJECT_ROW_PREFIX = "space_project_row_"
    const val PROJECT_NEW = "space_project_new"

    fun tab(id: String) = TAB_PREFIX + id
    fun projectRow(id: String) = PROJECT_ROW_PREFIX + id
    fun profileRow(id: String) = PROFILE_ROW_PREFIX + id
    fun setupRow(id: String) = SETUP_ROW_PREFIX + id

    val FIXED = listOf(ROOT, BACKDROP, TOP_BAR, LEFT_BUTTON, PILL, AVATAR, HOME, HERO, ANSWER, PROJECTS_CARD, SUGGESTIONS, NEW_CHAT,
        SETUP_CARD, SIGN_IN, COMPOSER, COMPOSER_FIELD, COMPOSER_PLUS, COMPOSER_MIC, COMPOSER_SEND, SPOTLIGHT, SPOTLIGHT_FIELD, SCRIM,
        PROFILE, PROFILE_SEARCH, ONBOARDING, ONBOARDING_LOGO, ONBOARDING_START, SETUP, SETUP_PROGRESS, SETUP_CONTINUE,
        PROJECTS, PROJECT_NEW)
}

/** How big to decode the space image for a screen (power-of-two sampling, never below the screen's height). */
object SpaceImage {
    const val WIDTH = 1080
    const val HEIGHT = 2400

    fun sample(screenW: Int, screenH: Int, lowRam: Boolean): Int {
        if (screenW <= 0 || screenH <= 0) return if (lowRam) 2 else 1
        var s = 1
        while (WIDTH / (s * 2) >= screenW && HEIGHT / (s * 2) >= screenH) s *= 2
        return if (lowRam) maxOf(s, 2) else s
    }
}
