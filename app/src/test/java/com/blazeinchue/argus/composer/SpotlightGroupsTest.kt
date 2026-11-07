package com.blazeinchue.argus.composer

import com.blazeinchue.argus.BrandName
import com.blazeinchue.argus.composer.SpotlightGroups.App
import com.blazeinchue.argus.composer.SpotlightGroups.Group
import com.blazeinchue.argus.composer.SpotlightGroups.Said
import com.blazeinchue.argus.composer.SpotlightSearch.Act
import com.blazeinchue.argus.composer.SpotlightSearch.Icon
import com.blazeinchue.argus.composer.SpotlightSearch.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52.2 the ARGUS Home's pull-down Spotlight (composer/SpotlightGroups.kt): results grouped Apps, Actions, Settings,
 * Chats & memory, Web (in that order), the phone's own apps, chat lines, Recent + Suggested before typing, the "!" / "@"
 * command modes kept in one list, and the recent-search store.
 */
class SpotlightGroupsTest {
    private val views = listOf(
        SelfMap.ViewInfo("home", "Home", "The ARGUS orb Home"),
        SelfMap.ViewInfo("browser", "Browser", "${BrandName.NAME}'s own browser and tabs"),
        SelfMap.ViewInfo("music", "Music", "${BrandName.NAME} Music: your songs", listOf("songs", "player")),
        SelfMap.ViewInfo("video", "Video", "${BrandName.NAME} Video: your videos", listOf("videos", "movies")),
        SelfMap.ViewInfo("terminal", "Terminal", "A real terminal: your Alpine Linux or the phone shell", listOf("shell", "console")),
        SelfMap.ViewInfo("look", "Look & feel", "Light / dark and accent", listOf("theme", "dark mode")),
        SelfMap.ViewInfo("settings", "Settings", "Behaviour, voice, privacy, backup"),
    )
    private val map = SelfMap.of(views)
    private val apps = listOf(App("WhatsApp", "com.whatsapp"), App("Spotify", "com.spotify.music"), App("Settings", "com.android.settings"),
        App("Camera", "com.android.camera"), App("Calendar", "com.google.android.calendar"), App("Calculator", "com.android.calculator2"),
        App("Clock", "com.android.deskclock"), App("Contacts", "com.android.contacts"))
    private val said = listOf(Said(1, "Remind me to call mum at six", true), Said(2, "Done. I'll remind you at 18:00 to call mum.", false),
        Said(3, "What's the weather in Ndola tomorrow?", true), Said(4, "Tomorrow in Ndola: sunny, 31 degrees.", false))

    private fun base(q: String) = SpotlightSearch.search(q, map)
    private fun sections(q: String, recent: List<String> = emptyList(), hour: Int = 12) =
        SpotlightGroups.sections(q, base(q), apps, said, recent, hour)
    private val order = listOf(Group.APPS, Group.ACTIONS, Group.SETTINGS, Group.CHATS, Group.WEB)

    // ---------------------------------------------------------------- grouping

