package com.blaze.assistant.ui.space

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.blaze.assistant.BrandName
import com.blaze.assistant.i18n.tr
import com.blaze.assistant.ui.MotionTokens
import kotlinx.coroutines.delay

/**
 * Owner 2026-11-18: onboarding opens with the ARGUS logo clip (res/raw/argus_intro.mp4) full screen, with no controls
 * and the system bars hidden. 0.55.12 (owner 2026-11-24): edge to edge with no letterbox: the screen around the clip is
 * the clip's own deep blue ([introGround]) and the clip's edges fade into it ([introFeather]), never black bars. A tap anywhere skips it; when it ends onboarding moves on by itself. With the
 * phone's "Remove animations" on, or when the clip can't play (no decoder, an error, nothing on screen after
 * [IntroClip.START_TIMEOUT_MS]), the still ARGUS logo and the tagline show for [IntroClip.STILL_MS] instead. Its sound
 * plays at [IntroClip.VOLUME], muted on silent / vibrate or while other audio plays ([IntroClip.volume]).
 */
@Composable
fun IntroStep(onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    var left by remember { mutableStateOf(false) }
    val finish = { if (!left) { left = true; done() } }
    var failed by remember { mutableStateOf(false) }
    val mode = IntroClip.mode(MotionTokens.enabled, failed)
    LaunchedEffect(Unit) { delay(IntroClip.MAX_MS); finish() }   // whatever happens, onboarding moves on
    HideSystemBars()
    Box(Modifier.fillMaxSize().introGround().testTag(SpaceTags.ONBOARDING_INTRO)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = tr("Skip"), role = Role.Button) { finish() }
        .semantics { contentDescription = BrandName.UPPER },
        contentAlignment = Alignment.Center) {
        if (mode == IntroClip.Mode.CLIP) IntroVideo(onEnd = { finish() }, onFail = { failed = true })
        else {
            LaunchedEffect(Unit) { delay(IntroClip.STILL_MS); finish() }
            Column(Modifier.padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                ArgusLogo(if (com.blaze.assistant.ui.common.WindowUi.short()) 140.dp else 240.dp)
                Spacer(Modifier.height(20.dp))
                Text(tr(SpaceOnboarding.TAGLINE), textAlign = TextAlign.Center, style = SpaceType.headline(26.sp, Color.White))
            }
        }
    }
}

/**
 * The clip on a TextureView (so it can be sized past the screen and cut), fitted and gently zoomed by [IntroClip.size],
 * its edges fading into the ground ([introFeather]).
 */
@Composable
private fun IntroVideo(onEnd: () -> Unit, onFail: () -> Unit) {
    val ctx = LocalContext.current
    val end by rememberUpdatedState(onEnd)
    val fail by rememberUpdatedState(onFail)
    var shown by remember { mutableStateOf(false) }
    val player = remember {
        try {
            ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://" + ctx.packageName + "/" + com.blaze.assistant.R.raw.argus_intro)))
                volume = IntroClip.volume(ringerNormal(ctx), otherAudio(ctx))
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                prepare()
            }
        } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("IntroVideo", t); null }
    }
    if (player == null) { LaunchedEffect(Unit) { fail() }; return }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) end() }
            override fun onPlayerError(error: PlaybackException) { com.blaze.assistant.device.CrashLog.quiet("IntroVideo play", error); fail() }
            override fun onRenderedFirstFrame() { shown = true }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); try { player.release() } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("IntroVideo release", t) } }
    }
    LaunchedEffect(Unit) { delay(IntroClip.START_TIMEOUT_MS); if (!shown) fail() }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(240), label = "intro")
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val (vw, vh) = IntroClip.size(maxWidth.value, maxHeight.value)
        AndroidView(factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
            modifier = Modifier.requiredSize(vw.dp, vh.dp).graphicsLayer { this.alpha = alpha }.introFeather())
    }
}

/** The screen behind and around the clip: [IntroClip.GROUND], a deep blue glow from the centre to near-black corners. */
private fun Modifier.introGround(): Modifier = drawBehind {
    val stops = IntroClip.GROUND_STOPS.indices.map { IntroClip.GROUND_STOPS[it] to Color(IntroClip.GROUND[it]) }.toTypedArray()
    drawRect(Brush.radialGradient(*stops, center = center, radius = size.maxDimension * IntroClip.GROUND_RADIUS))
}

/** Fades the clip's four edges out (DstIn masks on an offscreen layer), so no seam shows against [introGround]. */
private fun Modifier.introFeather(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fy = IntroClip.FEATHER_Y; val fx = IntroClip.FEATHER_X
        drawRect(Brush.verticalGradient(0f to Color.Transparent, fy to Color.Black, (1f - fy) to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, fx to Color.Black, (1f - fx) to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

private fun ringerNormal(ctx: Context): Boolean = try {
    ctx.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_NORMAL
} catch (_: Throwable) { false }

private fun otherAudio(ctx: Context): Boolean = try { ctx.getSystemService(AudioManager::class.java)?.isMusicActive == true } catch (_: Throwable) { false }

/** Full screen while the clip plays: the status and navigation bars hide (a swipe shows them) and come back after. */
@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val win = view.context.activity()?.window
        val c = win?.let { WindowCompat.getInsetsController(it, view) }
        try {
            c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(WindowInsetsCompat.Type.systemBars())
        } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("IntroStep bars", t) }
        onDispose { try { c?.show(WindowInsetsCompat.Type.systemBars()) } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("IntroStep bars back", t) } }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
