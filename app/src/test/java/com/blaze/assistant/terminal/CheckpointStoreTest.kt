package com.blaze.assistant.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Terminal checkpoints: snapshot, incremental reuse, dedupe, diff, rollback (and undoing a rollback), excludes, pruning. */
class CheckpointStoreTest {
    private val tmp: File = Files.createTempDirectory("cpstore").toFile()
    private val home = File(tmp, "home").apply { mkdirs() }
    private var now = 1_000L
    private fun store(maxAuto: Int = 25, maxFile: Long = 1 shl 20) = CheckpointStore(File(tmp, "checkpoints"), maxAuto = maxAuto, maxFileBytes = maxFile, clock = { now++ })
    private val scope = CheckpointStore.Scope("home", home, setOf("cache"))
    private fun write(p: String, s: String) = File(home, p).apply { parentFile.mkdirs(); writeText(s) }

    @Test fun rollbackUndoesEditsNewFilesAndDeletes() {
        write("app/main.py", "print('ok')\n"); write("app/data.txt", "keep"); write("notes.md", "v1")
        val st = store()
        val cp = st.snapshot(listOf(scope), "before refactor").checkpoint
        // The agent "breaks" things.
        write("app/main.py", "print('broken'\n"); File(home, "app/data.txt").delete(); write("junk/out.log", "x"); write("notes.md", "v2")
        val d = st.diff(cp.id, listOf(scope))
        assertEquals(listOf("app/main.py", "notes.md"), d.changed)
        assertEquals(listOf("junk/out.log"), d.added)
        assertEquals(listOf("app/data.txt"), d.deleted)

        val r = st.restore(cp.id, listOf(scope))
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertEquals("print('ok')\n", File(home, "app/main.py").readText())
        assertEquals("keep", File(home, "app/data.txt").readText())
        assertEquals("v1", File(home, "notes.md").readText())
        assertFalse(File(home, "junk/out.log").exists()); assertFalse(File(home, "junk").exists())
        assertTrue(st.diff(cp.id, listOf(scope)).empty)

        // The rollback itself can be undone.
        val safety = r.safety; assertNotNull(safety)
        st.restore(safety!!.id, listOf(scope), safety = false)
        assertEquals("print('broken'\n", File(home, "app/main.py").readText())
        assertTrue(File(home, "junk/out.log").exists()); assertFalse(File(home, "app/data.txt").exists())
    }

    @Test fun sameStateIsNotTakenTwiceAndContentIsShared() {
        write("a.txt", "same"); write("b.txt", "same")
        val st = store()
        val one = st.snapshot(listOf(scope), auto = true)
        assertTrue(one.created)
        val two = st.snapshot(listOf(scope), auto = true)
        assertFalse(two.created); assertEquals(one.checkpoint.id, two.checkpoint.id)
        write("c.txt", "new")
        val three = st.snapshot(listOf(scope), auto = true)
        assertTrue(three.created); assertNotEquals(one.checkpoint.id, three.checkpoint.id)
        // "same" stored once, "new" once.
        val objs = File(tmp, "checkpoints/objects").walkTopDown().count { it.isFile }
        assertEquals(2, objs)
        assertEquals(listOf(three.checkpoint.id, one.checkpoint.id), st.list().map { it.id })
    }

    @Test fun excludedFoldersAndBigFilesAreLeftAlone() {
        write("cache/blob", "c1"); write("small.txt", "s"); File(home, "big.bin").writeBytes(ByteArray(2048))
        val st = store(maxFile = 1024)
        val cp = st.snapshot(listOf(scope)).checkpoint
        assertEquals(1, cp.skipped)
        write("cache/blob", "c2"); write("cache/more", "m"); File(home, "big.bin").writeBytes(ByteArray(4096)); write("small.txt", "S")
        st.restore(cp.id, listOf(scope))
        assertEquals("s", File(home, "small.txt").readText())
        assertEquals("c2", File(home, "cache/blob").readText())   // excluded: untouched
        assertTrue(File(home, "cache/more").exists())
        assertEquals(4096L, File(home, "big.bin").length())        // too big to keep: untouched, never deleted
    }

    @Test fun restoreOnlyNamedPaths() {
        write("a/x.txt", "x1"); write("b/y.txt", "y1")
        val st = store()
        val cp = st.snapshot(listOf(scope)).checkpoint
        write("a/x.txt", "x2"); write("b/y.txt", "y2"); write("a/new.txt", "n")
        st.restore(cp.id, listOf(scope), paths = listOf("a"))
        assertEquals("x1", File(home, "a/x.txt").readText()); assertFalse(File(home, "a/new.txt").exists())
        assertEquals("y2", File(home, "b/y.txt").readText())
    }

