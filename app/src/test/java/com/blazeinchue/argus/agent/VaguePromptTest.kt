package com.blazeinchue.argus.agent


import com.blazeinchue.argus.BrandName
import com.blazeinchue.argus.agent.TaskMemory.Rec
import com.blazeinchue.argus.agent.TaskMemory.State
import com.blazeinchue.argus.agent.TaskMemory.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52.1 hard test of understanding (owner: "memory of tasks and old context"): 40+ vague / elliptical follow-ups, each
 * against a prior-conversation fixture (the chat's earlier tasks, with distractors that are newer than the right one).
 * Asserts the right task is retrieved, and reports the pass rate of the old behaviour ("before": GoalPin only carries the
 * previous request across a short follow-up word, so the newest task, and nothing for anything else) against
 * [TaskMemory.resolve] ("after").
 */
class VaguePromptTest {
    private val now = 10_000_000_000L
    private fun min(m: Int) = now - m * 60_000L

    data class Case(val scenario: List<Rec>, val text: String, val expect: String?)

    // ---- fixtures: each scenario is one chat's task records (what the conversation did before) ----
    private val gitlab = Rec("a1", "c", "Check my GitLab pipeline for ArgusMobile", State.WAITING_USER, Surface.BROWSER, needsSignIn = "gitlab.com", updated = min(10),
        findings = listOf("GitLab asked for a sign-in in the Browser"), next = "open the pipelines page once signed in")
    private val weather = Rec("a2", "c", "What's the weather in Lusaka today", State.DONE, updated = min(2), findings = listOf("27°C, sunny"))
    private val sA = listOf(gitlab, weather)

    private val song = Rec("b1", "c", "Download the song Blinding Lights by The Weeknd", State.FAILED, updated = min(30), findings = listOf("The download source timed out"))
    private val flights = Rec("b2", "c", "Find cheap flights from Lusaka to Cape Town in December", State.DONE, Surface.BROWSER, updated = min(20),
        findings = listOf("Cheapest: Airlink K4,200 on 12 Dec", "Proflight K5,100 via Johannesburg"))
    private val alarm = Rec("b3", "c", "Set an alarm for 6am", State.DONE, updated = min(5))
    private val sB = listOf(song, flights, alarm)

    private val report = Rec("c1", "c", "Write a research report on solar panel prices in Zambia", State.STOPPED, updated = min(60),
        findings = listOf("Five suppliers compared"), next = "stitch the findings into the report")
    private val joke = Rec("c2", "c", "Tell me a joke", State.DONE, updated = min(50))
    private val sC = listOf(report, joke)

    private val parcel = Rec("d1", "c", "Track my DHL package 123456", State.DONE, updated = min(180), findings = listOf("In transit at the Johannesburg hub"))
    private val article = Rec("d2", "c", "Summarise this article about AI chips", State.DONE, updated = min(60))
    private val sD = listOf(parcel, article)

    private val playlist = Rec("e1", "c", "Make a playlist of 90s hip hop", State.DONE, updated = min(2 * 1440))
    private val translate = Rec("e2", "c", "Translate hello into French", State.DONE, updated = min(1440))
    private val sE = listOf(playlist, translate)

    private val laptops = Rec("f1", "c", "Find me three laptops under K15000", State.DONE, updated = min(90),
        findings = listOf("Lenovo IdeaPad 3 K12,999", "HP 15s K14,500", "Acer Aspire 5 K13,200"))
    private val table = Rec("f2", "c", "Book a table at Marlin for Friday", State.WAITING_USER, Surface.PHONE, updated = min(30), next = "pick the 19:00 slot")
    private val maths = Rec("f3", "c", "What's 15% of 2300", State.DONE, updated = min(3))
    private val sF = listOf(laptops, table, maths)

    private val invoice = Rec("h1", "c", "Check Gmail for the invoice from Zesco", State.DONE, updated = min(10 * 1440), findings = listOf("Invoice K820 due 30 Nov"))
    private val rename = Rec("h2", "c", "Rename my holiday photos by date", State.DONE, Surface.SCREEN, updated = min(1440))
    private val sH = listOf(invoice, rename)

    private val oldSongs = Rec("i1", "c", "Download the songs I had in my previous install", State.ACTIVE, updated = min(4),
        findings = listOf("From memory: Mwansa by Yo Maps; Bombastic by Macky 2"))
    private val news = Rec("i2", "c", "Give me today's top news", State.DONE, updated = min(1))
    private val sI = listOf(oldSongs, news)

    private val apk = Rec("j1", "c", "Build the ${BrandName.NAME} APK in the terminal", State.FAILED, Surface.TERMINAL, updated = min(15),
        findings = listOf("Gradle failed: missing SDK platform 34"), next = "install platform 34 and rebuild")
    private val email = Rec("j2", "c", "Draft an email to Ana about Friday", State.DONE, updated = min(8))
    private val sJ = listOf(apk, email)

    val cases = listOf(
        // A: the owner's case — signed in on the Browser; a newer unrelated task sits on top
        Case(sA, "I signed you in", "a1"),
        Case(sA, "ok I logged in, continue", "a1"),
        Case(sA, "done logging in", "a1"),
        Case(sA, "I've entered the code", "a1"),
        Case(sA, "logged in now", "a1"),
        Case(sA, "continue", "a1"),
        Case(sA, "check the pipeline now", "a1"),
        // B: a failed download, a finished flight search, a newest alarm
        Case(sB, "retry it", "b1"),
        Case(sB, "try the song again", "b1"),
        Case(sB, "give it another go", "b1"),
        Case(sB, "same as before but for Johannesburg", "b2"),
        Case(sB, "do the same for 7am", "b3"),
        Case(sB, "show me that one", "b2"),
        Case(sB, "book the Airlink one", "b2"),
        Case(sB, "set a timer for 5 minutes", null),
        // C: a stopped report, a joke on top
        Case(sC, "carry on with the report", "c1"),
        Case(sC, "where were we", "c1"),
        Case(sC, "finish it", "c1"),
        Case(sC, "how's it going?", "c1"),
        Case(sC, "keep going", "c1"),
        // D: a parcel to check, an article on top
        Case(sD, "check now", "d1"),
        Case(sD, "any update?", "d1"),
        Case(sD, "has it arrived?", "d1"),
        Case(sD, "check the DHL one again", "d1"),
        Case(sD, "what's the capital of France", null),
        // E: again / same-for
        Case(sE, "do it again", "e2"),
        Case(sE, "same as last time but for Spanish", "e2"),
        Case(sE, "same thing for 80s rock", "e1"),
        Case(sE, "make the playlist again", "e1"),
        // F: that one / names
        Case(sF, "open the HP one", "f1"),
        Case(sF, "book the Marlin one", "f2"),
        Case(sF, "I logged in, go ahead", "f2"),
        Case(sF, "continue with the booking", "f2"),
        Case(sF, "the Lenovo, is it still K12,999?", "f1"),
        Case(sF, "tell me about black holes", null),
        // H: an old task named by its words
        Case(sH, "check the Zesco invoice again", "h1"),
        Case(sH, "do the photos again", "h2"),
        // I: songs from memory (the song-loop bug)
        Case(sI, "download that one by Yo Maps", "i1"),
        Case(sI, "get Bombastic too", "i1"),
        Case(sI, "continue the downloads", "i1"),
        // J: a failed terminal build, a newer email on top
        Case(sJ, "retry the build", "j1"),
        Case(sJ, "try again", "j1"),
        Case(sJ, "rerun it", "j1"),
        Case(sJ, "did it work?", "j1"),
    )

    /** The old behaviour: a short follow-up word ("retry", "continue", "again") carried the newest task; nothing else did. */
    private fun before(c: Case): String? = if (GoalPin.isFollowUp(c.text)) c.scenario.maxByOrNull { it.updated }?.id else null

    private fun after(c: Case): String? = TaskMemory.resolve(c.text, c.scenario, now).rec?.id

    @Test fun atLeastFortyCases() = assertTrue("cases: ${cases.size}", cases.size >= 40)

    @Test fun passRateBeforeAndAfter() {
        val b = cases.count { before(it) == it.expect }
        val fails = cases.filter { after(it) != it.expect }
        val a = cases.size - fails.size
        val line = "VAGUE_PROMPTS n=${cases.size} before=$b (${100 * b / cases.size}%) after=$a (${100 * a / cases.size}%)"
        println(line)
        fails.forEach { println("  MISS \u201c${it.text}\u201d expected ${it.expect} got ${after(it)} (${TaskMemory.kind(it.text)})") }
        System.getProperty("vague.out")?.let { java.io.File(it).writeText(line + "\n" + fails.joinToString("\n") { f -> "MISS ${f.text} -> ${after(f)} (want ${f.expect})" }) }
        assertTrue(line, a > b)
        assertTrue(line, fails.isEmpty())
    }

    @Test fun signInContinuesOnTheSameSurface() {
        val r = TaskMemory.resolve("I signed you in", sA, now)
        val block = TaskMemory.block(r)
        assertEquals("a1", r.rec?.id)
        assertTrue(block, block.contains("in-app Browser"))
        assertTrue(block, block.contains("never switch"))
        // and the guard stops a switch to Argus Phone for that task
        assertTrue(TaskMemory.surfaceGuard("phone_open", r.rec, "I signed you in", now)!!.startsWith("Not run"))
        assertEquals(null, TaskMemory.surfaceGuard("browser_read", r.rec, "I signed you in", now))
        assertEquals(null, TaskMemory.surfaceGuard("phone_open", r.rec, "use ${BrandName.NAME} Phone instead", now))
    }

    @Test fun sameForCarriesTheNewSubject() {
        val r = TaskMemory.resolve("same as before but for Johannesburg", sB, now)
        assertEquals("Johannesburg", r.substitution)
        assertTrue(TaskMemory.block(r).contains("Johannesburg"))
    }
}
