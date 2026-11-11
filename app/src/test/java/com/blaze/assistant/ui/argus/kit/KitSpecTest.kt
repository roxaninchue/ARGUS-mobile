package com.blaze.assistant.ui.argus.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** P1-SH-02: the pure kit rules (KitSpec.kt). Plain JVM. */
class KitSpecTest {
    private val zone = ZoneId.of("Africa/Lusaka")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun textPairsPassAaOnBothGrounds() {
        for (dark in listOf(true, false)) {
            val t = KitPalette.of(dark)
            fun aa(fg: Long, bg: Long, what: String, min: Double = 4.5) =
                assertTrue("$what ${if (dark) "dark" else "light"} = ${KitPalette.ratio(fg, bg)}", KitPalette.ratio(fg, bg) >= min)
            aa(t.ink, t.bg, "ink on bg")
            aa(t.mute, t.bg, "mute on bg")
            aa(t.onInk, t.ink, "onInk on ink")
            aa(t.dangerText, t.bg, "danger text")
            aa(t.successText, t.bg, "success text")
            aa(t.waitingText, t.bg, "waiting text")
            // text over a glass card (the card laid over the ground) keeps AA too
            val card = KitPalette.over(t.glassFill2, t.bg)
            aa(t.ink, card, "ink on glass")
            aa(t.mute, card, "mute on glass")
            // the sheet (popups, mention list)
            val sheet = KitPalette.over(t.sheet, t.bg)
            aa(t.ink, sheet, "ink on sheet"); aa(t.mute, sheet, "mute on sheet")
        }
    }

    @Test fun lightMuteIsTheAaFix() {
        // 04 §5.2: A-team's #7C7C84 fails on the light ground; #64646C passes.
        assertTrue(KitPalette.ratio(0xFF7C7C84, KitPalette.LIGHT.bg) < 4.5)
        assertEquals(0xFF64646C, KitPalette.LIGHT.mute)
    }

    @Test fun translucentValuesCarryAlpha() {
        assertEquals(0x12, ((KitPalette.DARK.soft shr 24) and 0xFF).toInt()) // white 7 %
        assertEquals(0xFFFFFF, (KitPalette.DARK.soft and 0xFFFFFF).toInt())
        assertEquals(0xFF0B0B0D, KitPalette.over(0x00FFFFFF, 0xFF0B0B0D))
        assertEquals(8, KitPalette.BOT_COLORS.size)
    }

    @Test fun touchTargetsNeverUnder48() {
        assertEquals(48, KitSize.target(38))
        assertEquals(48, KitSize.target(21))
        assertEquals(58, KitSize.target(58))
        assertTrue(ButtonSpec.Size.S.heightDp < 48) // visual; the component pads its hit area to KitSize.TOUCH
    }

    @Test fun needYouBanner() {
        assertNull(NeedYou.of(0, 0, listOf("Nova")))
        val b = NeedYou.of(2, 1, listOf("Ledger", "Atlas", "Ledger", "Vita"))!!
        assertEquals("3 need you", b.title)
        assertEquals("Ledger, Atlas +1", b.names)
        assertTrue(!b.amber)
        assertEquals("3 need you: Ledger, Atlas +1. Review", b.spoken)
        val a = NeedYou.of(1, 0, listOf("Atlas"))!!
        assertTrue(a.amber); assertEquals("Atlas", a.names)
        assertEquals("1 need you. Review", NeedYou.of(1, 0, emptyList())!!.spoken)
    }

    @Test fun rowPreviewAndSpeech() {
        val p = RowSpec.preview("hi", overdue = 1, firstOverdue = "Pay ZESCO bill")
        assertEquals("1 overdue · Pay ZESCO bill", p.text); assertEquals(RowSpec.Tone.DANGER, p.tone); assertTrue(p.bold)
        assertEquals(RowSpec.Tone.SUCCESS, RowSpec.preview("", allClear = true).tone)
        assertEquals("Booked it. Two ideas", RowSpec.preview("Booked it.\n\n  Two   ideas").text)
        assertEquals("Ledger, Money, 1 overdue: Pay ZESCO bill, 9:12, unread",
            RowSpec.spoken("Ledger", "Money", p.text, "9:12", unread = true))
        assertEquals("ARGUS, Manager, lead, Booked it, 9:41", RowSpec.spoken("ARGUS", "Manager", "Booked it", "9:41", false, lead = true))
        assertEquals("Coder, working", RowSpec.spoken("Coder", "", "", "", false, working = true))
    }

