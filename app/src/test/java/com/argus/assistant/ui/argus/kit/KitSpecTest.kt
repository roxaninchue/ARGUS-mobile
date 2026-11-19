package com.blaze.assistant.ui.argus.kit

import com.blaze.assistant.ui.argus.ArgusContrast
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.Ground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-SH-02: the kit's pure rules (KitSpec.kt) on the JVM: sizes, labels, mention parsing and the kit's AA colours. */
class KitSpecTest {

    @Test fun touchTargetsAreAtLeast48() {
        assertEquals(48, KitSpec.TARGET)
        for (v in listOf(KitSpec.IconButton.VISUAL, ButtonSize.S.heightDp, ButtonSize.M.heightDp, KitSpec.Mention.ROW, KitSpec.Chips.HEIGHT, KitSpec.Cloud.HEIGHT, 21))
            assertTrue("$v", KitSpec.target(v) >= 48)
        assertEquals(72, KitSpec.target(72))
        // Rows and fields are tall enough on their own.
        for (h in listOf(KitSpec.Row.MIN_HEIGHT, KitSpec.Menu.MIN_HEIGHT, KitSpec.Settings.MIN_HEIGHT, KitSpec.Field.HEIGHT, KitSpec.Banner.MIN_HEIGHT, KitSpec.Sheet.ACTION_ROW))
            assertTrue("$h", h >= 48)
        // 04 §2.8 visual sizes
        assertEquals(50, KitSpec.Row.ORB); assertEquals(10, KitSpec.Row.UNREAD_DOT); assertEquals(13, KitSpec.Pins.OVERDUE_DOT)
        assertEquals(44, KitSpec.Menu.DISC); assertEquals(52, KitSpec.Attachment.TILE); assertEquals(40, KitSpec.Attachment.THUMB)
        assertEquals(44, KitSpec.Progress.BAR_W); assertEquals(4, KitSpec.Progress.BAR_H); assertEquals(38, KitSpec.IconButton.VISUAL)
    }

    @Test fun needYouBanner() {
        assertEquals(NeedYou.Variant.HIDDEN, NeedYou.variant(0, false))
        assertEquals(NeedYou.Variant.HIDDEN, NeedYou.variant(-1, true))
        assertEquals(NeedYou.Variant.INK, NeedYou.variant(3, false))
        assertEquals(NeedYou.Variant.AMBER, NeedYou.variant(2, true))
        assertEquals("3 need you · Nova, Atlas", NeedYou.line(3, listOf("Nova", "Atlas", "Nova")))
        assertEquals("1 needs you · Nova", NeedYou.line(1, listOf("Nova")))
        assertEquals("5 need you · Nova, Atlas +2", NeedYou.line(5, listOf("Nova", "Atlas", "Ledger", " ", "Tempo")))
        assertEquals("2 need you", NeedYou.line(2, emptyList()))
        assertEquals("3 need you, Nova, Atlas. Review", KitA11y.banner(3, listOf("Nova", "Atlas")))
    }

    @Test fun botRowRulesAndDescription() {
        val m = BotRowModel("nova", "Nova", "  Sent the deck\n to Alex ", "10:42", unread = 2, status = "Working")
        assertTrue(BotRow.showDot(m))
        assertFalse(BotRow.showDot(m.copy(muted = true)))
        assertFalse(BotRow.showDot(m.copy(unread = 0)))
        assertEquals("Sent the deck to Alex", BotRow.previewLine(m.preview))
        assertEquals("Nova, Working, 2 unread, Sent the deck to Alex, 10:42", KitA11y.botRow(m))
        assertEquals("ARGUS, Lead, 1 unread, Pinned, All clear", KitA11y.botRow(BotRowModel("manager", "ARGUS", "All clear", unread = 1, manager = true, pinned = true)))
        assertEquals("Ledger, Muted", KitA11y.botRow(BotRowModel("l", "Ledger", " ", unread = 4, muted = true)))
    }

