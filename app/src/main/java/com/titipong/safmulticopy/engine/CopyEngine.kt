package com.titipong.safmulticopy.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.titipong.safmulticopy.util.formatCount
import com.titipong.safmulticopy.util.formatDuration
import com.titipong.safmulticopy.util.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Copy pipeline:
 *  1. scan every source and its destination folder (one query per folder)
 *  2. compare in memory: new / changed files are queued, identical ones skipped
 *  3. mirror only: delete extras in the destination (before copying, frees space)
 *  4. create missing folders (in order, parents first)
 *  5. copy the queue with N workers in parallel
 *
 * SAF cannot set a file's "last modified" time, so a copied file gets the copy time.
 * A file is therefore treated as unchanged when the size matches and the source is
 * not newer than the copy.
 */
class CopyEngine(
    private val context: Context,
    private val req: CopyRequest,
    private val publish: (CopyState) -> Unit,
) {
    private val cr = context.contentResolver
    private val excludeFiles = GlobSet(req.excludeFiles)
    private val excludeDirs = GlobSet(req.excludeDirs)

    @Volatile private var phase = Phase.SCANNING
    @Volatile private var message = "Starting…"
    @Volatile private var filesToCopy = 0
    @Volatile private var bytesToCopy = 0L
    @Volatile private var scanned = 0
    private val filesDone = AtomicInteger()
    private val bytesDone = AtomicLong()
    private val copied = AtomicInteger()
    private val skipped = AtomicInteger()
    private val failed = AtomicInteger()
    private val deleted = AtomicInteger()
    private val current = ConcurrentHashMap<Int, String>()
    private val startedAt = System.currentTimeMillis()
    @Volatile private var copyStartedAt = 0L
    @Volatile private var finishedAt = 0L

    private val logLock = Any()
    private val logLines = ArrayDeque<LogLine>()
    private var logWriter: BufferedWriter? = null
    private var logFile: File? = null

    private class Target(val destTree: Uri, val name: String, val dirIds: ConcurrentHashMap<String, String>)

    private class CopyJob(
        val srcTree: Uri,
        val src: FileRec,
        val target: Target,
        val parentKey: String,
        @Volatile var existingDocId: String?,
    )

    private class Plan(
        val target: Target,
        val jobs: List<CopyJob>,
        val dirsToCreate: List<String>,
        val deletions: List<Pair<String, String>>,
    )

    suspend fun run() = coroutineScope {
        openLog()
        val ticker = launch {
            while (isActive) {
                publishNow()
                delay(250)
            }
        }
        try {
            work()
            phase = Phase.DONE
        } catch (e: CancellationException) {
            phase = Phase.CANCELLED
            log("■ Stopped by user", Level.ERROR)
        } catch (e: Exception) {
            phase = Phase.FAILED
            log("✖ Error: ${e.message ?: e.javaClass.simpleName}", Level.ERROR)
        } finally {
            ticker.cancel()
            current.clear()
            message = ""
            finishedAt = System.currentTimeMillis()
            writeSummary()
            closeLog()
            publishNow()
        }
    }

    // ------------------------------------------------------------------ pipeline

    private suspend fun work() {
        val destTree = req.destination
        val destRootId = Saf.rootId(destTree)
        log(if (req.dryRun) "PREVIEW — nothing will be copied or deleted" else "COPY", Level.HEAD)
        log("Destination: ${Saf.locationLabel(destTree)}", Level.INFO)

        val destChildren = Saf.listChildren(cr, destTree, destRootId)
        val names = uniqueNames(req.sources.map { Saf.displayName(cr, it) ?: Saf.fallbackName(it) })

        val plans = ArrayList<Plan>()
        for ((i, srcTree) in req.sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            val name = names[i]
            message = "Scanning $name…"
            log("[${i + 1}/${req.sources.size}] $name  (${Saf.locationLabel(srcTree)})", Level.HEAD)

            val base = scanned
            val src = Saf.scan(cr, srcTree, Saf.rootId(srcTree), excludeFiles, excludeDirs) { scanned = base + it }

            val existing = destChildren.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (existing != null && !existing.isDir) {
                log("  ✖ Destination already has a FILE named \"$name\" — source skipped", Level.ERROR)
                failed.addAndGet(src.files.size)
                continue
            }
            val dest = if (existing != null) {
                message = "Checking destination $name…"
                val base2 = scanned
                Saf.scan(cr, destTree, existing.docId, excludeFiles, excludeDirs) { scanned = base2 + it }
            } else {
                ScanResult()
            }
            log("  ${formatCount(src.files.size)} files in ${formatCount(src.dirs.size)} folders", Level.INFO)
            plans += buildPlan(srcTree, destTree, name, existing?.docId, src, dest)
        }

        filesToCopy = plans.sumOf { it.jobs.size }
        bytesToCopy = plans.sumOf { p -> p.jobs.sumOf { it.src.size } }
        log(
            "To copy: ${formatCount(filesToCopy)} files (${formatSize(bytesToCopy)}) · " +
                "unchanged: ${formatCount(skipped.get())}",
            Level.INFO,
        )

        if (req.mirror) deleteExtras(plans, destTree)

        if (!req.dryRun) {
            phase = Phase.PREPARING
            message = "Creating folders…"
            for (p in plans) createFolders(p, destTree, destRootId)
        }

        phase = Phase.COPYING
        message = ""
        copyStartedAt = System.currentTimeMillis()
        val all = plans.flatMap { it.jobs }
        if (all.isEmpty()) {
            log("✓ Nothing to copy — everything is up to date", Level.OK)
            return
        }

        val queue = Channel<CopyJob>(Channel.UNLIMITED)
        all.forEach { queue.trySend(it) }
        queue.close()
        coroutineScope {
            repeat(req.threads.coerceIn(1, 32)) { worker ->
                launch(Dispatchers.IO) {
                    val buf = ByteArray(BUFFER_SIZE)
                    for (job in queue) process(worker, job, buf)
                }
            }
        }
    }

    private fun buildPlan(
        srcTree: Uri,
        destTree: Uri,
        name: String,
        existingRootId: String?,
        src: ScanResult,
        dest: ScanResult,
    ): Plan {
        val dirIds = ConcurrentHashMap<String, String>()
        if (existingRootId != null) {
            dirIds[""] = existingRootId
            dest.dirs.forEach { (k, d) -> dirIds[k] = d.docId }
        }
        val target = Target(destTree, name, dirIds)

        // A source folder where the destination has a file (only fixable by mirror).
        val blocked = HashSet<String>()
        if (!req.mirror) {
            for ((k, d) in src.dirs) if (dest.files.containsKey(k)) {
                blocked += k
                log("  ✖ Mismatch: \"${d.rel}\" is a folder in source but a file in destination", Level.ERROR)
            }
        }

        val jobs = ArrayList<CopyJob>()
        for ((k, f) in src.files) {
            val parent = Saf.parentKey(k)
            if (k in blocked || parent in blocked || Saf.underAny(k, blocked)) {
                failed.incrementAndGet()
                continue
            }
            if (dest.dirs.containsKey(k)) {
                if (!req.mirror) {
                    log("  ✖ Mismatch: \"${f.rel}\" is a file in source but a folder in destination", Level.ERROR)
                    failed.incrementAndGet()
                } else {
                    jobs += CopyJob(srcTree, f, target, parent, null) // folder is removed by mirror first
                }
                continue
            }
            val d = dest.files[k]
            when {
                d == null -> jobs += CopyJob(srcTree, f, target, parent, null)
                d.size != f.size || f.mtime > d.mtime + MTIME_TOLERANCE_MS ->
                    jobs += CopyJob(srcTree, f, target, parent, d.docId)
                else -> {
                    skipped.incrementAndGet()
                    if (req.showSkipped) log("  = Same: ${f.rel}", Level.DIM)
                }
            }
        }

        val dirsToCreate = src.dirs.values
            .filter { d ->
                val k = d.rel.lowercase()
                !dirIds.containsKey(k) && k !in blocked && !Saf.underAny(k, blocked)
            }
            .map { it.rel }
            .sortedBy { rel -> rel.count { it == '/' } }

        val deletions = if (req.mirror) extras(src, dest) else emptyList()
        return Plan(target, jobs, dirsToCreate, deletions)
    }

    /** Destination items that are not in the source. Folders are deleted as a whole. */
    private fun extras(src: ScanResult, dest: ScanResult): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val removedDirs = HashSet<String>()
        for (d in dest.dirs.values.sortedBy { rel -> rel.rel.count { it == '/' } }) {
            val k = d.rel.lowercase()
            if (k in src.dirs || Saf.underAny(k, removedDirs)) continue
            removedDirs += k
            out += d.rel to d.docId
        }
        for (f in dest.files.values) {
            val k = f.rel.lowercase()
            if (k in src.files || Saf.underAny(k, removedDirs)) continue
            out += f.rel to f.docId
        }
        return out
    }

    private suspend fun deleteExtras(plans: List<Plan>, destTree: Uri) {
        if (plans.none { it.deletions.isNotEmpty() }) return
        phase = Phase.DELETING
        message = "Removing extra files…"
        for (p in plans) for ((rel, id) in p.deletions) {
            currentCoroutineContext().ensureActive()
            val label = "${p.target.name}/$rel"
            if (req.dryRun) {
                deleted.incrementAndGet()
                log("  − Would delete: $label", Level.WARN)
                continue
            }
            try {
                DocumentsContract.deleteDocument(cr, Saf.docUri(destTree, id))
                deleted.incrementAndGet()
                log("  − Deleted: $label", Level.WARN)
            } catch (e: Exception) {
                failed.incrementAndGet()
                log("  ✖ Cannot delete $label — ${e.message}", Level.ERROR)
            }
        }
    }

    private suspend fun createFolders(p: Plan, destTree: Uri, destRootId: String) {
        val ids = p.target.dirIds
        if (!ids.containsKey("")) {
            try {
                val uri = DocumentsContract.createDocument(
                    cr, Saf.docUri(destTree, destRootId), Document.MIME_TYPE_DIR, p.target.name,
                ) ?: throw IOException("provider returned nothing")
                ids[""] = DocumentsContract.getDocumentId(uri)
            } catch (e: Exception) {
                log("  ✖ Cannot create folder ${p.target.name} — ${e.message}", Level.ERROR)
                return
            }
        }
        for (rel in p.dirsToCreate) {
            currentCoroutineContext().ensureActive()
            val key = rel.lowercase()
            if (ids.containsKey(key)) continue
            val parentId = ids[Saf.parentKey(key)] ?: continue
            try {
                val uri = DocumentsContract.createDocument(
                    cr, Saf.docUri(destTree, parentId), Document.MIME_TYPE_DIR, rel.substringAfterLast('/'),
                ) ?: throw IOException("provider returned nothing")
                ids[key] = DocumentsContract.getDocumentId(uri)
            } catch (e: Exception) {
                log("  ✖ Cannot create folder ${p.target.name}/$rel — ${e.message}", Level.ERROR)
            }
        }
    }

    // ------------------------------------------------------------------ copying

    private suspend fun process(worker: Int, job: CopyJob, buf: ByteArray) {
        val label = "${job.target.name}/${job.src.rel}"
        val size = job.src.size
        current[worker] = job.src.rel
        try {
            if (req.dryRun) {
                copied.incrementAndGet()
                bytesDone.addAndGet(size)
                filesDone.incrementAndGet()
                val verb = if (job.existingDocId != null) "Would update" else "Would copy"
                log("  + $verb: $label (${formatSize(size)})", Level.OK)
                return
            }
            val update = job.existingDocId != null
            var attempt = 0
            while (true) {
                var written = 0L
                try {
                    copyOne(job, buf) { n ->
                        written += n
                        bytesDone.addAndGet(n.toLong())
                    }
                    copied.incrementAndGet()
                    log("  + ${if (update) "Updated" else "Copied"}: $label (${formatSize(size)})", Level.OK)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    bytesDone.addAndGet(-written)
                    attempt++
                    val reason = e.message ?: e.javaClass.simpleName
                    if (attempt > req.retries) {
                        failed.incrementAndGet()
                        bytesDone.addAndGet(size)
                        log("  ✖ Failed: $label — $reason", Level.ERROR)
                        break
                    }
                    log("  ↻ Retry $attempt/${req.retries}: $label — $reason", Level.WARN)
                    delay(1000L * attempt)
                }
            }
            filesDone.incrementAndGet()
        } finally {
            current.remove(worker)
        }
    }

    private suspend fun copyOne(job: CopyJob, buf: ByteArray, onBytes: (Int) -> Unit) {
        val destTree = job.target.destTree
        val parentId = job.target.dirIds[job.parentKey] ?: throw IOException("destination folder is missing")

        // Replace = delete + create. Opening the old file with "w" does not truncate on every provider.
        job.existingDocId?.let { id ->
            DocumentsContract.deleteDocument(cr, Saf.docUri(destTree, id))
            job.existingDocId = null
        }

        // octet-stream keeps the file name exactly as given (no extension is added).
        val out = DocumentsContract.createDocument(
            cr, Saf.docUri(destTree, parentId), "application/octet-stream", job.src.name,
        ) ?: throw IOException("cannot create file")

        var ok = false
        try {
            val ctx = currentCoroutineContext()
            cr.openInputStream(Saf.docUri(job.srcTree, job.src.docId)).use { input ->
                if (input == null) throw IOException("cannot open source")
                cr.openOutputStream(out, "w").use { output ->
                    if (output == null) throw IOException("cannot open destination")
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        onBytes(n)
                    }
                }
            }
            ok = true
        } finally {
            if (!ok) runCatching { DocumentsContract.deleteDocument(cr, out) }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun uniqueNames(raw: List<String>): List<String> {
        val used = HashMap<String, Int>()
        return raw.map { n ->
            val key = n.lowercase()
            val c = (used[key] ?: 0) + 1
            used[key] = c
            if (c == 1) n else "$n ($c)"
        }
    }

    private fun publishNow() {
        val lines = synchronized(logLock) { logLines.toList() }
        publish(
            CopyState(
                phase = phase,
                dryRun = req.dryRun,
                message = message,
                filesToCopy = filesToCopy,
                bytesToCopy = bytesToCopy,
                filesDone = filesDone.get(),
                bytesDone = bytesDone.get().coerceAtLeast(0),
                scanned = scanned,
                copied = copied.get(),
                skipped = skipped.get(),
                failed = failed.get(),
                deleted = deleted.get(),
                current = current.values.toList(),
                startedAt = startedAt,
                copyStartedAt = copyStartedAt,
                finishedAt = finishedAt,
                log = lines,
                logFile = logFile?.absolutePath,
            ),
        )
    }

    private fun log(text: String, level: Level = Level.INFO) {
        synchronized(logLock) {
            logLines.addLast(LogLine(text, level))
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            runCatching {
                logWriter?.write(text)
                logWriter?.newLine()
            }
        }
    }

    private fun openLog() {
        runCatching {
            val dir = (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")).apply { mkdirs() }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "copy_$stamp${if (req.dryRun) "_preview" else ""}.txt")
            logFile = f
            logWriter = f.bufferedWriter()
            logWriter?.write("SAF MultiCopy — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            logWriter?.newLine()
            logWriter?.write(
                "Threads: ${req.threads} · Retries: ${req.retries} · Mirror: ${req.mirror} · " +
                    "Exclude files: ${req.excludeFiles} · Exclude folders: ${req.excludeDirs}",
            )
            logWriter?.newLine()
        }
    }

    private fun closeLog() {
        synchronized(logLock) {
            runCatching { logWriter?.close() }
            logWriter = null
        }
    }

    private fun writeSummary() {
        val took = formatDuration(finishedAt - startedAt)
        val verb = if (req.dryRun) "Would copy" else "Copied"
        log("────────────────────────", Level.DIM)
        log(
            "$verb ${formatCount(copied.get())} · unchanged ${formatCount(skipped.get())} · " +
                "failed ${formatCount(failed.get())}" +
                (if (req.mirror) " · ${if (req.dryRun) "would delete" else "deleted"} ${formatCount(deleted.get())}" else "") +
                " · ${formatSize(bytesDone.get().coerceAtLeast(0))} · $took",
            if (failed.get() > 0 || phase != Phase.DONE) Level.ERROR else Level.OK,
        )
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        private const val MAX_LOG_LINES = 400
        private const val MTIME_TOLERANCE_MS = 2_000L // FAT stores time in 2-second steps
    }
}
