package com.blazeinchue.argus.agent


import com.blazeinchue.argus.BrandName
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ArgusRouter on the phone: Argus Desktop's router (Go port of OmniRoute, MIT) shipped as
 * libargusrouter.so and run as a local server on 127.0.0.1:20128 with its dashboard.
 *
 * Where it runs ([runner] pref "router_runner"): "alpine" = inside Argus's Alpine Linux (proot job),
 * "termux" = in the Termux app, "direct" = a plain child process. "auto" tries Alpine, then Termux,
 * then direct. Its data lives in ~/.argus (filesDir/home/.argus), the same layout as Desktop/CLI;
 * the phone's model keys are handed over at start (ARGUS_KEYS) so every preset can use them.
 */
object ArgusRouter {
    const val ID = "argusrouter"
    const val PORT = 20128
    const val BASE = "http://127.0.0.1:$PORT"
    // Every OmniRoute auto-preset is served; this one is the daily driver.
    const val DEFAULT_MODEL = "auto/best-free"
    val RUNNERS = listOf("auto", "alpine", "termux", "direct")

    data class State(val running: Boolean = false, val where: String = "", val note: String = "", val dashboard: String = "")
    val state = MutableStateFlow(State())

    private var proc: Process? = null
    private var jobId = 0

    var runner: String
        get() = Settings.str("router_runner", "auto")
        set(v) = Settings.setStr("router_runner", v)
    var autostart: Boolean
        get() = Settings.bool("router_autostart", false)
        set(v) = Settings.setBool("router_autostart", v)

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libargusrouter.so")
    fun home(ctx: Context) = File(File(ctx.filesDir, "home"), ".argus").apply { mkdirs() }

    /** The phone's model keys in the keystore's keys.json shape ({"gemini": k, "gemini#extra": "[…]"}). */
    fun keysJson(): String {
        val o = JSONObject()
        Providers.all.filter { it.id != Providers.AUTO && it.id != ID && it.id != Providers.CUSTOM }.forEach { p ->
            val ks = KeyRing.all(p.id)
            if (ks.isNotEmpty()) {
                o.put(p.id, ks.first())
                if (ks.size > 1) o.put(p.id + "#extra", org.json.JSONArray(ks.drop(1)).toString())
            }
        }
        return o.toString()
    }

