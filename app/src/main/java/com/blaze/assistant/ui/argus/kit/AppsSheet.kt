package com.blaze.assistant.ui.argus.kit

import com.blaze.assistant.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.ui.Views
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.icons.Lucide

/**
 * Owner 2026-11-17: Home's "Apps" button opens ARGUS's own apps as a launcher grid (it used to open Spotlight search,
 * which made no sense). Order: media first (Music, Video), then the web, then making things, then the rest. Each tile
 * opens its view through [Views], the same path open_view and Spotlight use. Pure list here; the sheet draws it.
 */
object ArgusApps {
    data class App(val view: String, val icon: String, val label: String)

    const val COLUMNS = 4

    /** View id → its icon, in launcher order. Labels come from [Views] so renames follow. */
    private val ORDER = listOf(
        "music" to "music",
        "video" to "clapperboard",
        "browser" to "globe",
        "phonebrowser" to "smartphone",
        "gallery" to "image",
        "design" to "palette",
        "projects" to "folder",
        "terminal" to "square-terminal",
        "learn" to "graduation-cap",
        "seller" to "shopping-bag",
        "schedule" to "calendar-clock",
        "apps" to "blocks",
    )

    val all: List<App> get() = ORDER.mapNotNull { (id, icon) -> Views.find(id)?.let { App(it.id, icon, it.name) } }

    fun rows(apps: List<App> = all): List<List<App>> = apps.chunked(COLUMNS)
}

/** The Apps launcher: a sheet with a 4-wide grid of rounded icon tiles, each named underneath. Tap opens; Back closes. */
@Composable
fun AppsSheet(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Argus.colors
    ArgusSheet(onDismiss = onDismiss, title = tr("Apps"), scroll = true) {
        for (row in ArgusApps.rows()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (app in row) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onOpen(app.view) }
                            .semantics(mergeDescendants = true) { contentDescription = app.label }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(c.soft), contentAlignment = Alignment.Center) {
                            Icon(Lucide.icon(app.icon, 26), null, Modifier.size(26.dp), tint = c.ink)
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(app.label, style = ArgusType.meta, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp))
                    }
                }
                // keep the last row's tiles in their columns
                repeat(ArgusApps.COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}
