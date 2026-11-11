package com.blaze.assistant.agent

import com.blaze.assistant.agent.ToolVerify.FileStat
import com.blaze.assistant.agent.guards.CompletenessGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P2-SF-07 (AT-20 verified done_job): A team app/tests/v27.test.js §9 (done_job without a deliverable is rejected; HTML
 * with a broken inline script fails) and the chat.js verifyJob / checkFile rules, named `at-v27-9` and `sf07`.
 */
class ToolVerifyDoneTest {
    /** A fake workspace: files in memory; py/sh checks run the real python3 / bash on a temp copy. */
    private class Ws(val files: Map<String, String>, val dirs: Map<String, Int> = emptyMap()) : ToolVerify.DoneProbes {
        override fun stat(path: String): FileStat? = dirs[path]?.let { FileStat(dir = true, count = it) } ?: files[path]?.let { FileStat(size = it.toByteArray().size.toLong()) }
        override fun read(path: String): String? = files[path]
        override suspend fun exec(cmd: String): Pair<Int, String>? {
            val d = Files.createTempDirectory("ws-").toFile()
            var c = cmd
            for ((p, src) in files) { val f = File(d, p.trimStart('/')); f.parentFile.mkdirs(); f.writeText(src); c = c.replace("'$p'", "'${f.path}'") }
            val pr = ProcessBuilder("bash", "-c", c).redirectErrorStream(true).start(); val out = pr.inputStream.bufferedReader().readText(); val code = pr.waitFor()
            d.deleteRecursively(); return code to out
        }
    }
    private fun probs(title: String, ws: Ws, written: List<String>, attached: List<String> = written, said: Boolean = true, gate: CompletenessGate? = null) =
        runBlocking { ToolVerify.doneProblems(title, "", written, attached, said, gate, ws) }
    private fun check(path: String, src: String) = runBlocking { ToolVerify.checkFile(path, Ws(mapOf(path to src))) }

    /** at-v27-9 'done_job without a deliverable is rejected' */
    @Test fun at_v27_9_doneJobWithoutADeliverableIsRejected() {
        val p = probs("Build the pricing page", Ws(emptyMap()), written = emptyList())
        assertEquals(listOf("no deliverable file was created in this run: make it in the workspace first"), p)
        assertEquals(listOf("you have not delivered anything to the user yet: write the result to them in this chat first"), probs("Greet user", Ws(emptyMap()), emptyList(), said = false))
        assertEquals(emptyList<String>(), probs("Greet user", Ws(emptyMap()), emptyList(), said = true))
    }

    /** at-v27-9b 'HTML with a broken inline script fails the check' */
    @Test fun at_v27_9b_htmlWithABrokenInlineScriptFails() {
        val e = check("/root/bad/index.html", "<html><body><script>function (</script></body></html>")
        assertTrue(e, e.contains("JavaScript error"))
        assertEquals("", check("/root/ok/index.html", "<html><body><script>const a = [1, 2]; function f(x) { return `v\${x + 1}` } f(a[0]); if (/a[/]b/.test('a/b')) {}</script></body></html>"))
        assertEquals("", check("/root/m/index.html", "<html><script type=\"module\">import x from './x.js'</script></html>"))
        assertEquals("", check("/root/j/index.html", "<html><script type=\"application/ld+json\">{\"a\": </script></html>"))
        assertTrue(check("/root/x/page.html", "just words").contains("doesn't look like HTML"))
    }

    /** sf07 file checks: JSON, JS, Python and bash (A team chat.js checkFile). */
    @Test fun sf07_jsonJsPyAndBashChecks() {
        assertEquals("", check("/root/a.json", "{\"a\": [1, 2.5e3, true, null, \"x\\u00e9\"]}"))
        assertTrue(check("/root/b.json", "{a: 1}").contains("is not valid JSON"))
        assertTrue(check("/root/c.json", "{\"a\": 1,}").contains("is not valid JSON"))
        assertEquals("", check("/root/a.js", "const s = 'a\\'b'; /* c */ // d\nconsole.log(s.length / 2)"))
        assertTrue(check("/root/b.js", "function f() {\n  return [1, 2;\n}").contains("JavaScript error"))
        assertEquals("", check("/root/m.js", "import x from './x.js'\nx("))   // modules are not checked here
        assertEquals("", check("/root/a.py", "def f():\n    return 1\n"))
        assertTrue(check("/root/b.py", "def g(:\n").contains("fails its syntax check"))
        assertEquals("", check("/root/a.sh", "#!/bin/bash\nfor i in 1 2; do echo \$i; done\n"))
        assertTrue(check("/root/b.sh", "if true; then echo x\n").contains("fails its syntax check"))
    }

    /** sf07 verifyJob: exists, not empty, checks out, code verified, shown to the user. */
    @Test fun sf07_doneNeedsAFileThatChecksOutAndWasShown() {
        val ws = Ws(mapOf("/root/site/index.html" to "<html><body><h1>Hi</h1></body></html>", "/root/empty.txt" to ""), dirs = mapOf("/root/out" to 0))
        assertEquals(emptyList<String>(), probs("Build the pricing page", ws, listOf("/root/site/index.html", "/tmp/scratch.txt")))
        assertEquals(listOf("it was not shown to the user: attach /root/site/index.html"), probs("Build the pricing page", ws, listOf("/root/site/index.html"), attached = emptyList()))
        assertEquals(emptyList<String>(), probs("Build the pricing page", ws, listOf("/root/site/index.html"), attached = listOf("/root/site")))
        assertEquals(listOf("/root/missing.html does not exist"), probs("Make a page", ws, listOf("/root/missing.html")))
        assertEquals(listOf("/root/empty.txt is empty"), probs("Write notes", ws, listOf("/root/empty.txt")))
        assertEquals(listOf("/root/out is an empty folder"), probs("Make a site", ws, listOf("/root/out")))
        val gate = CompletenessGate().apply { onWrite("/root/site/app.js") }
        val p = probs("Build the pricing page", ws, listOf("/root/site/index.html"), gate = gate)
        assertEquals(1, p.size); assertTrue(p[0], p[0].startsWith("you changed code (/root/site/app.js)"))
    }

    /** sf07 JsSyntax / JsonStrict edge cases (no false failures on valid code). */
    @Test fun sf07_pureSyntaxScreens() {
        for (ok in listOf("", "a = b / c / d;", "x = `a${'$'}{ {a:1}.a }b`;", "if (a) { b() } else { c() }", "const r = /[)]/g; r.test(')')", "return /x/.test(y)", "let s = \"}\"; let t = '('"))
            assertNull(ok, ToolVerify.JsSyntax.error(ok))
        for (bad in listOf("f(", "a = [1, 2", "x = 'abc", "{ ) }", "x = `abc", "/* open"))
            assertTrue(bad, ToolVerify.JsSyntax.error(bad) != null)
        assertNull(ToolVerify.JsonStrict.error("[]")); assertNull(ToolVerify.JsonStrict.error(" {\"a\":{\"b\":[-0.5, 1E2]}} "))
        for (bad in listOf("", "[1,]", "01", "'a'", "{\"a\" 1}", "[1] x", "\"tab\there\"")) assertTrue(bad, ToolVerify.JsonStrict.error(bad) != null)
    }
}