    @Test fun rowsLandInTheRightGroup() {
        fun row(id: String, act: Act, icon: Icon = Icon.ACTION) = Row(id, icon, id, "", act, 50)
        assertEquals(Group.APPS, SpotlightGroups.groupOf(row("app:com.whatsapp", Act.RunAction("app:com.whatsapp"))))
        assertEquals(Group.APPS, SpotlightGroups.groupOf(row("view:music:full", Act.OpenView("music"), Icon.MUSIC)))
        assertEquals(Group.APPS, SpotlightGroups.groupOf(row("view:terminal:preview", Act.Preview("terminal"), Icon.TERMINAL)))
        assertEquals(Group.CHATS, SpotlightGroups.groupOf(row("chat:12", Act.RunAction("history"), Icon.CHAT)))
        assertEquals(Group.CHATS, SpotlightGroups.groupOf(row("mem:mum", Act.AskArgus("x"), Icon.SEARCH)))
        assertEquals(Group.WEB, SpotlightGroups.groupOf(row("fb:browse", Act.Browse("x"), Icon.BROWSER)))
        assertEquals(Group.SETTINGS, SpotlightGroups.groupOf(row("setting:voice", Act.OpenView("voice"), Icon.SETTINGS)))
        // the Settings view (an OpenView with the settings icon) is a setting, not an app
        assertEquals(Group.SETTINGS, SpotlightGroups.groupOf(row("view:settings:full", Act.OpenView("settings"), Icon.SETTINGS)))
        assertEquals(Group.ACTIONS, SpotlightGroups.groupOf(row("action:new_chat", Act.RunAction("new_chat"))))
        assertEquals(Group.ACTIONS, SpotlightGroups.groupOf(row("fb:music", Act.MusicSearch("x"), Icon.MUSIC)))
        assertEquals(Group.ACTIONS, SpotlightGroups.groupOf(row("fb:ask", Act.AskArgus("x"), Icon.CHAT)))
    }

    @Test fun groupsComeInOrderBestFirstAndCapped() {
        val rows = (1..9).map { Row("action:$it", Icon.ACTION, "A$it", "", Act.RunAction("a$it"), it) } +
            Row("fb:browse", Icon.BROWSER, "Browse", "", Act.Browse("q"), -1) +
            Row("setting:voice", Icon.SETTINGS, "Voice", "", Act.OpenView("voice"), 70) +
            (1..9).map { Row("app:p$it", Icon.ACTION, "P$it", "", Act.RunAction("app:p$it"), 40 + it) } +
            Row("action:1", Icon.ACTION, "duplicate id", "", Act.RunAction("dup"), 99)
        val s = SpotlightGroups.group(rows)
        assertEquals(listOf(Group.APPS, Group.ACTIONS, Group.SETTINGS, Group.WEB), s.map { it.group })
        val actions = s.first { it.group == Group.ACTIONS }.rows
        assertEquals(SpotlightGroups.PER_GROUP_MAX, actions.size)
        assertEquals(listOf(9, 8, 7, 6, 5), actions.map { it.score })
        assertTrue("first of a repeated id wins", actions.none { it.title == "duplicate id" })
        assertEquals(SpotlightGroups.APPS_MAX + 2, s.first { it.group == Group.APPS }.rows.size)
        assertTrue(s.all { it.rows.isNotEmpty() })
        assertTrue(SpotlightGroups.group(emptyList()).isEmpty())
    }

    // ---------------------------------------------------------------- before typing

    @Test fun emptyQueryShowsRecentThenSuggestedThenTheCuratedRows() {
        val s = sections("", recent = listOf("weather", "spotify"), hour = 8)
        assertEquals(Group.RECENT, s[0].group)
        assertEquals(listOf("weather", "spotify"), s[0].rows.map { it.title })
        assertTrue(s[0].rows.all { it.act == Act.Complete(it.title) })
        assertEquals(Group.SUGGESTED, s[1].group)
        assertEquals(SpotlightGroups.suggested(8), s[1].rows.map { it.title })
        assertTrue(s[1].rows.all { it.act is Act.AskArgus })
        // then the curated list, grouped: Chat with ARGUS (Actions), Music / Video / Browser / Terminal (Apps), Settings
        val rest = s.drop(2).map { it.group }
        assertTrue(rest.isNotEmpty())
        assertEquals(order.filter { it in rest }, rest)
        assertTrue(s.drop(2).flatMap { it.rows }.any { it.id == "action:chat" })
    }

    @Test fun noRecentNoRecentSection() {
        val s = sections("")
        assertEquals(Group.SUGGESTED, s[0].group)
        assertTrue(s.none { it.group == Group.RECENT })
        assertTrue(sections("", recent = (1..10).map { "q$it" })[0].rows.size <= SpotlightGroups.PER_GROUP_MAX)
    }

