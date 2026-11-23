package com.blaze.assistant.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import java.io.File
import java.util.Base64

class TermLogicTest {
    @Test fun termuxKeyRows() {
        assertEquals(listOf("ESC", "/", "―", "HOME", "↑", "END", "PGUP"), TermLogic.ROWS[0].map { it.label })
        assertEquals(listOf("⇥", "CTRL", "ALT", "←", "↓", "→", "PGDN"), TermLogic.ROWS[1].map { it.label })
        assertTrue(TermLogic.ROWS.flatten().all { it.code != 0 || it.text.isNotEmpty() || it.toggle.isNotEmpty() })
    }

    @Test fun envsPreferLinux() {
        assertEquals(listOf("alpine", "ubuntu", "phone"), TermLogic.envs(setOf("ubuntu", "alpine")).map { it.id })
        assertEquals(listOf("phone"), TermLogic.envs(emptySet()).map { it.id })
        assertEquals("alpine", TermLogic.pick(null, setOf("alpine")).id)
        assertEquals("debian", TermLogic.pick("debian", setOf("alpine", "debian")).id)
        assertEquals("phone", TermLogic.pick("alpine", emptySet()).id)
    }

    @Test fun distrosPerCpu() {
        assertEquals("aarch64", TermLogic.cpu("arm64-v8a")); assertEquals("arm", TermLogic.cpu("armeabi-v7a")); assertEquals(null, TermLogic.cpu("x86_64"))
        assertEquals(listOf("alpine", "ubuntu", "debian", "archlinux", "fedora", "manjaro"), TermLogic.installable("arm64-v8a").map { it.id })
        assertEquals(listOf("alpine", "ubuntu", "debian", "archlinux"), TermLogic.installable("armeabi-v7a").map { it.id })
        assertEquals(listOf("alpine"), TermLogic.installable("x86_64").map { it.id })
        TermLogic.DISTROS.flatMap { it.urls.values }.forEach { assertTrue(it, it.startsWith("https://") && it.endsWith(".tar.xz")) }
        assertEquals("Ubuntu 24.04", TermLogic.distro("ubuntu")!!.env().label)
        assertEquals("Alpine Linux", TermLogic.ALPINE.label)
        assertTrue(TermLogic.welcome(TermLogic.distro("archlinux")!!.env()).contains("pacman -S <package>"))
    }

    @Test fun paxRecords() {
        val long = "usr/share/" + "x".repeat(120)
        fun rec(k: String, v: String): String { val body = " $k=$v\n"; var n = body.toByteArray().size + 2; while ("$n$body".toByteArray().size != n) n++; return "$n$body" }
        assertEquals(listOf("path" to long, "linkpath" to "../bin/é"), TermLogic.pax(rec("path", long) + rec("linkpath", "../bin/é")))
        assertEquals(emptyList<Pair<String, String>>(), TermLogic.pax("garbage"))
    }

    @Test fun phoneShell() {
        assertEquals("/lib/libbusybox.so" to listOf("ash", "-l"), TermLogic.phoneArgs("/lib/libbusybox.so"))
        assertEquals("/system/bin/sh" to listOf("sh", "-l"), TermLogic.phoneArgs(null))
    }

    @Test fun envForcesColourTerm() {
        val e = TermLogic.envArray(mapOf("HOME" to "/h", "TERM" to "dumb"), mapOf("PATH" to "/bin"))
        assertTrue("TERM=xterm-256color" in e); assertTrue("HOME=/h" in e); assertTrue("PATH=/bin" in e)
        assertTrue(e.none { it == "TERM=dumb" })
    }

    @Test fun tabsAndZoom() {
        assertEquals("1 alpine", TermLogic.tabLabel(1, TermLogic.ALPINE, null, false))
        assertEquals("2 vim", TermLogic.tabLabel(2, TermLogic.ALPINE, "vim", false))
        assertEquals("3 phone sh ✕", TermLogic.tabLabel(3, TermLogic.PHONE, "", true))
        assertEquals(TermLogic.MAX_FONT, TermLogic.zoom(60, 2f)); assertEquals(TermLogic.MIN_FONT, TermLogic.zoom(15, 0.2f))
        assertTrue(TermLogic.welcome(TermLogic.ALPINE).contains("apk add <package>"))
    }

