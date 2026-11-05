package com.argus.assistant

/**
 * 0.52.1: the app's user-facing name in ONE place (Blaze → Argus). Every user-visible string, prompt and label reads it,
 * so a future rename is a one-line change here (plus res/values/strings.xml, the manifest labels and the assets, which
 * can't read Kotlin constants; see docs/RENAME-ARGUS.md).
 *
 * Package rename: the Kotlin package and applicationId are com.argus.assistant now (a new install id on the phone).
 * Deliberately NOT renamed (sync and sign-in depend on them): database / file / pref / folder names, the `blaze` URI
 * scheme the OAuth callback is registered under, gateway URLs and hosts, the X-Blaze-* headers and
 * blaze_* request fields, user agents, repo names and the CLI binary name.
 */
object BrandName {
    const val NAME = "Argus"
    const val UPPER = "ARGUS"
    /** The old name, for reading things saved before the rename (chat speakers, titles). */
    const val OLD = "Blaze"
}
