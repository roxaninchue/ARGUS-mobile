package com.blaze.assistant.ui.team

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotDraft
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.BotThreads
import com.blaze.assistant.team.TeamEdits
import com.blaze.assistant.team.TeamStore
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.BotOrb
import com.blaze.assistant.ui.argus.ManagerOrb
import com.blaze.assistant.ui.argus.OrbFace
import com.blaze.assistant.ui.argus.OrbSize
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ArgusField
import com.blaze.assistant.ui.argus.kit.ArgusSheet
import com.blaze.assistant.ui.argus.kit.ArgusToasts
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.ChipModel
import com.blaze.assistant.ui.argus.kit.FilterChips
import com.blaze.assistant.ui.argus.kit.MenuRow
import com.blaze.assistant.ui.argus.kit.Segmented
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** P1-TM-04: what the shell asked the bot editor for (Team's New bot / Edit soul, the top bar's New › Bot). */
sealed class BotFlow {
    object New : BotFlow()
    data class Edit(val botId: String) : BotFlow()
}

/** New bot: Blank / From a template / Describe it. */
@Composable
fun NewBotSheet(onChoice: (NewBotChoice) -> Unit, onDismiss: () -> Unit) {
    ArgusSheet(onDismiss = onDismiss, title = "New bot") {
        for (ch in NewBotChoice.entries) MenuRow(ch.icon, ch.title, { onChoice(ch) }, sub = ch.sub)
    }
}

/** The template picker, by group (Specialists, Demo team, Modes). */
@Composable
fun TemplateSheet(templates: List<BotTemplate>, onPick: (BotTemplate) -> Unit, onDismiss: () -> Unit) {
    val c = Argus.colors
    ArgusSheet(onDismiss = onDismiss, title = "From a template") {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            var last = ""
            for (t in templates) {
                if (t.group != last) {
                    last = t.group
                    Text(t.group.uppercase(), style = ArgusType.section, color = c.mute,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
                }
                MenuRow("bot", t.title, { onPick(t) }, sub = t.sub)
            }
        }
    }
}

/** Describe it: the user's words go to ARGUS ([BotEditorModel.describePrompt]), which drafts the bot in Chat. */
@Composable
fun DescribeBotSheet(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    ArgusSheet(onDismiss = onDismiss, title = "Describe your bot", primary = "Ask ${BotRules.MANAGER_NAME}",
        onPrimary = { if (text.isNotBlank()) onSend(BotEditorModel.describePrompt(text)) }, primaryEnabled = text.isNotBlank()) {
        ArgusField(text, { text = it }, "What should it do?", Modifier.padding(horizontal = 6.dp),
            placeholder = "Keeps my reading list and reminds me on Sundays", singleLine = false)
    }
}

/** The Manager's read-only card: no fields, no Delete. */
@Composable
fun ManagerCardSheet(card: ManagerCard, onDismiss: () -> Unit) {
    val c = Argus.colors
    ArgusSheet(onDismiss = onDismiss, title = card.name, cancel = "Close") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ManagerOrb(OrbSize.LG.dp, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(card.label, style = ArgusType.bodyStrong, color = c.ink)
                Text(card.portfolio, style = ArgusType.preview, color = c.mute)
            }
        }
        Text(card.note, style = ArgusType.body, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** A labelled block in the editor. */
@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    val c = Argus.colors
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, style = ArgusType.meta, color = c.mute, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        content()
    }
}

/**
 * The editor sheet: Name, Label, Email, Portfolio, Instructions, Look (6 faces × 8 colours + any colour), Reports to,
 * Effort, Mode; Delete for an existing bot (never the Manager, which opens [ManagerCardSheet] instead). Stateless:
 * [onChange] gets every edit, [onSave] / [onDelete] the buttons.
 */
