package com.titipong.safmulticopy

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.titipong.safmulticopy.engine.CopyEngine
import com.titipong.safmulticopy.engine.CopyRequest
import com.titipong.safmulticopy.engine.CopyState
import com.titipong.safmulticopy.engine.CopyTask
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/** Helpers shared by the device tests. */
object T {
    val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val pkg: String get() = ctx.packageName
    private val auth get() = "$pkg.testdocs"

    /** A fresh, empty folder for one test. */
    fun dir(name: String): File = File(ctx.filesDir, "saftest/$name").apply {
        deleteRecursively()
        mkdirs()
    }

    /** SAF folder (tree) for a real folder, served by the debug TestDocumentsProvider. */
    fun tree(dir: File): Uri = DocumentsContract.buildTreeDocumentUri(auth, "raw:" + dir.absolutePath)

    /** SAF document for a single file, as the system file picker would return it. */
    fun doc(file: File): Uri = DocumentsContract.buildDocumentUri(auth, "raw:" + file.absolutePath)

    fun write(root: File, rel: String, text: String, mtime: Long? = null): File {
        val f = File(root, rel)
        f.parentFile!!.mkdirs()
        f.writeText(text)
        if (mtime != null) f.setLastModified(mtime)
        return f
    }

    /** rel path -> content, for every file under [root]. */
    fun contents(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    fun dirs(root: File): Set<String> =
        root.walkTopDown().filter { it.isDirectory && it != root }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()

    fun quick(
        sources: List<Uri>,
        dest: Uri,
        files: List<Uri> = emptyList(),
        mirror: Boolean = false,
        xf: List<String> = emptyList(),
        xd: List<String> = emptyList(),
    ) = CopyTask(null, "Quick copy", sources, files, dest, true, mirror, xf, xd)

    fun pair(
        id: String,
        src: Uri,
        dest: Uri,
        mirror: Boolean = false,
        xf: List<String> = emptyList(),
        xd: List<String> = emptyList(),
    ) = CopyTask(id, id, listOf(src), emptyList(), dest, false, mirror, xf, xd)

    fun req(
        vararg tasks: CopyTask,
        threads: Int = 4,
        retries: Int = 0,
        dryRun: Boolean = false,
        keepDates: Boolean = false,
    ) = CopyRequest(tasks.toList(), threads, retries, dryRun, showSkipped = true, keepDates = keepDates)

    /** Runs the engine to the end and returns its final state. */
    fun run(r: CopyRequest): CopyState {
        val last = AtomicReference(CopyState())
        runBlocking { CopyEngine(ctx, r) { last.set(it) }.run() }
        return last.get()
    }

    /** Runs a shell command as the test (shell user) and waits for it. */
    fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptorReader.read(pfd)
    }

    /** "All files access" on/off (Android 11+). Needed for keep-original-dates and the USB fallback. */
    fun allFilesAccess(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            shell("appops set $pkg MANAGE_EXTERNAL_STORAGE ${if (on) "allow" else "default"}")
        } else {
            val verb = if (on) "grant" else "revoke"
            shell("pm $verb $pkg android.permission.WRITE_EXTERNAL_STORAGE")
        }
        Thread.sleep(300)
    }

    /** Saves a screenshot; the CI workflow collects them from the device. */
    fun screenshot(name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "api${Build.VERSION.SDK_INT}-$name.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

object ParcelFileDescriptorReader {
    fun read(pfd: android.os.ParcelFileDescriptor): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
}
