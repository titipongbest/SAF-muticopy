package com.titipong.safmulticopy.engine

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.titipong.safmulticopy.util.FileDates
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
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

    fun isFileUri(uri: Uri): Boolean = uri.scheme == "file"

    fun fileOf(uri: Uri): File? = if (isFileUri(uri)) uri.path?.let(::File) else null

    /** True if [uri] points directly to Internal storage root (/storage/emulated/0). */
    fun isInternalStorageRoot(uri: Uri): Boolean {
        val f = fileOf(uri) ?: return false
        @Suppress("DEPRECATION")
        val ext = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        return f.absolutePath.trimEnd('/').equals(ext, ignoreCase = true)
    }

    /** True when [uri] points to a subfolder inside a granted tree (tree + document URI). */
    fun isSubfolderUri(uri: Uri): Boolean {
        if (isFileUri(uri)) return false
        val segs = uri.pathSegments
        return segs.size >= 4 && segs[0] == "tree" && segs[2] == "document"
    }

    /** The underlying granted tree URI (for persisted permission checks and release). */
    fun treeUriOf(uri: Uri): Uri =
        if (isSubfolderUri(uri)) {
            DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
        } else {
            uri
        }

    /** Document ID (or absolute path for file://) of the folder [tree] points to. */
    fun rootId(tree: Uri): String = when {
        isFileUri(tree) -> tree.path ?: ""
        isSubfolderUri(tree) -> DocumentsContract.getDocumentId(tree)
        else -> DocumentsContract.getTreeDocumentId(tree)
    }

    fun docUri(tree: Uri, docId: String): Uri =
        if (isFileUri(tree)) Uri.fromFile(File(docId))
        else DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    /**
     * Lists a whole folder with ONE query (or directly via File.listFiles for file:// URIs).
     * Using DocumentFile.findFile() per file instead re-reads the folder every time and is slow.
     */
    fun listChildren(cr: ContentResolver, tree: Uri, parentId: String): List<DocEntry> {
        if (isFileUri(tree)) {
            val dir = File(parentId)
            if (!dir.exists()) throw FileNotFoundException("Folder not found: $parentId")
            if (!dir.isDirectory) throw IOException("Not a folder: $parentId")
            val entries = dir.listFiles() ?: throw IOException("Cannot list folder: $parentId")
            return entries.map { f ->
                DocEntry(
                    docId = f.absolutePath,
                    name = f.name,
                    isDir = f.isDirectory,
                    size = if (f.isFile) f.length() else 0L,
                    mtime = f.lastModified(),
                )
            }
        }
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
        if (isFileUri(uri)) {
            val f = fileOf(uri)?.takeIf { it.exists() } ?: return@runCatching null
            return@runCatching FileInfo(f.name, f.length(), f.lastModified())
        }
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

    private fun fileDisplayName(f: File): String {
        @Suppress("DEPRECATION")
        val ext = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val path = f.absolutePath.trimEnd('/')
        if (path.equals(ext, ignoreCase = true)) return "Internal storage"
        if (f.parentFile?.absolutePath == "/storage") return "Drive ${f.name}"
        return f.name.ifBlank { path }
    }

    fun displayName(cr: ContentResolver, tree: Uri): String? {
        if (isFileUri(tree)) return fileOf(tree)?.let(::fileDisplayName)
        return runCatching {
            cr.query(docUri(tree, rootId(tree)), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** "primary:DCIM/Camera" -> "Camera", "1234-ABCD:" -> "Drive_1234-ABCD" */
    fun fallbackName(tree: Uri): String {
        if (isFileUri(tree)) return fileOf(tree)?.let(::fileDisplayName) ?: "Storage"
        val id = runCatching { rootId(tree) }.getOrDefault("")
        val path = id.substringAfter(':', "")
        val last = path.trimEnd('/').substringAfterLast('/')
        return last.ifBlank { "Drive_" + id.substringBefore(':').ifBlank { "root" } }
    }

    /** "primary:DCIM/Camera" -> "Internal storage › DCIM › Camera" */
    fun locationLabel(tree: Uri): String {
        if (isFileUri(tree)) {
            val path = fileOf(tree)?.absolutePath?.trimEnd('/') ?: return ""
            @Suppress("DEPRECATION")
            val ext = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
            if (path.equals(ext, ignoreCase = true)) return "Internal storage (root)"
            if (path.startsWith("$ext/", ignoreCase = true)) {
                val rel = path.substring(ext.length + 1).trim('/')
                return "Internal storage › " + rel.replace("/", " › ")
            }
            if (path.startsWith("/storage/")) {
                val rest = path.removePrefix("/storage/").trim('/')
                val vol = rest.substringBefore('/')
                val rel = rest.substringAfter('/', "")
                return if (rel.isEmpty()) "Drive $vol (root)" else "Drive $vol › " + rel.replace("/", " › ")
            }
            return path
        }
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

    /** True if the folder [child] is [parent] itself or somewhere inside it (same storage only). */
    fun isSameOrInside(child: Uri, parent: Uri): Boolean {
        if (isFileUri(child) || isFileUri(parent)) {
            val cPath = (fileOf(child) ?: FileDates.fileFor(docUri(child, runCatching { rootId(child) }.getOrNull() ?: return false)))
                ?.absolutePath?.trimEnd('/') ?: return false
            val pPath = (fileOf(parent) ?: FileDates.fileFor(docUri(parent, runCatching { rootId(parent) }.getOrNull() ?: return false)))
                ?.absolutePath?.trimEnd('/') ?: return false
            return cPath.equals(pPath, ignoreCase = true) || cPath.startsWith("$pPath/", ignoreCase = true)
        }
        if (child.authority != parent.authority) return false
        val c = runCatching { rootId(child) }.getOrNull() ?: return false
        val p = runCatching { rootId(parent) }.getOrNull() ?: return false
        if (c.equals(p, ignoreCase = true)) return true
        val prefix = if (p.endsWith(":") || p.endsWith("/")) p else "$p/"
        return c.startsWith(prefix, ignoreCase = true)
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
