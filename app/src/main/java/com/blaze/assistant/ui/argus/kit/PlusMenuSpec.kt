package com.blaze.assistant.ui.argus.kit

/**
 * Owner 2026-11-15 (Finn: the + sheet "looks ugly and not a part of the whole page because it sits on top of the chatbox
 * icons disconnected"): the + menu is no longer a bottom sheet. It grows out of the chat box itself: the composer's own
 * glass pill expands upward (same surface, same [ShellSpec.Composer.CORNER] radius) and the + turns into × ([PLUS_TURN]).
 * The model chip on the composer's top row folds away while it is open, and so does the Chat | Living switch, so nothing
 * sits over the composer's own controls. Back, a tap outside (on the thread) or a pick closes it.
 *
 * The pure rules, so PlusMenuSpecTest checks them on the JVM:
 *  - every tile fits the width: [columns] picks the most tiles a row can hold at their measured label width, and a
 *    narrow phone (or a big font) wraps them onto a second row instead of scrolling sideways or cutting one off;
 *  - labels never ellipsize: tiles use the short labels ([label]; "Argus Camera" is "Scan") and draw them unclipped;
 *  - the menu never takes more than [maxHeightDp] (half the screen, landscape included); its rows scroll inside it;
 *  - icons are Lucide ([icon]).
 */
object PlusMenuSpec {
    const val TILE_HEIGHT = 74
    /** The smallest tile (dp): 5 tiles fit a 360 dp phone's composer (320 dp inside) with [GAP] between them. */
    const val TILE_MIN = 56
    /** Space either side of a tile's label (dp). */
    const val TILE_LABEL_PAD = 4
    const val TILE_ICON = 22
    const val GAP = 6
    const val ROW_MIN = 52
    const val ROW_ICON = 20
    /** The menu's own inset inside the composer's glass (dp), on top of the composer's 4 dp. */
    const val PAD = 4
    /** The + rotates by this much to read as × while the menu is open. */
    const val PLUS_TURN = 45f
    const val MAX_FRACTION = 0.5f
    const val MAX_HEIGHT = 440
    const val MIN_HEIGHT = 150
    /** Tile labels stay this short (in English), so they never need an ellipsis. */
    const val LABEL_MAX_CHARS = 7

    const val TAG = "composer_plus_menu"
    const val SCRIM_TAG = "composer_plus_scrim"
    fun tileTag(id: String) = "plus_tile_$id"
    fun rowTag(id: String) = "plus_row_$id"

    /** The width (dp) inside the menu for a composer on a screen [screenWidthDp] wide (capped like the landscape float). */
    fun innerWidthDp(screenWidthDp: Int, capDp: Int = Int.MAX_VALUE): Int =
        minOf(screenWidthDp - 2 * ShellSpec.Composer.SIDE, capDp) - 2 * 4 - 2 * PAD

    /** How wide a tile must be for a label [labelDp] wide. */
    fun minTile(labelDp: Float): Int = maxOf(TILE_MIN, kotlin.math.ceil(labelDp + 2 * TILE_LABEL_PAD).toInt())

    /** The most tiles one row holds at [minTileDp] each in [widthDp] (at least 1, at most [tiles]). */
    fun columns(widthDp: Float, tiles: Int, minTileDp: Int = TILE_MIN): Int {
        if (tiles <= 0) return 1
        var c = tiles
        while (c > 1 && c * minTileDp + (c - 1) * GAP > widthDp) c--
        // even rows: 5 tiles that don't fit one row go 3 + 2, not 4 + 1
        val rows = (tiles + c - 1) / c
        return (tiles + rows - 1) / rows
    }

    /** Each tile's width (dp) with [cols] per row in [widthDp]. */
    fun tileWidth(widthDp: Float, cols: Int): Float = (widthDp - (cols - 1) * GAP) / cols

    /** The menu's height cap for a screen [screenHeightDp] tall. */
    fun maxHeightDp(screenHeightDp: Int): Int = (screenHeightDp * MAX_FRACTION).toInt().coerceIn(MIN_HEIGHT, MAX_HEIGHT)

    /** The short label a tile shows (rows keep their full label and sub line). */
    fun label(id: String, label: String): String = SHORT[id] ?: label
    private val SHORT = mapOf("blazecam" to "Scan", "camera" to "Camera", "photos" to "Photos", "files" to "Files", "call" to "Call")

    /** Rows that open a page get a chevron; the rest act in place. */
    fun opensPage(id: String): Boolean = id == "apps" || id == "skills"

    /** The Lucide icon for a + entry ([com.blaze.assistant.ui.plusItems] ids). */
    fun icon(id: String, on: Boolean = false): String = when (id) {
        "camera" -> "camera"
        "blazecam" -> "scan-text"
        "photos" -> "image"
        "files" -> "paperclip"
        "call" -> "phone"
        "paste" -> "clipboard-paste"
        "apps" -> "plug"
        "skills" -> "sparkles"
        "atfile" -> "at-sign"
        "plan" -> if (on) "lightbulb" else "pencil"
        "new" -> "square-pen"
        else -> "plus"
    }
}
