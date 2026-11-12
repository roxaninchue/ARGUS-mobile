package com.blaze.assistant.ui
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
object GlassTokens { fun blurSupported(ctx: android.content.Context): Boolean = false }
fun DrawScope.drawArgusArcs(alpha: Float = 1f) {}
fun DrawScope.drawArgusMark(center: Offset, w: Float, t: Float = 0f, alpha: Float = 1f, fill: Float = 0.62f) {}
object ThemeMode { val mode = androidx.compose.runtime.mutableStateOf("system"); fun set(m: String) { mode.value = m } }
