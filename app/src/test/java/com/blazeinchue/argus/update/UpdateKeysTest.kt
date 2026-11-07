package com.blazeinchue.argus.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.45.15 key rotation: updates pin the private release key; the old public key alone is refused; no key in the repo. */
class UpdateKeysTest {
    @Test fun releaseKeyIsPinned() {
        assertTrue(UpdateKeys.pinned(setOf(UpdateKeys.RELEASE_CERT)))
        assertTrue(UpdateKeys.pinned(setOf(UpdateKeys.RELEASE_CERT.uppercase())))
        assertFalse(UpdateKeys.pinned(setOf(UpdateKeys.BURNED_CERT)))
        assertFalse(UpdateKeys.pinned(emptySet()))
    }

    @Test fun noSigningKeyInTheRepo() {
        fun f(p: String) = listOf(File(p), File("../$p")).firstOrNull { it.exists() }
        val g = (f("app/build.gradle.kts") ?: f("build.gradle.kts"))!!.readText()
        assertFalse(g.contains("storePassword"))
        assertFalse(g.contains(".keystore"))
        // 0.52.4: no key file of any kind under keystore/ (the old name's keystore included).
        val dir = f("keystore")
        assertTrue(dir == null || dir.walk().none { it.isFile && it.extension.lowercase() in setOf("keystore", "jks", "p12", "pfx") })
    }
}
