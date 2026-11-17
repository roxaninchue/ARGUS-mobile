package com.blaze.assistant.terminal


import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 0.53.7 (owner 17 Nov 19:07): a place Argus leaves servers running. A music server started with run_shell background=true
 * lived only in Terminal's in-memory job list, so when Android killed the app the server went with it, and the next turn
 * ("is the server still running??") had no record it had ever existed. Now a server is a named entry in
 * files/servers.json (name, command, port, Linux or phone shell, folder). The entry outlives the app: JobKeeper keeps the
 * process up while one runs, a server that dies is started again (at most [MAX_RESTARTS] times in [RESTART_WINDOW_MS]),
 * entries left running come back when Argus starts, and every turn's prompt names them with their real state
 * ([promptBlock]), checked by connecting to the port, so the model answers from the record, not from memory.
 */
object Servers {
    data class Entry(val name: String, val cmd: String, val port: Int, val linux: Boolean, val cwd: String, val keep: Boolean,
                     val added: Long, var job: Int = 0, var restarts: MutableList<Long> = mutableListOf())

    const val MAX_RESTARTS = 3
    const val RESTART_WINDOW_MS = 30 * 60_000L
    private lateinit var file: File
    private val list = LinkedHashMap<String, Entry>()

    fun init(ctx: Context) {
        file = File(ctx.applicationContext.filesDir, "servers.json")
        synchronized(list) { list.clear(); load().forEach { list[it.name] = it } }
    }

    private fun ready() = ::file.isInitialized

    fun clean(name: String): String = name.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(40)

    internal fun toJson(e: Entry): JSONObject = JSONObject().put("name", e.name).put("cmd", e.cmd).put("port", e.port)
        .put("linux", e.linux).put("cwd", e.cwd).put("keep", e.keep).put("added", e.added)

    internal fun fromJson(o: JSONObject): Entry? {
        val name = clean(o.optString("name")); val cmd = o.optString("cmd")
        if (name.isEmpty() || cmd.isBlank()) return null
        return Entry(name, cmd, o.optInt("port", 0), o.optBoolean("linux", false), o.optString("cwd"), o.optBoolean("keep", true), o.optLong("added", 0L))
    }

