package com.titipong.safmulticopy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.titipong.safmulticopy.engine.CopyEngine
import com.titipong.safmulticopy.engine.CopyState
import com.titipong.safmulticopy.engine.EventKind
import com.titipong.safmulticopy.engine.Phase
import com.titipong.safmulticopy.engine.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * The copy engine against real files through the Storage Access Framework (debug test provider).
 * Every test checks the actual files in the destination, not just the reported numbers.
 */
@RunWith(AndroidJUnit4::class)
class CopyEngineTest {

    @After fun resetAccess() = T.allFilesAccess(false)

    private fun photos(root: File): File {
        val src = File(root, "Photos").apply { mkdirs() }
        T.write(src, "a.txt", "alpha")
        T.write(src, "sub/b.txt", "bravo")
        T.write(src, "sub/deep/c.txt", "charlie")
        File(src, "emptydir").mkdirs()
        return src
    }

    private fun assertClean(s: CopyState) {
        assertEquals("phase", Phase.DONE, s.phase)
        assertEquals("failed", 0, s.failed)
    }

    // ---------------------------------------------------------------- quick copy basics

    @Test fun quickCopy_copiesFolderIntoItsOwnSubfolder() {
        val base = T.dir("quick1")
        val src = photos(base)
        val dest = File(base, "dest").apply { mkdirs() }

        val s = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest))))

        assertClean(s)
        assertEquals(3, s.copied)
        assertEquals(T.contents(src), T.contents(File(dest, "Photos")))
        assertTrue("empty folder is created", File(dest, "Photos/emptydir").isDirectory)
        assertEquals(setOf("Photos"), dest.list()!!.toSet())
    }

    @Test fun secondRun_skipsEverythingUnchanged() {
        val base = T.dir("quick2")
        val src = photos(base)
        val dest = File(base, "dest").apply { mkdirs() }
        val r = T.req(T.quick(listOf(T.tree(src)), T.tree(dest)))
        T.run(r)

        val s = T.run(r)

        assertClean(s)
        assertEquals(0, s.copied)
        assertEquals(3, s.skipped)
        assertTrue(s.eventsFor(null).all { it.kind == EventKind.SKIPPED })
    }

    @Test fun changedSize_isUpdated_withReason() {
        val base = T.dir("quick3")
        val src = photos(base)
        val dest = File(base, "dest").apply { mkdirs() }
        val r = T.req(T.quick(listOf(T.tree(src)), T.tree(dest)))
        T.run(r)
        T.write(src, "sub/b.txt", "bravo-changed")

        val s = T.run(r)

        assertClean(s)
        assertEquals(1, s.copied)
        assertEquals(2, s.skipped)
        assertEquals("bravo-changed", File(dest, "Photos/sub/b.txt").readText())
        val ev = s.eventsFor(null).first { it.kind == EventKind.UPDATED }
        assertTrue(ev.detail!!, ev.detail!!.startsWith("size changed"))
    }

    @Test fun newerSourceSameSize_isUpdated() {
        val base = T.dir("quick4")
        val src = File(base, "S").apply { mkdirs() }
        val dest = File(base, "dest").apply { mkdirs() }
        val old = System.currentTimeMillis() - 86_400_000L
        T.write(src, "f.txt", "AAAA", old)
        val r = T.req(T.quick(listOf(T.tree(src)), T.tree(dest)))
        T.run(r)
        File(dest, "S/f.txt").setLastModified(old)
        T.write(src, "f.txt", "BBBB", old + 3_600_000L * 3 + 17 * 60_000L) // same size, 3 h 17 m newer

        val s = T.run(r)

        assertEquals(1, s.copied)
        assertEquals("BBBB", File(dest, "S/f.txt").readText())
    }

    @Test fun twoSourcesWithTheSameName_getSeparateFolders() {
        val base = T.dir("quick5")
        val a = File(base, "a/Data").apply { mkdirs() }
        val b = File(base, "b/Data").apply { mkdirs() }
        T.write(a, "one.txt", "1")
        T.write(b, "two.txt", "2")
        val dest = File(base, "dest").apply { mkdirs() }

        val s = T.run(T.req(T.quick(listOf(T.tree(a), T.tree(b)), T.tree(dest))))

        assertClean(s)
        assertEquals("1", File(dest, "Data/one.txt").readText())
        assertEquals("2", File(dest, "Data (2)/two.txt").readText())
    }

    // ---------------------------------------------------------------- merge / mirror / preview

    private fun withExtras(name: String): Triple<File, File, File> {
        val base = T.dir(name)
        val src = photos(base)
        val dest = File(base, "dest").apply { mkdirs() }
        T.write(dest, "Photos/extra.txt", "x")
        T.write(dest, "Photos/oldfolder/inside.txt", "y")
        return Triple(base, src, dest)
    }

    @Test fun merge_keepsExtraFilesInDestination() {
        val (_, src, dest) = withExtras("merge1")

        val s = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest), mirror = false)))

        assertClean(s)
        assertEquals(0, s.deleted)
        assertTrue(File(dest, "Photos/extra.txt").exists())
        assertTrue(File(dest, "Photos/oldfolder/inside.txt").exists())
        assertEquals("alpha", File(dest, "Photos/a.txt").readText())
    }

    @Test fun mirror_deletesWhatIsNotInSource() {
        val (_, src, dest) = withExtras("mirror1")

        val s = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest), mirror = true)))

        assertClean(s)
        assertEquals(2, s.deleted) // one file + one whole folder
        assertEquals(T.contents(src), T.contents(File(dest, "Photos")))
        assertFalse(File(dest, "Photos/oldfolder").exists())
        val deleted = s.eventsFor(null).filter { it.kind == EventKind.DELETED }
        assertEquals(setOf("extra.txt", "oldfolder"), deleted.map { it.name }.toSet())
        assertEquals("whole folder", deleted.first { it.name == "oldfolder" }.detail)
        assertEquals(s.deleteTotal, s.deleteDone)
        assertNull(s.deletingPath)
    }

    @Test fun preview_changesNothing() {
        val (_, src, dest) = withExtras("preview1")
        val before = T.contents(dest)

        val s = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest), mirror = true), dryRun = true))

        assertClean(s)
        assertEquals(before, T.contents(dest))
        assertEquals(3, s.copied) // "would copy"
        assertEquals(2, s.deleted) // "would delete"
        val kinds = s.eventsFor(null).map { it.kind }.toSet()
        assertTrue(kinds.containsAll(setOf(EventKind.WOULD_COPY, EventKind.WOULD_DELETE)))
        assertFalse(kinds.contains(EventKind.COPIED))
    }

    @Test fun mirror_folderInSourceReplacesFileInDestination() {
        val base = T.dir("mismatch1")
        val src = File(base, "M").apply { mkdirs() }
        T.write(src, "x/inside.txt", "dir content")
        val dest = File(base, "dest").apply { mkdirs() }
        T.write(dest, "M/x", "a file named x")

        val merge = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest))))
        assertTrue("merge reports the clash", merge.failed > 0)
        assertTrue(File(dest, "M/x").isFile)

        val mirror = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest), mirror = true)))
        assertClean(mirror)
        assertEquals("dir content", File(dest, "M/x/inside.txt").readText())
    }

    // ---------------------------------------------------------------- excludes

    @Test fun excludes_areNotCopied_andNotDeletedByMirror() {
        val base = T.dir("exclude1")
        val src = File(base, "E").apply { mkdirs() }
        T.write(src, "keep.txt", "k")
        T.write(src, "junk.tmp", "j")
        T.write(src, "Thumbs.db", "t")
        T.write(src, "cache/c.bin", "c")
        val dest = File(base, "dest").apply { mkdirs() }
        T.write(dest, "E/old.tmp", "dest-only, excluded")
        T.write(dest, "E/cache/dest.bin", "dest-only, excluded folder")

        val s = T.run(
            T.req(T.quick(listOf(T.tree(src)), T.tree(dest), mirror = true, xf = listOf("*.tmp", "thumbs.db"), xd = listOf("cache"))),
        )

        assertClean(s)
        assertEquals(1, s.copied)
        assertEquals("k", File(dest, "E/keep.txt").readText())
        assertFalse(File(dest, "E/junk.tmp").exists())
        assertFalse(File(dest, "E/Thumbs.db").exists())
        assertFalse(File(dest, "E/cache/c.bin").exists())
        assertTrue("excluded file in destination is kept", File(dest, "E/old.tmp").exists())
        assertTrue("excluded folder in destination is kept", File(dest, "E/cache/dest.bin").exists())
    }

    // ---------------------------------------------------------------- picked files

    @Test fun pickedFiles_goStraightIntoDestination() {
        val base = T.dir("files1")
        val f1 = T.write(base, "pick/one.txt", "1")
        val f2 = T.write(base, "pick/two.jpg", "22")
        val dest = File(base, "dest").apply { mkdirs() }

        val r = T.req(T.quick(emptyList(), T.tree(dest), files = listOf(T.doc(f1), T.doc(f2))))
        val s = T.run(r)

        assertClean(s)
        assertEquals(mapOf("one.txt" to "1", "two.jpg" to "22"), T.contents(dest))

        val again = T.run(r)
        assertEquals(0, again.copied)
        assertEquals(2, again.skipped)
    }

    // ---------------------------------------------------------------- folder pairs

    @Test fun pair_syncsContentsDirectlyIntoDestination() {
        val base = T.dir("pair1")
        val src = photos(base)
        val dest = File(base, "Backup").apply { mkdirs() }

        val s = T.run(T.req(T.pair("P", T.tree(src), T.tree(dest))))

        assertClean(s)
        assertEquals(T.contents(src), T.contents(dest))
        assertFalse(File(dest, "Photos").exists())
        assertEquals(TaskStatus.OK, s.results.single().status)
    }

    @Test fun queue_runsPairsInOrder_keepsGoingAfterAFailure_andSeparatesFiles() {
        val base = T.dir("queue1")
        val s1 = File(base, "s1").apply { mkdirs() }
        val s3 = File(base, "s3").apply { mkdirs() }
        T.write(s1, "p1.txt", "one")
        T.write(s3, "p3.txt", "three")
        val missing = File(base, "s2-missing") // never created: like an unplugged drive
        val d1 = File(base, "d1").apply { mkdirs() }
        val d2 = File(base, "d2").apply { mkdirs() }
        T.write(d2, "precious.txt", "must survive")
        val d3 = File(base, "d3").apply { mkdirs() }

        val s = T.run(
            T.req(
                T.pair("P1", T.tree(s1), T.tree(d1)),
                T.pair("P2", T.tree(missing), T.tree(d2), mirror = true),
                T.pair("P3", T.tree(s3), T.tree(d3)),
            ),
        )

        assertEquals(listOf("P1", "P2", "P3"), s.results.map { it.id })
        assertEquals(listOf(TaskStatus.OK, TaskStatus.FAILED, TaskStatus.OK), s.results.map { it.status })
        assertEquals("one", File(d1, "p1.txt").readText())
        assertEquals("three", File(d3, "p3.txt").readText())
        assertEquals("a failed source must not wipe the mirror destination", "must survive", File(d2, "precious.txt").readText())
        assertEquals(listOf("p1.txt"), s.eventsFor("P1").map { it.name })
        assertEquals(listOf("p3.txt"), s.eventsFor("P3").map { it.name })
        assertTrue(s.eventsFor(null).isEmpty())
        assertTrue(s.hasErrors)
    }

    // ---------------------------------------------------------------- parallel / stop

    @Test fun manySmallFiles_inParallel_allArrive() {
        val base = T.dir("many1")
        val src = File(base, "Many").apply { mkdirs() }
        repeat(300) { i -> T.write(src, "d${i % 10}/file_$i.txt", "content-$i-" + "x".repeat(i)) }
        val dest8 = File(base, "dest8").apply { mkdirs() }
        val dest1 = File(base, "dest1").apply { mkdirs() }

        val s8 = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest8)), threads = 8))
        val s1 = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest1)), threads = 1))

        assertClean(s8)
        assertClean(s1)
        assertEquals(300, s8.copied)
        assertEquals(T.contents(src), T.contents(File(dest8, "Many")))
        assertEquals(T.contents(src), T.contents(File(dest1, "Many")))
    }

    @Test fun stop_cancelsCleanly_withoutHalfWrittenFiles() {
        val base = T.dir("stop1")
        val src = File(base, "Big").apply { mkdirs() }
        val chunk = "z".repeat(512 * 1024)
        repeat(300) { i -> T.write(src, "f$i.bin", chunk) }
        val dest = File(base, "dest").apply { mkdirs() }

        val last = AtomicReference(CopyState())
        runBlocking {
            val job = launch(Dispatchers.IO) {
                CopyEngine(T.ctx, T.req(T.quick(listOf(T.tree(src)), T.tree(dest)), threads = 1)) { last.set(it) }.run()
            }
            // stop as soon as copying starts
            while (last.get().phase != Phase.COPYING && !last.get().finished) delay(5)
            job.cancel()
            job.join()
        }
        val s = last.get()

        assertEquals(Phase.CANCELLED, s.phase)
        assertTrue("stopped early (${s.copied})", s.copied < 300)
        File(dest, "Big").listFiles()!!.forEach {
            assertEquals("no half-written copy: ${it.name}", chunk.length.toLong(), it.length())
        }
    }

    // ---------------------------------------------------------------- dates

    @Test fun timeZoneShiftedDate_isNotCopiedAgain() {
        val base = T.dir("tz1")
        val src = File(base, "Z").apply { mkdirs() }
        val t0 = (System.currentTimeMillis() - 5 * 86_400_000L) / 1000 * 1000
        T.write(src, "a.jpg", "same", t0)
        T.write(src, "b.jpg", "same", t0)
        val dest = File(base, "dest").apply { mkdirs() }
        val r = T.req(T.quick(listOf(T.tree(src)), T.tree(dest)))
        T.run(r)
        File(dest, "Z/a.jpg").setLastModified(t0 - 3_600_000L) // 1 h off: time-zone / DST shift
        File(dest, "Z/b.jpg").setLastModified(t0 - 20_220_000L) // 5 h 37 m off: a real difference

        val s = T.run(r)

        assertEquals(1, s.skipped)
        assertEquals(1, s.copied)
        val updated = s.eventsFor(null).single { it.kind == EventKind.UPDATED }
        assertEquals("b.jpg", updated.name)
        assertTrue(updated.detail!!, updated.detail!!.startsWith("source is newer"))
    }

    @Test fun keepDates_withAllFilesAccess_copiesGetOriginalDate_andOldCopiesAreFixed() {
        T.allFilesAccess(true)
        val base = T.dir("dates1")
        val src = File(base, "D").apply { mkdirs() }
        val t0 = (System.currentTimeMillis() - 30 * 86_400_000L) / 1000 * 1000
        T.write(src, "new.jpg", "new photo", t0)
        T.write(src, "old.jpg", "old photo", t0)
        val dest = File(base, "dest").apply { mkdirs() }
        T.write(dest, "D/old.jpg", "old photo") // copied earlier, has today's date

        val s = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest)), keepDates = true))

        assertClean(s)
        assertFalse(s.datesUnavailable)
        assertEquals(1, s.copied)
        assertEquals(1, s.datesFixed)
        assertEquals(0, s.datesNotKept)
        assertTrue(abs(File(dest, "D/new.jpg").lastModified() - t0) <= 2_000)
        assertTrue(abs(File(dest, "D/old.jpg").lastModified() - t0) <= 2_000)

        val again = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest)), keepDates = true))
        assertEquals(0, again.copied)
        assertEquals(0, again.datesFixed)
    }

    @Test fun keepDates_withoutAccess_isReported() {
        T.allFilesAccess(false)
        val base = T.dir("dates2")
        val src = File(base, "D").apply { mkdirs() }
        T.write(src, "a.jpg", "a", System.currentTimeMillis() - 86_400_000L)
        val dest = File(base, "dest").apply { mkdirs() }

        val s = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest)), keepDates = true))

        assertEquals(1, s.copied)
        assertTrue(s.datesUnavailable)
    }

    // ---------------------------------------------------------------- USB fallback

    @Test fun storageServiceFailure_fallsBackToDirectWrite_andNextSyncSkips() {
        T.allFilesAccess(true)
        val base = T.dir("usb1")
        val src = File(base, "broken-usb").apply { mkdirs() } // provider refuses files inside it
        T.write(src, "e-1.bin", "one")
        T.write(src, "e-2.bin", "two")
        val dest = File(base, "dest").apply { mkdirs() }
        val r = T.req(T.quick(listOf(T.tree(src)), T.tree(dest)), keepDates = true)

        val s = T.run(r)

        assertClean(s)
        assertEquals(2, s.copied)
        assertEquals(T.contents(src), T.contents(File(dest, "broken-usb")))

        val again = T.run(r)
        assertEquals(0, again.copied)
        assertEquals(2, again.skipped)
    }

    @Test fun storageServiceFailure_withoutAccess_isReportedAsFailed() {
        T.allFilesAccess(false)
        val base = T.dir("usb2")
        val src = File(base, "broken-usb").apply { mkdirs() }
        T.write(src, "e-1.bin", "one")
        val dest = File(base, "dest").apply { mkdirs() }

        val s = T.run(T.req(T.quick(listOf(T.tree(src)), T.tree(dest))))

        assertEquals(1, s.failed)
        val ev = s.eventsFor(null).single { it.kind == EventKind.FAILED }
        assertTrue(ev.detail!!, ev.detail!!.contains("is child of"))
    }
}
