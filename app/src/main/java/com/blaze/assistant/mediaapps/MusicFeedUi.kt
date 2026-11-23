package com.blaze.assistant.mediaapps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.ui.space.spaceLook

/*
 * 0.55.11: songs from YouTube shown like a music app: shelves of square covers with the song title and the artist
 * under them, and rows of cover + title + "artist · 3:45". No source names, no video words, no view counts.
 */

/** What a song can do; made once in MusicApp (it holds the player). */
class SongActions(
    val play: (List<StoreItem>, Int, String) -> Unit,
    val playNext: (StoreItem) -> Unit,
    val download: (StoreItem) -> Unit,
) {
    fun menu(i: StoreItem): List<Pair<String, () -> Unit>> = listOf("Play next" to { playNext(i) }, "Download" to { download(i) })
}

private val coverShape = RoundedCornerShape(8.dp)
private val rowShape = RoundedCornerShape(6.dp)

/** A song as a [Track] for the shared cover view (square crop of the picture). */
fun songTrack(i: StoreItem) = Track(MusicOnline.key(i), "", i.title, i.author, i.extra["album"].orEmpty(), i.durationSec * 1000L, i.thumb, "stream", videoId = i.id)

@Composable
fun SongCard(i: StoreItem, width: Int = 148, onClick: () -> Unit) {
    val look = spaceLook()
    Column(Modifier.width(width.dp).clip(coverShape).clickable(role = Role.Button, onClickLabel = "Play ${i.title}") { onClick() }) {
        Art(songTrack(i), Modifier.size(width.dp), coverShape, target = 360)
        Text(i.title, color = look.ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp))
        if (i.author.isNotBlank()) Text(i.author, color = look.sub, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A titled row of song cards; a tap plays the shelf from that song. */
@Composable
fun SongShelf(title: String, items: List<StoreItem>, actions: SongActions) {
    if (items.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(items, key = { _, s -> "c" + s.id }) { n, s -> SongCard(s) { actions.play(items, n, title) } }
    }
}

/** A shelf still loading: its title (when known) and three soft placeholders. */
@Composable
fun ShelfSkeleton(title: String = "") {
    if (title.isNotBlank()) SectionTitle(title) else Spacer(Modifier.height(22.dp))
    Row(Modifier.padding(horizontal = 14.dp).semantics { contentDescription = "Loading songs" }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { Box(Modifier.width(148.dp)) { SkeletonTile(true) } }
    }
}

/** One song row: cover, title, "artist · 3:45" and a ⋮ menu (Play next, Download). */
@Composable
fun SongRow(i: StoreItem, playing: Boolean = false, menu: List<Pair<String, () -> Unit>> = emptyList(), onClick: () -> Unit) {
    val look = spaceLook()
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Play ${i.title}") { onClick() }
        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(songTrack(i), Modifier.size(52.dp), rowShape, target = 160)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(i.title, color = if (playing) look.accent else look.ink, fontSize = 15.sp, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(i.author, i.durationText).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
            if (sub.isNotBlank()) Text(sub, color = look.sub, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (menu.isNotEmpty()) Box {
            RoundIcon(Icons.Rounded.MoreVert, "More for ${i.title}", tint = look.sub) { open = true }
            com.blaze.assistant.ui.BlazeMenu(open, { open = false }, menu.map { (l, f) -> l to { open = false; f() } })
        }
    }
}

@Composable
fun SongRowSkeleton() {
    val look = spaceLook()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { contentDescription = "Loading" }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(rowShape).background(look.track))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(50)).background(look.track))
            Spacer(Modifier.height(7.dp))
            Box(Modifier.fillMaxWidth(0.35f).height(10.dp).clip(RoundedCornerShape(50)).background(look.track))
        }
    }
}

/** Offline with nothing saved yet: says so plainly, with a retry. */
@Composable
fun OfflineCard(onRetry: () -> Unit) {
    val look = spaceLook()
    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(look.track)
        .clickable(role = Role.Button, onClickLabel = "Try again") { onRetry() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = look.sub, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("You're offline", color = look.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Songs show up here when you're connected. Tap to try again.", color = look.sub, fontSize = 12.5.sp)
        }
    }
}

/**
 * The song shelves of [MusicFeed] (Home, empty Library): the saved ones at once, skeletons while the first load runs,
 * and an offline card only when there is nothing saved and no network.
 */
fun LazyListScope.songShelves(shelves: List<MusicFeedPlan.Shelf>, loading: Boolean, offline: Boolean, actions: SongActions,
                              onRetry: () -> Unit, max: Int = Int.MAX_VALUE) {
    val shown = shelves.filter { it.items.isNotEmpty() }.take(max)
    shown.forEach { s -> item(key = "shelf-" + s.id) { SongShelf(s.title, s.items, actions) } }
    if (shown.isEmpty()) {
        if (offline && !loading) item(key = "shelf-offline") { OfflineCard(onRetry) }
        else items(minOf(3, max)) { n -> ShelfSkeleton(listOf("Trending now", "New releases", "For you")[n % 3]) }
    } else if (loading && shown.size < minOf(3, max)) item(key = "shelf-more") { ShelfSkeleton() }
}
