package com.blaze.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** 0.55.18: phone control (the Accessibility service, screen tools, Magic Hand, pointer, flash engine) left the app for good. */
class PhoneControlRemovalTest {
    private val manifest get() = File("src/main/AndroidManifest.xml").readText()

    @Test fun noAccessibilityServiceInTheManifest() {
        assertFalse(manifest.contains("AccessibilityService"))
        assertFalse(manifest.contains("BIND_ACCESSIBILITY_SERVICE"))
        assertFalse(File("src/main/res/xml/accessibility_config.xml").exists())
    }

    @Test fun theServiceAndScreenEnginesAreGone() {
        listOf("phone/BlazeAccessibilityService.kt", "phone/MagicHand.kt", "phone/Pointer.kt", "phone/ScreenTree.kt",
            "phone/Watchers.kt", "phone/PhoneControl.kt", "agent/PermissionGate.kt", "agent/ScreenMemory.kt", "phone/flash/FlashLoop.kt")
            .forEach { assertFalse(it, File("src/main/java/com/blaze/assistant/$it").exists()) }
    }

    @Test fun noScreenToolIsDeclared() {
        val agent = File("src/main/java/com/blaze/assistant/agent/Agent.kt").readText()
        listOf("read_screen", "run_plan", "do_actions", "look_at_screen").forEach {
            assertFalse(it, Regex("""tool\(\s*"$it"""").containsMatchIn(agent))
            assertFalse(it, Regex(""""name"\s*,\s*"$it"""").containsMatchIn(agent))
        }
    }

    @Test fun noPhoneControlCardOrSetupStep() {
        val fix = File("src/main/java/com/blaze/assistant/agent/FixCards.kt").readText()
        assertFalse(fix.contains("PHONE_CONTROL"))
        val setup = File("src/main/java/com/blaze/assistant/setup/SetupChecklist.kt").readText()
        assertFalse(setup.contains("Allow phone control"))
    }
}
