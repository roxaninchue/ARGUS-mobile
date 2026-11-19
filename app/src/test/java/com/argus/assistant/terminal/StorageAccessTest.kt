package com.argus.assistant.terminal

import com.argus.assistant.agent.FixCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessTest {
    @Test fun sharedStorageCommandsAreSeen() {
        assertTrue(StorageAccess.touchesShared("find /storage/emulated/0/ -name \"*.apk\" -o -name \"*.zip\""))
        assertTrue(StorageAccess.touchesShared("find / -name \"blaze-2705.zip\" 2>/dev/null"))
        assertTrue(StorageAccess.touchesShared("ls -la /sdcard/Download"))
        assertTrue(StorageAccess.touchesShared("/storage/emulated/0/Download/Andronix"))
        assertFalse(StorageAccess.touchesShared("ls -la ~/projects"))
        assertFalse(StorageAccess.touchesShared("find . -name '*.kt'"))
        assertFalse(StorageAccess.touchesShared("/data/user/0/com.argus.assistant/files/home"))
    }

    @Test fun noteOnlyWhenAccessIsOff() {
        val cmd = "find /storage/emulated/0/ -name '*.zip'"
        assertEquals("", StorageAccess.annotate(cmd, "", hasAccess = true))
        assertEquals("x", StorageAccess.annotate("ls ~", "x", hasAccess = false))
        val off = StorageAccess.annotate(cmd, "exit 0", hasAccess = false)
        assertTrue(off.startsWith(StorageAccess.NOTE))
        assertEquals(off, StorageAccess.annotate(cmd, off, hasAccess = false))   // never twice
    }

    @Test fun noteRaisesTheAllFilesCardNotTheAppPage() {
        val r = StorageAccess.annotate("find / -name x", "find: /storage/emulated/0/Android/data: Permission denied", hasAccess = false)
        assertEquals(FixCards.Kind.ALL_FILES, FixCards.kindOf(r))
        assertEquals("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", FixCards.Kind.ALL_FILES.action)
    }
}
