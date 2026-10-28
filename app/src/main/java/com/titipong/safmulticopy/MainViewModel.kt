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
import com.titipong.safmulticopy.data.FolderPair
import com.titipong.safmulticopy.data.PairRecent
import com.titipong.safmulticopy.data.PairStore
import com.titipong.safmulticopy.data.Settings
import com.titipong.safmulticopy.engine.CopyManager
import com.titipong.safmulticopy.engine.CopyRequest
import com.titipong.safmulticopy.engine.CopyState
import com.titipong.safmulticopy.engine.CopyTask
import com.titipong.safmulticopy.engine.GlobSet
import com.titipong.safmulticopy.engine.Saf
import com.titipong.safmulticopy.util.FileDates
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class FolderRef(
    val uri: Uri,
    val name: String,
    val location: String,
    val isFile: Boolean = false,
    val size: Long = 0,
)

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

    val state: StateFlow<CopyState> = CopyManager.state
    val pairs: StateFlow<List<FolderPair>> = pairStore.pairs

    /** Folders picked in the pair editor; released on save/cancel if nothing ends up using them. */
    private val draftPicks = HashSet<Uri>()

    init {
        refreshGrants()
        // Only restore what we still have permission for (the user may have revoked it).
        val granted = grants.read
        settings.sources.map(Uri::parse).filter { it in granted }.forEach { sources += describe(it) }
        settings.files.map(Uri::parse).filter { it in granted }.forEach { uri ->
            describeFile(uri)?.let { files += it }
        }
        settings.destination?.let(Uri::parse)?.takeIf { it in granted }?.let { destination = describe(it) }
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
        if (sources.none { it.uri == uri }) sources += describe(uri)
        save()
        return null
    }

    fun removeSource(ref: FolderRef) {
        sources.remove(ref)
        releaseIfUnused(ref.uri)
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
            val sId = runCatching { DocumentsContract.getTreeDocumentId(s.uri) }.getOrNull() ?: continue
            val dId = runCatching { DocumentsContract.getTreeDocumentId(dest.uri) }.getOrNull() ?: continue
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

    fun canAccess(p: FolderPair): Boolean = p.sourceUri in grants.read && p.destUri in grants.write

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

    fun validateDraft(d: PairDraft): String? {
        val src = d.source ?: return "Choose a source folder."
        val dst = d.destination ?: return "Choose a destination folder."
        if (dst.uri !in grants.write) {
            return "This app can't write to \"${dst.name}\". Pick the destination folder again."
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
        if (sources.any { it.uri == uri } || destination?.uri == uri) return
        if (pairs.value.any { it.sourceUri == uri || it.destUri == uri }) return
        if (runCatching { cr.releasePersistableUriPermission(uri, rw) }.isFailure) {
            runCatching { cr.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
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
}
