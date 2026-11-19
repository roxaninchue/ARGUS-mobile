package com.blaze.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.blaze.assistant.BrandName
import com.blaze.assistant.i18n.tr
import com.blaze.assistant.notify.Inbox
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ArgusField
import com.blaze.assistant.ui.argus.kit.ArgusSheet
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.ButtonSize

/**
 * Owner 16 Nov 2026: the bell opens the Inbox. "Not working" first (each with its Fix), then Argus's messages as one
 * thread per chat, with a reply box under each: the reply goes into that chat, like a reply from the notification.
 * Opening it marks everything read. P2-SF-03: "Needs you" (the one [com.blaze.assistant.policy.NeedsYou] list, drawn by
 * the shared InboxList) comes first.
 */
@Composable
fun InboxSheet(onDismiss: () -> Unit) {
    val c = Argus.colors
    val ctx = LocalContext.current
    val items by Inbox.items.collectAsState()
    val drafts = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) { Inbox.markAllRead() }
    val problems = remember(items) { Inbox.problems(items) }
    val threads = remember(items) { Inbox.threads(items) }
    // P2-SF-03: what waits on you (approvals, questions, connects, failed jobs, suggestions), the same list as Desk → Inbox
    val needs = com.blaze.assistant.ui.team.rememberNeedsYou()
    ArgusSheet(onDismiss = onDismiss, title = tr("Inbox")) {
        if (needs.isNotEmpty()) {
            Text(tr("Needs you"), style = ArgusType.meta, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            com.blaze.assistant.ui.team.InboxList(needs, onOpen = {
                com.blaze.assistant.ui.team.DeskState.show(com.blaze.assistant.ui.team.DeskModel.Segment.JOBS); onDismiss()
            })
        }
        if (needs.isEmpty() && problems.isEmpty() && threads.isEmpty())
            Text(tr("Nothing here yet. Messages {0} sends you while you're away, and anything that isn't working, show up here.", BrandName.NAME),
                style = ArgusType.meta, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp))
        if (problems.isNotEmpty()) {
            Text(tr("Not working"), style = ArgusType.meta, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            for (p in problems) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.text, style = ArgusType.field, color = c.ink)
                    Text(InboxText.ago(p.at, System.currentTimeMillis()), style = ArgusType.meta, color = c.mute)
                }
                if (p.key == com.blaze.assistant.phone.PhoneControl.INBOX_KEY)
                    ArgusButton(tr("Fix"), { com.blaze.assistant.phone.PhoneControl.openSettings(ctx) }, kind = ButtonKind.GLASS, size = ButtonSize.S)
            }
        }
        for ((chat, lines) in threads) {
            Text(InboxText.threadTitle(chat), style = ArgusType.meta, color = c.mute, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp))
            for (m in lines.takeLast(InboxText.LINES)) {
                val mine = m.kind == Inbox.Kind.YOU
                Text((if (mine) tr("You") else BrandName.NAME) + " \u00b7 " + InboxText.ago(m.at, System.currentTimeMillis()),
                    style = ArgusType.meta, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                Text(m.text, style = ArgusType.field, color = if (mine) c.mute else c.ink, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp))
            }
            val draft = drafts[chat].orEmpty()
            fun send() {
                val t = drafts[chat].orEmpty().trim(); if (t.isEmpty()) return
                drafts[chat] = ""
                Inbox.message(t, chat, fromUser = true)
                com.blaze.assistant.agent.Agent.replyFromNotification(chat.ifBlank { null }, null, t)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArgusField(draft, { drafts[chat] = it }, label = tr("Reply to {0}", BrandName.NAME), modifier = Modifier.weight(1f),
                    placeholder = tr("Reply"), imeAction = ImeAction.Send, onIme = { send() })
                ArgusButton(tr("Send"), { send() }, kind = ButtonKind.PRIMARY, size = ButtonSize.S)
            }
        }
    }
}

/** Pure words for the Inbox (InboxTextTest). */
object InboxText {
    const val LINES = 6

    fun threadTitle(chatId: String): String = if (chatId.isBlank() || chatId == com.blaze.assistant.agent.Mind.MAIN) "Chat" else "Another chat"

    fun ago(at: Long, now: Long): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }
}
