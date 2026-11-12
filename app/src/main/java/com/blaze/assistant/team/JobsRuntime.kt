package com.blaze.assistant.team

import android.app.ActivityManager
import android.content.Context
import com.blaze.assistant.agent.BgAgents
import com.blaze.assistant.agent.ChatNav
import com.blaze.assistant.agent.TaskKeeper
import com.blaze.assistant.agent.TeamHooks
import com.blaze.assistant.agent.ToolRouter
import com.blaze.assistant.agent.engines.ToolSubset
import com.blaze.assistant.agent.engines.LocalEngine
import com.blaze.assistant.agent.engines.RowMapper
import com.blaze.assistant.orchestra.AgentBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * P1-TM-05 on the phone: [JobQueue] on team.db ([TeamGraph]), its runs on [BgAgents] (resume after a restart; the
 * process is held by [TaskKeeper]'s TaskWorker while one runs, so a job finishes with the app closed), the job tools
 * on [TeamHooks.tools], and the queue ticked when something changes and every [TICK_MS] while jobs wait.
 *
 *  - A bot's job runs in its own hidden chat `job-<jobId>-<n>`, bound to the bot ([TeamHooks.bind], its identity and
 *    tool subset), so its steps and reply land in the bot's thread as rows (the brief is posted once, as a task message
 *    from whoever asked, and the loop's own copy of it is skipped).
 *  - The Manager's jobs run as plain background agents of the main chat, so their result comes back to it as any
 *    background agent's does.
 *  - Reports: started → the task message; done / waiting / failed → a note in the bot's thread, and a line in the
 *    requester's thread when another bot asked.
 */
object JobsRuntime {
    const val TICK_MS = 30_000L
    const val CHAT_PREFIX = JobTools.CHAT_PREFIX
    const val PARENT_PREFIX = "job:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var q: JobQueue? = null
    @Volatile private var st: JobStore? = null
    private val bound = ConcurrentHashMap<String, TeamHooks.Binding>()   // chat → binding

    fun jobOfChat(chatId: String): String? = JobTools.jobOfChat(chatId)

    fun queue(): JobQueue? = q
    fun store(): JobStore? = st

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            val g = TeamGraph.get(app)
            val store = JobStore(g.sql)
            val rowStore = RowStore(g.sql)
            val am = app.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val cap = AgentBudget.cap(mem.totalMem, am?.isLowRamDevice == true)
            lateinit var queue: JobQueue
            val runner = object : JobQueue.Runner {
                override fun start(job: Job, bot: Bot, brief: String): String? {
                    val manager = BotRules.isManager(bot)
                    val chat = "$CHAT_PREFIX${job.id}-${System.currentTimeMillis()}"
                    if (!manager) {
                        val gen = rowStore.generation(bot.id) ?: return null
                        val mapper = RowMapper(bot.id, gen, rowStore.nextSeq(bot.id, gen), { System.currentTimeMillis() })
                        val b = TeamHooks.Binding(bot.id, LocalEngine.identity(bot, LocalEngine.readSoul(bot)), ToolSubset.of(bot, ToolRouter::groupOf)) { e ->
                            // the brief is posted once as the task message, so the loop's own copy of it is skipped
                            if (!(e is TeamHooks.Event.Message && e.role == "USER")) for (row in mapper.map(e)) runCatching { g.threads.put(row) }
                        }
                        TeamHooks.bind(chat, b); bound[chat] = b
                    }
                    val parent = if (manager || JobStore.requester(job.source) == null) ChatNav.MAIN else PARENT_PREFIX + job.id
                    val bg = BgAgents.start(brief, job.title, parent, chat = chat)
                    TaskKeeper.hold(app)
                    return bg.id
                }

                override fun alive(runId: String) = BgAgents.alive(runId)

                override fun report(job: Job, bot: Bot?, event: JobQueue.Report) {
                    if (bot == null) return
                    val manager = BotRules.isManager(bot)
                    val from = JobStore.requester(job.source)?.let { g.store.get(it) }
                    runCatching {
                        when (event.kind) {
                            JobQueue.Kind.STARTED -> if (!manager) g.threads.post(bot.id, RowKind.MESSAGE, RowBodies.message(RowBodies.USER,
                                "Job #${job.id}: ${job.title}" + (job.brief.takeIf { JobStore.key(it) != JobStore.key(job.title) }?.let { "\n\n${it.take(1500)}" } ?: ""),
                                via = from?.let { RowBodies.Via("bot", it.id, it.name) }))
                            else -> {
                                val line = when (event.kind) {
                                    JobQueue.Kind.DONE -> "\u2713 Job \u00ab${job.title}\u00bb done"
                                    JobQueue.Kind.WAITING -> "Job \u00ab${job.title}\u00bb waits for you: ${event.text.take(300)}"
                                    JobQueue.Kind.FAILED -> "Job \u00ab${job.title}\u00bb failed: ${event.text}. Trying again soon"
                                    else -> "Job \u00ab${job.title}\u00bb failed: ${event.text}. Retry it from the job"
                                }
                                if (!manager) g.threads.post(bot.id, RowKind.EVENT, RowBodies.event("job", line, job.id))
                                if (from != null && from.id != bot.id && !BotRules.isManager(from) && event.kind != JobQueue.Kind.FAILED)
                                    g.threads.post(from.id, RowKind.EVENT, RowBodies.event("job", "${bot.name}: " + line +
                                        (if (event.kind == JobQueue.Kind.DONE) job.result?.let { ". ${it.take(400)}" }.orEmpty() else ""), job.id))
                            }
                        }
                    }
                }
            }
            queue = JobQueue(store, g.store, runner, cap = { cap }, busy = { g.threads.isRunning(it) })
            val tools = JobTools(store, queue, g.store, current = { jobOfChat(it) }, changed = { kick() })
            st = store; q = queue
            TeamHooks.tools = object : TeamHooks.Tools {
                override fun defs() = tools.defs()
                override fun names() = JobTools.NAMES
                override fun run(chatId: String, botId: String?, name: String, args: String) = tools.run(chatId, botId, name, args)
            }
            BgAgents.onEnd = { bg -> if (bg.chat.startsWith(CHAT_PREFIX)) scope.launch { ended(bg) } }
            delay(6_000)   // BgAgents puts runs from before a restart back first (it waits 4 s), so they count as alive
            runCatching { queue.recover() }
            while (true) {
                runCatching { queue.tick() }
                delay(TICK_MS)
            }
        }
    }

    private fun ended(bg: BgAgents.Bg) {
        val queue = q ?: return
        bound.remove(bg.chat)?.let { TeamHooks.unbind(bg.chat, it) }
        val jobId = jobOfChat(bg.chat) ?: st?.jobOfRun(bg.id) ?: return
        runCatching { queue.finished(jobId, ok = bg.status == BgAgents.DONE, text = bg.result, cancelled = bg.status == BgAgents.CANCELLED) }
        kick()
    }

    /** Tick now (a job was added, closed or retried, or a run ended). */
    fun kick() { val queue = q ?: return; scope.launch { runCatching { queue.tick() } } }
}
