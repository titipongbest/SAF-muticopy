package com.titipong.safmulticopy.engine

import android.net.Uri

enum class Phase { IDLE, SCANNING, DELETING, PREPARING, COPYING, DONE, CANCELLED, FAILED }

enum class Level { HEAD, INFO, OK, WARN, ERROR, DIM }

enum class TaskStatus { OK, ERRORS, FAILED, CANCELLED }

/** One progress bar = one parallel copy worker. [name] is null while the worker is idle. */
data class SlotState(
    val index: Int,
    val name: String?,
    val size: Long,
    val done: Long,
    val retry: Int,
    val update: Boolean,
) {
    val fraction: Float
        get() = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f
}

enum class EventKind {
    COPIED, UPDATED, SKIPPED, FAILED, DELETED, DATE_FIXED,
    WOULD_COPY, WOULD_UPDATE, WOULD_DELETE, WOULD_FIX_DATE,
}

/** A finished file shown in the "Recent files" list. */
data class FileEvent(
    val id: Long,
    val kind: EventKind,
    val name: String,
    val path: String,
    val size: Long,
    val detail: String? = null,
)

/** Key for quick-copy events in [CopyState.eventsByTask] (folder pairs use their id). */
const val QUICK_KEY = "quick"

/**
 * One unit of work in the queue.
 *  - Quick copy: [intoSubfolders] = true — each source folder becomes a sub-folder of the
 *    destination, picked [files] go straight into it.
 *  - Folder pair: [intoSubfolders] = false — the contents of the single source are synced
 *    directly into the destination folder.
 */
data class CopyTask(
    val id: String?,
    val label: String,
    val sources: List<Uri>,
    val files: List<Uri>,
    val destination: Uri,
    val intoSubfolders: Boolean,
    val mirror: Boolean,
    val excludeFiles: List<String>,
    val excludeDirs: List<String>,
)

/** Tasks run one after another, in order. */
data class CopyRequest(
    val tasks: List<CopyTask>,
    val threads: Int,
    val retries: Int,
    val dryRun: Boolean,
    val showSkipped: Boolean,
    /** Give copies the original's modified date (needs All files access). */
    val keepDates: Boolean = true,
)

data class TaskResult(
    val id: String?,
    val label: String,
    val status: TaskStatus,
    val copied: Int,
    val skipped: Int,
    val failed: Int,
    val deleted: Int,
    val bytes: Long,
    val durationMs: Long,
    val error: String? = null,
)

data class CopyState(
    val phase: Phase = Phase.IDLE,
    val stopping: Boolean = false,
    val dryRun: Boolean = false,
    val message: String = "",
    // Progress of the task that is running now (totals of all tasks once finished).
    val filesToCopy: Int = 0,
    val bytesToCopy: Long = 0,
    val filesDone: Int = 0,
    val bytesDone: Long = 0,
    val scanned: Int = 0,
    // Totals across all tasks so far.
    val copied: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val deleted: Int = 0,
    /** Existing copies whose date was set back to the original's. */
    val datesFixed: Int = 0,
    /** Copies whose original date could not be set. */
    val datesNotKept: Int = 0,
    /** Keep original dates is on but All files access is not allowed. */
    val datesUnavailable: Boolean = false,
    val slots: List<SlotState> = emptyList(),
    // Mirror: what is being deleted right now (this task)
    val deletingPath: String? = null,
    val deletingIsDir: Boolean = false,
    val deleteDone: Int = 0,
    val deleteTotal: Int = 0,
    val startedAt: Long = 0,
    val copyStartedAt: Long = 0,
    val finishedAt: Long = 0,
    // Queue
    val taskIndex: Int = 0,
    val taskCount: Int = 0,
    val taskLabel: String = "",
    val taskId: String? = null,
    val pendingIds: List<String> = emptyList(),
    val results: List<TaskResult> = emptyList(),
    /** Recent files per task (pair id, or [QUICK_KEY]); newest first. */
    val eventsByTask: Map<String, List<FileEvent>> = emptyMap(),
    val logFile: String? = null,
) {
    val running: Boolean
        get() = phase == Phase.SCANNING || phase == Phase.DELETING ||
            phase == Phase.PREPARING || phase == Phase.COPYING

    val finished: Boolean
        get() = phase == Phase.DONE || phase == Phase.CANCELLED || phase == Phase.FAILED

    val hasErrors: Boolean
        get() = failed > 0 || phase == Phase.FAILED || results.any { it.status == TaskStatus.FAILED }

    /** Recent files of one folder pair (or quick copy when [pairId] is null). */
    fun eventsFor(pairId: String?): List<FileEvent> = eventsByTask[pairId ?: QUICK_KEY].orEmpty()

    /** This run came from folder pairs (not quick copy). */
    val isPairRun: Boolean
        get() = taskId != null

    /** True when the queue came from folder pairs (or has several tasks). */
    val isQueue: Boolean
        get() = taskCount > 1 || taskId != null
}
