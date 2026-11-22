package com.titipong.safmulticopy

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.titipong.safmulticopy.data.FolderPair
import com.titipong.safmulticopy.data.PairRecent
import com.titipong.safmulticopy.data.PairStore
import com.titipong.safmulticopy.data.Settings
import com.titipong.safmulticopy.engine.CopyManager
import com.titipong.safmulticopy.engine.CopyRequest
import com.titipong.safmulticopy.engine.CopyState
import com.titipong.safmulticopy.engine.CopyTask
import com.titipong.safmulticopy.engine.DocEntry
import com.titipong.safmulticopy.engine.GlobSet
import com.titipong.safmulticopy.engine.Saf
import com.titipong.safmulticopy.util.FileDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class FolderRef(
    val uri: Uri,
    val name: String,
    val location: String,
    val isFile: Boolean = false,
    val size: Long = 0,
)

/** State of the multi-folder picker sheet in Quick copy. */
data class FolderPickerState(
    val rootTree: Uri,
    /** Navigation stack of (docId, name); the last entry is the folder currently being browsed. */
    val stack: List<Pair<String, String>>,
    val loading: Boolean = false,
    val subfolders: List<DocEntry> = emptyList(),
    val error: String? = null,
) {
    val currentDocId: String get() = stack.last().first
    val currentName: String get() = stack.last().second
    val canGoUp: Boolean get() = stack.size > 1
}

/** What the pair editor is working on (not saved until Save). */
data class PairDraft(
    val id: String? = null,
    val name: String = "",
    val source: FolderRef? = null,
    val destination: FolderRef? = null,
    val mirror: Boolean = false,
    val excludeFiles: String = "",
    val excludeDirs: String = "",
) {
    val defaultName: String
        get() = if (source != null && destination != null) "${source.name} → ${destination.name}"
        else source?.name ?: "My folder pair"
}

