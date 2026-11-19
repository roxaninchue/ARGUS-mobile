package com.blaze.assistant.ui.argus.settings

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
import com.blaze.assistant.i18n.tr
import com.blaze.assistant.policy.PermissionsModel
import com.blaze.assistant.policy.PolicyStore
import com.blaze.assistant.policy.SpendGuard
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.SendScope
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ArgusField
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.ButtonSize
import com.blaze.assistant.ui.argus.kit.ChipModel
import com.blaze.assistant.ui.argus.kit.Segmented
import com.blaze.assistant.ui.argus.kit.SettingsGroup
import com.blaze.assistant.ui.argus.kit.SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PermissionsTags {
    const val PAGE = "perm-page"
    const val ALLOWS = "perm-allows"
    const val BOT = "perm-bot"
    const val AUTOPILOT = "perm-autopilot"
}

/**
 * P2-SF-04 Settings › Privacy › Approvals & permissions (BH-25, NEW-09), ARGUS look. Stateless: the model is
 * [PermissionsModel] (pure, PermissionsTest). Sections: standing allows by bot with "Ask again"; per bot autonomy, send
 * scope and the SpendGuard cap + per-payment maximum; what still asks on Autopilot. Every surface is the kit's opaque
 * SettingsGroup; colours are Argus tokens (ink / mute / meta), so light and dark keep AA contrast.
 */
