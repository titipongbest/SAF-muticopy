package com.titipong.safmulticopy.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import java.io.File

/**
 * SAF has no way to set a file's "last modified" date, so a copied file would get today's date.
 * With "All files access" (Android 11+) or the storage permission (Android 10 and older) the app
 * can reach the same file by its real path and set the date to the original's.
 */
object FileDates {
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Real file behind a SAF document or file:// URI, e.g. "primary:DCIM/a.jpg" -> /storage/emulated/0/DCIM/a.jpg */
    fun fileFor(docUri: Uri): File? {
        if (docUri.scheme == "file") return docUri.path?.let(::File)
        val id = runCatching { DocumentsContract.getDocumentId(docUri) }.getOrNull() ?: return null
        if (id.startsWith("raw:")) return File(id.removePrefix("raw:"))
        if (docUri.authority != "com.android.externalstorage.documents") return null
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        @Suppress("DEPRECATION")
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volume") // SD card or USB drive
        }
        return if (rel.isEmpty()) root else File(root, rel)
    }

    /** Sets the file's modified date. Returns the file, or null if the date could not be set. */
    fun set(docUri: Uri, mtime: Long): File? {
        if (mtime <= 0) return null
        val f = fileFor(docUri) ?: return null
        return f.takeIf { runCatching { it.exists() && it.setLastModified(mtime) }.getOrDefault(false) }
    }
}
