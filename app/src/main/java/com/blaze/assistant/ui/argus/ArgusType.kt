package com.blaze.assistant.ui.argus

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.blaze.assistant.R

/**
 * P0-SH-01: 04 §2.2 type as Compose text styles, from [ArgusTypeScale]. Inter and Inter Tight are the fonts AM already
 * bundles (OFL). Instrument Serif and JetBrains Mono are not bundled yet (a new third-party font is P0-IL-02's notice
 * work), so SERIF and MONO use the system serif / monospace until then. Weights between the bundled ones (620, 650,
 * 750) resolve to the nearest bundled face.
 */
object ArgusType {
    private val Inter = FontFamily(
        Font(R.font.inter_300, FontWeight.Light),
        Font(R.font.inter_400, FontWeight.Normal),
        Font(R.font.inter_500, FontWeight.Medium),
        Font(R.font.inter_600, FontWeight.SemiBold),
        Font(R.font.inter_700, FontWeight.Bold),
    )
    private val InterTight = FontFamily(
        Font(R.font.inter_tight_400, FontWeight.Normal),
        Font(R.font.inter_tight_500, FontWeight.Medium),
        Font(R.font.inter_tight_600, FontWeight.SemiBold),
        Font(R.font.inter_700, FontWeight.Bold),
    )

    fun family(f: ArgusFont): FontFamily = when (f) {
        ArgusFont.SANS -> Inter
        ArgusFont.DISPLAY -> InterTight
        ArgusFont.SERIF -> FontFamily.Serif
        ArgusFont.MONO -> FontFamily.Monospace
    }

    fun style(t: TypeSpec): TextStyle = TextStyle(
        fontFamily = family(t.family),
        fontSize = t.size.sp,
        lineHeight = t.line.sp,
        fontWeight = FontWeight(t.weight),
        letterSpacing = t.tracking.sp,
        fontFeatureSettings = if (t.tabular) "tnum" else null,
    )

    val display get() = style(ArgusTypeScale.display)
    val serifHello get() = style(ArgusTypeScale.serifHello)
    val title1 get() = style(ArgusTypeScale.title1)
    val title2 get() = style(ArgusTypeScale.title2)
    val sheetTitle get() = style(ArgusTypeScale.sheetTitle)
    val topTitle get() = style(ArgusTypeScale.topTitle)
    val rowTitle get() = style(ArgusTypeScale.rowTitle)
    val body get() = style(ArgusTypeScale.body)
    val field get() = style(ArgusTypeScale.field)
    val bodyStrong get() = style(ArgusTypeScale.bodyStrong)
    val sub get() = style(ArgusTypeScale.sub)
    val preview get() = style(ArgusTypeScale.preview)
    val tool get() = style(ArgusTypeScale.tool)
    val meta get() = style(ArgusTypeScale.meta)
    /** Uppercase it at the call site (`text.uppercase()`); TextStyle has no case transform. */
    val section get() = style(ArgusTypeScale.section)
    val chip get() = style(ArgusTypeScale.chip)
    val statusChip get() = style(ArgusTypeScale.statusChip)
    val pill get() = style(ArgusTypeScale.pill)
    val mono get() = style(ArgusTypeScale.mono)
}
