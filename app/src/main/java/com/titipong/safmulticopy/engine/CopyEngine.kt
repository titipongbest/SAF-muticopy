package com.titipong.safmulticopy.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.titipong.safmulticopy.util.FileDates
import com.titipong.safmulticopy.util.formatCount
import com.titipong.safmulticopy.util.formatDuration
import com.titipong.safmulticopy.util.formatSize
import com.titipong.safmulticopy.util.resultSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the tasks of a request one after another. For each task:
 *  1. scan the source(s) and the destination (one query per folder)
 *  2. compare in memory: new / changed files are queued, identical ones skipped
 *  3. mirror only: delete extras in the destination (before copying, frees space)
 *  4. create missing folders (in order, parents first)
 *  5. copy the queue with N workers in parallel
 *
 * A task that fails (e.g. a drive was unplugged) is recorded and the next task still runs.
 * Stop cancels the whole queue.
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

    // The task that is running now.
    @Volatile private var task: CopyTask = req.tasks.first()
    @Volatile private var taskIndex = 0
    private var excludeFiles = GlobSet(emptyList())
    private var excludeDirs = GlobSet(emptyList())

    @Volatile private var phase = Phase.SCANNING
    @Volatile private var message = "Starting…"

    // Per task
    @Volatile private var deletingPath: String? = null
    @Volatile private var deletingIsDir = false
    @Volatile private var deleteDone = 0
    @Volatile private var deleteTotal = 0
    @Volatile private var filesToCopy = 0
    @Volatile private var bytesToCopy = 0L
    @Volatile private var scanned = 0
    private val filesDone = AtomicInteger()
    private val bytesDone = AtomicLong()
    @Volatile private var copyStartedAt = 0L

    // Totals of finished tasks
    private var aggFilesToCopy = 0
    private var aggBytesToCopy = 0L
    private var aggFilesDone = 0
    private var aggBytesDone = 0L

    // Totals across the whole run
    private val copied = AtomicInteger()
    private val skipped = AtomicInteger()
    private val failed = AtomicInteger()
    private val deleted = AtomicInteger()
    private val datesFixed = AtomicInteger()
    private val datesNotKept = AtomicInteger()

    /** Copies get the original's modified date (SAF alone can't; needs All files access). */
    private val canSetDates = req.keepDates && FileDates.hasAccess(context)
    private val datesUnavailable = req.keepDates && !canSetDates

    /** Lets the app write by real path when SAF fails (some USB drives / SD cards). */
    private val hasAllFilesAccess = FileDates.hasAccess(context)
    private val directWrites = AtomicInteger()

    /** Files the gallery / media index doesn't know about yet (written directly, or date changed). */
    private val toScan = ConcurrentLinkedQueue<String>()

    private val workerCount = req.threads.coerceIn(1, 32)
    private val slots = Array(workerCount) { Slot() }
    private val results = CopyOnWriteArrayList<TaskResult>()
    private val startedAt = System.currentTimeMillis()
    @Volatile private var finishedAt = 0L

    private val logLock = Any()
    private var logWriter: BufferedWriter? = null
    private var logFile: File? = null

    private val eventLock = Any()
    private val eventsByTask = LinkedHashMap<String, ArrayDeque<FileEvent>>()
    private var eventSeq = 0L

    /** What one parallel worker is doing right now (drives one progress bar). */
    private class Slot {
        @Volatile var name: String? = null
        @Volatile var size = 0L
        val done = AtomicLong()
        @Volatile var retry = 0
        @Volatile var update = false

        fun start(name: String, size: Long, update: Boolean) {
            done.set(0)
            retry = 0
            this.size = size
            this.update = update
            this.name = name
        }

        fun clear() {
            name = null
            done.set(0)
            retry = 0
        }
    }

    private fun clearSlots() = slots.forEach { it.clear() }

    private fun event(kind: EventKind, path: String, size: Long, detail: String? = null) {
        synchronized(eventLock) {
            val list = eventsByTask.getOrPut(task.id ?: QUICK_KEY) { ArrayDeque() }
            list.addFirst(
                FileEvent(
                    id = eventSeq++,
                    kind = kind,
                    name = path.substringAfterLast('/'),
                    path = path,
                    size = size,
                    detail = detail,
                ),
            )
            while (list.size > MAX_EVENTS) list.removeLast()
        }
    }

    private class Target(val destTree: Uri, val name: String, val dirIds: ConcurrentHashMap<String, String>)

    private class CopyJob(
        val srcUri: Uri,
        val src: FileRec,
        val target: Target,
        val parentKey: String,
        @Volatile var existingDocId: String?,
        /** Why an existing copy is replaced (shown in Recent files / log). */
        val reason: String? = null,
    )

    /** Something in the destination that mirror removes (a whole folder, or a file). */
    private class Deletion(
        val rel: String,
        val docId: String,
        val isDir: Boolean,
        /** For [isDir] = true: nested files then deepest-first sub-folders so SAF doesn't do one huge recursive IPC. */
        val childrenBottomUp: List<String> = emptyList(),
    )

    /** Thrown when a blocking storage/SAF call exceeds its timeout so we stop hammering a wedged drive. */
    private class StorageStalledException(message: String) : IOException(message)

    /** An existing, unchanged copy whose date is wrong (e.g. copied before dates were kept). */
    private class DateFix(val docId: String, val mtime: Long, val label: String, val size: Long)

    private class Plan(
        val target: Target,
        val jobs: List<CopyJob>,
        val dirsToCreate: List<String>,
        val deletions: List<Deletion>,
        val dateFixes: List<DateFix> = emptyList(),
    )

    private fun needsDateFix(srcMtime: Long, destMtime: Long) =
        canSetDates && FileCompare.needsDateFix(srcMtime, destMtime)

    private fun changeReason(src: FileRec, destSize: Long, destMtime: Long): String? =
        FileCompare.changeReason(src.size, src.mtime, destSize, destMtime)

    suspend fun run() = coroutineScope {
        openLog()
        val ticker = launch {
            while (isActive) {
                publishNow()
                delay(250)
            }
        }
        try {
            log(if (req.dryRun) "PREVIEW — nothing will be copied or deleted" else "COPY", Level.HEAD)
            if (datesUnavailable) {
                log("⚠ Original dates can't be kept — allow 'All files access' in the app's settings", Level.WARN)
            }
            for ((i, t) in req.tasks.withIndex()) {
                currentCoroutineContext().ensureActive()
                runTask(i, t)
            }
            phase = Phase.DONE
        } catch (e: CancellationException) {
            phase = Phase.CANCELLED
            log("■ Stopped by user", Level.ERROR)
        } catch (e: Exception) {
            phase = Phase.FAILED
            log("✖ Error: ${describe(e)}", Level.ERROR)
        } finally {
            ticker.cancel()
            clearSlots()
            message = ""
            finishedAt = System.currentTimeMillis()
            writeSummary()
            closeLog()
            publishNow()
        }
    }

    // ------------------------------------------------------------------ tasks

    private suspend fun runTask(index: Int, t: CopyTask) {
        task = t
        taskIndex = index
        excludeFiles = GlobSet(t.excludeFiles)
        excludeDirs = GlobSet(t.excludeDirs)
        filesToCopy = 0
        bytesToCopy = 0
        deleteDone = 0
        deleteTotal = 0
        deletingPath = null
        scanned = 0
        filesDone.set(0)
        bytesDone.set(0)
        copyStartedAt = 0
        phase = Phase.SCANNING
        message = "Starting…"

        val c0 = copied.get()
        val s0 = skipped.get()
        val f0 = failed.get()
        val d0 = deleted.get()
        val t0 = System.currentTimeMillis()
        if (req.tasks.size > 1 || t.id != null) {
            log("", Level.DIM)
            log("━━ ${index + 1}/${req.tasks.size} · ${t.label}", Level.HEAD)
        }

        var status = TaskStatus.OK
        var error: String? = null
        try {
            if (t.intoSubfolders) runQuickCopy(t) else runPair(t)
        } catch (e: CancellationException) {
            status = TaskStatus.CANCELLED
            throw e
        } catch (e: Exception) {
            status = TaskStatus.FAILED
            error = describe(e)
            log("✖ ${t.label}: $error", Level.ERROR)
            event(EventKind.FAILED, t.label, 0, error)
        } finally {
            clearSlots()
            scanForGallery()
            aggFilesToCopy += filesToCopy
            aggBytesToCopy += bytesToCopy
            aggFilesDone += filesDone.get()
            val taskBytes = bytesDone.get().coerceAtLeast(0)
            aggBytesDone += taskBytes
            val fd = failed.get() - f0
            if (status == TaskStatus.OK && fd > 0) status = TaskStatus.ERRORS
            val r = TaskResult(
                id = t.id,
                label = t.label,
                status = status,
                copied = copied.get() - c0,
                skipped = skipped.get() - s0,
                failed = fd,
                deleted = deleted.get() - d0,
                bytes = taskBytes,
                durationMs = System.currentTimeMillis() - t0,
                error = error,
            )
            results += r
        }
    }

    /** Quick copy: each source folder becomes a sub-folder of the destination; files go straight in. */
    private suspend fun runQuickCopy(t: CopyTask) {
        val destTree = t.destination
        val destRootId = Saf.rootId(destTree)
        log("Destination: ${Saf.locationLabel(destTree)}", Level.INFO)

        val destChildren = Saf.listChildren(cr, destTree, destRootId)
        val names = uniqueNames(t.sources.map { Saf.displayName(cr, it) ?: Saf.fallbackName(it) })

        val plans = ArrayList<Plan>()
        for ((i, srcTree) in t.sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            val name = names[i]
            message = "Scanning $name…"
            log("[${i + 1}/${t.sources.size}] $name  (${Saf.locationLabel(srcTree)})", Level.HEAD)

            val base = scanned
            val src = Saf.scan(cr, srcTree, Saf.rootId(srcTree), excludeFiles, excludeDirs) { scanned = base + it }

            val existing = destChildren.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (existing != null && !existing.isDir) {
                log("  ✖ Destination already has a FILE named \"$name\" — source skipped", Level.ERROR)
                event(EventKind.FAILED, name, 0, "a file with this name is in the destination")
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

        if (t.files.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            plans += planPickedFiles(destTree, destRootId, destChildren, names)
        }
        execute(plans, destTree, destRootId)
    }

    /** Folder pair: the contents of the source are synced directly into the destination. */
    private suspend fun runPair(t: CopyTask) {
        val srcTree = t.sources.first()
        val destTree = t.destination
        val destRootId = Saf.rootId(destTree)
        log("${Saf.locationLabel(srcTree)}  →  ${Saf.locationLabel(destTree)}", Level.INFO)

        message = "Scanning source…"
        val src = Saf.scan(cr, srcTree, Saf.rootId(srcTree), excludeFiles, excludeDirs) { scanned = it }
        message = "Checking destination…"
        val base = scanned
        val dest = Saf.scan(cr, destTree, destRootId, excludeFiles, excludeDirs) { scanned = base + it }
        log("  ${formatCount(src.files.size)} files in ${formatCount(src.dirs.size)} folders", Level.INFO)

        execute(listOf(buildPlan(srcTree, destTree, "", destRootId, src, dest)), destTree, destRootId)
    }

    private suspend fun execute(plans: List<Plan>, destTree: Uri, destRootId: String) {
        filesToCopy = plans.sumOf { it.jobs.size }
        bytesToCopy = plans.sumOf { p -> p.jobs.sumOf { it.src.size } }
        log(
            "  To copy: ${formatCount(filesToCopy)} files (${formatSize(bytesToCopy)})",
            Level.INFO,
        )

        if (task.mirror) deleteExtras(plans, destTree)

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
            log("  ✓ Nothing to copy — everything is up to date", Level.OK)
        } else {
            val queue = Channel<CopyJob>(Channel.UNLIMITED)
            all.forEach { queue.trySend(it) }
            queue.close()
            coroutineScope {
                repeat(workerCount) { worker ->
                    launch(Dispatchers.IO) {
                        val buf = ByteArray(BUFFER_SIZE)
                        for (job in queue) process(worker, job, buf)
                    }
                }
            }
        }

        fixDates(plans, destTree)
    }

    /** Copies made earlier with the wrong date (e.g. today's) get the original's date back — no re-copy. */
    private suspend fun fixDates(plans: List<Plan>, destTree: Uri) {
        val fixes = plans.flatMap { it.dateFixes }
        if (fixes.isEmpty()) return
        message = "Restoring original dates…"
        log("  Restoring original dates on ${formatCount(fixes.size)} existing files", Level.INFO)
        for (fx in fixes) {
            currentCoroutineContext().ensureActive()
            when {
                req.dryRun -> {
                    datesFixed.incrementAndGet()
                    event(EventKind.WOULD_FIX_DATE, fx.label, fx.size)
                }
                FileDates.set(Saf.docUri(destTree, fx.docId), fx.mtime)?.also { toScan += it.path } != null -> {
                    datesFixed.incrementAndGet()
                    event(EventKind.DATE_FIXED, fx.label, fx.size)
                }
                else -> {
                    datesNotKept.incrementAndGet()
                    log("  ⚠ Could not set the date of ${fx.label}", Level.WARN)
                }
            }
        }
        message = ""
    }

    // ------------------------------------------------------------------ planning

    private fun buildPlan(
        srcTree: Uri,
        destTree: Uri,
        name: String,
        existingRootId: String?,
        src: ScanResult,
        dest: ScanResult,
    ): Plan {
        val mirror = task.mirror
        val dirIds = ConcurrentHashMap<String, String>()
        if (existingRootId != null) {
            dirIds[""] = existingRootId
            dest.dirs.forEach { (k, d) -> dirIds[k] = d.docId }
        }
        val target = Target(destTree, name, dirIds)

        // A source folder where the destination has a file (only fixable by mirror).
        val blocked = HashSet<String>()
        if (!mirror) {
            for ((k, d) in src.dirs) if (dest.files.containsKey(k)) {
                blocked += k
                log("  ✖ Mismatch: \"${d.rel}\" is a folder in source but a file in destination", Level.ERROR)
            }
        }

        val jobs = ArrayList<CopyJob>()
        val dateFixes = ArrayList<DateFix>()
        for ((k, f) in src.files) {
            val parent = Saf.parentKey(k)
            if (k in blocked || parent in blocked || Saf.underAny(k, blocked)) {
                failed.incrementAndGet()
                event(EventKind.FAILED, label(target, f.rel), f.size, "a file in the destination has its folder's name")
                continue
            }
            if (dest.dirs.containsKey(k)) {
                if (!mirror) {
                    log("  ✖ Mismatch: \"${f.rel}\" is a file in source but a folder in destination", Level.ERROR)
                    failed.incrementAndGet()
                    event(EventKind.FAILED, label(target, f.rel), f.size, "a folder with this name is in the destination")
                } else {
                    // the folder is removed by mirror first
                    jobs += CopyJob(Saf.docUri(srcTree, f.docId), f, target, parent, null)
                }
                continue
            }
            val d = dest.files[k]
            when {
                d == null -> jobs += CopyJob(Saf.docUri(srcTree, f.docId), f, target, parent, null)
                changeReason(f, d.size, d.mtime) != null ->
                    jobs += CopyJob(Saf.docUri(srcTree, f.docId), f, target, parent, d.docId, changeReason(f, d.size, d.mtime))
                else -> {
                    skipped.incrementAndGet()
                    event(EventKind.SKIPPED, label(target, f.rel), f.size)
                    if (req.showSkipped) log("  = Same: ${f.rel}", Level.DIM)
                    if (needsDateFix(f.mtime, d.mtime)) dateFixes += DateFix(d.docId, f.mtime, label(target, f.rel), f.size)
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

        val deletions = if (mirror) extras(src, dest) else emptyList()
        return Plan(target, jobs, dirsToCreate, deletions, dateFixes)
    }

    /**
     * Individually picked files go straight into the destination folder.
     * Mirror never deletes anything here — it only applies inside the source folders' targets.
     */
    private fun planPickedFiles(
        destTree: Uri,
        destRootId: String,
        destChildren: List<DocEntry>,
        folderTargets: List<String>,
    ): Plan {
        val files = task.files
        message = "Checking selected files…"
        log("[Files] ${formatCount(files.size)} selected → destination folder", Level.HEAD)
        val target = Target(destTree, "", ConcurrentHashMap(mapOf("" to destRootId)))
        val destFiles = destChildren.filter { !it.isDir }.associateBy { it.name.lowercase() }
        val takenByFolders = destChildren.filter { it.isDir }.map { it.name.lowercase() }.toSet() +
            folderTargets.map { it.lowercase() }
        val seen = HashSet<String>()
        val jobs = ArrayList<CopyJob>()
        val dateFixes = ArrayList<DateFix>()
        for (uri in files) {
            val info = Saf.fileInfo(cr, uri)
            if (info == null) {
                failed.incrementAndGet()
                log("  ✖ Cannot read a selected file (it may have been moved or deleted)", Level.ERROR)
                event(EventKind.FAILED, uri.lastPathSegment ?: "file", 0, "can't read — moved or deleted?")
                continue
            }
            val key = info.name.lowercase()
            if (!seen.add(key)) {
                failed.incrementAndGet()
                log("  ✖ Two selected files are both named \"${info.name}\" — only the first is copied", Level.ERROR)
                event(EventKind.FAILED, info.name, info.size, "another selected file has the same name")
                continue
            }
            if (key in takenByFolders) {
                failed.incrementAndGet()
                log("  ✖ \"${info.name}\": a folder with this name exists in the destination", Level.ERROR)
                event(EventKind.FAILED, info.name, info.size, "a folder with this name is in the destination")
                continue
            }
            val f = FileRec(info.name, info.name, "", info.size, info.mtime)
            val d = destFiles[key]
            when {
                d == null -> jobs += CopyJob(uri, f, target, "", null)
                changeReason(f, d.size, d.mtime) != null ->
                    jobs += CopyJob(uri, f, target, "", d.docId, changeReason(f, d.size, d.mtime))
                else -> {
                    skipped.incrementAndGet()
                    event(EventKind.SKIPPED, f.rel, f.size)
                    if (req.showSkipped) log("  = Same: ${f.rel}", Level.DIM)
                    if (needsDateFix(f.mtime, d.mtime)) dateFixes += DateFix(d.docId, f.mtime, f.rel, f.size)
                }
            }
        }
        return Plan(target, jobs, emptyList(), emptyList(), dateFixes)
    }

    private fun label(target: Target, rel: String) = if (target.name.isEmpty()) rel else "${target.name}/$rel"

    /** Destination items that are not in the source. Folders are deleted bottom-up as a whole. */
    private fun extras(src: ScanResult, dest: ScanResult): List<Deletion> {
        val out = ArrayList<Deletion>()
        val removedDirs = HashSet<String>()
        val removedDirList = ArrayList<Pair<String, DirRec>>()
        for (d in dest.dirs.values.sortedBy { it.rel.count { c -> c == '/' } }) {
            val k = d.rel.lowercase()
            if (k in src.dirs || Saf.underAny(k, removedDirs)) continue
            removedDirs += k
            removedDirList += k to d
        }
        val nestedFiles = HashMap<String, ArrayList<String>>()
        val nestedDirs = HashMap<String, ArrayList<DirRec>>()
        if (removedDirs.isNotEmpty()) {
            for ((k, d) in dest.dirs) {
                if (k in removedDirs) continue
                val root = ancestorIn(k, removedDirs) ?: continue
                nestedDirs.getOrPut(root) { ArrayList() } += d
            }
            for ((k, f) in dest.files) {
                val root = ancestorIn(k, removedDirs) ?: continue
                nestedFiles.getOrPut(root) { ArrayList() } += f.docId
            }
        }
        for ((k, d) in removedDirList) {
            val children = ArrayList<String>()
            nestedFiles[k]?.let { children.addAll(it) }
            nestedDirs[k]
                ?.sortedByDescending { it.rel.count { c -> c == '/' } }
                ?.forEach { children += it.docId }
            out += Deletion(d.rel, d.docId, isDir = true, childrenBottomUp = children)
        }
        for (f in dest.files.values) {
            val k = f.rel.lowercase()
            if (k in src.files || Saf.underAny(k, removedDirs)) continue
            out += Deletion(f.rel, f.docId, isDir = false)
        }
        return out
    }

    private fun ancestorIn(key: String, set: Set<String>): String? {
        if (set.isEmpty()) return null
        var p = key
        while (true) {
            val i = p.lastIndexOf('/')
            if (i < 0) return null
            p = p.substring(0, i)
            if (p in set) return p
        }
    }

    /**
     * Runs blocking storage/SAF delete calls on a single reusable daemon thread with a timeout.
     * On cancellation (user taps Stop) or timeout, awaiting unblocks immediately instead of wedging
     * the engine, UI, and foreground service in a hung Binder/FUSE call.
     */
    private class DeleteRunner {
        private val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "saf-delete").apply { isDaemon = true }
        }
        @Volatile private var stalled = false

        suspend fun <T> run(itemLabel: String, block: () -> T): T {
            if (stalled) throw StorageStalledException("storage stopped responding")
            currentCoroutineContext().ensureActive()
            val deferred = CompletableDeferred<Result<T>>()
            exec.execute { deferred.complete(runCatching(block)) }
            val result = try {
                withTimeout(DELETE_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                stalled = true
                throw StorageStalledException(
                    "storage stopped responding while deleting $itemLabel (timed out after ${DELETE_TIMEOUT_MS / 1000}s) — drive may be busy or disconnected",
                )
            }
            return result.getOrThrow()
        }

        fun close() {
            exec.shutdownNow()
        }
    }

    private suspend fun deleteExtras(plans: List<Plan>, destTree: Uri) {
        if (plans.none { it.deletions.isNotEmpty() }) return
        phase = Phase.DELETING
        message = "Removing extra files…"
        deleteTotal = plans.sumOf { it.deletions.size }
        deleteDone = 0
        val runner = if (req.dryRun) null else DeleteRunner()
        var directDeletes = 0
        var directFailures = 0
        var directBlocked = false
        try {
            for (p in plans) for (del in p.deletions) {
                currentCoroutineContext().ensureActive()
                val label = label(p.target, del.rel)
                val what = if (del.isDir) "folder" else "file"
                deletingIsDir = del.isDir
                deletingPath = label
                message = "Deleting ${deleteDone + 1} of $deleteTotal: $label"
                if (req.dryRun) {
                    deleted.incrementAndGet()
                    log("  − Would delete $what: $label", Level.WARN)
                    event(EventKind.WOULD_DELETE, label, 0, if (del.isDir) "whole folder" else null)
                } else {
                    try {
                        val r = runner!!
                        if (Saf.isFileUri(destTree)) {
                            val f = File(del.docId)
                            if (!deleteDirect(f, del.isDir, label, r)) {
                                throw IOException("cannot delete ${f.name}")
                            }
                        } else {
                            var doneDirect = false
                            if (hasAllFilesAccess && !directBlocked) {
                                val f = FileDates.fileFor(Saf.docUri(destTree, del.docId))
                                if (f != null) {
                                    doneDirect = deleteDirect(f, del.isDir, label, r)
                                    if (doneDirect) {
                                        directDeletes++
                                    } else {
                                        directFailures++
                                        if (directDeletes == 0 && directFailures >= 3) directBlocked = true
                                    }
                                }
                            }
                            if (!doneDirect) deleteViaSaf(del, destTree, label, r)
                        }
                        deleted.incrementAndGet()
                        log("  − Deleted $what: $label", Level.WARN)
                        event(EventKind.DELETED, label, 0, if (del.isDir) "whole folder" else null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: StorageStalledException) {
                        failed.incrementAndGet()
                        log("  ✖ ${e.message}", Level.ERROR)
                        event(EventKind.FAILED, label, 0, e.message)
                        throw e
                    } catch (e: Exception) {
                        failed.incrementAndGet()
                        log("  ✖ Cannot delete $label — ${e.message}", Level.ERROR)
                        event(EventKind.FAILED, label, 0, "can't delete — ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                deleteDone++
            }
        } finally {
            deletingPath = null
            runner?.close()
        }
    }

    /**
     * Deletes a file or folder directly via POSIX File API (avoids hammering ExternalStorageProvider
     * and MediaProvider per-file on USB OTG drives). Returns false if direct deletion isn't permitted,
     * so the caller can fall back to SAF.
     */
    private suspend fun deleteDirect(file: File, isDir: Boolean, label: String, runner: DeleteRunner): Boolean {
        if (!isDir) {
            val ok = runner.run(label) { !file.exists() || file.delete() || !file.exists() }
            if (ok) toScan += file.path
            return ok
        }
        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.addLast(file to false)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (cur, visited) = stack.removeLast()
            if (!visited) {
                stack.addLast(cur to true)
                val entries = runner.run(label) {
                    cur.listFiles()?.map { it to it.isDirectory }
                }
                if (entries != null) {
                    for ((child, childIsDir) in entries) {
                        if (childIsDir) {
                            stack.addLast(child to false)
                        } else {
                            currentCoroutineContext().ensureActive()
                            val ok = runner.run("$label/${child.name}") {
                                !child.exists() || child.delete() || !child.exists()
                            }
                            if (!ok) return false
                            toScan += child.path
                        }
                    }
                }
            } else {
                val ok = runner.run(label) { !cur.exists() || cur.delete() || !cur.exists() }
                if (!ok) return false
            }
        }
        return true
    }

    /** Deletes via SAF bottom-up so large folders aren't removed in a single uninterruptible IPC call. */
    private suspend fun deleteViaSaf(del: Deletion, destTree: Uri, label: String, runner: DeleteRunner) {
        if (del.isDir) {
            for (childId in del.childrenBottomUp) {
                currentCoroutineContext().ensureActive()
                val childUri = Saf.docUri(destTree, childId)
                try {
                    runner.run(label) { DocumentsContract.deleteDocument(cr, childUri) }
                } catch (_: FileNotFoundException) {
                    // Already removed (e.g. by partial direct delete)
                }
            }
        }
        val uri = Saf.docUri(destTree, del.docId)
        val ok = runner.run(label) { DocumentsContract.deleteDocument(cr, uri) }
        if (!ok) throw IOException("storage provider refused to delete")
    }

    private suspend fun createFolders(p: Plan, destTree: Uri, destRootId: String) {
        val ids = p.target.dirIds
        val direct = Saf.isFileUri(destTree)
        if (!ids.containsKey("")) {
            try {
                ids[""] = if (direct) {
                    val dir = File(destRootId, p.target.name)
                    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create folder")
                    dir.absolutePath
                } else {
                    val uri = DocumentsContract.createDocument(
                        cr, Saf.docUri(destTree, destRootId), Document.MIME_TYPE_DIR, p.target.name,
                    ) ?: throw IOException("provider returned nothing")
                    DocumentsContract.getDocumentId(uri)
                }
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
            val name = rel.substringAfterLast('/')
            try {
                ids[key] = if (direct) {
                    val dir = File(parentId, name)
                    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create folder")
                    dir.absolutePath
                } else {
                    val uri = DocumentsContract.createDocument(
                        cr, Saf.docUri(destTree, parentId), Document.MIME_TYPE_DIR, name,
                    ) ?: throw IOException("provider returned nothing")
                    DocumentsContract.getDocumentId(uri)
                }
            } catch (e: Exception) {
                log("  ✖ Cannot create folder ${label(p.target, rel)} — ${e.message}", Level.ERROR)
            }
        }
    }

    // ------------------------------------------------------------------ copying

    private suspend fun process(worker: Int, job: CopyJob, buf: ByteArray) {
        val label = label(job.target, job.src.rel)
        val size = job.src.size
        val update = job.existingDocId != null
        val slot = slots[worker]
        slot.start(label, size, update)
        try {
            if (req.dryRun) {
                copied.incrementAndGet()
                bytesDone.addAndGet(size)
                filesDone.incrementAndGet()
                log("  + ${if (update) "Would update" else "Would copy"}: $label (${formatSize(size)})" +
                    (job.reason?.let { " — $it" } ?: ""), Level.OK)
                event(if (update) EventKind.WOULD_UPDATE else EventKind.WOULD_COPY, label, size, job.reason)
                return
            }
            var attempt = 0
            while (true) {
                var written = 0L
                slot.done.set(0)
                try {
                    copyOne(job, buf) { n ->
                        written += n
                        bytesDone.addAndGet(n.toLong())
                        slot.done.addAndGet(n.toLong())
                    }
                    copied.incrementAndGet()
                    log("  + ${if (update) "Updated" else "Copied"}: $label (${formatSize(size)})" +
                        (job.reason?.let { " — $it" } ?: ""), Level.OK)
                    event(if (update) EventKind.UPDATED else EventKind.COPIED, label, size, job.reason)
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
                        event(EventKind.FAILED, label, size, reason)
                        break
                    }
                    slot.retry = attempt
                    log("  ↻ Retry $attempt/${req.retries}: $label — $reason", Level.WARN)
                    delay(1000L * attempt)
                }
            }
            filesDone.incrementAndGet()
        } finally {
            slot.clear()
        }
    }

    private fun openSource(uri: Uri): InputStream =
        if (Saf.isFileUri(uri)) FileInputStream(Saf.fileOf(uri) ?: throw IOException("cannot open source"))
        else cr.openInputStream(uri) ?: throw IOException("cannot open source")

    private suspend fun copyOne(job: CopyJob, buf: ByteArray, onBytes: (Int) -> Unit) {
        val destTree = job.target.destTree
        val parentId = job.target.dirIds[job.parentKey] ?: throw IOException("destination folder is missing")

        if (Saf.isFileUri(destTree)) {
            if (!copyDirect(job, parentId, buf, onBytes)) throw IOException("cannot write to destination folder")
            job.existingDocId = null
            return
        }

        // Replace = delete + create. Opening the old file with "w" does not truncate on every provider.
        job.existingDocId?.let { id ->
            try {
                DocumentsContract.deleteDocument(cr, Saf.docUri(destTree, id))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Same storage-service problem as below: overwrite the old copy by real path instead.
                if (copyDirect(job, parentId, buf, onBytes)) {
                    job.existingDocId = null
                    return
                }
                throw e
            }
            job.existingDocId = null
        }

        // octet-stream keeps the file name exactly as given (no extension is added).
        val out = try {
            DocumentsContract.createDocument(
                cr, Saf.docUri(destTree, parentId), "application/octet-stream", job.src.name,
            ) ?: throw IOException("cannot create file")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Some phones' storage service can't see a folder it just created on a USB drive / SD card
            // ("Failed to determine if … is child of …"). With All files access, write the file directly.
            val direct = try {
                copyDirect(job, parentId, buf, onBytes)
            } catch (e2: Exception) {
                if (e2 is CancellationException) throw e2
                throw IOException("${e.message} — direct write also failed: ${e2.message}")
            }
            if (direct) return
            throw e
        }

        var ok = false
        try {
            val ctx = currentCoroutineContext()
            openSource(job.srcUri).use { input ->
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

        // Give the copy the original's date (after the stream is closed, or closing resets it).
        if (canSetDates && job.src.mtime > 0) {
            val f = FileDates.set(out, job.src.mtime)
            if (f != null) {
                toScan += f.path // the gallery still has the copy's date — let it re-read the file
            } else {
                datesNotKept.incrementAndGet()
                log("  ⚠ Could not set the date of ${label(job.target, job.src.rel)}", Level.WARN)
            }
        }
    }

    /**
     * Copies to the real path of the destination folder (used directly for file:// destinations,
     * and as a fallback when SAF fails on some USB drives / SD cards).
     * Returns false when that isn't possible, so the original error is reported.
     */
    private suspend fun copyDirect(job: CopyJob, parentId: String, buf: ByteArray, onBytes: (Int) -> Unit): Boolean {
        val isDirectDest = Saf.isFileUri(job.target.destTree)
        if (!hasAllFilesAccess && !isDirectDest) return false
        val dir = FileDates.fileFor(Saf.docUri(job.target.destTree, parentId)) ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create folder ${dir.path}")
        val outFile = File(dir, job.src.name)
        var ok = false
        try {
            val ctx = currentCoroutineContext()
            openSource(job.srcUri).use { input ->
                FileOutputStream(outFile).use { output ->
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
            if (!ok) runCatching { outFile.delete() }
        }
        if (canSetDates && job.src.mtime > 0 && !outFile.setLastModified(job.src.mtime)) {
            datesNotKept.incrementAndGet()
        }
        toScan += outFile.path // written without the storage service, so the gallery doesn't know it yet
        if (!isDirectDest && directWrites.getAndIncrement() == 0) {
            log("  ℹ The storage service couldn't create files here — writing directly to ${dir.path}", Level.INFO)
        }
        return true
    }

    /**
     * Asks Android to (re)index files so the gallery shows new photos and their original dates.
     * Runs in the background; done after each task, also when it was stopped.
     */
    private fun scanForGallery() {
        val paths = generateSequence { toScan.poll() }.toList()
        if (paths.isEmpty()) return
        log("  Updating the gallery for ${formatCount(paths.size)} files", Level.INFO)
        paths.chunked(SCAN_BATCH).forEach { batch ->
            runCatching { MediaScannerConnection.scanFile(context, batch.toTypedArray(), null, null) }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun describe(e: Exception): String = when (e) {
        is SecurityException -> "no access to the folder — open it and pick the folder again"
        is FileNotFoundException -> "folder not found (deleted, renamed or the drive is unplugged)"
        else -> e.message ?: e.javaClass.simpleName
    }

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
        val recent = synchronized(eventLock) { eventsByTask.mapValues { it.value.toList() } }
        val done = finishedAt > 0
        val t = task
        publish(
            CopyState(
                phase = phase,
                dryRun = req.dryRun,
                message = message,
                filesToCopy = if (done) aggFilesToCopy else filesToCopy,
                bytesToCopy = if (done) aggBytesToCopy else bytesToCopy,
                filesDone = if (done) aggFilesDone else filesDone.get(),
                bytesDone = if (done) aggBytesDone else bytesDone.get().coerceAtLeast(0),
                scanned = scanned,
                copied = copied.get(),
                skipped = skipped.get(),
                failed = failed.get(),
                deleted = deleted.get(),
                datesFixed = datesFixed.get(),
                datesNotKept = datesNotKept.get(),
                datesUnavailable = datesUnavailable,
                deletingPath = if (done) null else deletingPath,
                deletingIsDir = deletingIsDir,
                deleteDone = deleteDone,
                deleteTotal = deleteTotal,
                slots = if (done) emptyList() else slots.mapIndexed { i, s ->
                    SlotState(i, s.name, s.size, s.done.get(), s.retry, s.update)
                },
                startedAt = startedAt,
                copyStartedAt = if (done) startedAt else copyStartedAt,
                finishedAt = finishedAt,
                taskIndex = taskIndex,
                taskCount = req.tasks.size,
                taskLabel = t.label,
                taskId = t.id,
                pendingIds = if (done) emptyList() else req.tasks.drop(taskIndex + 1).mapNotNull { it.id },
                results = results.toList(),
                eventsByTask = recent,
                logFile = logFile?.absolutePath,
            ),
        )
    }

    /** Writes a line to the log file (shown in the app as "Share full log"). */
    @Suppress("UNUSED_PARAMETER")
    private fun log(text: String, level: Level = Level.INFO) {
        synchronized(logLock) {
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
            val kind = if (req.tasks.any { it.id != null }) "sync" else "copy"
            val f = File(dir, "${kind}_$stamp${if (req.dryRun) "_preview" else ""}.txt")
            logFile = f
            logWriter = f.bufferedWriter()
            logWriter?.write("SAF MultiCopy — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            logWriter?.newLine()
            logWriter?.write("Threads: ${req.threads} · Retries: ${req.retries} · Tasks: ${req.tasks.size}")
            logWriter?.newLine()
            for (t in req.tasks) {
                logWriter?.write(
                    "  • ${t.label} — mirror: ${t.mirror} · exclude files: ${t.excludeFiles} · " +
                        "exclude folders: ${t.excludeDirs}",
                )
                logWriter?.newLine()
            }
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
        if (req.tasks.size > 1) {
            for (r in results) {
                val icon = when (r.status) {
                    TaskStatus.OK -> "✓"
                    TaskStatus.ERRORS -> "⚠"
                    TaskStatus.FAILED -> "✖"
                    TaskStatus.CANCELLED -> "■"
                }
                log(
                    "$icon ${r.label}: ${resultSummary(r, req.dryRun)}",
                    if (r.status == TaskStatus.OK) Level.OK else Level.ERROR,
                )
            }
            val notRun = req.tasks.size - results.size
            if (notRun > 0) log("■ $notRun not started", Level.ERROR)
        }
        log(
            "$verb ${formatCount(copied.get())} · unchanged ${formatCount(skipped.get())} · " +
                "failed ${formatCount(failed.get())}" +
                (if (req.tasks.any { it.mirror }) " · ${if (req.dryRun) "would delete" else "deleted"} ${formatCount(deleted.get())}" else "") +
                " · ${formatSize(aggBytesDone)} · $took",
            if (failed.get() > 0 || phase != Phase.DONE || results.any { it.status == TaskStatus.FAILED }) {
                Level.ERROR
            } else {
                Level.OK
            },
        )
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        private const val MAX_EVENTS = 60
        private const val SCAN_BATCH = 500
        private const val DELETE_TIMEOUT_MS = 20_000L
    }
}
