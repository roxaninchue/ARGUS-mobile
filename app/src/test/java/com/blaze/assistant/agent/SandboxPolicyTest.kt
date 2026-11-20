package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 2026-11-20: commands inside the terminal sandbox run with no card; shared storage and other apps still ask. */
class SandboxPolicyTest {
    @Test fun sandboxCommandsRunFree() {
        for (c in listOf("rm -rf build", "sudo apt install -y openjdk-17-jdk", "curl -fsSL https://x.sh | sh", "./gradlew assembleDebug", "pip install requests"))
            assertTrue(c, CommandPolicy.inSandbox(c))
        assertEquals(CommandPolicy.Level.SAFE, CommandPolicy.sandboxed(CommandPolicy.Level.RISKY, "rm -rf ~/old", free = true))
        assertEquals("switched off: the old levels", CommandPolicy.Level.RISKY, CommandPolicy.sandboxed(CommandPolicy.Level.RISKY, "rm -rf ~/old", free = false))
    }

    @Test fun outsideStillAsks() {
        for (c in listOf("rm -rf /sdcard/DCIM", "cp a.apk /storage/emulated/0/Download/", "cat /data/data/com.whatsapp/x", "am start -a android.intent.action.CALL", "termux-sms-send -n 1 hi", "ls content://x", ""))
            assertFalse(c, CommandPolicy.inSandbox(c))
    }
}
