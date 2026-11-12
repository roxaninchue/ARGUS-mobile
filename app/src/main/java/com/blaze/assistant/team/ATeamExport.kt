package com.blaze.assistant.team

import org.json.JSONArray
import org.json.JSONObject

/**
 * P0-MG-01: the two files ARGUS takes from A-team, and the rules they must pass before anything is imported.
 * See docs/overhaul/ateam-export.md. Pure (org.json only) so it runs as a JVM test; P1-MG-01 / P3-MG-01 import on top.
 *
 *  - [Kind.BACKUP]: A-team's "Copy backup" (Settings → Data), `{agents, tasks}` exactly as `S.agents` / `S.tasks`.
 *  - [Kind.V1]: `argus-team-export.v1`, A-team's "Export for ARGUS" (P3-MG-01 / P8-MG-01): bots + look, jobs, settings,
 *    the profile (stated / inferred), bot and team memory, routines, proactivity and, only when the user ticks it,
 *    Manager chats.
 */
object ATeamExport {
    enum class Kind { BACKUP, V1 }

    const val FORMAT = "argus-team-export"
    const val MANAGER_ID = "manager"
    /** A-team BOT_FACES order, the same as [com.blaze.assistant.ui.argus.OrbFace]. */
    val FACES = listOf("Orbit", "Tilt", "Steep", "Pure", "Twin", "Polar")
    /** A-team manager.js KINDS. */
    val PROFILE_KINDS = listOf("name", "style", "goal", "project", "preference", "person", "date", "place", "rule", "note")
    val ROUTINE_KINDS = setOf("remind", "task", "brief", "followup")
    val PRIORITIES = setOf("low", "normal", "high")
    val STATUSES = setOf("todo", "done")
    val RUNS = setOf("queued", "running", "waiting", "done", "failed")
    val DAYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
    /** Keys that never appear anywhere in either file (A-team keeps these in its vault or drops them on load). */
    val FORBIDDEN_KEYS = setOf("token", "api", "apikey", "api_key", "password", "secret", "vault", "gateway", "gw", "key")

    /** A-team manager.js SECRET + memory.js SECRET: what a profile line or memory may never hold. */
    private val SECRET = Regex(
        "\\b(gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{16,}|xox[abp]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|" +
            "eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+)\\b|" +
            "\\b(password|passwd|api[_ -]?key|secret|token|pin)\\s*[:=]\\s*\\S{4,}|\\b(?:\\d[ -]?){13,19}\\b",
        RegexOption.IGNORE_CASE,
    )
    private val HEX = Regex("^#[0-9a-fA-F]{6}$")
    private val YMD = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val HHMM = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    fun isSecret(s: String?): Boolean = s != null && SECRET.containsMatchIn(s)

    fun detect(o: JSONObject): Kind? = when {
        o.optString("format") == FORMAT && o.optInt("v") == 1 -> Kind.V1
        !o.has("format") && o.optJSONArray("agents") != null -> Kind.BACKUP
        else -> null
    }

    /** The face a bot shows: its own, else A-team's agentFace() (sum of the name's char codes mod 6). */
    fun faceOf(bot: JSONObject, kind: Kind): Int {
        val f = if (kind == Kind.V1) bot.optJSONObject("look")?.opt("face") else bot.opt("face")
        if (f is Number) return Math.floorMod(f.toInt(), FACES.size)
        return Math.floorMod(bot.optString("name").sumOf { it.code }, FACES.size)
    }

    /** docs §4.3: where a profile line lands in mem_scope (05 §4.2). */
    fun scopeFor(kind: String): String = when (kind) {
        "name", "style", "rule", "preference" -> "user:core"
        "person" -> "user:people"
        "place" -> "user:places"
        "date" -> "user:dates"
        "goal" -> "user:goals"
        "project" -> "user:projects"
        else -> "user:notes"
    }

