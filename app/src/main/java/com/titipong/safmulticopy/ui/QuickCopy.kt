@file:OptIn(ExperimentalMaterial3Api::class)

package com.titipong.safmulticopy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titipong.safmulticopy.FolderPickerState
import com.titipong.safmulticopy.FolderRef
import com.titipong.safmulticopy.MainViewModel
import com.titipong.safmulticopy.ui.theme.Success
import com.titipong.safmulticopy.util.formatCount
import com.titipong.safmulticopy.util.formatSize

private const val FOLDERS_PREVIEW = 6
private const val FILES_PREVIEW = 5

@Composable
internal fun SourcesCard(
    folders: List<FolderRef>,
    files: List<FolderRef>,
    enabled: Boolean,
    onAddFolder: () -> Unit,
    onAddFiles: () -> Unit,
    onRemoveFolder: (FolderRef) -> Unit,
    onClearFolders: () -> Unit,
    onRemoveFile: (FolderRef) -> Unit,
    onClearFiles: () -> Unit,
) {
    var showAllFolders by remember { mutableStateOf(false) }
    var showAllFiles by remember { mutableStateOf(false) }
    val summary = listOfNotNull(
        folders.size.takeIf { it > 0 }?.let { "${formatCount(it)} folder${if (it > 1) "s" else ""}" },
        files.size.takeIf { it > 0 }?.let {
            "${formatCount(it)} file${if (it > 1) "s" else ""} (${formatSize(files.sumOf { f -> f.size })})"
        },
    ).joinToString(" · ")

    SectionCard(
        title = "Copy from",
        subtitle = summary.ifEmpty { "Folders or files — internal storage, SD card or USB OTG" },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val visibleFolders = if (showAllFolders) folders else folders.take(FOLDERS_PREVIEW)
            visibleFolders.forEach { ref ->
                FolderRow(ref, "📁", if (enabled) ({ onRemoveFolder(ref) }) else null)
            }
            if (folders.size > FOLDERS_PREVIEW || (folders.size > 1 && enabled)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (folders.size > FOLDERS_PREVIEW) {
                        TextButton(onClick = { showAllFolders = !showAllFolders }) {
                            Text(if (showAllFolders) "Show less" else "Show all ${formatCount(folders.size)} folders")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (enabled && folders.size > 1) {
                        TextButton(onClick = onClearFolders) {
                            Text("Remove all folders", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            val visible = if (showAllFiles) files else files.take(FILES_PREVIEW)
            visible.forEach { ref ->
                FolderRow(ref, "📄", if (enabled) ({ onRemoveFile(ref) }) else null)
            }
            if (files.size > FILES_PREVIEW || (files.size > 1 && enabled)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (files.size > FILES_PREVIEW) {
                        TextButton(onClick = { showAllFiles = !showAllFiles }) {
                            Text(if (showAllFiles) "Show less" else "Show all ${formatCount(files.size)} files")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (enabled && files.size > 1) {
                        TextButton(onClick = onClearFiles) {
                            Text("Remove all files", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onAddFolder,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Folders")
                }
                FilledTonalButton(
                    onClick = onAddFiles,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Files")
                }
            }
            if (enabled) {
                Text(
                    "Folders: pick a parent folder to select several subfolders at once · Files: long-press for several",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun DestinationCard(dest: FolderRef?, enabled: Boolean, onPick: () -> Unit) {
    SectionCard(
        title = "Destination",
        subtitle = "Folders are copied into their own sub-folder · files go straight here",
    ) {
        if (dest == null) {
            Button(
                onClick = onPick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Choose destination") }
        } else {
            FolderRow(dest, "💾", null)
            if (enabled) {
                TextButton(onClick = onPick, modifier = Modifier.align(Alignment.End)) { Text("Change") }
            }
        }
    }
}

/** Copy mode + excludes for quick copy (folder pairs have their own). */
@Composable
internal fun QuickOptionsCard(vm: MainViewModel, enabled: Boolean) {
    SectionCard(title = "Quick copy options") {
        CopyModeSelector(mirror = vm.mirror, enabled = enabled) { vm.mirror = it; vm.save() }

        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        ExcludeFields(
            files = vm.excludeFiles,
            dirs = vm.excludeDirs,
            enabled = enabled,
            onFiles = { vm.excludeFiles = it },
            onDirs = { vm.excludeDirs = it },
        )
    }
}

@Composable
internal fun ExcludeFields(
    files: String,
    dirs: String,
    enabled: Boolean,
    onFiles: (String) -> Unit,
    onDirs: (String) -> Unit,
) {
    // Hidden until tapped — most people never need it.
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val summary = listOf(files, dirs).filter { it.isNotBlank() }.joinToString("; ")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = !open }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Exclude files or folders", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (summary.isEmpty()) "Optional — tap to set" else summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (summary.isEmpty()) cs.onSurfaceVariant else cs.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "Hide" else "Show",
                tint = cs.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = open) {
            Column {
                Text(
                    "Separate with ;  — * and ? wildcards",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = files,
                    onValueChange = onFiles,
                    label = { Text("Files") },
                    placeholder = { Text("*.tmp; Thumbs.db") },
                    singleLine = true,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dirs,
                    onValueChange = onDirs,
                    label = { Text("Folders") },
                    placeholder = { Text(".thumbnails; .trash*") },
                    singleLine = true,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Multi-select subfolder picker for Quick copy. Android's system folder picker only returns one
 * folder at a time, so after picking a parent folder the user can tick several subfolders at once,
 * browse deeper into any subfolder, or add the current folder itself.
 */
@Composable
internal fun MultiFolderPickerSheet(
    picker: FolderPickerState,
    isAdded: (String) -> Boolean,
    onBrowseInto: (docId: String, name: String) -> Unit,
    onBrowseUp: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme
    var selected by remember(picker.rootTree, picker.currentDocId) { mutableStateOf(emptySet<String>()) }
    val scroll = rememberScrollState()
    LaunchedEffect(picker.currentDocId) { scroll.scrollTo(0) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (picker.canGoUp) {
                    IconButton(onClick = onBrowseUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        picker.currentName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (picker.stack.size > 1) picker.stack.joinToString(" › ") { it.second }
                        else "Tick folders to add, or add this whole folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (picker.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (picker.error != null) {
                Text(
                    picker.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.error,
                )
            } else if (picker.subfolders.isEmpty()) {
                Text(
                    "No subfolders inside \"${picker.currentName}\". Tap the button below to add this folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            } else {
                val selectableIds = picker.subfolders.map { it.docId }.filterNot(isAdded)
                val allSelected = selectableIds.isNotEmpty() && selectableIds.all { it in selected }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${formatCount(picker.subfolders.size)} subfolder${if (picker.subfolders.size > 1) "s" else ""}" +
                            if (selected.isNotEmpty()) " · ${formatCount(selected.size)} selected" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (selectableIds.isNotEmpty()) {
                        TextButton(onClick = {
                            selected = if (allSelected) emptySet() else selectableIds.toSet()
                        }) {
                            Text(if (allSelected) "Select none" else "Select all")
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
                        .verticalScroll(scroll)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    picker.subfolders.forEach { entry ->
                        val already = isAdded(entry.docId)
                        val checked = entry.docId in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        checked -> cs.primary.copy(alpha = 0.08f)
                                        else -> cs.surface
                                    },
                                )
                                .clickable(enabled = !already) {
                                    selected = if (checked) selected - entry.docId else selected + entry.docId
                                }
                                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked || already,
                                enabled = !already,
                                onCheckedChange = {
                                    selected = if (checked) selected - entry.docId else selected + entry.docId
                                },
                            )
                            Text("📁", fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.name,
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
                            IconButton(onClick = { onBrowseInto(entry.docId, entry.name) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Open ${entry.name}",
                                    tint = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onConfirm(selected) },
                    enabled = !picker.loading,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        if (selected.isNotEmpty()) {
                            "Add ${formatCount(selected.size)} folder${if (selected.size > 1) "s" else ""}"
                        } else {
                            "Add \"${picker.currentName}\" itself"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
