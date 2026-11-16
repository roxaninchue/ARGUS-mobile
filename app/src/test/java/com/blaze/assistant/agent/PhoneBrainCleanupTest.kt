package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Owner 16 Nov 2026: the on-device backup brain is removed; its leftovers go once after the update. */
class PhoneBrainCleanupTest {
    @Test fun leftoversAreOnlyTheBrainsOwn() {
        val base = kotlin.io.path.createTempDirectory("linux").toFile()
        val rootfs = File(base, "alpine").apply { mkdirs() }
        File(rootfs, "opt/blaze-backup").mkdirs(); File(rootfs, "root/.blaze-backup").mkdirs(); File(rootfs, "root/notes.txt").writeText("mine")
        File(base, ".dl-backup-1.19.4.tar.gz").writeText("x"); File(base, ".dl-alpine.tar.xz").writeText("x")
        val l = PhoneBrainCleanup.leftovers(base, rootfs).map { it.relativeTo(base).path }.toSet()
        assertEquals(setOf("alpine/opt/blaze-backup", "alpine/root/.blaze-backup", ".dl-backup-1.19.4.tar.gz"), l)
        assertFalse(l.any { "notes" in it || "alpine.tar" in it })
        base.deleteRecursively()
    }

    @Test fun prefsAndWiring() {
        assertTrue("backup_brain" in PhoneBrainCleanup.LEGACY_PREFS && "backup_brain_mobile_data" in PhoneBrainCleanup.LEGACY_PREFS)
        val root = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
        val setup = File(root, "com/blaze/assistant/setup/SetupPlan.kt").readText()
        assertFalse("On-device backup brain" in setup)
        assertFalse("BackupBrain.askMobile" in File(root, "com/blaze/assistant/ui/ChatScreen.kt").readText())
    }
}