@Composable
fun BotEditorSheet(
    state: EditorState,
    team: List<Bot>,
    onChange: ((BotDraft) -> BotDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Argus.colors
    val d = state.draft
    var wheel by remember { mutableStateOf(false) }
    ArgusSheet(onDismiss = onDismiss, title = state.title, primary = state.primary, onPrimary = onSave, primaryEnabled = state.canSave) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Look first: the orb as it will show
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
                BotOrb(d.face?.let { OrbFace.of(it) } ?: OrbFace.forName(d.name), ArgusTokens.botColor(d.color) ?: 0xFFCBD5E1L, OrbSize.LG.dp,
                    contentDescription = "${d.name.ifBlank { "New bot" }}'s look")
            }
            ArgusField(d.name, { v -> onChange { it.copy(name = v) } }, "Name", placeholder = "Nova")
            ArgusField(d.label, { v -> onChange { it.copy(label = v) } }, "Label", placeholder = "Research, Sales ops…")
            ArgusField(d.email, { v -> onChange { it.copy(email = v) } }, "Email", placeholder = "nova@argus.ai")
            ArgusField(d.portfolio, { v -> onChange { it.copy(portfolio = v) } }, "Portfolio", placeholder = "Accounts, projects, clients this bot owns", singleLine = false)
            ArgusField(d.instructions, { v -> onChange { it.copy(instructions = v) } }, "Instructions", placeholder = "How this bot should work (optional)", singleLine = false)

            Section("Look") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotDraft.FACES.forEachIndexed { i, n ->
                        val on = (d.face ?: OrbFace.forName(d.name).ordinal) == i
                        Box(Modifier.size(52.dp).clip(CircleShape).then(if (on) Modifier.border(2.dp, c.ink, CircleShape) else Modifier)
                            .clickable(role = Role.RadioButton) { onChange { it.copy(face = i) } }
                            .semantics { contentDescription = n; selected = on }, contentAlignment = Alignment.Center) {
                            BotOrb(OrbFace.of(i), ArgusTokens.botColor(d.color) ?: 0xFFCBD5E1L, OrbSize.SM.dp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BotDraft.COLORS.forEachIndexed { i, hex ->
                        val on = d.color.equals(hex, true)
                        Box(Modifier.size(48.dp).clickable(role = Role.RadioButton) { onChange { it.copy(color = hex) } }
                            .semantics { contentDescription = BotDraft.COLOR_NAMES[i]; selected = on }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Color((ArgusTokens.botColor(hex) ?: 0xFFCBD5E1L).toInt()))
                                .then(if (on) Modifier.border(2.dp, c.ink, CircleShape) else Modifier))
                        }
                    }
                    ArgusButton(if (wheel) "Done" else "Any colour", { wheel = !wheel }, icon = "palette")
                }
                if (wheel) com.blaze.assistant.ui.ColorWheelPicker(d.color, { hex -> BotEditorModel.wheelColor(hex)?.let { col -> onChange { it.copy(color = col) } } },
                    Modifier.padding(horizontal = 8.dp))
            }
            Section("Reports to") {
                val opts = remember(team, state.botId) { BotEditorModel.reportsToOptions(team, state.botId) }
                FilterChips(opts.map { ChipModel(it.id, it.name) }, d.reportsTo, { id -> onChange { it.copy(reportsTo = id) } })
            }
            Section("Effort") { Segmented(BotEditorModel.EFFORT_CHIPS, d.effort, { e -> onChange { it.copy(effort = e) } }) }
            Section("Mode") { Segmented(BotEditorModel.MODE_CHIPS, d.mode, { m -> onChange { it.copy(mode = m) } }) }

            for (p in state.problems) Text(p, style = ArgusType.preview, color = c.mute, modifier = Modifier.padding(horizontal = 14.dp))
            if (state.canDelete) ArgusButton("Delete", onDelete, Modifier.padding(top = 8.dp, start = 8.dp), kind = ButtonKind.DANGER, icon = "trash-2")
        }
    }
}

/**
 * P1-TM-04: the whole bot flow the shell opens: New bot → Blank / template / Describe, the editor, the Manager's card,
 * and the delete confirmation. Reads and writes team.db off the main thread through [TeamEdits]; every save or delete
 * tells [BotThreads.rosterChanged] so Team reloads. [onDescribe] sends the Describe text to ARGUS (the shell opens Chat).
 */
@Composable
fun BotFlowHost(flow: BotFlow?, onClose: () -> Unit, onDescribe: (String) -> Unit) {
    if (flow == null) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var graph by remember { mutableStateOf<com.blaze.assistant.team.TeamGraph.Graph?>(null) }
    LaunchedEffect(Unit) {
        try { graph = withContext(Dispatchers.IO) { com.blaze.assistant.team.TeamGraph.get(ctx.applicationContext) } }
        catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BotFlowHost.open", t); ArgusToasts.show("Team couldn't open."); onClose() }
    }
    val g = graph ?: return
    BotFlowScreen(flow, g.store, g.threads, onClose, onDescribe)
}

private sealed class Step {
    object Choose : Step()
    object Templates : Step()
    object Describe : Step()
    data class Editor(val s: EditorState) : Step()
    data class Manager(val card: ManagerCard) : Step()
    data class ConfirmDelete(val s: EditorState) : Step()
}

