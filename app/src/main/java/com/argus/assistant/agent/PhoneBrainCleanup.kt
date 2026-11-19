package com.argus.assistant.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owner 16 Nov 2026: the on-device brains were removed: the backup brain (an OpenCode server inside Alpine, ~218 MB) and
 * the older Offline Brain (a ~0.5 GB MediaPipe model in filesDir/offline-brain). Nothing installs, starts or asks them
 * any more. This runs once after the update, in the background, and frees what they left: their folders, partial
 * downloads in filesDir/linux, the backup brain's own prefs file and their saved settings ([LEGACY_PREFS]). The Linux
 * itself stays. Pure parts: [leftovers], [LEGACY_PREFS] (PhoneBrainCleanupTest).
 */
object PhoneBrainCleanup {
    const val PREF_DONE = "phone_brain_removed_v1"
    /** The backup brain kept its server password in a prefs file of its own. */
    const val PREFS_FILE = "backup_brain"

    /** Every pref key the two brains wrote. */
    val LEGACY_PREFS = listOf("backup_brain", "backup_brain_mobile_data", "backup_mobile_asked", "backup_ttft_ms", "backup_models", "backup_models_at",
        "offline_brain")

    /** What to delete, given filesDir, the Linux base folder (filesDir/linux) and its Alpine root. */
    fun leftovers(filesDir: File, base: File, rootfs: File): List<File> =
        listOf(File(rootfs, "opt/blaze-backup"), File(rootfs, "root/.blaze-backup"), File(filesDir, "offline-brain")) +
            (base.listFiles()?.filter { it.isFile && it.name.startsWith(".dl-backup-") } ?: emptyList())

    suspend fun runOnce(ctx: Context) = withContext(Dispatchers.IO) {
        if (Settings.bool(PREF_DONE, false)) return@withContext
        try {
            com.argus.assistant.terminal.Linux.init(ctx)
            leftovers(ctx.filesDir, com.argus.assistant.terminal.Linux.base(), com.argus.assistant.terminal.Linux.rootfs()).forEach { it.deleteRecursively() }
        } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("PhoneBrainCleanup", e) }
        try { ctx.deleteSharedPreferences(PREFS_FILE) } catch (_: Throwable) { }
        try { Settings.removeKeys(LEGACY_PREFS) } catch (_: Throwable) { }
        Settings.setBool(PREF_DONE, true)
    }
}
