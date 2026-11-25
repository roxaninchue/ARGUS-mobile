package com.blaze.assistant.browser.phone.instances

import android.content.Context
import com.blaze.assistant.agent.BgAgents
import com.blaze.assistant.device.CrashLog
import com.blaze.assistant.browser.phone.PhoneBrowser
import com.blaze.assistant.browser.phone.PhoneBrowserActivity
import com.blaze.assistant.browser.phone.PhoneBrowserTools
import com.blaze.assistant.browser.phone.PhonePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * The Android side of Argus Phone's instances: binds [ArgusPhoneCore] to the one live WebView ([PhoneBrowser], wrapped,
 * not rewritten) and to [ArgusPhoneService]. Checkpoints live under `filesDir/phone/instances/<id>/`. The agent's
 * phone_* tools go through [browserTool] (switch / lock check before, step and lease renewal after, any failure kept in
 * its instance); phone_power and phone_instances through [instancesTool]. One coroutine mirrors PhoneBrowser.live into
 * the active instance; one wakes every 30 s to end lapsed locks and keep the budget (cheap: no I/O unless something moved).
 */
object ArgusPhone {
    private lateinit var app: Context
    @Volatile private var core0: ArgusPhoneCore? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The phone_* tools that act (the rest only read). */
    private val ACTS = setOf("phone_open", "phone_act", "phone_handoff")

    private object Browser : ArgusPhoneCore.BrowserPort {
        override fun hasPage() = PhoneBrowser.hasPage()
        override fun mode() = PhoneBrowser.live.value.mode
        override suspend fun capture(): BrowserState? {
            if (!PhoneBrowser.hasPage()) return null
            val l = PhoneBrowser.live.value
            return BrowserState(l.url, l.title, PhoneBrowser.site(), PhoneBrowser.scrollY())
        }
        override suspend fun load(s: BrowserState): String { PhoneBrowser.restorePage(app, s.url, s.scrollY); return "ok" }
        override suspend fun release() = PhoneBrowser.releaseNow()
    }

    private object Service : ArgusPhoneCore.ServicePort {
        override fun start(): String? = ArgusPhoneService.start(app)
        override fun stop() = ArgusPhoneService.stop(app)
        override fun changed() = ArgusPhoneService.refresh()
        override fun show(id: String) = PhoneBrowserActivity.openInstance(app, id)
    }

    fun core(ctx: Context): ArgusPhoneCore {
        core0?.let { return it }
        synchronized(this) {
            core0?.let { return it }
            app = ctx.applicationContext
            PhoneBrowser.init(app)
            val c = ArgusPhoneCore(InstanceStore(File(app.filesDir, "phone/instances")), Browser, Service, { System.currentTimeMillis() },
                lowRam = try { PhoneBrowser.config().lowRam } catch (_: Throwable) { true },
                workspace = Workspace(File(app.filesDir, "home"), File(app.filesDir, "phone/instances")))
            core0 = c
            scope.launch {
                try { c.load() } catch (e: Throwable) { CrashLog.quiet("ArgusPhone.load", e) }
                PhoneBrowser.live.map { l -> listOf(l.mode, l.url, l.title, l.reason) }.distinctUntilChanged().collect { k ->
                    try { c.onLive(k[0] as PhonePolicy.Mode, k[1] as String, k[2] as String, PhonePolicy.siteOf(k[1] as String), k[3] as String?) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { CrashLog.quiet("ArgusPhone.onLive", e) }
                }
            }
            scope.launch {
                while (true) {
                    delay(30_000)
                    if (c.registry.size() == 0) continue
                    try { c.sweep() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { CrashLog.quiet("ArgusPhone.sweep", e) }
                }
            }
            return c
        }
    }

    /** Who is calling: a background agent by its id, else this chat's Argus. From the runtime, never the model. */
    fun owner(chatId: String, agentId: String?): Owner =
        if (agentId != null) Owner(Owner.Kind.AGENT, agentId, try { BgAgents.get(agentId)?.title.orEmpty() } catch (_: Throwable) { "" })
        else Owner(Owner.Kind.ARGUS, chatId)

    /**
     * A phone_* browser tool in the active (or `instance`-named) instance: switches and checks its lock first, records
     * the step and renews the owner's lease after, and keeps any failure inside that instance.
     */
    suspend fun browserTool(ctx: Context, name: String, args: JSONObject, by: Owner, run: suspend () -> String): String {
        val c = core(ctx)
        c.beforeBrowserTool(by, args.optString("instance").ifBlank { null }, name in ACTS)?.let { return it }
        val out = c.registry.isolate(c.registry.active, name, run)
        val typed = name == "phone_act" && args.optString("action") == "type" && !args.optBoolean("submit", false)
        c.afterBrowserTool(by, PhoneBrowserTools.describe(name, args), typed)
        return if (name == "phone_status") out + "\n" + c.status() else out
    }

    suspend fun instancesTool(ctx: Context, name: String, args: JSONObject, by: Owner, confirm: suspend (String, String, String) -> Boolean): String =
        PhoneInstanceTools.run(core(ctx), by, name, args, confirm)

    /** The Argus Phone screen is (not) being looked at: the watched instance is never demoted. */
    fun watching(ctx: Context, on: Boolean) { core(ctx).watching(on) }

    /**
     * The page to open when the user opens Argus Phone with nothing on screen: the page of [id] (the notification's
     * instance) or of the instance watched last, so it comes back where it was; Google for a fresh one.
     */
    fun resumeUrl(ctx: Context, id: String?): String {
        val c = core(ctx)
        c.load()
        val i = c.registry.get(id) ?: c.registry.get(c.registry.watched) ?: c.registry.all().maxByOrNull { it.lastActiveAt }
        if (i != null) c.registry.watched = i.id
        val url = i?.let { if (it.body) it.browser?.url else c.store.load(it.id)?.browser?.url }
        return url?.takeIf { it.startsWith("http") } ?: "https://www.google.com/"
    }

    /** The notification opened Argus Phone on [id] while another instance is on screen: switch, as the user. */
    fun showFromNotification(ctx: Context, id: String?) {
        if (id == null) return
        val c = core(ctx)
        if (c.registry.active == id) return
        scope.launch { try { c.use(id, Owner.USER) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { } }
    }

    /** The notification's Shut down / Close all instances. */
    fun shutDown(ctx: Context) { val c = core(ctx); scope.launch { try { c.powerOff() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { ArgusPhoneService.stop(ctx) } } }
    fun closeAll(ctx: Context) { val c = core(ctx); scope.launch { try { c.closeAll() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { } } }
}
