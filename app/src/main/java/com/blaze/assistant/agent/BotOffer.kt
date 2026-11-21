package com.blaze.assistant.agent

import android.content.Context
import com.blaze.assistant.orchestra.OrchestraRuns
import com.blaze.assistant.device.CrashLog
import com.blaze.assistant.team.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 0.54.1 (Finn, 2026-11-21: "create bots for work the user frequently does when it fits"): when the same kind of job
 * comes up a third time in two weeks (deep research, building an app, shopping…), Argus pops a card in the chat
 * suggesting a bot for it, drawn in that bot's own shape and colour, with its name and a short brief. Add creates it,
 * No thanks hides it (no new suggestion for that kind for 30 days), and leaving it alone changes nothing: the chat and
 * the work carry on as usual. The card is a chat row only; the model never sees it.
 *
 * Rules ([decide], pure): 3+ jobs of one area in 14 days; no bot on the team already covers it (by its label); fewer
 * than [MAX_BOTS] bots besides the Manager; that area never added, not declined in the last 30 days, not offered in the
 * last 7. Plain background agents with no specialist don't count (there is nothing to name them by).
 */
object BotOffer {
    const val CARD = "botoffer:"
    const val ASKING = "open"; const val TAKEN = "added"; const val TURNED_DOWN = "declined"
    const val THRESHOLD = 3
    const val MAX_BOTS = 4
    private const val DAY = 86_400_000L
    const val WINDOW = 14 * DAY
    const val DECLINE_QUIET = 30 * DAY
    const val OFFER_QUIET = 7 * DAY

    /** A bot Argus would suggest for one area of work. */
    data class Proposal(val area: String, val name: String, val label: String, val brief: String, val face: Int, val color: String,
                        val count: Int = 0, val what: String = "", val state: String = ASKING, val botId: String = "") {
        fun draft(): BotDraft = BotDraft(name = name, label = label, portfolio = brief, face = face, color = color,
            instructions = "You are $name, the user's ${label.lowercase()} bot. $brief")
        fun meta(): String = CARD + JSONObject().put("area", area).put("name", name).put("label", label).put("brief", brief).put("face", face)
            .put("color", color).put("count", count).put("what", what).put("state", state).put("bot", botId).toString()
    }

    private data class T(val label: String, val what: String, val brief: String, val face: Int, val color: String, val names: List<String>)

    /** Every area, with the plain words for it ("research") and the bot it gets. Colours are BotDraft.COLORS swatches. */
    private val AREAS = mapOf(
        "research" to T("Research", "deep research", "Digs into a topic across many sources, checks every fact, and hands you a cited report or PDF.", 0, "#8b5cf6", listOf("Nova", "Vega")),
        "code" to T("Builder", "app builds", "Builds and fixes apps and scripts in the terminal, runs the tests, and tells you what changed.", 2, "#06b6d4", listOf("Forge", "Bolt")),
        "writing" to T("Writer", "long writing", "Drafts documents, emails and posts in your voice, then tightens them until they read well.", 3, "#f59e0b", listOf("Quill", "Ink")),
        "media" to T("Curator", "media lists", "Finds songs, films, books and podcasts you'll like and lays them out with their artwork.", 4, "#ef4444", listOf("Echo", "Reel")),
        "shopping" to T("Shopping", "shopping", "Compares products and prices across stores and finds the best deal before you buy.", 1, "#22c55e", listOf("Penny", "Scout")),
        "booking" to T("Bookings", "bookings", "Finds and books tables, tickets and appointments, and keeps the confirmations together.", 5, "#3b82f6", listOf("Atlas", "Jet")),
        "planning" to T("Planner", "planning", "Turns trips, days and projects into clear steps with times and to-dos.", 1, "#6366f1", listOf("Tempo", "Compass")),
        "inbox" to T("Inbox", "inbox work", "Sorts your mail, drafts replies for you to check, and flags what needs you.", 3, "#3b82f6", listOf("Iris", "Post")),
        "design" to T("Design", "design work", "Makes images, layouts and pages in one consistent style.", 4, "#8b5cf6", listOf("Prism", "Hue")),
        "study" to T("Tutor", "studying", "Explains topics step by step, quizzes you, and builds flashcards.", 2, "#22c55e", listOf("Sage", "Owl")),
        "phone" to T("Phone", "phone tasks", "Handles jobs on your phone: settings, apps, messages and files.", 0, "#cbd5e1", listOf("Relay", "Dash")),
    )

    /** The area a background run belongs to, or null (a plain agent). Pure. */
    fun areaOf(specialist: String, task: String): String? = when (specialist.lowercase()) {
        "" -> null
        OrchestraRuns.SPECIALIST -> when (try { OrchestraRuns.decode(task).shape.id } catch (_: Throwable) { "generic" }) {
            "research" -> "research"; "apk" -> "code"; "document" -> "writing"; "media" -> "media"; else -> null
        }
        "researcher" -> "research"; "coder" -> "code"; "writer" -> "writing"; "shopper" -> "shopping"; "booker" -> "booking"
        "planner" -> "planning"; "inbox" -> "inbox"; "designer" -> "design"; "tutor" -> "study"; "operator" -> "phone"
        else -> null
    }

    data class Job(val at: Long, val area: String)
    data class Past(val state: String, val at: Long)
    data class Member(val name: String, val label: String)

