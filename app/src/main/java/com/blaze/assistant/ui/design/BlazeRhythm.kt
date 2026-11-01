package com.blaze.assistant.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.Type

/**
 * 0.49.0 (mem1-ui-browse): the spacing and type rhythm.
 *
 * Report: "the current look is squashed — everything feels cramped. Create a unique, creative design that captures
 * what's important in Blaze... Give it real breathing room and hierarchy."
 *
 * The screenshot shows the real problem: one uniform 12–15 sp size everywhere, 6–10 dp between everything, and every
 * surface the same visual weight, so nothing reads as more important than anything else. That is a rhythm problem, not
 * a colour problem, so this file fixes rhythm and leaves the palette alone.
 *
 * Three scales and one rule:
 *  - [Space]: a 4 dp base with named steps. Screens stop inventing 6, 10 and 14.
 *  - [Text]: a real hierarchy, with line heights set so paragraphs breathe.
 *  - [Weight]: only three levels, so emphasis means something.
 *  - The rule: one [Space.xl] between sections, one [Space.lg] inside a section, and never two different gaps at the
 *    same level.
 */
object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 20.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp

    /** The gap between two turns of the conversation. */
    val turn: Dp = lg
    /** The gap between the thread and the composer: the composer gets its own air. */
    val dock: Dp = xl
    /** Side padding of the thread. */
    val gutter: Dp = lg
}

object Text {
    /** The one big thing on a screen: a greeting, a project name. */
    val display = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp)
    /** A section or chat title. */
    val title = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
    /** A message, a paragraph. */
    val body = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    /** A label, a timestamp, a hint. */
    val meta = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    /** The smallest thing worth reading: a chip, a step. */
    val micro = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
}

object Weight {
    val regular = FontWeight.Normal
    val medium = FontWeight.Medium
    val strong = FontWeight.SemiBold
}

/**
 * The chat shell: a generous header, a thread with real gaps between turns, and the composer on its own ground.
 *
 * It only arranges what it is given — [thread] is the existing message list and [composer] the existing bar — so this
 * cannot change behaviour, only rhythm. [title] is blank for the main chat.
 */
@Composable
fun BlazeChatShell(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    thread: @Composable () -> Unit,
    composer: @Composable () -> Unit = {},
) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxSize()) {
        // Header: the title at title size, the actions quiet beside it, and air above and below.
        Row(
            Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.md, top = Space.md, bottom = Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (title.isNotBlank()) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    style = Text.title.copy(color = c.ink))
            } else Spacer(Modifier.weight(1f))
            actions()
        }
        // The thread gets the room the header and composer don't need.
        Box(Modifier.weight(1f).fillMaxWidth()) { thread() }
        // The composer sits on its own ground, separated by the dock gap rather than crowding the last message.
        Column(Modifier.fillMaxWidth().padding(top = Space.sm)) { composer() }
    }
}

/**
 * A section heading: the small quiet label that tells you what the block below is. Used instead of making every line
 * the same size, which is what made the old screen read as one flat wall.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    Text(text.uppercase(), modifier = modifier.padding(start = Space.gutter, top = Space.lg, bottom = Space.sm),
        style = Text.micro.copy(color = c.sub, letterSpacing = 0.8.sp))
}

/**
 * A card with real padding: the old surfaces hugged their content, which is most of why everything felt cramped.
 */
@Composable
fun BlazeCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBlaze.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.gutter)
            .clip(RoundedCornerShape(24.dp))
            .background(c.ink.copy(alpha = 0.04f))
            .padding(horizontal = Space.lg, vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        content = content
    )
}

/** A quiet divider that separates sections without drawing a hard line across the screen. */
@Composable
fun BlazeDivider(modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    Box(modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.lg)
        .height(1.dp).background(c.ink.copy(alpha = 0.06f)))
}

/** A fixed gap, named, so screens stop hard-coding numbers. */
@Composable
fun Gap(size: Dp) = Spacer(Modifier.height(size))

/** A fixed horizontal gap. */
@Composable
fun HGap(size: Dp) = Spacer(Modifier.width(size))
