package com.blaze.assistant.ui

/**
 * 2026-11-15: the "Continue with Google" / "Continue with GitHub" buttons (onboarding's sign-in step and Settings ›
 * Account, both drawn by [AccountPage]) carry the real brand marks: res/drawable/logo_google_g.xml (the four-colour G,
 * from Google's Sign in with Google asset) and res/drawable/logo_github_mark.xml (GitHub's mark, github-mark.svg).
 *
 * Google's branding rules for the button: the G is never recoloured; a light button is #FFFFFF with a 1 dp #747775
 * outline and #1F1F1F text, a dark one #131314 with a #8E918F outline and #E3E3E3 text; Roboto Medium 14 sp, the G
 * 20 dp at 12 dp from the start with 10 dp to the text; "Continue with Google" is one of the approved labels. GitHub
 * has no button spec: the same button, with its one-colour mark in white on dark and #24292F on light. Pure, so the
 * rules are unit tested (SignInBrandTest).
 */
object SignInBrand {
    const val GOOGLE = "google"
    const val GITHUB = "github"
    /** The providers in the order the account page offers them. */
    val PROVIDERS = listOf(GOOGLE, GITHUB)

    const val HEIGHT_DP = 44
    const val LOGO_DP = 20
    const val START_DP = 12
    const val GAP_DP = 10
    const val TEXT_SP = 14
    const val OUTLINE_DP = 1

    /** Colours as ARGB. */
    data class Style(val fill: Long, val outline: Long, val text: Long)

    val LIGHT = Style(fill = 0xFFFFFFFFL, outline = 0xFF747775L, text = 0xFF1F1F1FL)
    val DARK = Style(fill = 0xFF131314L, outline = 0xFF8E918FL, text = 0xFFE3E3E3L)

    fun style(dark: Boolean): Style = if (dark) DARK else LIGHT

    /** The button's words: Google's approved "Continue with Google", the same wording for GitHub. */
    fun label(provider: String): String = "Continue with " + name(provider)

    fun name(provider: String): String = when (provider) { GOOGLE -> "Google"; GITHUB -> "GitHub"; else -> provider }

    /** The tint for the provider's mark, or null to draw it in its own colours (Google's G is never tinted). */
    fun logoTint(provider: String, dark: Boolean): Long? = when (provider) {
        GITHUB -> if (dark) 0xFFFFFFFFL else 0xFF24292FL
        else -> null
    }

    /** True when the provider has a brand mark drawable (anything else gets a plain text button). */
    fun hasLogo(provider: String): Boolean = provider == GOOGLE || provider == GITHUB
}
