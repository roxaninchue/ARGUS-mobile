package com.blaze.assistant.ui.team

import com.blaze.assistant.agent.ScheduleDay
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.icons.LucideSpec
import com.blaze.assistant.ui.space.SpaceAnswer
import com.blaze.assistant.ui.space.SpaceHomeCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P1-SH-05 Life mode v1: the page's rules (LifeModel) on the JVM. Day line words, the Today strip's window, dots and now
 * mark from real [ScheduleDay] items, the ten domain tiles ("Set up my life team" until P5), the cached weather panel,
 * section order with the AM Home's sign-in / setup cards, caps (3 projects, 3 asks), and "SpaceHome cards still
 * reachable": every card the 0.52 Home can show has a place in Life for the same inputs, and the 0.52 Home stays wired.
 */
class LifeModelTest {
    private val zone = ZoneId.of("Africa/Lusaka")
    private val thu = LocalDate.of(2026, 11, 12)

    private fun item(title: String, h: Int, m: Int = 0, kind: String = "reminder", toH: Int? = null) =
        ScheduleDay.Item(title, kind, LocalTime.of(h, m), toH?.let { LocalTime.of(it, 0) })

    private val day = listOf(item("Gym", 7), item("Call Mum", 13, 30, kind = "event", toH = 14), item("Pay ZESCO bill", 18))

    @Test fun dayLineCountsTheDaysThings() {
        assertEquals("Thursday \u2014 nothing on today.", LifeModel.dayLine(thu, emptyList(), LocalTime.of(9, 0)))
        assertEquals("Thursday \u2014 one thing today.", LifeModel.dayLine(thu, listOf(item("Gym", 17)), LocalTime.of(9, 0)))
        assertEquals("Thursday \u2014 three things today.", LifeModel.dayLine(thu, day, LocalTime.of(6, 30)))
        assertEquals("Thursday \u2014 three things today, two still ahead.", LifeModel.dayLine(thu, day, LocalTime.of(9, 0)))
        assertEquals("Thursday \u2014 nothing left today.", LifeModel.dayLine(thu, day, LocalTime.of(22, 0)))
        // no clock (a screenshot) = nothing has passed
        assertEquals("Thursday \u2014 three things today.", LifeModel.dayLine(thu, day, null))
        assertEquals("Monday \u2014 13 things today.", LifeModel.dayLine(LocalDate.of(2026, 11, 16), List(13) { item("x$it", 23) }, LocalTime.of(1, 0)))
    }

    @Test fun repeatingShellJobsAreNotThingsToDo() {
        val every = ScheduleDay.itemsOn(thu, zone, listOf(ScheduleDay.Entry("Backup", "reminder", ScheduleDay.Rule.Every(30))))
        assertEquals(1, every.size)
        assertEquals("Thursday \u2014 nothing on today.", LifeModel.dayLine(thu, every, LocalTime.of(9, 0)))
        assertTrue(LifeModel.strip(every, LocalTime.of(9, 0)).empty)
    }

    @Test fun todayStripRunsSixToMidnightWithDotsAndNow() {
        val s = LifeModel.strip(day, LocalTime.of(12, 0))
        assertEquals(6, s.startHour)
        assertEquals(listOf(6, 12, 18, 24), s.ticks)
        assertEquals(0.5f, s.tickAt(15), 1e-6f)
        assertEquals(listOf("07:00", "13:30\u201314:00", "18:00"), s.timed.map { it.time })
        assertEquals(1f / 18f, s.timed[0].at!!, 1e-4f)
        assertEquals(2f / 3f, s.timed[2].at!!, 1e-4f)
        assertEquals(listOf(true, false, false), s.timed.map { it.passed })
        assertEquals(1f / 3f, s.now!!, 1e-4f)
        // before the window starts there is no now mark
        assertNull(LifeModel.strip(day, LocalTime.of(5, 0)).now)
        assertNull(LifeModel.strip(day, null).now)
    }

    @Test fun anEarlyItemOpensTheStripAtMidnight() {
        val s = LifeModel.strip(listOf(item("Flight", 4, 40, kind = "event"), item("Gym", 18)), LocalTime.of(3, 0))
        assertEquals(0, s.startHour)
        assertEquals(listOf(0, 6, 12, 18, 24), s.ticks)
        assertEquals((4 * 60 + 40) / 1440f, s.timed[0].at!!, 1e-4f)
        assertEquals(3f / 24f, s.now!!, 1e-4f)
    }

