package com.blaze.assistant.ui.space

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.BrandName
import com.blaze.assistant.setup.SetupPlan
import com.blaze.assistant.ui.DarkColors
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.drawArgusMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The metallic ARGUS logo (res/drawable-nodpi/argus_logo.webp), decoded off the main thread once. */
object ArgusLogoCache {
    @Volatile private var cached: ImageBitmap? = null
    private val lock = Any()
    fun get0(): ImageBitmap? = cached
    /**
     * 0.52.3 CI fix: decoded at the size the welcome draws it (260 dp wide, power-of-two sampling of the 720 px source)
     * instead of full size, once (synchronized), and prefetched by StartWarm before the first frame like [SpaceImageCache].
     */
    fun get(ctx: android.content.Context): ImageBitmap? = cached ?: synchronized(lock) {
        cached ?: try {
            val targetPx = (260f * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            var s = 1; while (720 / (s * 2) >= targetPx) s *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = s }
            BitmapFactory.decodeResource(ctx.resources, com.blaze.assistant.R.drawable.argus_logo, o)
                ?.also { it.prepareToDraw() }?.asImageBitmap()?.also { cached = it }
        } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("ArgusLogoCache", t); null }
    }
}

/** The logo at [width]; until it is decoded (or if it can't be) the vector ARGUS mark stands in, so the layout never jumps. */
@Composable
fun ArgusLogo(width: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val img by produceState(ArgusLogoCache.get0()) { if (value == null) value = withContext(Dispatchers.IO) { ArgusLogoCache.get(ctx) } }
    Box(modifier.width(width).aspectRatio(720f / 573f).testTag(SpaceTags.ONBOARDING_LOGO).semantics { contentDescription = BrandName.UPPER },
        contentAlignment = Alignment.Center) {
        val b = img
        if (b != null) Image(b, contentDescription = null, modifier = Modifier.fillMaxSize())
        else Canvas(Modifier.fillMaxSize()) { drawArgusMark(Offset(size.width / 2f, size.height * 0.42f), size.height * 0.62f) }
    }
}

/**
 * 0.52.3 first run, always on the space view: [SpaceOnboarding.Step.WELCOME] (logo, tagline, Get started),
 * [SpaceOnboarding.Step.SIGN_IN] (the account card, skippable) and [SpaceOnboarding.Step.SETUP] (the one progress screen).
 * [accountCard] is the existing account sign-in UI; [setup] the live setup state ([com.blaze.assistant.setup.SetupRunner]).
 */
@Composable
fun SpaceOnboardingScreen(
    step: SpaceOnboarding.Step,
    signedIn: Boolean,
    setup: Map<String, SetupPlan.Status>,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    accountCard: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalBlaze provides DarkColors) {
        SpaceBackdrop(modifier.testTag(SpaceTags.ONBOARDING), forceDark = true) {
            AnimatedContent(step, transitionSpec = { fadeIn(com.blaze.assistant.ui.MotionTokens.medium()) togetherWith fadeOut(com.blaze.assistant.ui.MotionTokens.fast()) },
                label = "onboarding") { s ->
                when (s) {
                    SpaceOnboarding.Step.WELCOME -> Welcome(onNext)
                    SpaceOnboarding.Step.SIGN_IN -> SignIn(signedIn, onNext, accountCard)
                    SpaceOnboarding.Step.SETUP -> SpaceSetupScreen(setup, onNext, onRetry)
                }
            }
        }
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    val look = SpaceLook.Dark
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.9f))
        ArgusLogo(260.dp)
        Spacer(Modifier.height(28.dp))
        Text(SpaceOnboarding.TAGLINE, textAlign = TextAlign.Center, style = SpaceType.headline(30.sp, look.ink))
        Spacer(Modifier.height(12.dp))
        Text("Your assistant for everything on this phone. Ready in a minute, nothing to set up.", textAlign = TextAlign.Center,
            style = SpaceType.body(16.sp, look.sub))
        Spacer(Modifier.weight(1f))
        SpacePillButton("Get started", onStart, primary = true, tag = SpaceTags.ONBOARDING_START, modifier = Modifier.widthIn(max = 420.dp))
    }
}

@Composable
private fun SignIn(signedIn: Boolean, onNext: () -> Unit, accountCard: @Composable () -> Unit) {
    val look = SpaceLook.Dark
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 18.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ArgusLogo(120.dp, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(18.dp))
            SpaceCard {
                SpaceHeadline("Sign in to ${BrandName.UPPER}", size = 30.sp)
                Spacer(Modifier.height(8.dp))
                Text("One free account and ${BrandName.NAME} Cloud answers every message with the best brain for it. No keys, no settings.",
                    style = SpaceType.body(16.sp, look.sub))
                Spacer(Modifier.height(8.dp))
                accountCard()
            }
        }
        Spacer(Modifier.height(12.dp))
        SpacePillButton(SpaceOnboarding.signInButton(signedIn), onNext, primary = signedIn)
    }
}

