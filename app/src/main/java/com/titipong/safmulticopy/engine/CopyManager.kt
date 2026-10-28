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

/** Shared between the UI and the foreground service (same process). */
object CopyManager {
    private val _state = MutableStateFlow(CopyState())
    val state: StateFlow<CopyState> = _state.asStateFlow()

    @Volatile private var pending: CopyRequest? = null
    @Volatile private var job: Job? = null

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
        job?.cancel()
    }

    fun reset() {
        if (!_state.value.running) _state.value = CopyState()
    }

    /** Called by the service once it is in the foreground. */
    internal fun launchPending(scope: CoroutineScope, context: Context): Job? {
        val req = pending ?: return null
        pending = null
        val j = scope.launch(Dispatchers.IO) {
            CopyEngine(context, req) { _state.value = it }.run()
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
