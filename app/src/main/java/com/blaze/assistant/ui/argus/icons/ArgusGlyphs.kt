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
        // 2026-11-17 Apps launcher: Video, Projects and Seller tiles (Lucide's shapes, which the generated set lacks).
        "clapperboard" to listOf("M20.2 6 3 11l-.9-2.4c-.3-1.1.3-2.2 1.3-2.5l13.5-4c1.1-.3 2.2.3 2.5 1.3Z", "m6.2 5.3 3.1 3.9", "m12.4 3.4 3.1 4",
            "M3 11h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"),
        "folder" to listOf("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"),
        // 0.55.7 model picker: the Vision filter / capability icon and the Recent header (Lucide's shapes).
        "eye" to listOf("M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z"),
        "history" to listOf("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2"),
        "shopping-bag" to listOf("M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4Z", "M3 6h18", "M16 10a4 4 0 0 1-8 0"),
    )
}
