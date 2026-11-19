package com.blaze.assistant.ui.team

import com.blaze.assistant.i18n.tr
import com.blaze.assistant.agent.Specialists
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotDraft
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.ui.argus.kit.ChipModel

/**
 * P1-TM-04: what the bot editor shows and checks, as plain values so BotEditorModelTest runs on the JVM (A-team
 * agentSheet / mgrSheet, Bot HQ new-bot flow, 06 P1-TM-04):
 *  - New bot: Blank, From a template, Describe it ([NewBotChoice]);
 *  - templates ([templates]): the built-in specialists (via [BotRules.workerTemplate]), A-team's demo bots (Nova, Atlas,
 *    Echo, Vega) and Bot HQ's two modes ("Personal assistant", "Teammate");
 *  - Describe it sends ARGUS [describePrompt], which drafts the bot and creates it with agent_create once the user says yes;
 *  - the editor: Name, Label, Email, Portfolio, Instructions (the SOUL), Look (6 faces × 8 colours + any colour),
 *    Reports to ([reportsToOptions]: never itself, never a loop), Effort and Mode as segmented choices; [problems] are
 *    [BotDraft.problems]; Save is [EditorState.canSave];
 *  - the Manager opens a read-only card ([managerCard]) with no Delete.
 */
enum class NewBotChoice(val icon: String, val title: String, val sub: String) {
    BLANK("plus", "Blank", "Start from an empty bot"),
    TEMPLATE("layers", "From a template", "A specialist, a demo bot or a ready-made mode"),
    DESCRIBE("sparkles", "Describe it", "Say what you need and ${BotRules.MANAGER_NAME} drafts it"),
}

/** A template in the picker: [group] is the section it shows under. */
data class BotTemplate(val id: String, val group: String, val title: String, val sub: String, val draft: BotDraft)

data class ReportsToOption(val id: String, val name: String)

/** One open editor: [botId] null = a new bot. */
data class EditorState(val draft: BotDraft, val botId: String? = null, val problems: List<String> = emptyList()) {
    val isNew get() = botId == null
    val title get() = if (isNew) "New bot" else "Edit ${draft.name.ifBlank { "bot" }}"
    val primary get() = if (isNew) "Add bot" else "Save"
    val canSave get() = problems.isEmpty() && draft.name.isNotBlank()
    val canDelete get() = botId != null && BotRules.canDelete(botId)
}

/** The Manager's read-only card: what it is, and why nothing on it can change. */
data class ManagerCard(val name: String, val label: String, val portfolio: String, val note: String)

object BotEditorModel {
    const val GROUP_SPECIALISTS = "Specialists"
    const val GROUP_DEMO = "Demo team"
    const val GROUP_MODES = "Modes"

    val EFFORT_CHIPS = listOf(ChipModel("low", "Low"), ChipModel("medium", "Medium"), ChipModel("high", "High"), ChipModel("xhigh", "Max"))
    val MODE_CHIPS = listOf(ChipModel("personal", "Personal"), ChipModel("teammate", "Teammate"))

    /** What the Describe sheet sends ARGUS. */
    fun describePrompt(text: String): String =
        "Draft a new bot for me: ${text.trim().removeSuffix(".")}. Suggest its name, role, look and instructions, show me the draft, " +
            "and create it with agent_create once I say yes."

    /** A blank new bot's draft: next swatch by roster size (A-team addAgent). */
    fun blank(rosterSize: Int): BotDraft = BotDraft(color = BotDraft.nextColor(rosterSize))

