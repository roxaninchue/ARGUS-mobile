package com.blaze.assistant.agent

import com.blaze.assistant.agent.ToolVerify.Status
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolVerifyTest {
    private fun a(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { put(it.first, it.second) } }

    private class Fake(val fg: String? = null,
                       val alarm: Pair<Int, Int>? = null, val size: Long? = null, val alarmAt: Long? = null, val clock: Long = 0L) : ToolVerify.Probes {
        override suspend fun foreground() = fg
        override val self = "com.blaze.assistant"
        override suspend fun nextAlarm() = alarm
        override suspend fun nextAlarmAt() = alarmAt
        override fun now() = clock
        override fun fileSize(path: String) = size
    }
    private val noWait: suspend (Long, suspend () -> Boolean) -> Boolean = { _, ok -> ok() }
    private fun check(name: String, args: JSONObject, result: String, p: ToolVerify.Probes) = runBlocking { ToolVerify.check(name, args, result, p, 0, noWait) }

    @Test fun failuresAndReadOnlyResults() {
        assertEquals(Status.FAILED, ToolVerify.classify("send_sms", a(), "Couldn't send: no number.").status)
        assertEquals(Status.FAILED, ToolVerify.classify("message_contact", a(), "Not sent yet: WhatsApp is open on Mary's chat").status)
        assertEquals(Status.UNVERIFIED, ToolVerify.classify("message_contact", a(), "Couldn't confirm it was sent: I pressed Send in Mary's WhatsApp").status)
        assertEquals(Status.VERIFIED, ToolVerify.classify("web_search", a(), "1. Lusaka weather: 28C").status)
        assertEquals(Status.VERIFIED, ToolVerify.classify("message_contact", a(), "Sent to Mary on WhatsApp: hi").status)
    }

    @Test fun settingsPageOpenedIsNotASettingChanged() {
        val v = ToolVerify.classify("device_setting", a("setting" to "wifi", "value" to "on"), "Opened Wi-Fi settings.")
        assertEquals(Status.UNVERIFIED, v.status)
        assertTrue(v.why.contains("not changed"))
        assertEquals(Status.VERIFIED, ToolVerify.classify("device_setting", a(), "Flashlight on.").status)
        assertEquals(Status.VERIFIED, ToolVerify.classify("device_setting", a(), "Media volume is 40%.").status)
    }

    @Test fun handedOffIntentsStayUnverified() {
        assertEquals(Status.UNVERIFIED, ToolVerify.classify("set_timer", a(), "Timer started for 10 minutes.").status)
        assertEquals(Status.UNVERIFIED, ToolVerify.classify("call_number", a(), "Calling 0977.").status)
    }

    @Test fun desktopToolIsGone() {
        // Wave B-4: the on-phone Linux desktop and its `desktop` tool were removed; nothing probes it any more.
        assertFalse("desktop" in ToolVerify.PROBED)
    }

    @Test fun appLaunchNeedsTheAppInFront() {
        assertEquals("com.whatsapp", ToolVerify.packageIn("Opened WhatsApp (com.whatsapp). It is installed"))
        assertEquals("com.whatsapp", ToolVerify.packageIn("Opened com.whatsapp. It is installed"))
        val r = "Opened WhatsApp (com.whatsapp). It is installed, so never offer to install it."
        assertEquals(Status.VERIFIED, check("open_app", a(), r, Fake(fg = "com.whatsapp")).status)
        assertEquals(Status.UNVERIFIED, check("open_app", a(), r, Fake(fg = "com.blaze.assistant")).status)
        assertEquals(Status.UNVERIFIED, check("open_app", a(), r, Fake(fg = null)).status)
    }

    @Test fun alarmMustBeListed() {
        assertEquals(Status.VERIFIED, check("set_alarm", a("hour" to 7, "minute" to 0), "Alarm set. [ID A3]", Fake(alarm = 7 to 0)).status)
        assertEquals(Status.UNVERIFIED, check("set_alarm", a("hour" to 7, "minute" to 0), "Alarm set. [ID A3]", Fake(alarm = 6 to 30)).status)
    }

    /** 0.55.1 (Finn's phone): an earlier alarm hides a 07:00 alarm the Clock app took; that is set, not "couldn't confirm". */
    @Test fun anEarlierAlarmNoLongerHidesASetAlarm() {
        val tz = java.util.TimeZone.getDefault()
        val now = java.util.Calendar.getInstance(tz).apply { set(2026, 10, 22, 22, 30, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        val target = ToolVerify.nextAt(7, 0, now)
        assertEquals(8.5 * 3_600_000, (target - now).toDouble(), 1.0)   // 22:30 → 07:00 tomorrow
        val ok = "Alarm set for 07:00, label \u201c(Argus A7)\u201d. [ID A7]"
        val args = a("hour" to 7, "minute" to 0)
        // the system's next alarm is an earlier one (06:00 tomorrow): ours is hidden behind it
        val v = check("set_alarm", args, ok, Fake(alarm = 6 to 0, alarmAt = target - 3_600_000, clock = now))
        assertEquals(Status.VERIFIED, v.status)
        assertTrue(v.why, v.why.contains("an earlier alarm rings first"))
        // the next alarm is LATER than ours: ours really isn't there
        val bad = check("set_alarm", args, ok, Fake(alarm = 9 to 0, alarmAt = target + 2 * 3_600_000, clock = now))
        assertEquals(Status.UNVERIFIED, bad.status)
        assertFalse(ToolVerify.reportedDone(bad.why))
        // the Clock app didn't take it at all: a plain failure, never verified
        assertEquals(Status.FAILED, check("set_alarm", args, "No app on this phone can do that.", Fake(alarm = 6 to 0, alarmAt = target - 1, clock = now)).status)
        // the system list can't be read: the tool's own success stands (soft note, see UnconfirmedEcho)
        val unread = check("set_alarm", args, ok, Fake(clock = now))
        assertTrue(ToolVerify.reportedDone(unread.why))
    }

    @Test fun aToolThatReportedSuccessIsNeverPrefixedWithCouldntConfirm() {
        val done = listOf(ClaimCheck.Done("set_alarm", Status.UNVERIFIED, ToolVerify.REPORTED + "; the system's alarm list couldn't be read"))
        for (reply in listOf("Alarm set for 7:00 tomorrow.", "Done, alarm's set for 7.", "Started setting an alarm for 7 am tomorrow.")) {
            val out = UnconfirmedEcho.fix(reply, done)
            assertTrue(out, out.startsWith(reply.trim()))
            assertFalse(out, out.startsWith("I couldn't confirm"))
        }
        // a real failure still gets the honest line
        val real = listOf(ClaimCheck.Done("set_alarm", Status.UNVERIFIED, "the Clock app didn't list an alarm at 07:00"))
        assertTrue(UnconfirmedEcho.fix("Alarm set for 7.", real).startsWith("I couldn't confirm"))
    }

    @Test fun filesAreReadBack() {
        assertEquals(Status.VERIFIED, ToolVerify.classify("file_write", a(), "Wrote /root/a.txt (12 bytes, 1 lines).").status)
        assertEquals(Status.VERIFIED, check("export_file", a(), "Saved to /sdcard/Download/a.pdf", Fake(size = 2048)).status)
        assertEquals(Status.FAILED, check("export_file", a(), "Saved to /sdcard/Download/a.pdf", Fake(size = -1)).status)
    }

    @Test fun tagLinesAndTheRule() {
        assertTrue(ToolVerify.tag("ok", ToolVerify.Verdict(Status.VERIFIED)).endsWith("[verified=true]"))
        val u = ToolVerify.tag("Timer started.", ToolVerify.Verdict(Status.UNVERIFIED, "handed to Clock"))
        assertTrue(u.contains("[verified=false: handed to Clock"))
        assertTrue(ToolVerify.tagged(u))
        assertFalse(ToolVerify.tagged("plain"))
        assertTrue(ToolVerify.tag("x", ToolVerify.Verdict(Status.FAILED, "port closed")).contains("FAILED: port closed"))
        // The tag never makes a result look failed to the older checks.
        assertTrue(ClaimGuard.succeeded(ToolVerify.tag("Opened Settings.", ToolVerify.Verdict(Status.VERIFIED))))
        assertTrue(ToolVerify.PROMPT_RULE.contains("verified=true"))
    }
}
