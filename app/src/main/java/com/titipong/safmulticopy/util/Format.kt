package com.titipong.safmulticopy.util

import com.titipong.safmulticopy.engine.TaskResult
import com.titipong.safmulticopy.engine.TaskStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "copied 120 · unchanged 3,400 · failed 2" */
fun resultSummary(r: TaskResult, dryRun: Boolean): String {
    if (r.status == TaskStatus.FAILED && r.error != null) return r.error
    val parts = ArrayList<String>()
    parts += "${if (dryRun) "would copy" else "copied"} ${formatCount(r.copied)}"
    parts += "unchanged ${formatCount(r.skipped)}"
    if (r.failed > 0) parts += "failed ${formatCount(r.failed)}"
    if (r.deleted > 0) parts += "${if (dryRun) "would delete" else "deleted"} ${formatCount(r.deleted)}"
    if (r.status == TaskStatus.CANCELLED) parts += "stopped"
    return parts.joinToString(" · ")
}

/** "just now", "12 min ago", "3 h ago", "yesterday 18:20", "12 Oct 09:15" */
fun formatAgo(time: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - time
    if (diff < 60_000) return "just now"
    if (diff < 3_600_000) return "${diff / 60_000} min ago"
    val today = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date(time))
    return when {
        time >= today -> if (diff < 6 * 3_600_000) "${diff / 3_600_000} h ago" else "today $hm"
        time >= today - 86_400_000 -> "yesterday $hm"
        else -> SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(time))
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

fun formatCount(n: Int): String = String.format(Locale.US, "%,d", n)

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}
