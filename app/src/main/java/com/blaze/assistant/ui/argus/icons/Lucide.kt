package com.blaze.assistant.ui.argus.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * P0-SH-02: Lucide icons as ImageVectors (04 §2.7: no icon font, no Material icons in new ARGUS UI). Built from the
 * generated [LucidePaths] on first use and cached per (name, stroke). Tint them with `Icon(…, tint = …)`: the stroke is
 * black and Icon's tint replaces it, the way `currentColor` works in A-team.
 */
object Lucide {
    private val cache = HashMap<String, ImageVector>()

    /** [name] (an alias or one of [LucideSpec.Semantic]'s names) drawn for [sizeDp]; unknown names fall back to "square". */
    fun icon(name: String, sizeDp: Int = 20): ImageVector {
        val key = LucideSpec.resolve(name)
        val stroke = LucideSpec.strokeFor(sizeDp)
        val ck = "$key@$stroke"
        return synchronized(cache) {
            cache.getOrPut(ck) {
                val b = ImageVector.Builder(name = "lucide-$key", defaultWidth = 24.dp, defaultHeight = 24.dp,
                    viewportWidth = LucidePaths.VIEWPORT, viewportHeight = LucidePaths.VIEWPORT)
                for (d in LucidePaths.ICONS[key].orEmpty()) b.addPath(pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
                b.build()
            }
        }
    }

    val Bots get() = icon(LucideSpec.Semantic.BOTS)
    val Chat get() = icon(LucideSpec.Semantic.CHAT)
    val Inbox get() = icon(LucideSpec.Semantic.INBOX)
    val New get() = icon(LucideSpec.Semantic.NEW)
    val Search get() = icon(LucideSpec.Semantic.SEARCH)
    val Voice get() = icon(LucideSpec.Semantic.VOICE)
    val NeedsYou get() = icon(LucideSpec.Semantic.NEEDS_YOU)
    val Approvals get() = icon(LucideSpec.Semantic.APPROVALS)
}
