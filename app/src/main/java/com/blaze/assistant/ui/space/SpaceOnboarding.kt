package com.blaze.assistant.ui.space

import com.blaze.assistant.i18n.tr
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import com.blaze.assistant.ui.common.cutoutSafe
import com.blaze.assistant.ui.common.readableWidth
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.blaze.assistant.ui.BlazeIconStyle
import com.blaze.assistant.ui.IconTile
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
 * First run, owner 2026-11-18 ([SpaceOnboarding]): [SpaceOnboarding.Step.INTRO] the logo clip on black ([IntroStep]),
 * [SpaceOnboarding.Step.LANGUAGE] the language pill on the space view, [SpaceOnboarding.Step.SIGN_IN] the account card
 * (account lock: no skipping). [accountCard] is the existing account sign-in UI. The setup downloads run in the background
 * (SetupRunner); their screen ([SpaceSetupScreen]) opens from Life's "Finishing setup" card.
 */
@Composable
fun SpaceOnboardingScreen(
    step: SpaceOnboarding.Step,
    signedIn: Boolean,
    onNext: () -> Unit,
    accountCard: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalBlaze provides DarkColors) {
        com.blaze.assistant.ui.DarkSystemBars()   // always dark: keep the status bar icons light over it
        SpaceBackdrop(modifier.testTag(SpaceTags.ONBOARDING), forceDark = true) {
            AnimatedContent(step, transitionSpec = {
                    // the clip dissolves slowly into the space view; the other steps cross-fade as before
                    if (initialState == SpaceOnboarding.Step.INTRO && com.blaze.assistant.ui.MotionTokens.enabled)
                        fadeIn(androidx.compose.animation.core.tween(520)) togetherWith fadeOut(androidx.compose.animation.core.tween(420))
                    else fadeIn(com.blaze.assistant.ui.MotionTokens.medium()) togetherWith fadeOut(com.blaze.assistant.ui.MotionTokens.fast()) },
                label = "onboarding") { s ->
                when (s) {
                    SpaceOnboarding.Step.INTRO -> IntroStep(onNext)
                    SpaceOnboarding.Step.LANGUAGE -> LanguageStep(onNext)
                    SpaceOnboarding.Step.SIGN_IN -> SignIn(signedIn, onNext, accountCard)
                }
            }
        }
    }
}

/**
 * Fresh install, account locked (MainActivity): the clip and the language step come before the lock screen's sign-in
 * ([SpaceOnboarding.beforeLock]). [onDone] runs when the language step moves on; the lock screen then shows.
 */
@Composable
fun SpaceFirstRunBeforeLock(onDone: () -> Unit) {
    var step by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(SpaceOnboarding.Step.INTRO.name) }
    val s = SpaceOnboarding.Step.valueOf(step)
    SpaceOnboardingScreen(s, signedIn = false, onNext = {
        val nx = SpaceOnboarding.next(s, signedIn = false)
        if (SpaceOnboarding.introSeen(s, nx)) com.blaze.assistant.agent.Settings.setBool(SpaceOnboarding.PREF_INTRO_SEEN, true)
        if (nx == null || nx == SpaceOnboarding.Step.SIGN_IN) onDone() else step = nx.name
    }, accountCard = {})
}

/**
 * Owner 2026-11-18: the language step is one clean pill in the middle of the screen with the language in use (the
 * phone's to begin with, [com.blaze.assistant.i18n.AppLang.initial] at start). A tap grows it into a box, up and down at once, holding every
 * language by its own name in a smooth list (no typing); picking one switches the app and folds the box back into the
 * pill. The round arrow at the far right of the screen, apart from the pill, moves on. Springs, the space view's colours.
 */
