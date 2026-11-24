package com.blaze.assistant.phone

import com.blaze.assistant.agent.ActCheck
import com.blaze.assistant.agent.ClaimCheck
import com.blaze.assistant.agent.ToggleReply
import com.blaze.assistant.agent.ToolVerify
import com.blaze.assistant.agent.UnconfirmedEcho
import com.blaze.assistant.notify.BlazeThread
import com.blaze.assistant.notify.QuickReplies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.45.19 phone tasks: toggles are verified by the phone's STATE (fakes of UiModeManager / Configuration, WifiManager,
 * BluetoothAdapter, Settings.System brightness, AudioManager ringer), in-app switches by re-finding and re-reading the
 * switch, and the reply never contradicts itself. Each case runs 5 times with a different settle latency: 5/5 strict.
 */
class StateCheckTest {
    /** A virtual clock: sleep advances it; readings change once it passes [flipAt]. */
    private class Clock { var now = 0L; suspend fun sleep(ms: Long) { now += ms } }

    /** Fake phone: each setting reads [before] until [flipAt] ms, then [after] (null = can't read). */
    private class FakePhone(val clock: Clock, val flipAt: Long, val settings: Map<StateCheck.Setting, Pair<Boolean?, Boolean?>> = emptyMap(),
                            val nightMode: Pair<Int, Int>? = null, val configNight: Pair<Boolean?, Boolean?>? = null,
                            val bright: Pair<Int?, Int?>? = null, val ringer: Pair<Int?, Int?>? = null) : StateCheck.Reader {
        var reads = 0
        private fun <T> at(p: Pair<T, T>): T = if (clock.now >= flipAt) p.second else p.first
        override fun isOn(s: StateCheck.Setting): Boolean? {
            reads++
            if (s == StateCheck.Setting.DARK_MODE && (nightMode != null || configNight != null))
                return StateCheck.darkFrom(nightMode?.let { at(it) } ?: -1, configNight?.let { at(it) })
            if (s == StateCheck.Setting.SILENT || s == StateCheck.Setting.VIBRATE || s == StateCheck.Setting.SOUND) return StateCheck.ringerOn(s, ringerMode())
            return settings[s]?.let { at(it) }
        }
        override fun brightness(): Int? = bright?.let { at(it) }
        override fun ringerMode(): Int? = ringer?.let { at(it) }
    }

    private val latencies = listOf(0L, 100L, 300L, 700L, 1200L)

    private fun fiveOfFive(case: (Clock, Long) -> Boolean) {
        var pass = 0
        for (l in latencies) if (case(Clock(), l)) pass++
        assertEquals("strict 5/5", 5, pass)
    }

    private fun toggle(label: String, want: Boolean, phone: StateCheck.Reader?, clock: Clock, reread: suspend () -> Boolean? = { null }) =
        runBlocking { StateCheck.verifyToggle(label, want, phone, reread, { clock.sleep(it) }) }

    // ---------- verified by state ----------



    @Test fun wifiByWifiManager() = fiveOfFive { c, l ->
        val phone = FakePhone(c, l, settings = mapOf(StateCheck.Setting.WIFI to (false to true)))
        val r = toggle("Wi-Fi", true, phone, c)
        r.verified && r.word == "Wi-Fi" && r.how == "state"
    }

    @Test fun bluetoothByAdapter() = fiveOfFive { c, l ->
        val phone = FakePhone(c, l, settings = mapOf(StateCheck.Setting.BLUETOOTH to (true to false)))
        val r = toggle("Bluetooth", false, phone, c)
        r.verified && r.word == "Bluetooth" && StateCheck.evidence(r) == "Bluetooth is off (read from Bluetooth)"
    }

    @Test fun brightnessBySettingsSystem() = fiveOfFive { c, l ->
        val phone = FakePhone(c, minOf(l, 700), bright = 40 to 127)
        val r = runBlocking { StateCheck.verifyBrightness(50, phone, { c.sleep(it) }) }
        r.verified && r.how == "state"
    }

    @Test fun ringerByAudioManager() = fiveOfFive { c, l ->
        val phone = FakePhone(c, minOf(l, 700), ringer = 2 to 1)
        val r = runBlocking { StateCheck.verifyRinger("vibrate", phone, { c.sleep(it) }) }
        val viaToggle = toggle("Vibrate", true, FakePhone(Clock(), 0, ringer = 2 to 1), Clock())
        r.verified && r.word == "Vibrate" && viaToggle.verified
    }

    // ---------- in-app toggles: no system state, the switch is re-found and re-read ----------