data class Grants(val read: Set<Uri> = emptySet(), val write: Set<Uri> = emptySet())

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val pairStore = PairStore.get(app)
    private val cr = app.contentResolver
    private val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    var tab by mutableIntStateOf(settings.tab)
        private set

    // Quick copy
    val sources = mutableStateListOf<FolderRef>()
    val files = mutableStateListOf<FolderRef>()
    var destination by mutableStateOf<FolderRef?>(null)
        private set
    var mirror by mutableStateOf(settings.mirror)
    var excludeFiles by mutableStateOf(settings.excludeFiles)
    var excludeDirs by mutableStateOf(settings.excludeDirs)

    // Shared settings
    var threads by mutableIntStateOf(settings.threads)
    var retries by mutableIntStateOf(settings.retries)
    var keepScreenOn by mutableStateOf(settings.keepScreenOn)
    var showSkipped by mutableStateOf(settings.showSkipped)
    var keepDates by mutableStateOf(settings.keepDates)

    /** "All files access" (Android 11+) / storage permission — needed to keep original dates. */
    var hasDateAccess by mutableStateOf(FileDates.hasAccess(app))
        private set

    fun refreshDateAccess() {
        hasDateAccess = FileDates.hasAccess(getApplication())
    }

    /** True when a real copy would give files today's date instead of the original's. */
    val datesWillBeLost: Boolean get() = keepDates && !hasDateAccess

    var grants by mutableStateOf(Grants())
        private set

    var folderPicker by mutableStateOf<FolderPickerState?>(null)
        private set

    val state: StateFlow<CopyState> = CopyManager.state
    val pairs: StateFlow<List<FolderPair>> = pairStore.pairs

    /** Folders picked in the pair editor; released on save/cancel if nothing ends up using them. */
    private val draftPicks = HashSet<Uri>()

    init {
        refreshGrants()
        // Only restore what we still have permission for (the user may have revoked it).
        val granted = grants.read
        val restoredSources = settings.sources.map(Uri::parse).filter { hasAccessTo(it, needWrite = false) }
        val restoredFiles = settings.files.map(Uri::parse).filter { it in granted || isOwn(it) }
        val restoredDest = settings.destination?.let(Uri::parse)?.takeIf { hasAccessTo(it, needWrite = false) }

        // Populate immediately using URI strings (zero IPC) so slow/stalled OTG never freezes startup.
        restoredSources.forEach { sources += describeFast(it) }
        restoredDest?.let { destination = describeFast(it) }

        if (restoredSources.isNotEmpty() || restoredFiles.isNotEmpty() || restoredDest != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val resolvedSources = restoredSources.associateWith { Saf.displayName(cr, it) }
                val resolvedDest = restoredDest?.let { Saf.displayName(cr, it) }
                val resolvedFiles = restoredFiles.mapNotNull { describeFile(it) }
                withContext(Dispatchers.Main) {
                    resolvedSources.forEach { (uri, name) ->
                        if (name == null) return@forEach
                        val idx = sources.indexOfFirst { it.uri == uri }
                        if (idx >= 0 && sources[idx].name != name) sources[idx] = sources[idx].copy(name = name)
                    }
                    if (resolvedDest != null && destination?.uri == restoredDest && destination?.name != resolvedDest) {
                        destination = destination?.copy(name = resolvedDest)
                    }
                    resolvedFiles.forEach { ref ->
                        if (files.none { it.uri == ref.uri }) files += ref
                    }
                }
            }
        }
    }

    fun selectTab(i: Int) {
        tab = i
        settings.tab = i
    }

    fun refreshGrants() {
        val perms = cr.persistedUriPermissions
        grants = Grants(
            read = perms.filter { it.isReadPermission }.map { it.uri }.toSet(),
            write = perms.filter { it.isWritePermission }.map { it.uri }.toSet(),
        )
    }

    // ------------------------------------------------------------------ quick copy

    fun addSource(uri: Uri): String? {
        if (!takePermission(uri)) return "Could not get permission for this folder."
        if (sources.none { sameFolder(it.uri, uri) }) sources += describe(uri)
        save()
        return null
    }

    /**
     * Called when the user picks a folder in Quick copy. If the folder has subfolders, opens a
     * multi-select sheet so several subfolders can be ticked at once (or the folder itself added).
     * Leaf folders with no subfolders are added immediately without an extra dialog.
     */
    fun openFolderPicker(treeUri: Uri): String? {
        if (!takePermission(treeUri)) return "Could not get permission for this folder."
        val rootDocId = runCatching { Saf.rootId(treeUri) }.getOrNull()
            ?: return "Could not read this folder."
        viewModelScope.launch(Dispatchers.IO) {
            val rootName = Saf.displayName(cr, treeUri) ?: Saf.fallbackName(treeUri)
            val subs = runCatching {
                Saf.listChildren(cr, treeUri, rootDocId)
                    .filter { it.isDir }
                    .sortedBy { it.name.lowercase() }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (subs.isNullOrEmpty()) {
                    if (sources.none { sameFolder(it.uri, treeUri) }) {
                        sources += FolderRef(treeUri, rootName, Saf.locationLabel(treeUri))
                    }
                    save()
                    refreshGrants()
                } else {
                    folderPicker = FolderPickerState(
                        rootTree = treeUri,
                        stack = listOf(rootDocId to rootName),
                        loading = false,
                        subfolders = subs,
                    )
                }
            }
        }
        return null
    }

    fun browsePickerInto(docId: String, name: String) {
        val cur = folderPicker ?: return
        val nextStack = cur.stack + (docId to name)
        folderPicker = cur.copy(stack = nextStack, loading = true, subfolders = emptyList(), error = null)
        loadPickerChildren(cur.rootTree, docId)
    }

    fun browsePickerUp() {
        val cur = folderPicker ?: return
        if (!cur.canGoUp) return
        val nextStack = cur.stack.dropLast(1)
        val targetId = nextStack.last().first
        folderPicker = cur.copy(stack = nextStack, loading = true, subfolders = emptyList(), error = null)
        loadPickerChildren(cur.rootTree, targetId)
    }

    private fun loadPickerChildren(rootTree: Uri, docId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                Saf.listChildren(cr, rootTree, docId)
                    .filter { it.isDir }
                    .sortedBy { it.name.lowercase() }
            }
            withContext(Dispatchers.Main) {
                val now = folderPicker ?: return@withContext
                if (now.rootTree != rootTree || now.currentDocId != docId) return@withContext
                folderPicker = res.fold(
                    onSuccess = { now.copy(loading = false, subfolders = it, error = null) },
                    onFailure = { now.copy(loading = false, error = it.message ?: "Could not open folder") },
                )
            }
        }
    }

    fun isFolderAlreadyAdded(rootTree: Uri, docId: String): Boolean {
        val uri = Saf.docUri(rootTree, docId)
        return sources.any { sameFolder(it.uri, uri) }
    }

    /** Adds all ticked subfolders, or the currently viewed folder itself when nothing is ticked. */
    fun confirmFolderPicker(selectedDocIds: Set<String>) {
        val picker = folderPicker ?: return
        folderPicker = null
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(picker.rootTree) }.getOrNull()
        if (selectedDocIds.isEmpty()) {
            val uri = if (picker.currentDocId == rootDocId) picker.rootTree
            else Saf.docUri(picker.rootTree, picker.currentDocId)
            if (sources.none { sameFolder(it.uri, uri) }) {
                sources += FolderRef(uri, picker.currentName, Saf.locationLabel(uri))
            }
        } else {
            for (entry in picker.subfolders) {
                if (entry.docId !in selectedDocIds) continue
                val uri = Saf.docUri(picker.rootTree, entry.docId)
                if (sources.none { sameFolder(it.uri, uri) }) {
                    sources += FolderRef(uri, entry.name, Saf.locationLabel(uri))
                }
            }
        }
        save()
        releaseIfUnused(picker.rootTree)
        refreshGrants()
    }

    fun dismissFolderPicker() {
        val picker = folderPicker ?: return
        folderPicker = null
        releaseIfUnused(picker.rootTree)
        refreshGrants()
    }

    fun removeSource(ref: FolderRef) {
        sources.remove(ref)
        releaseIfUnused(ref.uri)
        save()
    }

    fun clearSources() {
        val old = sources.toList()
        sources.clear()
        old.forEach { releaseIfUnused(it.uri) }
        save()
    }

    fun isFileFolderAdded(dir: File): Boolean {
        val uri = Uri.fromFile(dir)
        return sources.any { sameFolder(it.uri, uri) }
    }

    fun addFileSources(dirs: List<File>) {
        for (dir in dirs) {
            val uri = Uri.fromFile(dir)
            if (sources.none { sameFolder(it.uri, uri) }) {
                sources += describeFast(uri)
            }
        }
        save()
    }

    fun setFileDestination(dir: File) {
        val uri = Uri.fromFile(dir)
        val old = destination
        destination = describeFast(uri)
        if (old != null && old.uri != uri) releaseIfUnused(old.uri)
        save()
    }

    /**
     * Android keeps only a limited number of saved file permissions per app (128 before
     * Android 11, 512 after) and silently drops the oldest — which could include the folder
     * permissions. Picked files are therefore capped; for more, add the folder instead.
     */
    private val maxFiles = if (android.os.Build.VERSION.SDK_INT >= 30) 400 else 100

    fun addFiles(uris: List<Uri>): String? {
        var skippedOverLimit = 0
        var unreadable = 0
        for (uri in uris) {
            if (files.any { it.uri == uri }) continue
            if (files.size >= maxFiles) {
                skippedOverLimit++
                continue
            }
            runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val ref = describeFile(uri)
            if (ref == null) {
                unreadable++
                releaseFile(uri)
                continue
            }
            files += ref
        }
        save()
        return buildList {
            if (skippedOverLimit > 0) {
                add(
                    "$skippedOverLimit file(s) were not added: up to $maxFiles single files can be selected. " +
                        "For more, add their folder instead.",
                )
            }
            if (unreadable > 0) add("$unreadable file(s) could not be read and were skipped.")
        }.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    fun removeFile(ref: FolderRef) {
        files.remove(ref)
        releaseFile(ref.uri)
        save()
    }

    fun clearFiles() {
        files.forEach { releaseFile(it.uri) }
        files.clear()
        save()
    }

    private fun releaseFile(uri: Uri) {
        runCatching { cr.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun setDestinationUri(uri: Uri): String? {
        if (!takePermission(uri)) return "Could not get permission for this folder."
        val old = destination
        destination = describe(uri)
        if (old != null && old.uri != uri) releaseIfUnused(old.uri)
        save()
        return null
    }

    fun save() {
        settings.sources = sources.map { it.uri.toString() }
        settings.files = files.map { it.uri.toString() }
        settings.destination = destination?.uri?.toString()
        settings.threads = threads
        settings.retries = retries
        settings.mirror = mirror
        settings.keepScreenOn = keepScreenOn
        settings.showSkipped = showSkipped
        settings.keepDates = keepDates
        settings.excludeFiles = excludeFiles
        settings.excludeDirs = excludeDirs
    }

    /** Returns an error message, or null when the job can start. */
    fun validate(): String? {
        if (sources.isEmpty() && files.isEmpty()) return "Add at least one folder or file to copy."
        val dest = destination ?: return "Choose a destination folder."
        for (s in sources) {
            if (Saf.isSameOrInside(dest.uri, s.uri)) {
                return "The destination is inside the source \"${s.name}\". " +
                    "That would copy the folder into itself — choose a destination outside it."
            }
            if (s.uri.authority != dest.uri.authority) continue
            val sId = runCatching { Saf.rootId(s.uri) }.getOrNull() ?: continue
            val dId = runCatching { Saf.rootId(dest.uri) }.getOrNull() ?: continue
            val target = if (dId.endsWith(":")) dId + s.name else "$dId/${s.name}"
            if (target.equals(sId, true)) {
                return "\"${s.name}\" would be copied onto itself. Choose a different destination."
            }
        }
        return null
    }

    fun start(dryRun: Boolean) {
        save()
        val dest = destination ?: return
        CopyManager.start(
            getApplication(),
            CopyRequest(
                tasks = listOf(
                    CopyTask(
                        id = null,
                        label = "Quick copy",
                        sources = sources.map { it.uri },
                        files = files.map { it.uri },
                        destination = dest.uri,
                        intoSubfolders = true,
                        mirror = mirror,
                        excludeFiles = GlobSet.parse(excludeFiles),
                        excludeDirs = GlobSet.parse(excludeDirs),
                    ),
                ),
                threads = threads,
                retries = retries,
                dryRun = dryRun,
                showSkipped = showSkipped,
                keepDates = keepDates,
            ),
        )
    }

    // ------------------------------------------------------------------ folder pairs

    private fun hasAccessTo(uri: Uri, needWrite: Boolean): Boolean = when {
        Saf.isFileUri(uri) -> hasDateAccess
        isOwn(uri) -> true
        needWrite -> Saf.treeUriOf(uri) in grants.write
        else -> Saf.treeUriOf(uri) in grants.read
    }

    fun canAccess(p: FolderPair): Boolean =
        hasAccessTo(p.sourceUri, needWrite = false) && hasAccessTo(p.destUri, needWrite = true)

    /**
     * Folders from this app's own document provider need no granted permission. Only debug builds
     * have one (used by the automated tests); release builds have none, so this is always false there.
     */
    private fun isOwn(uri: Uri): Boolean = uri.authority == "${getApplication<Application>().packageName}.testdocs"

    fun draftOf(p: FolderPair) = PairDraft(
        id = p.id,
        name = p.name,
        source = FolderRef(p.sourceUri, p.sourceName, p.sourceLocation),
        destination = FolderRef(p.destUri, p.destName, p.destLocation),
        mirror = p.mirror,
        excludeFiles = p.excludeFiles,
        excludeDirs = p.excludeDirs,
    )

    /** Takes permission for a folder picked in the editor. */
    fun pickPairFolder(uri: Uri): FolderRef? {
        if (!takePermission(uri)) return null
        draftPicks += uri
        refreshGrants()
        return describe(uri)
    }

    fun pickPairFileFolder(dir: File): FolderRef = describeFast(Uri.fromFile(dir))

    fun validateDraft(d: PairDraft): String? {
        val src = d.source ?: return "Choose a source folder."
        val dst = d.destination ?: return "Choose a destination folder."
        if (!hasAccessTo(dst.uri, needWrite = true)) {
            return "This app can't write to \"${dst.name}\". Pick the destination folder again."
        }
        if (d.mirror && Saf.isInternalStorageRoot(dst.uri)) {
            return "Mirror mode cannot target Internal storage root — it would delete your system and personal folders. Choose a subfolder or use Merge mode."
        }
        if (Saf.isSameOrInside(src.uri, dst.uri) || Saf.isSameOrInside(dst.uri, src.uri)) {
            return "The source and destination can't be the same folder or inside each other."
        }
        return null
    }

    fun savePair(d: PairDraft): String? {
        validateDraft(d)?.let { return it }
        val src = d.source!!
        val dst = d.destination!!
        val old = d.id?.let { id -> pairs.value.firstOrNull { it.id == id } }
        pairStore.upsert(
            FolderPair(
                id = d.id ?: UUID.randomUUID().toString(),
                name = d.name.trim().ifEmpty { d.defaultName },
                source = src.uri.toString(),
                sourceName = src.name,
                sourceLocation = src.location,
                destination = dst.uri.toString(),
                destName = dst.name,
                destLocation = dst.location,
                mirror = d.mirror,
                excludeFiles = d.excludeFiles.trim(),
                excludeDirs = d.excludeDirs.trim(),
                selected = old?.selected ?: true,
                lastSyncAt = old?.lastSyncAt ?: 0,
                lastStatus = old?.lastStatus ?: "",
                lastSummary = old?.lastSummary ?: "",
            ),
        )
        if (old != null) {
            releaseIfUnused(old.sourceUri)
            releaseIfUnused(old.destUri)
        }
        releaseDraftPicks()
        return null
    }

    fun discardDraft() = releaseDraftPicks()

    private fun releaseDraftPicks() {
        draftPicks.forEach(::releaseIfUnused)
        draftPicks.clear()
        refreshGrants()
    }

    fun deletePair(id: String) {
        val p = pairs.value.firstOrNull { it.id == id } ?: return
        pairStore.delete(id)
        releaseIfUnused(p.sourceUri)
        releaseIfUnused(p.destUri)
        releaseDraftPicks()
    }

    /** Files of the pair's last sync (saved on disk). */
    fun loadRecent(id: String): PairRecent? = pairStore.loadRecent(id)

    fun setPairSelected(id: String, selected: Boolean) = pairStore.setSelected(id, selected)

    fun setAllPairsSelected(selected: Boolean) = pairStore.setAllSelected(selected)

    fun movePair(id: String, delta: Int) = pairStore.move(id, delta)

    fun validatePairs(list: List<FolderPair>): String? {
        if (list.isEmpty()) return "Tick at least one folder pair."
        refreshGrants()
        val lost = list.filterNot(::canAccess)
        if (lost.isNotEmpty()) {
            return "No access to: " + lost.joinToString { "\"${it.name}\"" } +
                ".\n\nOpen the pair and pick its folders again (or plug the drive back in), " +
                "or untick it to sync the others."
        }
        return null
    }

    /** Syncs the pairs one after another, in list order. */
    fun syncPairs(list: List<FolderPair>, dryRun: Boolean) {
        if (list.isEmpty()) return
        save()
        CopyManager.start(
            getApplication(),
            CopyRequest(
                tasks = list.map { p ->
                    CopyTask(
                        id = p.id,
                        label = p.name,
                        sources = listOf(p.sourceUri),
                        files = emptyList(),
                        destination = p.destUri,
                        intoSubfolders = false,
                        mirror = p.mirror,
                        excludeFiles = GlobSet.parse(p.excludeFiles),
                        excludeDirs = GlobSet.parse(p.excludeDirs),
                    )
                },
                threads = threads,
                retries = retries,
                dryRun = dryRun,
                showSkipped = showSkipped,
                keepDates = keepDates,
            ),
        )
    }

    // ------------------------------------------------------------------ common

    fun stop() = CopyManager.stop()

    fun dismissResult() = CopyManager.reset()

    private fun takePermission(uri: Uri): Boolean = try {
        cr.takePersistableUriPermission(uri, rw)
        true
    } catch (e: SecurityException) {
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
    }

    private fun releaseIfUnused(uri: Uri) {
        if (Saf.isFileUri(uri)) return
        val tree = Saf.treeUriOf(uri)
        if (sources.any { Saf.treeUriOf(it.uri) == tree } || destination?.uri?.let(Saf::treeUriOf) == tree) return
        if (pairs.value.any { Saf.treeUriOf(it.sourceUri) == tree || Saf.treeUriOf(it.destUri) == tree }) return
        if (runCatching { cr.releasePersistableUriPermission(tree, rw) }.isFailure) {
            runCatching { cr.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun sameFolder(a: Uri, b: Uri): Boolean {
        if (a == b) return true
        if (Saf.isFileUri(a) && Saf.isFileUri(b)) {
            return a.path?.trimEnd('/').equals(b.path?.trimEnd('/'), ignoreCase = true)
        }
        if (a.scheme != b.scheme || a.authority != b.authority) return false
        val aId = runCatching { Saf.rootId(a) }.getOrNull() ?: return false
        val bId = runCatching { Saf.rootId(b) }.getOrNull() ?: return false
        return aId.equals(bId, ignoreCase = true)
    }

    private fun describeFile(uri: Uri): FolderRef? {
        val info = Saf.fileInfo(cr, uri) ?: return null
        return FolderRef(uri, info.name, Saf.fileLocationLabel(uri), isFile = true, size = info.size)
    }

    private fun describe(uri: Uri) = FolderRef(
        uri = uri,
        name = Saf.displayName(cr, uri) ?: Saf.fallbackName(uri),
        location = Saf.locationLabel(uri),
    )

    private fun describeFast(uri: Uri) = FolderRef(
        uri = uri,
        name = Saf.fallbackName(uri),
        location = Saf.locationLabel(uri),
    )
}
