package com.argus.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Owner 16 Nov 2026: the on-device brains are removed; their leftovers go once after the update. */
class PhoneBrainCleanupTest {
    @Test fun leftoversAreOnlyTheBrainsOwn() {
        val files = kotlin.io.path.createTempDirectory("files").toFile()
        val base = File(files, "linux").apply { mkdirs() }
        val rootfs = File(base, "alpine").apply { mkdirs() }
        File(files, "offline-brain").mkdirs(); File(files, "notes").mkdirs()
        File(rootfs, "opt/blaze-backup").mkdirs(); File(rootfs, "root/.blaze-backup").mkdirs(); File(rootfs, "root/notes.txt").writeText("mine")
        File(base, ".dl-backup-1.19.4.tar.gz").writeText("x"); File(base, ".dl-alpine.tar.xz").writeText("x")
        val l = PhoneBrainCleanup.leftovers(files, base, rootfs).map { it.relativeTo(files).path }.toSet()
        assertEquals(setOf("linux/alpine/opt/blaze-backup", "linux/alpine/root/.blaze-backup", "linux/.dl-backup-1.19.4.tar.gz", "offline-brain"), l)
        assertFalse(l.any { "notes" in it || "alpine.tar" in it })
        files.deleteRecursively()
    }

    @Test fun prefsAndWiring() {
        assertTrue("backup_brain" in PhoneBrainCleanup.LEGACY_PREFS && "backup_brain_mobile_data" in PhoneBrainCleanup.LEGACY_PREFS && "offline_brain" in PhoneBrainCleanup.LEGACY_PREFS)
        val root = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
        val setup = File(root, "com/argus/assistant/setup/SetupPlan.kt").readText()
        assertFalse("On-device backup brain" in setup)
        assertFalse("BackupBrain.askMobile" in File(root, "com/argus/assistant/ui/ChatScreen.kt").readText())
    }
}
