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
}
