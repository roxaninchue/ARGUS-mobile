package com.blaze.assistant.ui


import com.blaze.assistant.i18n.tr
import com.blaze.assistant.BrandName
import kotlinx.coroutines.flow.first
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.blaze.assistant.MainActivity
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Role
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.assist.BlazeInteractionService
import com.blaze.assistant.voice.Voice
import androidx.compose.foundation.shape.RoundedCornerShape

/** Lets a ComposeView live outside an Activity (assistant session window, floating orb). */
class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val reg = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun start() { saved.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
    fun destroy() { reg.currentState = Lifecycle.State.DESTROYED; store.clear() }

    fun attach(v: View) {
        v.setViewTreeLifecycleOwner(this)
        v.setViewTreeSavedStateRegistryOwner(this)
        v.setViewTreeViewModelStoreOwner(this)
    }
}

fun composeView(ctx: Context, owner: OverlayOwner, content: @androidx.compose.runtime.Composable () -> Unit): ComposeView =
    ComposeView(ctx).apply { owner.attach(this); setContent { BlazeTheme { content() } } }

/** The floating orb (and, without the assistant role, the overlay chat panel). */
/**
 * Opening Argus from outside the app (the home-screen widget, the wake word). 2026-11-25 permission trim: the floating
 * orb, its drag-to-bin, the live task card and the chat panel over other apps (SYSTEM_ALERT_WINDOW) left the app; the
 * panel now opens as the assistant session when Argus is the default assistant, otherwise Argus itself opens.
 */
object Overlay {
    /** Open the chat panel over the current app when Argus is the assistant (no overlay permission), else open Argus. */
    fun openPanel(ctx: Context, listen: Boolean) {
        // Account lock: the lock screen instead.
        if (com.blaze.assistant.connect.AccountGate.locked()) { try { ctx.startActivity(com.blaze.assistant.connect.AccountGate.lockIntent(ctx)) } catch (_: Exception) { }; return }
        com.blaze.assistant.agent.LlmClient.prewarm()
        if (BlazeInteractionService.showPanel(listen)) return
        openApp(ctx, listen)
    }

    fun openApp(ctx: Context, listen: Boolean) {
        ctx.startActivity(com.blaze.assistant.IntentKey.sign(ctx, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_LISTEN, listen)))
    }
}

// ---------------- UI ----------------

/** The round Argus orb: pixel B in the middle, a ring that breathes while listening or working. */
@Composable
fun OrbFace(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    val mood by Agent.mood.collectAsState()
    val voice by Voice.state.collectAsState()
    val level by Voice.level.collectAsState()
    val active = mood != Mood.IDLE || voice != Voice.State.IDLE
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // Idle orb is a still picture: no animation frames while it just sits on screen.
        if (active) OrbRing(size, level) else Canvas(Modifier.size(size)) {
            drawCircle(c.accent.copy(alpha = 0.10f), radius = this.size.minDimension / 2f * 0.92f)
        }
        Box(Modifier.size(size * 0.66f).clip(CircleShape).background(c.ink), contentAlignment = Alignment.Center) {
            BrandMark(mood, size * 0.44f)
        }
    }
}

@Composable
private fun OrbRing(size: androidx.compose.ui.unit.Dp, level: Float) {
    val c = LocalBlaze.current
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0.9f, 1.05f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "b")
    val lv by animateFloatAsState(level, label = "lv")
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(c.accent.copy(alpha = 0.18f + lv * 0.3f), radius = r * breathe)
        drawArc(c.accent, spin, 110f, false, style = Stroke(width = r * 0.08f),
            topLeft = Offset(r * 0.1f, r * 0.1f), size = androidx.compose.ui.geometry.Size(r * 1.8f, r * 1.8f))
    }
}
