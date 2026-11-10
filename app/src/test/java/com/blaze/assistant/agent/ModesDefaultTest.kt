package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ModesDefaultTest {
    @Test fun autopilotIsTheDefault() {
        assertEquals("autopilot", ApprovalDefault.DEFAULT)
        assertEquals("autopilot", ApprovalDefault.of(null))
        assertEquals("autopilot", ApprovalDefault.of(""))
        assertEquals("autopilot", ApprovalDefault.of("garbage"))
        // Unreleased (owner): Autopilot is the only mode; an old saved "auto" reads as Autopilot.
        assertEquals("autopilot", ApprovalDefault.of("auto"))
        assertEquals("autopilot", ApprovalDefault.of("autopilot"))
    }
}
