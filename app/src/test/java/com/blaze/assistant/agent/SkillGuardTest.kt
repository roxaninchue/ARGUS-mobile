package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.5: tasks done often are counted and flagged for a skill. (The relevance gate went with the skill shelf, 2026-11-20.) */
class SkillGuardTest {
    private val builder = SkillMatch.Cand("android-app-builder", "Build, sign and ship an Android app from a Kotlin project",
        listOf("gradle", "apk", "kotlin"), "1. …", "bundled")
    private val flash = SkillMatch.Cand("study-flashcards", "Make flashcards from notes and quiz the user", listOf("flashcards", "quiz"), "1. …", "bundled")
    private val airtime = SkillMatch.Cand("airtime-topup", "Top up Airtel airtime with the USSD menu", listOf("airtime", "airtel"), "1. …", "learned")
    private val all = listOf(builder, flash, airtime)

    /** 2026-11-20 skill shelf: there is no relevance gate any more; the model picks from SkillShelf. */
    @Test fun noGateLeft() {
        val gone = SkillGuard::class.java.declaredMethods.map { it.name }.filter { it in setOf("gate", "relevant", "strongHit") }
        assertTrue(gone.toString(), gone.isEmpty())
        assertTrue(all.size == 3)
    }

    @Test fun frequentTasksAreCountedAndFlagged() {
        var tasks = emptyList<SkillGuard.Freq.Task>()
        val sig = Learn.signature("check my airtel balance", listOf("open_app", "tap", "read_screen"))
        repeat(2) { tasks = SkillGuard.Freq.record(tasks, sig, "check my airtel balance", 1000L + it).first }
        val (after, t) = SkillGuard.Freq.record(tasks, Learn.signature("check airtel balance please", listOf("open_app", "tap", "read_screen")), "check airtel balance please", 2000)
        assertEquals(1, after.size); assertEquals(3, t.count)
        assertTrue(SkillGuard.Freq.needsSkill(t))
        assertTrue(SkillGuard.Freq.promptLine(after).contains("×3"))
        val skilled = SkillGuard.Freq.markSkilled(after, t.sig)
        assertFalse(SkillGuard.Freq.needsSkill(skilled.first()))
        assertEquals("", SkillGuard.Freq.promptLine(skilled))
        assertEquals(skilled, SkillGuard.Freq.fromJson(SkillGuard.Freq.toJson(skilled)))
        // An unrelated task is its own row.
        val (two, _) = SkillGuard.Freq.record(skilled, Learn.signature("send the weekly report email", listOf("gmail_send")), "send the weekly report email", 3000)
        assertEquals(2, two.size)
    }

    // 0.53.3 (owner screenshot 23:36): a product name never pulls a specialist in (unnamed is still used by SpecialistMatch).
    private val lecture = SkillMatch.Cand("lecture-notes", "Turn a lecture recording into clean notes, a short summary and key terms",
        listOf("student", "notes", "transcription", "summary", "lectures"), "1. …", "bundled")

    @Test fun productNamesPickNoSkill() {
        val q = "Please look for official benchmarks for Dots 3 Note Preview model"
        assertEquals("Please look for official benchmarks for model", SkillGuard.unnamed(q))
        assertEquals("Make Notes", SkillGuard.unnamed("Make Notes"))
    }
}
