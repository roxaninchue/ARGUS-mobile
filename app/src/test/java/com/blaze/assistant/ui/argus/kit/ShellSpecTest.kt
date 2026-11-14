package com.blaze.assistant.ui.argus.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.blaze.assistant.ui.argus.kit.ShellSpec.Composer.Trailing as T
import com.blaze.assistant.ui.argus.kit.ShellSpec.Notch.Pull as P

/** P1-SH-01: the shell's rules (04 §2.8 Chrome, §3.2): composer states, placeholders, notch pulls, chips, toasts. */
class ShellSpecTest {
    @Test fun composerTrailingMicSendStop() {
        assertEquals(T.MIC, ShellSpec.Composer.trailing("", 0, streaming = false))
        assertEquals(T.MIC, ShellSpec.Composer.trailing("   ", 0, streaming = false))
        assertEquals(T.SEND, ShellSpec.Composer.trailing("hi", 0, streaming = false))
        assertEquals(T.SEND, ShellSpec.Composer.trailing("", 1, streaming = false))
        assertEquals(T.STOP, ShellSpec.Composer.trailing("", 0, streaming = true))
        assertEquals("typing while it streams joins the turn", T.SEND, ShellSpec.Composer.trailing("and also", 0, streaming = true))
        assertEquals(listOf("mic", "arrow-up", "square"), T.entries.map { ShellSpec.Composer.trailingIcon(it) })
        assertEquals(listOf("Talk", "Send", "Stop"), T.entries.map { ShellSpec.Composer.trailingLabel(it) })
        assertEquals(5, ShellSpec.Composer.MAX_LINES)
        assertEquals(58, ShellSpec.Composer.HEIGHT)
        assertTrue("the + disc keeps a 48 dp target", KitSpec.target(ShellSpec.Composer.PLUS) >= KitSpec.TARGET)
    }

    @Test fun placeholdersPerMode() {
        assertEquals("Tell the team\u2026", ShellSpec.Composer.placeholder("team"))
        assertEquals("Ask anything", ShellSpec.Composer.placeholder("chat"))
        assertEquals("Ask about your life\u2026", ShellSpec.Composer.placeholder("life"))
        assertEquals("Add a job, routine or file\u2026", ShellSpec.Composer.placeholder("desk"))
        assertEquals("Ask anything", ShellSpec.Composer.placeholder("projects"))
    }

    @Test fun notchPulls() {
        assertEquals(P.NONE, ShellSpec.Notch.pull(0f))
        assertEquals(P.NONE, ShellSpec.Notch.pull(23.9f))
        assertEquals(P.OPEN, ShellSpec.Notch.pull(24f))
        assertEquals(P.OPEN, ShellSpec.Notch.pull(139f))
        assertEquals(P.VOICE, ShellSpec.Notch.pull(140f))
        assertEquals(46 to 22, ShellSpec.Notch.HANDLE_W to ShellSpec.Notch.HANDLE_H)
        assertEquals(listOf("Team", "Chat", "Life", "Desk"), ShellSpec.MODES.map { ShellSpec.label(it) })
        assertEquals("Team mode. Switch mode", KitA11y.Shell.notch("team", open = false))
        assertEquals("Desk mode. Close mode switcher", KitA11y.Shell.notch("desk", open = true))
    }

    @Test fun chipsAndToasts() {
        assertEquals(listOf("Add job", "Memory", "Routines"), ShellSpec.Chips.of("team").map { it.label })
        assertEquals(emptyList<Any>(), ShellSpec.Chips.of("chat"))
        assertTrue(ShellSpec.Chips.visible(0, sheetOpen = false))
        assertFalse(ShellSpec.Chips.visible(1, sheetOpen = false))
        assertFalse(ShellSpec.Chips.visible(0, sheetOpen = true))
        assertEquals(ShellSpec.Toast.SHORT_MS, ShellSpec.Toast.durationMs("Dark"))
        assertEquals(ShellSpec.Toast.LONG_MS, ShellSpec.Toast.durationMs("x".repeat(200)))
        assertEquals(3_310L, ShellSpec.Toast.durationMs("x".repeat(35)))
        assertEquals(62, ShellSpec.TOP_BAR_DP)
    }

