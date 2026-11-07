package com.blazeinchue.argus.notify


import com.blazeinchue.argus.BrandName
import com.blazeinchue.argus.agent.Ack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.45.29 (user report, Mon 26 Oct 09:02): a user replied "I play guitar" from a heads-up and the notification then
 * showed "Playing guitar a little." under Argus's name: an ack line, not an answer. Argus shows only what the model
 * wrote; the user's inline reply shows as the user's own words until the real answer arrives.
 */
class NoCannedRepliesTest {
    private fun main(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun src(rel: String) = File(main(), "java/com/blazeinchue/argus/$rel").readText()

    @Test fun theUsersReplyShowsAsTheirsInTheirOwnWords() {
        val thread = ArrayDeque<ArgusThread.Msg>()
        ArgusThread.push(thread, ArgusThread.Msg(1, "That song's got such a simple, warm hook \u2014 is there an instrument you've been meaning to pick up?", ArgusThread.Kind.CHECK_IN))
        val lines = ArgusThread.push(thread, ArgusThread.Msg(2, "I play guitar", ArgusThread.Kind.USER))
        assertEquals(2, lines.size)
        assertEquals("I play guitar", lines.last().text)          // never reworded
        assertTrue(lines.last().fromUser)
        assertFalse(lines.first().fromUser)
        assertTrue(QuickReplies.forMessage("I play guitar", ArgusThread.Kind.USER).isEmpty())
        // The real answer lands below it, as Argus's.
        val after = ArgusThread.push(thread, ArgusThread.Msg(3, "Nice! What do you like to play?", Notifier.replyKind("Nice! What do you like to play?", false)))
        assertEquals(listOf(false, true, false), after.map { it.fromUser })
        // The same words from Argus right after the user's are not merged into the user's line.
        val again = ArgusThread.push(ArrayDeque(listOf(ArgusThread.Msg(1, "ok", ArgusThread.Kind.USER))), ArgusThread.Msg(2, "ok", ArgusThread.Kind.REPLY))
        assertEquals(2, again.size)
    }

    @Test fun thereIsNoAckKindOrAckPost() {
        assertFalse(ArgusThread.Kind.values().any { it.name == "ACK" })
        assertFalse(Notifier::class.java.declaredMethods.any { it.name == "ack" })
        assertEquals(ArgusThread.Kind.FAILED, Notifier.replyKind("Couldn't reach ${BrandName.NAME} Cloud.", true))
        assertEquals(ArgusThread.Kind.REPLY, Notifier.replyKind("Got it, here's the plan.", false))   // model words are a reply, never an "ack"
        assertEquals(ArgusThread.Kind.RESULT, Notifier.replyKind("Done \u2713 Dark mode is on.", false))
    }

    @Test fun noOnDeviceAckWordingIsLeft() {
        val gone = setOf("local", "describe", "yours", "safe", "receipt", "isReadOnly", "whenPart")
        val left = Ack::class.java.declaredMethods.map { it.name }.filter { it in gone }
        assertTrue("canned ack helpers came back: $left", left.isEmpty())
    }

    @Test fun noCannedLineIsPostedBetweenAMessageAndItsAnswer() {
        val agent = src("agent/Agent.kt")
        for (bad in listOf("Ack.local(", "showAck(", "Notifier.ack(", "${BrandName.NAME} got your message", "Ack.receipt("))
            assertFalse("Agent.kt still has $bad", agent.contains(bad))
        val receiver = src("notify/Notifier.kt")
        assertFalse(receiver.contains("Thinking about: "))
        assertTrue(receiver.contains("Notifier.userReplied(text"))
        assertTrue(receiver.contains("Agent.replyFromNotification(chat, context, text)"))
        // The 0.45.28 wait line is the model's own words and stays.
        assertTrue(agent.contains("startWaitNudge("))
    }
}
