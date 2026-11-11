package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.44.0: the one parser for tool calls a model wrote as plain text in the main agent loop. Smaller models answer
 * with `browser_show()`, `open_app("WhatsApp")`, `desktop(action="start")`, `` `set_alarm(hour=7, minute=0)` ``,
 * `name {json}` or a bare `{"name": …, "arguments": …}` instead of calling the tool. [ToolLeak] already handles the
 * bracket / tag forms (`[/name k=v]`, `<tool_call>…`, `[TOOL_CALLS]…`); this file adds the function-call forms.
 *
 * What the loop does with it: a call to a tool that exists becomes a real call ([toCalls]); function syntax for a tool
 * that doesn't exist is taken out ([strip]) and the model is asked once more for a plain answer ([has]). The user never
 * sees function syntax, while streaming either ([strip] with final = false hides an unfinished call line).
 *
 * Kept deliberately small and self-contained: the Halo lane (DuoText on feat/bank-mode) parses the same forms, so the
 * two can be folded into this one file. Only a line that is nothing but a call counts, never a call inside a sentence,
 * and code in a fenced block (```python …```) is left alone. Pure Kotlin; TextCallsTest.
 */
object TextCalls {
    data class Call(val name: String, val args: JSONObject, val raw: String)

    /** 0.46.0: a label in front of the call ("Tool call: memory_search {…}", "Calling: x()", "Action - y {…}"). */
    private const val LEAD = """(?i:(?:\*\*)?(?:tool[ _]?calls?|function[ _]?calls?|calling(?: tool)?|call|action|using(?: tool)?)(?:\*\*)?[ \t]*[:\-][ \t]*(?:\*\*)?[ \t]*)?"""
    private const val ID = """[A-Za-z_][A-Za-z0-9_.:]*"""
    // name(...) alone on a line, optional backticks and a trailing semicolon.
    private val pyLine = Regex("""(?m)^[ \t]*(?:[-*•][ \t]+)?`{0,3}[ \t]*$LEAD`{0,3}[ \t]*($ID)[ \t]*\(([^\n]*)\)[ \t]*;?[ \t]*`{0,3}[ \t]*$""")
    // name {json} alone on a line.
    private val braceLine = Regex("""(?m)^[ \t]*(?:[-*•][ \t]+)?`?[ \t]*$LEAD`?($ID)[ \t]*(\{[^\n]*\})[ \t]*`?[ \t]*$""")
    // ```lang\n…``` blocks (the language decides whether the body may hold calls).
    private val fence = Regex("""```([A-Za-z_\-]*)[ \t]*\n?([\s\S]*?)```""")
    private val kv = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*[=:]\s*("(?:[^"\\]|\\.)*"|'[^']*'|\[[^\]]*\]|\{[^}]*\}|[^,)]+)""")
    // While streaming: a line that starts like a call and isn't closed yet.
    private val openTail = Regex("""(?:^|\n)[ \t]*`?[ \t]*$LEAD`?[A-Za-z]+_[A-Za-z0-9_]*[ \t]*[({][^\n]*$|(?:^|\n)[ \t]*(?:\*\*)?(?:tool[ _]?calls?|function[ _]?calls?)(?:\*\*)?[ \t]*[:\-]?[^\n]*$""", RegexOption.IGNORE_CASE)
    private val callLangs = setOf("", "json", "tool", "tool_call", "tool_code", "python", "py")

    /** A name that looks like a tool rather than a word: snake_case, or one of [known]. */
    fun toolish(name: String, known: Collection<String> = emptySet()): Boolean {
        val n = name.substringAfterLast('.').substringAfterLast(':')
        return (n.contains('_') && n.length in 3..48 && !n.startsWith("__")) || known.any { it.equals(n, true) }
    }

    private fun value(v: String): Any {
        val t = v.trim()
        if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))))
            return t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\n", "\n")
        if (t.startsWith("[") || t.startsWith("{")) ToolCalls.parseArgs(if (t.startsWith("[")) "{\"v\":$t}" else t)?.let { o -> return if (t.startsWith("[")) o.opt("v") ?: t else o }
        return when (t) { "true", "True" -> true; "false", "False" -> false; "None", "null" -> JSONObject.NULL; else -> t.toLongOrNull() ?: t.toDoubleOrNull() ?: t }
    }

    /** `key="v", n=2` or `{"key": "v"}`; a lone positional value comes back under "_0". */
    fun parseArgs(s: String): JSONObject {
        val t = s.trim()
        if (t.isEmpty()) return JSONObject()
        if (t.startsWith("{")) return ToolCalls.parseArgs(t) ?: JSONObject()
        val o = JSONObject()
        kv.findAll(t).forEach { m -> o.put(m.groupValues[1], value(m.groupValues[2])) }
        if (o.length() == 0) o.put("_0", value(t))
        return o
    }

    /** Text ranges that are code to leave alone (fenced blocks in a language that isn't a call format). */
    private fun codeRanges(text: String): List<IntRange> = fence.findAll(text)
        .filter { m -> m.groupValues[1].lowercase() !in callLangs || !onlyCallLines(m.groupValues[2]) }
        .map { it.range }.toList()

    private fun onlyCallLines(body: String): Boolean {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        val joined = lines.joinToString("\n")
        if (joined.startsWith("{") && jsonCall(joined) != null) return true
        return lines.all { l -> pyLine.matchEntire(l)?.let { toolish(it.groupValues[1]) } == true || braceLine.matchEntire(l)?.let { toolish(it.groupValues[1]) } == true }
    }

    /** `{"name": "x", "arguments": {…}}` (also tool / function.name / parameters / args). */
    private fun jsonCall(s: String): Call? {
        val o = ToolCalls.parseArgs(s) ?: return null
        val f = o.optJSONObject("function")
        val n = (f?.optString("name") ?: o.optString("name").ifBlank { o.optString("tool").ifBlank { o.optString("tool_name") } }).trim()
        if (n.isEmpty() || n.contains(' ')) return null
        val hasArgs = (f ?: o).let { it.has("arguments") || it.has("args") || it.has("parameters") || it.has("input") }
        if (!hasArgs && f == null && !o.has("tool")) return null
        val a = f?.opt("arguments") ?: o.opt("arguments") ?: o.opt("args") ?: o.opt("parameters") ?: o.opt("input")
        return Call(n, ToolCalls.parseArgs(a) ?: JSONObject(), s)
    }

    /** Every function-style call in [text], in order. [known] widens "tool-ish" to tool names without an underscore. */
    fun find(text: String, known: Collection<String> = emptySet()): List<Call> {
        if (text.isBlank() || (!text.contains('(') && !text.contains('{'))) return emptyList()
        val skip = codeRanges(text)
        fun free(r: IntRange) = skip.none { it.first <= r.first && r.last <= it.last }
        val out = ArrayList<Pair<Int, Call>>()
        for (m in pyLine.findAll(text)) if (free(m.range) && toolish(m.groupValues[1], known))
            out += m.range.first to Call(m.groupValues[1], parseArgs(m.groupValues[2]), m.value.trim('\n'))
        for (m in braceLine.findAll(text)) if (free(m.range) && toolish(m.groupValues[1], known) && out.none { it.first == m.range.first })
            out += m.range.first to Call(m.groupValues[1], parseArgs(m.groupValues[2]), m.value.trim('\n'))
        // A bare JSON call: the whole reply, or a fenced json / tool block.
        val whole = text.trim()
        if (whole.startsWith("{") && whole.endsWith("}")) jsonCall(whole)?.let { out += 0 to it.copy(raw = whole) }
        for (m in fence.findAll(text)) if (m.groupValues[1].lowercase() in callLangs) {
            val b = m.groupValues[2].trim()
            if (b.startsWith("{")) jsonCall(b)?.let { c -> out.removeAll { it.first in m.range }; out += m.range.first to c.copy(raw = m.value) }
            else if (onlyCallLines(b)) {
                // The fence itself goes with its calls.
                val inner = out.filter { it.first in m.range }
                if (inner.isNotEmpty()) { out.removeAll { it.first in m.range }; inner.forEachIndexed { i, (_, c) -> out += m.range.first + i to (if (i == 0) c.copy(raw = m.value) else c.copy(raw = "")) } }
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** Is there function syntax the user must not see? */
    fun has(text: String, known: Collection<String> = emptySet()): Boolean = find(text, known).isNotEmpty()

    /** Nothing but calls (no words for the user). */
    fun onlyCalls(text: String, known: Collection<String> = emptySet()): Boolean {
        val c = find(text, known)
        return c.isNotEmpty() && strip(text, known = known).isBlank()
    }

    /** [text] without call lines (and, while streaming, without an unfinished one at the end). */
    fun strip(text: String, final: Boolean = true, known: Collection<String> = emptySet()): String {
        if (text.isEmpty() || (!text.contains('(') && !text.contains('{') && (final || !text.contains("call", true)))) return text
        var t = text
        for (c in find(t, known)) if (c.raw.isNotEmpty()) t = t.replace(c.raw, "")
        if (!final) openTail.find(t)?.let { m -> if (codeRanges(t).none { m.range.first in it } && t.count { it == '`' } % 6 == 0) t = t.substring(0, m.range.first) }
        return if (t == text) text else t.replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** The first required (else the first) parameter of [name] in [all], for a positional `open_app("WhatsApp")`. */
    private fun firstParam(all: JSONArray, name: String): String? {
        for (i in 0 until all.length()) {
            val f = all.optJSONObject(i)?.optJSONObject("function") ?: continue
            if (f.optString("name") != name) continue
            val p = f.optJSONObject("parameters") ?: return null
            p.optJSONArray("required")?.optString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            return p.optJSONObject("properties")?.keys()?.asSequence()?.firstOrNull()
        }
        return null
    }

    /** Real calls for the text calls that name a tool in [all], and the text left over (every call line stripped). */
    fun toCalls(text: String, all: JSONArray): Pair<JSONArray, String>? {
        val known = ToolCalls.names(all)
        val found = find(text, known)
        if (found.isEmpty()) return null
        val calls = JSONArray()
        for (c in found) {
            val n = ToolCalls.resolveName(c.name, known) ?: continue
            val a = c.args
            if (a.length() == 1 && a.has("_0")) firstParam(all, n)?.let { p -> val v = a.opt("_0"); a.remove("_0"); a.put(p, v) }
            a.remove("_0")
            calls.put(JSONObject().put("id", "textcall_${calls.length()}").put("type", "function")
                .put("function", JSONObject().put("name", n).put("arguments", a.toString())))
        }
        if (calls.length() == 0) return null
        return calls to strip(text, known = known)
    }

    // ---------------- 0.45.15: block calls (device /phoneqa 21 Oct 22:40, codestral on the "run" route) ----------------

    /**
     * The forms codestral wrote on the phone instead of calling the tool:
     *  - a tool name alone on a line, then `key: value`, `key=value` or `- key: value` lines
     *    ("open_settings\npage: dnd\ntap\ntext: \"Do Not Disturb\"\nwant: \"on\"");
     *  - a tool name with its pairs on the same line ("tap text=\"Dark mode\" want=\"on\"");
     *  - a tool name alone on a line, then a JSON object ("run_plan\n{\"steps\": [...]}");
     *  - a bare `{"steps": [...]}` (whole reply or a ```json block): that is run_plan's argument.
     * Only names in [known] count. A call missing a required parameter (a bare "tap") is dropped; [blockHas] still sees
     * the syntax so the loop asks once more instead of showing it.
     */
    data class Block(val name: String, val args: JSONObject, val lines: IntRange)

    private val pairLine = Regex("""^\s*(?:[-*]\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*[:=]\s*(.*?)\s*,?\s*$""")
    private val inlinePair = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*[:=]\s*("(?:[^"\\]|\\.)*"|'[^']*'|[^\s"']+)""")

    private fun blockValue(v: String): Any? {
        val t = v.trim().trimEnd(',')
        if (t.isEmpty() || t.equals("null", true) || t.equals("none", true)) return null
        return value(t)
    }

    private fun stepsJson(t: String): JSONObject? {
        val o = ToolCalls.parseArgs(t.trim()) ?: return null
        val steps = o.optJSONArray("steps") ?: return null
        if (steps.length() == 0 || (0 until steps.length()).none { steps.optJSONObject(it)?.has("do") == true }) return null
        return o
    }

    /** Block-form calls in [text], in order (see [Block]). */
    fun blocks(text: String, known: Collection<String>): List<Block> {
        if (text.isBlank()) return emptyList()
        val names = known.associateBy { it.lowercase() }
        val out = ArrayList<Block>()
        // A bare steps object: the whole reply, or a fenced json block.
        val whole = text.trim()
        if ("run_plan" in known) {
            if (whole.startsWith("{")) stepsJson(whole)?.let { return listOf(Block("run_plan", it, 0..0)) }
            for (m in fence.findAll(text)) if (m.groupValues[1].lowercase() in callLangs) stepsJson(m.groupValues[2])?.let { out += Block("run_plan", it, -1..-1) }
            if (out.isNotEmpty()) return out
        }
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i].trim().trim('`')
            val head = raw.substringBefore(' ').substringBefore('\t')
            val name = names[head.lowercase()]
            if (name == null) { i++; continue }
            val start = i
            val args = JSONObject()
            val restOfLine = raw.substring(head.length).trim()
            if (restOfLine.startsWith("{")) {
                ToolCalls.parseArgs(restOfLine)?.let { o -> o.keys().forEach { k -> args.put(k, o.get(k)) } }
            } else if (restOfLine.isNotEmpty()) {
                val pairs = inlinePair.findAll(restOfLine).toList()
                // "tap element 17 and tap …" or a sentence: not a call line.
                if (pairs.isEmpty()) { i++; continue }
                pairs.forEach { m -> if (!args.has(m.groupValues[1])) blockValue(m.groupValues[2])?.let { args.put(m.groupValues[1], it) } }
            }
            i++
            // A JSON object on the next line(s).
            if (args.length() == 0 && i < lines.size && lines[i].trim().startsWith("{")) {
                var j = i; val sb = StringBuilder()
                while (j < lines.size) { sb.append(lines[j]).append('\n'); if (ToolCalls.parseArgs(sb.toString().trim()) != null) break; j++ }
                ToolCalls.parseArgs(sb.toString().trim())?.let { o -> o.keys().forEach { k -> args.put(k, o.get(k)) }; i = j + 1 }
            }
            // key: value lines (blank lines inside the block are fine).
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) { if (i + 1 < lines.size && pairLine.matches(lines[i + 1]) && names[lines[i + 1].trim().substringBefore(' ').lowercase()] == null) { i++; continue } else break }
                val m = pairLine.matchEntire(l) ?: break
                if (names[l.trim().substringBefore(' ').lowercase()] != null && !l.contains(':') && !l.contains('=')) break
                val k = m.groupValues[1]
                if (!args.has(k)) blockValue(m.groupValues[2])?.let { args.put(k, it) }
                i++
            }
            // A bare word ("tap", "wait") on a line is prose unless it carries arguments.
            if (args.length() == 0 && !name.contains('_')) continue
            out += Block(name, args, start until i)
        }
        return out
    }

    /** Required parameters of [name] in [all]. */
    private fun required(all: JSONArray, name: String): List<String> {
        for (i in 0 until all.length()) {
            val f = all.optJSONObject(i)?.optJSONObject("function") ?: continue
            if (f.optString("name") != name) continue
            val r = f.optJSONObject("parameters")?.optJSONArray("required") ?: return emptyList()
            return (0 until r.length()).map { r.optString(it) }
        }
        return emptyList()
    }

    /** True when [b] can run: its required parameters are there (tap needs text or element, whatever its schema says). */
    private fun runnable(b: Block, all: JSONArray): Boolean {
        if (b.name == "tap" && !b.args.has("text") && !b.args.has("element") && !b.args.has("x")) return false
        return required(all, b.name).all { b.args.has(it) }
    }

    /** Is there block-form call syntax for a known tool (shown to the user it reads as broken code)? */
    fun blockHas(text: String, known: Collection<String>): Boolean = blocks(text, known).isNotEmpty()

    /** Real calls for the runnable block calls, and the words left for the user (every block line taken out). */
    fun blockCalls(text: String, all: JSONArray): Pair<JSONArray, String>? {
        val known = ToolCalls.names(all)
        val found = blocks(text, known)
        if (found.isEmpty()) return null
        val ok = found.filter { runnable(it, all) }
        if (ok.isEmpty()) return null
        val calls = JSONArray()
        for (b in ok) calls.put(JSONObject().put("id", "blockcall_${calls.length()}").put("type", "function")
            .put("function", JSONObject().put("name", b.name).put("arguments", b.args.toString())))
        return calls to blockStrip(text, known)
    }

    /** [text] without its block calls (and without a fenced steps block). */
    fun blockStrip(text: String, known: Collection<String>): String {
        val found = blocks(text, known)
        if (found.isEmpty()) return text
        if (found.any { it.lines == 0..0 }) return ""
        var t = text
        if (found.any { it.lines == -1..-1 }) t = fence.replace(t) { m -> if (stepsJson(m.groupValues[2]) != null) "" else m.value }
        val drop = found.filter { it.lines.first >= 0 }.flatMap { it.lines.toList() }.toSet()
        if (drop.isNotEmpty()) t = t.lines().filterIndexed { i, _ -> i !in drop }.joinToString("\n")
        return t.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** Sent once when the reply was function syntax for no real tool. */
    const val NUDGE = "Your last reply was function-call text (like name(...)) instead of a real tool call, so the user would only see code. " +
        "Call tools only through the native tool-calling API. If no tool fits, answer in plain words. Never write function names, brackets or JSON in your reply."
    // ---------------- P2-SF-07: fence tool blocks (A team AT-25 fence fallback: guard.js parse / normalize / validate) ----------------
    // Ported from A team app/www/guard.js (owner grant, MIT). Models that don't call tools natively write them as fences:
    // ```run\nls -la\n``` or ```write_file ~/a.sh\n…\n```. [fenceNormalize] repairs the common slips (```RUN, ``` run:, ~~~run,
    // ```terminal, one-line ```run ls```, an unclosed last block, "$ " prompts); [fenceParse] splits a reply into markdown
    // and tool blocks (code fences in other languages stay markdown); [fenceCalls] turns run / write_file / remember /
    // recall / forget blocks into real calls of this app's tools when those tools are offered. Cases: A team
    // evals/test/guard.test.mjs, as JUnit in TextCallsFenceTest.

    /** Fence words that are tool blocks (the A team's TOOLS). */
    val FENCE_TOOLS: List<String> = listOf("run", "write_file", "remember", "recall", "forget", "add_job", "done_job", "update_agent", "note_team", "cloud",
        "job_status", "job_wait", "job_kill", "agents_list", "agent_create", "agent_update", "agent_delete", "agent_delegate", "agent_status",
        "memory_read", "memory_write", "memory_forget", "routine_create", "routine_list", "routine_cancel", "retry_job", "browser")
    /** Slipped fence words and their canonical tool. */
    val FENCE_ALIASES: Map<String, String> = mapOf("shell" to "run", "bash_run" to "run", "run_shell" to "run", "terminal" to "run", "exec" to "run", "execute" to "run", "cmd" to "run",
        "memory" to "remember", "save_memory" to "remember", "search_memory" to "recall", "addjob" to "add_job", "add-job" to "add_job",
        "donejob" to "done_job", "done-job" to "done_job", "complete_job" to "done_job", "update-agent" to "update_agent",
        "writefile" to "write_file", "write-file" to "write_file", "write_to_file" to "write_file", "create_file" to "write_file")

    sealed class FenceBlock {
        data class Md(val text: String) : FenceBlock()
        data class Tool(val name: String, val arg: String, val body: String, val closed: Boolean) : FenceBlock()
    }
    data class Normalized(val text: String, val fixes: List<String>)

    private val fenceOpen = Regex("""^[ \t]*(`{3,}|~{3})[ \t]*([A-Za-z_-]+)?[ \t]*:?[ \t]*([^\n`]*)$""")

    /** A reply as markdown and tool blocks (a fence named after a [FENCE_TOOLS] word is a tool block). */
    fun fenceParse(text: String?): List<FenceBlock> {
        val lines = (text ?: "").replace(Regex("""\r\n?"""), "\n").split("\n")
        val out = ArrayList<FenceBlock>(); val md = ArrayList<String>(); var i = 0
        fun flush() { if (md.joinToString("\n").trim().isNotEmpty()) out += FenceBlock.Md(md.joinToString("\n").replace(Regex("""\n+$"""), "")); md.clear() }
        while (i < lines.size) {
            val m = fenceOpen.find(lines[i])
            if (m == null) { md += lines[i++]; continue }
            val fence = m.groupValues[1]; val raw = m.groupValues[2].lowercase(); val name = raw.takeIf { it in FENCE_TOOLS }
            val close = Regex("^[ \\t]*" + Regex.escape(fence[0].toString()) + "{" + fence.length + ",}[ \\t]*$")
            val body = ArrayList<String>(); var j = i + 1; var closed = false
            while (j < lines.size) { if (close.containsMatchIn(lines[j])) { closed = true; break }; body += lines[j++] }
            if (name != null) { flush(); out += FenceBlock.Tool(name, m.groupValues[3].trim(), body.joinToString("\n"), closed) }
            else md += lines.subList(i, if (closed) j + 1 else j)
            i = if (closed) j + 1 else j
        }
        flush()
        return out
    }

    /** Index just after the first closing run / recall / write_file / cloud fence (text after it is dropped), or null. */
    fun cutAfterFirstResultBlock(text: String?): Int? {
        var pos = 0; var inFence: Regex? = null; var tool = false
        for (l in (text ?: "").split("\n")) {
            val end = pos + l.length
            val f = inFence
            if (f != null) {
                if (f.containsMatchIn(l)) { if (tool) return end; inFence = null }
            } else {
                fenceOpen.find(l)?.let { m ->
                    inFence = Regex("^[ \\t]*" + Regex.escape(m.groupValues[1][0].toString()) + "{" + m.groupValues[1].length + ",}[ \\t]*$")
                    tool = Regex("^(run|recall|write_file|cloud)$", RegexOption.IGNORE_CASE).matches(m.groupValues[2])
                }
            }
            pos = end + 1
        }
        return null
    }

    /** Fix common tool-fence slips so the parser sees what the model meant. */
    fun fenceNormalize(text: String?): Normalized {
        val fixes = ArrayList<String>(); var t = (text ?: "").replace(Regex("""\r\n?"""), "\n")
        // ``` RUN / ```Run / ~~~run / ```run: -> ```run ; aliases (```terminal etc.) -> canonical; unknown words stay
        t = Regex("""^([ \t]*)(`{3,}|~{3})[ \t]*([A-Za-z_-]+)[ \t]*:?[ \t]*$""", RegexOption.MULTILINE).replace(t) { m ->
            val lw = m.groupValues[3].lowercase(); val canon = if (lw in FENCE_TOOLS) lw else FENCE_ALIASES[lw]
            if (canon == null) m.value else { val fixed = "```$canon"; if (m.value.trim() != fixed) fixes += "fence \"${m.value.trim()}\" -> \"$fixed\""; fixed }
        }
        t = Regex("""^[ \t]*~~~[ \t]*$""", RegexOption.MULTILINE).replace(t) { fixes += "~~~ close -> ```"; "```" }
        // one-line form: ```run ls -la```  -> multi-line
        t = Regex("""^```(run|recall|forget|done_job)[ \t]+([^\n`]+?)[ \t]*```[ \t]*$""", RegexOption.MULTILINE).replace(t) { m -> fixes += "inline ${m.groupValues[1]} block expanded"; "```" + m.groupValues[1] + "\n" + m.groupValues[2] + "\n```" }
        // unclosed final tool block
        val last = fenceParse(t).lastOrNull()
        if (last is FenceBlock.Tool && !last.closed) { t = t.replace(Regex("""\n*$"""), "\n```"); fixes += "closed unterminated ${last.name} block" }
        // "$ " prompt prefixes inside run blocks
        t = Regex("""(^```run[^\n]*\n)([\s\S]*?)(\n```)""", RegexOption.MULTILINE).replace(t) { m ->
            var body = m.groupValues[2]; val ls = body.split("\n")
            if (ls.isNotEmpty() && ls.all { it.isBlank() || Regex("""^\s*\$ """).containsMatchIn(it) }) { fixes += "stripped \"\$ \" prompts"; body = ls.joinToString("\n") { it.replace(Regex("""^(\s*)\$ """), "$1") } }
            m.groupValues[1] + body + m.groupValues[3]
        }
        return Normalized(t, fixes)
    }

    data class FenceProblem(val index: Int, val name: String, val error: String)
    data class FenceCheck(val text: String, val blocks: List<FenceBlock.Tool>, val problems: List<FenceProblem>, val fixes: List<String>)

    /** Normalize, then check every tool block (run: CheckRun rules; secrets never go to memory; update_agent needs fields). */
    fun fenceValidate(reply: String?, cwd: String? = null): FenceCheck {
        val n = fenceNormalize(reply); val all = fenceParse(n.text)
        val blocks = all.filterIsInstance<FenceBlock.Tool>(); val problems = ArrayList<FenceProblem>()
        blocks.forEachIndexed { i, b ->
            if (b.name == "run") com.blaze.assistant.agent.guards.CheckRun.check(b.body, cwd).errors.forEach { problems += FenceProblem(i, b.name, it) }
            else if (b.body.isBlank() && !(b.name == "remember" && b.arg.isNotEmpty())) problems += FenceProblem(i, b.name, "empty ${b.name} block")
            if (b.name == "remember" && Regex("""\b(password|passwd|api[_ -]?key|secret|token|bearer)\b\s*[:=]?\s*\S{6,}""", RegexOption.IGNORE_CASE).containsMatchIn(b.body)) problems += FenceProblem(i, b.name, "secrets are never stored in memory")
            if (b.name == "update_agent" && !Regex("""^\s*\w+\s*:""", RegexOption.MULTILINE).containsMatchIn(b.body)) problems += FenceProblem(i, b.name, "update_agent needs \"field: value\" lines (name, role, email, portfolio, model)")
        }
        val md = all.filterIsInstance<FenceBlock.Md>().joinToString("\n") { it.text }
        if (blocks.isNotEmpty() && Regex("""```run[\s\S]*```[\s\S]*\n(output|result)s?:\s*\n""", RegexOption.IGNORE_CASE).containsMatchIn(n.text)) problems += FenceProblem(-1, "text", "invented output after a run block: stop and wait for the result")
        if (blocks.none { it.name == "run" } && Regex("""^\s*(\$|root@\S*#)\s+\S+[\s\S]*\n(total \d+|drwx|-rw-)""", RegexOption.MULTILINE).containsMatchIn(md)) problems += FenceProblem(-1, "text", "shows terminal output without running anything")
        return FenceCheck(n.text, blocks, problems, n.fixes)
    }

    /** Fence tool -> (this app's tool, how its arguments are built), used by [fenceCalls] when that tool is offered. */
    private val fenceTools: Map<String, Pair<String, (FenceBlock.Tool) -> JSONObject>> = mapOf(
        "run" to ("run_shell" to { b -> JSONObject().put("command", b.body) }),
        "write_file" to ("file_write" to { b -> JSONObject().put("path", b.arg).put("content", b.body) }),
        "remember" to ("remember" to { b -> JSONObject().put("text", b.body) }),
        "recall" to ("recall_memory" to { b -> JSONObject().put("query", b.body) }),
        "forget" to ("forget" to { b -> JSONObject().put("text", b.body) }),
    )

    /**
     * Real calls for the closed, valid fence tool blocks whose tool is in [all] (run -> run_shell, write_file -> file_write,
     * remember / recall_memory / forget), and the markdown left over; null when there is none. A run block that
     * [fenceValidate] rejects is not turned into a call.
     */
    fun fenceCalls(text: String, all: JSONArray): Pair<JSONArray, String>? {
        val known = ToolCalls.names(all)
        val v = fenceValidate(text)
        val bad = v.problems.filter { it.index >= 0 }.map { it.index }.toSet()
        val calls = JSONArray()
        v.blocks.forEachIndexed { i, b ->
            if (!b.closed || i in bad) return@forEachIndexed
            val (tool, args) = fenceTools[b.name] ?: return@forEachIndexed
            if (known.none { it == tool }) return@forEachIndexed
            calls.put(JSONObject().put("id", "fencecall_${calls.length()}").put("type", "function")
                .put("function", JSONObject().put("name", tool).put("arguments", args(b).toString())))
        }
        if (calls.length() == 0) return null
        val rest = fenceParse(v.text).filterIsInstance<FenceBlock.Md>().joinToString("\n\n") { it.text }.trim()
        return calls to rest
    }
}
