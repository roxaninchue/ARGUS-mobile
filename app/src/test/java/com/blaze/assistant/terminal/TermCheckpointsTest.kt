package com.blaze.assistant.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Which folders a terminal checkpoint covers, and the tool the model sees. */
class TermCheckpointsTest {
    @Test fun scopesCoverHomeAndEachLinuxsOwnFolders() {
        val tmp = Files.createTempDirectory("tcp").toFile()
        val home = File(tmp, "home").apply { mkdirs() }
        val alpine = File(tmp, "linux/alpine").apply { listOf("root/phone", "etc", "usr/local", "bin").forEach { File(this, it).mkdirs() } }
        val user = TermCheckpoints.scopesFor(home, mapOf("alpine" to alpine), system = false)
        assertEquals(listOf("home", "alpine-root", "alpine-etc", "alpine-usr-local"), user.map { it.name })
        assertTrue("the phone home inside Linux is the home scope, not twice", "phone" in user.first { it.name == "alpine-root" }.excludes)
        assertTrue(user.all { "node_modules" in it.skipNames && ".gradle" in it.skipNames })
        val sys = TermCheckpoints.scopesFor(home, mapOf("alpine" to alpine), system = true)
        assertEquals(listOf("home", "alpine-system"), sys.map { it.name })
        assertTrue(listOf("proc", "sys", "dev", "root/phone").all { it in sys[1].excludes })
    }

    @Test fun toolIsWellFormed() {
        val f = TermCheckpoints.tool().getJSONObject("function")
        assertEquals("terminal_checkpoint", f.getString("name"))
        val actions = f.getJSONObject("parameters").getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
        assertEquals(listOf("save", "list", "changes", "rollback", "delete"), (0 until actions.length()).map { actions.getString(it) })
        assertTrue("run_shell" in TermCheckpoints.MUTATING && "file_write" in TermCheckpoints.MUTATING && "file_read" !in TermCheckpoints.MUTATING)
    }

    @Test fun beforeIsQuietWhenNotReady() = kotlinx.coroutines.runBlocking {
        assertEquals("", TermCheckpoints.before("file_read", JSONObject().put("path", "x")))
    }
}
