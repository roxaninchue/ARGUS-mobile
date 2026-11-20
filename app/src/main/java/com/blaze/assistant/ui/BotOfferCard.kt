package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.BotOffer
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.ui.argus.*
import kotlinx.coroutines.launch

/**
 * 0.54.1 (Finn: "a nice card … with the shape and color of the agent and their name and a short brief"): Argus suggests a
 * bot for work you keep asking for (agent/BotOffer). The bot in its own body and colour, its name, its area, what it does
 * and why it's suggested; Add creates it, No thanks hides the card, and ignoring it changes nothing.
 */
@Composable
fun BotOfferCard(m: ChatMessage) {
    val p = remember(m.meta) { BotOffer.parse(m.meta) } ?: return
    if (p.state == BotOffer.TURNED_DOWN) return
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(m.id) { mutableStateOf(false) }
    var error by remember(m.id) { mutableStateOf<String?>(null) }
    val tint = remember(p.color) { parse(p.color) }
    val added = p.state == BotOffer.TAKEN
    val face = OrbFace.values().getOrElse(p.face) { OrbFace.ORBIT }
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()
        .glass(GlassTokens.cardShape, GlassIntensity.Medium, shadow = false).padding(16.dp)
        .semantics(mergeDescendants = false) { contentDescription = if (added) "${p.name} joined your team" else "Suggested bot ${p.name}, ${p.label}. ${p.brief}" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotOrb(face, tint.toArgbLong(), size = 52.dp, state = if (busy) OrbState.WORKING else OrbState.IDLE)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(if (added) "New on your team" else "Suggested bot", color = tint, size = 9.sp)
                Spacer(Modifier.height(2.dp))
                Text(p.name, style = TextStyle(fontFamily = Type.Sans, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
                Spacer(Modifier.height(4.dp))
                Text(p.label, modifier = Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.16f)).padding(horizontal = 9.dp, vertical = 2.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(p.brief, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.body))
        Spacer(Modifier.height(6.dp))
        Text(if (added) "${p.name} takes ${p.what.ifBlank { p.label.lowercase() }} from here. Find them on your team." else BotOffer.why(p),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 17.sp, color = c.sub))
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
        if (!added) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (busy) "Adding\u2026" else "Add ${p.name}",
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(tint)
                        .clickable(enabled = !busy, role = Role.Button) {
                            busy = true; error = null
                            scope.launch { error = BotOffer.accept(ctx, m.id, p); busy = false }
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onTint(tint)))
                Text("No thanks",
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !busy, role = Role.Button) { BotOffer.decline(ctx, m.id, p) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.sub))
            }
        }
    }
}

private fun parse(hex: String): Color = try { Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")) } catch (_: Exception) { Color(0xFF8B5CF6) }

private fun Color.toArgbLong(): Long = (((alpha * 255).toInt().toLong() and 0xFF) shl 24) or (((red * 255).toInt().toLong() and 0xFF) shl 16) or
    (((green * 255).toInt().toLong() and 0xFF) shl 8) or ((blue * 255).toInt().toLong() and 0xFF)

/** Dark text on the light swatches (amber, cyan, green, silver), white on the rest. */
private fun onTint(c: Color): Color = if (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue > 0.6f) Color(0xFF111110) else Color.White