    /** Owner 2026-11-14: "Let's {explore|discover|build|…}": the lead mostly stays, the next word changes, the lead now and then. */
    @Test fun composerHintsRotateUnderAMostlyStaticLead() {
        val H = ShellSpec.Composer.Hints
        for (seed in listOf(1, 42, 1_731_590_000)) {
            val hs = (0 until 200).map { H.hint(it, seed) }
            assertEquals("Let's", hs[0].lead)
            for (b in 0 until 50) {
                val block = hs.subList(b * H.LEAD_EVERY, (b + 1) * H.LEAD_EVERY)
                assertEquals("one lead per block", 1, block.map { it.lead }.toSet().size)
                assertEquals("no word twice in a block", H.LEAD_EVERY, block.map { it.word }.toSet().size)
                if (b > 0 && hs[b * H.LEAD_EVERY].lead != "Let's") assertTrue("another lead never repeats block to block", hs[b * H.LEAD_EVERY].lead != hs[b * H.LEAD_EVERY - 1].lead)
            }
            val lets = hs.count { it.lead == "Let's" }
            assertTrue("Let's is the usual lead ($lets/200)", lets in 70..150 && hs.groupingBy { it.lead }.eachCount().maxByOrNull { it.value }!!.key == "Let's")
            assertTrue("other leads show up too", hs.map { it.lead }.toSet().size >= 4)
        }
        assertEquals(H.hint(7, 3), H.hint(7, 3))
        for ((lead, words) in H.GROUPS) { assertTrue(lead, words.size % H.STRIDE != 0 && words.size >= H.LEAD_EVERY); assertTrue(lead.split(' ').size <= 2) }
        assertTrue("slow enough not to bother anyone", H.INTERVAL_MS >= 2_500L && H.FADE_MS <= 800)
        assertEquals("the static hint is still Ask anything", "Ask anything", ShellSpec.Composer.placeholder("chat"))
    }

    /** Owner 2026-11-14: the chatbox only rides up with the keyboard when the keyboard is for it. */
    @Test fun keyboardLiftsTheChatboxOnlyForItself() {
        val C = ShellSpec.Composer
        assertTrue(C.liftsWithKeyboard(spotlight = false, search = false, drawer = false, settings = false))
        assertFalse(C.liftsWithKeyboard(spotlight = true, search = false, drawer = false, settings = false))
        assertFalse(C.liftsWithKeyboard(spotlight = false, search = true, drawer = false, settings = false))
        assertFalse(C.liftsWithKeyboard(spotlight = false, search = false, drawer = true, settings = false))
        assertFalse(C.liftsWithKeyboard(spotlight = false, search = false, drawer = false, settings = true))
        val screen = java.io.File("src/main/java/com/blaze/assistant/ui/ChatScreen.kt").readText()
        assertTrue(screen.contains("liftsWithKeyboard(") && screen.contains("if (lift) Modifier.imePadding()"))
    }

    /** Owner 2026-11-14: one continuous surface: drawer over the whole screen, chip and chatbox one glass. */
    @Test fun shellReadsAsOneSurface() {
        val screen = java.io.File("src/main/java/com/blaze/assistant/ui/ChatScreen.kt").readText()
        assertTrue(screen.contains("ChatDrawerLayer()"))
        assertTrue(screen.contains("ModelChip(flat = true)"))
        assertFalse("no chip pill over the composer", screen.contains("ModelChip(Modifier.padding(start = 18.dp))"))
        val drawer = java.io.File("src/main/java/com/blaze/assistant/ui/team/ChatDrawer.kt").readText()
        assertTrue(drawer.contains("drawerShape = RectangleShape"))
        val shell = java.io.File("src/main/java/com/blaze/assistant/ui/argus/kit/KitShell.kt").readText()
        assertTrue(shell.contains("top: (@Composable () -> Unit)? = null"))
    }
}
