package com.blaze.assistant.team

/**
 * P0-TM-01: the team's pure data model, 05-agent-ecosystem.md §9.1 verbatim (plus [DEFAULT_RULES], the 05 §7.1
 * defaults). Every bot is data (identity + soul + skills + tools + engine + apps + computer + permissions); one runtime
 * runs them all. No Android here: these are stored in team.db by [TeamDb] (05 §9.2 DDL in [TeamSchema]) and turned
 * to/from JSON by [TeamJson], so every rule over them runs in JVM tests. Nothing is wired to the UI in P0.
 *
 * `u` is the hybrid-logical-clock stamp used for last-writer-wins sync (P8). Secrets never live here: a Hermes key is
 * a vault id ([EngineSpec.hermesSecretRef]), and approvals carry redacted args only.
 */

enum class BotKind { MANAGER, DOMAIN, CUSTOM, WORKER, SUBAGENT, ROOM, REMOTE }
enum class EngineKind { LOCAL, CLOUD_RUN, HERMES }
enum class ComputerKind { NONE, PHONE, UBUNTU, CLOUD, HERMES_HOST }
enum class CloudProvider { SELF_HOSTED, CODESPACES, ORGO }
enum class Autonomy { ASSIST, ACT, AUTOPILOT }
enum class ActionClass { READ, WRITE_LOCAL, WRITE_REMOTE, SEND, SPEND, DELETE, SETTINGS, CREDENTIAL, COMPUTE_PAID, PHYSICAL }
enum class Rule { ALLOW, ASK, NEVER }
enum class SendScope { NONE, DRAFT_ONLY, KNOWN_CONTACTS, ANY }

/**
 * The default rule per action class (05 §7.1 "Default" column): reads, local writes and in-scope phone taps are
 * allowed; everything that reaches outside the phone, costs money, deletes, changes settings or touches credentials
 * asks. The policy engine (P2) layers taint, Plan mode and standing policies on top; this is only the starting map.
 */
val DEFAULT_RULES: Map<ActionClass, Rule> = mapOf(
    ActionClass.READ to Rule.ALLOW,
    ActionClass.WRITE_LOCAL to Rule.ALLOW,
    ActionClass.WRITE_REMOTE to Rule.ASK,
    ActionClass.SEND to Rule.ASK,
    ActionClass.SPEND to Rule.ASK,
    ActionClass.DELETE to Rule.ASK,
    ActionClass.SETTINGS to Rule.ASK,
    ActionClass.CREDENTIAL to Rule.ASK,
    ActionClass.COMPUTE_PAID to Rule.ASK,
    ActionClass.PHYSICAL to Rule.ALLOW,
)

data class Avatar(val shape: String, val color: String, val face: Int? = null, val image: String? = null)
data class Voice(val id: String = "default", val speed: Float = 1f, val language: String = "auto")

data class ModelPolicy(
    val lane: String = "agent",            // gateway blaze_task: chat|quick|code|vision|long|agent
    val tier: String? = null,              // spark|flare|inferno (ModelChoice.forRole); null = Auto
    val pinned: String? = null,            // user's choice; gateway still fails over inside the lane
    val effort: String = "medium",         // low|medium|high|xhigh
    val zeroRetention: Boolean = false     // BrainOrder privacy rule
)

data class EngineSpec(val kind: EngineKind = EngineKind.LOCAL, val model: ModelPolicy = ModelPolicy(),
                      val hermesUrl: String? = null, val hermesSecretRef: String? = null /* vault id, never the token */)

data class ComputerSpec(val kind: ComputerKind = ComputerKind.NONE, val provider: CloudProvider? = null,
                        val askEachSession: Boolean = true, val idleStopMin: Int = 10)

data class Permissions(
    val autonomy: Autonomy = Autonomy.ACT,
    val actions: Map<ActionClass, Rule> = DEFAULT_RULES,
    val spendCapMonthlyMinor: Long = 0, val currency: String = "ZMW",
    val sendScope: SendScope = SendScope.DRAFT_ONLY,
    val dataScopes: Set<String> = setOf("user:core", "team"),
    val maxStepsPerTurn: Int = 40, val maxJobMinutes: Int = 30, val maxHops: Int = 3
)

