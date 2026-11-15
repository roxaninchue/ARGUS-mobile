package com.blaze.assistant.ui

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Owner 2026-11-15: "remove the quote icon and the emoji/react feature". No Quote or React icon under replies, no double-tap 👍, no emoji chips or picker in the chat. */
class NoReactQuoteIconTest {
    private fun file(rel: String): File = listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() } ?: File("app/$rel")

    @Test fun chatHasNoReactionsOrQuoteIcon() {
        val chat = file("src/main/java/com/blaze/assistant/ui/ChatScreen.kt").readText()
        val actions = chat.substring(chat.indexOf("private fun Actions("))
        val body = actions.substring(0, actions.indexOf("MetaLine(side, time, acts"))
        assertFalse(body.contains("add(\"Quote\""))
        assertFalse(body.contains("add(\"React\""))
        for (gone in listOf("ReactionChips(", "ReactionPicker(", ".doubleTapToReact(", "add(\"React ")) assertFalse(gone, chat.contains(gone))
    }

    @Test fun replyLongPressHasNoMenuColumn() {
        val chat = file("src/main/java/com/blaze/assistant/ui/ChatScreen.kt").readText()
        val line = chat.lines().first { "LocalBubbleMenu provides" in it }
        assertFalse(line.contains("Role.BLAZE"))
    }
}