    /** docs §4.5: the routines.schedule text ARGUS stores, or null when the A-team schedule is not valid. */
    fun schedule(s: JSONObject?): String? {
        if (s == null) return null
        val time = s.optString("time")
        return when (s.optString("type")) {
            "once" -> if (YMD.matches(s.optString("date")) && HHMM.matches(time)) "once ${s.optString("date")} $time" else null
            "daily" -> if (HHMM.matches(time)) "daily $time" else null
            "weekly" -> {
                val d = s.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optString(it).lowercase().take(3) } }.orEmpty()
                if (d.isNotEmpty() && d.all { it in DAYS } && HHMM.matches(time)) "weekly ${d.joinToString(",")} $time" else null
            }
            "cron" -> { val c = s.optString("cron").trim(); if (c.split(Regex("\\s+")).size == 5) "cron $c" else null }
            else -> null
        }
    }

    /** Every rule in docs §3 the file breaks; empty means it can be imported. */
    fun validate(o: JSONObject): List<String> {
        val p = mutableListOf<String>()
        val kind = detect(o) ?: return listOf("not an A-team backup or argus-team-export v1")
        forbidden(o, "$", p)
        val bots = o.optJSONArray(if (kind == Kind.V1) "bots" else "agents") ?: JSONArray()
        val jobs = o.optJSONArray(if (kind == Kind.V1) "jobs" else "tasks") ?: JSONArray()

        val botIds = mutableSetOf<String>()
        var managers = 0
        objs(bots).forEachIndexed { i, b ->
            val id = b.optString("id")
            if (id.isBlank()) p += "bot[$i]: no id" else if (!botIds.add(id)) p += "bot[$i]: duplicate id $id"
            if (b.optString("name").isBlank()) p += "bot $id: no name"
            if (id == MANAGER_ID || b.optString("builtin") == MANAGER_ID) managers++
            val color = if (kind == Kind.V1) b.optJSONObject("look")?.optString("color") else b.optString("color")
            if (color != null && color.isNotEmpty() && !HEX.matches(color)) p += "bot $id: colour $color is not #rrggbb"
            if (kind == Kind.V1 && b.optJSONObject("look") == null) p += "bot $id: no look"
            val face = if (kind == Kind.V1) b.optJSONObject("look")?.opt("face") else b.opt("face")
            if (face != null && face != JSONObject.NULL && (face !is Int || face !in FACES.indices)) p += "bot $id: face $face is not 0..5"
        }
        if (managers != 1) p += "expected exactly one Manager, found $managers"
        objs(bots).forEach { b ->
            val lead = b.optString("lead").takeUnless { b.isNull("lead") }
            if (lead != null && lead.isNotEmpty() && (lead !in botIds || lead == b.optString("id"))) p += "bot ${b.optString("id")}: lead $lead is not another bot"
        }

        val jobIds = mutableSetOf<String>()
        val deps = mutableMapOf<String, List<String>>()
        objs(jobs).forEachIndexed { i, j ->
            val id = j.optString("id")
            if (id.isBlank()) p += "job[$i]: no id" else if (!jobIds.add(id)) p += "job[$i]: duplicate id $id"
            if (j.optString("title").isBlank()) p += "job $id: no title"
            val bot = j.optString(if (kind == Kind.V1) "bot" else "agentId").takeUnless { j.isNull(if (kind == Kind.V1) "bot" else "agentId") }
            if (!bot.isNullOrEmpty() && bot !in botIds) p += "job $id: bot $bot does not exist"
            if (!j.isNull("due") && j.has("due") && !YMD.matches(j.optString("due"))) p += "job $id: due ${j.optString("due")} is not YYYY-MM-DD"
            if (j.optString("priority", "normal") !in PRIORITIES) p += "job $id: priority ${j.optString("priority")}"
            if (j.optString("status", "todo") !in STATUSES) p += "job $id: status ${j.optString("status")}"
            if (j.has("run") && !j.isNull("run") && j.optString("run") !in RUNS) p += "job $id: run ${j.optString("run")}"
            if (j.optString("status") == "done" && (j.isNull("doneAt") || !j.has("doneAt"))) p += "job $id: done without doneAt"
            deps[id] = strings(j.optJSONArray("deps"))
        }
        deps.forEach { (id, ds) -> ds.forEach { d -> if (d == id || d !in jobIds) p += "job $id: waits on unknown job $d" } }
        if (cyclic(deps)) p += "jobs: the waits-on links form a loop"

        if (kind == Kind.V1) {
            objs(o.optJSONArray("profile") ?: JSONArray()).forEach { e ->
                val id = e.optString("id")
                if (e.optString("kind") !in PROFILE_KINDS) p += "profile $id: kind ${e.optString("kind")}"
                val t = e.optString("text")
                if (t.isBlank() || t.length > 400) p += "profile $id: text empty or over 400"
                if (e.opt("stated") !is Boolean) p += "profile $id: stated must be true or false"
                if (isSecret(t) || isSecret(e.optString("context"))) p += "profile $id: looks like a secret"
            }
            val mem = o.optJSONObject("memory")
            if (mem != null) {
                memItems(mem.optJSONArray("team"), "team", p)
                mem.optJSONObject("bots")?.let { m -> m.keys().forEach { k -> if (k !in botIds) p += "memory: bot $k does not exist"; memItems(m.optJSONArray(k), k, p) } }
            }
            objs(o.optJSONArray("routines") ?: JSONArray()).forEach { r ->
                val id = r.optString("id")
                if (r.optString("kind") !in ROUTINE_KINDS) p += "routine $id: kind ${r.optString("kind")}"
                if (schedule(r.optJSONObject("sched")) == null) p += "routine $id: schedule is not valid"
                if (r.optString("bot") !in botIds) p += "routine $id: bot ${r.optString("bot")} does not exist"
                if (r.optString("tz").isBlank()) p += "routine $id: no time zone"
            }
            o.optJSONObject("proactivity")?.let { pr ->
                val q = strings(pr.optJSONArray("quiet"))
                if (q.size != 2 || !q.all { HHMM.matches(it) }) p += "proactivity: quiet must be two HH:MM times"
                if (pr.optInt("cap", -1) < 0) p += "proactivity: cap must be 0 or more"
            }
            o.optJSONObject("chats")?.let { c ->
                c.keys().forEach { k ->
                    if (k !in botIds) p += "chats: bot $k does not exist"
                    objs(c.optJSONArray(k) ?: JSONArray()).forEach { m -> if (m.optString("role") !in setOf("user", "assistant")) p += "chats $k: role ${m.optString("role")}" }
                }
            }
            o.optJSONObject("settings")?.let { s -> strings(s.optJSONArray("pins")).forEach { if (it !in botIds) p += "settings: pin $it is not a bot" } }
        }
        return p
    }

    private fun memItems(a: JSONArray?, where: String, p: MutableList<String>) = objs(a ?: JSONArray()).forEach { m ->
        val t = m.optString("text")
        if (t.isBlank() || t.length > 600) p += "memory $where ${m.optString("id")}: text empty or over 600"
        if (isSecret(t)) p += "memory $where ${m.optString("id")}: looks like a secret"
    }

    private fun forbidden(v: Any?, path: String, p: MutableList<String>) {
        when (v) {
            is JSONObject -> v.keys().forEach { k ->
                if (k.lowercase() in FORBIDDEN_KEYS) p += "$path.$k: credentials never travel in an export"
                forbidden(v.opt(k), "$path.$k", p)
            }
            is JSONArray -> for (i in 0 until v.length()) forbidden(v.opt(i), "$path[$i]", p)
        }
    }

    private fun cyclic(g: Map<String, List<String>>): Boolean {
        val state = mutableMapOf<String, Int>()
        fun visit(n: String): Boolean {
            when (state[n]) { 1 -> return true; 2 -> return false }
            state[n] = 1
            if (g[n].orEmpty().any { it in g && visit(it) }) return true
            state[n] = 2; return false
        }
        return g.keys.any { visit(it) }
    }

    private fun objs(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
}
