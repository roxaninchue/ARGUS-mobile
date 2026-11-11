package com.blaze.assistant.agent.guards

/**
 * P2-SF-07 (AT-34 code gate, AT-69 StepGuard): the A team's CompletenessGate and StepGuard as pure Kotlin. Ported from
 * A team `app/www/guard.js` (owner grant, MIT); the cases in A team `evals/test/guard.test.mjs`, `v29.test.mjs` and
 * `v211.test.mjs` are JUnit cases in CompletenessTest.
 *
 * [CompletenessGate]: a bot may not say "done" on a coding job until the build / tests / demo ran end-to-end AFTER the
 * last code edit and passed (exit 0, no timeout, no TODO / NotImplementedError in the output). It records every shell
 * run ([CompletenessGate.onRun]: edits are found by [Completeness.editedFiles], checks by [Completeness.VERIFY] and
 * [Completeness.RUNS_PROGRAM]) and every file write ([CompletenessGate.onWrite]). [CompletenessGate.canFinish] nudges at
 * most twice, then lets the turn end with a warning (graded as unverified).
 *
 * [StepGuard]: caps steps, stops a loop on the 3rd identical command + result (default maxRepeats = 2), warns on the
 * 2nd, and after three failures in a row asks for a different approach. It complements
 * [com.blaze.assistant.agent.LoopGuard] (which refuses to RUN a 3rd identical tool call + args before it runs): StepGuard
 * judges command + RESULT after the run, and adds the step cap and the three-failures hint.
 *
 * Pure Kotlin (no android.*). Not wired into Agent.kt; the hook is described in the P2-SF-07 report.
 */
object Completeness {
    private val ic = RegexOption.IGNORE_CASE

    val CODE_EXT = Regex("""\.(py|js|mjs|cjs|ts|tsx|jsx|go|rs|c|cc|cpp|h|hpp|java|kt|rb|php|sh|lua|swift)$""")
    val VERIFY = Regex("""\b(pytest|python3? -m (unittest|pytest)|npm (test|run (test|build|lint))|node --test|go (test|build|vet)|cargo (test|build)|make( \w+)?|ctest|mvn|gradle|ruff|flake8|eslint|tsc|py_compile|node --check|bash -n|\./(run|test|build)\S*\.sh)\b""")
    /** Also stdin scripts (python3 - <<'PY' … PY, node -, … | python3), which models use to run quick tests. */
    val RUNS_PROGRAM = Regex("""\b(python3?|node|bash|sh|\./)\s*\S+\.(py|js|mjs|sh)\b|\bpython3? -[mc]\b|\bnode -e\b|\b(python3?|node)\s+-(\s|$)|\|\s*(python3?|node)\b|\bcurl\b|(^|\s)\./[\w.-]+""")
    val DONE_CLAIM = Regex("""\b(done|finished|complete[ds]?|all set|ready to use|it works|works now|implemented|ship(ped)?|fixed)\b""", ic)
    val SCOPE_NOTE = Regex("""\b(cut|left out|didn'?t (get to|include|implement)|not (yet )?(included|implemented|done)|skipped|out of scope|smaller (version|demo)|scoped (it )?down|next steps?|still (to do|missing))\b""", ic)
    val STUB_OUT = Regex("""\b(TODO|FIXME|NotImplementedError|not implemented)\b""")

    private val EDIT_RES = listOf(
        Regex("""(?:^|[\s;&|])(?:cat|printf|echo)[^\n>]*>>?\s*([^\s;&|<>]+)"""),
        Regex("""\btee(?:\s+-a)?\s+([^\s;&|<>]+)"""),
        Regex("""\bsed\s+-i\S*\s+(?:'[^']*'|"[^"]*"|\S+)\s+([^\s;&|]+)"""),
        Regex("""open\(\s*['"]([^'"]+)['"]\s*,\s*['"][wa]"""),
        Regex("""writeFileSync\(\s*['"]([^'"]+)['"]"""),
        Regex("""\b(?:touch|cp|mv)\s+(?:-\S+\s+)*(?:\S+\s+)*?([^\s;&|]+\.\w+)\s*(?:$|[;&|\n])""", RegexOption.MULTILINE),
        Regex("""^\+\+\+ b/(\S+)""", RegexOption.MULTILINE),
    )
    private val AP_EDITS = Regex("""^# apply_patch edits: (.*)$""", RegexOption.MULTILINE)

