package com.blaze.assistant.ui.argus.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.policy.ActionClasses
import com.blaze.assistant.policy.BotPolicy
import com.blaze.assistant.policy.PermissionsModel
import com.blaze.assistant.policy.PermissionsPrefs
import com.blaze.assistant.policy.Policy
import com.blaze.assistant.policy.PolicyStore
import com.blaze.assistant.policy.SpendGuard
import com.blaze.assistant.policy.SpendSettings
import com.blaze.assistant.policy.StandingRow
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Rule
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
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * P2-SF-04 Settings › Privacy › Approvals & permissions (04-design-ia.md §4.11 / §6 "Permissions policy per tool",
 * 05 §7.1–§7.4). Per bot: autonomy (Assist / Act / Autopilot), SpendGuard (monthly allowance, default 0 = every spend
 * asks; per-purchase max; currency; allowed shops), send scope, the standing "Always allow" / "Never" rules with
 * "Ask again", and the list of what still always asks on Autopilot. Rules shared by every bot (`*`) are listed too.
 *
 * Self-contained like ActivityPage: P1-SH-06's settings re-tree isn't on this branch, so it draws with the existing
 * Settings kit (PageHead, GroupHead, Rows/KitRow, Segmented, Tag, BlazeButton, BlazeTextField) inside the Settings
 * page's own vertical scroll (plain Columns, no LazyColumn). Model and rules: policy/PermissionsModel.kt (pure, tested);
 * storage: policy/PermissionsPrefs.kt. Registered in ui/SettingsSheet.kt as page "approvals".
 */
@Composable
fun PermissionsPage(
    bots: List<PermissionsPrefs.BotRef> = remember { PermissionsPrefs.bots() },
    store: PolicyStore? = PermissionsPrefs.store,
) {
    val c = LocalBlaze.current
    val all by PermissionsPrefs.settings.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PermissionsPrefs.current() } }
    var who by remember { mutableStateOf(bots.firstOrNull()?.subject?.id ?: Policy.LEGACY.id) }
    val ref = bots.firstOrNull { it.subject.id == who } ?: bots.firstOrNull() ?: PermissionsPrefs.BotRef(Policy.LEGACY, "ARGUS")
    val mine = all.of(ref.subject.id)
    val bot = PermissionsModel.apply(ref.subject, all)
    val spend = PermissionsModel.spendSettings(bot, all, PermissionsPrefs.zone())

    var rev by remember { mutableIntStateOf(0) }
    var rules by remember { mutableStateOf<List<StandingRow>>(emptyList()) }
    LaunchedEffect(store, rev) {
        if (store == null) return@LaunchedEffect
        rules = withContext(Dispatchers.IO) { runCatching { PermissionsModel.standing(store, bots.map { it.subject.id }) }.getOrDefault(emptyList()) }
    }

    PageHead("Approvals & permissions", "What your team may do on its own",
        "Choose how much each bot does without asking, what it may spend and send, and which tools you've always allowed.")

    // ---- whose settings
    if (bots.size > 1) {
        GroupHead("Bot", "")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            bots.forEach { b -> Chip(b.name, who == b.subject.id) { who = b.subject.id } }
        }
        Spacer(Modifier.height(14.dp))
    }

    // ---- autonomy
    GroupHead("Autonomy", PermissionsModel.AUTONOMY.first { it.first == bot.permissions.autonomy }.second)
    Segmented(PermissionsModel.AUTONOMY.map { (a, _) -> a.name to PermissionsModel.autonomyName(a) }, bot.permissions.autonomy.name,
        Modifier.fillMaxWidth()) { id ->
        PermissionsPrefs.put(mine.copy(autonomy = Autonomy.valueOf(id)))
    }
    Spacer(Modifier.height(18.dp))

    // ---- spending (SpendGuard)
    SpendSection(ref.name, mine, spend)
    Spacer(Modifier.height(18.dp))

    // ---- sending
    GroupHead("Sending", "Who ${ref.name} may message without showing you the draft first. Public posts always ask.")
    Rows {
        PermissionsModel.SEND_SCOPES.forEachIndexed { i, (scope, line) ->
            val on = bot.permissions.sendScope == scope
            KitRow(title = PermissionsModel.sendScopeName(scope), sub = line, first = i == 0, pill = if (on) "On" else "", pillOn = on,
                onClick = { PermissionsPrefs.put(mine.copy(sendScope = scope)) })
        }
    }
    Spacer(Modifier.height(18.dp))

    // ---- standing rules
    val forBot = rules.filter { it.botId == ref.subject.id }
    val shared = rules.filter { it.anyBot }
    val clearAll: (@Composable RowScope.() -> Unit)? = if (forBot.size > 1 && store != null) {
        { BlazeButton("Ask again for all", kind = BtnKind.GHOST, height = 32.dp) { PermissionsModel.askAgainAll(store, ref.subject.id); rev++ } }
    } else null
    GroupHead("Always allowed & never", if (forBot.isEmpty() && shared.isEmpty())
        "Nothing yet. Tap \"Always allow\" on an approval card and it shows up here." else
        "Rules you set from approval cards. \"Ask again\" removes one, and the next time it asks you first.", right = clearAll)
    if (store == null) {
        Note("Rules aren't available yet on this phone.")
    } else {
        if (forBot.isNotEmpty()) RuleRows(forBot) { PermissionsModel.askAgain(store, it); rev++ }
        if (shared.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            GroupHead("Every bot", "")
            RuleRows(shared) { PermissionsModel.askAgain(store, it); rev++ }
        }
    }
    Spacer(Modifier.height(18.dp))

    // ---- what still always asks
    GroupHead(if (bot.permissions.autonomy == Autonomy.AUTOPILOT) "Still always asks" else "Always asks, even on Autopilot", "")
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Autopilot exceptions" }) {
        PermissionsModel.autopilotExceptions(bot, spend).forEach { line ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("\u2022", modifier = Modifier.width(14.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
                Text(line, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.ink))
            }
        }
    }

    if (!mine.isEmpty) {
        Spacer(Modifier.height(18.dp))
        BlazeButton("Reset ${ref.name} to defaults", Modifier.fillMaxWidth(), kind = BtnKind.GHOST) { PermissionsPrefs.put(BotPolicy(ref.subject.id)) }
    }
}

