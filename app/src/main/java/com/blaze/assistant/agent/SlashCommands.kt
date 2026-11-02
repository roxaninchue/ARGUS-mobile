package com.blaze.assistant.agent

/**
 * 0.50.0: one registry for the "/" list in the composer and for Blaze's own answer to "what / commands do I have?".
 * Both read [Commands.all] (the user's own commands, the app commands, the prompt shortcuts), so what the menu shows and
 * what Blaze says can never disagree. Pure apart from [Commands.custom] (Settings; empty when Settings isn't ready).
 */
object SlashCommands {
    enum class Group(val title: String) { YOURS("Your commands"), APP("App commands"), VIEWS("Open a view"), PROMPTS("Prompt shortcuts") }

    data class Entry(val name: String, val args: String, val desc: String, val group: Group) {
        /** "/translate <lang> <text>" */
        val label: String get() = "/" + name + if (args.isNotBlank()) " $args" else ""
        /** Runs at once when tapped (no argument needed); otherwise "/name " is put in the box to finish typing. */
        val runNow: Boolean get() = !args.trim().startsWith("<")
        val fill: String get() = "/" + name + if (args.isNotBlank()) " " else ""
    }

    private val viewCommands = setOf("projects", "design", "jobs", "terminal", "music", "video", "schedule", "accounts", "doctor", "router",
        "skills", "gallery", "browser", "home", "doing", "look", "sync", "views", "settings", "memory", "routines")

    fun groupOf(c: Command): Group = when {
        c.custom -> Group.YOURS
        c.prompt != null -> Group.PROMPTS
        c.name in viewCommands -> Group.VIEWS
        else -> Group.APP
    }

    /** Every command once, the user's own first (a custom /brainstorm wins over the built-in one). */
    fun entries(all: List<Command> = safeAll()): List<Entry> =
        all.distinctBy { it.name }.map { Entry(it.name, it.args, it.desc, groupOf(it)) }

    private fun safeAll(): List<Command> = try { Commands.all() } catch (_: Throwable) { Commands.actions }

    /**
     * The "/" menu for what's typed (with or without the slash): names starting with it first, then names containing it,
     * then descriptions containing it. Empty query = everything. Nothing after a space (the command is being given input).
     */
    fun filter(typed: String, all: List<Entry> = entries()): List<Entry> {
        val raw = typed.trim().removePrefix("/")
        if (typed.removePrefix("/").contains(' ')) return emptyList()
        val q = raw.lowercase()
        if (q.isEmpty()) return all
        val starts = all.filter { it.name.startsWith(q) }
        val contains = all.filter { it !in starts && it.name.contains(q) }
        val desc = all.filter { it !in starts && it !in contains && it.desc.lowercase().contains(q) }
        return starts + contains + desc
    }

    /** Is the composer showing the "/" menu for [text]? A lone "/" or "/word" with no space and no second slash. */
    fun menuFor(text: String): Boolean = text.startsWith("/") && !text.startsWith("//") && !text.contains(' ') && !text.contains('\n') && text.length <= 32

    /** Blaze's answer to "what / commands do I have", from the same list the menu shows. */
    fun answer(all: List<Entry> = entries()): String = buildString {
        append("Type / in the message box to see these, filter by typing, and tap one to use it:\n")
        for (g in Group.values()) {
            val list = all.filter { it.group == g }
            if (list.isEmpty()) continue
            append("\n**").append(g.title).append("**\n")
            list.forEach { e -> append("- `").append(e.label).append("` \u2014 ").append(e.desc).append('\n') }
        }
        append("\nYou can add your own in Settings \u203a Slash commands.")
    }.trim()
}