/**
 * The one setup screen: the ARGUS logo, one overall progress bar with its percentage, then a compact list of every item
 * with its status (waiting, a percentage, ready, on Wi-Fi, not needed here). Nothing to choose; Continue is always on
 * (what is left finishes in the background, and the Home shows a small "Finishing setup" card meanwhile).
 */
@Composable
fun SpaceSetupScreen(setup: Map<String, SetupPlan.Status>, onContinue: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val look = SpaceLook.Dark
    val pct = SetupPlan.percent(setup)
    val done = SetupPlan.complete(setup)
    Column(modifier.fillMaxSize().testTag(SpaceTags.SETUP).statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 18.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ArgusLogo(130.dp, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(18.dp))
            SpaceCard {
                Row(verticalAlignment = Alignment.Bottom) {
                    SpaceHeadline(SetupPlan.headline(setup), size = 26.sp, modifier = Modifier.weight(1f))
                    Text("$pct%", modifier = Modifier.testTag(SpaceTags.SETUP_PROGRESS).semantics { liveRegion = LiveRegionMode.Polite },
                        style = SpaceType.body(26.sp, look.accent, FontWeight.Medium))
                }
                Spacer(Modifier.height(14.dp))
                SpaceProgressBar(pct / 100f)
                Spacer(Modifier.height(18.dp))
                SetupPlan.ITEMS.forEachIndexed { i, item ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(look.hair))
                    val st = setup[item.id]
                    Row(Modifier.fillMaxWidth().testTag(SpaceTags.setupRow(item.id)).padding(vertical = 12.dp).semantics(mergeDescendants = true) { },
                        verticalAlignment = Alignment.CenterVertically) {
                        SetupDot(st?.phase ?: SetupPlan.Phase.PENDING, (st?.progress ?: -1))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = SpaceType.body(16.sp, look.ink, FontWeight.Medium))
                            Text(item.sub, style = SpaceType.body(13.sp, look.sub))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(SetupPlan.line(item, st), style = SpaceType.body(13.sp, if (st?.phase == SetupPlan.Phase.DONE) look.accent else look.sub))
                    }
                }
                // 0.52.4: no need to wait for Wi-Fi; the user can finish now on mobile data (the size is on the button)
                val wifiMb = SetupPlan.waitingWifiMb(setup)
                if (wifiMb > 0) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Spacer(Modifier.height(8.dp))
                    SpaceChip(SetupPlan.mobileDataButton(wifiMb), { com.blaze.assistant.setup.SetupRunner.useMobileData(ctx) })
                }
                if (setup.values.any { it.phase == SetupPlan.Phase.FAILED }) {
                    Spacer(Modifier.height(8.dp))
                    SpaceChip("Retry now", onRetry)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SpacePillButton(SpaceOnboarding.setupButton(done), onContinue, primary = true, tag = SpaceTags.SETUP_CONTINUE)
    }
}

/** A row's status mark: a hollow ring (waiting), an arc that fills (running), a filled check (ready), a dash (not needed). */
@Composable
private fun SetupDot(phase: SetupPlan.Phase, progress: Int, size: Dp = 26.dp) {
    val electric = Color(SpacePalette.ELECTRIC); val steel = Color(SpacePalette.STEEL)
    Canvas(Modifier.size(size).semantics { contentDescription = phase.name.lowercase().replace('_', ' ') }) {
        val w = this.size.width; val sw = w * 0.1f; val inset = sw / 2f; val box = Size(w - sw, w - sw)
        when (phase) {
            SetupPlan.Phase.DONE -> {
                drawCircle(electric, radius = w / 2f)
                drawLine(Color.White, Offset(w * 0.30f, w * 0.52f), Offset(w * 0.45f, w * 0.66f), sw * 1.2f, StrokeCap.Round)
                drawLine(Color.White, Offset(w * 0.45f, w * 0.66f), Offset(w * 0.71f, w * 0.38f), sw * 1.2f, StrokeCap.Round)
            }
            SetupPlan.Phase.SKIPPED -> drawLine(steel, Offset(w * 0.3f, w / 2f), Offset(w * 0.7f, w / 2f), sw, StrokeCap.Round)
            SetupPlan.Phase.RUNNING -> {
                drawCircle(steel.copy(alpha = 0.35f), radius = (w - sw) / 2f, style = Stroke(sw))
                val sweep = if (progress in 0..100) 360f * progress / 100f else 90f
                drawArc(electric, -90f, sweep.coerceAtLeast(12f), false, Offset(inset, inset), box, style = Stroke(sw, cap = StrokeCap.Round))
            }
            else -> drawCircle(steel.copy(alpha = if (phase == SetupPlan.Phase.FAILED) 0.9f else 0.5f), radius = (w - sw) / 2f, style = Stroke(sw))
        }
    }
}
