package com.blaze.assistant.ui.living

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.browser.BrowserEngine
import com.blaze.assistant.browser.BrowserLive
import com.blaze.assistant.i18n.tr
import kotlinx.coroutines.delay

/**
 * Living UI 0.2.4, the glass view (Finn, 15 Nov 2026: "allow me to click to get the glass panel to see what the agent is
 * doing without touching or editing the screen"). Tapping a browser card opens it over the canvas: the agent's page as it
 * is right now (BrowserEngine.glassFrame, redrawn about every [FRAME_MS] while the agent works, slower when idle), the
 * site, the page title and what the agent is doing (BrowserLive.step). It is a pane of glass: every touch on the page
 * picture is taken by the overlay, so nothing reaches the page; only Close (and Back, LivingSpec.back) do anything.
 * When the tab sleeps the newest card picture (LivingShots) stands in.
 */
object LivingGlass {
    const val FRAME_MS = 700L
    const val IDLE_MS = 2_500L
}

@Composable
fun GlassPanel(url: String, working: Boolean, p: LivingSpec.Palette, now: String = "", modifier: Modifier = Modifier) {
    val step by BrowserLive.step.collectAsState()
    val shots by LivingShots.shots.collectAsState()
    val tab = BrowserEngine.glassTab(url)
    // Tab.url / Tab.title are snapshot state: the header follows the page as the agent moves
    val pageUrl = tab?.url?.takeIf { it.isNotBlank() } ?: url
    val pageTitle = tab?.title.orEmpty()
    val frame by produceState<ImageBitmap?>(null, tab, working) {
        val t = tab ?: return@produceState
        while (true) {
            BrowserEngine.glassFrame(t)?.let { value = it }
            delay(if (working) LivingGlass.FRAME_MS else LivingGlass.IDLE_MS)
        }
    }
    val fallback = shots.lastOrNull()?.image
    val host = LivingModel.host(pageUrl)
    val site = LivingPage.site(pageUrl).ifBlank { tr("Browser") }
    val doing = if (!working) "" else now.ifBlank { step?.what.orEmpty() }

    // the glass: takes every touch that isn't on Close, so the canvas and the page under it never get one
    Box(modifier.fillMaxSize().background(Color(0xE6000000))
        .pointerInput(Unit) { awaitPointerEventScope { while (true) { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } } }
        .semantics { contentDescription = tr("Watching {0}'s browser, view only", com.blaze.assistant.BrandName.NAME) }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveDot(working, p)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(site, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pageTitle.ifBlank { host }, color = Color(0xFFB8B8B8), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x33FFFFFF))
                    .clickable(onClickLabel = tr("Close")) { LivingSpec.closeGlass() }
                    .padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Text(tr("Close"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(tr("View only"), color = Color(0xFFDADADA), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (doing.isNotBlank()) doing else if (working) tr("Working\u2026") else tr("Idle: the agent isn't using the browser right now"),
                    color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            val shape = RoundedCornerShape(12.dp)
            Box(Modifier.fillMaxWidth().weight(1f).clip(shape).background(Color.White).border(1.dp, Color(0x33FFFFFF), shape),
                contentAlignment = Alignment.TopCenter) {
                val img = frame ?: fallback
                if (img != null) Image(img, contentDescription = pageTitle.ifBlank { site }, contentScale = ContentScale.Fit,
                    alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                else Text(tr("The page will show here when the agent opens one."), color = Color(p.mute), fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(host, color = Color(0xFF9A9A9A), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LiveDot(on: Boolean, p: LivingSpec.Palette) {
    val c = if (on) Color(p.orb) else Color(0xFF8A8A8A)
    Canvas(Modifier.size(12.dp)) {
        drawCircle(c.copy(alpha = 0.3f), size.minDimension / 2f)
        drawCircle(c, size.minDimension / 4f, Offset(size.width / 2f, size.height / 2f))
    }
}