    @Test fun allDayItemsAndCalendarSpansComeFromScheduleDay() {
        val b = ZonedDateTime.of(2026, 11, 12, 0, 0, 0, 0, zone).toInstant()
        val entries = listOf(
            ScheduleDay.Entry("Mum's birthday", "event", ScheduleDay.Rule.Span(b, b.plusSeconds(86_400), allDay = true)),
            ScheduleDay.Entry("Standup", "event", ScheduleDay.Rule.Span(Instant.parse("2026-11-12T07:00:00Z"), Instant.parse("2026-11-12T07:15:00Z"))),
            ScheduleDay.Entry("Morning brief", "reminder", ScheduleDay.Rule.Weekly(ScheduleDay.WEEKDAYS, LocalTime.of(7, 0))),
            ScheduleDay.Entry("Off routine", "reminder", ScheduleDay.Rule.Weekly(ScheduleDay.EVERY_DAY, LocalTime.of(8, 0)), enabled = false),
        )
        val items = ScheduleDay.itemsOn(thu, zone, entries)
        val s = LifeModel.strip(items, LocalTime.of(10, 0))
        assertEquals(listOf("Mum's birthday"), s.allDay.map { it.title })
        assertNull(s.allDay[0].time)
        assertEquals(listOf("07:00 Morning brief", "09:00\u201309:15 Standup"), s.timed.map { "${it.time} ${it.title}" })
        assertEquals("Thursday \u2014 three things today, one still ahead.", LifeModel.dayLine(thu, items, LocalTime.of(10, 0)))
    }

    @Test fun tenDomainTilesSayToSetUpTheLifeTeam() {
        val d = LifeModel.domains()
        assertEquals(listOf("health", "money", "work", "home", "people", "learning", "travel", "create", "mind", "phone"), d.map { it.key })
        assertEquals("Health", d[0].label)
        for (t in d) {
            assertEquals(LifeModel.SET_UP, t.line)
            assertEquals(ArgusTokens.DOMAIN_COLORS[t.key], t.color)
            assertEquals(LucideSpec.DOMAIN[t.key], t.glyph)
        }
        assertEquals("Set up my life team", LifeModel.setUpAsk(null))
        assertEquals("Set up my life team, starting with Money", LifeModel.setUpAsk(d[1]))
    }

    @Test fun weatherPanelOnlyFromAFreshReading() {
        val now = 1_000_000_000_000L
        val w = LifeModel.weather("24\u00b0C", "Clear \u00b7 feels 26\u00b0 \u00b7 wind 9 km/h \u00b7 Open-Meteo \u00b7 10:00", "Lusaka", now - 60_000, now)
        assertNotNull(w)
        assertEquals("24\u00b0C", w!!.value)
        assertEquals("Clear \u00b7 Lusaka", w.line)
        assertNull("never fetched", LifeModel.weather("24\u00b0C", "", "Lusaka", 0, now))
        assertNull("too old", LifeModel.weather("24\u00b0C", "", "Lusaka", now - LifeModel.WEATHER_MAX_AGE_MS - 1, now))
        assertNull("blank", LifeModel.weather(" ", "Clear", "Lusaka", now, now))
        assertEquals("no place", "Rain", LifeModel.weather("18\u00b0C", "Rain", "", now, now)!!.line)
    }

    @Test fun sectionsInOrderWithSignInAndSetupWhenNeeded() {
        val S = LifeModel.Section.entries
        // owner 2026-11-14: the board (only when it has panels) and the calendar come first
        assertEquals(listOf(S[0], S[4], S[5], S[6], S[7], S[8]), LifeModel.sections(signedIn = true, setupLeft = 0))
        assertEquals(S, LifeModel.sections(signedIn = false, setupLeft = 2, board = 1))
        assertEquals(LifeModel.Section.BOARD, LifeModel.sections(true, 0, board = 3)[1])
        assertEquals(LifeModel.Section.DAY_LINE, LifeModel.sections(true, 0).first())
        assertEquals(LifeModel.Section.PANELS, LifeModel.sections(true, 0).last())
    }

    @Test fun stateCapsProjectsAndAsks() {
        val p = (1..5).map { "p$it" to "Project $it" }
        val s = LifeModel.state(thu, LocalTime.of(9, 0), day, projects = p, setupLeft = -1, setupPercent = 140)
        assertEquals(SpaceHomeCards.MAX_PROJECTS, s.projects.size)
        assertEquals("p1", s.projects[0].first)
        assertTrue(s.asks.size <= LifeModel.MAX_ASKS && s.asks.isNotEmpty())
        assertEquals(0, s.setupLeft)
        assertEquals(100, s.setupPercent)
        assertEquals(10, s.domains.size)
    }

    /** 06 row 5 acceptance: SpaceHome cards still reachable. */
    @Test fun everySpaceHomeCardHasAPlaceInLife() {
        assertEquals(SpaceHomeCards.Card.entries.toSet(), LifeModel.HOME_CARDS.keys)
        for (signedIn in listOf(true, false)) for (answer in listOf(true, false)) for (projects in listOf(0, 2, 5)) for (setup in listOf(0, 3)) {
            val home = SpaceHomeCards.plan(SpaceHomeCards.Input(signedIn, answer, projects, setup))
            val life = LifeModel.state(thu, LocalTime.of(9, 0), day, projects = (1..projects).map { "p$it" to "P$it" },
                answer = if (answer) SpaceAnswer.Card("ask", "reply", false) else null, signedIn = signedIn, setupLeft = setup)
            for (card in home) assertTrue("$card ($signedIn/$answer/$projects/$setup)", LifeModel.HOME_CARDS.getValue(card) in life.sections)
            assertEquals(answer, life.answer != null)
            assertEquals(minOf(projects, SpaceHomeCards.MAX_PROJECTS), life.projects.size)
        }
    }