    @Test fun pins() {
        val p = listOf("a", "b", "c", "d", "e").map { PinModel(it, it.uppercase()) }
        assertEquals(4, Pins.visible(p + p).size)
        assertEquals(listOf("A", "B", "C", "D"), Pins.visible(p).map { it.name })
        assertEquals("Nova", Pins.label("Nova Research"))
        assertEquals("Christoph…", Pins.label("Christopherson"))
        assertEquals(listOf("b", "c", "a", "d", "e"), Pins.move(p, 0, 2).map { it.id })
        assertEquals(listOf("e", "a", "b", "c", "d"), Pins.move(p, 4, -3).map { it.id })
        assertEquals(p, Pins.move(p, 9, 0))
        assertEquals("Nova, overdue", KitA11y.pin(PinModel("n", "Nova", overdue = true)))
    }

    @Test fun chips() {
        assertNull(Chips.countText(ChipModel("all", "All")))
        assertNull(Chips.countText(ChipModel("all", "All", 0)))
        assertEquals("3", Chips.countText(ChipModel("h", "Health", 3)))
        assertEquals("99+", Chips.countText(ChipModel("h", "Health", 140)))
        assertEquals("Health, 3, Selected", KitA11y.chip(ChipModel("h", "Health", 3), true))
        assertEquals("All", KitA11y.chip(ChipModel("all", "All"), false))
    }

    @Test fun mentionQuery() {
        assertEquals(Mention.Query(Mention.Kind.BOT, "no", 4), Mention.query("ask @no"))
        assertEquals(Mention.Query(Mention.Kind.BOT, "", 0), Mention.query("@"))
        assertEquals(Mention.Query(Mention.Kind.FILE, "rep", 5), Mention.query("send #rep"))
        assertEquals(Mention.Query(Mention.Kind.COMMAND, "fre", 0), Mention.query("/fre"))
        assertNull(Mention.query("see 1/2"))        // a slash inside a word
        assertNull(Mention.query("go /fresh"))      // commands only at the start
        assertNull(Mention.query("mail a@b.com"))   // @ inside a word
        assertNull(Mention.query("ask @nova "))     // the word ended
        assertNull(Mention.query(""))
        // cursor in the middle of the text
        assertEquals(Mention.Query(Mention.Kind.BOT, "at", 0), Mention.query("@at and more", 3))
    }

    @Test fun mentionFilterAndApply() {
        val items = listOf(
            Mention.Item(Mention.Kind.BOT, "nova", "Nova", "Research"),
            Mention.Item(Mention.Kind.BOT, "atlas", "Atlas", "Travel"),
            Mention.Item(Mention.Kind.BOT, "manager", "ARGUS", "Lead"),
            Mention.Item(Mention.Kind.COMMAND, "fresh", "fresh", "New conversation"),
        )
        val q = Mention.query("ask @a")!!
        assertEquals(listOf("atlas", "manager", "nova"), Mention.filter(items, q).map { it.key })
        assertEquals(listOf("nova", "atlas", "manager"), Mention.filter(items, Mention.query("@")!!).map { it.key })
        assertEquals(listOf("fresh"), Mention.filter(items, Mention.query("/")!!).map { it.key })
        assertEquals(listOf("nova"), Mention.filter(items, Mention.query("@ov")!!).map { it.key })   // contains, after starts
        val many = (1..10).map { Mention.Item(Mention.Kind.BOT, "b$it", "Bot $it") }
        assertEquals(KitSpec.Mention.MAX_ROWS, Mention.filter(many, Mention.query("@b")!!).size)
        assertEquals("ask @atlas " to 11, Mention.apply("ask @at", q, items[1]))
        val q2 = Mention.query("@at  now", 3)!!
        assertEquals("@atlas now" to 7, Mention.apply("@at  now", q2, items[1], 3))
    }

