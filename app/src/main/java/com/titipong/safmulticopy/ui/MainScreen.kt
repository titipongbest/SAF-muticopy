package com.titipong.safmulticopy.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titipong.safmulticopy.FolderRef
import com.titipong.safmulticopy.MainViewModel
import com.titipong.safmulticopy.engine.CopyState
import com.titipong.safmulticopy.engine.Level
import com.titipong.safmulticopy.engine.LogLine
import com.titipong.safmulticopy.engine.Phase
import com.titipong.safmulticopy.ui.theme.LogBackground
import com.titipong.safmulticopy.ui.theme.Success
import com.titipong.safmulticopy.ui.theme.Warn
import com.titipong.safmulticopy.util.formatCount
import com.titipong.safmulticopy.util.formatDuration
import com.titipong.safmulticopy.util.formatSize
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    val running = st.running
    val context = LocalContext.current

    // Keep the screen on while a job runs (the service's wake lock keeps it going even if the screen does turn off).
    val view = LocalView.current
    val keepOn = running && vm.keepScreenOn
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var errorText by remember { mutableStateOf<String?>(null) }
    var confirmMirror by remember { mutableStateOf(false) }

    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addSource(uri)?.let { errorText = it }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)?.let { errorText = it }
    }
    val pickDest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setDestinationUri(uri)?.let { errorText = it }
    }

    fun tryStart(dryRun: Boolean) {
        val err = vm.validate()
        when {
            err != null -> errorText = err
            !dryRun && vm.mirror -> confirmMirror = true
            else -> vm.start(dryRun)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SAF MultiCopy", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Fast parallel folder copy",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            BottomActions(
                running = running,
                onPreview = { tryStart(true) },
                onStart = { tryStart(false) },
                onStop = vm::stop,
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = pad.calculateTopPadding() + 4.dp,
                bottom = pad.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (st.phase != Phase.IDLE) {
                item(key = "progress") {
                    ProgressCard(st, onDismiss = vm::dismissResult)
                }
            }
            item(key = "sources") {
                SourcesCard(
                    folders = vm.sources,
                    files = vm.files,
                    enabled = !running,
                    onAddFolder = { pickSource.launch(null) },
                    onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onRemoveFolder = vm::removeSource,
                    onRemoveFile = vm::removeFile,
                    onClearFiles = vm::clearFiles,
                )
            }
            item(key = "dest") {
                DestinationCard(vm.destination, enabled = !running, onPick = { pickDest.launch(null) })
            }
            item(key = "options") { OptionsCard(vm, enabled = !running) }
            if (st.log.isNotEmpty()) {
                item(key = "log") {
                    LogCard(
                        lines = st.log,
                        canShare = !running && st.logFile != null,
                        onShare = { shareLog(context, st.logFile) },
                    )
                }
            }
        }
    }

    if (confirmMirror) {
        AlertDialog(
            onDismissRequest = { confirmMirror = false },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Mirror will delete files") },
            text = {
                Text(
                    "Files and folders in the destination that are not in the source will be permanently deleted.\n\n" +
                        "Tip: tap Preview first to see exactly what would be deleted.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmMirror = false
                        vm.start(false)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete & copy") }
            },
            dismissButton = { TextButton(onClick = { confirmMirror = false }) { Text("Cancel") } },
        )
    }

    errorText?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text("Heads up") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { errorText = null }) { Text("OK") } },
        )
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun FolderRow(ref: FolderRef, emoji: String, onRemove: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ref.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (ref.isFile) "${formatSize(ref.size)} · ${ref.location}" else ref.location,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun SourcesCard(
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

private const val FILES_PREVIEW = 5

@Composable
private fun DestinationCard(dest: FolderRef?, enabled: Boolean, onPick: () -> Unit) {
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

@Composable
private fun OptionsCard(vm: MainViewModel, enabled: Boolean) {
    SectionCard(title = "Options") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Parallel copies", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    "${vm.threads}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Slider(
            value = vm.threads.toFloat(),
            onValueChange = { vm.threads = it.roundToInt() },
            onValueChangeFinished = vm::save,
            valueRange = 1f..16f,
            steps = 14,
            enabled = enabled,
        )
        Text(
            "Many small files: 8–16 · slow USB stick or SD card: 4–8",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SwitchRow(
            title = "Mirror",
            subtitle = if (vm.mirror) "Deletes files in the destination that aren't in the source"
            else "Off — extra files in the destination are kept",
            checked = vm.mirror,
            enabled = enabled,
            danger = true,
        ) { vm.mirror = it; vm.save() }
        SwitchRow(
            title = "Keep screen on",
            subtitle = "While copying (copy also continues with the screen off)",
            checked = vm.keepScreenOn,
            enabled = true,
        ) { vm.keepScreenOn = it; vm.save() }
        SwitchRow(
            title = "Log unchanged files",
            subtitle = "Also list files that were skipped",
            checked = vm.showSkipped,
            enabled = enabled,
        ) { vm.showSkipped = it; vm.save() }

        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Retries", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "When a file fails to copy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StepButton("−", enabled && vm.retries > 0) { vm.retries--; vm.save() }
            Text(
                "${vm.retries}",
                modifier = Modifier.width(36.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontWeight = FontWeight.Bold,
            )
            StepButton("+", enabled && vm.retries < 10) { vm.retries++; vm.save() }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

        Text("Exclude", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Separate with ;  — * and ? wildcards",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = vm.excludeFiles,
            onValueChange = { vm.excludeFiles = it },
            label = { Text("Files") },
            placeholder = { Text("*.tmp; Thumbs.db") },
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = vm.excludeDirs,
            onValueChange = { vm.excludeDirs = it },
            label = { Text("Folders") },
            placeholder = { Text(".thumbnails; .trash*") },
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
    ) { Text(label, fontSize = 20.sp) }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    danger: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    val err = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (danger && checked) err else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = if (danger) SwitchDefaults.colors(checkedTrackColor = err) else SwitchDefaults.colors(),
        )
    }
}

// ---------------------------------------------------------------- progress

@Composable
private fun ProgressCard(st: CopyState, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val now = if (st.finishedAt > 0) st.finishedAt else System.currentTimeMillis()
    val hasErrors = st.failed > 0 || st.phase == Phase.FAILED
    val (title, accent) = when (st.phase) {
        Phase.SCANNING -> "Scanning folders…" to cs.primary
        Phase.DELETING -> "Removing extra files…" to Warn
        Phase.PREPARING -> "Creating folders…" to cs.primary
        Phase.COPYING -> (if (st.dryRun) "Checking…" else "Copying…") to cs.primary
        Phase.DONE -> when {
            hasErrors -> "Finished with errors" to cs.error
            st.dryRun -> "Preview complete" to Success
            else -> "All done" to Success
        }
        Phase.CANCELLED -> "Stopped" to cs.error
        Phase.FAILED -> "Something went wrong" to cs.error
        Phase.IDLE -> "" to cs.primary
    }
    val fraction = when {
        st.phase == Phase.DONE && !hasErrors -> 1f
        st.bytesToCopy > 0 -> (st.bytesDone.toFloat() / st.bytesToCopy).coerceIn(0f, 1f)
        st.filesToCopy > 0 -> (st.filesDone.toFloat() / st.filesToCopy).coerceIn(0f, 1f)
        else -> 0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                if (st.dryRun) Badge("PREVIEW", cs.primary)
                if (st.finished) TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            if (st.running && st.phase != Phase.COPYING && st.message.isNotBlank()) {
                Text(
                    st.message + if (st.phase == Phase.SCANNING) " · ${formatCount(st.scanned)} items" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))

            if (st.running && st.phase != Phase.COPYING) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = accent,
                    trackColor = cs.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = accent,
                    trackColor = cs.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
            }
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(fraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${formatCount(st.filesDone)} / ${formatCount(st.filesToCopy)} files",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${formatSize(st.bytesDone)} / ${formatSize(st.bytesToCopy)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }

            if (!st.dryRun && st.copyStartedAt > 0) {
                val elapsed = now - st.copyStartedAt
                val speed = if (elapsed > 1000) st.bytesDone * 1000 / elapsed else 0L
                val remaining = (st.bytesToCopy - st.bytesDone).coerceAtLeast(0)
                val eta = if (speed > 0) remaining * 1000 / speed else -1L
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Speed", if (speed > 0) "${formatSize(speed)}/s" else "—", Modifier.weight(1f))
                    StatTile("Elapsed", formatDuration(elapsed), Modifier.weight(1f))
                    StatTile(
                        if (st.finished) "Total" else "Left",
                        if (st.finished) formatDuration(now - st.startedAt)
                        else if (eta >= 0) formatDuration(eta) else "—",
                        Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountTile(if (st.dryRun) "To copy" else "Copied", st.copied, Success, Modifier.weight(1f))
                CountTile("Unchanged", st.skipped, cs.onSurfaceVariant, Modifier.weight(1f))
                CountTile("Failed", st.failed, cs.error, Modifier.weight(1f))
                CountTile(if (st.dryRun) "To delete" else "Deleted", st.deleted, Warn, Modifier.weight(1f))
            }

            if (st.running && st.current.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Working on", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                st.current.take(4).forEach {
                    Text(
                        "• $it",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (st.current.size > 4) {
                    Text(
                        "+${st.current.size - 4} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun CountTile(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(formatCount(value), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- log + bottom bar

@Composable
private fun LogCard(lines: List<LogLine>, canShare: Boolean, onShare: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size, lines.lastOrNull()) { scroll.scrollTo(scroll.maxValue) }
    SectionCard(
        title = "Log",
        subtitle = "Last ${lines.size} lines · full log can be shared",
        trailing = {
            if (canShare) {
                TextButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(LogBackground)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp))
                .verticalScroll(scroll)
                .padding(10.dp),
        ) {
            lines.forEach { l ->
                Text(
                    l.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = if (l.level == Level.HEAD) FontWeight.Bold else FontWeight.Normal,
                    color = when (l.level) {
                        Level.HEAD -> cs.primary
                        Level.INFO -> cs.onSurface
                        Level.OK -> Success
                        Level.WARN -> Warn
                        Level.ERROR -> cs.error
                        Level.DIM -> cs.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun BottomActions(running: Boolean, onPreview: () -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop", style = MaterialTheme.typography.titleMedium) }
            } else {
                OutlinedButton(
                    onClick = onPreview,
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Preview") }
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1.5f).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Start copy", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun shareLog(context: Context, path: String?) {
    val file = path?.let(::File)?.takeIf { it.exists() } ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share log"))
}
