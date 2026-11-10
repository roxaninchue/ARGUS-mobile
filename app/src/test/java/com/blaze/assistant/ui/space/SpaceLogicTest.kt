package com.blaze.assistant.ui.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import com.blaze.assistant.ui.space.SpaceHomeCards.Card as C
import com.blaze.assistant.ui.space.SpaceOnboarding.Step as S
import com.blaze.assistant.ui.space.SpaceProjectRows.Status as PS

/**
 * 0.52.3 "ARGUS x Hark" shell, the pure rules (SpaceLogic.kt): the palette, the three tabs and their round button, the
 * pill's fit, the greeting and its hourly refresh, the Home's answer card and card order, the profile page's rows, the
 * decluttered settings tree, the onboarding steps, the projects rows, feedback, the test tags and the image sampling,
 * and the unification tokens (control height, gutter, card padding, rows, type scale, icons, solid card and menu).
 * Replaces ArgusHomeLogicTest (the 3D Home's orb, rings, GL renderer, pull sheet and meshes were removed with ui/argus).
 */
class SpaceLogicTest {
    private fun m(id: Long, argus: Boolean, text: String) = SpaceAnswer.Msg(id, argus, text)

    @Test fun thePaletteIsArgus() {
        assertEquals(0xFF000000L, SpacePalette.OBSIDIAN)
        assertEquals(0xFF0056FFL, SpacePalette.ARGUS_BLUE)
        assertEquals(0xFF1286FFL, SpacePalette.ELECTRIC)
        assertEquals(0xFF9AA0B3L, SpacePalette.STEEL)
        assertEquals(0xFFFFFFFFL, SpacePalette.WHITE)
        assertEquals(SpacePalette.ARGUS_BLUE, SpacePalette.BLUE_LIGHT)
        // the scrim keeps the stars in dark mode and is lighter in the light band
        assertTrue(SpacePalette.scrimAlpha(true) in 0.4f..0.8f)
        assertTrue(SpacePalette.scrimAlpha(false) < SpacePalette.scrimAlpha(true))
        // the cards are translucent over the space view, opaque on the light ground
        assertTrue((SpacePalette.CARD_DARK ushr 24) in 0xC0L..0xFEL)
        assertEquals(0xFFL, SpacePalette.CARD_LIGHT ushr 24)
    }

    /**
     * Unification tokens: one control height (round buttons, the segmented pill with its frame, the pill buttons), one
     * gutter under the round button, one card padding and row size, a stepped type scale and one icon style.
     */
    @Test fun oneSetOfShellTokens() {
        assertEquals(44, SpaceInsets.CONTROL_DP)
        assertEquals(SpaceInsets.CONTROL_DP, SpaceInsets.ROUND_DP)
        assertEquals("the pill is as tall as the round buttons beside it", SpaceInsets.CONTROL_DP, SpaceInsets.SEGMENT_DP + 2 * SpaceInsets.PILL_FRAME_DP)
        assertEquals(20, SpaceInsets.GUTTER_DP)
        assertEquals("content lines up under the round button", SpaceInsets.EDGE_DP, SpaceInsets.GUTTER_DP)
        assertTrue("the composer is the one deliberate exception", SpaceInsets.COMPOSER_EDGE_DP > SpaceInsets.GUTTER_DP)
        assertEquals(22, SpaceInsets.CARD_PAD_DP)
        assertEquals(56, SpaceInsets.ROW_MIN_DP)
        assertTrue(SpaceInsets.ROW_MIN_LARGE_DP > SpaceInsets.ROW_MIN_DP)
        assertTrue("a row's padding leaves room for a line of text", SpaceInsets.ROW_MIN_DP - 2 * SpaceInsets.ROW_PAD_V_DP >= 24)
        assertEquals(listOf(32f, 24f, 20f, 17f, 16f, 14f, 12f), SpaceTypeScale.ALL)
        assertEquals("the scale steps down", SpaceTypeScale.ALL.sortedDescending(), SpaceTypeScale.ALL)
        assertEquals(SpaceTypeScale.ALL.size, SpaceTypeScale.ALL.toSet().size)
        assertTrue("the icon sits inside its tile", SpaceIcons.TILE_ICON_DP < SpaceIcons.TILE_DP && SpaceIcons.SMALL_ICON_DP < SpaceIcons.SMALL_DP)
        assertTrue("the tile fits a list row", SpaceIcons.TILE_DP <= SpaceInsets.ROW_MIN_DP - 2 * 8)
        assertTrue(SpaceIcons.fillAlpha(true) > SpaceIcons.fillAlpha(false))
        // the solid card and the menu are (nearly) opaque in dark mode and opaque in light mode
        assertTrue((SpacePalette.CARD_SOLID_DARK ushr 24) >= 0xF0L)
        assertTrue((SpacePalette.MENU_DARK ushr 24) >= (SpacePalette.CARD_SOLID_DARK ushr 24))
        assertEquals(0xFFL, SpacePalette.CARD_SOLID_LIGHT ushr 24)
        assertEquals(0xFFL, SpacePalette.MENU_LIGHT ushr 24)
    }

