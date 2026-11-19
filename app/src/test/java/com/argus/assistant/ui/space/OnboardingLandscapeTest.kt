package com.blaze.assistant.ui.space

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Landscape audit 2026-11-15 (docs/overhaul/landscape-audit.md), redone for the cinematic onboarding (owner 2026-11-18):
 * the language step is a wheel on the space view, so it fits a phone held sideways (a smaller logo and one language each
 * side of the frame on a short window, LanguagePick.side); the clip is fitted to any shape (IntroClip.size); every step
 * keeps clear of a side cutout.
 */
class OnboardingLandscapeTest {
    private val src: String = File(listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory },
        "com/blaze/assistant/ui/space/SpaceOnboarding.kt").readText()
    private val intro: String = File(listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory },
        "com/blaze/assistant/ui/space/SpaceIntro.kt").readText()
    private fun body(fn: String, s: String = src) = s.substringAfter("private fun $fn(").substringBefore("\n}\n")

    /** Owner 2026-11-19: no pop-out box; a wheel on the page with a framed middle row, snapping, sized for a short window. */
    @Test fun languageIsAWheelOnThePage() {
        val b = body("LanguageStep")
        assertTrue("com.blaze.assistant.ui.common.WindowUi.short()" in b)
        assertTrue("cutoutSafe()" in b && "BoxWithConstraints(" in b)
        assertTrue("one each side on a short window", "LanguagePick.side(short)" in b && "LanguagePick.height(short)" in b)
        assertTrue("the frame is the middle row", "Modifier.align(Alignment.Center).size(wheelW.dp, LanguagePick.ROW_H.dp)" in b)
        assertTrue("the arrow sits apart, at the far end", "Modifier.align(Alignment.CenterEnd).padding(end = LanguagePick.NEXT_EDGE.dp)" in b)
        assertTrue("it scrolls and snaps", "LazyColumn(" in b && "rememberSnapFlingBehavior(list)" in b)
        assertTrue("the settled row is the language", "snapshotFlow { list.isScrollInProgress }" in b && "LanguagePick.centred(" in b)
        assertTrue("a tap scrolls the row into the frame", "animateScrollToItem(i)" in b)
        assertTrue("rows fade towards the edges", "LanguagePick.fade(" in b)
        assertTrue("no pop-out box, no typing", "BackHandler" !in b && "var open" !in b && "TextField" !in b)
    }

    @Test fun theClipFillsAnyWindowOnBlack() {
        assertTrue("IntroClip.size(maxWidth.value, maxHeight.value)" in intro)
        assertTrue("clipToBounds()" in intro && "Color.Black" in intro)
        assertTrue("no controls: a TextureView, not a PlayerView", "TextureView(c)" in intro && "PlayerView" !in intro)
    }

    @Test fun signInKeepsClearOfTheCutout() {
        val b = body("SignIn")
        assertTrue("cutoutSafe()" in b && "readableWidth()" in b && "verticalScroll(rememberScrollState())" in b)
    }
}
