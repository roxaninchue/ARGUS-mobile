package com.blaze.assistant.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Argus release signing: production credentials stay outside the repo; the legacy public Blaze key is never used. */
class UpdateKeysTest {
    @Test fun releaseKeyIsPinned() {
        assertTrue(UpdateKeys.pinned(setOf(UpdateKeys.RELEASE_CERT)))
        assertTrue(UpdateKeys.pinned(setOf(UpdateKeys.RELEASE_CERT.uppercase())))
        assertFalse(UpdateKeys.pinned(setOf(UpdateKeys.BURNED_CERT)))
        assertFalse(UpdateKeys.pinned(emptySet()))
    }

    @Test fun noProductionSigningKeyOrPasswordInTheRepo() {
        fun f(p: String) = listOf(File(p), File("../$p")).firstOrNull { it.exists() }
        val g = (f("app/build.gradle.kts") ?: f("build.gradle.kts"))!!.readText()

        // Production signing is environment-only, and incomplete configuration must fail closed.
        assertTrue(g.contains("ARGUS_KEYSTORE_FILE"))
        assertTrue(g.contains("ARGUS_KEYSTORE_PASSWORD"))
        assertTrue(g.contains("ARGUS_KEY_ALIAS"))
        assertTrue(g.contains("ARGUS_KEY_PASSWORD"))
        assertTrue(g.contains("argusSigningValues.any { it.isBlank() }"))
        assertTrue(g.contains("storePassword = argusSigningValues[1]"))
        assertTrue(g.contains("keyPassword = argusSigningValues[3]"))
        assertFalse(g.contains("storePassword = \""))
        assertFalse(g.contains("keyPassword = \""))

        // The legacy Blaze keystore may remain as pre-existing public/burned material, but this build must not use it.
        assertFalse(g.contains("blaze.keystore"))

        // The new private Argus keystore must never be checked into the repository.
        assertTrue(f("keystore/argus-release.p12") == null)
        assertTrue(f("argus-release.p12") == null)
    }
}