    @Test fun suggestionsFitTheTimeOfDay() {
        val morning = SpotlightGroups.suggested(8); val noon = SpotlightGroups.suggested(13)
        val evening = SpotlightGroups.suggested(19); val night = SpotlightGroups.suggested(2)
        assertTrue("Show my schedule" in morning)
        assertTrue(listOf(morning, noon, evening, night).toSet().size == 4)
        for (h in -24..48) assertEquals(3, SpotlightGroups.suggested(h).size)
        assertEquals(SpotlightGroups.suggested(1), SpotlightGroups.suggested(25))
        assertEquals(SpotlightGroups.suggested(23), SpotlightGroups.suggested(-1))
        assertTrue(SpotlightGroups.suggestedRows(8).all { it.id.startsWith("sug:") && it.subtitle == "Ask ${BrandName.NAME}" })
    }

    // ---------------------------------------------------------------- typing

    @Test fun phoneAppsAreFuzzyFoundAndOpenTheApp() {
        val r = SpotlightGroups.appRows("whats", apps)
        assertEquals("WhatsApp", r.first().title)
        assertEquals("app:com.whatsapp", r.first().id)
        assertEquals("com.whatsapp", SpotlightGroups.appPackage(r.first().act))
        // one typo still finds it
        assertEquals("Spotify", SpotlightGroups.appRows("spotfy", apps).firstOrNull()?.title)
        // never more than APPS_MAX; nothing for a blank query; nothing for a miss
        assertTrue(SpotlightGroups.appRows("c", apps).size <= SpotlightGroups.APPS_MAX)
        assertTrue(SpotlightGroups.appRows("  ", apps).isEmpty())
        assertTrue(SpotlightGroups.appRows("zzzzqq", apps).isEmpty())
        // shorter labels first on a tie ("Clock" before "Calculator" / "Calendar" / "Camera"... for "c")
        val c = SpotlightGroups.appRows("c", apps).map { it.title }
        assertEquals(c.sortedWith(compareBy { it.length }).first().length, c.first().length)
    }

    @Test fun appPackageOnlyForAppRows() {
        assertEquals("com.x", SpotlightGroups.appPackage(Act.RunAction("app:com.x")))
        assertNull(SpotlightGroups.appPackage(Act.RunAction("app:")))
        assertNull(SpotlightGroups.appPackage(Act.RunAction("history")))
        assertNull(SpotlightGroups.appPackage(Act.OpenView("music")))
    }

    @Test fun chatLinesAreFoundNewestFirstPlusMemorySearch() {
        val r = SpotlightGroups.chatRows("mum", said)
        assertEquals("chat:2", r.first().id)                        // newest first
        assertEquals("chat:1", r[1].id)
        assertTrue(r[0].subtitle.startsWith("${BrandName.NAME} said"))
        assertTrue(r[1].subtitle.startsWith("You said"))
        assertTrue(r.dropLast(1).all { it.act == Act.RunAction("history") })
        val mem = r.last()
        assertEquals("mem:mum", mem.id)
        assertEquals(Act.AskArgus("Search your memory for: mum"), mem.act)
        assertTrue(r.size <= SpotlightGroups.CHATS_MAX + 1)
        // one letter: nothing (too noisy)
        assertTrue(SpotlightGroups.chatRows("m", said).isEmpty())
        assertEquals(listOf("mem:qqzz"), SpotlightGroups.chatRows("qqzz", said).map { it.id })
    }

    @Test fun snippetCentresOnTheMatch() {
        assertEquals("short text", SpotlightGroups.snippet("short   text", "text"))
        val long = "a ".repeat(60) + "needle in the haystack " + "b ".repeat(60)
        val s = SpotlightGroups.snippet(long, "needle")
        assertTrue(s.contains("needle"))
        assertTrue(s.startsWith("\u2026") && s.endsWith("\u2026"))
        assertTrue(s.length <= 60 + 2)
        // no match: from the start
        assertTrue(SpotlightGroups.snippet(long, "zzz").startsWith("a a"))
    }

