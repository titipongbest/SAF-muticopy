package com.titipong.safmulticopy.engine

import com.titipong.safmulticopy.util.formatDuration
import com.titipong.safmulticopy.util.formatSize
import java.util.TimeZone
import kotlin.math.abs

/** Decides whether an existing copy is the same as its source. Pure logic, unit-tested. */
internal object FileCompare {
    const val TOLERANCE_MS = 2_000L // FAT stores time in 2-second steps
    const val HOUR_MS = 3_600_000L

    fun zoneOffset(at: Long): Long = TimeZone.getDefault().getOffset(at).toLong()

    /**
     * Same moment? Allows FAT's 2-second steps, and a shift of exactly the time-zone offset
     * (± 1 h for daylight saving): FAT/exFAT drives store local time, so some phones read a copy's
     * date back shifted by e.g. 7 hours. Without this, every file would be copied again each time.
     */
    fun sameTime(a: Long, b: Long, zoneOffsetMs: Long = zoneOffset(a)): Boolean {
        val d = abs(a - b)
        if (d <= TOLERANCE_MS) return true
        val tz = abs(zoneOffsetMs)
        return listOf(tz, tz + HOUR_MS, tz - HOUR_MS, HOUR_MS)
            .any { it > 0 && abs(d - it) <= TOLERANCE_MS }
    }

    /** Null when the existing copy is the same; otherwise why it has to be copied again. */
    fun changeReason(
        srcSize: Long,
        srcMtime: Long,
        destSize: Long,
        destMtime: Long,
        zoneOffsetMs: Long = zoneOffset(srcMtime),
    ): String? = when {
        destSize != srcSize -> "size changed (${formatSize(destSize)} → ${formatSize(srcSize)})"
        srcMtime > destMtime + TOLERANCE_MS && !sameTime(srcMtime, destMtime, zoneOffsetMs) ->
            "source is newer by ${formatDuration(srcMtime - destMtime)}"
        else -> null
    }

    /** The copy is unchanged but its date is wrong (e.g. it was copied before dates were kept). */
    fun needsDateFix(srcMtime: Long, destMtime: Long, zoneOffsetMs: Long = zoneOffset(srcMtime)): Boolean =
        srcMtime > 0 && destMtime > 0 && !sameTime(srcMtime, destMtime, zoneOffsetMs)
}
