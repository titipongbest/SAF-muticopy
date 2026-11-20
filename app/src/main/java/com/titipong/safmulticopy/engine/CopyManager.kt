package com.titipong.safmulticopy.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.titipong.safmulticopy.data.PairRecent
import com.titipong.safmulticopy.data.PairStore
import com.titipong.safmulticopy.service.CopyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Shared between the UI and the foreground service (same process). */
object CopyManager {
    private val _state = MutableStateFlow(CopyState())
    val state: StateFlow<CopyState> = _state.asStateFlow()

    @Volatile private var pending: CopyRequest? = null
    @Volatile private var job: Job? = null
    private val activeRunId = AtomicLong(0L)

    fun start(context: Context, req: CopyRequest) {
        if (_state.value.running || req.tasks.isEmpty()) return
        pending = req
        val first = req.tasks.first()
        _state.value = CopyState(
            phase = Phase.SCANNING,
            dryRun = req.dryRun,
            message = "Starting…",
            startedAt = System.currentTimeMillis(),
            taskCount = req.tasks.size,
            taskLabel = first.label,
            taskId = first.id,
            pendingIds = req.tasks.drop(1).mapNotNull { it.id },
        )
        ContextCompat.startForegroundService(context, Intent(context, CopyService::class.java))
    }

    fun stop() {
        val s = _state.value
        if (s.running && !s.stopping) {
            _state.value = s.copy(stopping = true, message = "Stopping…")
        }
        job?.cancel()
    }

    fun reset() {
        if (!_state.value.running) _state.value = CopyState()
    }

    internal fun currentRunId(): Long = activeRunId.get()

    /** Last-resort recovery if a cancelled job is wedged in an uninterruptible storage/Binder call. */
    internal fun forceCancelIfStuck(runId: Long) {
        if (!activeRunId.compareAndSet(runId, runId + 1)) return
        val s = _state.value
        if (!s.running) return
        _state.value = s.copy(
            phase = Phase.CANCELLED,
            stopping = false,
            message = "Stopped (storage stopped responding)",
            deletingPath = null,
            slots = emptyList(),
            finishedAt = System.currentTimeMillis(),
        )
    }

    /** Called by the service once it is in the foreground. */
    internal fun launchPending(scope: CoroutineScope, context: Context): Job? {
        val req = pending ?: return null
        pending = null
        val runId = activeRunId.incrementAndGet()
        val j = scope.launch(Dispatchers.IO) {
            CopyEngine(context, req) { if (activeRunId.get() == runId) _state.value = it }.run()
            if (activeRunId.get() != runId) return@launch
            // Remember the outcome on each folder pair (not for previews).
            if (!req.dryRun) {
                val s = _state.value
                val store = PairStore.get(context)
                runCatching { store.recordResults(s.results, s.finishedAt) }
                // Keep each pair's files so they can be viewed later by opening the pair.
                s.results.mapNotNull { it.id }.distinct().forEach { id ->
                    store.saveRecent(id, PairRecent(s.finishedAt, s.logFile, s.eventsFor(id)))
                }
            }
        }
        job = j
        return j
    }
}