    @Test fun threeTabsInHarksOrderWithTheirRoundButton() {
        assertEquals(listOf("home", "chat", "projects"), SpaceTabs.ALL.map { it.first })
        assertEquals(listOf("Home", "Chat", "Projects"), SpaceTabs.ALL.map { it.second })
        assertEquals(SpaceTabs.Left.PLUS, SpaceTabs.leftButton(SpaceTabs.HOME))
        assertEquals(SpaceTabs.Left.SEARCH, SpaceTabs.leftButton(SpaceTabs.CHAT))
        assertEquals(SpaceTabs.Left.MENU, SpaceTabs.leftButton(SpaceTabs.PROJECTS))
        assertEquals(SpaceTabs.Left.PLUS, SpaceTabs.leftButton("anything else"))
        assertEquals("Home", SpaceTabs.label("nope"))
        for (l in SpaceTabs.Left.values()) assertTrue(SpaceTabs.leftLabel(l).isNotBlank())
        assertEquals(3, SpaceTabs.Left.values().map { SpaceTabs.leftLabel(it) }.toSet().size)
        // the tab ids are the chat shell's
        assertEquals(com.blaze.assistant.agent.ChatNav.HOME, SpaceTabs.HOME)
        assertEquals(com.blaze.assistant.agent.ChatNav.CHAT, SpaceTabs.CHAT)
        assertEquals(com.blaze.assistant.agent.ChatNav.PROJECTS, SpaceTabs.PROJECTS)
    }

    @Test fun theAvatarLetter() {
        assertEquals("G", SpaceTabs.initial("gaby Alarcon", "x@y.z"))
        assertEquals("A", SpaceTabs.initial("  ", "alarcon@example.com"))
        assertEquals("A", SpaceTabs.initial(null, null))   // ARGUS
        assertEquals("7", SpaceTabs.initial("  7even", null))
        assertEquals("Z", SpaceTabs.initial("\u00a1zed", null))
    }

    @Test fun theSelectedSegmentFlipsWithTheMode() {
        assertEquals(SpacePalette.WHITE, SpaceTabs.selectedFill(true))
        assertEquals(SpacePalette.INK_LIGHT, SpaceTabs.selectedInk(true))
        assertEquals(SpacePalette.ARGUS_BLUE, SpaceTabs.selectedFill(false))
        assertEquals(SpacePalette.WHITE, SpaceTabs.selectedInk(false))
    }

