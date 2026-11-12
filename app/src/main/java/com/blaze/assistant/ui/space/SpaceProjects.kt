package com.blaze.assistant.ui.space

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.BrandName
import com.blaze.assistant.agent.ChatMeta
import com.blaze.assistant.agent.Project
import com.blaze.assistant.ui.BackTitle
import com.blaze.assistant.ui.BlazeDialog
import com.blaze.assistant.ui.BlazeIconStyle
import com.blaze.assistant.ui.BlazeMenu
import com.blaze.assistant.ui.IconTile
import com.blaze.assistant.ui.BlazeTextField
import com.blaze.assistant.ui.ChatRow
import com.blaze.assistant.ui.RenameChatDialog

/**
 * 0.52.3 Projects tab in the Hark look (replaces ProjectsPane): the list is a serif "Projects" headline, a line on what a
 * project is, a name field with "+ New project", and one large rounded card per project ([SpaceProjectRows] says what
 * each shows). [openId] non-blank opens that project as a page of cards: New chat, Instructions, Brief (with its token
 * cap), the project's chats (tap = open, ⋯ = rename / delete) and Delete project. Same callbacks as the old pane.
 * P1-TM-07: [embedded] = the list inside Desk's Projects segment (no headline; Desk has its own title). Everything else
 * is as before: a project page and its chats still open over the pager.
 */
@Composable
fun SpaceProjectsScreen(
    projects: List<Project>, chats: List<ChatMeta>, busy: Set<String>, needsYou: Set<String>, openId: String,
    onOpen: (String) -> Unit, onBack: () -> Unit, onCreate: (String) -> Unit, onChat: (String) -> Unit,
    onNewChat: (String) -> Unit, onInstructions: (String, String) -> Unit, onDelete: (String) -> Unit,
    onBrief: (String, String) -> Unit = { _, _ -> },
    onRenameChat: (String, String) -> Unit = { _, _ -> }, onDeleteChat: (String) -> Unit = {},
    embedded: Boolean = false,
) {
    val open = projects.firstOrNull { it.id == openId }
    Box(Modifier.fillMaxSize().testTag(SpaceTags.PROJECTS)) {
        if (open == null) ProjectList(projects, chats, busy, needsYou, onOpen, onCreate, embedded)
        else ProjectPage(open, chats, busy, needsYou, onBack, onChat, onNewChat, onInstructions, onDelete, onBrief, onRenameChat, onDeleteChat)
    }
}

