package com.blaze.assistant.device


import com.blaze.assistant.BrandName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local crash + slowness log the user can share in one tap. Nothing leaves the phone by itself. */
object CrashLog {
    private lateinit var file: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private lateinit var dir: File

    /**
     * 0.54.x (20 Nov report, a low-memory phone: "CRASH (previous run, crash)" and no line, three memory trims before it): when
     * the heap is full, even the fallback line can't be built. This reserve is let go first in the crash handler, so
     * the CRASH line always has room to be written.
     */
    @Volatile private var reserve: ByteArray? = null
    private fun freeReserve() { reserve = null }

    fun init(ctx: Context) {
        try { if (reserve == null) reserve = ByteArray(256 * 1024) } catch (_: Throwable) { }
        file = File(ctx.filesDir, "blaze_log.txt")
        dir = ctx.filesDir
        // 0.46.6: the vault file is known before the crash handler and the last-exit check run (the Agent sets it later),
        // so a crash at start and the next-launch crash report are scrubbed too.
        try { if (com.blaze.assistant.agent.SecretVault.file == null) com.blaze.assistant.agent.SecretVault.file = File(ctx.filesDir, "secret_vault.json") } catch (_: Throwable) { }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            freeReserve()
            // 0.54.2: an OutOfMemoryError can fail the full trace (16 Nov report: "CRASH (previous run, crash)" and no line);
            // then at least its type and top frames are written.
            try { writeNow("CRASH on ${t.name}: ${android.util.Log.getStackTraceString(e).take(6000)}"); markPending() } catch (_: Throwable) {
                try { writeNow("CRASH on " + t.name + ": " + e.javaClass.name + " at " + e.stackTrace.take(8).joinToString(" < ")); markPending() } catch (_: Throwable) { }
            }
            prev?.uncaughtException(t, e)
        }
        // 0.46.5: a crash with no Java stack (native: the provider engine) leaves no CRASH line. On the next start, read
        // Android's exit record (API 30+) and the in-flight marker, write what happened, and offer "Send crash report".
        Thread({ try { checkLastExit(ctx) } catch (_: Throwable) { } }, "blaze-crash-check").start()
    }

    // ---------------- 0.46.5: redaction, in-flight markers, last-exit check, "Send crash report" ----------------

    /** Names, ids and hosts of sources the user added: never written to the log as such (see [redact]). */
    private val secrets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Remember a user-added source's name / id / host so every log line shows "a source" instead. Blank and very short values are ignored. */
    fun secret(vararg values: String?) { values.forEach { v -> v?.trim()?.takeIf { it.length >= 3 }?.let { secrets += it } } }

    /** 0.46.6: saved secrets back to their handles (Secure form vault). Never throws. */
    fun scrubSecrets(text: String): String = try { com.blaze.assistant.agent.SecretVault.scrub(text) } catch (_: Throwable) { text }

    /** Pure: [text] with each of [names] (case-insensitive, longest first) replaced by "a source". */
    fun redact(text: String, names: Collection<String>): String {
        var out = text
        names.filter { it.length >= 3 }.sortedByDescending { it.length }.forEach { n -> out = out.replace(n, "a source", ignoreCase = true) }
        return out
    }

    private val inflight = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val inflightN = java.util.concurrent.atomic.AtomicLong()

    /**
     * Mark a risky native call (a provider in the JS engine) as running: if the process dies inside it, the next start
     * says so in the log. Returns a token for [leave]. Never throws; the label is redacted.
     */
    fun enter(label: String): Long {
        val id = inflightN.incrementAndGet()
        try { inflight[id] = redact(scrubSecrets(label), secrets).take(160); saveInflight() } catch (_: Throwable) { }
        return id
    }
    fun leave(id: Long) { try { inflight.remove(id); saveInflight() } catch (_: Throwable) { } }
    @Synchronized private fun saveInflight() {
        if (!::dir.isInitialized) return
        val f = File(dir, "blaze_inflight.txt")
        if (inflight.isEmpty()) f.delete() else f.writeText(inflight.values.joinToString("\n"))
    }

    private fun markPending() { try { if (::dir.isInitialized) File(dir, "blaze_crash_pending").writeText(System.currentTimeMillis().toString()) } catch (_: Throwable) { } }
    /** A crash was recorded since the user last chose: show "Send crash report" once. */
    fun pendingReport(): Boolean = try { ::dir.isInitialized && File(dir, "blaze_crash_pending").exists() } catch (_: Throwable) { false }
    fun dismissReport() { try { if (::dir.isInitialized) File(dir, "blaze_crash_pending").delete() } catch (_: Throwable) { } }
    @Volatile var pendingChanged: (() -> Unit)? = null

    /**
     * Pure: the log line for a previous run's exit record, or null when it wasn't a crash / ANR. [pssKb] / [rssKb] are the
     * record's memory at exit (0 = unknown) and [background] whether Argus wasn't on screen, so a crash with no
     * description still says whether memory was the likely cause.
     */
    fun exitLine(reason: Int, description: String?, trace: String?, during: String?, names: Collection<String>,
                 pssKb: Long = 0, rssKb: Long = 0, background: Boolean? = null): String? {
        val kind = when (reason) { 4 -> "native crash"; 5 -> "crash"; 6 -> "ANR"; else -> return null }   // ApplicationExitInfo.REASON_CRASH_NATIVE / CRASH / ANR
        val sb = StringBuilder("CRASH (previous run, $kind)")
        if (!description.isNullOrBlank()) sb.append(": ").append(description.take(300))
        if (!during.isNullOrBlank()) sb.append(" | while running: ").append(during.take(300))
        if (pssKb > 0 || rssKb > 0) sb.append(" | memory at exit: pss ").append(pssKb / 1024).append(" MB, rss ").append(rssKb / 1024).append(" MB")
        if (background != null) sb.append(if (background) " | in the background" else " | on screen")
        if (!trace.isNullOrBlank()) sb.append("\n").append(trace.take(6000))
        return redact(scrubSecrets(sb.toString()), names)
    }

    private fun head(i: java.io.InputStream, max: Int): String {
        val buf = ByteArray(max); var n = 0
        while (n < max) { val r = i.read(buf, n, max - n); if (r <= 0) break; n += r }
        return String(buf, 0, n, Charsets.UTF_8)
    }

    @Synchronized private fun checkLastExit(ctx: Context) {
        val mark = File(ctx.filesDir, "blaze_inflight.txt")
        val during = if (mark.exists()) mark.readText().lines().filter { it.isNotBlank() }.joinToString("; ") else null
        mark.delete()
        val seenF = File(ctx.filesDir, "blaze_exit_seen")
        val seen = seenF.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        var wrote = false
        if (Build.VERSION.SDK_INT >= 30) {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            val list = am?.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).orEmpty().filter { it.timestamp > seen }
            list.maxOfOrNull { it.timestamp }?.let { seenF.writeText(it.toString()) }
            list.sortedBy { it.timestamp }.forEach { x ->
                val trace = if (x.reason == 4 || x.reason == 6) try { x.traceInputStream?.use { head(it, 12_000) } } catch (_: Throwable) { null } else null
                exitLine(x.reason, x.description, trace?.replace("\u0000", "")?.trim(), during, secrets,
                    x.pss, x.rss, x.importance > android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)?.let { writeNow(it); wrote = true }
            }
        }
        if (!wrote && during != null) { writeNow(redact(scrubSecrets("CRASH (previous run ended while running: $during)"), secrets)); wrote = true }
        if (wrote) { markPending(); pendingChanged?.invoke() }
    }

    // ---------------- 0.55.15: crash-loop safe screen (24 Nov report: "keeps crashing" after sign-in, no way to send logs) ----------------

    /** How long a launch must stay up before it counts as surviving (a crash sooner is a crash loop). */
    const val SURVIVE_MS = 20_000L

    /**
     * Pure: whether this launch should open the crash screen instead of Argus. [lastLaunchUnfinished] = the previous
     * launch never reached [SURVIVE_MS] (its marker is still there); [pending] = a crash is recorded and unreported.
     */
    fun safeScreen(lastLaunchUnfinished: Boolean, pending: Boolean): Boolean = lastLaunchUnfinished && pending

    /**
     * Called first thing in MainActivity.onCreate. Marks this launch as running and returns true when the previous launch
     * died quickly with a crash on record: show [com.blaze.assistant.device.CrashReportActivity] so the report can be
     * shared even though Argus itself closes at once. Never throws.
     */
    fun launchBegan(ctx: Context): Boolean = try {
        val f = File(ctx.filesDir, "blaze_launch")
        val unfinished = f.exists()
        f.writeText(System.currentTimeMillis().toString())
        // A native crash is only known from Android's exit record: read it now (synchronized with the start-up check).
        if (unfinished && !pendingReport()) try { checkLastExit(ctx) } catch (_: Throwable) { }
        safeScreen(unfinished, pendingReport())
    } catch (_: Throwable) { false }

    /** This launch stayed up [SURVIVE_MS]: not a crash loop. Never throws. */
    fun launchSurvived(ctx: Context) { try { File(ctx.filesDir, "blaze_launch").delete() } catch (_: Throwable) { } }

    /** Pure: the part of the log the crash screen shows: the newest CRASH / CAUGHT entry and what follows it, at most [max] chars. */
    fun lastCrash(log: String, max: Int = 6000): String {
        val i = maxOf(log.lastIndexOf("CRASH"), log.lastIndexOf("CAUGHT on main (giving up)"))
        val from = if (i < 0) maxOf(0, log.length - max) else maxOf(0, log.lastIndexOf('\n', i).let { if (it < 0) 0 else it + 1 })
        return log.substring(from).take(max).trim()
    }

    /**
     * Added to every background scope: a failure inside Argus's own work is logged and shown as
     * an error in the chat instead of closing the app.
     */
    val guard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        write("CAUGHT: ${android.util.Log.getStackTraceString(e).take(4000)}")
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { com.blaze.assistant.agent.Agent.reportError(lineFor(e)) } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    /**
     * 0.46.3 (A5; report: "Something went wrong (t8: Blaze Cloud is busy…)": "t8" was the release build's shrunk name for
     * the model-call error class). A model error shows its own plain words; anything else a plain line. Never a class name.
     */
    fun lineFor(e: Throwable, kept: Boolean = false): String = when {
        e is com.blaze.assistant.agent.LlmClient.LlmException && !e.message.isNullOrBlank() -> e.message!!.trim()
        kept -> "Something went wrong, but ${BrandName.NAME} kept running. It's in the crash report."
        else -> "Something went wrong. It's in the crash report; try again."
    }

    private val recent = ArrayDeque<Long>()

    /**
     * Keep the app alive through a stray error on the main thread (a tap handler, a UI update):
     * log it, show it in chat, and carry on. If errors pile up (5 in 10 s), let Android restart us.
     */
    fun shieldMainThread() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            while (true) {
                try { android.os.Looper.loop() } catch (e: Throwable) {
                    val now = System.currentTimeMillis()
                    recent.addLast(now); while (recent.isNotEmpty() && now - recent.first() > 10_000) recent.removeFirst()
                    if (recent.size >= 5 || e is OutOfMemoryError) { freeReserve(); writeNow("CAUGHT on main (giving up): ${android.util.Log.getStackTraceString(e).take(4000)}"); throw e }
                    write("CAUGHT on main: ${android.util.Log.getStackTraceString(e).take(4000)}")
                    try { com.blaze.assistant.agent.Agent.reportError(lineFor(e, kept = true)) } catch (_: Throwable) { }
                }
            }
        }
    }

    /**
     * 0.45.16 smoothness: log lines are written on one background thread, never on the caller's (the main thread wrote
     * and sometimes rewrote a 400 KB file on every caught error). A crash still writes at once ([writeNow]).
     */
    private val writer: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "blaze-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    fun write(line0: String) {
        if (!::file.isInitialized) return
        val line = scrubSecrets(line0)   // 0.46.6: never a saved secret in a log
        val stamped = "${synchronized(fmt) { fmt.format(Date()) }}  $line\n"
        try { writer.execute { append(stamped) } } catch (_: Throwable) { append(stamped) }
    }

    /** Synchronous write, for the crash handler (the process is about to die). */
    fun writeNow(line0: String) { if (!::file.isInitialized) return
        val line = scrubSecrets(line0)
        append("${synchronized(fmt) { fmt.format(Date()) }}  $line\n") }

    @Synchronized private fun append(stamped: String) {
        // 0.54.x (20 Nov low-memory report): the trim read the whole 400 KB log into one String (~800 KB of chars) on every
        // overflow, and caught only Exception, so on a full heap the OutOfMemoryError escaped the log writer (and the
        // crash handler's own write). Now it copies the last 200 KB as bytes and nothing thrown here leaves this method.
        try {
            if (file.length() > 400_000) trimTail(file, 200_000)
            file.appendText(if (secrets.isEmpty()) stamped else redact(stamped, secrets))
        } catch (_: Throwable) { }
    }

    /** Keep only the last [keep] bytes of [f], starting at a line, without reading the whole file into a String. */
    internal fun trimTail(f: File, keep: Int) {
        val len = f.length()
        if (len <= keep) return
        val buf = ByteArray(keep)
        java.io.RandomAccessFile(f, "r").use { r -> r.seek(len - keep); r.readFully(buf) }
        val nl = buf.indexOf('\n'.code.toByte())
        val from = if (nl in 0 until buf.size - 1) nl + 1 else 0
        f.outputStream().use { it.write(buf, from, buf.size - from) }
    }

    /** Log steps that take long so we can see where time goes. */
    fun slow(what: String, ms: Long) { if (ms >= 8000) write("SLOW ${ms} ms: $what") }
    fun error(what: String, e: Throwable?) = write("ERROR $what: ${e?.javaClass?.simpleName}: ${e?.message}")

    private val quietAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /**
     * v0.45.0 (#29): for a catch that used to be empty. The failure is still not shown to the user, but it is written
     * to the crash log (once a minute per place), so "it just didn't do anything" can be traced. Cancellation is
     * normal control flow and is never logged. Never throws.
     */
    fun quiet(where: String, e: Throwable?) {
        if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) return
        try {
            val now = System.currentTimeMillis()
            val last = quietAt[where] ?: 0L
            if (now - last < 60_000) return
            quietAt[where] = now
            write("QUIET $where: ${e?.javaClass?.simpleName}: ${e?.message?.take(300)}")
        } catch (_: Throwable) { }
    }
    /** Pure: whether [quiet] would log [e] at all (unit-tested). */
    fun quietLogs(e: Throwable?): Boolean = !(e is kotlinx.coroutines.CancellationException || e is InterruptedException)

    /** The log as the user / model sees it: scrubbed again on the way out (lines written before a secret was saved). */
    fun read(): String = try { if (::file.isInitialized && file.exists()) reportText(file.readText()) else "" } catch (_: Throwable) { "" }

    /** Pure (0.46.6): what leaves the log (Send crash report, share, the crash_log tool): saved secrets as handles (user sources were redacted on write). */
    fun reportText(text: String): String = scrubSecrets(text)
    fun crashes(): Int = read().lines().count { it.contains("CRASH") }
    fun anrs(): Int = read().lines().count { it.contains("  ANR ") }
    fun clear() { try { file.delete() } catch (_: Exception) { } }

    fun shareIntent(ctx: Context): Intent? {
        if (!::file.isInitialized || !file.exists()) return null
        val out = File(ctx.cacheDir, "blaze-report.txt")
        val pi = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) { null }
        out.writeText(scrubSecrets("${BrandName.NAME} ${pi?.versionName} (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})\n\n" + read().takeLast(150_000)))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "${BrandName.NAME} report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
