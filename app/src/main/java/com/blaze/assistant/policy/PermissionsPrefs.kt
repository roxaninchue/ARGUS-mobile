package com.blaze.assistant.policy

import android.content.Context
import android.content.SharedPreferences
import com.blaze.assistant.agent.ConfirmGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

/**
 * P2-SF-04 Android glue: where the Permissions page's choices live on the phone, and the one place the app asks for a
 * decision with them applied. Checked by hand (no Robolectric run without "build").
 *
 *  - Standing rules: a [TextPolicyStore] in SharedPreferences `argus_permissions` / `policies` until P0-TM-01's team.db
 *    exists; then wrap TeamDb in a [SqlPolicyStore.Sql] adapter, call [TextPolicyStore.migrateTo] once and pass that
 *    store to [install] instead. [install] puts the store in [ConfirmGate.policyStore] (SF-01 left that to SF-04), so
 *    "Never" rules refuse at dispatch from then on. It does no disk work (the store loads on first use).
 *  - Per-bot settings ([PermissionSettings]): the same prefs file, key `bots` ([PermissionsCodec]).
 *  - [decide]: [PermissionsModel.decide] with the stored settings, the installed store and this month's spending from
 *    the audit ledger (AuditDao, when installed). For SF-02's cards / the TeamHooks seam.
 *  - [roster]: the bots the page lists. P1/P0 set it to the team roster once the phone's Team singleton exists; until
 *    then the page shows ARGUS ([Policy.LEGACY]) plus any bot that already has a rule or setting.
 */
object PermissionsPrefs {
    private const val FILE = "argus_permissions"
    private const val KEY_POLICIES = "policies"
    private const val KEY_BOTS = "bots"

    /** A bot as the page shows it. */
    data class BotRef(val subject: Policy.Subject, val name: String)

    @Volatile var store: PolicyStore? = null
        private set
    @Volatile private var prefs: SharedPreferences? = null

    private val _settings = MutableStateFlow(PermissionSettings())
    val settings: StateFlow<PermissionSettings> = _settings
    @Volatile private var loaded = false

    /** The team roster (Manager first); null = not wired yet → ARGUS only. */
    @Volatile var roster: (() -> List<BotRef>)? = null

    /** Once at app start (BlazeApp). Safe to call again; [custom] replaces the prefs store (team.db later). */
    fun install(context: Context, custom: PolicyStore? = null): PolicyStore {
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val s = custom ?: store ?: TextPolicyStore(load = { p.getString(KEY_POLICIES, null) },
            save = { p.edit().putString(KEY_POLICIES, it).apply() })
        store = s
        ConfirmGate.policyStore = s
        return s
    }

    /** The stored per-bot settings (read once, then kept in [settings]). */
    fun current(): PermissionSettings {
        if (!loaded) synchronized(this) {
            if (!loaded) { _settings.value = PermissionsCodec.decode(prefs?.getString(KEY_BOTS, null)); loaded = true }
        }
        return _settings.value
    }

    /** Saves one bot's settings (a [BotPolicy] with every field null removes the bot's row). */
    fun put(p: BotPolicy) {
        val next = synchronized(this) { current().with(p).also { _settings.value = it } }
        prefs?.edit()?.putString(KEY_BOTS, PermissionsCodec.encode(next))?.apply()
    }

    /** The bots the page lists: the roster, or ARGUS plus every bot that has a rule or a setting. */
    fun bots(): List<BotRef> {
        roster?.let { r -> runCatching { r() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it } }
        val ids = LinkedHashSet<String>()
        current().bots.keys.forEach { ids += it }
        store?.all()?.forEach { if (it.botId != PolicyStore.ANY_BOT) ids += it.botId }
        ids -= Policy.LEGACY.id
        return listOf(BotRef(Policy.LEGACY, "ARGUS")) + ids.sorted().map { BotRef(Policy.Subject(it), it) }
    }

    fun zone(): String = ZoneId.systemDefault().id

    /** This month's spending for a bot from the audit ledger (0 until AuditDao is installed). */
    fun monthSpent(botId: String, currency: String): Long =
        runCatching { AuditDao.shared?.monthSpent(botId, currency, System.currentTimeMillis(), zone()) }.getOrNull() ?: 0L

    /** [Policy.decide] with the page's settings, the installed store and SpendGuard (see [PermissionsModel.decide]). */
    fun decide(bot: Policy.Subject, call: Policy.Call, ctx: Policy.Ctx = Policy.Ctx(), spend: SpendFacts? = null, send: SendFacts? = null): Policy.Decision =
        PermissionsModel.decide(bot, call, if (ctx.store == null) ctx.copy(store = store) else ctx, current(), spend, send,
            { id, cur -> monthSpent(id, cur) }, zone())
}
