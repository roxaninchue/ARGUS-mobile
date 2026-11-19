package com.argus.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class AnyFileTest {
    @Test fun codeIsTextAndArchivesAreZips() {
        assertTrue(AnyFile.isText("Agent.kt", "")); assertTrue(AnyFile.isText("build.gradle", "application/octet-stream"))
        assertTrue(AnyFile.isText("Dockerfile", "")); assertFalse(AnyFile.isText("app.apk", "application/vnd.android.package-archive"))
        assertTrue(AnyFile.isZip("blaze-2705.zip", "application/zip")); assertTrue(AnyFile.isZip("app-release-unsigned.apk", ""))
        assertTrue(AnyFile.isZip("DreadKnight.mcaddon", "")); assertFalse(AnyFile.isZip("song.m4a", "audio/mp4"))
    }

    @Test fun savedNamesNeverCollideOrEscape() {
        val dir = Files.createTempDirectory("any").toFile()
        val a = AnyFile.target(dir, "../../etc/passwd")
        assertEquals(dir, a.parentFile); assertEquals("passwd", a.name)
        a.writeText("x")
        assertEquals("passwd (2)", AnyFile.target(dir, "passwd").name)
        AnyFile.target(dir, "a.zip").writeText("x")
        assertEquals("a (2).zip", AnyFile.target(dir, "a.zip").name)
    }

    @Test fun copyStopsAtTheCap() {
        assertEquals(5L, AnyFile.copyCapped(ByteArrayInputStream(ByteArray(5)), ByteArrayOutputStream(), 10))
        assertEquals(-1L, AnyFile.copyCapped(ByteArrayInputStream(ByteArray(11)), ByteArrayOutputStream(), 10))
    }

    @Test fun noteTellsTheModelWhereItIs() {
        val n = AnyFile.note("blaze.zip", "application/zip", 59_441_304, "~/attachments/blaze.zip", AnyFile.zipListing(listOf("app-release-unsigned.apk" to 61_121_548L)))
        assertTrue(n.contains("~/attachments/blaze.zip") && n.contains("56.7 MB") && n.contains("1 entry") && n.contains("app-release-unsigned.apk  58.3 MB"))
        assertTrue(AnyFile.zipListing((1..70).map { "f$it" to 1L }).contains("10 more"))
    }
}