    /** The bot to suggest now, or null. [jobs] includes the one just started. Pure. */
    fun decide(area: String, jobs: List<Job>, past: Map<String, Past>, team: List<Member>, now: Long): Proposal? {
        val t = AREAS[area] ?: return null
        val n = jobs.count { it.area == area && now - it.at in 0..WINDOW }
        if (n < THRESHOLD) return null
        past[area]?.let { p ->
            when (p.state) {
                TAKEN -> return null
                TURNED_DOWN -> if (now - p.at < DECLINE_QUIET) return null
                else -> if (now - p.at < OFFER_QUIET) return null
            }
        }
        if (team.size >= MAX_BOTS) return null
        if (team.any { m -> m.label.trim().lowercase().let { l -> l.isNotBlank() && (l.contains(t.label.lowercase()) || t.label.lowercase().contains(l)) } }) return null
        val name = t.names.firstOrNull { c -> team.none { it.name.equals(c, true) } } ?: return null
        return Proposal(area, name, t.label, t.brief, t.face, t.color, n, t.what)
    }

    /** A card's proposal from its meta, or null. Pure. */
    fun parse(meta: String): Proposal? {
        if (!meta.startsWith(CARD)) return null
        return try {
            val o = JSONObject(meta.removePrefix(CARD))
            Proposal(o.getString("area"), o.getString("name"), o.optString("label"), o.optString("brief"), o.optInt("face"), o.optString("color", "#8b5cf6"),
                o.optInt("count"), o.optString("what"), o.optString("state", ASKING), o.optString("bot"))
        } catch (_: Exception) { null }
    }

    /** "You've asked for deep research 3 times in two weeks." Pure. */
    fun why(p: Proposal): String = "You've asked for ${p.what.ifBlank { p.label.lowercase() }} ${p.count} times in two weeks."

    // ---------- on the phone ----------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "bot_offers.json")

    private fun read(ctx: Context): Pair<MutableList<Job>, MutableMap<String, Past>> = try {
        val f = file(ctx)
        if (!f.exists()) mutableListOf<Job>() to mutableMapOf() else {
            val o = JSONObject(f.readText())
            val j = o.optJSONArray("jobs") ?: JSONArray()
            val jobs = (0 until j.length()).map { j.getJSONObject(it) }.map { Job(it.optLong("at"), it.optString("area")) }.toMutableList()
            val p = o.optJSONObject("past") ?: JSONObject()
            jobs to p.keys().asSequence().associateWith { k -> p.getJSONObject(k).let { Past(it.optString("state"), it.optLong("at")) } }.toMutableMap()
        }
    } catch (_: Exception) { mutableListOf<Job>() to mutableMapOf() }

    private fun write(ctx: Context, jobs: List<Job>, past: Map<String, Past>) {
        try {
            val p = JSONObject(); past.forEach { (k, v) -> p.put(k, JSONObject().put("state", v.state).put("at", v.at)) }
            file(ctx).writeText(JSONObject().put("jobs", JSONArray(jobs.takeLast(200).map { JSONObject().put("at", it.at).put("area", it.area) })).put("past", p).toString())
        } catch (_: Exception) { }
    }

    /** BgAgents.start: a run began in chat [chatId]. Counts it, and pops the card there when one fits. Never throws. */
    fun noted(ctx: Context, specialist: String, task: String, chatId: String) {
        val area = areaOf(specialist, task) ?: return
        if (!Settings.bool("bot_offers", true)) return
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val p = synchronized(lock) {
                    val (jobs, past) = read(ctx)
                    jobs += Job(now, area)
                    val team = TeamGraph.get(ctx).store.roster()
                        .filterNot { BotRules.isManager(it) }.map { Member(it.name, it.label) }
                    val prop = decide(area, jobs, past, team, now)
                    if (prop != null) past[area] = Past(ASKING, now)
                    write(ctx, jobs, past)
                    prop
                } ?: return@launch
                withContext(Dispatchers.Main) { Agent.postCard(chatId, "Suggested ${p.name}, a ${p.label.lowercase()} bot. ${why(p)}", p.meta()) }
            } catch (e: Throwable) { CrashLog.quiet("BotOffer: noted", e) }
        }
    }

    /** The card's Add: creates the bot, then the card says it joined. Returns the refusal in plain words, or null. */
    suspend fun accept(ctx: Context, msgId: Long, p: Proposal): String? = withContext(Dispatchers.IO) {
        val store = TeamGraph.get(ctx).store
        when (val r = TeamEdits(store).create(p.draft(), "user")) {
            is TeamStore.Result.Ok -> {
                remember(ctx, p.area, TAKEN)
                // Unreleased after 0.54.1: My team showed no Nova until something else refreshed it; say so at once (also posts its hello).
                runCatching { TeamGraph.get(ctx).threads.rosterChanged(r.bot.id) }
                withContext(Dispatchers.Main) { Agent.setCardMeta(msgId, p.copy(state = TAKEN, botId = r.bot.id).meta()) }
                null
            }
            is TeamStore.Result.Refused -> r.why
        }
    }

    /** The card's No thanks: hidden, and no new suggestion for that area for 30 days. */
    fun decline(ctx: Context, msgId: Long, p: Proposal) {
        Agent.setCardMeta(msgId, p.copy(state = TURNED_DOWN).meta())
        scope.launch { remember(ctx, p.area, TURNED_DOWN) }
    }

    private fun remember(ctx: Context, area: String, state: String) = synchronized(lock) {
        val (jobs, past) = read(ctx); past[area] = Past(state, System.currentTimeMillis()); write(ctx, jobs, past)
    }
}
