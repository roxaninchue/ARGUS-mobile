package com.blaze.assistant.connect

/**
 * P4-CN-03/06: what a connector or MCP tool can do, as the 05 §9.1 `ActionClass` (P2-SF-01's policy engine reads the
 * same names), and whether it may run without a card.
 *
 * Read-only is decided in this order: the catalog's own `readOnly` flag (argus-connect) or MCP `readOnlyHint`;
 * otherwise Bot HQ's read-only slug heuristic (BH-24, composio.ts isReadOnlySlug) on the tool name. Anything that is
 * not READ goes behind an approval card unless the user (or the bot's grant) said Always for that tool. Pure.
 *
 * Seam: until P2's `policy.ActionClass` / `Policy.decide` is on this branch, [Action] mirrors it name for name and
 * [needsCard] stands in for `Policy.decide(...) == ASK`.
 */
object ConnectRisk {
    /** Same names and order as 05 §9.1 `enum class ActionClass`. */
    enum class Action { READ, WRITE_LOCAL, WRITE_REMOTE, SEND, SPEND, DELETE, SETTINGS, CREDENTIAL, COMPUTE_PAID, PHYSICAL }

    /** BH-24: `_(GET|LIST|…)_` anywhere in the slug (upper-cased, with `_` padding so a leading/trailing verb counts). */
    private val READ_SLUG = Regex("_(GET|LIST|SEARCH|FETCH|FIND|READ|RETRIEVE|QUERY|LOOKUP|CHECK|DESCRIBE|VIEW|COUNT)_")
    private val SEND = Regex("_(SEND|SENDS|POST|REPLY|PUBLISH|TWEET|SHARE|INVITE|NOTIFY|COMMENT|DM|FORWARD)_")
    private val DELETE = Regex("_(DELETE|REMOVE|TRASH|ARCHIVE|DESTROY|PURGE|CLEAR|UNSUBSCRIBE|REVOKE|DROP)_")
    private val SPEND = Regex("_(PAY|PAYMENT|CHARGE|PURCHASE|BUY|ORDER|CHECKOUT|INVOICE|REFUND|TRANSFER|SUBSCRIBE|DONATE)_")
    private val SETTINGS = Regex("_(SETTINGS?|PERMISSIONS?|SHARING|WEBHOOK|ADMIN|ROLE)_")
    private val CREDENTIAL = Regex("_(TOKEN|SECRET|PASSWORD|KEY|CREDENTIALS?|OAUTH)_")

    /** The tool name as a padded upper slug: `gmail_send_email` → `_GMAIL_SEND_EMAIL_`; camelCase is split too. */
    fun slug(name: String): String = "_" + name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .replace(Regex("[^A-Za-z0-9]+"), "_").uppercase().trim('_') + "_"

    fun looksReadOnly(name: String): Boolean {
        val s = slug(name)
        return READ_SLUG.containsMatchIn(s) && !SEND.containsMatchIn(s) && !DELETE.containsMatchIn(s) && !SPEND.containsMatchIn(s)
    }

    /**
     * The ActionClass of a tool. [readOnly] = the catalog or MCP hint (null = unknown, use the slug heuristic);
     * [destructive] = MCP `destructiveHint`.
     */
    fun classify(name: String, readOnly: Boolean? = null, destructive: Boolean = false): Action {
        val s = slug(name)
        if (destructive || DELETE.containsMatchIn(s) && readOnly != true) return Action.DELETE
        if (readOnly == true) return Action.READ
        return when {
            SPEND.containsMatchIn(s) -> Action.SPEND
            SEND.containsMatchIn(s) -> Action.SEND
            CREDENTIAL.containsMatchIn(s) && !READ_SLUG.containsMatchIn(s) -> Action.CREDENTIAL
            SETTINGS.containsMatchIn(s) && !READ_SLUG.containsMatchIn(s) -> Action.SETTINGS
            readOnly == null && looksReadOnly(name) -> Action.READ
            else -> Action.WRITE_REMOTE
        }
    }

    /** Per-tool rule as Mcp.kt / BotAppGrant store it: ask | always | off (05 Rule ASK / ALLOW / NEVER). */
    enum class Rule { ALLOW, ASK, NEVER;
        companion object {
            fun of(s: String?): Rule? = when (s?.trim()?.lowercase()) {
                "allow", "always", "on" -> ALLOW; "ask" -> ASK; "never", "off", "deny" -> NEVER; else -> null }
        }
        fun wire() = when (this) { ALLOW -> "always"; ASK -> "ask"; NEVER -> "off" }
    }

    /**
     * Whether a call needs an approval card. READ never does; SPEND, DELETE and CREDENTIAL always do (an Always on them
     * is ignored, like A-team RISKY); everything else does unless the tool's rule is ALLOW. Tainted turns (the bot read
     * mail / web / MCP output this turn) always ask for anything but READ (05 §7, K6). [autopilot] does not skip cards
     * for connector writes: they leave the phone.
     */
    fun needsCard(action: Action, rule: Rule?, tainted: Boolean = false): Boolean = when {
        action == Action.READ -> false
        rule == Rule.NEVER -> true      // caller refuses before asking; kept true so a slip never runs it silently
        action == Action.SPEND || action == Action.DELETE || action == Action.CREDENTIAL -> true
        tainted -> true
        rule == Rule.ALLOW -> false
        else -> true
    }

    /** Plain words for the card's first line. */
    fun words(action: Action): String = when (action) {
        Action.READ -> "reads"
        Action.WRITE_LOCAL -> "changes something on this phone"
        Action.WRITE_REMOTE -> "changes something in the app"
        Action.SEND -> "sends something in your name"
        Action.SPEND -> "can spend money"
        Action.DELETE -> "deletes or removes something"
        Action.SETTINGS -> "changes settings or sharing"
        Action.CREDENTIAL -> "touches a key or password"
        Action.COMPUTE_PAID -> "uses a paid service"
        Action.PHYSICAL -> "acts in the real world"
    }
}
