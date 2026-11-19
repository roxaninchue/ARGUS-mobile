package com.blaze.assistant.team

import android.content.Context
import com.blaze.assistant.agent.FastLane
import com.blaze.assistant.agent.LlmClient
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.terminal.Distros
import com.blaze.assistant.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * P1-TM-06 on the phone: [Router] over team.db ([TeamGraph]) and the jobs store, with its lanes wired to the native
 * Needle team runner ([FastLane.teamCalls]) and the gateway ([LlmClient], background lane), the jobs queue kicked
 * through [JobsRuntime], and HandoffCards posted in the bot's thread ([BotThreads.post]). The shell (ChatScreen) calls
 * [handle] for a message from the Team composer or the ARGUS chat and shows what came back.
 */
object TeamRouting {
    /** A-team "Apply commands without the preview step" (Settings `instant`); off = the plan sheet shows first. */
    const val INSTANT_KEY = "team_plan_instant"

    var instant: Boolean
        get() = runCatching { Settings.bool(INSTANT_KEY, false) }.getOrDefault(false)
        set(v) { runCatching { Settings.setBool(INSTANT_KEY, v) } }

    @Volatile private var r: Router? = null

    fun router(ctx: Context): Router = r ?: synchronized(this) {
        r ?: run {
            val g = TeamGraph.get(ctx.applicationContext)
            Router(g.store, JobsRuntime.store() ?: JobStore(g.sql), queue = { JobsRuntime.queue() },
                kick = { JobsRuntime.kick() },
                post = { bot, kind, body -> runCatching { g.threads.post(bot, kind, body) } },
                changed = { id -> runCatching { g.threads.rosterChanged(id) } })
        }.also { r = it }
    }

    fun lanes(ctx: Context): Router.Lanes = object : Router.Lanes {
        override suspend fun needle(clause: String): Router.Needle? = FastLane.teamCalls(ctx.applicationContext, clause)
        override fun cloud(): Boolean = runCatching { FastLane.online(ctx) && Settings.gatewayToken().isNotBlank() }.getOrDefault(false)
        override suspend fun gateway(system: String, text: String): String? = try {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", text))
            LlmClient.complete(msgs, null, null, null, LlmClient.Lane.BACKGROUND).optString("content")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    /** Routing v1 for one message; null when team.db can't be read (the message then goes to ARGUS as before). */
    suspend fun handle(ctx: Context, text: String, where: Router.Where): Router.Outcome? = withContext(Dispatchers.IO) {
        try { router(ctx).handle(text, where, lanes(ctx), instant) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    /** The plan sheet's Apply. */
    suspend fun apply(ctx: Context, plan: Router.Plan): Router.Applied = withContext(Dispatchers.IO) { router(ctx).apply(plan.actions) }

    /** `$ cmd`: Alpine when it's installed (P7 brings the Ubuntu workspace), else the phone's shell. */
    suspend fun shell(cmd: String): String {
        val alpine = runCatching { Distros.installed("alpine") }.getOrDefault(false)
        val out = runCatching { Terminal.run(cmd, timeoutSec = 60, linux = alpine) }.getOrElse { "Error: ${it.message}" }
        return ShellNote.of(cmd, out)
    }
}