@Composable
fun PermissionsSection(
    allows: List<PermissionsModel.BotAllows>,
    bots: List<PermissionsModel.BotPerms>,
    onAskAgain: (tool: String, botId: String) -> Unit,
    onSave: (PermissionsModel.BotPerms) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Argus.colors
    Column(modifier.fillMaxWidth().testTag(PermissionsTags.PAGE), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(tr("APPROVALS & PERMISSIONS"), style = ArgusType.section, color = c.meta, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        Text(tr("What each bot may do without asking you. Paying, deleting and sign-ins always ask."), style = ArgusType.sub, color = c.mute)

        // standing allows
        if (allows.isEmpty()) {
            SettingsGroup(listOf(SettingsItem("shield-check", "No standing allows yet", chevron = false)), Modifier.testTag(PermissionsTags.ALLOWS),
                title = "Always allowed")
        } else allows.forEach { g ->
            SettingsGroup(g.rows.map { r ->
                SettingsItem("rotate-ccw", PermissionsModel.toolLabel(r.tool), "Ask again", chevron = false, onClick = { onAskAgain(r.tool, r.botId) })
            }, Modifier.testTag(PermissionsTags.ALLOWS), title = tr("Always allowed: {0}", g.botName))
        }

        // per bot
        bots.forEach { b -> BotBlock(b, onSave) }

        // Autopilot exceptions
        SettingsGroup(PermissionsModel.autopilotExceptions().map { SettingsItem("shield-alert", it.label, chevron = false) },
            Modifier.testTag(PermissionsTags.AUTOPILOT), title = "Always asks, even on Autopilot")
    }
}

@Composable
private fun BotBlock(b: PermissionsModel.BotPerms, onSave: (PermissionsModel.BotPerms) -> Unit) {
    val c = Argus.colors
    var cap by remember(b) { mutableStateOf(if (b.capMonthlyMinor > 0) SpendGuard.money(b.capMonthlyMinor, "").trim() else "") }
    var tx by remember(b) { mutableStateOf(if (b.perTxMaxMinor > 0) SpendGuard.money(b.perTxMaxMinor, "").trim() else "") }
    Column(Modifier.fillMaxWidth().testTag(PermissionsTags.BOT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(b.botName.uppercase(), style = ArgusType.section, color = c.meta, modifier = Modifier.padding(start = 4.dp, top = 6.dp).semantics { heading() })
        Text(tr("Autonomy"), style = ArgusType.body, color = c.ink)
        Segmented(PermissionsModel.AUTONOMY.map { ChipModel(it.value.name, tr(it.label)) }, b.autonomy.name,
            { k -> onSave(b.copy(autonomy = Autonomy.valueOf(k))) })
        Text(tr(PermissionsModel.AUTONOMY.first { it.value == b.autonomy }.sub), style = ArgusType.sub, color = c.mute)
        Text(tr("Sending in your name"), style = ArgusType.body, color = c.ink)
        Segmented(PermissionsModel.SEND_SCOPE.map { ChipModel(it.value.name, tr(it.label)) }, b.sendScope.name,
            { k -> onSave(b.copy(sendScope = SendScope.valueOf(k))) })
        Text(tr(PermissionsModel.SEND_SCOPE.first { it.value == b.sendScope }.sub), style = ArgusType.sub, color = c.mute)
        Text(tr("Spending ({0})", b.currency), style = ArgusType.body, color = c.ink)
        Text(tr(PermissionsModel.capLine(b)), style = ArgusType.sub, color = c.mute)
        ArgusField(cap, { cap = it }, tr("Monthly cap"), Modifier.fillMaxWidth(), placeholder = tr("0 = every payment asks"))
        ArgusField(tx, { tx = it }, tr("Largest single payment"), Modifier.fillMaxWidth(), placeholder = tr("Empty = only the monthly cap"))
        val capMinor = PermissionsModel.parseMinor(cap); val txMinor = PermissionsModel.parseMinor(tx)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArgusButton(tr("Save limits"), { onSave(b.copy(capMonthlyMinor = capMinor ?: b.capMonthlyMinor, perTxMaxMinor = txMinor ?: b.perTxMaxMinor)) },
                kind = ButtonKind.PRIMARY, size = ButtonSize.S, icon = "wallet",
                enabled = capMinor != null && txMinor != null && (capMinor != b.capMonthlyMinor || txMinor != b.perTxMaxMinor))
        }
        Text(tr("Every payment still shows a card for you to approve; the limits say when one is over budget."), style = ArgusType.sub, color = c.mute)
    }
}

/** [PermissionsSection] wired: the policies table (PolicyStore) and the bots (TeamStore) on team.db, off the main thread. */
@Composable
fun PermissionsLive() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var allows by remember { mutableStateOf<List<PermissionsModel.BotAllows>>(emptyList()) }
    var bots by remember { mutableStateOf<List<PermissionsModel.BotPerms>>(emptyList()) }
    LaunchedEffect(tick) {
        runCatching {
            withContext(Dispatchers.IO) {
                val g = com.blaze.assistant.team.TeamGraph.get(ctx)
                val roster = g.store.all().filter { it.enabled }
                val names = roster.associate { it.id to it.name }
                val ledger = runCatching { g.sql.query("SELECT bot_id, amount_minor, currency, at FROM spend_ledger") { r ->
                    SpendGuard.Entry(r.text(0), r.long(1), r.text(2), r.long(3)) } }.getOrDefault(emptyList())
                val now = System.currentTimeMillis(); val zone = java.time.ZoneId.systemDefault()
                PermissionsModel.standing(PolicyStore(g.sql).all(), { names[it] ?: it }) to roster.map { b ->
                    PermissionsModel.of(b.id, b.name, b.permissions, SpendGuard.spentThisMonth(ledger, now, zone, b.permissions.currency, b.id)) }
            }
        }.onSuccess { (a, b) -> allows = a; bots = b }
    }
    PermissionsSection(allows, bots,
        onAskAgain = { tool, bot -> scope.launch { withContext(Dispatchers.IO) { runCatching { PolicyStore(com.blaze.assistant.team.TeamGraph.get(ctx).sql).forget(tool, bot) } }; tick++ } },
        onSave = { bp -> scope.launch {
            withContext(Dispatchers.IO) { runCatching {
                val st = com.blaze.assistant.team.TeamGraph.get(ctx).store
                st.get(bp.botId)?.let { old -> st.update(old.copy(permissions = PermissionsModel.apply(old.permissions, bp))) }
            } }
            tick++
        } })
}