    @Test fun inAppToggleOffByReRead() = fiveOfFive { c, l ->
        // the switch still reads on for a while (animation), then off
        val r = toggle("Switch, Autoplay videos", false, null, c, reread = { c.now >= l == false })
        r.verified && r.how == "switch" && r.word == "Autoplay videos"
    }

    // ---------- not verified: contradicted, unknown ----------



    @Test fun noContradictionEver() {
        // the exact 0.45.18 reply from the phone
        val why = "couldn't read the switch back"
        val done = listOf(ClaimCheck.Done("tap", ToolVerify.Status.UNVERIFIED, why))
        val bad = "I couldn't confirm it: couldn't read the switch back. Dark mode is now turned on."
        for (reply in listOf("Dark mode is now turned on.", bad, "I couldn't confirm it, but dark mode is on.", "Done, it's on.", "Dark mode has been turned on.")) {
            val out = UnconfirmedEcho.fix(reply, done)
            assertFalse(out, UnconfirmedEcho.HONEST.containsMatchIn(out) && UnconfirmedEcho.CLAIM.containsMatchIn(out))
            assertTrue(out, UnconfirmedEcho.HONEST.containsMatchIn(out))
        }
        // verified: the reply is the one Done line, never a "couldn't"
        val ok = listOf(ClaimCheck.Done("tap", ToolVerify.Status.VERIFIED, "Dark mode is on (read from the system theme)"))
        for (reply in listOf("Dark mode is now turned on.", "Done! Dark mode is on.", "Dark mode is on.", "Done.", "I turned on Dark mode for you."))
            assertEquals(reply, "Done \u2713 Dark mode is on.", ToggleReply.fix(UnconfirmedEcho.fix(reply, ok), ok))
        // a reply that asks something or carries more stays the model's
        assertEquals("Dark mode is on. Want me to schedule it from sunset?", ToggleReply.fix("Dark mode is on. Want me to schedule it from sunset?", ok))
        // an unrelated later action that wasn't a switch leaves it alone
        val later = ok + ClaimCheck.Done("open_url", ToolVerify.Status.VERIFIED, "Chrome is in front")
        assertEquals("Opened it.", ToggleReply.fix("Opened it.", later))
    }

    @Test fun quickRepliesFitTheResult() {
        assertEquals(listOf("Turn it off", "Thanks"), QuickReplies.forMessage(StateCheck.doneLine("Dark mode", true), BlazeThread.Kind.RESULT))
        assertEquals(listOf("Turn it on", "Thanks"), QuickReplies.forMessage(StateCheck.doneLine("Wi-Fi", false), BlazeThread.Kind.RESULT))
        assertEquals(listOf("Check it again", "Thanks"), QuickReplies.forMessage(StateCheck.honest(true), BlazeThread.Kind.RESULT))
        assertEquals(listOf("Try again", "What happened?"), QuickReplies.forMessage("I tried to turn Wi-Fi on, but it still reads off.", BlazeThread.Kind.RESULT))
        for (t in listOf(StateCheck.doneLine("Dark mode", true), StateCheck.honest(true), "I tried to turn Wi-Fi on, but it still reads off."))
            assertTrue(QuickReplies.forMessage(t, BlazeThread.Kind.RESULT).none { it.equals("Weird", true) || it.equals("Great!", true) })
    }

    @Test fun labelsMapToSettings() {
        assertEquals(StateCheck.Setting.DARK_MODE, StateCheck.settingOf("Use Dark theme"))
        assertEquals(StateCheck.Setting.DARK_MODE, StateCheck.settingOf("Dark mode"))
        assertEquals(StateCheck.Setting.WIFI, StateCheck.settingOf("Wi-Fi"))
        assertEquals(StateCheck.Setting.WIFI, StateCheck.settingOf("WiFi"))
        assertEquals(StateCheck.Setting.BLUETOOTH, StateCheck.settingOf("Bluetooth"))
        assertEquals(StateCheck.Setting.AIRPLANE, StateCheck.settingOf("Airplane mode"))
        assertEquals(StateCheck.Setting.DND, StateCheck.settingOf("Do not disturb"))
        assertEquals(StateCheck.Setting.BATTERY_SAVER, StateCheck.settingOf("Power saving"))
        assertNull(StateCheck.settingOf("Show previews"))
        assertEquals(true, StateCheck.darkFrom(2, null)); assertEquals(false, StateCheck.darkFrom(1, null)); assertNull(StateCheck.darkFrom(0, null))
        assertEquals(true, StateCheck.darkFrom(1, true)); assertEquals(false, StateCheck.darkFrom(0, false))
        assertEquals("Dark mode" to true, StateCheck.parseEvidence("Dark mode is on (read from the system theme)"))
        assertNull(StateCheck.parseEvidence("the switch reads OFF"))
    }

}
