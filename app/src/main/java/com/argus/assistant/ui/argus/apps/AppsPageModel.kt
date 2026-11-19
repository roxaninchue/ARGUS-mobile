package com.argus.assistant.ui.argus.apps

import com.argus.assistant.ui.ArgusView
import com.argus.assistant.ui.Views

/**
 * Owner 2026-11-18 (overhaul.md "NEXT UP" 3): Life's Apps button opens a full Apps page. Its tiles are ARGUS's own apps
 * (the views marked `app` in [Views]: Music, Video, Browser…; never Settings or Chat) and the sites installed as apps
 * from the browser. Pure rules here; the page draws them.
 */
object AppsPageModel {
    const val COLUMNS = 4

    enum class Kind { VIEW, SITE }

    /** One tile: a view (opened through Views) or an installed site app (opened as its own window). */
    data class Tile(val kind: Kind, val id: String, val label: String, val icon: String = "", val keywords: String = "")

    fun viewTiles(views: List<ArgusView> = Views.apps): List<Tile> =
        views.filter { it.app }.map { Tile(Kind.VIEW, it.id, it.name, it.icon.ifBlank { "square" }, (listOf(it.id, it.desc) + it.aliases).joinToString(" ")) }

    /** Installed site apps as (id, name, start url), oldest install first, as the browser lists them. */
    fun siteTiles(sites: List<Triple<String, String, String>>): List<Tile> =
        sites.map { (id, name, url) -> Tile(Kind.SITE, id, name, keywords = url) }

    /** Every word of [q] must appear in the label or keywords (case-insensitive); a blank query keeps everything. */
    fun filter(tiles: List<Tile>, q: String): List<Tile> {
        val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return tiles
        return tiles.filter { t -> val hay = (t.label + " " + t.keywords).lowercase(); words.all { it in hay } }
    }

    fun rows(tiles: List<Tile>): List<List<Tile>> = tiles.chunked(COLUMNS)
}