@Composable
private fun SpendSection(name: String, mine: BotPolicy, spend: SpendSettings) {
    val c = LocalBlaze.current
    // Fields reset when another bot is picked or its saved settings change.
    var cap by remember(mine) { mutableStateOf(if (spend.monthlyCapMinor > 0) PermissionsModel.moneyField(spend.monthlyCapMinor) else "") }
    var max by remember(mine) { mutableStateOf(spend.perTxMaxMinor?.let { PermissionsModel.moneyField(it) }.orEmpty()) }
    var cur by remember(mine) { mutableStateOf(spend.currency) }
    var shops by remember(mine) { mutableStateOf(spend.allowedMerchants.sorted().joinToString(", ")) }
    var note by remember(mine.botId) { mutableStateOf("") }
    val spent = remember(mine.botId, spend.currency) { PermissionsPrefs.monthSpent(mine.botId, spend.currency) }

    GroupHead("Spending", if (spend.monthlyCapMinor <= 0) "No monthly allowance: every purchase asks you first." else
        "$name may spend up to ${SpendGuard.money(spend.monthlyCapMinor, spend.currency)} a month without asking " +
            "(${SpendGuard.money(spent, spend.currency)} used this month). Purchases you didn't start always ask.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Small("Monthly allowance")
            BlazeTextField(cap, { cap = it }, "0.00", Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        Column(Modifier.weight(1f)) {
            Small("Most at once")
            BlazeTextField(max, { max = it }, "No limit", Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(96.dp)) {
            Small("Currency")
            BlazeTextField(cur, { cur = it.uppercase().take(3) }, "ZMW", Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f)) {
            Small("Only these shops (optional)")
            BlazeTextField(shops, { shops = it }, "Any shop", Modifier.fillMaxWidth())
        }
    }
    Spacer(Modifier.height(10.dp))
    BlazeButton("Save spending", Modifier.fillMaxWidth(), kind = BtnKind.SECONDARY) {
        val capMinor = if (cap.isBlank()) 0L else PermissionsModel.parseMoney(cap)
        val maxMinor = if (max.isBlank()) null else PermissionsModel.parseMoney(max)
        note = when {
            capMinor == null -> "The allowance should be an amount like 250 or 250.00."
            max.isNotBlank() && maxMinor == null -> "\"Most at once\" should be an amount, or empty for no limit."
            !Regex("^[A-Z]{3}$").matches(cur) -> "Currency is a three-letter code, like ZMW."
            else -> {
                PermissionsPrefs.put(mine.copy(monthlyCapMinor = capMinor, perTxMaxMinor = maxMinor, currency = cur,
                    allowedMerchants = shops.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()))
                if (capMinor == 0L) "Saved. Every purchase asks you first." else "Saved."
            }
        }
    }
    if (note.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(note, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = c.sub))
    }
}

@Composable
private fun RuleRows(rows: List<StandingRow>, onAskAgain: (StandingRow) -> Unit) {
    Rows {
        rows.forEachIndexed { i, r ->
            KitRow(title = r.tool.replace('_', ' '), sub = ruleSub(r), first = i == 0, pill = PermissionsModel.ruleLabel(r.rule),
                pillOn = r.rule == Rule.ALLOW && r.applies, dim = !r.applies,
                trailing = { BlazeButton("Ask again", kind = BtnKind.GHOST, height = 32.dp) { onAskAgain(r) } })
        }
    }
}

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun ruleSub(r: StandingRow): String {
    val cls = r.cls.name.lowercase().replace('_', ' ')
    val note = if (!r.applies) "not used: $cls always asks" else cls
    val at = if (r.at > 0) dateFmt.format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())) else ""
    return listOf(note, if (ActionClasses.isMcp(r.tool)) "connected app" else "", at).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(vertical = 4.dp)) { Tag(text, on = on) }
}

@Composable
private fun Small(text: String) {
    val c = LocalBlaze.current
    Text(text, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
}

@Composable
private fun Note(text: String) {
    val c = LocalBlaze.current
    Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
}
