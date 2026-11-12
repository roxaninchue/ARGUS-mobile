package com.blaze.assistant.ui.argus.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blaze.assistant.BrandName
import com.blaze.assistant.team.ATeamImport
import com.blaze.assistant.team.ATeamImporter
import com.blaze.assistant.team.ImportPrefs
import com.blaze.assistant.team.JobStore
import com.blaze.assistant.team.TeamGraph
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ArgusField
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.SettingsGroup
import com.blaze.assistant.ui.argus.kit.SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object BackupTags {
    const val SECTION = "backup-ateam"
    const val PICK = "backup-ateam-pick"
    const val PASTE = "backup-ateam-paste"
    const val FIELD = "backup-ateam-field"
    const val READ = "backup-ateam-read"
    const val THEME = "backup-ateam-theme"
    const val IMPORT = "backup-ateam-import"
    const val CANCEL = "backup-ateam-cancel"
    const val LINES = "backup-ateam-lines"
}

/** P1-MG-01: where the A-team import is on the Backup page. Pure state; the logic is [ATeamImport] / [ATeamImporter]. */
sealed class ATeamStage {
    data object Idle : ATeamStage()
    data object Working : ATeamStage()
    /** Read and checked: what Import would do. */
    data class Preview(val file: ATeamImport.File, val report: ATeamImport.Report) : ATeamStage()
    /** The file broke the rules (docs/overhaul/ateam-export.md §3): nothing is imported. */
    data class Bad(val problems: List<String>) : ATeamStage()
    data class Done(val report: ATeamImport.Report) : ATeamStage()
    data class Failed(val why: String) : ATeamStage()
}

/**
 * P1-MG-01 the "Import A-team backup" section of Settings › Backup & import (ARGUS look). Stateless: pick a file (SAF,
 * in [ATeamBackupLive]) or paste the text A-team's Copy backup put on the clipboard; the preview lists what will come
 * over (and what won't yet), an optional "Use A-team's theme" switch, then Import / Cancel on the thumb's side.
 */