@Composable
private fun LanguageStep(onNext: () -> Unit) {
    val look = SpaceLook.Dark
    val sel = com.blaze.assistant.i18n.AppLang.code.value
    val langs = com.blaze.assistant.i18n.AppLang.PICKABLE
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val motion = com.blaze.assistant.ui.MotionTokens.enabled
    val t by androidx.compose.animation.core.animateFloatAsState(if (open) 1f else 0f,
        if (motion) androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)
        else androidx.compose.animation.core.snap(), label = "language")
    androidx.activity.compose.BackHandler(enabled = open) { open = false }
    val pick: (String) -> Unit = { code ->
        com.blaze.assistant.agent.Settings.setStr(com.blaze.assistant.i18n.AppLang.PREF, code)
        com.blaze.assistant.i18n.AppLang.apply(code); com.blaze.assistant.ui.FontPick.refresh()
        open = false
    }
    val short = com.blaze.assistant.ui.common.WindowUi.short()
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val density = androidx.compose.ui.platform.LocalDensity.current
    var pillW by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(200f) }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().cutoutSafe()) {
        val w = maxWidth.value; val h = maxHeight.value
        // The heading over the pill (it steps back while the list is open)
        Column(Modifier.align(Alignment.TopCenter).padding(top = if (short) 10.dp else 44.dp, start = 24.dp, end = 24.dp)
            .graphicsLayer { alpha = 1f - 0.7f * t.coerceIn(0f, 1f) }, horizontalAlignment = Alignment.CenterHorizontally) {
            ArgusLogo(if (short) 56.dp else 96.dp)
            Spacer(Modifier.height(if (short) 8.dp else 16.dp))
            Text(tr("Choose your language"), textAlign = TextAlign.Center, style = SpaceType.headline(if (short) 22.sp else 28.sp, look.ink))
        }
        Text(tr("{0} always replies in the language you write in.", BrandName.UPPER), textAlign = TextAlign.Center,
            style = SpaceType.body(14.sp, look.sub),
            modifier = Modifier.align(Alignment.Center).offset(y = (LanguagePick.PILL_H / 2f + 30f).dp).padding(horizontal = 40.dp)
                .graphicsLayer { alpha = 1f - t.coerceIn(0f, 1f) })
        // The pill: the language in use, centred
        val name = com.blaze.assistant.i18n.AppLang.lang(sel).name
        Row(Modifier.align(Alignment.Center).height(LanguagePick.PILL_H.dp)
            .widthIn(min = 150.dp, max = LanguagePick.pillMax(w).dp)
            .onSizeChanged { pillW = with(density) { it.width.toDp().value } }
            .graphicsLayer { alpha = if (t > 0.02f) 0f else 1f }
            .clip(SpaceShapes.pill).background(look.card).border(1.dp, look.hair, SpaceShapes.pill)
            .clickable(enabled = !open, onClickLabel = tr("Change language")) { open = true }
            .semantics { role = androidx.compose.ui.semantics.Role.Button; contentDescription = tr("Language: {0}", name) }
            .testTag(SpaceTags.LANGUAGE_PILL).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            androidx.compose.material3.Icon(com.blaze.assistant.ui.argus.icons.Lucide.icon("globe", 18), null, Modifier.size(18.dp), tint = look.sub)
            Spacer(Modifier.width(10.dp))
            Text(name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                style = SpaceType.body(19.sp, look.ink, FontWeight.Medium).copy(fontFamily = com.blaze.assistant.ui.Type.sansFor(name)))
            Spacer(Modifier.width(10.dp))
            androidx.compose.material3.Icon(com.blaze.assistant.ui.argus.icons.Lucide.icon("chevron-down", 16), null, Modifier.size(16.dp), tint = look.sub)
        }
        // The continue button: its own round button at the far right, level with the pill
        SpaceRoundButton(tr("Continue"), onNext, Modifier.align(Alignment.CenterEnd).padding(end = LanguagePick.NEXT_EDGE.dp)
            .graphicsLayer { alpha = 1f - t.coerceIn(0f, 1f) }, tag = SpaceTags.LANGUAGE_NEXT, size = LanguagePick.NEXT.dp, fill = look.primaryFill) {
            androidx.compose.material3.Icon(com.blaze.assistant.ui.argus.icons.Lucide.icon("arrow-right", 22), null,
                Modifier.size(22.dp).graphicsLayer { scaleX = if (rtl) -1f else 1f }, tint = look.primaryInk)
        }
        // The open list: grows out of the pill (up and down), then the rows fade in
        if (t > 0.001f) {
            val target = LanguagePick.box(h / 2f, h, LanguagePick.contentHeight(langs.size))
            val top = LanguagePick.lerp(h / 2f - LanguagePick.PILL_H / 2f, target.top, t)
            val bh = LanguagePick.lerp(LanguagePick.PILL_H, target.height, t)
            val bw = LanguagePick.lerp(pillW, LanguagePick.width(w), t)
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(LanguagePick.lerp(LanguagePick.PILL_H / 2f, 26f, t.coerceIn(0f, 1f)).dp)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * t.coerceIn(0f, 1f)))
                .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null, onClickLabel = tr("Close")) { open = false })
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            val at = langs.indexOfFirst { it.code == sel }.coerceAtLeast(0)
            androidx.compose.runtime.LaunchedEffect(open) {
                if (open) list.scrollToItem(LanguagePick.firstRow(at, (target.height / LanguagePick.ROW_H).toInt()))
            }
            Box(Modifier.align(Alignment.TopCenter).offset(y = top.dp).size(bw.dp, bh.dp).clip(shape).background(look.menu)
                .border(1.dp, look.hair, shape).testTag(SpaceTags.LANGUAGE_LIST)) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().graphicsLayer { alpha = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f) },
                    state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = LanguagePick.PAD.dp)) {
                    items(langs.size, key = { langs[it].code }) { i ->
                        val l = langs[i]
                        val on = l.code == sel
                        Row(Modifier.fillMaxWidth().height(LanguagePick.ROW_H.dp)
                            .selectable(selected = on, enabled = open, role = androidx.compose.ui.semantics.Role.RadioButton) { pick(l.code) }
                            .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(l.name, modifier = Modifier.weight(1f), maxLines = 1,
                                style = SpaceType.body(18.sp, if (on) look.ink else look.sub).copy(fontFamily = com.blaze.assistant.ui.Type.sansFor(l.name),
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                            if (on) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, contentDescription = null,
                                tint = look.ink, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignIn(signedIn: Boolean, onNext: () -> Unit, accountCard: @Composable () -> Unit) {
    val look = SpaceLook.Dark
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().cutoutSafe().padding(horizontal = 18.dp, vertical = 18.dp).readableWidth()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ArgusLogo(if (com.blaze.assistant.ui.common.WindowUi.short()) 72.dp else 120.dp, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(18.dp))
            SpaceCard {
                SpaceHeadline(tr("Sign in to {0}", BrandName.UPPER), size = 30.sp)
                Spacer(Modifier.height(8.dp))
                Text(tr("One free account and {0} Cloud answers every message with the best brain for it. No keys, no settings.", BrandName.NAME),
                    style = SpaceType.body(16.sp, look.sub))
                Spacer(Modifier.height(8.dp))
                accountCard()
            }
        }
        Spacer(Modifier.height(12.dp))
        SpaceOnboarding.signInButton(signedIn)?.let { SpacePillButton(tr(it), onNext, primary = true) }   // account lock: no Skip
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
                        // 0.52.4: a round tinted icon tile per item (the reference's result rows), the status mark on the right
                        IconTile(setupIcon(item.id), tint = look.accent)
                        Spacer(Modifier.width(BlazeIconStyle.gap))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = SpaceType.body(16.sp, look.ink, FontWeight.Medium))
                            Text(item.sub, style = SpaceType.body(13.sp, look.sub))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(SetupPlan.line(item, st), modifier = Modifier.widthIn(max = 104.dp), maxLines = 2, textAlign = TextAlign.End,
                            style = SpaceType.body(13.sp, if (st?.phase == SetupPlan.Phase.DONE) look.accent else look.sub))
                        Spacer(Modifier.width(10.dp))
                        SetupDot(st?.phase ?: SetupPlan.Phase.PENDING, (st?.progress ?: -1), size = 22.dp)
                    }
                }
                // 0.52.4: no need to wait for Wi-Fi; the user can finish now on mobile data (the size is on the button)
                val wifiMb = SetupPlan.waitingWifiMb(setup)
                if (wifiMb > 0) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Spacer(Modifier.height(8.dp))
                    SpaceChip(SetupPlan.mobileDataButton(wifiMb), { com.blaze.assistant.setup.SetupRunner.useMobileData(ctx) }, icon = Icons.Rounded.SignalCellularAlt)
                }
                if (setup.values.any { it.phase == SetupPlan.Phase.FAILED }) {
                    Spacer(Modifier.height(8.dp))
                    SpaceChip("Retry now", onRetry, icon = Icons.Rounded.Refresh)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SpacePillButton(trBrand(SpaceOnboarding.setupButton(done)), onContinue, primary = true, tag = SpaceTags.SETUP_CONTINUE)
    }
}

/** Each setup item's icon (SetupPlan.ITEMS ids); an unknown id gets the download mark. */
internal fun setupIcon(id: String): ImageVector = when (id) {
    "linux" -> Icons.Rounded.Terminal
    "tools" -> Icons.Rounded.Build
    "skills" -> Icons.Rounded.AutoAwesome
    "voice" -> Icons.Rounded.RecordVoiceOver
    "quick" -> Icons.Rounded.Bolt
    else -> Icons.Rounded.Download
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
