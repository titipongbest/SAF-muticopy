package com.titipong.safmulticopy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.titipong.safmulticopy.MainActivity
import com.titipong.safmulticopy.R
import com.titipong.safmulticopy.engine.CopyManager
import com.titipong.safmulticopy.engine.CopyState
import com.titipong.safmulticopy.engine.Phase
import com.titipong.safmulticopy.util.formatCount
import com.titipong.safmulticopy.util.formatDuration
import com.titipong.safmulticopy.util.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the copy as a foreground service with a partial wake lock, so it keeps going
 * when the screen turns off or the user switches apps.
 */
class CopyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CopyManager.stop()
            return START_NOT_STICKY
        }
        createChannels()
        ServiceCompat.startForeground(
            this,
            PROGRESS_ID,
            progressNotification(CopyManager.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (working) return START_NOT_STICKY

        val job = CopyManager.launchPending(scope, applicationContext)
        if (job == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val runId = CopyManager.currentRunId()
        working = true
        acquireWakeLock()
        scope.launch {
            var cancelledAt = 0L
            while (!job.isCompleted) {
                if (job.isCancelled) {
                    if (cancelledAt == 0L) cancelledAt = System.currentTimeMillis()
                    if (System.currentTimeMillis() - cancelledAt >= STOP_FORCE_MS) {
                        CopyManager.forceCancelIfStuck(runId)
                        finish()
                        break
                    }
                }
                notificationManager().notify(PROGRESS_ID, progressNotification(CopyManager.state.value))
                delay(if (job.isCancelled) 500L else 1000L)
            }
        }
        job.invokeOnCompletion { scope.launch { finish() } }
        return START_NOT_STICKY
    }

    // Android 15+: dataSync services are limited to 6 hours a day; stop cleanly.
    override fun onTimeout(startId: Int, fgsType: Int) {
        CopyManager.stop()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun finish() {
        if (!working) return
        releaseWakeLock()
        working = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager().notify(DONE_ID, doneNotification(CopyManager.state.value))
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafMultiCopy:copy").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Copy progress", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Copy finished", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, CopyService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(s: CopyState): Notification {
        val pct = if (s.bytesToCopy > 0) (s.bytesDone * 100 / s.bytesToCopy).toInt().coerceIn(0, 100) else 0
        val copying = s.phase == Phase.COPYING && !s.stopping
        val title = when {
            s.stopping -> "Stopping…"
            s.phase == Phase.SCANNING -> "Scanning folders…"
            s.phase == Phase.DELETING -> "Removing extra files…"
            s.phase == Phase.PREPARING -> "Creating folders…"
            s.phase == Phase.COPYING -> if (s.dryRun) "Preview…" else "Copying · $pct%"
            else -> "SAF MultiCopy"
        }
        val text = if (copying) {
            "${formatCount(s.filesDone)} / ${formatCount(s.filesToCopy)} files · " +
                "${formatSize(s.bytesDone)} / ${formatSize(s.bytesToCopy)}"
        } else {
            s.message
        }
        val queuePrefix = if (s.isQueue) "${s.taskIndex + 1}/${s.taskCount} ${s.taskLabel} · " else ""
        val builder = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_copy)
            .setContentTitle(queuePrefix + title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .setProgress(100, pct, !copying)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (!s.stopping) builder.addAction(0, "Stop", stopIntent())
        return builder.build()
    }

    private fun doneNotification(s: CopyState): Notification {
        val title = when {
            s.phase == Phase.CANCELLED -> "Copy stopped"
            s.hasErrors -> "Finished with errors"
            s.dryRun -> "Preview finished"
            s.isQueue -> "Synced ${s.results.size} folder pair${if (s.results.size > 1) "s" else ""}"
            else -> "Copy finished"
        }
        val text = "${if (s.dryRun) "Would copy" else "Copied"} ${formatCount(s.copied)} · " +
            "unchanged ${formatCount(s.skipped)} · failed ${formatCount(s.failed)} · " +
            formatDuration(s.finishedAt - s.startedAt)
        return NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_copy)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.titipong.safmulticopy.STOP"
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val PROGRESS_ID = 1
        private const val DONE_ID = 2
        private const val STOP_FORCE_MS = 5_000L
    }
}
