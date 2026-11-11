package com.blaze.assistant.agent.guards

import java.util.Base64

/**
 * P2-SF-07 (AT-34 guard layer, AT-71 server detach): the A team's `checkRun` rules as pure Kotlin. Ported from A team
 * `app/www/guard.js` (owner grant, MIT); every case in A team `evals/test/guard.test.mjs`, `v211.test.mjs` and the
 * detach case in `v215.test.mjs` is a JUnit case in CheckRunTest.
 *
 * What it does with a shell script the model wants to run (workspace = the Linux /root, plus /tmp):
 *  - strips `sudo` (the agent is already root) with a warning;
 *  - blocks: fork bombs, mkfs, dd / redirects onto raw devices, power commands, chmod/chown -R on /, rm/mv/cp into Android
 *    host paths (/system /data /vendor /proc /sys /dev), --no-preserve-root, rm/shred/unlink/find -delete outside the
 *    workspace or on the whole home, fake / no-op run blocks ([fakeRun]), apply_patch outside /root and /tmp;
 *  - warns: curl|sh, git push --force, writes into system paths, a server started in the foreground;
 *  - rewrites `apply_patch <<'EOF' … EOF` into a python3 script that applies the patch (all hunks or nothing);
 *  - wraps the script in `timeout -k 5 N bash -lc …`.
 * [detachServers] rewrites servers and `cmd &` lines to `nohup … > /tmp/argus-bg/<id>-<n>.log 2>&1 &`.
 * [deletesEdited] finds a script that would delete files this job wrote. [feedback] turns a run result into the tool
 * result text with a retry hint.
 *
 * How it sits next to [com.blaze.assistant.agent.CommandPolicy] (no duplicate rules, no contradiction):
 *  - CommandPolicy answers "ask the user first?" (SAFE / ASK / RISKY) in Normal mode. CheckRun answers "may this run at
 *    all, and in what form?" for scripts run in the Linux workspace, in every mode. A command CheckRun blocks is never
 *    shown on an approval card; a command CheckRun allows still goes through CommandPolicy / ConfirmGate unchanged.
 *  - Overlaps (same command judged by both): rm/shred/unlink, find -delete (Policy RISKY; CheckRun blocks only outside the
 *    workspace), sudo/su (Policy RISKY; CheckRun strips sudo and lets the rest through to the Policy), mkfs/dd/shutdown
 *    (Policy RISKY; CheckRun blocks), curl|sh (Policy RISKY; CheckRun warns), git push --force (Policy RISKY; CheckRun
 *    warns), chmod outside the workspace (Policy RISKY; CheckRun blocks only on / and host paths). In every overlap CheckRun
 *    is the same or stricter, never looser: CheckRun runs first, then the Policy sees the cleaned [Result.script].
 *  - Workspace roots differ on purpose: CommandPolicy also counts /home (Blaze's own files); CheckRun is the A team rule
 *    for the Linux workspace (/root and /tmp only).
 * Pure Kotlin (no android.*). Not wired into Agent.kt (P2 adds zero lines there); see docs in the P2-SF-07 report.
 */
object CheckRun {
    /** A finished run: exit code, merged output, timed out. */
    data class RunResult(val code: Int, val out: String = "", val timedOut: Boolean = false, val err: String = "")

    data class Result(val ok: Boolean, val script: String, val patched: List<String>, val errors: List<String>,
                      val warnings: List<String>, val timeout: Int, val wrapped: String)

    private val ic = RegexOption.IGNORE_CASE
    private val ml = RegexOption.MULTILINE

    /** Is path word [p] inside the workspace (/root, ~, $HOME, /tmp, relative)? The whole home itself is not. */
    fun insideWorkspace(path: String?, cwd: String? = null): Boolean {
        val p = (path ?: "").replace(Regex("""^["']|["']$"""), "")
        if (p.isEmpty() || p == "-" || p.startsWith("-")) return true
        if (Regex("""^(/root|~|\${'$'}HOME|\${'$'}\{HOME\})(/|$)""").containsMatchIn(p)) return !Regex("""^(/root|~|\${'$'}HOME|\${'$'}\{HOME\})/?\*?$""").containsMatchIn(p)
        if (Regex("""^/tmp(/|$)""").containsMatchIn(p)) return true
        if (p.startsWith("/")) return false
        if (Regex("""^\.\.(/|$)""").containsMatchIn(p)) { val c = cwd ?: "/root"; return c != "/root" && c.startsWith("/root/") }
        return true
    }

