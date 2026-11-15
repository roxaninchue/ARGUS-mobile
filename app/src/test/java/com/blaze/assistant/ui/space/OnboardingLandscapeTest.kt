package com.blaze.assistant.ui.space

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Landscape audit 2026-11-15 (docs/overhaul/landscape-audit.md): onboarding's Language and Welcome steps fit a phone
 * held sideways. Language splits into heading + Continue | the list when the window is short; Welcome scrolls (a column
 * at least the screen's height, so the upright layout is unchanged); both keep clear of a side cutout.
 */
class OnboardingLandscapeTest {
    private val src: String = File(listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory },
        "com/blaze/assistant/ui/space/SpaceOnboarding.kt").readText()
    private fun body(fn: String) = src.substringAfter("private fun $fn(").substringBefore("\n}\n")

    @Test fun languageSplitsWhenShort() {
        val b = body("LanguageStep")
        assertTrue("com.blaze.assistant.ui.common.WindowUi.short()" in b)
        assertTrue("if (short) Row(frame" in b && "list(Modifier.weight(1f).fillMaxHeight())" in b)
        assertTrue("the list scrolls in both layouts", "Column(m.verticalScroll(rememberScrollState()))" in b)
        assertTrue("cutoutSafe()" in b && "readableWidth()" in b)
    }

    @Test fun welcomeScrollsAndKeepsItsUprightLayout() {
        val b = body("Welcome")
        assertTrue("verticalScroll(rememberScrollState()).heightIn(min = minH)" in b)
        assertTrue("BoxWithConstraints(" in b && "cutoutSafe()" in b)
        assertTrue("ArgusLogo(if (short) 140.dp else 260.dp)" in b)
        assertTrue("Spacer(Modifier.weight(0.9f))" in b && "Spacer(Modifier.weight(1f))" in b)
    }

    @Test fun signInKeepsClearOfTheCutout() {
        val b = body("SignIn")
        assertTrue("cutoutSafe()" in b && "readableWidth()" in b && "verticalScroll(rememberScrollState())" in b)
    }
}
