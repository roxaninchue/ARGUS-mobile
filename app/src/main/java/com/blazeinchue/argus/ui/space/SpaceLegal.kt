package com.blazeinchue.argus.ui.space

import android.content.Context
import com.blazeinchue.argus.BrandName

/*
 * 0.52.3: Settings › About › Licences reads these (ui/SetupColumns.kt LicencesSection). The files are copies of the repo
 * root's LICENSE.md and THIRD_PARTY_NOTICES.md (LegalAssetsTest keeps them in sync).
 */

/** The licence files shipped in assets/legal (copied from the repo root's LICENSE.md and THIRD_PARTY_NOTICES.md). */
object LegalAssets {
    const val DIR = "legal"
    val FILES = listOf("LICENSE.md" to "${BrandName.UPPER} licence", "THIRD_PARTY_NOTICES.md" to "Third-party notices")

    fun read(ctx: Context, file: String): String = try {
        ctx.assets.open("$DIR/$file").bufferedReader().use { it.readText() }
    } catch (t: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("LegalAssets.read", t); "Couldn't open $file." }

    /** Markdown headings, emphasis and fences out, so the text reads plainly in a Text. */
    fun plain(md: String): String = md.lines().filterNot { it.trim().startsWith("```") }
        .joinToString("\n") { it.replace(Regex("^#{1,6}\\s+"), "").replace("**", "").replace("`", "") }.trim()
}
