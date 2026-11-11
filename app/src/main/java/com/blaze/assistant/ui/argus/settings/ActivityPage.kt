package com.blaze.assistant.ui.argus.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.blaze.assistant.policy.Audit
import com.blaze.assistant.policy.AuditDao
import com.blaze.assistant.policy.AuditEntry
import com.blaze.assistant.policy.ChainCheck
import com.blaze.assistant.policy.Decision
import com.blaze.assistant.policy.SpendGuard
import com.blaze.assistant.ui.BlazeButton
import com.blaze.assistant.ui.BlazeTextField
import com.blaze.assistant.ui.BtnKind
import com.blaze.assistant.ui.GroupHead
import com.blaze.assistant.ui.KitRow
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.PageHead
import com.blaze.assistant.ui.Rows
import com.blaze.assistant.ui.Segmented
import com.blaze.assistant.ui.Tag
import com.blaze.assistant.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * P2-SF-05 Settings › Activity (04-design-ia.md §4.11 / 05 §7.6): every action that changed something and every
 * decision, newest first, team-wide or per bot, filterable (Needed you / Denied / search), each row opening to its
 * details (chain, trigger, redacted args, device, undo, cost, hash). A status line says whether the hash chain is intact.
 * Export writes the filtered rows as JSON or CSV to the cache and opens the share sheet (FileProvider "<pkg>.files",
 * cache-path "." in res/xml/file_paths.xml).
 *
 * Self-contained: P1's settings tree (P1-SH-06) isn't on main yet. It draws with the existing Settings kit (PageHead,
 * GroupHead, Rows/KitRow, Segmented, Tag, BlazeButton) and sits inside the Settings page's own vertical scroll, so it
 * uses plain Columns (no LazyColumn) and shows [PAGE] rows at a time with "Show more".
 * Registration (one line in ui/SettingsSheet.kt's `when (page)`, plus a SettingsPage entry), see docs/overhaul/P2-SF-05.md:
 *     "activity" -> com.blaze.assistant.ui.argus.settings.ActivityPage()
 */
private const val PAGE = 60

@Composable
fun ActivityPage(dao: AuditDao? = AuditDao.shared) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var who by remember { mutableStateOf<String?>(null) }        // null = team-wide
    var which by remember { mutableStateOf("all") }               // all | you | denied
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(PAGE) }
    var rev by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    var bots by remember { mutableStateOf<List<String>>(emptyList()) }
    var check by remember { mutableStateOf<ChainCheck?>(null) }
    var open by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }

    fun filter() = Audit.Filter(botId = who, query = query, decisions = when (which) {
        "you" -> Decision.NEEDED_YOU; "denied" -> setOf(Decision.DENIED); else -> emptySet() })

    LaunchedEffect(dao, rev) {
        if (dao == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val b = runCatching { dao.bots() }.getOrDefault(emptyList())
            val k = runCatching { dao.verify() }.getOrNull()
            withContext(Dispatchers.Main) { bots = b; check = k }
        }
    }
    LaunchedEffect(dao, who, which, query, limit, rev) {
        if (dao == null) return@LaunchedEffect
        val f = filter()
        rows = withContext(Dispatchers.IO) { runCatching { dao.list(f, limit + 1) }.getOrDefault(emptyList()) }
    }

    PageHead("Activity", "What your team did",
        "Everything a bot changed and every decision about it, newest first. Rows are chained, so any later change to this history shows up here.")

    if (dao == null) {
        Text("Activity starts once your team is set up. Nothing has been recorded on this phone yet.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
        return
    }

    check?.let { k ->
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).semantics { contentDescription = k.line() }) {
            Tag(if (k.ok) "Intact" else "Changed", on = k.ok)
            Spacer(Modifier.width(8.dp))
            Text(k.line(), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp,
                color = if (k.ok) c.sub else com.blaze.assistant.ui.GlassTokens.error))
        }
    }

    // Scope: team-wide, or one bot (chips scroll sideways).
    GroupHead("Who", "")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Whole team", who == null) { who = null; limit = PAGE }
        bots.forEach { b -> Chip(b, who == b) { who = b; limit = PAGE } }
    }
    Spacer(Modifier.height(12.dp))
    Segmented(listOf("all" to "All", "you" to "Needed you", "denied" to "Denied"), which, Modifier.fillMaxWidth()) { which = it; limit = PAGE }
    Spacer(Modifier.height(10.dp))
    BlazeTextField(query, { query = it; limit = PAGE }, "Search tool, bot or details", Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))

    val shown = rows.take(limit)
    if (shown.isEmpty()) {
        Text(if (query.isBlank() && which == "all") "Nothing recorded yet." else "Nothing matches.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
    } else {
        Rows {
            shown.forEachIndexed { i, e ->
                KitRow(title = "${e.botId} · ${e.tool}", sub = rowSub(e), first = i == 0, pill = classLabel(e),
                    pillOn = e.decision == Decision.DENIED, onClick = { open = if (open == e.id) null else e.id })
                if (open == e.id) Details(e)
            }
        }
        if (rows.size > limit) {
            Spacer(Modifier.height(10.dp))
            BlazeButton("Show more", Modifier.fillMaxWidth(), kind = BtnKind.GHOST) { limit += PAGE }
        }
    }

    Spacer(Modifier.height(18.dp))
    GroupHead("Export", "The rows matching this filter, with their hashes, so the history can be checked elsewhere. Secrets were removed before anything was saved.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BlazeButton("Export JSON", Modifier.weight(1f), kind = BtnKind.SECONDARY) {
            scope.launch { note = export(ctx, dao, filter(), json = true) }
        }
        BlazeButton("Export CSV", Modifier.weight(1f), kind = BtnKind.SECONDARY) {
            scope.launch { note = export(ctx, dao, filter(), json = false) }
        }
    }
    if (note.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(note, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = c.sub))
    }
    Spacer(Modifier.height(10.dp))
    BlazeButton("Check history again", Modifier.fillMaxWidth(), kind = BtnKind.GHOST) { rev++ }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(vertical = 4.dp)) { Tag(text, on = on) }
}

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
private fun time(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun classLabel(e: AuditEntry): String = e.actionClass.name.lowercase().replace('_', ' ')

private fun rowSub(e: AuditEntry): String {
    val by = e.decidedBy?.takeIf { it.isNotBlank() && e.decision != Decision.AUTO }?.let { " by $it" }.orEmpty()
    return listOfNotNull(e.decision.label + by, e.result?.takeIf { it.isNotBlank() }, time(e.at)).joinToString(" · ")
}

@Composable
private fun Details(e: AuditEntry) {
    val c = LocalBlaze.current
    val lines = listOfNotNull(
        "When" to time(e.at),
        "Asked by" to (if (e.chain.isEmpty()) e.botId else (e.chain + e.botId).joinToString(" → ")),
        "Started by" to e.trigger,
        "Decision" to (e.decision.label + (e.decidedBy?.let { " · $it" } ?: "") + (e.device?.let { " · on $it" } ?: "")),
        "Details" to e.argsRedacted.ifBlank { "—" },
        e.result?.let { "Result" to it },
        e.undo?.let { "Undo" to it },
        e.costMinor?.let { "Cost" to SpendGuard.money(it, "ZMW") },
        "Row" to "#${e.id} · ${e.hash.take(12)}…",
    )
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
        lines.forEach { (k, v) ->
            Row(Modifier.padding(top = 4.dp)) {
                Text(k, modifier = Modifier.width(84.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.weak))
                Text(v, style = TextStyle(fontFamily = if (k == "Details" || k == "Row") Type.Mono else Type.Sans, fontSize = 12.sp,
                    lineHeight = 17.sp, color = c.ink))
            }
        }
    }
}

/** Write the filtered rows (oldest first) to cache/activity/ and open the share sheet. Returns the line to show. */
private suspend fun export(ctx: Context, dao: AuditDao, f: Audit.Filter, json: Boolean): String = try {
    val (file, mime, n) = withContext(Dispatchers.IO) {
        val all = dao.all()
        val chain = dao.verify(all)
        val rows = Audit.filter(all, f)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(Instant.now().atZone(ZoneId.systemDefault()))
        val dir = File(ctx.cacheDir, "activity").apply { mkdirs() }
        val name = "argus-activity-$stamp." + if (json) "json" else "csv"
        val out = File(dir, name).apply { writeText(if (json) Audit.toJson(rows, chain) else Audit.toCsv(rows)) }
        Triple(out, if (json) "application/json" else "text/csv", rows.size)
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share activity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    "Exported $n rows as ${file.name}."
} catch (e: Exception) {
    "Couldn't export: ${e.message ?: e.javaClass.simpleName}"
}
