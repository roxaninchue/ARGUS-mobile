package com.blaze.assistant.agent.guards

import com.blaze.assistant.agent.guards.CheckRun.RunResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P2-SF-07: the A team CompletenessGate / StepGuard / checkScript / PROMPT cases. Named after the A team case:
 * `g` = evals/test/guard.test.mjs, `v29` / `v211` = evals/test/v2NN.test.mjs, then the case's position in that file.
 */
class CompletenessTest {
    private fun ok(msg: String, b: Boolean) = assertTrue(msg, b)
    private fun r(code: Int, out: String = "", timedOut: Boolean = false) = RunResult(code, out, timedOut)
    private fun bashStatus(vararg args: String): Int = ProcessBuilder("bash", *args).redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() }

    /** g07 'StepGuard stops identical command+result loops and caps steps' (the 3rd repeat stops) */
    @Test fun g07_stepGuardStopsIdenticalLoopsAndCapsSteps() {
        val g = StepGuard(maxSteps = 4, maxRepeats = 2); val res = r(1, "error: x")
        assertFalse(g.after("make", res).stop)
        ok("2nd", g.after("make", res).hint.contains("already ran"))
        assertTrue(g.after("make", res).stop)
        val h = StepGuard(maxSteps = 2); h.before("a"); h.before("b"); assertFalse(h.before("c").ok)
        val k = StepGuard(); k.after("a", r(1, "1")); k.after("b", r(1, "2")); ok("3 fails", k.after("c", r(1, "3")).hint.contains("Three failures"))
    }

    /** g09 'CompletenessGate: no "done" on code until a passing end-to-end check after the last edit' */
    @Test fun g09_completenessGateNoDoneUntilAPassingCheckAfterTheLastEdit() {
        val g = CompletenessGate()
        g.onRun("cat > app.py <<'EOF'\nprint(1)\nEOF", r(0))
        var f = g.canFinish("Done, it works."); assertFalse(f.ok); ok(f.message, f.message.contains("run the build, the tests and the demo")); assertTrue(f.message.length < 500)
        g.onRun("python3 -m unittest -q", r(1, "FAILED (failures=1)"))
        f = g.canFinish("Done."); assertFalse(f.ok); ok(f.message, f.message.contains("scope down"))
        g.onRun("python3 -m unittest -q && python3 app.py", r(0, "OK\n1"))
        assertTrue(g.canFinish("Done.").ok)
        val e = CompletenessGate()                                      // edit after a passing check: must re-verify
        e.onRun("cat > app.py <<'EOF'\nprint(1)\nEOF", r(0)); e.onRun("python3 app.py", r(0, "1")); assertTrue(e.canFinish("Done.").ok)
        e.onRun("sed -i 's/1/2/' app.py", r(0)); val r2 = e.canFinish("Done."); assertFalse(r2.ok); assertTrue(r2.autoCheck)
        e.canFinish("Done."); assertTrue("nudges at most twice, then lets the turn end (graded as unverified)", e.canFinish("Done.").ok)
        val n = CompletenessGate(); n.onRun("ls -la", r(0)); assertTrue(n.canFinish("Done.").ok)   // no code touched
        val s = CompletenessGate(); s.onRun("cat > a.py <<'E'\nx\nE", r(0)); s.onRun("python3 a.py", r(0, "TODO: real logic")); assertFalse(s.lastVerifyOk)
        assertTrue(CompletenessGate().needsScopeNote("All done.", true))
        assertFalse(CompletenessGate().needsScopeNote("Works. I cut the --pending flag.", true))
    }

    /** g10 'checkScript is valid bash and fails on stubs' */
    @Test fun g10_checkScriptIsValidBashAndFailsOnStubs() {
        assertEquals(0, bashStatus("-n", "-c", Completeness.checkScript("/x")))
        val d = Files.createTempDirectory("gate-").toFile()
        File(d, "a.py").writeText("def f():\n    return 1\n")
        assertEquals(0, bashStatus("-c", Completeness.checkScript(d.path)))
        File(d, "b.py").writeText("def g():\n    # TODO\n    pass\n")
        assertEquals(1, bashStatus("-c", Completeness.checkScript(d.path)))
        File(d, "b.py").writeText("def g(:\n")
        assertEquals(1, bashStatus("-c", Completeness.checkScript(d.path)))
        d.deleteRecursively()
    }

    /** g14 'PROMPT covers tools, completeness and safety' */
    @Test fun g14_promptCoversToolsCompletenessAndSafety() {
        for (k in listOf(Regex("never use sudo", RegexOption.IGNORE_CASE), Regex("stop and wait for the real result", RegexOption.IGNORE_CASE), Regex("run the build, the tests and the demo", RegexOption.IGNORE_CASE),
                Regex("cut scope", RegexOption.IGNORE_CASE), Regex("smaller version that fully works", RegexOption.IGNORE_CASE), Regex("safe alternative", RegexOption.IGNORE_CASE), Regex("look-alikes", RegexOption.IGNORE_CASE)))
            assertTrue(k.pattern, k.containsMatchIn(Completeness.PROMPT))
    }

    /** v29-09 'CompletenessGate sees write_file edits: a later passing test run verifies them, a later write unverifies' */
    @Test fun v29_09_gateSeesFileWriteEdits() {
        val g = CompletenessGate()
        g.onWrite("/root/proj/app.py"); assertTrue(g.codeTouched); assertFalse(g.canFinish("Done.").ok)
        val g2 = CompletenessGate(); g2.onWrite("/root/proj/app.py"); g2.onRun("cd /root/proj && python3 -m pytest -q", r(0, "3 passed"))
        assertTrue(g2.canFinish("Done.").ok)
        g2.onWrite("/root/proj/util.py"); assertFalse(g2.canFinish("Done.").ok)
        val g3 = CompletenessGate(); g3.onWrite("/root/notes.md"); assertFalse(g3.codeTouched)
    }

    /** v29-22 'gate counts stdin test scripts (python3 - <<PY) as a verification run; pytest-missing hint says use unittest' */
    @Test fun v29_22_gateCountsStdinTestScriptsAndPytestHint() {
        val g = CompletenessGate()
        g.onRun("cat > ~/proj/roman.py <<'EOF'\ndef f(): return 1\nEOF", r(0))
        g.onRun("python3 - <<'PY'\nimport unittest\nPY", r(0, "Ran 2 tests\n\nOK"))
        assertTrue(g.canFinish("Done.").ok)
        val g2 = CompletenessGate(); g2.onWrite("/root/a.js"); g2.onRun("cat input.txt | node -", r(0, "ok")); assertTrue(g2.canFinish("Done.").ok)
        ok("unittest", CheckRun.feedback(r(127, "bash: line 1: pytest: command not found")).contains("python3 -m unittest"))
        ok("no install", CheckRun.feedback(r(1, "No module named pytest")).contains("Do not try to install it"))
    }

    /** v211-06 'patched files reach the completeness gate' */
    @Test fun v211_06_patchedFilesReachTheCompletenessGate() {
        val c = CheckRun.check("cd /root/svc && apply_patch <<'EOF'\n*** Begin Patch\n*** Update File: users.py\n-a\n+b\n*** End Patch\nEOF")
        val g = CompletenessGate(); g.onRun(c.script, r(0, "Done!"))
        assertTrue(g.codeTouched); assertFalse(g.canFinish("done").ok)
    }

    /** v211-09 'gate asks for edge-case checks; no-tests hint' */
    @Test fun v211_09_gateAsksForEdgeCaseChecksAndNoTestsHint() {
        val g = CompletenessGate(); g.onWrite("/root/p/x.py")
        ok("edge", g.canFinish("done").message.contains("invalid input and edge cases"))
        ok("no tests", CheckRun.feedback(r(5, "\nRan 0 tests in 0.000s\n\nNO TESTS RAN\n")).contains("No tests were found"))
    }

    /** SF-07 extra: a timed-out or stub-printing check never verifies; a write + run in one script verifies itself. */
    @Test fun sf07_timeoutsAndStubsNeverVerify() {
        val g = CompletenessGate(); g.onWrite("/root/a.py"); g.onRun("python3 a.py", r(0, "", timedOut = true)); assertFalse(g.verified)
        val h = CompletenessGate(); h.onRun("cat > a.py <<'EOF'\nprint(1)\nEOF\npython3 a.py", r(0, "1")); assertTrue(h.codeTouched); assertTrue(h.verified)
    }
}