    @Test fun thePickedLinuxIsTheAgentsOnceInstalled() {
        // owner 2026-11-20: the onboarding distro step
        assertEquals("debian", TermLogic.defaultDistro(setOf("debian"), "debian"))
        assertEquals("debian", TermLogic.defaultDistro(setOf("alpine", "debian"), "debian"))
        assertEquals("not installed yet: as before", "alpine", TermLogic.defaultDistro(setOf("alpine"), "debian"))
        assertEquals("alpine", TermLogic.defaultDistro(emptySet(), "debian"))
        assertEquals("ubuntu", TermLogic.defaultDistro(setOf("ubuntu"), ""))
        assertEquals("debian", TermLogic.setupDistro("debian", listOf("alpine", "debian")))
        assertEquals("not built for this CPU", "alpine", TermLogic.setupDistro("fedora", listOf("alpine", "debian")))
        assertEquals("alpine", TermLogic.setupDistro("", listOf("alpine", "debian")))
        for (d in TermLogic.DISTROS) assertTrue("${d.id} has a logo", DistroLogos.of(d.id) != null)
    }
}

/** fix/chat-picture-load: a picture fetched with http_request saves as a picture the chat and the phone can open. */
class HttpBodyTest {
    // A real 1x1 PNG, including bytes that are not valid UTF-8 (0x89, 0x9C, ...), which reading as text destroyed.
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
    private val b64 = Base64.getEncoder().encodeToString(png)

    @Test fun binaryPictureIsKeptByteForByte() {
        val s = HttpBody.toSave("~/shot.png", png, "image/png")
        assertArrayEquals(png, s.bytes)
        assertEquals("", s.note)
        // The old path (read as UTF-8 text, written back) did not survive the round trip.
        assertFalse(png.contentEquals(String(png, Charsets.UTF_8).toByteArray()))
    }

    @Test fun pictureInsideJsonIsSavedAsThePicture() {
        val raw = """{"success":true,"image":"$b64","mime_type":"image/png","width":1,"height":1}"""
        val s = HttpBody.toSave("~/argus-cloud-screenshot.png", raw.toByteArray(), "application/json")
        assertArrayEquals(png, s.bytes)
        assertTrue(s.note.contains("picture"))
    }

    @Test fun dataUrlIsDecodedToo() {
        val raw = """{"image":"data:image/png;base64,$b64"}"""
        assertArrayEquals(png, HttpBody.toSave("x.png", raw.toByteArray(), "application/json").bytes)
    }

    @Test fun jsonWithoutAPictureWarnsInsteadOfPretending() {
        val raw = """{"success":true,"image":"/api/storage/ws/2026-11-23_abc.png"}"""
        val s = HttpBody.toSave("shot.png", raw.toByteArray(), "application/json")
        assertArrayEquals(raw.toByteArray(), s.bytes)
        assertTrue(s.note.contains("not a picture"))
    }

    @Test fun nonPictureNamesAreSavedAsIs() {
        val raw = """{"image":"$b64"}""".toByteArray()
        val s = HttpBody.toSave("reply.json", raw, "application/json")
        assertArrayEquals(raw, s.bytes)
        assertEquals("", s.note)
    }

    @Test fun binaryBodiesAreNotDumpedIntoTheChat() {
        assertNotNull(HttpBody.standIn(png, "image/png"))
        assertNotNull(HttpBody.standIn(png, ""))
        assertNull(HttpBody.standIn("""{"ok":true}""".toByteArray(), "application/json; charset=utf-8"))
        assertNull(HttpBody.standIn("hello".toByteArray(), "text/plain"))
    }

    @Test fun recognisesCommonPictureFormats() {
        assertTrue(HttpBody.looksLikeImage(png))
        assertTrue(HttpBody.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertTrue(HttpBody.looksLikeImage("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(HttpBody.looksLikeImage("""{"image":"x"}""".toByteArray()))
    }

    @Test fun devToolsWritesBytesNotText() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val src = root.walk().first { it.name == "DevTools.kt" }.readText()
        val http = src.substringAfter("private fun http(").substringBefore("private fun download(")
        assertTrue(http.contains("readBytes()"))
        assertTrue(http.contains("HttpBody.toSave("))
        assertFalse(http.contains("f.writeText(text)"))
    }
}
