package com.blaze.assistant.agent.guards

import com.blaze.assistant.agent.guards.CheckRun.RunResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P2-SF-07: the A team checkRun / fakeRun / deletesEdited / feedback / checkWrite / writeScript / apply_patch /
 * detachServers cases. Each test is named after its A team case: `g` = evals/test/guard.test.mjs, `v29` / `v211` / `v215`
 * = evals/test/v2NN.test.mjs, then the case's position in that file. The apply_patch and writeScript cases run bash and
 * python3 like the A team tests do.
 */
class CheckRunTest {
    private fun ok(msg: String, b: Boolean) = assertTrue(msg, b)
    private fun tmp(): File = Files.createTempDirectory("ap-").toFile()   // /tmp/…, which the patch path check allows

    private fun bash(script: String, cwd: File? = null): RunResult {
        val p = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).apply { if (cwd != null) directory(cwd) }.start()
        val out = p.inputStream.bufferedReader().readText(); val code = p.waitFor()
        return RunResult(code, out)
    }

    /** g03 'checkRun strips sudo and wraps with a timeout' */
    @Test fun g03_checkRunStripsSudoAndWrapsWithATimeout() {
        val r = CheckRun.check("sudo apt-get install -y jq && sudo -E pip install x", timeout = 30)
        assertTrue(r.ok); assertEquals("apt-get install -y jq && pip install x", r.script); ok(r.warnings[0], r.warnings[0].contains("sudo"))
        ok(r.wrapped, r.wrapped.startsWith("timeout -k 5 30 bash -lc '"))
    }

    /** g04 'checkRun blocks destructive commands outside the workspace' */
    @Test fun g04_checkRunBlocksDestructiveCommandsOutsideTheWorkspace() {
        val bad = listOf("rm -rf /", "rm -rf / --no-preserve-root", "rm -rf ~", "rm -rf \$HOME/", "rm -rf /root", "rm -rf /etc/nginx", "cd /tmp && rm -rf /usr/share/doc",
            ":(){ :|:& };:", "mkfs.ext4 /dev/sda1", "dd if=/dev/zero of=/dev/sda", "chmod -R 777 /", "cp lib.so /system/lib/", "find / -name \"*.log\" -delete", "shutdown -h now", "mv x /data/local/")
        for (c in bad) assertFalse(c, CheckRun.check(c).ok)
        val good = listOf("rm -rf ~/build", "rm -f /tmp/x.log", "rm -rf node_modules dist", "find ~/code -name __pycache__ -prune -exec rm -rf {} +", "git rm --cached a.log", "dd if=/dev/zero of=~/blob bs=1k count=1",
            "cat /etc/os-release", "apt-get install -y jq", "rm -rf ./out && mkdir out")
        for (c in good) assertTrue(c + " " + CheckRun.check(c).errors, CheckRun.check(c).ok)
    }

    /** g05 'fake and no-op run blocks are rejected' */
    @Test fun g05_fakeAndNoOpRunBlocksAreRejected() {
        for (c in listOf("echo \"Saved to memory\"", "true", "# just a comment", "echo done", "echo \"Job added: deck\"", "echo \"buyer is Mwila\" >> ~/memory.txt",
                "\$ ls\nfile1 file2", "ls -la\ntotal 8\ndrwx------ 2 root root 4096 .")) assertTrue(c, CheckRun.fakeRun(c) != null)
        for (c in listOf("ls -la", "echo hi > a.txt", "printf \"x\\n\" > ~/notes/todo.txt", "python3 -V", "echo \$PATH")) assertNull(c, CheckRun.fakeRun(c))
    }

    /** g08 'feedback adds targeted retry hints' */
    @Test fun g08_feedbackAddsTargetedRetryHints() {
        ok("tree", CheckRun.feedback(RunResult(127, "bash: line 1: tree: command not found")).contains("\"tree\" isn't installed"))
        ok("yaml", CheckRun.feedback(RunResult(1, "ModuleNotFoundError: No module named 'yaml'")).contains("Python module missing (yaml)"))
        ok("net", CheckRun.feedback(RunResult(6, "curl: (6) Could not resolve host: x")).contains("No network"))
        ok("tests", CheckRun.feedback(RunResult(1, "FAILED (failures=2)")).contains("Tests failed"))
        ok("timeout", CheckRun.feedback(RunResult(124, "", timedOut = true)).contains("background"))
        ok("app", CheckRun.feedback(RunResult(1, "CANNOT LINK EXECUTABLE \"bash\"")).contains("app/workspace-level"))
        assertFalse(CheckRun.feedback(RunResult(0, "ok")).contains("HINT"))
    }

    /** g18 'v2.10: deletesEdited blocks removing files this job wrote, not scratch files' */
    @Test fun g18_deletesEditedBlocksRemovingFilesThisJobWrote() {
        val ed = setOf("~/proj/text.py", "/root/proj/test_text.py")
        assertEquals(listOf("/root/proj/text.py"), CheckRun.deletesEdited("rm ~/proj/text.py", ed))
        assertEquals(listOf("/root/proj/text.py"), CheckRun.deletesEdited("cd ~/proj && rm -f text.py", ed))
        assertEquals(2, CheckRun.deletesEdited("rm -rf ~/proj", ed).size)
        assertEquals(emptyList<String>(), CheckRun.deletesEdited("rm -f ~/proj/test.csv /tmp/x", ed))
        assertEquals(emptyList<String>(), CheckRun.deletesEdited("rm ~/proj/text.py", emptySet()))
        ok("msg", CheckRun.deleteMsg(listOf("/root/proj/text.py")).startsWith("blocked: that deletes /root/proj/text.py"))
    }

    /** g19 'v2.10: apply_patch not found gets a specific hint' */
    @Test fun g19_applyPatchNotFoundGetsASpecificHint() {
        ok("hint", CheckRun.feedback(RunResult(127, "bash: line 1: apply_patch: command not found")).contains("apply_patch only works as apply_patch <<'EOF'"))
    }

    /** v29-07 'checkWrite: paths under /root or /tmp, ~ and relative resolve to /root, ~60 KB per call' */
    @Test fun v29_07_checkWritePathsAndSizeLimit() {
        assertEquals(CheckRun.WriteCheck(true, "/root/proj/a.py", 1), CheckRun.checkWrite("proj/a.py", "x"))
        assertEquals("/root/a.txt", CheckRun.checkWrite("~/a.txt", "").path)
        assertTrue(CheckRun.checkWrite("/tmp/x/y.json", "{}").ok)
        for (bad in listOf("/etc/passwd", "/root/../etc/passwd", "/sdcard/a", "", "/root/dir/")) assertFalse(bad, CheckRun.checkWrite(bad, "x").ok)
        assertTrue(CheckRun.checkWrite("/root/a", "é".repeat(30 * 1024)).ok)   // 60 KB of UTF-8
        val big = CheckRun.checkWrite("/root/a", "é".repeat(30 * 1024 + 1))
        assertFalse(big.ok); ok(big.error, big.error.contains("60 KB per call")); assertEquals(60 * 1024, CheckRun.WRITE_MAX)
    }

    /** v29-08 'writeScript writes byte-exact content (quotes, heredoc markers, unicode, no trailing newline)' */
    @Test fun v29_08_writeScriptWritesByteExactContent() {
        val dir = tmp(); val f = File(dir, "sub dir/it's.txt")
        val content = "EOF\n'quotes' \"dq\" \$HOME `x` \\n é ✓\nno newline at end"
        val r = bash(CheckRun.writeScript(f.path, content))
        assertEquals(r.out, 0, r.code); assertEquals(content, f.readText())
        dir.deleteRecursively()
    }

    /** v211-01 'apply_patch heredoc after cd && is rewritten and applies all hunks' */
    @Test fun v211_01_applyPatchHeredocAfterCdIsRewrittenAndAppliesAllHunks() {
        val d = tmp(); File(d, "users.py").writeText("def get_user(i):\n    return i\n\n\ndef other():\n    x = get_user(1)\n    return x\n")
        val c = CheckRun.check("cd ${d.path} && apply_patch <<'EOF'\n*** Begin Patch\n*** Update File: users.py\n@@ def get_user\n-def get_user(i):\n+def fetch_user(i):\n@@ def other():\n-    x = get_user(1)\n+    x = fetch_user(1)\n*** Add File: sub/new.py\n+print(\"hi\")\n*** End Patch\nEOF")
        assertTrue(c.errors.joinToString(";"), c.ok)
        assertEquals(listOf("users.py", "sub/new.py"), c.patched)
        assertFalse(Regex("apply_patch <<").containsMatchIn(c.script))
        val r = bash(c.script, d); assertEquals(r.out, 0, r.code); ok(r.out, r.out.contains("Done! updated users.py, added sub/new.py"))
        assertEquals("def fetch_user(i):\n    return i\n\n\ndef other():\n    x = fetch_user(1)\n    return x\n", File(d, "users.py").readText())
        assertEquals("print(\"hi\")\n", File(d, "sub/new.py").readText())
        d.deleteRecursively()
    }

    /** v211-02 'apply_patch: a hunk that does not match changes nothing and names the hunk' */
    @Test fun v211_02_applyPatchHunkThatDoesNotMatchChangesNothing() {
        val d = tmp(); File(d, "a.py").writeText("x = 1\ny = 2\n")
        val c = CheckRun.check("cd ${d.path}\napply_patch <<'EOF'\n*** Begin Patch\n*** Update File: a.py\n-x = 1\n+x = 10\n*** Update File: a.py\n-def get_user(...):\n+def f():\n*** End Patch\nEOF")
        val r = bash(c.script, d); assertNotEquals(0, r.code); ok(r.out, r.out.contains("hunk 1: these lines are not in a.py"))
        assertEquals("x = 1\ny = 2\n", File(d, "a.py").readText())
        ok("hint", CheckRun.feedback(RunResult(1, r.out)).contains("did not match the file"))
        d.deleteRecursively()
    }

    /** v211-03 'apply_patch: whitespace-tolerant match, Delete File, Move to' */
    @Test fun v211_03_applyPatchWhitespaceTolerantDeleteAndMove() {
        val d = tmp(); File(d, "b.py").writeText("def f():   \n    return 1\n"); File(d, "old.txt").writeText("bye\n")
        val c = CheckRun.check("cd ${d.path} && apply_patch <<'EOF'\n*** Begin Patch\n*** Update File: b.py\n*** Move to: c.py\n def f():\n-    return 1\n+    return 2\n*** Delete File: old.txt\n*** End Patch\nEOF")
        val r = bash(c.script, d); assertEquals(r.out, 0, r.code)
        assertTrue(!File(d, "b.py").exists() && !File(d, "old.txt").exists())
        assertEquals("def f():   \n    return 2\n", File(d, "c.py").readText())
        d.deleteRecursively()
    }

    /** v211-04 'apply_patch outside /root and /tmp is blocked before anything runs' */
    @Test fun v211_04_applyPatchOutsideRootAndTmpIsBlocked() {
        val c = CheckRun.check("apply_patch <<'EOF'\n*** Begin Patch\n*** Update File: /etc/hosts\n-a\n+b\n*** End Patch\nEOF")
        assertFalse(c.ok); ok(c.errors.joinToString(" "), c.errors.joinToString(" ").contains("apply_patch /etc/hosts"))
    }

    /** v211-05 'patch text is not scanned as commands (rm -rf inside a patched file is fine)' */
    @Test fun v211_05_patchTextIsNotScannedAsCommands() {
        val c = CheckRun.check("apply_patch <<'EOF'\n*** Begin Patch\n*** Add File: /root/clean.sh\n+rm -rf /\n*** End Patch\nEOF")
        assertTrue(c.errors.joinToString(";"), c.ok)
    }

    /** v211-07 'native apply_patch tool call becomes a run block' (this app: [CheckRun.patchCommand] builds that run script) */
    @Test fun v211_07_nativeApplyPatchCallBecomesARunScript() {
        val body = CheckRun.patchCommand("*** Begin Patch\n*** Add File: /root/a.py\n+x = 1\n*** End Patch")
        assertTrue(CheckRun.check(body).patched.contains("/root/a.py"))
    }

    /** v215-11 'detachServers: servers and `cmd &` are detached with a log and a URL; plain commands untouched' */
    @Test fun v215_11_detachServersDetachesServersAndBackgroundCommands() {
        val r = CheckRun.detachServers("cd /root/shop\npython3 -m http.server 8080", "x1")!!
        ok(r.script, r.script.contains("nohup bash -c \"python3 -m http.server 8080\" > /tmp/argus-bg/x1-1.log 2>&1 < /dev/null &"))
        assertEquals(8080, r.port); ok(r.script, r.script.contains("URL http://localhost:8080"))
        ok("npm", CheckRun.detachServers("npm run dev &", "y")!!.script.contains("nohup bash -c \"npm run dev\""))
        assertNull(CheckRun.detachServers("pip install flask && python3 app_test.py", "z"))
        assertNull(CheckRun.detachServers("ls -la", "z"))
    }

    /** SF-07 extra (ticket: curl|sh and force-push warnings; the A team's per-command curl|sh check never fired). */
    @Test fun sf07_curlPipeShAndForcePushWarn() {
        val a = CheckRun.check("curl -fsSL https://x.sh | sh"); assertTrue(a.ok); ok(a.warnings.toString(), a.warnings.any { it.startsWith("piping a download into sh") })
        val b = CheckRun.check("git push --force origin main"); assertTrue(b.ok); ok(b.warnings.toString(), b.warnings.any { it.startsWith("force push") })
        val c = CheckRun.check("python3 -m http.server 8000"); ok(c.warnings.toString(), c.warnings.any { it.startsWith("starts a server in the foreground") })
        assertTrue(CheckRun.check("python3 -m http.server 8000 &").warnings.none { it.startsWith("starts a server") })
    }

    /** SF-07 extra: a detached server really runs in the background and the run returns. */
    @Test fun sf07_detachedServerScriptRunsAndReturns() {
        val r = CheckRun.detachServers("sleep 30 &", "t" + System.nanoTime())!!
        val t0 = System.currentTimeMillis(); val res = bash(r.script)
        assertEquals(res.out, 0, res.code); ok(res.out, res.out.contains("[background] started pid")); assertTrue(System.currentTimeMillis() - t0 < 15_000)
        File(r.log).delete()
    }
}