    @Test fun thePillShrinksToFit() {
        assertEquals(SpacePillFit.Fit(18, 15f), SpacePillFit.pick(400f, 1f))
        // narrower rooms and bigger fonts step down, never up
        val widths = listOf(400f, 300f, 240f, 200f, 120f)
        val picks = widths.map { SpacePillFit.pick(it, 1.3f) }
        for (i in 1 until picks.size) assertTrue(picks[i].fontSp <= picks[i - 1].fontSp)
        assertEquals(SpacePillFit.Fit(6, 12f), SpacePillFit.pick(50f, 2f))
        // what is picked fits whenever a bigger step does not
        for (w in 150..420 step 10) for (s in listOf(0.85f, 1f, 1.3f, 2f)) {
            val f = SpacePillFit.pick(w.toFloat(), s)
            if (f != SpacePillFit.Fit(6, 12f)) assertTrue("w=$w s=$s", SpacePillFit.need(f.padDp, f.fontSp, s) <= w)
            // 0.52.4: the selected segment's icon shows whenever the picked step fits, and only then
            if (f != SpacePillFit.Fit(6, 12f)) assertTrue("icon w=$w s=$s", SpacePillFit.showIcon(f, w.toFloat(), s))
        }
        assertTrue(SpacePillFit.showIcon(SpacePillFit.pick(400f, 1f), 400f, 1f))
        assertFalse("no room: the icon goes first", SpacePillFit.showIcon(SpacePillFit.pick(50f, 2f), 50f, 2f))
    }

    @Test fun greetingFollowsTheHour() {
        for (h in 5..11) assertEquals("Good morning", SpaceGreeting.hello(h))
        for (h in 12..16) assertEquals("Good afternoon", SpaceGreeting.hello(h))
        for (h in 17..23) assertEquals("Good evening", SpaceGreeting.hello(h))
        for (h in 0..4) assertEquals("Still up", SpaceGreeting.hello(h))
        assertEquals(SpaceGreeting.hello(1), SpaceGreeting.hello(25))
        assertEquals(SpaceGreeting.hello(23), SpaceGreeting.hello(-1))
    }

    @Test fun greetingUsesOnlyAFirstName() {
        assertEquals("Good evening, Gaby", SpaceGreeting.hello(19, "Gaby"))
        assertEquals("Good morning, Ada", SpaceGreeting.hello(8, "  Ada Lovelace "))
        assertEquals("Good afternoon", SpaceGreeting.hello(13, null))
        assertEquals("Good afternoon", SpaceGreeting.hello(13, "   "))
        assertEquals("Good afternoon", SpaceGreeting.hello(13, "gaby@example.com"))
        assertEquals("Good afternoon", SpaceGreeting.hello(13, "x".repeat(40)))
    }

    @Test fun greetingHourComesFromTheClockInTheLocalZone() {
        val utc = TimeZone.getTimeZone("UTC")
        val lusaka = TimeZone.getTimeZone("Africa/Lusaka")
        val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
        val t = 1_762_419_600_000L                                 // 2025-11-06 09:00:00 UTC
        assertEquals(9, SpaceGreeting.hourOf(t, utc))
        assertEquals(11, SpaceGreeting.hourOf(t, lusaka))
        assertEquals(14, SpaceGreeting.hourOf(t, kolkata))
        assertTrue(SpaceGreeting.hourNow() in 0..23)
    }

    @Test fun theGreetingIsReReadAtTheNextFullHour() {
        val utc = TimeZone.getTimeZone("UTC")
        val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
        val t = 1_762_419_600_000L
        assertEquals(SpaceGreeting.HOUR_MS, SpaceGreeting.msToNextHour(t, utc))
        assertEquals(15 * 60_000L, SpaceGreeting.msToNextHour(t + 45 * 60_000L, utc))
        assertEquals(30 * 60_000L, SpaceGreeting.msToNextHour(t, kolkata))
        assertEquals(1_000L, SpaceGreeting.msToNextHour(t + SpaceGreeting.HOUR_MS - 10L, utc))
        for (z in listOf(utc, kolkata, TimeZone.getTimeZone("America/New_York"))) for (k in 0 until 50) {
            val now = t + k * 1_234_567L
            val wait = SpaceGreeting.msToNextHour(now, z)
            assertTrue(wait in 1_000L..SpaceGreeting.HOUR_MS)
            assertTrue(SpaceGreeting.hourOf(now + wait, z) != SpaceGreeting.hourOf(now, z) || wait == 1_000L)
        }
    }