    @Test fun rowTimes() {
        val now = ms(2026, 11, 11, 22, 30)
        assertEquals("9:41", RowSpec.time(ms(2026, 11, 11, 9, 41), now, zone, Locale.UK))
        assertEquals("Yesterday", RowSpec.time(ms(2026, 11, 10, 23, 59), now, zone, Locale.UK))
        assertEquals("Mon", RowSpec.time(ms(2026, 11, 9, 8, 0), now, zone, Locale.UK))
        assertEquals("2 Nov", RowSpec.time(ms(2026, 11, 2, 8, 0), now, zone, Locale.UK))
        assertEquals("2 Nov 2025", RowSpec.time(ms(2025, 11, 2, 8, 0), now, zone, Locale.UK))
        assertEquals("", RowSpec.time(0L, now, zone))
        assertEquals("22:31", RowSpec.time(now + 60_000, now, zone)) // a clock a minute ahead still reads as today
    }

    @Test fun pinsAndChips() {
        assertEquals(listOf(1, 2, 3, 4), Pins.visible(listOf(1, 2, 3, 4, 5)))
        assertEquals("Inbox 3", Chips.label("Inbox", 3))
        assertEquals("Jobs", Chips.label("Jobs", 0))
        assertEquals("Inbox, 3, selected", Chips.spoken("Inbox", 3, true))
    }

    @Test fun mentionTrigger() {
        assertEquals(Mention.Query(Mention.Kind.BOT, "at", 3), Mention.at("hi @at"))
        assertEquals(Mention.Query(Mention.Kind.BOT, "", 0), Mention.at("@"))
        assertEquals(Mention.Query(Mention.Kind.COMMAND, "te", 0), Mention.at("/te"))
        assertNull(Mention.at("go /te"))          // slash only at the start
        assertNull(Mention.at("1/2"))
        assertNull(Mention.at("mail me@x.com"))   // @ not at a word start
        assertEquals(Mention.Query(Mention.Kind.FILE, "deck", 5), Mention.at("send #deck"))
        assertNull(Mention.at("hi @at ", 7))      // cursor after the space: done typing
        assertEquals(Mention.Query(Mention.Kind.BOT, "a", 0), Mention.at("@at hi", 2))
        assertNull(Mention.at(""))
    }

    @Test fun mentionFilterAndApply() {
        val bots = listOf("Atlas", "Ledger", "Chief of Staff", "Pantry", "Tutor")
        assertEquals(listOf("Atlas", "Chief of Staff", "Pantry"), Mention.filter(bots, "a") { it })
        assertEquals(listOf("Atlas"), Mention.filter(bots, "atl") { it })
        assertEquals(listOf("Chief of Staff"), Mention.filter(bots, "sta") { it })
        assertEquals(listOf("Tutor"), Mention.filter(bots, "tu") { it })
        assertEquals(6, Mention.filter((1..10).map { "b$it" }, "") { it }.size)
        val q = Mention.at("ask @at")!!
        assertEquals("ask @Atlas " to 11, Mention.apply("ask @at", q, "Atlas"))
        val q2 = Mention.at("@le please", 3)!!
        assertEquals("@Ledger  please" to 8, Mention.apply("@le please", q2, "Ledger", 3))
    }

    @Test fun mentionRankingOrder() {
        val r = Mention.filter(listOf("Pantry", "Atlas", "Data atlas"), "atl") { it }
        assertEquals(listOf("Atlas", "Data atlas"), r)
        val r2 = Mention.filter(listOf("Scout", "Tutor", "Studio"), "t") { it }
        assertEquals("Tutor", r2.first())
        assertEquals(setOf("Scout", "Studio"), r2.drop(1).toSet())
    }

    @Test fun attachNames() {
        assertEquals("deck.pdf", Attach.shortName("deck.pdf"))
        val s = Attach.shortName("holiday-photo-from-the-beach.jpeg")
        assertTrue(s, s.endsWith("jpeg") && s.contains("\u2026") && s.length <= 14)
        val t = Attach.shortName("averyveryverylongname")
        assertTrue(t, t.endsWith("\u2026") && t.length <= 14)
    }

    @Test fun progressAndCloud() {
        assertNull(Progress.fraction(1, 0))
        assertEquals(0.5f, Progress.fraction(5, 10))
        assertEquals(1f, Progress.fraction(50, 10))
        assertEquals("50%", Progress.percent(0.5f)); assertEquals("", Progress.percent(null))
        assertTrue(!CloudChipRule.visible(running = true))           // hidden until P7
        assertTrue(CloudChipRule.visible(running = true, enabled = true))
        assertEquals("Cloud on · $0.055/h", CloudChipRule.label(0.055))
        assertEquals("Cloud on", CloudChipRule.label(null))
    }
}