@Composable
fun BotFlowScreen(flow: BotFlow, store: TeamStore, threads: BotThreads, onClose: () -> Unit, onDescribe: (String) -> Unit) {
    val c = Argus.colors
    val scope = rememberCoroutineScope()
    val edits = remember(store) { TeamEdits(store) }
    var team by remember { mutableStateOf<List<Bot>>(emptyList()) }
    var step by remember { mutableStateOf<Step?>(null) }
    LaunchedEffect(flow) {
        val (all, first) = withContext(Dispatchers.IO) {
            val all = store.all()
            all to when (flow) {
                is BotFlow.New -> Step.Choose
                is BotFlow.Edit -> {
                    val b = all.firstOrNull { it.id == flow.botId }
                    when {
                        b == null -> null
                        BotEditorModel.readOnly(b) -> Step.Manager(BotEditorModel.managerCard(b))
                        else -> Step.Editor(BotEditorModel.open(all, b, store.soul(b.id)))
                    }
                }
            }
        }
        team = all
        if (first == null) { ArgusToasts.show("That bot isn't on the team any more."); onClose() } else step = first
    }
    when (val st = step) {
        null -> Unit
        Step.Choose -> NewBotSheet(onChoice = { ch ->
            step = when (ch) {
                NewBotChoice.BLANK -> Step.Editor(BotEditorModel.open(team))
                NewBotChoice.TEMPLATE -> Step.Templates
                NewBotChoice.DESCRIBE -> Step.Describe
            }
        }, onDismiss = onClose)
        Step.Templates -> {
            val ts = remember { BotEditorModel.templates() }
            TemplateSheet(ts, onPick = { t -> step = Step.Editor(BotEditorModel.open(team, draft = BotEditorModel.fromTemplate(t, team))) }, onDismiss = onClose)
        }
        Step.Describe -> DescribeBotSheet(onSend = { t -> onClose(); onDescribe(t) }, onDismiss = onClose)
        is Step.Manager -> ManagerCardSheet(st.card, onDismiss = onClose)
        is Step.Editor -> BotEditorSheet(st.s, team,
            onChange = { f -> step = Step.Editor(BotEditorModel.edit(st.s, team, f)) },
            onSave = {
                val s = st.s
                scope.launch {
                    val r = withContext(Dispatchers.IO) { if (s.isNew) edits.create(s.draft, "user") else edits.update(s.botId!!, s.draft, "user") }
                    when (r) {
                        is TeamStore.Result.Ok -> { threads.rosterChanged(r.bot.id); ArgusToasts.show(if (s.isNew) "${r.bot.name} added" else "Saved"); onClose() }
                        is TeamStore.Result.Refused -> step = Step.Editor(s.copy(problems = listOf(r.why)))
                    }
                }
            },
            onDelete = { step = Step.ConfirmDelete(st.s) },
            onDismiss = onClose)
        is Step.ConfirmDelete -> {
            val b = team.firstOrNull { it.id == st.s.botId }
            ArgusSheet(onDismiss = { step = Step.Editor(st.s) }, title = "Delete ${st.s.draft.name}?",
                primary = if (b?.kind == com.blaze.assistant.team.BotKind.DOMAIN) "Switch off" else "Delete", primaryKind = ButtonKind.DANGER,
                onPrimary = {
                    val id = st.s.botId ?: return@ArgusSheet
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { edits.delete(id) }
                        if (r is TeamStore.Result.Ok) { threads.rosterChanged(id); ArgusToasts.show("Bot removed") }
                        else if (r is TeamStore.Result.Refused) ArgusToasts.show(r.why)
                        onClose()
                    }
                }) {
                b?.let { Text(TeamCopy.deleteBody(it), style = ArgusType.body, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            }
        }
    }
}

/**
 * P1-TM-04: the Delete / Keep sheet for a delete agent_delete is waiting on (A-team's confirm card). [name] = the bot.
 * Keep, or closing the sheet, keeps the bot: only Delete (or a yes in the chat) deletes it.
 */
@Composable
fun PendingDeleteSheet(name: String, onDelete: () -> Unit, onKeep: () -> Unit) {
    val c = Argus.colors
    ArgusSheet(onDismiss = onKeep, title = "Delete $name?", primary = "Delete", primaryKind = ButtonKind.DANGER, onPrimary = onDelete, cancel = "Keep") {
        Text("${BotRules.MANAGER_NAME} asked to delete $name. Its open jobs move to Unassigned.", style = ArgusType.body, color = c.mute,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
