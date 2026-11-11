package com.blaze.assistant.agent

import com.blaze.assistant.agent.TextCalls.FenceBlock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-SF-07: fence repair / fence fallback cases, named after A team evals/test/guard.test.mjs (case position). */
class TextCallsFenceTest {
    private fun tool(name: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name)
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))

    /** g01 'parse keeps multi-line run blocks intact and leaves code fences as markdown' */
    @Test fun g01_parseKeepsMultiLineRunBlocksIntactAndLeavesCodeFencesAsMarkdown() {
        val r = "Look:\n```run\ncat > a.sh <<'EOF'\necho \"\$1\" `x`\nEOF\nbash a.sh hi\n```\nand code:\n```python\nprint(1)\n```"
        val b = TextCalls.fenceParse(r); val t = b.filterIsInstance<FenceBlock.Tool>()
        assertEquals(1, t.size); assertEquals("run", t[0].name); assertTrue(t[0].closed)
        assertEquals("cat > a.sh <<'EOF'\necho \"\$1\" `x`\nEOF\nbash a.sh hi", t[0].body)
        assertTrue(b.any { it is FenceBlock.Md && it.text.contains("```python") })
    }

    /** g02 'normalize fixes fence slips' */
    @Test fun g02_normalizeFixesFenceSlips() {
        val cases = listOf(
            "```RUN\nls\n```" to "```run\nls\n```",
            "``` run:\nls\n```" to "```run\nls\n```",
            "~~~run\nls\n~~~" to "```run\nls\n```",
            "```shell_exec\nls\n```" to "```shell_exec\nls\n```",          // unknown words are left alone
            "```terminal\nls\n```" to "```run\nls\n```",
            "```run ls -la```" to "```run\nls -la\n```",
            "```run\nls -la" to "```run\nls -la\n```",                       // unclosed at the end
            "```run\n\$ ls\n\$ pwd\n```" to "```run\nls\npwd\n```",             // pasted prompts
            "```add-job\nShip it\n```" to "```add_job\nShip it\n```",
        )
        for ((i, o) in cases) assertEquals(i, o, TextCalls.fenceNormalize(i).text)
        assertTrue(TextCalls.fenceNormalize("```RUN\nls\n```").fixes.isNotEmpty())
    }

    /** g06 'validate flags secrets in memory and update_agent without fields' */
    @Test fun g06_validateFlagsSecretsInMemoryAndUpdateAgentWithoutFields() {
        val v = TextCalls.fenceValidate("```remember me\ngateway token: sk-live-abcdef123456\n```\n```update_agent\nmake me a designer\n```")
        assertEquals(v.problems.toString(), 2, v.problems.size)
    }

    /** g16 'v2.10: write_file works as a fence (fallback for calls written as text)' */
    @Test fun g16_writeFileWorksAsAFence() {
        val b = TextCalls.fenceParse("Writing it.\n```write_file ~/hello.sh\n#!/bin/bash\necho \"Hello, \$1\"\n```").filterIsInstance<FenceBlock.Tool>()
        assertEquals(1, b.size); assertEquals("write_file", b[0].name); assertEquals("~/hello.sh", b[0].arg); assertEquals("#!/bin/bash\necho \"Hello, \$1\"", b[0].body)
        assertEquals(0, TextCalls.fenceParse("```python\nprint(1)\n```").filterIsInstance<FenceBlock.Tool>().size)
    }

    /** g17 'v2.10: cutAfterFirstResultBlock drops text after the first run/recall/write_file fence' */
    @Test fun g17_cutAfterFirstResultBlockDropsTextAfterTheFirstRunFence() {
        val t = "Testing.\n```run\npython3 a.py\n```\nOutput:\n```\n42\n```"
        assertEquals("Testing.\n```run\npython3 a.py\n```", t.substring(0, TextCalls.cutAfterFirstResultBlock(t)!!))
        val u = "Code:\n```python\nx = 1\n```\n```run\nls\n```\nmore"
        assertTrue(u.substring(0, TextCalls.cutAfterFirstResultBlock(u)!!).endsWith("```run\nls\n```"))
        assertNull(TextCalls.cutAfterFirstResultBlock("no tools here"))
    }

    /** SF-07 extra (AT-25 fence fallback): run / write_file fences become this app's run_shell / file_write calls; unsafe runs don't. */
    @Test fun sf07_fenceCallsMapToThisAppsTools() {
        val all = JSONArray().put(tool("run_shell")).put(tool("file_write"))
        val (calls, rest) = TextCalls.fenceCalls("Checking.\n```RUN\nls -la\n```", all)!!
        assertEquals(1, calls.length()); val f = calls.getJSONObject(0).getJSONObject("function")
        assertEquals("run_shell", f.getString("name")); assertEquals("ls -la", JSONObject(f.getString("arguments")).getString("command")); assertEquals("Checking.", rest)
        val w = TextCalls.fenceCalls("```write_file /root/a.py\nprint(1)\n```", all)!!.first.getJSONObject(0).getJSONObject("function")
        assertEquals("file_write", w.getString("name")); assertEquals("/root/a.py", JSONObject(w.getString("arguments")).getString("path"))
        assertNull(TextCalls.fenceCalls("```run\nrm -rf /\n```", all))                       // CheckRun blocks it: no call
        assertNull(TextCalls.fenceCalls("```run\nls\n```", JSONArray().put(tool("file_write"))))   // run_shell not offered
        assertNull(TextCalls.fenceCalls("```python\nprint(1)\n```", all))
    }
}
