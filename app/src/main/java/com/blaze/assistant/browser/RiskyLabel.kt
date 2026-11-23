package com.blaze.assistant.browser

/**
 * A label that spends money, sends, deletes or can't be undone: the in-app browser asks before clicking one.
 * 0.55.11: moved here from phone/Pointer.kt, which went with phone control. Pure (RiskyLabelTest).
 */
object RiskyLabel {
    private val riskyRe = Regex("""\b(pay|payment|send|delete|remove|erase|buy|purchase|order|checkout|check out|transfer|withdraw|confirm|uninstall|post|publish|sign out|log ?out|subscribe|unsubscribe|reset|format|block|report|call)\b""", RegexOption.IGNORE_CASE)

    fun risky(label: String?) = !label.isNullOrBlank() && riskyRe.containsMatchIn(label)
}
