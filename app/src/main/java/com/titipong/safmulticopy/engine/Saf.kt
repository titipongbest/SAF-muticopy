package com.titipong.safmulticopy.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

data class DocEntry(val docId: String, val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

data class FileRec(val rel: String, val name: String, val docId: String, val size: Long, val mtime: Long)

data class DirRec(val rel: String, val docId: String)

/** Keys are lower-case relative paths (FAT/exFAT are case-insensitive). */
class ScanResult {
    val files = LinkedHashMap<String, FileRec>()
    val dirs = LinkedHashMap<String, DirRec>()
}

/** Glob patterns (* and ?) matched against a file or folder name, case-insensitive. */
class GlobSet(patterns: List<String>) {
    private val regexes = patterns.map(::toRegex)

    fun matches(name: String): Boolean = regexes.any { it.matches(name) }

    companion object {
        fun parse(text: String): List<String> =
            text.split(';', ',', '\n').map { it.trim().trim('"') }.filter { it.isNotEmpty() }

        private fun toRegex(p: String): Regex {
            val sb = StringBuilder()
            for (c in p) when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}

object Saf {
    private val PROJECTION = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )

    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    fun docUri(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    /**
     * Lists a whole folder with ONE query. Using DocumentFile.findFile() per file instead
     * re-reads the folder every time and is what makes most SAF code slow.
     */
    fun listChildren(cr: ContentResolver, tree: Uri, parentId: String): List<DocEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<DocEntry>()
        val cursor = cr.query(uri, PROJECTION, null, null, null)
            ?: throw IOException("Cannot list folder")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val mtime = if (c.isNull(4)) 0L else c.getLong(4)
                out += DocEntry(id, name, mime == Document.MIME_TYPE_DIR, size, mtime)
            }
        }
        return out
    }

    data class FileInfo(val name: String, val size: Long, val mtime: Long)

    /** Name, size and time of a single picked document (columns looked up by name; some providers lack some). */
    fun fileInfo(cr: ContentResolver, uri: Uri): FileInfo? = runCatching {
        cr.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val iName = c.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            val iSize = c.getColumnIndex(Document.COLUMN_SIZE)
            val iTime = c.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
            val name = if (iName >= 0) c.getString(iName) else null
            if (name.isNullOrBlank()) return@use null
            FileInfo(
                name = name,
                size = if (iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else 0L,
                mtime = if (iTime >= 0 && !c.isNull(iTime)) c.getLong(iTime) else 0L,
            )
        }
    }.getOrNull()

    /** Where a picked file lives: "Internal storage › Download", "Downloads", … */
    fun fileLocationLabel(uri: Uri): String = when (uri.authority) {
        "com.android.externalstorage.documents" -> {
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrDefault("")
            val vol = id.substringBefore(':')
            val parent = id.substringAfter(':', "").substringBeforeLast('/', "").trim('/')
            val volLabel = if (vol == "primary") "Internal storage" else "Drive $vol"
            if (parent.isEmpty()) volLabel else "$volLabel › " + parent.replace("/", " › ")
        }
        "com.android.providers.downloads.documents" -> "Downloads"
        "com.android.providers.media.documents" -> "Media"
        else -> uri.authority ?: ""
    }

    fun displayName(cr: ContentResolver, tree: Uri): String? = runCatching {
        cr.query(docUri(tree, rootId(tree)), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** "primary:DCIM/Camera" -> "Camera", "1234-ABCD:" -> "Drive_1234-ABCD" */
    fun fallbackName(tree: Uri): String {
        val id = runCatching { rootId(tree) }.getOrDefault("")
        val path = id.substringAfter(':', "")
        val last = path.trimEnd('/').substringAfterLast('/')
        return last.ifBlank { "Drive_" + id.substringBefore(':').ifBlank { "root" } }
    }

    /** "primary:DCIM/Camera" -> "Internal storage › DCIM › Camera" */
    fun locationLabel(tree: Uri): String {
        if (tree.authority != "com.android.externalstorage.documents") return tree.authority ?: ""
        val id = runCatching { rootId(tree) }.getOrDefault("")
        val vol = id.substringBefore(':')
        val path = id.substringAfter(':', "").trim('/')
        val volLabel = if (vol == "primary") "Internal storage" else "Drive $vol"
        return if (path.isEmpty()) volLabel else "$volLabel › " + path.replace("/", " › ")
    }

    /** Recursively scans a folder, skipping excluded names. */
    suspend fun scan(
        cr: ContentResolver,
        tree: Uri,
        rootDocId: String,
        excludeFiles: GlobSet,
        excludeDirs: GlobSet,
        onProgress: (Int) -> Unit,
    ): ScanResult {
        val result = ScanResult()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootDocId to "")
        var count = 0
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (id, prefix) = stack.removeLast()
            for (e in listChildren(cr, tree, id)) {
                val rel = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
                if (e.isDir) {
                    if (excludeDirs.matches(e.name)) continue
                    result.dirs[rel.lowercase()] = DirRec(rel, e.docId)
                    stack.addLast(e.docId to rel)
                } else {
                    if (excludeFiles.matches(e.name)) continue
                    result.files[rel.lowercase()] = FileRec(rel, e.name, e.docId, e.size, e.mtime)
                }
                count++
            }
            onProgress(count)
        }
        return result
    }

    fun parentKey(key: String): String = key.substringBeforeLast('/', "")

    /** True if any ancestor folder of [key] is in [set]. */
    fun underAny(key: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var p = key
        while (true) {
            val i = p.lastIndexOf('/')
            if (i < 0) return false
            p = p.substring(0, i)
            if (p in set) return true
        }
    }
}
