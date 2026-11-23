package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.blaze.assistant.ui.space.*

/**
 * The top of every full view (Finn, 23 Nov 2026, from the Movies mockup): the profile icon far left, the view's name
 * beside it ("Movies", "Music", "Studying"), then search and notifications on the right. Shared by Music and Video
 * (MediaTopBar) and Learn.
 *
 * The profile icon opens a small menu: the account (when the view has one), light / dark, and back to the chat. The
 * light / dark switch and the close used to be two more round buttons in this row; they moved here so the row is only
 * the four things in the mockup. Back (the phone's) still goes to the chat too.
 *
 * The bell lists the view's own notices ([ViewNotices]: downloads in Music and Video, cards waiting in Studying), with
 * a dot while one needs a look. It never makes notices up: an empty list says "Nothing new".
 */
@Composable
fun ViewHeader(
    title: String,
    initial: String,
    backLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAccount: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    notices: List<String> = emptyList(),
    noticeDot: Boolean = false,
    /** Side inset; a page that already pads its column (Studying, inside Settings) passes 0. */
    inset: androidx.compose.ui.unit.Dp = SpaceInsets.GUTTER_DP.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val look = spaceLook()
    var menu by remember { mutableStateOf(false) }
    var bell by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = (SpaceInsets.CONTROL_DP + 2 * 10).dp).padding(horizontal = inset),
        verticalAlignment = Alignment.CenterVertically) {
        Box {
            ProfileIcon(initial) { menu = true }
            PopMenu(menu, { menu = false }, buildList {
                onAccount?.let { add("Your account" to it) }
                add((if (look.dark) "Light theme" else "Dark theme") to { AppTheme.toggle() })
                add(backLabel to onBack)
            }, Alignment.TopStart)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = SpaceType.body(26.sp, look.ink, FontWeight.Bold),
            modifier = Modifier.weight(1f).semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
            if (onSearch != null) SpaceRoundButton("Search", onSearch) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = look.ink, modifier = Modifier.size(22.dp))
            }
            Box {
                SpaceRoundButton(if (noticeDot) "Notifications, something new" else "Notifications", { bell = true }) {
                    Icon(if (noticeDot) Icons.Rounded.Notifications else Icons.Rounded.NotificationsNone, contentDescription = null, tint = look.ink,
                        modifier = Modifier.size(22.dp))
                }
                if (noticeDot) Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 10.dp).size(8.dp).clip(CircleShape).background(look.accent))
                PopMenu(bell, { bell = false }, notices.ifEmpty { listOf("Nothing new") }.map { it to {} })
            }
        }
    }
}

