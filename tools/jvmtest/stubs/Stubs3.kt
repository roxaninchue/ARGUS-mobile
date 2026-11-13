package com.blaze.assistant.terminal
object DevTools { val names = emptySet<String>() }
/** P2-SF-01: CommandPolicyTest's autopilotRuleUnchanged reads Terminal.risky; copied verbatim from Terminal.kt (the rest needs Android). */
object Terminal {
    private val danger = Regex("(\\brm\\s+-[a-z]*r[a-z]*f?\\s+(/|~|\\*|\\.\\s*$))|\\bmkfs|\\bdd\\s+if=|\\breboot\\b|\\bsu\\b|>\\s*/dev/", RegexOption.IGNORE_CASE)
    fun risky(cmd: String) = danger.containsMatchIn(cmd)
}
