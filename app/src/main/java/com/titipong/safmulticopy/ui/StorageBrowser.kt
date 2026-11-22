@file:OptIn(ExperimentalMaterial3Api::class)

package com.titipong.safmulticopy.ui

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titipong.safmulticopy.ui.theme.Success
import com.titipong.safmulticopy.util.formatCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal enum class BrowserTarget {
    QUICK_SOURCES,
    QUICK_DEST,
    PAIR_SOURCE,
    PAIR_DEST,
}

internal data class StorageVolumeItem(
    val dir: File,
    val name: String,
    val isPrimary: Boolean,
)

/** Lists mounted storage roots (Internal storage, SD cards, USB OTG drives). */
internal fun listStorageVolumes(context: Context): List<StorageVolumeItem> {
    val out = LinkedHashMap<String, StorageVolumeItem>()
    if (Build.VERSION.SDK_INT >= 30) {
        val sm = context.getSystemService(StorageManager::class.java)
        sm?.storageVolumes?.forEach { vol ->
            if (vol.state != Environment.MEDIA_MOUNTED) return@forEach
            val dir = vol.directory ?: return@forEach
            if (!dir.exists() || !dir.isDirectory) return@forEach
            val label = if (vol.isPrimary) {
                "Internal storage"
            } else {
                vol.getDescription(context).takeIf { it.isNotBlank() } ?: "Drive ${dir.name}"
            }
            out[dir.absolutePath] = StorageVolumeItem(dir, label, vol.isPrimary)
        }
    }
    if (out.isEmpty()) {
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory()
        if (primary.exists() && primary.isDirectory) {
            out[primary.absolutePath] = StorageVolumeItem(primary, "Internal storage", true)
        }
        File("/storage").listFiles()?.forEach { f ->
            if (!f.isDirectory) return@forEach
            if (f.name == "emulated" || f.name == "self") return@forEach
            if (f.canRead() && !out.containsKey(f.absolutePath)) {
                out[f.absolutePath] = StorageVolumeItem(f, "Drive ${f.name}", false)
            }
        }
    }
    return out.values.sortedWith(compareByDescending<StorageVolumeItem> { it.isPrimary }.thenBy { it.name.lowercase() })
}

/**
 * In-app file-manager style folder browser (available when All files access is granted).
 * Lets the user pick storage roots directly (e.g. Internal storage root, USB OTG root, Download)
 * and select multiple subfolders at once in Quick copy.
 */