    @Test fun execBitAndSymlinksComeBack() {
        val sh = write("run.sh", "#!/bin/sh\necho hi\n").apply { setExecutable(true) }
        Files.createSymbolicLink(File(home, "link").toPath(), File("run.sh").toPath())
        val st = store()
        val cp = st.snapshot(listOf(scope)).checkpoint
        sh.delete(); File(home, "link").delete()
        st.restore(cp.id, listOf(scope))
        assertTrue(File(home, "run.sh").canExecute())
        assertTrue(Files.isSymbolicLink(File(home, "link").toPath()))
        assertEquals("run.sh", Files.readSymbolicLink(File(home, "link").toPath()).toString())
    }

    @Test fun oldAutomaticCheckpointsArePrunedButSavedOnesStay() {
        val st = store(maxAuto = 3)
        write("f", "0"); val saved = st.snapshot(listOf(scope), "keep me").checkpoint
        for (i in 1..6) { write("f", "$i"); st.snapshot(listOf(scope), auto = true) }
        val l = st.list()
        assertEquals(3, l.count { it.auto }); assertTrue(l.any { it.id == saved.id })
        // Pruned content is collected; the saved one still restores.
        st.restore(saved.id, listOf(scope), safety = false)
        assertEquals("0", File(home, "f").readText())
    }

    @Test fun incrementalSnapshotDoesNotRereadUnchangedFiles() {
        write("a.txt", "A").setLastModified(System.currentTimeMillis() - 60_000)   // clearly older than the index
        val st = store()
        st.snapshot(listOf(scope), auto = true)
        // Same size and mtime: the hash is reused, so a file that can't be read now still keeps its old entry.
        val f = File(home, "a.txt"); f.setReadable(false)
        write("b.txt", "B")
        val s2 = st.snapshot(listOf(scope), auto = true)
        f.setReadable(true)
        assertTrue(st.readIndex(s2.checkpoint.id, "home").any { it.path == "a.txt" })
    }

    @Test fun sameSizeEditInTheSameSecondIsSeen() {
        write("x", "a1")
        val st = store()
        val cp = st.snapshot(listOf(scope)).checkpoint
        val f = write("x", "a2"); f.setLastModified(st.readIndex(cp.id, "home").first { it.path == "x" }.mtime)   // same size, same mtime
        assertEquals(listOf("x"), st.diff(cp.id, listOf(scope)).changed)
        st.restore(cp.id, listOf(scope), safety = false)
        assertEquals("a1", File(home, "x").readText())
    }

    @Test fun cacheFoldersAreSkippedAtAnyDepth() {
        val sc = CheckpointStore.Scope("home", home, emptySet(), setOf("node_modules"))
        write("web/node_modules/x/index.js", "dep"); write("web/app.js", "a")
        val st = store()
        val cp = st.snapshot(listOf(sc)).checkpoint
        assertTrue(st.readIndex(cp.id, "home").none { it.path.contains("node_modules") })
        write("web/node_modules/y/index.js", "dep2"); write("web/app.js", "b")
        assertEquals(listOf("web/app.js"), st.diff(cp.id, listOf(sc)).changed)
        assertTrue(st.diff(cp.id, listOf(sc)).added.isEmpty())
        st.restore(cp.id, listOf(sc), safety = false)
        assertTrue(File(home, "web/node_modules/y/index.js").exists())   // never touched
        assertEquals("a", File(home, "web/app.js").readText())
    }

    @Test fun oneSnapshotAddsAtMostItsBudget() {
        write("a", "x".repeat(600)); write("b", "y".repeat(600))
        val st = CheckpointStore(File(tmp, "checkpoints"), maxNewBytes = 1000)
        val cp = st.snapshot(listOf(scope)).checkpoint
        assertEquals(1, cp.files); assertEquals(1, cp.skipped)
    }

    @Test fun escapingRoundTrips() {
        val s = "a\tb\nc\\d"
        assertEquals(s, CheckpointStore.unesc(CheckpointStore.esc(s)))
        write("we\tird name.txt", "w")
        val st = store(); val cp = st.snapshot(listOf(scope)).checkpoint
        File(home, "we\tird name.txt").delete()
        st.restore(cp.id, listOf(scope), safety = false)
        assertEquals("w", File(home, "we\tird name.txt").readText())
    }
}
