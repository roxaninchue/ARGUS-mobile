package com.blaze.assistant.terminal

import com.blaze.assistant.BrandName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Terminal checkpoints on the phone (the store itself is [CheckpointStore]).
 *
 * What a checkpoint holds:
 *  - "home": the phone shell's home (~), which is also /root/phone inside every Linux;
 *  - each installed Linux's own folders (/root, /home, /etc, /opt, /srv, /usr/local), named "<distro>-root" and so on;
 *  - with system=true (saved on request only): the whole Linux, installed packages included ("<distro>-system").
 *
 * Automatic checkpoints: before every command or file change the agent makes ([MUTATING]), [before] takes one. A
 * checkpoint equal to the newest one is not taken twice, so a run of read-only commands costs a stat walk each and
 * keeps nothing new. The tool result then names the checkpoint, so the agent (or the user, asking in chat) can roll
 * back what a command broke. The Terminal screen's ⋮ menu lists them too (Checkpoints…).
 */
object TermCheckpoints {
    const val NAME = "terminal_checkpoint"

    /** Agent tools that can change files in the terminal's folders. */
    val MUTATING = setOf("run_shell", "run_code", "file_write", "file_edit", "file_delete", "download_file", "git", "linux_install")

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun init(ctx: Context) { app = ctx.applicationContext }
    fun ready() = ::app.isInitialized

    val store: CheckpointStore by lazy { CheckpointStore(File(app.filesDir, "checkpoints")) }

    /** Turned off from the Terminal's menu; on by default. */
    var auto: Boolean
        get() = !ready() || app.getSharedPreferences("terminal", Context.MODE_PRIVATE).getBoolean("checkpoints_auto", true)
        set(v) { app.getSharedPreferences("terminal", Context.MODE_PRIVATE).edit().putBoolean("checkpoints_auto", v).apply() }

    /** Caches and dependency folders that rebuild themselves: never snapshotted, never touched by a rollback. */
    internal val SKIP = setOf(".cache", ".gradle", ".m2", ".npm", ".pnpm-store", ".yarn", ".cargo", ".rustup", "node_modules", "__pycache__", ".venv", "venv")

    private val LINUX_USER_DIRS = listOf("root", "home", "etc", "opt", "srv", "usr/local")
    private val LINUX_SYSTEM_EXCLUDES = setOf("proc", "sys", "dev", "tmp", "run", "var/tmp", "var/cache", "root/phone", "sdcard", "storage", "data", "mnt")

    /** The folders a checkpoint covers. Pure, so the naming is tested on the JVM. */
    internal fun scopesFor(home: File, distros: Map<String, File>, system: Boolean): List<CheckpointStore.Scope> = buildList {
        add(CheckpointStore.Scope("home", home, emptySet(), SKIP))
        for ((id, rootfs) in distros.toSortedMap()) {
            if (system) add(CheckpointStore.Scope("$id-system", rootfs, LINUX_SYSTEM_EXCLUDES, SKIP))
            else for (d in LINUX_USER_DIRS) {
                val dir = File(rootfs, d); if (!dir.isDirectory) continue
                add(CheckpointStore.Scope("$id-" + d.replace('/', '-'), dir, if (d == "root") setOf("phone") else emptySet(), SKIP))
            }
        }
    }

    private fun installedRoots(): Map<String, File> = try { Distros.installedIds().associateWith { Linux.rootfs(it) } } catch (_: Throwable) { emptyMap() }

    fun scopes(system: Boolean = false) = scopesFor(Terminal.home(), installedRoots(), system)

    /** Every scope a restore may need (user folders and whole systems), so any checkpoint can be put back. */
    private fun allScopes() = scopes(false) + scopes(true).filter { it.name.endsWith("-system") }

    /**
     * Called before a mutating agent tool. Returns the line added to that tool's result (naming the checkpoint that
     * holds the state before it), or "" when checkpoints are off or the snapshot couldn't be taken in time.
     */
    suspend fun before(tool: String, args: JSONObject): String {
        if (!ready() || !auto || tool !in MUTATING) return ""
        if (tool == "run_shell" && args.optString("command").isBlank()) return ""
        val what = when (tool) {
            "run_shell" -> "$ " + args.optString("command").lineSequence().first().take(80)
            "git" -> "git " + args.optString("action") + " " + args.optString("args").take(60)
            else -> tool + " " + args.optString("path").ifBlank { args.optString("language") }.take(60)
        }.trim()
        // The walk is blocking IO: it runs on its own scope and the tool waits at most 20 s for it (a first checkpoint of a big
        // home can take longer; it finishes in the background and the next command finds it).
        val job = scope.async { try { store.snapshot(scopes(), "", "before $what", auto = true) } catch (_: Throwable) { null } }
        val snap = withTimeoutOrNull(20_000) { job.await() } ?: return ""
        return "\n[checkpoint ${snap.checkpoint.id} holds the files as they were before this; $NAME action=rollback id=${snap.checkpoint.id} undoes it]"
    }

    // ---------- the tool ----------

