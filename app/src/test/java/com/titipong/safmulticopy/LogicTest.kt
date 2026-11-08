package com.titipong.safmulticopy

import com.titipong.safmulticopy.engine.FileCompare
import com.titipong.safmulticopy.engine.GlobSet
import com.titipong.safmulticopy.engine.Saf
import com.titipong.safmulticopy.engine.TaskResult
import com.titipong.safmulticopy.engine.TaskStatus
import com.titipong.safmulticopy.util.formatDuration
import com.titipong.safmulticopy.util.formatSize
import com.titipong.safmulticopy.util.resultSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic, runs on the JVM (no device). */
class LogicTest {
    private val hour = 3_600_000L
    private val bangkok = 7 * hour
    private val t = 1_730_000_000_000L

    // ---------------------------------------------------------------- same file?

    @Test fun sameTime_withinFatTwoSeconds() {
        assertTrue(FileCompare.sameTime(t, t + 1_999, bangkok))
        assertTrue(FileCompare.sameTime(t, t - 2_000, bangkok))
        assertFalse(FileCompare.sameTime(t, t + 2_500, bangkok))
    }

    @Test fun sameTime_toleratesTimeZoneShift() {
        // FAT drive read back 7 h off in Bangkok
        assertTrue(FileCompare.sameTime(t, t - bangkok, bangkok))
        assertTrue(FileCompare.sameTime(t, t + bangkok + 1_000, bangkok))
        // daylight saving (1 h) anywhere
        assertTrue(FileCompare.sameTime(t, t - hour, 0))
        assertTrue(FileCompare.sameTime(t, t - (bangkok + hour), bangkok))
    }

    @Test fun sameTime_otherDifferencesAreReal() {
        assertFalse(FileCompare.sameTime(t, t - 5 * hour, bangkok))
        assertFalse(FileCompare.sameTime(t, t - bangkok - 60_000, bangkok))
        assertFalse(FileCompare.sameTime(t, t - 2 * hour, 0))
    }

    @Test fun changeReason_sameFileIsNull() {
        assertNull(FileCompare.changeReason(100, t, 100, t, bangkok))
        assertNull(FileCompare.changeReason(100, t, 100, t + hour * 5, bangkok)) // copy is newer
        assertNull(FileCompare.changeReason(100, t, 100, t - bangkok, bangkok)) // zone shift
    }

    @Test fun changeReason_sizeOrNewerSource() {
        val size = FileCompare.changeReason(200, t, 100, t, bangkok)
        assertNotNull(size)
        assertTrue(size!!.startsWith("size changed"))
        val newer = FileCompare.changeReason(100, t + 10 * 60_000, 100, t, bangkok)
        assertNotNull(newer)
        assertTrue(newer!!, newer.startsWith("source is newer by 10:00"))
    }

    @Test fun needsDateFix_onlyWhenReallyDifferent() {
        assertFalse(FileCompare.needsDateFix(t, t + 1_000, bangkok))
        assertFalse(FileCompare.needsDateFix(t, t - bangkok, bangkok))
        assertTrue(FileCompare.needsDateFix(t, t + 3 * 86_400_000L, bangkok))
        assertFalse(FileCompare.needsDateFix(0, t, bangkok)) // unknown source date
    }

    // ---------------------------------------------------------------- excludes

    @Test fun globs_matchNamesCaseInsensitive() {
        val g = GlobSet(GlobSet.parse("*.tmp; Thumbs.db ; .trash*,  cache"))
        assertTrue(g.matches("a.TMP"))
        assertTrue(g.matches("thumbs.db"))
        assertTrue(g.matches(".trash-1000"))
        assertTrue(g.matches("Cache"))
        assertFalse(g.matches("a.tmpx"))
        assertFalse(g.matches("photo.jpg"))
        assertFalse(g.matches("mycache"))
    }

    @Test fun globs_questionMarkAndRegexCharacters() {
        val g = GlobSet(GlobSet.parse("IMG_????.jpg; (1)*"))
        assertTrue(g.matches("img_0001.jpg"))
        assertFalse(g.matches("IMG_01.jpg"))
        assertTrue(g.matches("(1) copy.txt"))
        assertTrue(GlobSet.parse("  ;  ").isEmpty())
    }

    // ---------------------------------------------------------------- paths

    @Test fun paths_parentAndAncestors() {
        assertEquals("a/b", Saf.parentKey("a/b/c.txt"))
        assertEquals("", Saf.parentKey("c.txt"))
        assertTrue(Saf.underAny("a/b/c.txt", setOf("a")))
        assertTrue(Saf.underAny("a/b/c.txt", setOf("a/b")))
        assertFalse(Saf.underAny("ab/c.txt", setOf("a")))
        assertFalse(Saf.underAny("a", setOf("a")))
        assertFalse(Saf.underAny("x/y", emptySet()))
    }

    // ---------------------------------------------------------------- formatting

    @Test fun formatting() {
        assertEquals("512 B", formatSize(512))
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.5 MB", formatSize(1_572_864))
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test fun summaries() {
        val ok = TaskResult("p", "Pair", TaskStatus.OK, 3, 10, 0, 0, 100, 1000)
        assertEquals("copied 3 · unchanged 10", resultSummary(ok, false))
        assertEquals("would copy 3 · unchanged 10", resultSummary(ok, true))
        val err = ok.copy(status = TaskStatus.ERRORS, failed = 2, deleted = 4)
        assertEquals("copied 3 · unchanged 10 · failed 2 · deleted 4", resultSummary(err, false))
        val failed = ok.copy(status = TaskStatus.FAILED, error = "drive unplugged")
        assertEquals("drive unplugged", resultSummary(failed, false))
    }
}
