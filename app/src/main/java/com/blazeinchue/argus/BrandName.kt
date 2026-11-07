package com.blazeinchue.argus

/**
 * 0.52.1: the app's user-facing name in ONE place. Every user-visible string, prompt and label reads it, so a future
 * rename is a one-line change here (plus res/values/strings.xml, the manifest labels and the assets, which can't read
 * Kotlin constants; see docs/RENAME-ARGUS.md).
 *
 * 0.52.4: the old name is gone everywhere (applicationId and Kotlin package com.blazeinchue.argus, databases, prefs,
 * files, headers, deep links). Only names of things outside this repo keep it (hosts, other repos, the CLI's files);
 * docs/RENAME-ARGUS.md lists them.
 */
object BrandName {
    const val NAME = "Argus"
    const val UPPER = "ARGUS"
}
