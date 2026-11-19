package com.argus.assistant.terminal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/**
 * 0.53.4 All files access. On Android 11+ scoped storage hides every file in shared storage that another app made
 * (Downloads, Documents, other apps' folders, audio without READ_MEDIA_AUDIO) from find / ls / file_search, so a
 * search "succeeds" with empty folders. The app-settings permission screen has no switch for this: it is the
 * special "All files access" page. When a command or file tool touches shared storage and the access is off,
 * the result starts with [NOTE] so the model says so and FixCards shows the right one-tap card.
 * The matching is pure (StorageAccessTest); [granted] needs a Context.
 */
object StorageAccess {
    /** Starts the tool result, so FixCards.kindOf (first 600 chars) always sees it. */
    val NOTE = "[All files access is off: Android hides other apps' files in shared storage, so this result may be " +
        "empty or incomplete. ${com.argus.assistant.BrandName.NAME} needs all files access to search the whole phone.]\n"

    private val shared = Regex("(?i)(/storage/(emulated|self)|/sdcard\\b|/mnt/sdcard|/storage\\b|\\bfind\\s+/(\\s|$)|\\bls\\s+(-\\w+\\s+)*/(\\s|$)|\\bdu\\s+(-\\w+\\s+)*/(\\s|$))")

    /** Does this command or path reach shared storage? */
    fun touchesShared(cmdOrPath: String): Boolean = shared.containsMatchIn(cmdOrPath)

    /** The result the model sees: unchanged when access is on or the command stays out of shared storage. */
    fun annotate(cmdOrPath: String, result: String, hasAccess: Boolean): String =
        if (hasAccess || !touchesShared(cmdOrPath) || result.startsWith(NOTE)) result else NOTE + result

    fun granted(ctx: Context?): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ctx != null && ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { true }   // can't tell: never nag
}
