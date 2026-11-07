package com.blazeinchue.argus.home

import android.content.Context
import com.blazeinchue.argus.agent.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 0.45.29: ArgusOS (Argus's own virtual phone, "Argus Phone" to its users: the Home screen with Calendar / Ask Argus /
 * music widgets, its app grid, dock, App Store and web / WebAssembly apps, added in v0.33.2) was removed. Home is the
 * Home board again for everyone; nothing in Argus opens ArgusOS any more. ArgusOS was an activity inside Argus, never
 * a system launcher (it had no HOME intent filter), so no phone is left without a home screen.
 *
 * This runs once after the update, in the background, and frees what ArgusOS left behind:
 *  - its saved settings ([LEGACY_PREFS]: the Classic-board switch, wallpaper, icon grid, dock, widgets, last app…);
 *  - its sandboxed disk (filesDir/[DISK]: installed ArgusOS apps, their data, its calendar and wallpaper photo);
 *  - its own WebView profile ([PROFILE]: the ArgusOS Browser's cookies and storage), when the phone's WebView has profiles.
 * Argus's own browser, the Argus Phone browser engine (browser/phone), site apps and the Argus Store are not touched.
 * Pure part: [LEGACY_PREFS] (ArgusOsRemovedTest).
 */
object ArgusOsCleanup {
    const val PREF_DONE = "argusos_removed_v1"
    const val DISK = "argusos"
    const val PROFILE = "argusos"

    /** Every pref key ArgusOS wrote (OsPrefs.K_*). */
    val LEGACY_PREFS = listOf(
        "bos_v", "bos_classic", "bos_wallpaper", "bos_icon_shape", "bos_icon_tint", "bos_grid", "bos_labels", "bos_dock_on",
        "bos_dock", "bos_glass", "bos_accent", "bos_theme", "bos_font", "bos_order", "bos_removed", "bos_widgets",
        "bos_volume", "bos_brightness", "bos_last_app",
    )

    /** Runs the one-time cleanup. Safe to call on every start: it returns at once when it is done. */
    suspend fun runOnce(ctx: Context) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        try {
            if (Settings.bool(PREF_DONE, false)) return@withContext
            Settings.removeKeys(LEGACY_PREFS)
            File(app.filesDir, DISK).takeIf { it.exists() }?.let { d ->
                // Delete without following links: ArgusOS's disk only ever held plain files, but never leave it by accident.
                d.walkBottomUp().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { f -> runCatching { java.nio.file.Files.deleteIfExists(f.toPath()) } }
            }
            withContext(Dispatchers.Main) {
                try {
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
                        val store = androidx.webkit.ProfileStore.getInstance()
                        if (PROFILE in store.allProfileNames) store.deleteProfile(PROFILE)
                    }
                } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusOsCleanup: profile", e) }
            }
            Settings.setBool(PREF_DONE, true)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            com.blazeinchue.argus.device.CrashLog.quiet("ArgusOsCleanup", e)
        }
    }
}
