package com.blaze.assistant.ui.argus.icons

/**
 * ARGUS's own line icons, drawn here (not generated, not from Lucide) on Lucide's 24-unit grid and with its stroke, so
 * they sit beside Lucide icons. [Lucide.icon] looks a name up here first.
 */
object ArgusGlyphs {
    /** Owner 2026-11-14: the chat-history button is a tapered menu: three lines, the top longest, then shorter, then shortest. */
    const val MENU_TAPER = "menu-taper"

    val PATHS: Map<String, List<String>> = mapOf(
        MENU_TAPER to listOf("M4 6h16", "M4 12h11", "M4 18h6"),
    )
}
