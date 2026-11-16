package com.blaze.assistant.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owner 16 Nov 2026: the on-device backup brain was removed (it no longer installs, starts or answers). This runs once
 * after the update, in the background, and frees what it left behind: its folder inside Alpine (the OpenCode server,
 * ~218 MB), its empty working folder, partial downloads in filesDir/linux, and its saved settings ([LEGACY_PREFS]).
 * The Linux itself stays. Pure parts: [leftovers], [LEGACY_PREFS] (PhoneBrainCleanupTest).
 */
object PhoneBrainCleanup {
    const val PREF_DONE = "phone_brain_removed_v1"

    /** Every pref key the backup brain wrote. */
    val LEGACY_PREFS = listOf("backup_brain", "backup_brain_mobile_data", "backup_mobile_asked", "backup_ttft_ms", "backup_models", "backup_models_at", "backup_pw")

    /** What to delete, given the Linux base folder (filesDir/linux) and its Alpine root. */
    fun leftovers(base: File, rootfs: File): List<File> =
        listOf(File(rootfs, "opt/blaze-backup"), File(rootfs, "root/.blaze-backup")) +
            (base.listFiles()?.filter { it.isFile && it.name.startsWith(".dl-backup-") } ?: emptyList())

    suspend fun runOnce(ctx: Context) = withContext(Dispatchers.IO) {
        if (Settings.bool(PREF_DONE, false)) return@withContext
        try {
            com.blaze.assistant.terminal.Linux.init(ctx)
            leftovers(com.blaze.assistant.terminal.Linux.base(), com.blaze.assistant.terminal.Linux.rootfs()).forEach { it.deleteRecursively() }
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("PhoneBrainCleanup", e) }
        try { Settings.removeKeys(LEGACY_PREFS) } catch (_: Throwable) { }
        Settings.setBool(PREF_DONE, true)
    }
}
