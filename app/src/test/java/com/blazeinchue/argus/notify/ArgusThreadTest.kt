package com.blazeinchue.argus.notify


import com.blazeinchue.argus.BrandName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.45.19 phone tasks: every Argus message (check-ins, task results, replies) is one Argus conversation: one builder,
 * one channel that alerts, one avatar Person, one shortcut, quick replies that fit the message.
 */
class ArgusThreadTest {
    private fun src(p: String) = listOf(File("src/main/java/com/blazeinchue/argus/$p"), File("app/src/main/java/com/blazeinchue/argus/$p")).first { it.exists() }.readText()

    @Test fun newChannelIdAndOldOnesDeleted() {
        assertFalse(ArgusThread.CHANNEL in ArgusThread.OLD_CHANNELS)
        assertTrue(ArgusThread.OLD_CHANNELS.containsAll(listOf("argus_reply", "argus_proactive", "argus_calls", "argus_followups")))
        assertEquals(listOf("argus_reply", "argus_calls"), ArgusThread.toDelete(listOf("argus_work", "argus_reply", "argus_calls", ArgusThread.CHANNEL, "argus_action")))
        assertEquals(emptyList<String>(), ArgusThread.toDelete(listOf(ArgusThread.CHANNEL, "argus_work")))
    }

    @Test fun channelAlertsAndHonoursDnd() {
        val n = src("notify/Notifier.kt")
        assertTrue(n.contains("NotificationChannel(ArgusThread.CHANNEL, ArgusThread.CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)"))
        assertTrue(n.contains("ArgusThread.toDelete(existing).forEach { nm.deleteNotificationChannel(it) }"))
        assertFalse("never bypasses Do Not Disturb", n.contains("setBypassDnd"))
        assertFalse("no forced sound", n.contains("setSound("))
        assertFalse("not silent", Regex("post\\([^)]*\\)[\\s\\S]{0,1500}setSilent\\(true\\)").containsMatchIn(n.substringAfter("fun post(").substringBefore("fun reply(")))
    }

    @Test fun oneBuilderForEveryArgusMessage() {
        val n = src("notify/Notifier.kt")
        val post = n.substringAfter("fun post(").substringBefore("fun reply(")
        for (needle in listOf("NotificationCompat.MessagingStyle(me)", "style.addMessage(m.text, m.at, if (m.fromUser) me else argus())", ".setShortcutId(ArgusThread.SHORTCUT)",
                "setAllowGeneratedReplies(false)", "QuickReplies.forMessage(text, kind)", "setChoices(", "SEMANTIC_ACTION_REPLY",
                ".notify(ArgusThread.NOTIF_ID, ", "CATEGORY_MESSAGE", "ensureShortcut()"))
            assertTrue(needle, post.contains(needle))
        assertFalse(post.contains("setAllowGeneratedReplies(true)"))
        // the avatar is the app icon, not a letter
        assertTrue(n.contains("R.mipmap.ic_launcher") && n.contains("setIcon(it)"))
        assertTrue(n.contains("setLongLived(true).setPerson(argus())"))
        // reply / proactive / question go through post
        assertTrue(n.substringAfter("fun reply(").substringBefore("fun wakeHeard").contains("post(raw, replyKind(raw, isError), chatId)"))
        assertTrue(n.substringAfter("fun proactive(").substringBefore("fun question").contains("post(text, kind, chatId"))
        assertTrue(n.substringAfter("fun question(").substringBefore("fun clearQuestion").contains("post(q, ArgusThread.Kind.QUESTION"))
        // the check-in path and the other senders
        assertTrue(src("proactive/Jarvis.kt").contains("Proactive.say(text, \"jarvis-q:\" + q.id, kind = com.blazeinchue.argus.notify.ArgusThread.Kind.CHECK_IN)"))
        assertTrue(src("proactive/Jarvis.kt").contains("Notifier.post(text, com.blazeinchue.argus.notify.ArgusThread.Kind.CALL_OFFER"))
        assertFalse(src("proactive/Jarvis.kt").contains("\"argus_calls\""))
        assertTrue(src("proactive/FollowUps.kt").contains("Notifier.post(text, com.blazeinchue.argus.notify.ArgusThread.Kind.FOLLOW_UP"))
        assertFalse(src("proactive/FollowUps.kt").contains("createNotificationChannel"))
        assertTrue(src("proactive/Proactive.kt").contains("Notifier.proactive(text, suggest, urgent, kind)"))
        assertFalse(n.contains("NotificationChannel(CH_PROACTIVE"))
    }

    @Test fun threadKeepsTheLastLinesOnce() {
        val t = ArrayDeque<ArgusThread.Msg>()
        repeat(7) { ArgusThread.push(t, ArgusThread.Msg(it.toLong(), "line $it", ArgusThread.Kind.REPLY)) }
        assertEquals(MessageThread.KEEP, t.size)
        assertEquals("line 6", t.last().text)
        ArgusThread.push(t, ArgusThread.Msg(9, "line 6", ArgusThread.Kind.REPLY))
        assertEquals(MessageThread.KEEP, t.size)
        assertEquals(9L, t.last().at)
        assertEquals(MessageThread.KEEP, ArgusThread.push(t, ArgusThread.Msg(10, "  ", ArgusThread.Kind.REPLY)).size)
    }

