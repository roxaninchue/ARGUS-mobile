package com.argus.assistant.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.53.4 (owner): sliding between screens got in the way of scrolling the chat, so the mode pager takes no swipes. */
class NoModeSwipeTest {
    private fun file(rel: String): File = listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() } ?: File("app/$rel")

    @Test fun modePagerTakesNoSwipe() {
        val chat = file("src/main/java/com/argus/assistant/ui/ChatScreen.kt").readText()
        val call = chat.substring(chat.indexOf("ArgusModesPager("))
        val end = call.indexOf("} else if (paneNow == \"projects\")")
        assertTrue(end > 0)
        assertTrue(call.substring(0, end).contains("swipe = false)"))
        assertFalse(chat.contains("HorizontalPager("))
    }
}
