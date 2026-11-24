package com.blaze.assistant.mediaapps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.YtMusic
import com.blaze.assistant.ui.space.SpaceRoundButton
import com.blaze.assistant.ui.space.spaceLook
import kotlinx.coroutines.withTimeout

/**
 * 0.55.17: one genre or mood ([MusicGenres.Tile]): its cover, a Play button, and that genre's songs from YouTube, loading
 * more as you near the end until YouTube has no more. Nothing here plays the library or asks Argus to pick songs.
 */
@Composable
internal fun MusicGenrePage(tile: MusicGenres.Tile, cover: String, actions: SongActions, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    val look = spaceLook()
    var items by remember(tile.name) { mutableStateOf<List<StoreItem>>(emptyList()) }
    var next by remember(tile.name) { mutableStateOf("") }
    var done by remember(tile.name) { mutableStateOf(false) }
    var loading by remember(tile.name) { mutableStateOf(false) }
    var failed by remember(tile.name) { mutableStateOf(false) }
    var asks by remember(tile.name) { mutableIntStateOf(0) }
    val list = rememberLazyListState()

    LaunchedEffect(tile.name, asks) {
        if (done || loading) return@LaunchedEffect
        loading = true; failed = false
        try {
            var tries = 0
            while (tries < 4) {   // a page of songs we already have: go straight to the next one
                tries++
                val p = withTimeout(20_000) { YtMusic.songsPage(tile.query, next) }
                val have = items.mapTo(HashSet()) { it.id }
                val add = MusicFeedPlan.songs(p.items).filter { it.id !in have }
                items = items + add
                next = p.next
                if (p.next.isBlank()) { done = true; break }
                if (add.isNotEmpty()) break
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { failed = true }
        loading = false
    }
    val nearEnd by remember { derivedStateOf { val li = list.layoutInfo; (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, items.size, loading) { if (nearEnd && !loading && !done && !failed && items.isNotEmpty()) asks++ }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 150.dp)) {
        item(key = "head") {
            Box(Modifier.fillMaxWidth().height(240.dp)) {
                Art(if (cover.isBlank()) null else songTrack(StoreItem("youtube", "cover:" + tile.name, tile.name, "", thumb = cover, square = true)),
                    Modifier.fillMaxSize(), RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp), 600)
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.75f)))))
                RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White, fill = Color.Black.copy(alpha = 0.3f),
                    modifier = Modifier.padding(12.dp).align(Alignment.TopStart)) { onBack() }
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(tile.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(if (tile.mood) "${tile.name} music from YouTube" else "${tile.name} songs from YouTube", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                if (items.isNotEmpty()) SpaceRoundButton("Play ${tile.name}", { actions.play(items, 0, tile.name) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = look.ink, modifier = Modifier.size(28.dp))
                }
            }
        }
        itemsIndexed(items, key = { _, s -> "g-" + s.id }) { n, s -> SongRow(s, menu = actions.menu(s)) { actions.play(items, n, tile.name) } }
        if (loading) items(if (items.isEmpty()) 8 else 3, key = { "gsk$it" }) { SongRowSkeleton() }
        if (failed) item(key = "fail") {
            Column(Modifier.padding(16.dp)) {
                Text("Couldn't load more ${tile.name} right now. Check the connection.", color = look.sub, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                com.blaze.assistant.ui.PillButton("Try again", primary = true, icon = Icons.Rounded.Refresh) { failed = false; asks++ }
            }
        }
        if (done && !loading) item(key = "end") {
            Text(if (items.isEmpty()) "YouTube has no ${tile.name} songs right now." else "That's every ${tile.name} song YouTube has for now.",
                color = look.sub, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
    }
}
