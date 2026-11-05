package com.argus.assistant.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 0.52.0: the renderers for [RichMedia]'s blocks — a gallery (song list with artwork, or a poster grid), link cards,
 * a document card (Open / Share) and a collapsible section. Lightweight Compose only; pictures go through RichPicture
 * (cached, off the main thread). Screenshot test: RichMediaShotsTest.
 */
private val cardShape = RoundedCornerShape(16.dp)

private fun open(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
}

@Composable
private fun BlockTitle(t: String) {
    val c = LocalBlaze.current
    if (t.isNotBlank()) Text(t, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp),
        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink))
}

@Composable
fun RichGallery(g: RichMedia.Gallery) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        BlockTitle(g.title)
        if (g.grid) {
            g.items.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { it0 ->
                        Column(Modifier.weight(1f).clickable(enabled = it0.url.isNotBlank()) { open(ctx, it0.url) }) {
                            if (it0.image.isNotBlank()) RichPicture(RichReplies.Img(it0.image, it0.title), Modifier.fillMaxWidth(), ratio = 2f / 3f)
                            else Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(cardShape).background(c.faint))
                            Text(it0.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else g.items.forEachIndexed { i, it0 ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = it0.url.isNotBlank()) { open(ctx, it0.url) }
                .semantics { contentDescription = "${i + 1}. ${it0.title} ${it0.subtitle}" }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", modifier = Modifier.width(22.dp), style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, color = c.weak))
                if (it0.image.isNotBlank()) RichPicture(RichReplies.Img(it0.image, it0.title), Modifier.size(52.dp), ratio = 1f)
                else Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(c.faint), contentAlignment = Alignment.Center) {
                    Text("\u266A", style = TextStyle(fontFamily = Type.Sans, fontSize = 18.sp, color = c.weak)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(it0.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                    if (it0.subtitle.isNotBlank()) Text(it0.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                    if (it0.note.isNotBlank()) Text(it0.note, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.weak))
                }
            }
        }
    }
}

@Composable
fun RichLinks(l: RichMedia.Links) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BlockTitle(l.title)
        l.items.forEach { k ->
            Row(Modifier.fillMaxWidth().clip(cardShape).background(c.faint).clickable { open(ctx, k.url) }
                .semantics { contentDescription = "${k.title}, ${k.source}" }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(k.source, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.weak))
                    Text(k.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                    if (k.summary.isNotBlank()) Text(k.summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
                if (k.image.isNotBlank()) { Spacer(Modifier.width(10.dp)); RichPicture(RichReplies.Img(k.image, k.title), Modifier.size(64.dp), ratio = 1f) }
            }
        }
    }
}

@Composable
fun RichDoc(d: RichMedia.Doc) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    fun file(): File? = File(d.path).takeIf { it.isAbsolute }
    Row(Modifier.fillMaxWidth().clip(cardShape).background(c.faint).semantics { contentDescription = "Document ${d.title}" }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 44.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)).background(c.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text(RichMedia.badge(d.format), style = TextStyle(fontFamily = Type.Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = c.accent))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink))
            if (d.summary.isNotBlank()) Text(d.summary, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionText("Open", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
                    val f = file()
                    if (f != null && f.exists()) try { ctx.startActivity(com.argus.assistant.media.Media.viewIntent(f)) } catch (_: Exception) { }
                    else Toast.makeText(ctx, "Saved at ${d.path}", Toast.LENGTH_SHORT).show()
                })
                ActionText("Share", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
                    val f = file()
                    if (f != null && f.exists()) try { ctx.startActivity(com.argus.assistant.media.Media.shareIntent(f)) } catch (_: Exception) { }
                    else try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, d.title + "\n" + d.path), null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                })
            }
        }
    }
}

@Composable
fun RichDetails(d: RichMedia.Details, text: @Composable (String) -> Unit) {
    val c = LocalBlaze.current
    var open by remember(d.title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(cardShape).background(c.faint)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.semantics { contentDescription = (if (open) "Hide " else "Show ") + d.title }
            .padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(d.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
            val rot by animateFloatAsState(if (open) 90f else 0f, label = "det")
            Text("\u203A", modifier = Modifier.rotate(rot), style = TextStyle(fontFamily = Type.Display, fontSize = 18.sp, color = c.sub))
        }
        AnimatedVisibility(open, enter = MotionTokens.expand(), exit = MotionTokens.collapse()) {
            Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { text(d.body) }
        }
    }
}