    /** Split a script into simple commands (good enough for policy; not a full shell parser). */
    fun commands(script: String?): List<String> = (script ?: "").replace("\\\n", " ").split(Regex("""\n|;|&&|\|\||\|(?!\|)""")).map { it.trim() }.filter { it.isNotEmpty() }

    private val HARD_BLOCK: List<Pair<Regex, String>> = listOf(
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;?\s*:""") to "fork bomb",
        Regex("""\bmkfs(\.\w+)?\b""") to "formats a filesystem",
        Regex("""\bdd\b[^\n]*\bof=/dev/(?!null\b)""") to "writes to a raw device",
        Regex(""">\s*/dev/(sd|nvme|mmcblk|block)""") to "writes to a raw device",
        Regex("""\b(shutdown|reboot|halt|poweroff)\b""") to "power commands",
        Regex("""\bchmod\s+(-R\s+)?[0-7]{3,4}\s+/(\s|$)""") to "chmod on /",
        Regex("""\bchown\s+-R\s+\S+\s+/(\s|$)""") to "chown -R on /",
        Regex("""\b(rm|mv|cp)\b[^\n]*\s/(system|data|vendor|proc|sys|dev)(/|\s|$)""") to "touches Android/host paths",
        Regex("""--no-preserve-root""") to "rm --no-preserve-root",
    )

    // ------------------------------------------------------------------ apply_patch (A team v2.11)
    /** The python3 program that applies a `*** Begin Patch` patch (argv[1] = base64 patch): all hunks or nothing, /root and /tmp only. */
    const val AP_PY = AP_PY_SRC

    private val AP_HEREDOC = Regex("""(^|\n|&&|;|\|\|)([ \t]*)apply_patch(?:[ \t]+-)?[ \t]*<<-?[ \t]*(['"]?)(\w+)\3([^\n]*)\n([\s\S]*?)\n[ \t]*\4[ \t]*(?=\n|$)""")
    private val AP_QUOTED = Regex("""(^|\n|&&|;|\|\|)([ \t]*)apply_patch[ \t]+(['"])(\*\*\* Begin Patch[\s\S]*?\*\*\* End Patch)[ \t]*\n?\3""")

    /** Paths a patch touches (Add/Update/Delete/Move to). */
    fun patchPaths(patch: String?): List<String> = Regex("""^\*\*\* (?:Add File|Update File|Delete File|Move to):\s*(.+?)\s*$""", ml).findAll(patch ?: "").map { it.groupValues[1] }.toList()

    /** apply_patch calls pulled out of a script: the script with placeholders, and the patches (empty when none). */
    fun extractPatches(script: String?): Pair<String, List<String>> {
        val patches = ArrayList<String>()
        fun put(pre: String, ind: String, body: String): String { patches += body; return "$pre${ind}apply_patch __ARGUS_PATCH_${patches.size - 1}__" }
        var s = AP_HEREDOC.replace(script ?: "") { m -> put(m.groupValues[1], m.groupValues[2], m.groupValues[6]) + m.groupValues[5] }
        s = AP_QUOTED.replace(s) { m -> put(m.groupValues[1], m.groupValues[2], if (m.groupValues[3] == "'") m.groupValues[4].replace("'\\''", "'") else m.groupValues[4]) }
        return s to patches
    }

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    fun expandPatches(script: String, patches: List<String>): String {
        if (patches.isEmpty()) return script
        val paths = patches.flatMap { patchPaths(it) }
        val s = Regex("""apply_patch __ARGUS_PATCH_(\d+)__""").replace(script) { m ->
            "python3 - '" + b64(patches[m.groupValues[1].toInt()]) + "' <<'__ARGUS_AP__'\n" + AP_PY + "\n__ARGUS_AP__"
        }
        return "# apply_patch edits: " + paths.joinToString(" ") + "\n" + s
    }

    /** A whole apply_patch call (a native `apply_patch` tool call's input) as a run script. */
    fun patchCommand(patch: String?): String = "apply_patch <<'PATCH'\n" + (patch ?: "").replace(Regex("""\s+$"""), "") + "\nPATCH"

    // ------------------------------------------------------------------ write_file paths (A team v2.9)
    const val WRITE_MAX = 60 * 1024

    data class WriteCheck(val ok: Boolean, val path: String = "", val bytes: Int = 0, val error: String = "")

    /** A file write: absolute under /root or /tmp, ~/x, or relative to /root; at most [WRITE_MAX] UTF-8 bytes. */
    fun checkWrite(path: String?, content: String?): WriteCheck {
        var p = (path ?: "").trim().replace(Regex("""^['"]|['"]$"""), "")
        if (p.isEmpty()) return WriteCheck(false, error = "no path given")
        p = if (p == "~" || p.startsWith("~/")) "/root" + p.substring(1) else if (!p.startsWith("/")) "/root/" + p.replace(Regex("""^\./"""), "") else p
        if (Regex("""(^|/)\.\.(/|$)""").containsMatchIn(p)) return WriteCheck(false, error = "\"..\" is not allowed in the path")
        if (!Regex("""^/(root|tmp)/[^\u0000]+$""").containsMatchIn(p) || p.endsWith("/")) return WriteCheck(false, error = "write files inside /root or /tmp (a file path, not a folder)")
        val bytes = (content ?: "").toByteArray(Charsets.UTF_8).size
        if (bytes > WRITE_MAX) return WriteCheck(false, p, bytes, "content is ${Math.round(bytes / 1024.0)} KB, over the ~60 KB per call limit: write the first part with write_file and append the rest with run (cat >> file <<'EOF')")
        return WriteCheck(true, p, bytes)
    }

    /** Shell script that writes [content] to [path] byte-exact (base64, so quotes and heredoc markers can't break it). */
    fun writeScript(path: String, content: String): String {
        val q = shq(path)
        return "mkdir -p \"\$(dirname $q)\" && printf %s '${b64(content)}' | base64 -d > $q && wc -c < $q"
    }

    // ------------------------------------------------------------------ the check
    private val SUDO_ANY = Regex("""(^|[\s;&|(])sudo\b""")
    private val SUDO_STRIP = Regex("""(^|\n|[;&|(]\s*|&&\s*|\|\|\s*)([ \t]*)sudo\s+(?:-[EHnSs]+\s+)*""")
    private val WHOLE_HOME = Regex("""^(/root|~|\${'$'}HOME|\${'$'}\{HOME\})/?\*?$|^/?\*$|^/$""")
    private val CURL_SH = Regex("""\b(curl|wget)\b[^|\n]*\|\s*(sudo\s+)?(ba)?sh\b""")
    private val SERVER_FG = Regex("""^(python3? -m http\.server|npm (start|run dev)|node \S+server\S*\.js|flask run|uvicorn)\b""", ml)
    private val BACKGROUNDED = Regex("""&\s*$|nohup|timeout\s+\d""", ml)

    /**
     * Inspect a run script. [cwd] = where it starts (default /root), [timeout] seconds (1..600, default 120),
     * [allowSystem] = don't warn on writes into /etc, /usr… Returns the cleaned script (sudo stripped, apply_patch expanded).
     */
    fun check(body: String?, cwd: String? = null, timeout: Int? = null, allowSystem: Boolean = false): Result {
        val errors = ArrayList<String>(); val warnings = ArrayList<String>()
        var script = (body ?: "").replace(Regex("""^\n+|\s+$"""), "")
        if (script.isEmpty()) errors += "empty run block"
        val (noPatch, patches) = extractPatches(script); script = noPatch   // the checks below see apply_patch, not the patch text
        for (p in patches.flatMap { patchPaths(it) }) { val w = checkWrite(p, ""); if (!w.ok) errors += "blocked: apply_patch $p: ${w.error}" }
        // sudo: you are root
        if (SUDO_ANY.containsMatchIn(script)) {
            warnings += "removed sudo: you are already root"
            script = SUDO_STRIP.replace(script, "$1$2")
        }
        for ((re, why) in HARD_BLOCK) if (re.containsMatchIn(script)) errors += "blocked: $why"
        for (c in commands(script)) {
            val w = c.split(Regex("""\s+"""))
            if (Regex("""^(rm|shred|unlink)$""").matches(w[0]) || (w[0] == "find" && Regex("""-delete\b|-exec\s+rm\b""").containsMatchIn(c))) {
                val targets = if (w[0] == "find") listOf(w.getOrNull(1) ?: ".") else w.drop(1).filter { !it.startsWith("-") }
                for (p in targets) if (!insideWorkspace(p, cwd)) errors += "blocked: ${w[0]} outside the workspace ($p)"
                if (w[0] == "rm" && targets.any { WHOLE_HOME.containsMatchIn(it) }) errors += "blocked: deleting the whole workspace/home"
            }
            if (Regex("""^(mv|cp|chmod|chown|truncate|ln)$""").matches(w[0])) {
                val dest = w.drop(1).filter { !it.startsWith("-") }.lastOrNull()
                if (dest != null && Regex("""^/(etc|usr|bin|sbin|lib|boot|var/lib/dpkg)(/|$)""").containsMatchIn(dest) && !allowSystem) warnings += "writes into system path $dest; prefer the workspace or apt-get"
                if (dest != null && Regex("""^/(system|data|vendor|proc|sys|dev)(/|$)""").containsMatchIn(dest)) errors += "blocked: ${w[0]} into $dest"
            }
            if (w[0] == "git" && Regex("""\bpush\b.*(--force\b|\s-f\b)""").containsMatchIn(c)) warnings += "force push: only if the user asked for it"
        }
        // A team checked curl|sh per simple command, but commands() splits on "|" so it never fired; checked on the whole script here.
        if (CURL_SH.containsMatchIn(script)) warnings += "piping a download into sh: check the source first"
        fakeRun(script)?.let { errors += it }
        if (SERVER_FG.containsMatchIn(script) && !BACKGROUNDED.containsMatchIn(script)) warnings += "starts a server in the foreground: background it (cmd > log 2>&1 &) or use timeout, then test it with curl"
        val t = (timeout ?: 120).coerceIn(1, 600)
        script = expandPatches(script, patches)
        return Result(errors.isEmpty(), script, patches.flatMap { patchPaths(it) }, errors, warnings, t, "timeout -k 5 $t bash -lc ${shq(script)}")
    }

    fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------ fake / no-op run blocks
    private val NOOP_LINE = Regex("""^(true|:|exit( 0)?|echo(\s+[^${'$'}`>|;&<(]*)?|printf\s+["'][^"'${'$'}`%]*["'])$""")
    private val FAKE_ACTION = Regex("""^\s*echo\b.*\b(saved|remembered|added (the )?job|job (added|done)|marked .* done|memory updated|task complete)\b""", setOf(ic, ml))
    private val FAKE_STORE = Regex("""(>>?|tee( -a)?)\s*\S*(memory|memories|jobs?|tasks?)\.(txt|md|json)\b""", ic)
    private val REAL_STORE = Regex("""/root/\S+/(memory|jobs?|tasks?)\.""")
    private val PROMPT_LINE = Regex("""^\s*(\${'$'} |root@[^#\n]*# )\S""", ml)
    private val NON_PROMPT_LINE = Regex("""^(?!\s*(\${'$'} |root@|#))\S.*$""", ml)
    private val MADE_UP = Regex("""^(total \d+|drwx|-rw-|Cloning into|Successfully installed|exit (code )?\d+$)""", ml)

    /** A run block that does nothing real, pretends to be another tool, or contains made-up output: the error, else null. */
    fun fakeRun(script: String?): String? {
        val s = (script ?: "").trim()
        val lines = s.split("\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) return "no-op run block (only comments)"
        if (lines.all { NOOP_LINE.containsMatchIn(it) }) return "no-op run block: echo/true does nothing; say it in text instead, or run the real command"
        if (FAKE_ACTION.containsMatchIn(s)) return "fake action: use the remember/add_job/done_job/update_agent block, not an echo"
        if (FAKE_STORE.containsMatchIn(s) && !REAL_STORE.containsMatchIn(s)) return "fake action: memory and jobs live in the app; use remember / add_job, not a file"
        if (PROMPT_LINE.containsMatchIn(s) && NON_PROMPT_LINE.containsMatchIn(s) && s.contains('\n')) return "run block contains pasted prompt/output lines; put only the commands"
        if (MADE_UP.containsMatchIn(s)) return "run block contains made-up output; put only the commands and wait for the real result"
        return null
    }

    // ------------------------------------------------------------------ deleting the job's own files (A team v2.10)
    private fun norm(p: String): String = p.replace(Regex("""^["']|["']$"""), "")
        .replace(Regex("""^(~|\${'$'}HOME|\${'$'}\{HOME\})(?=/|$)"""), "/root").replace(Regex("""^(?!/)"""), "/root/").replace(Regex("""/+$"""), "")

    /** Files this job wrote ([edited], the CompletenessGate's set) that [script] would delete (rm/unlink/shred). */
    fun deletesEdited(script: String?, edited: Collection<String>?): List<String> {
        val mine = (edited ?: emptyList()).map { norm(it) }.toSet(); val hit = LinkedHashSet<String>()
        if (mine.isEmpty()) return emptyList()
        var cwd = "/root"
        for (c in commands(script)) {
            val w = c.split(Regex("""\s+"""))
            if (w[0] == "cd" && w.size > 1 && w[1].isNotEmpty()) { cwd = norm(w[1]); continue }
            if (!Regex("""^(rm|unlink|shred)$""").matches(w[0])) continue
            for (t in w.drop(1).filter { !it.startsWith("-") }) {
                val p = if (Regex("""^(/|~|\${'$'}HOME|\${'$'}\{HOME\})""").containsMatchIn(t)) norm(t) else norm(cwd + "/" + t.replace(Regex("""^\./"""), ""))
                for (f in mine) if (f == p || f.startsWith("$p/")) hit += f
            }
        }
        return hit.toList()
    }
    fun deleteMsg(files: List<String>): String = "blocked: that deletes ${files.joinToString(", ")}, which you wrote for this task. Keep it: the user needs it. Delete only scratch files you made for testing."

    // ------------------------------------------------------------------ servers and `cmd &` (A team v2.15, AT-71)
    val SERVER = Regex("""^\s*(?:(?:sudo|exec|nohup)\s+)?(?:python3?\s+-m\s+(?:http\.server|flask\s+run|uvicorn)|python3?\s+\S*(?:server|app|manage)\.py\b(?:\s+runserver)?|flask\s+run|uvicorn\s|gunicorn\s|node\s+\S*(?:server|app|index)\.m?js\b|npm\s+(?:start|run\s+(?:dev|start|serve|preview))\b|yarn\s+(?:start|dev)\b|pnpm\s+(?:start|dev)\b|npx\s+(?:serve|http-server|vite|next\s+dev)\b|http-server\b|serve\s+-|php\s+-S\s|ruby\s+-run\s+-e\s+httpd|rails\s+s(?:erver)?\b|hugo\s+server|jekyll\s+serve|live-server\b)""", ic)

    fun portOf(line: String): Int? {
        val m = Regex("""(?:-p|--port)[ =](\d{2,5})\b""").find(line) ?: Regex("""\bphp\s+-S\s+[\w.]*:(\d{2,5})""").find(line)
            ?: Regex("""http\.server\s+(\d{2,5})\b""").find(line) ?: Regex("""\bPORT=(\d{2,5})\b""").find(line) ?: Regex(""":(\d{4,5})\b""").find(line)
        if (m != null) return m.groupValues[1].toInt()
        return when {
            line.contains("http.server") -> 8000
            Regex("""flask\s+run""").containsMatchIn(line) -> 5000
            line.contains("uvicorn") -> 8000
            Regex("""npm\s+(start|run)|yarn|pnpm|vite|next\s+dev""").containsMatchIn(line) -> null
            Regex("""http-server|live-server""").containsMatchIn(line) -> 8080
            Regex("""npx\s+serve|serve\s+-""").containsMatchIn(line) -> 3000
            else -> null
        }
    }

    data class Detached(val script: String, val port: Int?, val log: String, val count: Int)

    /** JSON.stringify for a string (what the A team used to quote the command for bash -c). */
    private fun jsonQuote(s: String): String {
        val b = StringBuilder("\"")
        for (ch in s) when (ch) {
            '"' -> b.append("\\\""); '\\' -> b.append("\\\\"); '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); '\t' -> b.append("\\t")
            '\b' -> b.append("\\b"); '\u000C' -> b.append("\\f")
            else -> if (ch < ' ') b.append(String.format("\\u%04x", ch.code)) else b.append(ch)
        }
        return b.append('"').toString()
    }

    /**
     * Servers (python3 -m http.server, npm start, node server.js…) and `cmd &` lines rewritten to run detached with a log:
     * `nohup bash -c "<cmd>" > /tmp/argus-bg/<id>-<n>.log 2>&1 < /dev/null &`, then a 2 s wait that prints the URL and the
     * log tail. Null when nothing needs detaching.
     */
    fun detachServers(script: String?, id: String? = null): Detached? {
        val safeId = (id ?: java.lang.Long.toString(System.currentTimeMillis(), 36)).replace(Regex("""[^A-Za-z0-9_-]"""), "")
        var n = 0; var port: Int? = null; var log: String? = null
        val out = (script ?: "").split("\n").map { l ->
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("#") || Regex("""<<\s*['"]?\w+""").containsMatchIn(t)) return@map l
            val amp = Regex("""[^&]&\s*$""").containsMatchIn(t) && !Regex(""">\s*\S+\s+2>&1\s*&\s*$""").containsMatchIn(t) && !Regex("""^nohup\b.*>""").containsMatchIn(t)
            val srv = SERVER.containsMatchIn(t) && !Regex("""&\s*$""").containsMatchIn(t)
            if (!amp && !srv) return@map l
            val cmd = t.replace(Regex("""&\s*$"""), "").replace(Regex("""^nohup\s+"""), "").trim(); n++
            log = "/tmp/argus-bg/$safeId-$n.log"; port = port ?: portOf(cmd)
            "mkdir -p /tmp/argus-bg; nohup bash -c " + jsonQuote(cmd).replace("$", "\\$").replace("`", "\\`") + " > $log 2>&1 < /dev/null & echo \"[background] started pid \$! · log $log\""
        }.toMutableList()
        if (n == 0) return null
        out += "sleep 2; U=\$(grep -oE 'https?://(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0)(:[0-9]+)?[^ ]*' $log 2>/dev/null | head -n 1); [ -n \"\$U\" ] && echo \"[background] URL \$U\"" +
            (if (port != null) " || echo \"[background] URL http://localhost:$port\"" else "") + "; tail -n 5 $log 2>/dev/null; true"
        return Detached(out.joinToString("\n"), port, log!!, n)
    }

    // ------------------------------------------------------------------ run feedback (A team loop control)
    private val HINTS: List<Pair<Regex, (String) -> String>> = listOf(
        Regex("""pytest: (command )?not found|No module named '?pytest""") to { _ -> "pytest is not installed here and the network may be off. Do not try to install it: run the tests with the standard library (python3 -m unittest discover -v, or python3 -m unittest <module>)." },
        Regex("""apply_patch: (command )?not found""") to { _ -> "apply_patch only works as apply_patch <<'EOF' with the patch (*** Begin Patch … *** End Patch) and EOF on its own line. Or rewrite the file with write_file." },
        Regex("""apply_patch: hunk \d+: these lines are not in""") to { _ -> "The patch context did not match the file. cat the file (or sed -n) and copy the lines exactly, or rewrite the file with write_file." },
        Regex("""Ran 0 tests|NO TESTS RAN|no tests ran""") to { _ -> "No tests were found. List them (find . -name \"test*.py\") and run them by path: cd into the folder that has them and run python3 -m unittest discover -s <dir> -v, or python3 <test_file>.py." },
        Regex("""command not found|: not found""", ic) to { m -> val c = Regex("""(?:bash: (?:line \d+: )?)?([\w.+-]+): (?:command )?not found""").find(m)?.groupValues?.get(1); "\"${c ?: "that command"}\" isn't installed. Check with \"command -v\", install it (apt-get install -y <pkg>, pip install <pkg>) or use a tool that is there." },
        Regex("""No module named '?([\w.]+)""") to { m -> "Python module missing (${Regex("""No module named '?([\w.]+)""").find(m)?.groupValues?.get(1)}). Install it with pip, or use the standard library." },
        Regex("""Cannot find module '([^']+)'""") to { m -> "Node module missing (${Regex("""Cannot find module '([^']+)'""").find(m)?.groupValues?.get(1)}). npm install it in the project, or fix the path." },
        Regex("""No such file or directory""", ic) to { _ -> "A path is wrong. List the directory (ls -la) and use the real path; do not guess." },
        Regex("""Permission denied""", ic) to { _ -> "Permission denied. You are root, so sudo will not help: check the file mode (chmod +x) or whether it is outside the workspace." },
        Regex("""Could not resolve host|Network is unreachable|Temporary failure in name resolution|ENOTFOUND|EAI_AGAIN""", ic) to { _ -> "No network. Use local files, a local package (pip install ./pkg.whl, npm install ./pkg.tgz --offline) or tell the user." },
        Regex("""SyntaxError|IndentationError|unexpected token|syntax error""", ic) to { _ -> "Syntax error. Open the file around the reported line (sed -n \"START,ENDp\" file), fix it, and re-run." },
        Regex("""(\d+) (failed|errors?)\b|FAILED|AssertionError|not ok \d+""") to { _ -> "Tests failed. Read the first failure, fix the code (not the test), and re-run the tests." },
        Regex("""fatal: not a git repository""", ic) to { _ -> "Not a git repo here: cd into the repo or run git init." },
        Regex("""merge conflict|CONFLICT \(""", ic) to { _ -> "Merge conflict: open the files with <<<<<<< markers, resolve, git add, then commit." },
        Regex("""address already in use|EADDRINUSE""", ic) to { _ -> "Port in use: find the process (ss -ltnp or lsof -i) and stop it, or use another port." },
        Regex("""Killed|out of memory|MemoryError""", ic) to { _ -> "Out of memory: work on less data at once." },
    )
    private val APPERR = Regex("""CANNOT LINK EXECUTABLE|proot (error|warning|info)|proot: |workspace (is )?not ready|libtalloc|Bad system call""", ic)

    /** The tool-result text for a finished run: exit code, the output tail, and a concrete retry hint (plus a loop hint). */
    fun feedback(res: RunResult, loopHint: String? = null): String {
        val out = res.out + (if (res.err.isNotEmpty()) "\n" + res.err else "")
        var s = "[run] exit ${res.code}${if (res.timedOut) " (timed out)" else ""}\n" + (if (out.length > 6000) "…" + out.takeLast(6000) else out.trim().ifEmpty { "(no output)" })
        if (APPERR.containsMatchIn(out)) return s + "\nNOTE: this is an app/workspace-level failure, not something to fix from the shell. Tell the user and suggest updating the ARGUS app."
        if (res.timedOut) s += "\nHINT: it timed out. Run long jobs in the background (cmd > log 2>&1 &) and check the log, or make the work smaller."
        else if (res.code != 0) { val h = HINTS.firstOrNull { it.first.containsMatchIn(out) }; s += "\nHINT: " + (h?.second?.invoke(out) ?: "It failed. Read the error above and fix the cause before trying again; do not repeat the same command unchanged.") }
        if (!loopHint.isNullOrEmpty()) s += "\nLOOP: $loopHint"
        return s
    }
}

/** The A team's apply_patch program (python3), verbatim. */
private const val AP_PY_SRC = """import sys, os, base64
P = base64.b64decode(sys.argv[1]).decode('utf-8', 'replace').replace('\r\n', '\n')
L = P.split('\n')
def die(m):
    sys.stderr.write('apply_patch: ' + m + '\nNothing was changed.\n'); sys.exit(1)
def chk(p):
    p = p.strip()
    if not p: die('a file line has no path')
    if '..' in p.split('/'): die('".." is not allowed in ' + p)
    a = os.path.abspath(os.path.expanduser(p))
    if not (a.startswith('/root/') or a.startswith('/tmp/')): die('only files inside /root or /tmp can be patched (' + p + ')')
    return os.path.expanduser(p)
i, ops = 0, []
while i < len(L) and not L[i].startswith('*** Begin Patch'): i += 1
if i == len(L): die('no "*** Begin Patch" line; use the format *** Begin Patch / *** Update File: path / @@ / -old / +new / *** End Patch')
i += 1
while i < len(L):
    l = L[i]
    if l.startswith('*** End Patch'): break
    if l.startswith('*** Add File:'):
        p = chk(l.split(':', 1)[1]); i += 1; body = []
        while i < len(L) and not L[i].startswith('*** '):
            body.append(L[i][1:] if L[i].startswith('+') else L[i]); i += 1
        while body and body[-1] == '': body.pop()
        ops.append(('add', p, body)); continue
    if l.startswith('*** Delete File:'):
        ops.append(('del', chk(l.split(':', 1)[1]))); i += 1; continue
    if l.startswith('*** Update File:'):
        p = chk(l.split(':', 1)[1]); i += 1; mv = None
        if i < len(L) and L[i].startswith('*** Move to:'): mv = chk(L[i].split(':', 1)[1]); i += 1
        hunks, cur = [], None
        while i < len(L) and not (L[i].startswith('*** ') and not L[i].startswith('*** End of File')):
            x = L[i]
            if x.startswith('@@'):
                cur = {'hdr': x[2:].strip().strip('@').strip(), 'lines': []}; hunks.append(cur)
            elif not x.startswith('*** End of File'):
                if cur is None: cur = {'hdr': '', 'lines': []}; hunks.append(cur)
                cur['lines'].append(x)
            i += 1
        for h in hunks:
            while h['lines'] and h['lines'][-1] == '': h['lines'].pop()
        ops.append(('upd', p, mv, hunks)); continue
    i += 1
if not ops: die('the patch has no *** Add File / *** Update File / *** Delete File sections')
def find(lines, old, start):
    for norm in (lambda s: s, lambda s: s.rstrip(), lambda s: s.strip()):
        o = [norm(x) for x in old]
        for base in (start, 0):
            for k in range(base, len(lines) - len(o) + 1):
                if all(norm(lines[k + j]) == o[j] for j in range(len(o))): return k
    return -1
out, done = {}, []
for op in ops:
    if op[0] == 'add':
        out[op[1]] = '\n'.join(op[2]) + '\n'; done.append('added ' + op[1]); continue
    if op[0] == 'del':
        if not os.path.isfile(op[1]): die(op[1] + ' does not exist')
        out[op[1]] = None; done.append('deleted ' + op[1]); continue
    _, p, mv, hunks = op
    if p in out and out[p] is not None: src = out[p]
    elif os.path.isfile(p): src = open(p, encoding='utf-8').read()
    else: die(p + ' does not exist (use *** Add File: for a new file)')
    nl = src.endswith('\n') or src == ''
    lines = src.split('\n')
    if nl and lines and lines[-1] == '': lines.pop()
    pos = 0
    for n, h in enumerate(hunks, 1):
        old, new = [], []
        for x in h['lines']:
            if x.startswith('+'): new.append(x[1:])
            elif x.startswith('-'): old.append(x[1:])
            else:
                t = x[1:] if x.startswith(' ') else x; old.append(t); new.append(t)
        if h['hdr']:
            for k in range(pos, len(lines)):
                if lines[k].strip() == h['hdr'] or (len(h['hdr']) > 3 and h['hdr'] in lines[k]): pos = k; break
        if not old:
            at = pos + 1 if h['hdr'] and pos < len(lines) else len(lines)
            lines[at:at] = new; pos = at + len(new); continue
        k = find(lines, old, pos)
        if k < 0:
            die('hunk %d: these lines are not in %s (copy them exactly from the file, or cat it first):\n%s' % (n, p, '\n'.join(old[:6])))
        res, oi = [], 0
        for x in h['lines']:   # context lines keep the file's own text (whitespace included)
            if x.startswith('+'): res.append(x[1:])
            elif x.startswith('-'): oi += 1
            else: res.append(lines[k + oi]); oi += 1
        lines[k:k + len(old)] = res; pos = k + len(res)
    txt = '\n'.join(lines) + ('\n' if nl and lines else '')
    if mv: out[p] = None; out[mv] = txt; done.append('updated %s -> %s' % (p, mv))
    else: out[p] = txt; done.append('updated ' + p)
for p, t in out.items():
    if t is None:
        if os.path.exists(p): os.remove(p)
        continue
    d = os.path.dirname(p)
    if d: os.makedirs(d, exist_ok=True)
    with open(p, 'w', encoding='utf-8') as f: f.write(t)
print('Done! ' + ', '.join(done))
"""