    @Test fun typedQueryGroupsAppsActionsSettingsChatsWeb() {
        val s = sections("set")
        val groups = s.map { it.group }
        assertEquals(order.filter { it in groups }, groups)
        assertTrue(Group.APPS in groups)                             // the phone's Settings app
        assertTrue(Group.SETTINGS in groups)                         // ARGUS's own settings
        assertTrue(Group.WEB in groups)                              // "Browse 'set'" fallback, always there
        assertTrue(Group.CHATS in groups)                            // "Search my memory for 'set'"
        assertTrue(s.first { it.group == Group.APPS }.rows.any { it.id == "app:com.android.settings" })
    }

    @Test fun enterRunsTheFirstRowOfTheFirstGroup() {
        val s = sections("whatsapp")
        val top = SpotlightGroups.top(s)
        assertNotNull(top)
        assertEquals("app:com.whatsapp", top!!.id)
        assertNull(SpotlightGroups.top(emptyList()))
        assertEquals("x", SpotlightGroups.top(listOf(SpotlightGroups.Section(Group.APPS, emptyList()),
            SpotlightGroups.Section(Group.WEB, listOf(Row("x", Icon.BROWSER, "x", "", Act.Browse("x"))))))?.id)
    }

    @Test fun commandModesKeepTheirOwnOrderInOneList() {
        val term = sections("!ls -la")
        assertEquals(1, term.size)
        assertEquals(Group.ACTIONS, term[0].group)
        assertEquals(base("!ls -la").rows, term[0].rows)
        val at = sections("@mus")
        assertEquals(1, at.size)
        assertEquals(base("@mus").rows, at[0].rows)
        // no phone apps or chat lines mixed into a command line
        assertTrue(term[0].rows.none { it.id.startsWith("app:") || it.id.startsWith("chat:") })
    }

    @Test fun neverEmptyWhileTyping() {
        for (q in listOf("a", "zzqqxx", "weather tomorrow", "spotify", "dark mode")) {
            val s = sections(q)
            assertTrue("'$q' has results", s.isNotEmpty() && s.all { it.rows.isNotEmpty() })
            assertTrue("'$q' offers the web", s.any { it.group == Group.WEB })
        }
    }

    // ---------------------------------------------------------------- recent searches

    @Test fun recentsMoveUpNeverRepeatAndSkipJunk() {
        var l = emptyList<String>()
        l = SpotlightRecents.push(l, "weather")
        l = SpotlightRecents.push(l, "spotify")
        l = SpotlightRecents.push(l, "  Weather ")
        assertEquals(listOf("Weather", "spotify"), l)
        assertEquals(l, SpotlightRecents.push(l, "   "))
        assertEquals(l, SpotlightRecents.push(l, "!rm -rf /tmp/x"))
        assertEquals(l, SpotlightRecents.push(l, "x".repeat(81)))
        assertEquals("a b", SpotlightRecents.push(emptyList(), "a\nb").first())
        for (i in 1..20) l = SpotlightRecents.push(l, "q$i")
        assertEquals(SpotlightRecents.MAX, l.size)
        assertEquals("q20", l.first())
    }

    @Test fun recentsRoundTripThroughThePref() {
        val l = listOf("weather", "spotify", "dark mode")
        assertEquals(l, SpotlightRecents.decode(SpotlightRecents.encode(l)))
        assertTrue(SpotlightRecents.decode(null).isEmpty())
        assertTrue(SpotlightRecents.decode("").isEmpty())
        assertEquals(listOf("a", "b"), SpotlightRecents.decode("a\n\n  \nb\n"))
        assertEquals(SpotlightRecents.MAX, SpotlightRecents.decode((1..20).joinToString("\n") { "q$it" }).size)
        assertEquals("argus_spot_recent", SpotlightRecents.PREF)
    }
}