data class Bot(
    val id: String, val kind: BotKind, val name: String, val label: String, val domain: String? = null,
    val avatar: Avatar, val voice: Voice = Voice(), val mode: String = "personal",        // personal|teammate (Bot HQ)
    val soulPath: String,                       // filesDir/bots/<id>/SOUL.md
    val soulVersion: Int = 1,
    val skills: List<String> = emptyList(),     // skill slugs / pack ids
    val toolGroups: Set<String>, val toolGrants: Map<String, Rule> = emptyMap(),
    val engine: EngineSpec = EngineSpec(), val computer: ComputerSpec = ComputerSpec(),
    val apps: List<BotAppGrant> = emptyList(), val permissions: Permissions = Permissions(),
    val reportsTo: String? = "manager", val parentId: String? = null, val members: List<String> = emptyList(),
    val hidden: Boolean = false, val pinned: Boolean = false, val enabled: Boolean = true,
    val routingHints: List<String> = emptyList(),
    val createdAt: Long, val u: Long                                   // u = HLC for LWW sync
)

data class BotAppGrant(val appId: String, val source: String /* argus-connect|mcp|device (no third-party OAuth broker in the app, D5) */,
                       val tools: Map<String, Rule> = emptyMap())

enum class JobRun { QUEUED, RUNNING, WAITING, DONE, FAILED, CANCELLED }
data class Job(
    val id: String, val title: String, val botId: String?, val due: String?, val priority: String = "normal",
    val status: String = "todo", val run: JobRun = JobRun.QUEUED, val deps: List<String> = emptyList(),
    val parentJob: String? = null, val source: String /* user|manager|routine|bot|mcp|suggestion */,
    val brief: String, val result: String? = null, val notes: String = "",
    val confirmed: Boolean, /* proactive jobs start false */ val retryAt: Long? = null,
    val createdAt: Long, val doneAt: Long? = null, val u: Long
)
data class JobStep(val jobId: String, val seq: Int, val label: String, val state: String, val at: Long)

data class Row(  // Bot HQ transcript row, used for every bot chat and room
    val botId: String, val generation: Int, val seq: Long, val updatedSeq: Long,
    val kind: String /* message|tool-call|card|event|notice */, val body: String /* JSON */, val createdAt: Long)

data class BotMessage(val threadId: String, val fromBot: String, val toBot: String, val text: String,
                      val depth: Int, val expectsReply: Boolean, val budgetLeft: Int)

data class Routine(
    val id: String, val botId: String, val name: String, val prompt: String,
    val kind: String /* CRON|EVENT|CHECKIN|WATCH */, val schedule: String /* cron or event spec */,
    val timezone: String, val runsIn: String = "phone" /* phone|cloud */, val enabled: Boolean,
    val silentIfNothing: Boolean = true, val silentStreak: Int = 0, val lastRunAt: Long? = null, val u: Long)

data class Suggestion(val id: String, val botId: String, val title: String, val why: String, val task: String,
                      val topic: String, val urgency: Int, val risk: Set<ActionClass>, val createdAt: Long,
                      val state: String /* new|shown|done|not_now|muted|expired */)

data class Approval(val id: String, val botId: String, val jobId: String?, val callId: String, val tool: String,
                    val actionClass: ActionClass, val title: String, val detail: String, val argsRedacted: String,
                    val tainted: Boolean, val status: String /* pending|allowed|always|denied|expired|skipped */,
                    val onDeviceOnly: Boolean, val createdAt: Long, val resolvedAt: Long?, val resolvedBy: String?)

data class MemScope(val memId: Long, val scope: String, val botId: String?, val kind: String?,
                    val stated: Boolean, val sensitivity: Int /* 0 normal, 1 private, 2 secret-refused */,
                    val shareTags: Set<String>, val source: String, val expires: Long?)