    /** Every template, in picker order: specialists, the A-team demo bots, then the two Bot HQ modes. */
    fun templates(specs: List<Specialists.Spec> = Specialists.builtIn): List<BotTemplate> {
        val out = ArrayList<BotTemplate>()
        specs.forEachIndexed { i, s ->
            val w = BotRules.workerTemplate(s)
            out += BotTemplate("spec:${s.id}", GROUP_SPECIALISTS, s.name, s.prompt.substringBefore(". ").take(80),
                BotDraft(name = w.name, label = s.name, instructions = s.soul.ifBlank { s.prompt }.take(BotDraft.INSTRUCTIONS_MAX),
                    color = BotDraft.colorOf(s.colour) ?: BotDraft.COLORS[i % BotDraft.COLORS.size],
                    toolGroups = w.toolGroups.ifEmpty { BotDraft.DEFAULT_GROUPS }, skills = w.skills))
        }
        // A-team demo() (app.js): name, email, role, portfolio; colours as P1-TM-03's Demo team, Vega's face Tilt
        val demo = listOf(
            arrayOf("Nova", "nova@argus.ai", "Research lead", "Market intel, competitor tracking, Q4 strategy deck", "#22c55e"),
            arrayOf("Atlas", "atlas@argus.ai", "Sales ops", "Enterprise pipeline, 14 accounts in Lusaka & Nairobi", "#f59e0b"),
            arrayOf("Echo", "echo@argus.ai", "Support", "Tier-1 inbox, onboarding flows", "#ef4444"),
            arrayOf("Vega", "vega@argus.ai", "Engineering", "Gateway reliability, model routing", "#06b6d4"))
        for (d in demo) out += BotTemplate("demo:${d[0].lowercase()}", GROUP_DEMO, d[0], d[2],
            BotDraft(name = d[0], email = d[1], label = d[2], portfolio = d[3], color = d[4], face = if (d[0] == "Vega") 1 else null))
        out += BotTemplate("mode:personal", GROUP_MODES, "Personal assistant", "Works for you alone",
            BotDraft(name = "Assistant", label = tr("Personal assistant"), mode = "personal", color = BotDraft.COLORS[6],
                instructions = "You are the user's personal assistant. Keep track of what they ask, follow up, and keep answers short."))
        out += BotTemplate("mode:teammate", GROUP_MODES, "Teammate", "Takes work from the other bots and shares what it learns",
            BotDraft(name = "Teammate", label = tr("Teammate"), mode = "teammate", color = BotDraft.COLORS[5],
                instructions = "You are a teammate on the user's bot team. Take the work other bots hand you, finish it, and save what the whole team should know with note_team."))
        return out
    }

    /** [name], or "<name> 2", "<name> 3"… when a bot (or the Manager's reserved names) already has it. */
    fun uniqueName(name: String, team: Collection<Bot>): String {
        val base = name.trim().ifEmpty { "Bot" }
        if (BotRules.nameProblem(base, team) == null) return base
        var n = 2
        while (true) { val t = "$base $n"; if (BotRules.nameProblem(t, team) == null) return t; n++ }
    }

    /** A template's draft, ready for the editor: a free name, and its colour kept. */
    fun fromTemplate(t: BotTemplate, team: Collection<Bot>): BotDraft = t.draft.copy(name = uniqueName(t.draft.name, team))

    /** Who a bot may report to: the Manager first, then enabled bots on the roster that aren't [selfId] and don't loop. */
    fun reportsToOptions(team: Collection<Bot>, selfId: String?): List<ReportsToOption> {
        val out = arrayListOf(ReportsToOption(BotRules.MANAGER_ID, BotRules.MANAGER_NAME))
        for (b in team) {
            if (BotRules.isManager(b) || !b.enabled || b.hidden || b.id == selfId) continue
            if (selfId != null && BotDraft.loops(selfId, b.id, team)) continue
            out += ReportsToOption(b.id, b.name)
        }
        return out
    }

    /** The editor for [bot] ([soul] = its stored instructions), or a new one from [draft]. */
    fun open(team: Collection<Bot>, bot: Bot? = null, soul: String? = null, draft: BotDraft? = null): EditorState {
        val d = draft ?: bot?.let { BotDraft.of(it, soul) } ?: blank(team.count { it.enabled && !it.hidden })
        return EditorState(d, bot?.id, d.problems(team, bot?.id).takeIf { d.name.isNotBlank() }.orEmpty())
    }

    /** The state after an edit: problems are re-checked (an empty name shows no problem until Save is pressed). */
    fun edit(s: EditorState, team: Collection<Bot>, f: (BotDraft) -> BotDraft): EditorState {
        val d = f(s.draft)
        return s.copy(draft = d, problems = d.problems(team, s.botId).takeIf { d.name.isNotBlank() }.orEmpty())
    }

    /** The colour wheel's hex (#RRGGBB or #RRGGBBAA) as a bot colour; the alpha is dropped (orbs are opaque). */
    fun wheelColor(hex: String): String? = BotDraft.colorOf(hex.trim().let { if (it.startsWith("#") && it.length == 9) it.take(7) else it })

    fun managerCard(m: Bot): ManagerCard = ManagerCard(m.name, m.label, m.portfolio,
        "The built-in team lead. Its name, label, look and instructions are locked, and it can't be deleted.")

    /** True when the editor should open read-only (the Manager). */
    fun readOnly(b: Bot?): Boolean = b != null && BotRules.isManager(b)
}
