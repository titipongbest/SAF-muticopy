package com.titipong.safmulticopy.engine

import android.net.Uri

enum class Phase { IDLE, SCANNING, DELETING, PREPARING, COPYING, DONE, CANCELLED, FAILED }

enum class Level { HEAD, INFO, OK, WARN, ERROR, DIM }

data class LogLine(val text: String, val level: Level)

data class CopyRequest(
    val sources: List<Uri>,
    val destination: Uri,
    val threads: Int,
    val retries: Int,
    val mirror: Boolean,
    val dryRun: Boolean,
    val showSkipped: Boolean,
    val excludeFiles: List<String>,
    val excludeDirs: List<String>,
)

data class CopyState(
    val phase: Phase = Phase.IDLE,
    val dryRun: Boolean = false,
    val message: String = "",
    val filesToCopy: Int = 0,
    val bytesToCopy: Long = 0,
    val filesDone: Int = 0,
    val bytesDone: Long = 0,
    val scanned: Int = 0,
    val copied: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val deleted: Int = 0,
    val current: List<String> = emptyList(),
    val startedAt: Long = 0,
    val copyStartedAt: Long = 0,
    val finishedAt: Long = 0,
    val log: List<LogLine> = emptyList(),
    val logFile: String? = null,
) {
    val running: Boolean
        get() = phase == Phase.SCANNING || phase == Phase.DELETING ||
            phase == Phase.PREPARING || phase == Phase.COPYING

    val finished: Boolean
        get() = phase == Phase.DONE || phase == Phase.CANCELLED || phase == Phase.FAILED
}