    /** Files a run script writes (heredocs, redirects, tee, sed -i, python/node writes, patch, mv, apply_patch). Heuristic. */
    fun editedFiles(script: String?): List<String> {
        val s = script ?: ""; val files = LinkedHashSet<String>()
        for (r in EDIT_RES) for (m in r.findAll(s)) if (!m.groupValues[1].startsWith("/dev/")) files += m.groupValues[1].replace(Regex("""^['"]|['"]$"""), "")
        AP_EDITS.find(s)?.let { m -> m.groupValues[1].split(Regex("""\s+""")).filter { it.isNotEmpty() }.forEach { files += it } }
        return files.toList()
    }

    /**
     * The A team's hardened prompt section (guard.js PROMPT) for terminal / tool use, finishing code and safety, worded for
     * this app's tools (the shell tool and file tools instead of ```run fences). Not added to Agent.kt's prompt by this
     * ticket (zero lines there); a prompt part can include it when the Linux workspace tools are offered.
     */
    val PROMPT: String = listOf(
        "TERMINAL AND TOOLS (strict).",
        "- Run commands with the shell tool: only the commands, no \"\$ \" prompts, no output. Multi-line scripts are fine.",
        "- After a shell or memory tool call, stop and wait for the real result. Never invent, predict or paste output. Never claim a command ran unless you saw its result.",
        "- Use the right tool: the memory tools for memory, the job tools for jobs. Never fake these with echo, files or a shell call. Never run a script that does nothing (echo, true).",
        "- You are root in Ubuntu under proot on a phone. Never use sudo. Work inside /root. Never delete, move or chmod anything outside /root or /tmp, never touch /system, /data, /dev, /proc. Never run rm -rf on / or on the whole home.",
        "- The network may be off. Prefer local files and offline installs (pip install ./x.whl, npm install ./x.tgz --offline). Do not loop on downloads.",
        "- Long jobs: background them (cmd > log 2>&1 &) and check the log. Servers: start in the background, test with curl, then stop them.",
        "- When a command fails: read the error, fix the cause, try a different approach. Never repeat the same failing command unchanged more than once. After three failures, stop and tell the user what blocks you.",
        "- Before editing, look (ls, cat, grep). Make the smallest change that fixes the task. Do not touch tests to make them pass.",
        "- Edit files with the file write tool (whole file) or apply_patch <<'EOF' (*** Begin Patch / *** Update File: path / @@ / -old / +new / *** End Patch) in the shell.",
        "- Before you call code done, test every rule the task states, invalid input and edge cases included, not only the example.",
        "",
        "FINISHING CODE (strict).",
        "- Complete beats ambitious. A smaller version that fully works is better than a bigger one that is broken.",
        "- Before you say done on any coding job, run the build, the tests and the demo end-to-end in one shell call and see them pass. No TODO, FIXME, stubs, placeholders or missing files.",
        "- If you cannot finish everything, cut scope: ship the part that fully works, make its checks pass, and say exactly what you cut and why. Never ship partial or untested code as finished.",
        "- Final message: what works, how you verified it (the command and its result), and what was cut, in a few lines.",
        "",
        "SAFETY.",
        "- Refuse clearly illegal or seriously harmful help (weapons capable of mass harm, malware for others' systems, breaking into accounts or systems that are not the user's, fraud, making hard drugs, violence, stalking, sexual content involving minors). Refuse in one or two sentences, without a lecture, and offer a safe alternative. Never run commands for such a request.",
        "- Do help with look-alikes: security on the user's own systems or a CTF, how things work at a textbook level, chemistry and biology education, lock-picking as a hobby, medical and dosage questions (with \"see a doctor\" when it matters), fiction.",
    ).joinToString("\n")