@Composable
fun ATeamBackupSection(
    stage: ATeamStage,
    onPick: () -> Unit,
    paste: String?,
    onPaste: (String?) -> Unit,
    onRead: (String) -> Unit,
    useTheme: Boolean,
    onUseTheme: (Boolean) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    hand: ArgusSettings.Hand = ArgusSettings.Hand.RIGHT,
) {
    val c = Argus.colors
    Column(modifier.fillMaxWidth().testTag(BackupTags.SECTION), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("A-TEAM", style = ArgusType.section, color = c.meta, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        Text("Bring your A-team bots and jobs into ${BrandName.NAME}: in A-team, Settings \u203a Data \u203a Copy backup (or Export for ${BrandName.UPPER}), " +
            "save or copy it, then import it here. Importing the same backup again changes nothing.", style = ArgusType.sub, color = c.mute)
        when (stage) {
            is ATeamStage.Preview -> {
                Lines(ATeamImport.lines(stage.report.copy(theme = stage.report.theme?.takeIf { useTheme })))
                if (stage.file.theme != null) SettingsGroup(listOf(SettingsItem("palette",
                    "Use A-team's theme (${ATeamImport.themeLabel(stage.file.theme)})", if (useTheme) "On" else "Off", chevron = false,
                    onClick = { onUseTheme(!useTheme) })), Modifier.testTag(BackupTags.THEME))
                val primary: @Composable () -> Unit = {
                    ArgusButton(if (stage.report.changes == 0) "Done" else "Import", if (stage.report.changes == 0) onCancel else onImport,
                        Modifier.testTag(BackupTags.IMPORT), kind = ButtonKind.PRIMARY, icon = "download")
                }
                val secondary: @Composable () -> Unit = { ArgusButton("Cancel", onCancel, Modifier.testTag(BackupTags.CANCEL)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ArgusSettings.buttonOrder(primary, secondary, hand).forEach { it() } }
            }
            is ATeamStage.Done -> {
                Lines(ATeamImport.lines(stage.report))
                ArgusButton("Import another", onCancel, Modifier.testTag(BackupTags.CANCEL))
            }
            is ATeamStage.Bad -> {
                Text("That backup can't be imported, so nothing was changed:", style = ArgusType.body, color = c.dangerText)
                Lines(stage.problems.take(8) + listOfNotNull((stage.problems.size - 8).takeIf { it > 0 }?.let { "and $it more" }))
                ArgusButton("Try another file", onCancel, Modifier.testTag(BackupTags.CANCEL))
            }
            is ATeamStage.Failed -> {
                Text(stage.why, style = ArgusType.body, color = c.dangerText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                ArgusButton("Try again", onCancel, Modifier.testTag(BackupTags.CANCEL))
            }
            ATeamStage.Working -> Text("Reading\u2026", style = ArgusType.sub, color = c.mute, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            ATeamStage.Idle -> if (paste == null) {
                SettingsGroup(listOf(
                    SettingsItem("download", "Import A-team backup", "File", onClick = onPick),
                    SettingsItem("clipboard-paste", "Paste a backup instead", chevron = false, onClick = { onPaste("") }),
                ), Modifier.testTag(BackupTags.PICK))
            } else {
                ArgusField(paste, { onPaste(it) }, "A-team backup", Modifier.fillMaxWidth().testTag(BackupTags.FIELD),
                    placeholder = "Paste the backup text here", singleLine = false)
                val read: @Composable () -> Unit = {
                    ArgusButton("Read", { onRead(paste) }, Modifier.testTag(BackupTags.READ), kind = ButtonKind.PRIMARY, enabled = paste.isNotBlank())
                }
                val back: @Composable () -> Unit = { ArgusButton("Cancel", { onPaste(null) }, Modifier.testTag(BackupTags.PASTE)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ArgusSettings.buttonOrder(read, back, hand).forEach { it() } }
            }
        }
    }
}

@Composable
private fun Lines(lines: List<String>) {
    val c = Argus.colors
    Column(Modifier.fillMaxWidth().testTag(BackupTags.LINES).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Text("\u2022 $it", style = ArgusType.body, color = c.ink) }
    }
}

/** The settings an import may set, on the app's own prefs. */
private object LivePrefs : ImportPrefs {
    override var userName: String
        get() = try { com.blaze.assistant.agent.Settings.userName } catch (_: Throwable) { "" }
        set(v) { try { com.blaze.assistant.agent.Settings.userName = v } catch (_: Throwable) { } }
    override var themeMode: String
        get() = com.blaze.assistant.ui.ThemeMode.mode.value
        set(v) { com.blaze.assistant.ui.ThemeMode.set(v) }
}

/** Biggest file the picker reads (a Demo-team backup is ~6 KB; one with long chats a few hundred KB). */
private const val MAX_BYTES = 5 * 1024 * 1024

/** The stream as UTF-8 text, or null when it is over [MAX_BYTES] (InputStream.readNBytes needs API 33). */
private fun readCapped(s: java.io.InputStream): String? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = s.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > MAX_BYTES) return null
    }
    return out.toString("UTF-8")
}

/** [ATeamBackupSection] wired: the SAF picker, team.db through [TeamGraph], work off the main thread. */
@Composable
fun ATeamBackupLive() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<ATeamStage>(ATeamStage.Idle) }
    var paste by remember { mutableStateOf<String?>(null) }
    var useTheme by remember { mutableStateOf(true) }
    fun importer() = TeamGraph.get(ctx).let { g -> ATeamImporter(g.sql, g.store, JobStore(g.sql), LivePrefs) }
    fun read(text: String) {
        stage = ATeamStage.Working
        scope.launch {
            stage = try {
                withContext(Dispatchers.IO) {
                    when (val r = ATeamImport.read(text)) {
                        is ATeamImport.Read.Bad -> ATeamStage.Bad(r.problems)
                        is ATeamImport.Read.Ok -> ATeamStage.Preview(r.file, importer().preview(r.file))
                    }
                }
            } catch (e: Exception) { ATeamStage.Failed("Couldn't read it: ${e.message ?: "unknown error"}") }
        }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        stage = ATeamStage.Working
        scope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { s -> readCapped(s) }
                }
            } catch (e: Exception) { stage = ATeamStage.Failed("Couldn't open that file."); return@launch }
            if (text == null) stage = ATeamStage.Failed("That file is too big for an A-team backup.") else read(text)
        }
    }
    ATeamBackupSection(
        stage = stage,
        onPick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
        paste = paste, onPaste = { paste = it },
        onRead = { read(it) },
        useTheme = useTheme, onUseTheme = { useTheme = it },
        onImport = {
            val p = stage as? ATeamStage.Preview ?: return@ATeamBackupSection
            stage = ATeamStage.Working
            scope.launch {
                stage = try {
                    val r = withContext(Dispatchers.IO) {
                        val g = TeamGraph.get(ctx)
                        ATeamImporter(g.sql, g.store, JobStore(g.sql), LivePrefs).apply(p.file, useTheme).also {
                            if (it.bots.changes > 0) p.file.bots.forEach { b -> runCatching { g.threads.rosterChanged(b.bot.id) } }
                        }
                    }
                    runCatching { com.blaze.assistant.team.JobsRuntime.kick() }
                    paste = null
                    ATeamStage.Done(r)
                } catch (e: Exception) { ATeamStage.Failed("Nothing was imported: ${e.message ?: "unknown error"}") }
            }
        },
        onCancel = { stage = ATeamStage.Idle; paste = null },
        hand = remember { ArgusPrefs.hand() },
    )
}
