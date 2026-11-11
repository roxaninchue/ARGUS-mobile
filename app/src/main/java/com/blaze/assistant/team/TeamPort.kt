package com.blaze.assistant.team

/**
 * P1-TM-01/02/05: what the team stores need from storage. Pure interface so every rule (TeamStore, Rows, BotThreads,
 * JobQueue) runs on the JVM against [MemoryTeamPort]; on the phone it is backed by team.db (P0-TM-01 TeamDb, 05 §9.2)
 * through `TeamDbPort`. Nothing here touches blaze.db or the shared `memories` schema.
 */
interface TeamPort {
    // bots (05 §9.2 bots)
    fun bots(): List<Bot>
    fun putBot(b: Bot)
    fun deleteBot(id: String)

    // per-bot thread state: the bots table's generation / unread / last_preview / last_activity_at columns
    fun thread(botId: String): ThreadState?
    fun putThread(t: ThreadState)

    // rows (05 §9.2 rows, primary key bot_id + generation + seq)
    fun rows(botId: String, generation: Int, sinceUpdatedSeq: Long = 0): List<Row>
    fun row(botId: String, generation: Int, seq: Long): Row?
    fun putRow(r: Row)
    /** The highest seq or updated_seq written for this bot in any generation (0 when none). */
    fun maxSeq(botId: String): Long

    // jobs + job_steps
    fun jobs(): List<Job>
    fun job(id: String): Job?
    fun putJob(j: Job)
    fun deleteJob(id: String)
    fun steps(jobId: String): List<JobStep>
    fun putStep(s: JobStep)

    // small device-only flags (team seeded once, team notes…): Settings prefs on the phone
    fun flag(key: String): String?
    fun setFlag(key: String, value: String?)
}

/** The bots-table thread columns (05 §9.2) that [Bot] itself doesn't carry. */
data class ThreadState(val botId: String, val generation: Int = 0, val unread: Int = 0, val lastPreview: String? = null, val lastActivityAt: Long? = null)

/** In-memory [TeamPort]: JVM tests, previews and the shots fixtures. Insertion-ordered like a table scan. */
class MemoryTeamPort : TeamPort {
    private val bots = LinkedHashMap<String, Bot>()
    private val threads = HashMap<String, ThreadState>()
    private val rows = LinkedHashMap<Triple<String, Int, Long>, Row>()
    private val jobs = LinkedHashMap<String, Job>()
    private val steps = LinkedHashMap<Pair<String, Int>, JobStep>()
    private val flags = HashMap<String, String>()

    @Synchronized override fun bots() = bots.values.toList()
    @Synchronized override fun putBot(b: Bot) { bots[b.id] = b }
    @Synchronized override fun deleteBot(id: String) { bots.remove(id); threads.remove(id) }
    @Synchronized override fun thread(botId: String) = threads[botId]
    @Synchronized override fun putThread(t: ThreadState) { threads[t.botId] = t }
    @Synchronized override fun rows(botId: String, generation: Int, sinceUpdatedSeq: Long) =
        rows.values.filter { it.botId == botId && it.generation == generation && it.updatedSeq > sinceUpdatedSeq }.sortedBy { it.seq }
    @Synchronized override fun row(botId: String, generation: Int, seq: Long) = rows[Triple(botId, generation, seq)]
    @Synchronized override fun putRow(r: Row) { rows[Triple(r.botId, r.generation, r.seq)] = r }
    @Synchronized override fun maxSeq(botId: String) = rows.values.filter { it.botId == botId }.maxOfOrNull { maxOf(it.seq, it.updatedSeq) } ?: 0L
    @Synchronized override fun jobs() = jobs.values.toList()
    @Synchronized override fun job(id: String) = jobs[id]
    @Synchronized override fun putJob(j: Job) { jobs[j.id] = j }
    @Synchronized override fun deleteJob(id: String) { jobs.remove(id); steps.keys.removeAll { it.first == id } }
    @Synchronized override fun steps(jobId: String) = steps.values.filter { it.jobId == jobId }.sortedBy { it.seq }
    @Synchronized override fun putStep(s: JobStep) { steps[s.jobId to s.seq] = s }
    @Synchronized override fun flag(key: String) = flags[key]
    @Synchronized override fun setFlag(key: String, value: String?) { if (value == null) flags.remove(key) else flags[key] = value }
}