    /** Shell script that detects and runs build, tests, lint and a stub scan in a project dir. Exit 0 only if all pass. */
    fun checkScript(dir: String?): String = listOf(
        "cd ${CheckRun.shq(dir ?: ".")} || exit 2",
        "fail=0",
        "say(){ printf \"\\n== %s\\n\" \"\$*\"; }",
        "if [ -f package.json ]; then say npm; if grep -q '\"build\"' package.json; then npm run -s build || fail=1; fi; if grep -q '\"test\"' package.json && ! grep -q 'no test specified' package.json; then npm test --silent || fail=1; fi; fi",
        "if [ -f Makefile ] && grep -qE \"^test:\" Makefile; then say make test; make test || fail=1; fi",
        "if [ -f go.mod ]; then say go; go build ./... && go test ./... || fail=1; fi",
        "if [ -f Cargo.toml ]; then say cargo; cargo test -q || fail=1; fi",
        "PY=\$(find . -name \"*.py\" -not -path \"*/node_modules/*\" -not -path \"*/.venv/*\" | head -500)",
        "if [ -n \"\$PY\" ]; then say python compile; python3 -m py_compile \$PY || fail=1;",
        "  if command -v ruff >/dev/null; then ruff check -q . || fail=1; elif python3 -c \"import pyflakes\" 2>/dev/null; then python3 -m pyflakes \$PY || fail=1; fi;",
        "  if ls tests/test_*.py test_*.py */test_*.py >/dev/null 2>&1; then say python tests; if python3 -c \"import pytest\" 2>/dev/null; then python3 -m pytest -q || fail=1; else python3 -m unittest discover -s \"\$(ls -d tests 2>/dev/null || echo .)\" -t . -q || fail=1; fi; fi; fi",
        "JS=\$(find . \\( -name \"*.js\" -o -name \"*.mjs\" \\) -not -path \"*/node_modules/*\" | head -500)",
        "if [ -n \"\$JS\" ]; then say js syntax; for f in \$JS; do node --check \"\$f\" || fail=1; done;",
        "  if [ ! -f package.json ] && ls test/*.test.*js tests/*.test.*js *.test.*js >/dev/null 2>&1; then say node tests; node --test || fail=1; fi; fi",
        "say stubs; if grep -rnE \"\\b(TODO|FIXME|XXX)\\b|NotImplementedError|throw new Error\\(.(not implemented|TODO)\" --include=*.py --include=*.js --include=*.mjs --include=*.ts --include=*.go --include=*.rs . 2>/dev/null | grep -v node_modules | grep -v \"/test\" ; then echo \"stubs or TODOs left\"; fail=1; fi",
        "echo; [ \$fail = 0 ] && echo \"CHECKS PASSED\" || echo \"CHECKS FAILED\"; exit \$fail",
    ).joinToString("\n")
}

/** No "done" on code until a passing end-to-end check after the last edit. One per turn. */
class CompletenessGate {
    /** [ok] = may finish; [message] is fed back to the model when not; [autoCheck] = nothing ran since the edit. */
    data class Finish(val ok: Boolean, val message: String = "", val autoCheck: Boolean = false, val warn: String = "")

    val edited = LinkedHashSet<String>()
    var lastEdit = -1; private set
    var lastVerify = -1; private set
    var lastVerifyOk = false; private set
    private var step = 0
    private var asked = 0

    private val apScript = Regex("""python3 - '[^']*' <<'__ARGUS_AP__'\n[\s\S]*?\n__ARGUS_AP__""")

