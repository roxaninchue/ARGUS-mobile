package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.46.3 (Rocky 18:20): the on-phone brain was never branded to users. Owner 16 Nov 2026: that brain is removed, so
 * OpenCode is neither named in a user-facing string ([UiStrings], the agent files that write chat lines) nor in the
 * changelog, and the notices no longer list it (nothing downloads it any more).
 */
class NoOpenCodeNameTest {
    private val name = Regex("(?i)\\bopen-?code\\b")

    private fun main(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun repoFile(rel: String): File = listOf(File(rel), File("../$rel")).first { it.exists() }

    /** Agent files whose strings reach the user (status lines, labels, prompts, errors, chat lines). */
    private val agentFiles = listOf("BrainOrder.kt", "SelfControl.kt", "PlainError.kt", "Friendly.kt", "Ack.kt")

    @Test fun userStringsNeverNameOpenCode() {
        val hits = UiStrings.literals().filter { (_, s) -> name.containsMatchIn(s) }.map { "${it.first}: ${it.second}" } +
            agentFiles.map { File(main(), "java/com/blaze/assistant/agent/$it") }.filter { it.isFile }
                .flatMap { f -> UiStrings.kotlinStrings(f.readText()).filter { name.containsMatchIn(it) }.map { "${f.name}: $it" } }
        assertEquals("OpenCode named in user-facing strings:\n" + hits.joinToString("\n"), 0, hits.size)
    }

    @Test fun changelogAndNoticesNoLongerNameIt() {
        assertTrue("changelog names OpenCode", !name.containsMatchIn(repoFile("CHANGELOG.md").readText()))
        assertFalse("notices still list OpenCode", name.containsMatchIn(repoFile("THIRD_PARTY_NOTICES.md").readText()))
        assertFalse(File(main(), "java/com/blaze/assistant/agent/BackupBrain.kt").exists())
    }

    @Test fun brainLabels() {
        assertEquals("Nous reserve", BrainOrder.label(BackupRoute.Step.RESERVE))
        assertEquals("${BrandName.NAME} Cloud", BrainOrder.label(BackupRoute.Step.CLOUD))
    }
}