    @Test fun subLineAndSuggestions() {
        assertTrue(SpaceGreeting.sub(true).contains("working"))
        assertEquals("What would you like to do?", SpaceGreeting.sub(false))
        assertTrue(SpaceGreeting.SUGGESTIONS.size in 3..6)
        assertEquals(SpaceGreeting.SUGGESTIONS.size, SpaceGreeting.SUGGESTIONS.toSet().size)
    }

    @Test fun answerCardShowsTheNewestAskAndItsReply() {
        val msgs = listOf(m(1, false, "old ask"), m(2, true, "old reply"), m(5, false, "What's the news?"), m(6, true, "## Top\n- **One** thing\n- [Two](https://x.y) things"))
        assertNull(SpaceAnswer.card(6, msgs, busy = false))
        assertNull(SpaceAnswer.card(-1, msgs, busy = true))
        val c = SpaceAnswer.card(2, msgs, busy = false)
        assertNotNull(c); c!!
        assertEquals("What's the news?", c.ask)
        assertEquals("Top One thing Two things", c.text)
        assertFalse(c.working)
    }

    @Test fun answerCardWhileWorkingAndWithoutReply() {
        val msgs = listOf(m(10, false, "Plan my day"))
        val working = SpaceAnswer.card(9, msgs, busy = true)
        assertNotNull(working)
        assertEquals("", working!!.text)
        assertTrue(working.working)
        assertNull(SpaceAnswer.card(9, msgs, busy = false))
        val two = listOf(m(10, false, "a"), m(11, true, "reply to a"), m(12, false, "b"))
        assertEquals("", SpaceAnswer.card(9, two, busy = true)!!.text)
        assertEquals("b", SpaceAnswer.card(9, two, busy = true)!!.ask)
        assertNull(SpaceAnswer.card(9, two + m(13, true, "   "), busy = false))
    }

    @Test fun plainTextIsCutAtAWord() {
        assertEquals("a b", SpaceAnswer.plain("a\n\n   b"))
        assertEquals("before after", SpaceAnswer.plain("before ```kotlin\nval x = 1\n``` after"))
        assertEquals("quote item", SpaceAnswer.plain("> quote\n1. item"))
        val long = (1..200).joinToString(" ") { "word$it" }
        val cut = SpaceAnswer.plain(long)
        assertTrue(cut.length <= SpaceAnswer.MAX_CHARS + 1)
        assertTrue(cut.endsWith("\u2026"))
        assertTrue(cut.removeSuffix("\u2026").split(' ').all { it.startsWith("word") && it.length > 4 })
        assertEquals(91, SpaceAnswer.plain("y".repeat(200), 90).length)
    }

    @Test fun homeCardsStackInHarksOrder() {
        assertEquals(listOf(C.HERO, C.SUGGESTIONS, C.NEW_CHAT), SpaceHomeCards.plan(SpaceHomeCards.Input(true, false, 0, 0)))
        assertEquals(listOf(C.HERO, C.SIGN_IN, C.SETUP, C.ANSWER, C.PROJECTS, C.SUGGESTIONS, C.NEW_CHAT),
            SpaceHomeCards.plan(SpaceHomeCards.Input(signedIn = false, hasAnswer = true, projects = 2, setupLeft = 3)))
        // every combination starts with the hero and ends with the new-chat pill, no card twice
        for (s in listOf(true, false)) for (a in listOf(true, false)) for (p in 0..1) for (l in 0..1) {
            val plan = SpaceHomeCards.plan(SpaceHomeCards.Input(s, a, p, l))
            assertEquals(C.HERO, plan.first()); assertEquals(C.NEW_CHAT, plan.last()); assertEquals(plan.size, plan.toSet().size)
        }
        assertTrue(SpaceHomeCards.MAX_PROJECTS in 1..5)
    }