/** The profile icon: the user's initial in an Argus Blue circle with a soft ring (the mockup's glowing avatar). */
@Composable
fun ProfileIcon(initial: String, onClick: () -> Unit) {
    val look = spaceLook()
    Box(Modifier.size(SpaceInsets.CONTROL_DP.dp + 6.dp).clip(CircleShape).border(1.5.dp, look.accent.copy(alpha = 0.7f), CircleShape).padding(3.dp)
        .clip(CircleShape).background(Color(SpacePalette.ARGUS_BLUE))
        .clickable(role = Role.Button, onClickLabel = "Profile menu") { onClick() }
        .semantics { contentDescription = "Profile menu" }, contentAlignment = Alignment.Center) {
        if (initial.isNotBlank()) Text(initial.trim().take(1).uppercase(), style = SpaceType.rowTitle(Color.White))
        else Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** What a view's bell lists (pure; ViewRedesignTest). */
object ViewNotices {
    /** Music ([kind] audio) or Video ([kind] video): the newest downloads, each "title · status". */
    fun downloads(jobs: List<Pair<String, String>>, max: Int = 8): List<String> =
        jobs.take(max).map { (title, status) -> listOf(title, status).filter { it.isNotBlank() }.joinToString(" \u00b7 ") }

    /** Studying: cards waiting now, else when the next ones come back, else nothing. */
    fun cards(waiting: Int, nextIn: String?): List<String> = when {
        waiting > 0 -> listOf(if (waiting == 1) "1 card is waiting" else "$waiting cards are waiting")
        nextIn != null -> listOf("Next cards come back in $nextIn")
        else -> emptyList()
    }
}

/**
 * The planet from the mockups (hero cards): a lit sphere in the accent colour with a tilted ring and a soft halo. Pure
 * drawing, no bitmap, so it costs nothing to keep on screen and follows the theme's accent.
 */
@Composable
fun OrbArt(modifier: Modifier = Modifier, accent: Color = spaceLook().accent) {
    androidx.compose.foundation.Canvas(modifier) {
        val r = minOf(size.width, size.height) * 0.34f
        val c = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.5f)
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), c, r * 1.9f), r * 1.9f, c)
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFFBFE0FF), accent, Color(0xFF07112B)),
            androidx.compose.ui.geometry.Offset(c.x - r * 0.45f, c.y - r * 0.5f), r * 1.7f), r, c)
        drawCircle(accent.copy(alpha = 0.8f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        androidx.compose.ui.graphics.drawscope.rotate(-18f, c) {
            drawOval(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(accent.copy(alpha = 0.1f), Color(0xFFBFE0FF), accent.copy(alpha = 0.2f))),
                topLeft = androidx.compose.ui.geometry.Offset(c.x - r * 1.55f, c.y - r * 0.38f), size = androidx.compose.ui.geometry.Size(r * 3.1f, r * 0.76f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        }
    }
}

/** The glowing horizon of a planet low on the right (the Studying and Music mockups' backdrop). */
@Composable
fun HorizonGlow(modifier: Modifier = Modifier, accent: Color = spaceLook().accent, dark: Boolean = spaceLook().dark) {
    androidx.compose.foundation.Canvas(modifier) {
        val big = size.width * 1.1f
        val c = androidx.compose.ui.geometry.Offset(size.width * 1.05f, size.height + big * 0.78f)
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent), c, big * 1.25f), big * 1.25f, c)
        drawCircle(if (dark) Color(0xFF050A18) else Color(0xFFDCE6FF), big, c)
        drawCircle(accent.copy(alpha = 0.9f), big, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

/** A section's heading in the redesigned views: bold title on the left, "See all ›" on the right when [onSeeAll] is set. */
@Composable
fun SeeAllHeader(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null, inset: androidx.compose.ui.unit.Dp = SpaceInsets.GUTTER_DP.dp) {
    val look = spaceLook()
    Row(modifier.fillMaxWidth().padding(start = inset, end = if (inset.value > 0f) 12.dp else 0.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = SpaceType.body(21.sp, look.ink, FontWeight.Bold), modifier = Modifier.weight(1f).semantics { heading() })
        if (onSeeAll != null) Row(Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "See all $title") { onSeeAll() }.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("See all", style = SpaceType.body(14.sp, look.sub, FontWeight.Medium))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = look.sub, modifier = Modifier.size(20.dp))
        }
    }
}

/** The accent pill button of the hero cards ("Play Mix", "Play", "Continue"). */
@Composable
fun AccentPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    val look = spaceLook()
    Row(modifier.clip(CircleShape).then(if (filled) Modifier.background(look.accent) else Modifier.background(look.track).border(1.dp, look.hair, CircleShape))
        .clickable(role = Role.Button, onClickLabel = label) { onClick() }.padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, contentDescription = null, tint = if (filled) Color.White else look.ink, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, style = SpaceType.body(15.sp, if (filled) Color.White else look.ink, FontWeight.SemiBold))
    }
}

/** Page dots under a carousel. */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier, alignStart: Boolean = false) {
    if (count < 2) return
    val look = spaceLook()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i -> Box(Modifier.size(if (i == current) 8.dp else 7.dp).clip(CircleShape).background(if (i == current) look.ink else look.sub.copy(alpha = 0.45f))) }
    }
}

/** The shared menu card in a popup at [alignment] of its parent (the profile menu opens to the right, the rest to the left). */
@Composable
fun PopMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<Pair<String, () -> Unit>>, alignment: Alignment = Alignment.TopEnd) {
    if (!expanded) return
    Popup(alignment = alignment, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) { BlazeMenuCard(items, onDismiss) }
}