    @Test fun theShellShowsLifeAndKeepsThe052Home() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val chat = File(root, "com/blaze/assistant/ui/ChatScreen.kt").readText()
        assertTrue("Life's page is LifePage", "life = { lifePane() }" in chat && "com.blaze.assistant.ui.team.LifePage(" in chat)
        assertTrue("the 0.52 Home stays for argus_look off", "if (paneNow == \"home\") {\n                    homePane()" in chat && "SpaceHomeLayer(" in chat)
        assertTrue("the Today strip opens Schedule", "onOpenSchedule = { Views.find(\"schedule\")?.let { openView(it) } }" in chat)
        val life = File(root, "com/blaze/assistant/ui/team/LifeMode.kt").readText()
        assertTrue("Life reads the same day items as the [context] line", "TurnContextSource.dayEntries(now)" in life && "ScheduleDay.itemsOn(" in life)
        assertFalse("Life never fetches weather itself", "HomeSources" in life || "refresh(" in life)
    }

    @Test fun monthCalendarGrid() {
        val nov = java.time.YearMonth.of(2026, 11)               // 1 Nov 2026 is a Sunday
        val w = LifeCalendar.weeks(nov)
        assertTrue(w.all { it.size == 7 })
        assertEquals(6, w.size)
        assertEquals(List(6) { null } + LocalDate.of(2026, 11, 1), w[0])
        assertEquals(30, w.flatten().count { it != null })
        assertEquals(LocalDate.of(2026, 11, 30), w.last().first())
        assertEquals("November 2026", LifeCalendar.title(nov, java.util.Locale.UK))
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), LifeCalendar.heads(locale = java.util.Locale.UK))
        val d = LocalDate.of(2026, 11, 14)
        assertEquals("Today", LifeCalendar.dayLabel(d, d)); assertEquals("Tomorrow", LifeCalendar.dayLabel(d.plusDays(1), d))
        assertEquals("Sat 21 Nov", LifeCalendar.dayLabel(d.plusDays(7), d, java.util.Locale.UK))
        assertEquals(4, LifeCalendar.weeks(java.time.YearMonth.of(2027, 2)).size)
    }

    @Test fun boardNewestFirstWithoutWeatherOrHidden() {
        fun w(id: String, type: String, order: Int, created: Long, hidden: Boolean = false) =
            com.blaze.assistant.home.Widget(id, type, id, "x", order = order, created = created, hidden = hidden)
        val out = LifeBoard.panels(listOf(w("old", "news", 1, 10), w("new", "todo", 0, 20), w("sky", "weather", 0, 30), w("gone", "custom", 0, 40, hidden = true)))
        assertEquals(listOf("new", "old"), out.map { it.id })
    }

    @Test fun boardUnderHeadersNewestHeaderFirstAndFoldsSurvive() {
        fun w(id: String, cat: String, order: Int) = com.blaze.assistant.home.Widget(id, "custom", id, cat, order = order, created = 100L - order)
        val g = LifeBoard.groups(listOf(w("bills", "Money", 2), w("milk", "To-do", 0), w("rent", "Money", 1), w("x", " ", 3)))
        assertEquals(listOf("To-do", "Money", "Notes"), g.map { it.first })
        assertEquals(listOf("rent", "bills"), g[1].second.map { it.id })
        val f = LifeBoard.toggle(LifeBoard.toggle(emptySet(), "Money"), LifeBoard.SECTION + "Projects")
        assertEquals(f, LifeBoard.folded(LifeBoard.save(f)))
        assertEquals(setOf(LifeBoard.SECTION + "Projects"), LifeBoard.toggle(f, "Money"))
        assertTrue(LifeBoard.folded("\n \n").isEmpty())
    }

    /** Owner 2026-11-14: quick access to accounts and apps, folding headers, the board and the calendar wired on Life. */
    @Test fun lifeWiresTheBoardCalendarFoldsAndQuickAccess() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val chat = File(root, "com/blaze/assistant/ui/ChatScreen.kt").readText()
        assertTrue("onConnect = { settingsPage = \"connections\" }" in chat && "onApps = { composer.openSpotlight() }" in chat)
        val life = File(root, "com/blaze/assistant/ui/team/LifeMode.kt").readText()
        assertTrue("LifeBoard.groups(widgets)" in life && "LifeCalendarCard(" in life && "LifeQuickAccess(" in life)
        for (h in listOf("Today", "Your life", "Projects", "Panels", "Calendar")) assertTrue(h, "LifeFoldHead(\"$h\"" in life)
        val tools = File(root, "com/blaze/assistant/home/HomeTools.kt").readText()
        assertTrue("newest on top", "atEnd = false" in tools)
        assertTrue("the model keeps headers few", "Keep headers few" in tools)
    }
}