    @Test fun attachmentsProgressCloud() {
        assertEquals("notes.txt", Attachments.shortName("notes.txt"))
        val s = Attachments.shortName("quarterly-report-final-v3.pdf")
        assertEquals(KitSpec.Attachment.NAME_MAX, s.length)
        assertTrue(s, s.endsWith("….pdf"))
        assertEquals(18, Attachments.shortName("a-very-long-name-without-extension").length)
        assertNull(Progress.fraction(1, 0))
        assertEquals(0.5f, Progress.fraction(50, 100)!!, 0f)
        assertEquals(1f, Progress.fraction(150, 100)!!, 0f)
        assertEquals("42 %", Progress.percent(0.42f))
        assertNull(Progress.percent(null))
        assertEquals("Voice model, 42 %", KitA11y.progress("Voice model", 0.42f))
        assertEquals("Workspace", KitA11y.progress("Workspace", null))
        assertFalse("hidden until P7", CloudChipSpec.ENABLED)
        assertEquals("Cloud on · \$0.055/h", CloudChipSpec.text(0.055))
        assertEquals("Cloud on · \$1.20/h", CloudChipSpec.text(1.2))
        assertEquals("Cloud on", CloudChipSpec.text(null))
        assertEquals("Remove a.pdf", KitA11y.removeAttachment(AttachmentModel("1", "a.pdf")))
    }

    // ---------------- AA (04 §5.2) for the colours the kit adds ----------------

    private fun worstOn(fg: Long, under: (Long) -> Long, dark: Boolean): Double {
        val p = ArgusTokens.palette(dark)
        return Ground.entries.minOf { g -> ArgusContrast.worst(fg, ArgusTokens.groundSamples(p, g).map(under)) }
    }

    @Test fun dangerButtonTextPassesAA() {
        for (dark in listOf(true, false)) {
            val p = ArgusTokens.palette(dark)
            val r = worstOn(KitSpec.dangerText(p), { ArgusContrast.over(KitSpec.dangerFill(p), it) }, dark)
            assertTrue("dark=$dark $r", r >= ArgusContrast.AA_TEXT)
        }
        // the 04 values are what failed (kept here so the reason stays checked)
        val d = ArgusTokens.Dark
        assertTrue(worstOn(0xFFFF6B62L, { ArgusContrast.over(ArgusTokens.a(d.danger, 0.16f), it) }, true) < ArgusContrast.AA_TEXT)
    }

    @Test fun managerRowAndLeadBadgePassAA() {
        for (dark in listOf(true, false)) {
            val p = ArgusTokens.palette(dark)
            val tint = { s: Long -> ArgusContrast.over(ArgusTokens.a(p.manager, KitSpec.MANAGER_TINT_ALPHA), s) }
            assertTrue("preview dark=$dark", worstOn(KitSpec.managerPreview(p), tint, dark) >= ArgusContrast.AA_TEXT)
            assertTrue("name dark=$dark", worstOn(p.ink, tint, dark) >= ArgusContrast.AA_TEXT)
            assertTrue("time dark=$dark", worstOn(KitSpec.managerMeta(p), tint, dark) >= ArgusContrast.AA_TEXT)
            assertTrue("lead badge dark=$dark", ArgusContrast.ratio(KitSpec.LEAD_TEXT, p.manager) >= ArgusContrast.AA_TEXT)
        }
    }

    @Test fun bannerAndChipsPassAA() {
        for (dark in listOf(true, false)) {
            val p = ArgusTokens.palette(dark)
            for (v in listOf(NeedYou.Variant.INK, NeedYou.Variant.AMBER))
                assertTrue("banner $v dark=$dark", ArgusContrast.ratio(KitSpec.bannerText(p, v), KitSpec.bannerFill(p, v)) >= ArgusContrast.AA_TEXT)
            // selected chip: onInk on ink, count at 60 %
            assertTrue(ArgusContrast.worst(ArgusTokens.a(p.onInk, KitSpec.Chips.COUNT_ALPHA), listOf(p.ink)) >= ArgusContrast.AA_TEXT)
            // unselected chip: ink (and its count at 60 %) on raised glass
            for (g in Ground.entries) {
                val raised = ArgusTokens.surfaces(p, g, GlassLevel.RAISED)
                assertTrue("chip dark=$dark $g", ArgusContrast.worst(ArgusTokens.a(p.ink, KitSpec.Chips.COUNT_ALPHA), raised) >= ArgusContrast.AA_TEXT)
                // unread dot (UI, 3:1) on flat rows
                assertTrue("dot dark=$dark $g", ArgusContrast.worst(p.unread, ArgusTokens.surfaces(p, g, GlassLevel.FLAT)) >= ArgusContrast.AA_UI)
            }
        }
    }
}