@Composable
private fun ProjectList(projects: List<Project>, chats: List<ChatMeta>, busy: Set<String>, needsYou: Set<String>,
                        onOpen: (String) -> Unit, onCreate: (String) -> Unit, embedded: Boolean = false) {
    val look = spaceLook()
    var name by remember { mutableStateOf("") }
    val existing = remember(chats) { chats.map { it.id }.toSet() }
    fun create() { if (name.isNotBlank()) { onCreate(name.trim()); name = "" } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = SpaceInsets.GUTTER_DP.dp, vertical = 10.dp)) {
        // P1-TM-07: inside Desk (its Projects segment) the Desk title and segment row stand in for the headline
        if (!embedded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpaceHeadline("Projects", Modifier.weight(1f))
                // light / dark here too (every screen has the switch; ThemeEverywhereTest)
                com.blaze.assistant.ui.ThemeSwitch(tint = look.ink)
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(SpaceProjectRows.INTRO, style = SpaceType.bodyText(look.sub))
        Spacer(Modifier.height(16.dp))
        SpaceCard {
            BlazeTextField(name, { name = it }, "New project name", modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { create() }))
            Spacer(Modifier.height(12.dp))
            SpacePillButton("New project", { create() }, primary = true, plus = true, tag = SpaceTags.PROJECT_NEW, enabled = name.isNotBlank())
        }
        projects.forEach { p ->
            val status = SpaceProjectRows.status(p.chats, busy, needsYou)
            Spacer(Modifier.height(12.dp))
            SpaceCard(tag = SpaceTags.projectRow(p.id), onClick = { onOpen(p.id) }, onClickLabel = "Open ${p.name}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 0.52.4: the shared round icon tile (a folder) instead of the name's initial
                    IconTile(Icons.Rounded.Folder, tint = look.accent)
                    Spacer(Modifier.width(BlazeIconStyle.gap))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = SpaceType.section(look.ink))
                        Text(SpaceProjectRows.countLabel(SpaceProjectRows.count(p.chats, existing)), style = SpaceType.meta(look.sub))
                    }
                    val chip = SpaceProjectRows.chip(status)
                    if (chip.isNotEmpty()) StatusPill(chip, filled = status == SpaceProjectRows.Status.NEEDS_YOU)
                    else ChevronMark(look.ink)
                }
            }
        }
        if (projects.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            SpaceCard {
                SpaceHeadline("No projects yet", size = SpaceTypeScale.TITLE.sp)
                Spacer(Modifier.height(6.dp))
                Text("Name one above to start. Each project keeps its own chats, instructions and brief.", style = SpaceType.bodyText(look.sub))
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun ProjectPage(open: Project, chats: List<ChatMeta>, busy: Set<String>, needsYou: Set<String>, onBack: () -> Unit,
                        onChat: (String) -> Unit, onNewChat: (String) -> Unit, onInstructions: (String, String) -> Unit,
                        onDelete: (String) -> Unit, onBrief: (String, String) -> Unit,
                        onRenameChat: (String, String) -> Unit, onDeleteChat: (String) -> Unit) {
    val look = spaceLook()
    var ins by remember(open.id) { mutableStateOf(open.instructions) }
    var brief by remember(open.id, open.brief) { mutableStateOf(open.brief) }
    var cut by remember(open.id) { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ChatMeta?>(null) }
    var deleting by remember { mutableStateOf<ChatMeta?>(null) }
    val P = com.blaze.assistant.agent.Projects
    // Unification 6/9: the header spans the screen so its back circle sits at the gutter like the top bar's round
    // button (it sat ~34 dp in when the whole column was padded); the cards under it take the gutter themselves.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackTitle(open.name, onBack = onBack, modifier = Modifier.padding(top = 4.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = SpaceInsets.GUTTER_DP.dp)) {
            Spacer(Modifier.height(8.dp))
            SpacePillButton("New chat in this project", { onNewChat(open.id) }, primary = true, plus = true)
            Spacer(Modifier.height(12.dp))
            SpaceCard {
                CardHead(Icons.Rounded.EditNote) { SpaceHeadline("Instructions", size = SpaceTypeScale.SECTION.sp) }
                Spacer(Modifier.height(8.dp))
                BlazeTextField(ins, { ins = it; onInstructions(open.id, it) }, "What ${BrandName.NAME} should know in every chat here",
                    modifier = Modifier.fillMaxWidth(), singleLine = false, minHeight = 72.dp)
            }
            Spacer(Modifier.height(12.dp))
            SpaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.PushPin, tint = look.accent, small = true)
                    Spacer(Modifier.width(10.dp))
                    SpaceHeadline("Brief", Modifier.weight(1f), size = SpaceTypeScale.SECTION.sp)
                    // The real count (BlazeDb.tokens) against the hard cap; typing or pasting past it is cut at the cap.
                    val used = P.tokens(brief)
                    Text("$used / ${P.BRIEF_TOKENS} tokens", style = SpaceType.caption(if (used >= P.BRIEF_TOKENS * 9 / 10) look.accent else look.sub))
                }
                Spacer(Modifier.height(8.dp))
                BlazeTextField(brief, { t -> val fit = P.cutTokens(t, P.BRIEF_TOKENS); cut = fit.length < t.length; brief = fit; onBrief(open.id, fit) },
                    "Pinned brief: purpose and rules first, then key files and what's in progress. ${BrandName.NAME} keeps it up to date.",
                    modifier = Modifier.fillMaxWidth(), singleLine = false, minHeight = 96.dp)
                if (cut) Text("The brief is at its 600-token cap, so the rest was cut. Put longer details in Instructions: ${BrandName.NAME} searches them when it needs them.",
                    modifier = Modifier.padding(top = 6.dp), style = SpaceType.caption(look.accent))
            }
            Spacer(Modifier.height(12.dp))
            val list = open.chats.mapNotNull { id -> chats.firstOrNull { it.id == id } }
            SpaceCard {
                CardHead(Icons.Rounded.ChatBubbleOutline) { SpaceHeadline("Chats", size = SpaceTypeScale.SECTION.sp) }
                Spacer(Modifier.height(6.dp))
                list.forEach { ch ->
                    Box {
                        ChatRow(ch, ch.id in busy, chip = if (ch.id in needsYou) "Needs you" else "", onMore = { menuFor = ch.id }) { onChat(ch.id) }
                        if (menuFor == ch.id) BlazeMenu(true, onDismiss = { menuFor = "" },
                            items = listOf("Rename" to { renaming = ch }, "Delete chat" to { deleting = ch }))
                    }
                }
                if (list.isEmpty()) Text("No chats yet. Start one with New chat: it keeps this project's instructions and brief.", style = SpaceType.bodyText(look.sub))
            }
            Spacer(Modifier.height(12.dp))
            SpacePillButton("Delete project (chats stay)", { onDelete(open.id) }, icon = Icons.Rounded.Delete)
            Spacer(Modifier.height(110.dp))
        }
    }
    renaming?.let { ch -> RenameChatDialog(ch.title, onDone = { t -> renaming = null; if (t.isNotBlank()) onRenameChat(ch.id, t) }, onDismiss = { renaming = null }) }
    deleting?.let { ch -> BlazeDialog(title = "Delete \u201c${ch.title.ifBlank { "this chat" }.take(40)}\u201d?",
        body = "Its messages are removed from this phone. The project, its instructions and its other chats stay.",
        confirm = "Delete", onConfirm = { deleting = null; onDeleteChat(ch.id) }, warm = true, dismiss = "Keep", onDismiss = { deleting = null }) }
}

/** A project page card's heading with its small icon tile on the left. */
@Composable
private fun CardHead(icon: ImageVector, title: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, tint = spaceLook().accent, small = true)
        Spacer(Modifier.width(10.dp))
        title()
    }
}

/** The Needs you (filled) / Working (outlined) chip on a project card. */
@Composable
private fun StatusPill(text: String, filled: Boolean) {
    val look = spaceLook()
    Box(Modifier.clip(SpaceShapes.pill).background(if (filled) look.primaryFill else Color.Transparent)
        .let { if (filled) it else it.background(look.tile, SpaceShapes.pill) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, maxLines = 1, style = SpaceType.body(SpaceTypeScale.CAPTION.sp, if (filled) look.primaryInk else look.ink, FontWeight.Medium))
    }
}