    fun tool(): JSONObject {
        fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)
        val props = JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("save", "list", "changes", "rollback", "delete")))
                .put("description", "save: take a checkpoint now. list: the checkpoints, newest first. changes: what changed since one. rollback: put the files back as they were at one. delete: remove one."))
            .put("id", p("string", "Checkpoint id (cp-12) for changes / rollback / delete"))
            .put("label", p("string", "Short name for a saved checkpoint, e.g. 'before upgrading node'"))
            .put("system", p("boolean", "save only: include the whole Linux (installed packages too), not just ~ and the Linux's own folders. Slower and bigger; use before risky package or system changes."))
            .put("paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                .put("description", "rollback only: restore just these paths (relative to their folder, e.g. 'project/src' in ~, or 'alpine-etc:apk/world'); everything else stays as it is now"))
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", NAME)
            .put("description", "Checkpoints (rollbacks) of ${BrandName.NAME}'s terminal: the phone home (~) and each Linux's folders. " +
                "One is taken automatically before every command or file change you make (the tool result names it), so when a command broke " +
                "something, or the user wants to go back, look at 'changes' and then 'rollback' to that checkpoint. A rollback first checkpoints the " +
                "current state, so it can be undone too. Installed packages are only covered by a checkpoint saved with system=true.")
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(listOf("action")))))
    }

    /** The card shown before a rollback: (title, detail). */
    fun describeRollback(args: JSONObject): Pair<String, String> {
        val id = args.optString("id")
        val cp = if (ready()) store.get(id) else null
        val paths = args.optJSONArray("paths")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val title = "Roll the terminal back to $id?"
        val detail = (cp?.let { "${ago(it.created)} · ${it.label.ifBlank { it.reason }}".take(120) } ?: "") +
            (if (paths.isNotEmpty()) "\nOnly: " + paths.joinToString().take(100) else "\nFiles changed since then are put back; new ones are removed.")
        return title to detail
    }

    suspend fun run(args: JSONObject): String = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext "Checkpoints aren't ready yet."
        val id = args.optString("id").trim()
        try {
            when (args.optString("action")) {
                "save" -> {
                    val system = args.optBoolean("system")
                    val s = store.snapshot(scopes(system), args.optString("label"), "saved on request", auto = false)
                    val c = s.checkpoint
                    (if (s.created) "Saved checkpoint ${c.id}" else "Nothing changed since ${c.id}; it already holds this state") +
                        " · ${c.files} files, ${mb(c.bytes)}" + (if (c.skipped > 0) " (${c.skipped} files over ${mb(store.maxFileBytes)} left out)" else "") +
                        (if (system) " · includes the whole Linux" else "")
                }
                "list" -> list()
                "changes" -> {
                    val cp = store.get(id.ifBlank { store.latest()?.id.orEmpty() }) ?: return@withContext "No checkpoint ${id.ifBlank { "yet" }}. ${shortList()}"
                    "Since ${cp.id} (${ago(cp.created)}, ${cp.label.ifBlank { cp.reason }}):\n" + store.diff(cp.id, allScopes()).summary()
                }
                "rollback" -> {
                    val cp = store.get(id) ?: return@withContext "No checkpoint \"$id\". ${shortList()}"
                    val paths = args.optJSONArray("paths")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    val jobs = Terminal.jobCount()
                    val r = store.restore(cp.id, allScopes(), paths)
                    "Rolled back to ${cp.id} (${ago(cp.created)}): ${r.rewritten} files put back, ${r.deleted} removed, ${r.unchanged} already right." +
                        (r.safety?.let { "\nThe state before this rollback is ${it.id} (rollback id=${it.id} to undo)." } ?: "") +
                        (if (r.errors.isNotEmpty()) "\nCouldn't restore: " + r.errors.take(8).joinToString("; ") else "") +
                        (if (jobs > 0) "\nNote: $jobs background job(s) are still running and may change files again (terminal_jobs / terminal_stop)." else "") +
                        (if (cp.scopes.none { it.endsWith("-system") }) "\nInstalled packages aren't part of this checkpoint." else "")
                }
                "delete" -> if (store.delete(id)) "Deleted $id." else "No checkpoint \"$id\"."
                else -> "Unknown action; use save, list, changes, rollback or delete."
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun list(max: Int = 15): String {
        val l = store.list()
        if (l.isEmpty()) return "No checkpoints yet."
        return l.take(max).joinToString("\n") { c ->
            "${c.id} · ${ago(c.created)} · ${if (c.auto) "auto" else "saved"} · ${c.label.ifBlank { c.reason }.take(80)} · ${c.files} files" +
                if (c.scopes.any { it.endsWith("-system") }) " · whole Linux" else ""
        } + (if (l.size > max) "\n…${l.size - max} older" else "") + "\nStore: ${mb(store.diskBytes())} on disk."
    }

    private fun shortList() = store.list().take(5).joinToString(", ") { it.id }.let { if (it.isBlank()) "There are none yet." else "Recent: $it." }

    fun ago(t: Long): String {
        val s = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
        return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86_400 -> "${s / 3600} h ago"; else -> "${s / 86_400} d ago" }
    }

    private fun mb(b: Long) = if (b < 1024 * 1024) "${(b + 1023) / 1024} KB" else "%.1f MB".format(b / 1048576.0)
}