    @Test fun theProfilePageIsHarks() {
        assertEquals(listOf("Profile", "Scheduled Tasks", "Accounts", "Wallet", "What Argus is holding"), SpaceProfile.GROUPS[0].map { it.label })
        assertEquals(listOf("Security", "System Preferences", "Submit Feedback", "About"), SpaceProfile.GROUPS[1].map { it.label })
        assertEquals("Usage and plan", SpaceProfile.PLAN.label)
        val all = SpaceProfile.GROUPS.flatten() + SpaceProfile.PLAN
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.icon }.toSet().size)
        for (r in all) assertEquals(r, SpaceProfile.row(r.id))
        assertNull(SpaceProfile.row("advanced"))
        // each row opens a real settings page
        val pages = com.blaze.assistant.ui.settingsPages.map { it.id }.toSet()
        for (p in SpaceProfile.PAGES) assertTrue("page $p", p in pages)
        assertEquals("Gaby Alarcon", SpaceProfile.title(" Gaby Alarcon ", "G"))
        assertEquals("Gaby", SpaceProfile.title(" ", "Gaby"))
        assertEquals("Your ARGUS", SpaceProfile.title(null, ""))
        assertEquals("gaby@example.com", SpaceProfile.subtitle(" gaby@example.com", true))
        assertEquals("Signed in", SpaceProfile.subtitle("", true))
        assertTrue(SpaceProfile.subtitle("x@y.z", false).startsWith("Not signed in"))
    }

    @Test fun theSettingsTreeIsDecluttered() {
        assertEquals(listOf("look", "language", "voice", "permissions", "updates"), SpaceSettings.SYSTEM_ROWS.map { it.first })
        for ((p, _) in SpaceSettings.SYSTEM_ROWS) assertEquals(SpaceProfile.SYSTEM_PAGE, SpaceSettings.parentOf(p))
        assertEquals("account", SpaceSettings.parentOf("plan"))
        assertEquals("account", SpaceSettings.parentOf("sync"))
        assertEquals("secrets", SpaceSettings.parentOf("logins"))
        assertNull("profile rows go back to the profile", SpaceSettings.parentOf("about"))
        assertNull(SpaceSettings.parentOf(null))
        // every page in the tree exists, the required ones are in it, the removed ones are not
        val pages = com.blaze.assistant.ui.settingsPages.map { it.id }.toSet()
        for (p in SpaceSettings.TREE) assertTrue("page $p", p in pages)
        assertTrue(SpaceSettings.TREE.containsAll(SpaceSettings.REQUIRED))
        for (p in SpaceSettings.REMOVED_FROM_SETTINGS) assertFalse("$p still listed", p in SpaceSettings.TREE)
        // no model or brain picker anywhere in the tree
        assertFalse("ai" in SpaceSettings.TREE); assertFalse("backup_brain" in SpaceSettings.TREE)
    }

    @Test fun onboardingIsWelcomeThenOneSetupScreen() {
        assertEquals(S.WELCOME, SpaceOnboarding.first())
        assertEquals(S.SETUP, SpaceOnboarding.next(S.WELCOME, signedIn = true))
        assertEquals(S.SIGN_IN, SpaceOnboarding.next(S.WELCOME, signedIn = false))
        assertEquals(S.SETUP, SpaceOnboarding.next(S.SIGN_IN, signedIn = false))
        assertNull(SpaceOnboarding.next(S.SETUP, signedIn = true))
        assertEquals("Intelligence beyond the interface", SpaceOnboarding.TAGLINE)
        assertTrue(SpaceOnboarding.show(onboarded = false, signedIn = false, hasMessages = false))
        assertFalse(SpaceOnboarding.show(true, false, false))
        assertFalse(SpaceOnboarding.show(false, true, false))
        assertFalse(SpaceOnboarding.show(false, false, true))
        assertEquals("Continue", SpaceOnboarding.signInButton(true))
        assertEquals("Skip for now", SpaceOnboarding.signInButton(false))
        assertEquals("Start using ARGUS", SpaceOnboarding.setupButton(true))
        assertTrue(SpaceOnboarding.setupButton(false).contains("background"))
        assertEquals("onboarded", SpaceOnboarding.PREF_ONBOARDED)
    }

    @Test fun projectRows() {
        assertEquals(PS.NEEDS_YOU, SpaceProjectRows.status(listOf("a", "b"), busy = setOf("a"), needsYou = setOf("b")))
        assertEquals(PS.WORKING, SpaceProjectRows.status(listOf("a"), setOf("a"), emptySet()))
        assertEquals(PS.IDLE, SpaceProjectRows.status(listOf("a"), setOf("z"), setOf("y")))
        assertEquals(PS.IDLE, SpaceProjectRows.status(emptyList(), setOf("a"), setOf("a")))
        assertEquals("Needs you", SpaceProjectRows.chip(PS.NEEDS_YOU)); assertEquals("Working", SpaceProjectRows.chip(PS.WORKING)); assertEquals("", SpaceProjectRows.chip(PS.IDLE))
        assertEquals(1, SpaceProjectRows.count(listOf("a", "gone"), setOf("a", "b")))
        assertEquals("1 chat", SpaceProjectRows.countLabel(1)); assertEquals("0 chats", SpaceProjectRows.countLabel(0)); assertEquals("3 chats", SpaceProjectRows.countLabel(3))
        assertEquals("N", SpaceProjectRows.initial(" northrise")); assertEquals("#", SpaceProjectRows.initial("  ")); assertEquals("#", SpaceProjectRows.initial("--"))
    }

    @Test fun feedbackCarriesOnlyTheNoteVersionAndPhone() {
        val b = SpaceFeedback.body("  Love it  ", "0.52.3", "Pixel 7", 34)
        assertTrue(b.startsWith("Love it\n\n"))
        assertTrue(b.endsWith("ARGUS 0.52.3 \u00b7 Pixel 7 \u00b7 Android API 34"))
        assertTrue(SpaceFeedback.body("x", "", "", 26).endsWith("ARGUS ? \u00b7 Android \u00b7 Android API 26"))
        assertEquals("ARGUS feedback", SpaceFeedback.SUBJECT)
    }

    @Test fun testTagsAreUniqueAndNamespaced() {
        assertEquals(SpaceTags.FIXED.size, SpaceTags.FIXED.toSet().size)
        for (t in SpaceTags.FIXED) assertTrue(t, t.startsWith("space_"))
        assertEquals("space_tab_home", SpaceTags.tab("home"))
        assertEquals("space_profile_row_about", SpaceTags.profileRow("about"))
        assertEquals("space_setup_row_linux", SpaceTags.setupRow("linux"))
        assertEquals("space_project_row_p1", SpaceTags.projectRow("p1"))
        val prefixes = listOf(SpaceTags.TAB_PREFIX, SpaceTags.PROFILE_ROW_PREFIX, SpaceTags.SETUP_ROW_PREFIX, SpaceTags.PROJECT_ROW_PREFIX)
        for (t in SpaceTags.FIXED) for (p in prefixes) assertFalse("$t collides with $p", t.startsWith(p))
    }

    @Test fun theSpaceImageIsSampledForTheScreen() {
        assertEquals(1, SpaceImage.sample(1080, 2400, lowRam = false))
        assertEquals(2, SpaceImage.sample(1080, 2400, lowRam = true))
        assertEquals(2, SpaceImage.sample(540, 1200, lowRam = false))
        assertEquals(4, SpaceImage.sample(270, 600, lowRam = false))
        assertEquals(1, SpaceImage.sample(1440, 3200, lowRam = false))
        assertEquals(2, SpaceImage.sample(0, 0, lowRam = true))
        // never below the screen
        for (w in listOf(320, 480, 720, 1080)) for (h in listOf(640, 1280, 1600, 2400)) {
            val s = SpaceImage.sample(w, h, lowRam = false)
            assertTrue(SpaceImage.WIDTH / s >= w || s == 1); assertTrue(SpaceImage.HEIGHT / s >= h || s == 1)
        }
    }
}