@Composable
internal fun StorageBrowserSheet(
    target: BrowserTarget,
    isAdded: (File) -> Boolean,
    onPickMulti: (List<File>) -> Unit,
    onPickSingle: (File) -> Unit,
    onUseSystemPicker: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val multi = target == BrowserTarget.QUICK_SOURCES

    val volumes = remember { listStorageVolumes(context) }
    var activeVolume by remember { mutableStateOf(if (volumes.size == 1) volumes.first() else null) }
    var currentDir by remember { mutableStateOf(if (volumes.size == 1) volumes.first().dir else null) }
    var children by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedPaths by remember(currentDir) { mutableStateOf(emptySet<String>()) }
    var showNewFolder by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    val scroll = rememberScrollState()
    LaunchedEffect(currentDir, refreshTick) {
        val dir = currentDir ?: return@LaunchedEffect
        loading = true
        errorMsg = null
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val list = dir.listFiles() ?: throw IllegalStateException("Cannot read this folder")
                list.filter { it.isDirectory }
                    .sortedWith(compareBy<File> { it.name.startsWith(".") }.thenBy { it.name.lowercase() })
            }
        }
        loading = false
        res.fold(
            onSuccess = { children = it },
            onFailure = {
                children = emptyList()
                errorMsg = it.message ?: "Cannot read this folder"
            },
        )
        scroll.scrollTo(0)
    }

    val titleText = when (target) {
        BrowserTarget.QUICK_SOURCES -> "Choose source folders"
        BrowserTarget.QUICK_DEST -> "Choose destination"
        BrowserTarget.PAIR_SOURCE -> "Choose source folder"
        BrowserTarget.PAIR_DEST -> "Choose destination folder"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val dir = currentDir
            val vol = activeVolume
            if (dir == null || vol == null) {
                // Storage volume chooser
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(titleText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Pick a drive to browse or use its root folder",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    volumes.forEach { v ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(cs.surfaceVariant)
                                .clickable {
                                    activeVolume = v
                                    currentDir = v.dir
                                }
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (v.isPrimary) "📱" else "💾", fontSize = 22.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    v.dir.absolutePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onUseSystemPicker) { Text("System picker (SAF)") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            } else {
                val atVolumeRoot = dir.absolutePath.trimEnd('/') == vol.dir.absolutePath.trimEnd('/')
                val currentName = if (atVolumeRoot) vol.name else dir.name
                val relPath = dir.absolutePath.removePrefix(vol.dir.absolutePath).trim('/')
                val breadcrumb = if (relPath.isEmpty()) "${vol.name} (root)" else "${vol.name} › " + relPath.replace("/", " › ")

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!atVolumeRoot || volumes.size > 1) {
                        IconButton(onClick = {
                            if (atVolumeRoot) {
                                currentDir = null
                                activeVolume = null
                            } else {
                                currentDir = dir.parentFile ?: vol.dir
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            currentName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            breadcrumb,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!multi) {
                        TextButton(onClick = { showNewFolder = true }) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("New")
                        }
                    }
                }

                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (errorMsg != null) {
                    Text(errorMsg!!, style = MaterialTheme.typography.bodyMedium, color = cs.error)
                } else if (children.isEmpty()) {
                    Text(
                        "No subfolders here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                } else {
                    if (multi) {
                        val selectable = children.filterNot(isAdded).map { it.absolutePath }
                        val allSelected = selectable.isNotEmpty() && selectable.all { it in selectedPaths }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${formatCount(children.size)} folder${if (children.size > 1) "s" else ""}" +
                                    if (selectedPaths.isNotEmpty()) " · ${formatCount(selectedPaths.size)} selected" else "",
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (selectable.isNotEmpty()) {
                                TextButton(onClick = {
                                    selectedPaths = if (allSelected) emptySet() else selectable.toSet()
                                }) {
                                    Text(if (allSelected) "Select none" else "Select all")
                                }
                            }
                        }
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
                            .verticalScroll(scroll)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        children.forEach { child ->
                            if (multi) {
                                val already = isAdded(child)
                                val checked = child.absolutePath in selectedPaths
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (checked) cs.primary.copy(alpha = 0.08f) else cs.surface)
                                        .clickable(enabled = !already) {
                                            selectedPaths = if (checked) selectedPaths - child.absolutePath
                                            else selectedPaths + child.absolutePath
                                        }
                                        .padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked || already,
                                        enabled = !already,
                                        onCheckedChange = {
                                            selectedPaths = if (checked) selectedPaths - child.absolutePath
                                            else selectedPaths + child.absolutePath
                                        },
                                    )
                                    Text("📁", fontSize = 16.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        child.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = if (already) cs.onSurfaceVariant else cs.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (already) {
                                        Spacer(Modifier.width(6.dp))
                                        Badge("Added", Success)
                                    }
                                    IconButton(onClick = { currentDir = child }) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = "Open ${child.name}",
                                            tint = cs.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { currentDir = child }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("📁", fontSize = 16.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        child.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = cs.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onUseSystemPicker) { Text("System picker") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (multi) {
                                val picked = if (selectedPaths.isNotEmpty()) {
                                    children.filter { it.absolutePath in selectedPaths }
                                } else {
                                    listOf(dir)
                                }
                                onPickMulti(picked)
                            } else {
                                onPickSingle(dir)
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        val label = when {
                            multi && selectedPaths.isNotEmpty() ->
                                "Add ${formatCount(selectedPaths.size)} folder${if (selectedPaths.size > 1) "s" else ""}"
                            multi -> "Add \"$currentName\""
                            else -> "Use \"$currentName\""
                        }
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    if (showNewFolder && currentDir != null) {
        var name by remember { mutableStateOf("") }
        var createErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; createErr = null },
                        label = { Text("Folder name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (createErr != null) {
                        Text(createErr!!, style = MaterialTheme.typography.bodySmall, color = cs.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isEmpty() || trimmed.contains('/') || trimmed == "." || trimmed == "..") {
                            createErr = "Enter a valid folder name."
                            return@Button
                        }
                        val targetDir = File(currentDir!!, trimmed)
                        if (targetDir.exists() || targetDir.mkdirs()) {
                            showNewFolder = false
                            currentDir = targetDir
                            refreshTick++
                        } else {
                            createErr = "Could not create folder here. Try System picker (SAF)."
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel") } },
        )
    }
}
