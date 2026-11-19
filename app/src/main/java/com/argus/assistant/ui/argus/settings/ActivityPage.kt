package com.argus.assistant.ui.argus.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.argus.assistant.i18n.tr
import com.argus.assistant.policy.Audit
import com.argus.assistant.policy.AuditStore
import com.argus.assistant.policy.AuditView
import com.argus.assistant.ui.argus.Argus
import com.argus.assistant.ui.argus.ArgusType
import com.argus.assistant.ui.argus.kit.ArgusButton
import com.argus.assistant.ui.argus.kit.ButtonKind
import com.argus.assistant.ui.argus.kit.ButtonSize
import com.argus.assistant.ui.argus.kit.ChipModel
import com.argus.assistant.ui.argus.kit.Segmented
import com.argus.assistant.ui.argus.kit.SettingsGroup
import com.argus.assistant.ui.argus.kit.SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ActivityTags {
    const val PAGE = "activity-page"
    const val CHAIN = "activity-chain"
    const val GROUP = "activity-group"
}

/**
 * P2-SF-05 Settings › Privacy › Activity, ARGUS look: every non-read action the bots took or asked for (audit_log), by
 * bot or by team, filtered by what was decided, with the hash chain's state and an export (CSV / JSON through the share
 * sheet). Stateless; the model is [AuditView] (pure, AuditChainTest). Surfaces are the kit's opaque SettingsGroup and
 * colours are Argus tokens (ink / mute / meta), so light and dark keep AA contrast.
 */
@Composable
fun ActivitySection(
    entries: List<Audit.Entry>,
    nameOf: (String) -> String,
    teamOf: (String) -> String?,
    onExport: (csv: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Argus.colors
    var by by remember { mutableStateOf(AuditView.By.BOT) }
    var filter by remember { mutableStateOf(AuditView.Filter.ALL) }
    val zone = remember { java.time.ZoneId.systemDefault() }
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm") }
    Column(modifier.fillMaxWidth().testTag(ActivityTags.PAGE), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(tr("ACTIVITY"), style = ArgusType.section, color = c.meta, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        Text(tr("Everything your bots did or asked to do, beyond looking things up. Secrets are cut out of every row."), style = ArgusType.sub, color = c.mute)

        val line = AuditView.chainLine(entries)
        SettingsGroup(listOf(SettingsItem(if (line.startsWith("Chain verified")) "shield-check" else "shield-alert", line,
            tr("{0} entries", entries.size.toString()), chevron = false)), Modifier.testTag(ActivityTags.CHAIN))

        Segmented(AuditView.By.values().map { ChipModel(it.name, tr(if (it == AuditView.By.BOT) "By bot" else "By team")) }, by.name,
            { k -> by = AuditView.By.valueOf(k) })
        Segmented(AuditView.Filter.values().map { ChipModel(it.name, tr(it.label)) }, filter.name, { k -> filter = AuditView.Filter.valueOf(k) })

        val groups = AuditView.group(entries, by, filter, nameOf, teamOf)
        if (groups.isEmpty()) SettingsGroup(listOf(SettingsItem("list-checks", "Nothing here yet", chevron = false)))
        groups.forEach { g ->
            SettingsGroup(g.rows.take(MAX_ROWS).map { e ->
                val who = if (by == AuditView.By.TEAM && e.botId != g.key) nameOf(e.botId) + " · " else ""
                SettingsItem(AuditView.icon(e.decision), who + e.tool.replace('_', ' '),
                    tr(AuditView.decisionLabel(e.decision)) + " · " + fmt.format(java.time.Instant.ofEpochMilli(e.at).atZone(zone)), chevron = false)
            }, Modifier.testTag(ActivityTags.GROUP), title = g.title)
            if (g.rows.size > MAX_ROWS) Text(tr("{0} older entries are in the export.", (g.rows.size - MAX_ROWS).toString()),
                style = ArgusType.sub, color = c.mute, modifier = Modifier.padding(start = 4.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArgusButton(tr("Export CSV"), { onExport(true) }, kind = ButtonKind.PRIMARY, size = ButtonSize.S, icon = "upload", enabled = entries.isNotEmpty())
            ArgusButton(tr("Export JSON"), { onExport(false) }, kind = ButtonKind.GLASS, size = ButtonSize.S, icon = "download", enabled = entries.isNotEmpty())
        }
    }
}

private const val MAX_ROWS = 50

/** [ActivitySection] wired: audit_log and the bots (team.db) off the main thread; export goes through Media.shareIntent. */
@Composable
fun ActivityLive() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<Audit.Entry>>(emptyList()) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var teams by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                val g = com.argus.assistant.team.TeamGraph.get(ctx)
                val roster = g.store.all()
                Triple(AuditStore(g.sql).all(), roster.associate { it.id to it.name } + (com.argus.assistant.team.BotRules.MANAGER_ID to com.argus.assistant.BrandName.NAME),
                    roster.mapNotNull { b -> b.parentId?.let { b.id to it } }.toMap())
            }
        }.onSuccess { (e, n, t) -> entries = e; names = n; teams = t }
    }
    ActivitySection(entries, { names[it] ?: it }, { teams[it] }, onExport = { csv -> scope.launch {
        runCatching {
            val f = withContext(Dispatchers.IO) {
                java.io.File(ctx.cacheDir, "argus-activity." + if (csv) "csv" else "json").apply {
                    writeText(if (csv) AuditView.csv(entries) else AuditView.json(entries))
                }
            }
            ctx.startActivity(com.argus.assistant.media.Media.shareIntent(f))
        }
    } })
}
