package com.blaze.assistant.ui.space

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Landscape audit 2026-11-15 (docs/overhaul/landscape-audit.md), redone for the cinematic onboarding (owner 2026-11-18):
 * the language step is a centred pill over the space view, so it fits a phone held sideways (a smaller logo on a short
 * window, the list sized to the window by LanguagePick.box); the clip is fitted to any shape (IntroClip.size); every step
 * keeps clear of a side cutout.
 */
class OnboardingLandscapeTest {
    private val src: String = File(listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory },
        "com/blaze/assistant/ui/space/SpaceOnboarding.kt").readText()
    private val intro: String = File(listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory },
        "com/blaze/assistant/ui/space/SpaceIntro.kt").readText()
    private fun body(fn: String, s: String = src) = s.substringAfter("private fun $fn(").substringBefore("\n}\n")

    @Test fun languageIsAPillThatOpensIntoAList() {
        val b = body("LanguageStep")
        assertTrue("com.blaze.assistant.ui.common.WindowUi.short()" in b)
        assertTrue("cutoutSafe()" in b && "BoxWithConstraints(" in b)
        assertTrue("the pill is centred", "Modifier.align(Alignment.Center).height(LanguagePick.PILL_H.dp)" in b)
        assertTrue("the arrow sits apart, at the far end", "Modifier.align(Alignment.CenterEnd).padding(end = LanguagePick.NEXT_EDGE.dp)" in b)
        assertTrue("the list is sized to the window", "LanguagePick.box(h / 2f, h, LanguagePick.contentHeight(langs.size))" in b)
        assertTrue("the list scrolls", "LazyColumn(" in b && "rememberLazyListState()" in b)
        assertTrue("springs", "spring(dampingRatio = 0.82f" in b)
        assertTrue("no typing", "TextField" !in b && "BasicTextField" !in b)
        assertTrue("Back folds the list first", "BackHandler(enabled = open) { open = false }" in b)
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
