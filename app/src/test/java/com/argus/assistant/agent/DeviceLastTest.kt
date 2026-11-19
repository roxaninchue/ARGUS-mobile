package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceLastTest {
    private val ndola = "Find 3 good restaurants near me in Ndola with ratings."

    @Test fun firstScreenCallForAWebTaskIsSentToTheBrowser() {
        assertEquals(DeviceLast.NUDGE, DeviceLast.nudge("open_app", "{\"app\":\"Google Maps\"}", ndola, triedOwn = false, alreadyNudged = false))
        assertEquals(DeviceLast.NUDGE, DeviceLast.nudge("read_screen", "{}", ndola, false, false))
    }
    @Test fun namedAppOrPhoneWordsRun() {
        assertNull(DeviceLast.nudge("open_app", "{\"app\":\"YouTube\"}", "Open YouTube and play lo-fi beats", false, false))
        assertNull(DeviceLast.nudge("open_app", "{\"app\":\"Google Maps\"}", "find cafes in maps", false, false))
        assertNull(DeviceLast.nudge("open_app", "{\"app\":\"WhatsApp\"}", "message mum on whatsapp", false, false))
        assertNull(DeviceLast.nudge("tap", "{}", "tap the blue button on my screen", false, false))
        assertNull(DeviceLast.nudge("open_app", "{\"package\":\"com.spotify.music\"}", "play something on spotify", false, false))
    }
    @Test fun afterOwnToolsOrOneNudgeItRuns() {
        assertNull(DeviceLast.nudge("open_app", "{\"app\":\"Google Maps\"}", ndola, triedOwn = true, alreadyNudged = false))
        assertNull(DeviceLast.nudge("open_app", "{\"app\":\"Google Maps\"}", ndola, triedOwn = false, alreadyNudged = true))
    }
    @Test fun otherToolsAreNeverTouched() {
        assertNull(DeviceLast.nudge("set_timer", "{}", ndola, false, false))
        assertNull(DeviceLast.nudge("browser_open", "{}", ndola, false, false))
    }
}
