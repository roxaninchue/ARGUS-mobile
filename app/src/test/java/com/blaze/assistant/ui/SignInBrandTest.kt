package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 2026-11-15: the onboarding / Settings › Account sign-in buttons show the real Google G and GitHub mark ([SignInBrand],
 * res/drawable/logo_google_g.xml and logo_github_mark.xml), in Google's button style.
 */
class SignInBrandTest {
    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private fun src(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun vector(name: String): Element {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(File(res(), "drawable/$name.xml")).documentElement
    }
    private fun paths(v: Element) = (0 until v.getElementsByTagName("path").length).map { v.getElementsByTagName("path").item(it) as Element }

    @Test fun labelsFollowTheBrands() {
        assertEquals("Continue with Google", SignInBrand.label("google"))
        assertEquals("Continue with GitHub", SignInBrand.label("github"))
        assertEquals(listOf("google", "github"), SignInBrand.PROVIDERS)
        assertTrue(SignInBrand.PROVIDERS.all(SignInBrand::hasLogo))
    }

    @Test fun googleButtonColoursAndTheGIsNeverTinted() {
        assertEquals(SignInBrand.Style(0xFFFFFFFFL, 0xFF747775L, 0xFF1F1F1FL), SignInBrand.style(dark = false))
        assertEquals(SignInBrand.Style(0xFF131314L, 0xFF8E918FL, 0xFFE3E3E3L), SignInBrand.style(dark = true))
        assertNull(SignInBrand.logoTint("google", true)); assertNull(SignInBrand.logoTint("google", false))
        // GitHub's one-colour mark: white on dark, #24292F on light
        assertEquals(0xFFFFFFFFL, SignInBrand.logoTint("github", true))
        assertEquals(0xFF24292FL, SignInBrand.logoTint("github", false))
        assertTrue("never smaller than Google's 40 dp button", SignInBrand.HEIGHT_DP >= 40)
    }

    @Test fun theGoogleGIsTheFourColourMark() {
        val v = vector("logo_google_g")
        assertEquals("vector", v.tagName)
        assertEquals("48", v.getAttributeNS(ns, "viewportWidth")); assertEquals("48", v.getAttributeNS(ns, "viewportHeight"))
        assertEquals(listOf("#FFEA4335", "#FF4285F4", "#FFFBBC05", "#FF34A853"), paths(v).map { it.getAttributeNS(ns, "fillColor") })
        for (p in paths(v)) assertTrue(p.getAttributeNS(ns, "pathData").startsWith("M"))
    }

    @Test fun theGitHubMarkIsOneEvenOddPath() {
        val v = vector("logo_github_mark")
        assertEquals("98", v.getAttributeNS(ns, "viewportWidth")); assertEquals("96", v.getAttributeNS(ns, "viewportHeight"))
        val p = paths(v).single()
        assertEquals("evenOdd", p.getAttributeNS(ns, "fillType"))
        assertTrue(p.getAttributeNS(ns, "pathData").startsWith("M48.854,0C21.839,0 0,22 0,49.217"))
    }

    @Test fun theAccountPageDrawsTheBrandButtons() {
        val page = File(src(), "com/blaze/assistant/ui/AccountPage.kt").readText()
        assertTrue("for (p in SignInBrand.PROVIDERS) if (v.login[p] == true) {" in page)
        assertTrue("ProviderButton(p) { err = BlazeAccount.signInWith(ctx, p) }" in page)
        assertTrue("R.drawable.logo_google_g" in page && "R.drawable.logo_github_mark" in page)
        assertTrue("the plain text button is gone", "AccountButton(\"Continue with \${BlazeAccount.title(p)}\"" !in page)
    }
}
