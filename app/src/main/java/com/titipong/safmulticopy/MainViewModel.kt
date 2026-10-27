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
import com.titipong.safmulticopy.data.Settings
import com.titipong.safmulticopy.engine.CopyManager
import com.titipong.safmulticopy.engine.CopyRequest
import com.titipong.safmulticopy.engine.CopyState
import com.titipong.safmulticopy.engine.GlobSet
import com.titipong.safmulticopy.engine.Saf
import kotlinx.coroutines.flow.StateFlow

data class FolderRef(
    val uri: Uri,
    val name: String,
    val location: String,
    val isFile: Boolean = false,
    val size: Long = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val cr = app.contentResolver
    private val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    val sources = mutableStateListOf<FolderRef>()
    val files = mutableStateListOf<FolderRef>()
    var destination by mutableStateOf<FolderRef?>(null)
        private set

    var threads by mutableIntStateOf(settings.threads)
    var retries by mutableIntStateOf(settings.retries)
    var mirror by mutableStateOf(settings.mirror)
    var keepScreenOn by mutableStateOf(settings.keepScreenOn)
    var showSkipped by mutableStateOf(settings.showSkipped)
    var excludeFiles by mutableStateOf(settings.excludeFiles)
    var excludeDirs by mutableStateOf(settings.excludeDirs)

    val state: StateFlow<CopyState> = CopyManager.state

    init {
        // Only restore folders we still have permission for (the user may have revoked it).
        val granted = cr.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        settings.sources.map(Uri::parse).filter { it in granted }.forEach { sources += describe(it) }
        settings.files.map(Uri::parse).filter { it in granted }.forEach { uri ->
            describeFile(uri)?.let { files += it }
        }
        settings.destination?.let(Uri::parse)?.takeIf { it in granted }?.let { destination = describe(it) }
    }

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
        settings.excludeFiles = excludeFiles
        settings.excludeDirs = excludeDirs
    }

    /** Returns an error message, or null when the job can start. */
    fun validate(): String? {
        if (sources.isEmpty() && files.isEmpty()) return "Add at least one folder or file to copy."
        val dest = destination ?: return "Choose a destination folder."
        for (s in sources) {
            if (s.uri.authority != dest.uri.authority) continue
            val sId = runCatching { DocumentsContract.getTreeDocumentId(s.uri) }.getOrNull() ?: continue
            val dId = runCatching { DocumentsContract.getTreeDocumentId(dest.uri) }.getOrNull() ?: continue
            val prefix = if (sId.endsWith(":") || sId.endsWith("/")) sId else "$sId/"
            if (dId.equals(sId, true) || dId.startsWith(prefix, true)) {
                return "The destination is inside the source \"${s.name}\". " +
                    "That would copy the folder into itself — choose a destination outside it."
            }
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
                sources = sources.map { it.uri },
                files = files.map { it.uri },
                destination = dest.uri,
                threads = threads,
                retries = retries,
                mirror = mirror,
                dryRun = dryRun,
                showSkipped = showSkipped,
                excludeFiles = GlobSet.parse(excludeFiles),
                excludeDirs = GlobSet.parse(excludeDirs),
            ),
        )
    }

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
        runCatching { cr.releasePersistableUriPermission(uri, rw) }
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
