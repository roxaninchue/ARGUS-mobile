package com.argus.assistant.agent


import com.argus.assistant.BrandName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FixCardsTest {
    @Test fun matchesMissingAccess() {
        assertEquals(FixCards.Kind.NOTIFICATIONS, FixCards.kindOf("Notification access is off. Ask the user to turn it on in ${BrandName.NAME} › Settings."))
        assertEquals(FixCards.Kind.PHONE_CONTROL, FixCards.kindOf("Phone control is off, so I can't take a screenshot."))
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
}
