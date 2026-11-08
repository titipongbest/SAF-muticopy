package com.titipong.safmulticopy.testing

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * DEBUG BUILDS ONLY — a small Storage Access Framework provider over the app's own files, so the
 * automated tests can give the copy engine real SAF folders without the system folder picker.
 *
 * Document ids are "raw:<absolute path>", the same form some system providers use, so the app's
 * real-path features (keep original dates, direct-write fallback) work against it too.
 *
 * A folder whose name starts with [BROKEN_PREFIX] refuses to create files in it with the same error
 * some phones give for USB drives ("Failed to determine if … is child of …").
 */
class TestDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    private fun fileOf(id: String?): File {
        if (id == null || !id.startsWith(PREFIX)) throw FileNotFoundException("Bad id $id")
        return File(id.removePrefix(PREFIX))
    }

    private fun idOf(f: File) = PREFIX + f.absolutePath

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val base = File(context!!.filesDir, "saftest").apply { mkdirs() }
        return MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, "test")
                .add(Root.COLUMN_DOCUMENT_ID, idOf(base))
                .add(Root.COLUMN_TITLE, "Test storage")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
        }
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val f = fileOf(documentId)
        if (!f.exists()) throw FileNotFoundException("Missing $documentId")
        return MatrixCursor(projection ?: DOC_COLUMNS).apply { addFile(this, f) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val dir = fileOf(parentDocumentId)
        // Like the real storage provider: a missing folder is an error, not an empty folder.
        if (!dir.isDirectory) throw FileNotFoundException("Missing folder $parentDocumentId")
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        dir.listFiles()?.forEach { addFile(c, it) }
        return c
    }

    private fun addFile(c: MatrixCursor, f: File) {
        val dir = f.isDirectory
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(f))
            .add(Document.COLUMN_DISPLAY_NAME, f.name)
            .add(Document.COLUMN_MIME_TYPE, if (dir) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_SIZE, if (dir) null else f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(
                Document.COLUMN_FLAGS,
                Document.FLAG_SUPPORTS_DELETE or
                    (if (dir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE),
            )
    }

    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode ?: "r"))

    override fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String {
        val parent = fileOf(parentDocumentId)
        if (parent.name.startsWith(BROKEN_PREFIX)) {
            throw IllegalArgumentException(
                "Failed to determine if $parentDocumentId is child of ${idOf(parent.parentFile!!)}: " +
                    "java.io.FileNotFoundException: Missing file for $parentDocumentId",
            )
        }
        val name = displayName ?: "untitled"
        var f = File(parent, name)
        var n = 1
        while (f.exists()) {
            val base = name.substringBeforeLast('.')
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            f = File(parent, "$base ($n)$ext")
            n++
        }
        val ok = if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
        if (!ok) throw IllegalStateException("Could not create $f")
        return idOf(f)
    }

    override fun deleteDocument(documentId: String?) {
        val f = fileOf(documentId)
        if (!f.deleteRecursively()) throw IllegalStateException("Could not delete $f")
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        val p = fileOf(parentDocumentId).absolutePath
        val d = fileOf(documentId).absolutePath
        return d == p || d.startsWith("$p/")
    }

    override fun getDocumentType(documentId: String?): String =
        if (fileOf(documentId).isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"

    companion object {
        const val PREFIX = "raw:"
        const val BROKEN_PREFIX = "broken-usb"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS,
        )
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