    @Test fun titleIsArgusOrHonestFailure() {
        assertEquals("${BrandName.NAME}", ArgusThread.title(ArgusThread.Kind.CHECK_IN))
        assertEquals("${BrandName.NAME}", ArgusThread.title(ArgusThread.Kind.RESULT))
        assertEquals(MessageThread.FAILED_TITLE, ArgusThread.title(ArgusThread.Kind.FAILED))
        assertEquals("${BrandName.NAME} \u00b7 important", ArgusThread.title(ArgusThread.Kind.HEADS_UP, urgent = true))
    }

    // ---------- quick replies that fit (5/5 each over phrasings) ----------

    private fun fits(expected: List<String>, vararg texts: String, kind: ArgusThread.Kind = ArgusThread.Kind.RESULT) {
        var ok = 0
        for (t in texts) {
            val q = QuickReplies.forMessage(t, kind)
            assertEquals(t, expected, q)
            assertTrue(q.none { it.lowercase() in QuickReplies.NEVER })
            ok++
        }
        assertEquals(5, ok)
    }

    @Test fun toggleOnGetsTurnItOff() = fits(listOf("Turn it off", "Thanks"),
        "Done \u2713 Dark mode is on.", "Dark mode is now on.", "Done \u2713 Wi-Fi is on.", "I turned Bluetooth on.", "Dark mode is now turned on.")

    @Test fun toggleOffGetsTurnItOn() = fits(listOf("Turn it on", "Thanks"),
        "Done \u2713 Dark mode is off.", "Wi-Fi is now off.", "Done \u2713 Bluetooth is off.", "I turned it off.", "Airplane mode is already off.")

    @Test fun failureGetsTryAgain() = fits(listOf("Try again", "What happened?"),
        "I couldn't find the Dark mode switch.", "That failed: no network.", "I wasn't able to open Settings.", "${BrandName.NAME} hit a problem.", "Sorry, it didn't work.")

    @Test fun honestFallbackGetsCheckAgain() = fits(listOf("Check it again", "Thanks"),
        "I turned it on but couldn't check it; it should be on.", "I turned Dark mode on but couldn't check it; it should be on.",
        "I turned it off but couldn't check it; it should be off.", "I turned Wi-Fi off but couldn't confirm it; it should be off.",
        "I couldn't confirm it: couldn't read the switch back. Dark mode is now turned on.")

    @Test fun yesNoQuestionGetsYesNoLater() = fits(listOf("Yes", "No", "Later"),
        "Battery is at 15%. Want me to turn on battery saver?", "Should I set an alarm for 7?", "Want me to reply to Sam?",
        "Shall I add it to your calendar?", "Do you want me to open Maps?", kind = ArgusThread.Kind.HEADS_UP)

    @Test fun checkInNeverGetsWeirdOrGreat() {
        val q = QuickReplies.forMessage("Did 'lovely' hit different today because of where you are now, or is it just a random favorite?", ArgusThread.Kind.CHECK_IN)
        assertEquals(listOf("Ask me later", "Skip this one"), q)
        assertEquals(listOf("Coffee", "Tea"), QuickReplies.forMessage("Coffee or tea?", ArgusThread.Kind.CHECK_IN))
        assertEquals(listOf("Morning", "Evening"), QuickReplies.forMessage("Do you prefer morning or evening?", ArgusThread.Kind.CHECK_IN))
        for (t in listOf("Dark mode is on.", "Done \u2713 Alarm set for 06:40.", "Here's the weather: 24\u00b0 and sunny.", "What's your favourite song lately?", "Now playing Despacito."))
            for (k in ArgusThread.Kind.values()) assertTrue("$t/$k", QuickReplies.forMessage(t, k).none { it.lowercase() in QuickReplies.NEVER })
    }

    @Test fun otherResultsFit() {
        assertEquals(listOf("Cancel it", "Thanks"), QuickReplies.forMessage("Done \u2713 Alarm set for 06:40, label QA wake."))
        assertEquals(listOf("Pause", "Thanks"), QuickReplies.forMessage("Now playing Despacito on YouTube."))
        assertEquals(emptyList<String>(), QuickReplies.forMessage("Got it, I'm on it. I'll message you here when it's done."))
        assertEquals(emptyList<String>(), QuickReplies.forMessage("I play guitar", ArgusThread.Kind.USER))
        assertEquals(listOf("Not now"), QuickReplies.forMessage("Got a minute? I can catch you up out loud.", ArgusThread.Kind.CALL_OFFER))
        assertTrue(QuickReplies.forMessage("x".repeat(10)).size <= QuickReplies.MAX)
    }
}