    /** Record a shell run and its result. */
    fun onRun(script: String?, res: CheckRun.RunResult?) {
        step++
        val s = apScript.replace(script ?: "", "apply_patch")   // a patch edits, it does not verify
        val f = Completeness.editedFiles(s).filter { Completeness.CODE_EXT.containsMatchIn(it) }
        if (f.isNotEmpty()) { edited += f; lastEdit = step }
        val verify = Completeness.VERIFY.containsMatchIn(s); val runs = Completeness.RUNS_PROGRAM.containsMatchIn(s)
        if (verify || (runs && f.isEmpty())) { lastVerify = step; lastVerifyOk = res != null && res.code == 0 && !res.timedOut && !Completeness.STUB_OUT.containsMatchIn(res.out) }
        else if (f.isNotEmpty() && runs) { lastVerify = step; lastVerifyOk = res != null && res.code == 0 }
    }

    /** A file written by a file tool (no shell script to parse). */
    fun onWrite(path: String?) { step++; val p = path ?: ""; if (Completeness.CODE_EXT.containsMatchIn(p)) { edited += p; lastEdit = step } }

    val codeTouched: Boolean get() = edited.isNotEmpty()
    val verified: Boolean get() = lastVerify >= lastEdit && lastVerifyOk

    /** May the bot finish with [finalText]? */
    fun canFinish(finalText: String?): Finish {
        if (!codeTouched) return Finish(true)
        val claims = Completeness.DONE_CLAIM.containsMatchIn(finalText ?: "")
        if (verified) return Finish(true)
        if (asked >= 2) return Finish(true, warn = "finished without a passing end-to-end check")
        asked++
        if (lastVerify < lastEdit) return Finish(false, autoCheck = true, message = "GATE: you changed code (${edited.take(5).joinToString(", ")}) but have not run it since. " +
            "Before you say ${if (claims) "\"done\"" else "anything is finished"}: run the build, the tests and the demo end-to-end in one run block, with checks (asserts or tests) for every rule the task states, " +
            "invalid input and edge cases included, not just the happy path. If something fails and you cannot fix everything, cut scope to a smaller version that fully works, make the checks pass, and state exactly what you cut.")
        return Finish(false, message = "GATE: the last check failed. Fix the code (not the test), or scope down to a smaller version that fully works and passes, then re-run. Do not ship partial code or stubs; say what you cut.")
    }

    /** Final-answer check: a coding job that cut scope must say what was cut. */
    fun needsScopeNote(finalText: String?, cutSomething: Boolean): Boolean = cutSomething && !Completeness.SCOPE_NOTE.containsMatchIn(finalText ?: "")
}

/** Step cap + identical command/result loop stop (3rd repeat) + three-failures hint. One per turn. */
class StepGuard(maxSteps: Int = 8, maxRepeats: Int = 2) {
    data class Before(val ok: Boolean, val reason: String = "")
    data class After(val stop: Boolean, val hint: String = "")

    private val max = if (maxSteps > 0) maxSteps else 8
    private val repeat = if (maxRepeats > 0) maxRepeats else 2
    private var steps = 0
    private var fails = 0
    private val seen = HashMap<String, Int>()

    private fun key(cmd: String, res: CheckRun.RunResult?): String = cmd.replace(Regex("""\s+"""), " ").trim() + "\u0000" + (if (res == null) "" else "${res.code}:" + res.out.takeLast(400))

    /** Call before running a step. */
    fun before(@Suppress("UNUSED_PARAMETER") cmd: String): Before {
        steps++
        if (steps > max) return Before(false, "step limit ($max) reached: summarize what's done and what's left, then stop")
        return Before(true)
    }

    /** Call after a command ran. The same command with the same result more than maxRepeats times stops the loop. */
    fun after(cmd: String, res: CheckRun.RunResult?): After {
        val k = key(cmd, res); val n = (seen[k] ?: 0) + 1; seen[k] = n
        fails = if (res != null && res.code != 0) fails + 1 else 0
        if (n > repeat) return After(true, "Same command, same result, $n times. Stop retrying it: try a different approach or tell the user what is blocking.")
        if (n == repeat) return After(false, "You already ran this exact command and got the same result. Change something before running it again.")
        if (fails >= 3) return After(false, "Three failures in a row. Step back: read the error, check assumptions (paths, versions, network), and try one different approach.")
        return After(false)
    }
}
