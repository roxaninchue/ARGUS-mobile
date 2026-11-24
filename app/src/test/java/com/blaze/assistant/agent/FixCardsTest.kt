package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class FixCardsTest {
    @Test fun matchesMissingAccess() {
        assertEquals(FixCards.Kind.NOTIFICATIONS, FixCards.kindOf("Notification access is off. Ask the user to turn it on in ${BrandName.NAME} › Settings."))
        assertNull("phone control card is gone", FixCards.kindOf("Phone control is off, so I can't take a screenshot."))
        assertEquals(FixCards.Kind.USAGE, FixCards.kindOf("${BrandName.NAME} needs Usage access: I opened that page."))
        assertEquals(FixCards.Kind.APP_PERMISSION, FixCards.kindOf("Error: permission denied for contacts"))
        assertEquals(FixCards.Kind.ALL_FILES, FixCards.kindOf("[All files access is off: Android hides other apps' files]"))
        assertNull(FixCards.kindOf("Opened WhatsApp."))
    }

    @Test fun plainHeadlinesAndButton() {
        assertEquals("I couldn't read WhatsApp", FixCards.headline("whatsapp_read", FixCards.Kind.NOTIFICATIONS))
        assertEquals("I couldn't read your messages", FixCards.headline("read_notifications", FixCards.Kind.NOTIFICATIONS))
        assertEquals("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", FixCards.Kind.NOTIFICATIONS.action)
    }

    @Test fun linuxPermissionDeniedIsNotAnAndroidPermission() {
        assertNull(FixCards.kindOf("cp: can't create '/data/music_server.py': Permission denied", "run_shell"))
        assertNull(FixCards.kindOf("PermissionError: [Errno 13] Permission denied", "run_code"))
        assertEquals(FixCards.Kind.ALL_FILES, FixCards.kindOf("[All files access is off: Android hides other apps' files]", "run_shell"))
        assertEquals(FixCards.Kind.APP_PERMISSION, FixCards.kindOf("Error: permission denied for contacts", "find_contact"))
    }

    @Test fun permissionCardNamesThePermission() {
        assertEquals("Contacts", FixCards.permissionOf("Error: permission denied for contacts"))
        assertEquals("Camera", FixCards.permissionOf("SecurityException: requires android.permission.CAMERA"))
        assertEquals("Microphone", FixCards.permissionOf("this needs the microphone permission"))
        val f = FixCards.Fix(FixCards.Kind.APP_PERMISSION, "", "Camera")
        assertEquals("Allow Camera for ${BrandName.NAME}?", f.title)
        assertEquals("", FixCards.permissionOf("permission denied"))
    }

    // 0.55.18: phone control (the Accessibility service, screen tools, Magic Hand, pointer, flash engine) left the app for good.
    private val srcRoot get() = File("src/main/java/" + javaClass.`package`!!.name.replace('.', '/').removeSuffix("/agent"))

    @Test fun phoneControlIsGoneFromTheManifest() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("AccessibilityService"))
        assertFalse(manifest.contains("BIND_ACCESSIBILITY_SERVICE"))
        assertFalse(File("src/main/res/xml/accessibility_config.xml").exists())
    }

    @Test fun phoneControlEnginesAreGone() {
        listOf("phone/${BrandName.OLD}AccessibilityService.kt", "phone/MagicHand.kt", "phone/Pointer.kt", "phone/ScreenTree.kt",
            "phone/Watchers.kt", "phone/PhoneControl.kt", "agent/PermissionGate.kt", "agent/ScreenMemory.kt", "phone/flash/FlashLoop.kt")
            .forEach { assertFalse(it, File(srcRoot, it).exists()) }
    }

    @Test fun noScreenToolIsDeclared() {
        val agent = File(srcRoot, "agent/Agent.kt").readText()
        listOf("read_screen", "run_plan", "do_actions", "look_at_screen").forEach {
            assertFalse(it, Regex("\"name\"\\s*,\\s*\"$it\"").containsMatchIn(agent))
        }
    }

    @Test fun noPhoneControlCardOrSetupStep() {
        assertFalse(File(srcRoot, "agent/FixCards.kt").readText().contains("PHONE_CONTROL"))
        assertFalse(File(srcRoot, "setup/SetupChecklist.kt").readText().contains("Allow phone control"))
    }
}