    suspend fun healthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val c = URL("$BASE/health").openConnection() as HttpURLConnection
            c.connectTimeout = 800; c.readTimeout = 1500
            c.inputStream.bufferedReader().readText().contains("ArgusRouter")
        } catch (_: Exception) { false }
    }

    /** Reads the run file the server writes (api key, dashboard link) and saves the key for the provider. */
    private fun adopt(ctx: Context, where: String): State {
        val run = File(home(ctx), "argusrouter.run.json")
        val o = try { JSONObject(run.readText()) } catch (_: Exception) { JSONObject() }
        o.optString("api_key").takeIf { it.isNotBlank() }?.let { Settings.setKeyForSynced(ID, it) }
        return State(true, where, "", o.optString("dashboard")).also { state.value = it }
    }

    private suspend fun waitUp(seconds: Int): Boolean {
        repeat(seconds * 4) { if (healthy()) return true; delay(250) }
        return false
    }

    suspend fun start(ctx: Context): String {
        if (healthy()) return "ArgusRouter is already running (${adopt(ctx, state.value.where.ifBlank { "running" }).where})."
        val bin = binary(ctx)
        if (!bin.exists()) return "This build has no ArgusRouter for this phone's CPU."
        val keys = keysJson()
        val order = when (runner) { "alpine" -> listOf("alpine"); "termux" -> listOf("termux"); "direct" -> listOf("direct"); else -> listOf("alpine", "termux", "direct") }
        val notes = mutableListOf<String>()
        for (r in order) {
            val err = try { launch(ctx, r, bin, keys) } catch (e: Exception) { e.message ?: "failed" }
            if (err == null && waitUp(if (r == "direct") 6 else 12)) { adopt(ctx, r); return "ArgusRouter is running in ${label(r)} on $BASE. Dashboard: ${state.value.dashboard.substringBefore('#')}" }
            notes += "${label(r)}: ${err ?: "didn't answer"}"
        }
        state.value = State(false, "", notes.joinToString("; "))
        return "ArgusRouter didn't start. " + notes.joinToString("; ")
    }

    fun label(r: String) = when (r) { "alpine" -> "Alpine Linux"; "termux" -> "Termux"; "direct" -> "${BrandName.NAME}"; else -> r }

    /** Starts the server one way; null = launched (health is checked by the caller). */
    private suspend fun launch(ctx: Context, r: String, bin: File, keys: String): String? = when (r) {
        "alpine" -> {
            if (!com.blazeinchue.argus.terminal.Linux.installed()) "Alpine isn't installed"
            else withContext(Dispatchers.IO) {
                // Inside proot the phone's home is /root/phone; copy the binary there so Alpine can run it.
                val dir = File(File(ctx.filesDir, "home"), ".argus/bin").apply { mkdirs() }
                val dst = File(dir, "argusrouter")
                if (!dst.exists() || dst.length() != bin.length()) { bin.copyTo(dst, overwrite = true); dst.setExecutable(true) }
                File(home(ctx), ".keys.in").apply { writeText(keys); setReadable(true, true) }
                val cmd = "K=\"\$(cat /root/phone/.argus/.keys.in)\"; rm -f /root/phone/.argus/.keys.in; ARGUS_KEYS=\"\$K\" exec /root/phone/.argus/bin/argusrouter --home /root/phone/.argus"
                val out = com.blazeinchue.argus.terminal.Terminal.startJob(cmd, linux = true, maxMinutes = 0, distro = "alpine")
                jobId = Regex("\\d+").find(out)?.value?.toIntOrNull() ?: 0
                if (out.contains("rror", true)) out else null
            }
        }
        "termux" -> {
            if (!com.blazeinchue.argus.terminal.Terminal.termuxInstalled(ctx)) "Termux isn't installed"
            else {
                // Termux can run the library (it's world-readable) but keeps its data in its own ~/.argus.
                val q = keys.replace("'", "'\\''")
                val out = com.blazeinchue.argus.terminal.Terminal.runInTermux(ctx, "ARGUS_KEYS='$q' nohup '${bin.absolutePath}' >/dev/null 2>&1 &", background = true)
                if (out.contains("didn't allow") || out.contains("isn't installed")) out else null
            }
        }
        else -> withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(bin.absolutePath, "--home", home(ctx).absolutePath).redirectErrorStream(true)
            pb.environment()["ARGUS_KEYS"] = keys
            pb.environment()["HOME"] = File(ctx.filesDir, "home").absolutePath
            proc = pb.start()
            Thread { try { proc?.inputStream?.bufferedReader()?.forEachLine { } } catch (_: Exception) { } }.apply { isDaemon = true }.start()
            null
        }
    }

    suspend fun stop(ctx: Context): String {
        proc?.destroy(); proc = null
        if (jobId > 0) runCatching { com.blazeinchue.argus.terminal.Terminal.stopJob(jobId) }; jobId = 0
        // Termux (or a leftover) still answering: ask nicely through the run file's pid isn't possible, so say so.
        val still = waitDown()
        state.value = State(still, if (still) state.value.where else "", if (still) "Still running in ${label(state.value.where)}; stop it there." else "")
        return if (still) state.value.note else "ArgusRouter stopped."
    }

    private suspend fun waitDown(): Boolean { repeat(8) { if (!healthy()) return false; delay(250) }; return true }

    /** Picking ArgusRouter as the brain: make sure it's up first. */
    suspend fun ensure(ctx: Context): Boolean = healthy() || start(ctx).startsWith("ArgusRouter is")

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", "argusrouter")
        .put("description", "ArgusRouter, the local model router on this phone (OmniRoute presets like auto/best-free, auto/fast, chaos fan-out, last-known-good) with a web dashboard. " +
            "status, start (runs in Alpine Linux, Termux or directly), stop, use (make it ${BrandName.NAME}'s brain), dashboard (open it).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", org.json.JSONArray(listOf("status", "start", "stop", "use", "dashboard"))))
            .put("runner", JSONObject().put("type", "string").put("enum", org.json.JSONArray(RUNNERS)).put("description", "start: where to run it (default: the saved choice, auto)"))
            .put("model", JSONObject().put("type", "string").put("description", "use: preset, default auto/best-free")))
            .put("required", org.json.JSONArray(listOf("action")))))

    suspend fun run(ctx: Context, args: JSONObject): String = when (args.optString("action")) {
        "start" -> { args.optString("runner").takeIf { it in RUNNERS }?.let { runner = it }; start(ctx) }
        "stop" -> stop(ctx)
        "use" -> {
            if (!ensure(ctx)) "ArgusRouter isn't running: ${state.value.note}"
            else { Settings.providerId = ID; Settings.model = args.optString("model").ifBlank { DEFAULT_MODEL }; "${BrandName.NAME} now answers through ArgusRouter · ${Settings.model}." }
        }
        "dashboard" -> {
            if (!ensure(ctx)) "ArgusRouter isn't running: ${state.value.note}"
            else { openDashboard(ctx); "Opened the ArgusRouter dashboard." }
        }
        else -> if (healthy()) "Running in ${label(state.value.where.ifBlank { "direct" })} on $BASE (runner setting: $runner)." else "Not running (runner setting: $runner). ${state.value.note}"
    }

    /** Opens the dashboard (already signed in) in Argus's own browser. */
    suspend fun openDashboard(ctx: Context) {
        val u = state.value.dashboard.ifBlank { "$BASE/dashboard/" }
        try { com.blazeinchue.argus.browser.BrowserEngine.open(ctx.applicationContext, u, true) } catch (_: Exception) { }
        withContext(Dispatchers.Main) { com.blazeinchue.argus.browser.BrowserTools.show(ctx.applicationContext) }
    }
}
