package com.blaze.assistant.agent.query

/**
 * 0.51.0 BlazeQuery verification for commands and code: before a shell command runs (and in the reply rules for code), its
 * program, flags and API names are checked against the evidence BlazeQuery fetched for this turn. The result is a short
 * line appended to the tool result: "verified against developer.android.com" or "unverified: --foo isn't in the fetched
 * docs". It never blocks or changes the command (the confirm card / CommandPolicy still decide what runs).
 * Pure (VerifyTest).
 */
object Verify {
    data class Result(val program: String, val checked: List<String>, val missing: List<String>, val source: String?) {
        val verified: Boolean get() = source != null && missing.isEmpty() && checked.isNotEmpty()
    }

    private val flagRe = Regex("""(?:^|\s)(--?[A-Za-z][\w-]*)""")
    private val apiRe = Regex("""\b([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)+)\s*\(|\b([A-Z][A-Za-z0-9]+::[a-z_]\w*)""")
    private val shellWords = setOf("sudo", "env", "time", "nohup", "exec", "command", "busybox")

    /** The program a command runs (after sudo / env / VAR=x), or "". Only the first command of a pipeline or chain. */
    fun program(cmd: String): String {
        val first = cmd.trim().split(Regex("""\s*(?:\|\||&&|;|\|)\s*""")).firstOrNull().orEmpty()
        return first.split(Regex("\\s+")).firstOrNull { it.isNotBlank() && it !in shellWords && !it.contains('=') }?.substringAfterLast('/').orEmpty()
    }

    /** The flags of the first command ("-la" counts as one token; "-" alone and numbers are skipped). */
    fun flags(cmd: String): List<String> {
        val first = cmd.trim().split(Regex("""\s*(?:\|\||&&|;|\|)\s*""")).firstOrNull().orEmpty()
        return flagRe.findAll(first).map { it.groupValues[1].substringBefore('=') }.filter { it.length >= 2 }.distinct().toList()
    }

    /** Dotted API calls (`Foo.bar(`) and `Type::method` in code. */
    fun apis(code: String): List<String> = apiRe.findAll(code).map { it.groupValues[1].ifBlank { it.groupValues[2] } }.distinct().take(8).toList()

    /**
     * [cmd] against [evidence]. The program must be named in an item; each flag must appear in an item that names the program
     * (or in a code item). [Result.source] = the host of the best item that covers it.
     */
    fun check(cmd: String, evidence: List<Evidence>): Result {
        val prog = program(cmd)
        val fl = flags(cmd)
        if (prog.isEmpty() || evidence.isEmpty()) return Result(prog, fl, fl, null)
        val about = evidence.filter { e -> Regex("(?i)(^|[^\\w-])" + Regex.escape(prog) + "([^\\w-]|$)").containsMatchIn(e.text) }
        if (about.isEmpty()) return Result(prog, listOf(prog) + fl, listOf(prog) + fl, null)
        val missing = fl.filter { f -> about.none { e -> Regex("(^|[^\\w-])" + Regex.escape(f) + "([^\\w-]|$)").containsMatchIn(e.text) } }
        val best = about.maxByOrNull { it.score + it.authority }!!
        return Result(prog, listOf(prog) + fl, missing, best.host)
    }

    /** Code (a snippet the model wrote) against the evidence: which API names the fetched docs mention. */
    fun checkCode(code: String, evidence: List<Evidence>): Result {
        val a = apis(code)
        if (a.isEmpty() || evidence.isEmpty()) return Result("", a, a, null)
        val missing = a.filter { api -> val last = api.substringAfterLast('.').substringAfterLast("::"); evidence.none { it.text.contains(last) } }
        val src = evidence.filter { e -> a.any { e.text.contains(it.substringAfterLast('.').substringAfterLast("::")) } }.maxByOrNull { it.score }?.host
        return Result("", a, missing, src)
    }

    /** The line appended to a tool result ("" when there was nothing to check against). */
    fun note(r: Result): String = when {
        r.checked.isEmpty() || r.program.isEmpty() && r.source == null && r.missing.isEmpty() -> ""
        r.verified -> "\n[BlazeQuery] verified against ${r.source}" + if (r.checked.size > 1) " (${r.checked.drop(1).joinToString(" ")})" else ""
        r.source != null -> "\n[BlazeQuery] partly verified against ${r.source}; unverified: ${r.missing.joinToString(" ")}"
        else -> "\n[BlazeQuery] unverified: the fetched docs don't cover ${r.missing.take(4).joinToString(" ")}"
    }
}
