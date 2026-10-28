package com.titipong.safmulticopy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.titipong.safmulticopy.FolderRef
import com.titipong.safmulticopy.MainViewModel
import com.titipong.safmulticopy.util.formatCount
import com.titipong.safmulticopy.util.formatSize

private const val FILES_PREVIEW = 5

@Composable
internal fun SourcesCard(
    folders: List<FolderRef>,
    files: List<FolderRef>,
    enabled: Boolean,
    onAddFolder: () -> Unit,
    onAddFiles: () -> Unit,
    onRemoveFolder: (FolderRef) -> Unit,
    onRemoveFile: (FolderRef) -> Unit,
    onClearFiles: () -> Unit,
) {
    var showAllFiles by remember { mutableStateOf(false) }
    val summary = listOfNotNull(
        folders.size.takeIf { it > 0 }?.let { "$it folder${if (it > 1) "s" else ""}" },
        files.size.takeIf { it > 0 }?.let {
            "${formatCount(it)} file${if (it > 1) "s" else ""} (${formatSize(files.sumOf { f -> f.size })})"
        },
    ).joinToString(" · ")

    SectionCard(
        title = "Copy from",
        subtitle = summary.ifEmpty { "Folders or files — internal storage, SD card or USB OTG" },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            folders.forEach { ref ->
                FolderRow(ref, "📁", if (enabled) ({ onRemoveFolder(ref) }) else null)
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
                    Text("Folder")
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
            if (files.isEmpty() && enabled) {
                Text(
                    "Files: long-press to select several at once",
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

/** Mirror + excludes for quick copy (folder pairs have their own). */
@Composable
internal fun QuickOptionsCard(vm: MainViewModel, enabled: Boolean) {
    SectionCard(title = "Quick copy options") {
        SwitchRow(
            title = "Mirror",
            subtitle = if (vm.mirror) "Deletes files in the destination that aren't in the source"
            else "Off — extra files in the destination are kept",
            checked = vm.mirror,
            enabled = enabled,
            danger = true,
        ) { vm.mirror = it; vm.save() }

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
    Column {
        Text("Exclude", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Separate with ;  — * and ? wildcards",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