    private fun load(): List<Entry> = try {
        if (!file.exists()) emptyList() else JSONArray(file.readText()).let { a -> (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it)) } }
    } catch (_: Exception) { emptyList() }

    private fun save() {
        if (!ready()) return
        try { val a = JSONArray(); synchronized(list) { list.values.forEach { a.put(toJson(it)) } }; file.writeText(a.toString(2)) } catch (_: Exception) { }
    }

    /** Something answers on 127.0.0.1:[port]. */
    fun listening(port: Int): Boolean {
        if (port <= 0) return false
        return try { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 400); true } } catch (_: Exception) { false }
    }

    private fun up(e: Entry): Boolean = (e.job > 0 && Terminal.jobAlive(e.job)) || listening(e.port)

    private fun state(e: Entry): String {
        val job = e.job > 0 && Terminal.jobAlive(e.job)
        val port = listening(e.port)
        return when {
            job && (port || e.port <= 0) -> "up"
            job -> "starting (process alive, nothing on port ${e.port} yet)"
            port -> "port ${e.port} answers, but not from this entry's process"
            else -> "down"
        }
    }

    private fun launch(e: Entry): String {
        val cd = if (e.cwd.isNotBlank()) "cd '" + e.cwd.replace("'", "'\\''") + "' && " else ""
        val out = Terminal.startJob(cd + e.cmd, e.linux)
        e.job = Terminal.lastJob
        return out
    }

    fun start(name: String, cmd: String, port: Int, linux: Boolean, cwd: String, keep: Boolean): String {
        val n = clean(name)
        if (n.isEmpty()) return "Give the server a short name (e.g. music)."
        if (cmd.isBlank()) return "Give the command that starts the server."
        synchronized(list) { list[n] }?.let { old -> if (old.job > 0) Terminal.stopJob(old.job) }
        if (port > 0 && listening(port)) return "Port $port is already in use by something else. Pick another port or stop what's on it (servers action=list)."
        val e = Entry(n, cmd.trim(), port, linux, cwd.trim(), keep, System.currentTimeMillis())
        synchronized(list) { list[n] = e }
        save()
        val out = launch(e)
        var ok = false
        if (port > 0) for (i in 0 until 20) { if (listening(port)) { ok = true; break }; if (e.job > 0 && !Terminal.jobAlive(e.job)) break; Thread.sleep(500) }
        val alive = e.job > 0 && Terminal.jobAlive(e.job)
        val head = when {
            port > 0 && ok -> "Server '$n' is up on port $port (job ${e.job}). Open: http://127.0.0.1:$port/ (preview_server port=$port)."
            alive && port > 0 -> "Server '$n' started (job ${e.job}) but nothing answers on port $port after 10 s. Read its log: servers action=logs name=$n."
            alive -> "Server '$n' started (job ${e.job})."
            else -> "Server '$n' did NOT start: its process exited. Read the output, fix it, then servers action=start again."
        }
        return head + (if (keep) " It is kept: restarted if it dies and when ${com.blaze.assistant.BrandName.NAME} starts again, until servers action=stop." else "") +
            "\n" + out.takeLast(1200)
    }

    fun stop(name: String): String {
        val e = synchronized(list) { list.remove(clean(name)) } ?: return "No server named '$name'. " + names()
        if (e.job > 0) Terminal.stopJob(e.job)
        save()
        return "Stopped server '${e.name}' and removed it from the kept servers."
    }

    fun restart(name: String): String {
        val e = synchronized(list) { list[clean(name)] } ?: return "No server named '$name'. " + names()
        return start(e.name, e.cmd, e.port, e.linux, e.cwd, e.keep)
    }

    fun logs(name: String, lines: Int): String {
        val e = synchronized(list) { list[clean(name)] } ?: return "No server named '$name'. " + names()
        return if (e.job > 0) Terminal.jobLog(e.job, lines) else "Server '${e.name}' has no process yet (${state(e)})."
    }

    private fun names(): String = synchronized(list) { if (list.isEmpty()) "No servers are kept." else "Servers: " + list.keys.joinToString() }

    fun describe(): String {
        val all = synchronized(list) { list.values.toList() }
        if (all.isEmpty()) return "No servers are kept. Start one with servers action=start name=<name> command=<cmd> port=<port>."
        return all.joinToString("\n") { e ->
            "${e.name} · ${state(e)} · port ${if (e.port > 0) e.port else "none"}${if (e.job > 0) " · job ${e.job}" else ""} · ${if (e.linux) "Linux" else "phone shell"}" +
                (if (e.cwd.isNotBlank()) " · in ${e.cwd}" else "") + "\n    $ ${e.cmd.take(160)}"
        }
    }

    /** Short status for the JobKeeper notification ("music :8080"), or "". */
    fun noteLine(): String = synchronized(list) { list.values.joinToString(", ") { if (it.port > 0) "${it.name} :${it.port}" else it.name } }

    fun has(): Boolean = synchronized(list) { list.isNotEmpty() }

    /** Every turn: the kept servers and their real state, so "is it still running?" is answered from the record. */
    fun promptBlock(): String {
        if (!ready() || !has()) return ""
        return "\n\nSERVERS YOU KEEP RUNNING (servers tool; checked just now, trust this over memory; never say one doesn't exist without servers action=list):\n" +
            describe() + "\nIf one is down, read servers action=logs, fix the cause, then servers action=restart.\n"
    }

    /** JobKeeper sweep: a kept server whose process died is started again, within the restart budget. */
    fun onJobEnded(job: Int): String? {
        val e = synchronized(list) { list.values.firstOrNull { it.job == job } } ?: return null
        if (!e.keep) return null
        val now = System.currentTimeMillis()
        e.restarts.removeAll { now - it > RESTART_WINDOW_MS }
        if (e.restarts.size >= MAX_RESTARTS) { e.job = 0; return "Server '${e.name}' keeps stopping ($MAX_RESTARTS restarts in 30 min). Left it down; ask ${com.blaze.assistant.BrandName.NAME} to look at its log." }
        e.restarts.add(now)
        launch(e)
        return null
    }

    /** At start: kept servers whose process is gone (the app was killed) come back. */
    fun resume() {
        if (!ready()) return
        val todo = synchronized(list) { list.values.filter { it.keep } }
        for (e in todo) { if (!up(e)) try { launch(e) } catch (_: Exception) { } }
    }

    // ---------- tool ----------

    const val NAME = "servers"

    fun tool(): JSONObject {
        fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)
        val props = JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("list", "start", "stop", "restart", "logs"))).put("description", "list (default), start, stop, restart, logs"))
            .put("name", p("string", "Short name for the server, e.g. music"))
            .put("command", p("string", "start: the command that runs the server in the foreground (no trailing &), e.g. python3 server.py"))
            .put("port", p("integer", "start: the port it listens on; used to check it is really up"))
            .put("linux", p("boolean", "start: run inside ${com.blaze.assistant.BrandName.NAME}'s Linux (python, node...) instead of the phone shell"))
            .put("folder", p("string", "start: folder to run it in, e.g. /root/music-server"))
            .put("keep", p("boolean", "start: keep it running (restart if it dies and when ${com.blaze.assistant.BrandName.NAME} starts). Default true"))
            .put("lines", p("integer", "logs: last N lines, default 80"))
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", NAME)
            .put("description", "The place servers live. Start a server the user will keep using here (not run_shell background=true): it gets a name, keeps running when ${com.blaze.assistant.BrandName.NAME} is closed, is restarted if it dies or the app is killed, and every turn shows whether it is really answering on its port. list shows each one's real state; logs shows its output; stop removes it.")
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray())))
    }

    fun run(args: JSONObject): String = when (args.optString("action", "list").ifBlank { "list" }) {
        "start" -> start(args.optString("name"), args.optString("command"), args.optInt("port", 0), args.optBoolean("linux", false), args.optString("folder"), args.optBoolean("keep", true))
        "stop" -> stop(args.optString("name"))
        "restart" -> restart(args.optString("name"))
        "logs" -> logs(args.optString("name"), args.optInt("lines", 80))
        else -> describe()
    }
}
