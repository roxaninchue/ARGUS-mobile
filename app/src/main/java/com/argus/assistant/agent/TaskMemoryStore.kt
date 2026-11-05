package com.argus.assistant.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.52.1: where [TaskMemory] records live — filesDir/task_records.json, read once on a background thread, written on
 * a background thread at most every [SAVE_DEBOUNCE_MS] (never on the main thread). Thread-safe (a copy-on-write list).
 */
object TaskMemoryStore {
    private const val FILE = "task_records.json"
    private const val SAVE_DEBOUNCE_MS = 800L
    private val recs = CopyOnWriteArrayList<TaskMemory.Rec>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.argus.assistant.device.CrashLog.guard)
    @Volatile private var file: File? = null
    @Volatile private var saveJob: Job? = null
    @Volatile var loaded = false; private set

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, FILE); file = f
        scope.launch {
            val list = try { if (f.exists()) TaskMemory.decode(f.readText()) else emptyList() } catch (_: Throwable) { emptyList() }
            // Blaze closed mid-task: an ACTIVE task is now STOPPED (it can be continued), never silently "running".
            val fixed = list.map { if (it.state == TaskMemory.State.ACTIVE) it.copy(state = TaskMemory.State.STOPPED) else it }
            val newer = recs.toList()
            recs.clear(); recs.addAll((fixed + newer).distinctBy { it.id }.takeLast(TaskMemory.KEEP))
            loaded = true
        }
    }

    /** This chat's records, oldest first. */
    fun forChat(chat: String): List<TaskMemory.Rec> = recs.filter { it.chat == chat }

    fun get(id: String): TaskMemory.Rec? = recs.firstOrNull { it.id == id }

    /** Adds or replaces [r] (by id) and schedules a save. */
    fun put(r: TaskMemory.Rec) {
        synchronized(recs) {
            val i = recs.indexOfFirst { it.id == r.id }
            if (i >= 0) recs[i] = r else { recs.add(r); while (recs.size > TaskMemory.KEEP) recs.removeAt(0) }
        }
        save()
    }

    fun update(id: String, f: (TaskMemory.Rec) -> TaskMemory.Rec) { get(id)?.let { put(f(it)) } }

    private fun save() {
        val f = file ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            try {
                val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.writeText(TaskMemory.encode(recs.toList()))
                if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
            } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("TaskMemoryStore: save", e) }
        }
    }

    /** Tests only. */
    fun clearForTest() { recs.clear() }
}
