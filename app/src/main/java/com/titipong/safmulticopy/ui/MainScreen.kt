@file:OptIn(ExperimentalMaterial3Api::class)

package com.titipong.safmulticopy.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.RequiresApi
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titipong.safmulticopy.MainViewModel
import com.titipong.safmulticopy.PairDraft
import com.titipong.safmulticopy.data.FolderPair
import com.titipong.safmulticopy.engine.CopyState
import com.titipong.safmulticopy.engine.Phase
import com.titipong.safmulticopy.engine.TaskResult
import com.titipong.safmulticopy.engine.TaskStatus
import com.titipong.safmulticopy.ui.theme.Success
import com.titipong.safmulticopy.ui.theme.Warn
import com.titipong.safmulticopy.util.formatCount
import com.titipong.safmulticopy.util.formatDuration
import com.titipong.safmulticopy.util.formatSize
import com.titipong.safmulticopy.util.resultSummary
import java.io.File
import kotlin.math.roundToInt

private const val TAB_PAIRS = 0
private const val TAB_QUICK = 1

/** A pending "mirror will delete files" confirmation. */
private class MirrorConfirm(val names: List<String>, val action: () -> Unit)

@Composable
fun MainScreen(vm: MainViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    val pairs by vm.pairs.collectAsStateWithLifecycle()
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
    // Coming back from the "All files access" settings screen (or any app) — re-check permissions.
    LifecycleResumeEffect(Unit) {
        vm.refreshDateAccess()
        vm.refreshGrants()
        onPauseOrDispose { }
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshDateAccess()
    }
    fun requestDateAccess() {
        if (Build.VERSION.SDK_INT >= 30) openAllFilesAccess(context)
        else legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    LaunchedEffect(Unit) {
        vm.refreshGrants()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var errorText by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<MirrorConfirm?>(null) }
    var editing by remember { mutableStateOf<PairDraft?>(null) }
    var datePending by remember { mutableStateOf<(() -> Unit)?>(null) }

    /** Before a real copy: warn if files would get today's date instead of the original's. */
    fun withDateCheck(dryRun: Boolean, go: () -> Unit) {
        if (!dryRun && vm.datesWillBeLost) datePending = go else go()
    }

    // Quick copy pickers
    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addSource(uri)?.let { errorText = it }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)?.let { errorText = it }
    }
    val pickDest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setDestinationUri(uri)?.let { errorText = it }
    }
    // Pair editor pickers
    val pickPairSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val ref = vm.pickPairFolder(uri)
            if (ref == null) errorText = "Could not get permission for this folder."
            else editing = editing?.copy(source = ref)
        }
    }
    val pickPairDest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val ref = vm.pickPairFolder(uri)
            if (ref == null) errorText = "Could not get permission for this folder."
            else editing = editing?.copy(destination = ref)
        }
    }

    fun startQuick(dryRun: Boolean) {
        val err = vm.validate()
        if (err != null) {
            errorText = err
            return
        }
        withDateCheck(dryRun) {
            if (!dryRun && vm.mirror) confirm = MirrorConfirm(listOf("Quick copy")) { vm.start(false) }
            else vm.start(dryRun)
        }
    }

    fun startPairs(list: List<FolderPair>, dryRun: Boolean) {
        val err = vm.validatePairs(list)
        val mirrored = list.filter { it.mirror }
        if (err != null) {
            errorText = err
            return
        }
        withDateCheck(dryRun) {
            if (!dryRun && mirrored.isNotEmpty()) {
                confirm = MirrorConfirm(mirrored.map { it.name }) { vm.syncPairs(list, false) }
            } else {
                vm.syncPairs(list, dryRun)
            }
        }
    }

    val selectedPairs = pairs.filter { it.selected }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                TopAppBar(
                    title = {
                        Column {
                            Text("SAF MultiCopy", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Fast parallel folder copy & sync",
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
                TabSwitcher(
                    selected = vm.tab,
                    labels = listOf(
                        if (pairs.isEmpty()) "Folder pairs" else "Folder pairs (${pairs.size})",
                        "Quick copy",
                    ),
                    onSelect = vm::selectTab,
                )
            }
        },
        bottomBar = {
            if (running) {
                BottomActions(running = true, primaryLabel = "", primaryEnabled = false, onPreview = {}, onPrimary = {}, onStop = vm::stop)
            } else if (vm.tab == TAB_PAIRS) {
                if (pairs.isNotEmpty()) {
                    BottomActions(
                        running = false,
                        primaryLabel = if (selectedPairs.size == pairs.size && pairs.size > 1) "Sync all (${pairs.size})"
                        else "Sync selected (${selectedPairs.size})",
                        primaryEnabled = selectedPairs.isNotEmpty(),
                        primaryIsSync = true,
                        onPreview = { startPairs(selectedPairs, true) },
                        onPrimary = { startPairs(selectedPairs, false) },
                        onStop = vm::stop,
                    )
                }
            } else {
                BottomActions(
                    running = false,
                    primaryLabel = "Start copy",
                    primaryEnabled = true,
                    onPreview = { startQuick(true) },
                    onPrimary = { startQuick(false) },
                    onStop = vm::stop,
                )
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = pad.calculateTopPadding() + 8.dp,
                bottom = pad.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (st.phase != Phase.IDLE) {
                item(key = "progress") {
                    ProgressCard(st, onDismiss = vm::dismissResult)
                }
            }
            // One running bar per parallel copy (not for previews — nothing is copied).
            if (st.phase == Phase.COPYING && !st.dryRun && st.slots.isNotEmpty()) {
                item(key = "workers") { WorkersCard(st) }
            }
            if (st.events.isNotEmpty()) {
                item(key = "recent") {
                    RecentFilesCard(
                        st = st,
                        canShare = !running && st.logFile != null,
                        onShare = { shareLog(context, st.logFile) },
                    )
                }
            }

            if (vm.tab == TAB_PAIRS) {
                pairItems(
                    pairs = pairs,
                    st = st,
                    canAccess = vm::canAccess,
                    enabled = !running,
                    onToggle = { p, sel -> vm.setPairSelected(p.id, sel) },
                    onSelectAll = vm::setAllPairsSelected,
                    onEdit = { editing = vm.draftOf(it) },
                    onSyncOne = { startPairs(listOf(it), false) },
                    onNew = { editing = PairDraft() },
                )
            } else {
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
                item(key = "quick-options") { QuickOptionsCard(vm, enabled = !running) }
            }

            item(key = "settings") { SettingsCard(vm, enabled = !running, onAllowDates = ::requestDateAccess) }
            item(key = "credit") { CreditFooter() }
        }
    }

    editing?.let { draft ->
        val index = draft.id?.let { id -> pairs.indexOfFirst { it.id == id } } ?: -1
        PairEditorSheet(
            draft = draft,
            onChange = { editing = it },
            onPickSource = { pickPairSource.launch(draft.source?.uri) },
            onPickDest = { pickPairDest.launch(draft.destination?.uri) },
            onSave = {
                val err = vm.savePair(draft)
                if (err != null) errorText = err else editing = null
            },
            onDelete = draft.id?.let { id ->
                {
                    vm.deletePair(id)
                    editing = null
                }
            },
            onMoveUp = if (index > 0) ({ vm.movePair(draft.id!!, -1) }) else null,
            onMoveDown = if (index >= 0 && index < pairs.size - 1) ({ vm.movePair(draft.id!!, 1) }) else null,
            onDismiss = {
                vm.discardDraft()
                editing = null
            },
        )
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Mirror will delete files") },
            text = {
                Text(
                    (if (c.names.size == 1 && c.names[0] == "Quick copy") "" else
                        "Mirror mode is set for: " + c.names.joinToString { "\"$it\"" } + "\n\n") +
                        "Files and folders in the destination that are not in the source will be permanently deleted.\n\n" +
                        "Tip: tap Preview first to see exactly what would be deleted.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirm = null
                        c.action()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete & copy") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    datePending?.let { go ->
        AlertDialog(
            onDismissRequest = { datePending = null },
            icon = { Text("🕒", fontSize = 28.sp) },
            title = { Text("Keep the original dates?") },
            text = {
                Text(
                    "To give copies the same date as the originals, this app needs \"All files access\". " +
                        "Without it, copied files get today's date and photos may sort in the wrong order.\n\n" +
                        "Tap Allow, switch on \"Allow access to manage all files\", then come back and start again.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    datePending = null
                    requestDateAccess()
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    datePending = null
                    go()
                }) { Text("Copy with today's date") }
            },
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

// ---------------------------------------------------------------- tabs + settings

@Composable
private fun TabSwitcher(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceContainerHighest)
            .padding(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) cs.surface else cs.surfaceContainerHighest)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) cs.primary else cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Settings shared by folder pairs and quick copy. */
@Composable
private fun SettingsCard(vm: MainViewModel, enabled: Boolean, onAllowDates: () -> Unit) {
    SectionCard(title = "Settings", subtitle = "Used by folder pairs and quick copy") {
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
            title = "Keep original dates",
            subtitle = when {
                !vm.keepDates -> "Off — copies get today's date"
                vm.hasDateAccess -> "Copies get the same modified date as the original ✓"
                else -> "Needs \"All files access\" — tap Allow below"
            },
            checked = vm.keepDates,
            enabled = enabled,
        ) { vm.keepDates = it; vm.save() }
        if (vm.keepDates && !vm.hasDateAccess) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Warn.copy(alpha = 0.10f))
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Without it, copied photos get today's date and may sort in the wrong order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onAllowDates,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(36.dp),
                ) { Text("Allow") }
            }
        }
        SwitchRow(
            title = "Keep screen on",
            subtitle = "While copying (copy also continues with the screen off)",
            checked = vm.keepScreenOn,
            enabled = true,
        ) { vm.keepScreenOn = it; vm.save() }
        SwitchRow(
            title = "Log skipped files",
            subtitle = "Also write skipped files to the full log file",
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
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
            )
            StepButton("+", enabled && vm.retries < 10) { vm.retries++; vm.save() }
        }
    }
}

// ---------------------------------------------------------------- progress

@Composable
private fun ProgressCard(st: CopyState, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val now = if (st.finishedAt > 0) st.finishedAt else System.currentTimeMillis()
    val hasErrors = st.hasErrors
    val queue = st.isQueue
    val (title, accent) = when (st.phase) {
        Phase.SCANNING -> "Scanning folders…" to cs.primary
        Phase.DELETING -> "Removing extra files…" to Warn
        Phase.PREPARING -> "Creating folders…" to cs.primary
        Phase.COPYING -> (if (st.dryRun) "Checking…" else if (queue) "Syncing…" else "Copying…") to cs.primary
        Phase.DONE -> when {
            hasErrors -> "Finished with errors" to cs.error
            st.dryRun -> "Preview complete" to Success
            queue -> "All pairs synced" to Success
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

            if (queue && st.running) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge("PAIR ${st.taskIndex + 1} / ${st.taskCount}", cs.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        st.taskLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (st.taskCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    QueueDots(done = st.results, index = st.taskIndex, count = st.taskCount)
                }
            }
            if (st.running && st.phase != Phase.COPYING && st.message.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
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
                        "${formatCount(st.filesDone)} / ${formatCount(st.filesToCopy)} files" +
                            if (queue && st.running) " (this pair)" else "",
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
                CountTile("Skipped", st.skipped, cs.onSurfaceVariant, Modifier.weight(1f))
                CountTile("Failed", st.failed, cs.error, Modifier.weight(1f))
                CountTile(if (st.dryRun) "To delete" else "Deleted", st.deleted, Warn, Modifier.weight(1f))
            }
            DateNotes(st)

            if (st.finished && queue && st.results.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("Results", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    st.results.forEach { ResultRow(it, st.dryRun) }
                    val notRun = st.taskCount - st.results.size
                    if (notRun > 0) {
                        Text(
                            "■ $notRun pair${if (notRun > 1) "s" else ""} not started",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** One dot per pair in the queue: done (coloured by result), running, waiting. */
@Composable
private fun QueueDots(done: List<TaskResult>, index: Int, count: Int) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until count.coerceAtMost(24)) {
            val color = when {
                i < done.size -> when (done[i].status) {
                    TaskStatus.OK -> Success
                    TaskStatus.ERRORS -> Warn
                    else -> cs.error
                }
                i == index -> cs.primary
                else -> cs.outlineVariant
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun ResultRow(r: TaskResult, dryRun: Boolean) {
    val cs = MaterialTheme.colorScheme
    val (icon, color) = when (r.status) {
        TaskStatus.OK -> "✓" to Success
        TaskStatus.ERRORS -> "⚠" to Warn
        TaskStatus.FAILED -> "✖" to cs.error
        TaskStatus.CANCELLED -> "■" to cs.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                resultSummary(r, dryRun),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(formatDuration(r.durationMs), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- bottom bar

@Composable
private fun BottomActions(
    running: Boolean,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPreview: () -> Unit,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    primaryIsSync: Boolean = false,
) {
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
                    enabled = primaryEnabled,
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Preview") }
                Button(
                    onClick = onPrimary,
                    enabled = primaryEnabled,
                    modifier = Modifier.weight(1.8f).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(if (primaryIsSync) Icons.Filled.Refresh else Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(primaryLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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


/** Notes about file dates under the counters. */
@Composable
private fun DateNotes(st: CopyState) {
    val cs = MaterialTheme.colorScheme
    val notes = buildList {
        if (st.datesUnavailable) add("⚠ Original dates not kept — \"All files access\" is off (see Settings)" to Warn)
        if (st.datesFixed > 0) {
            add(
                "🕒 ${formatCount(st.datesFixed)} earlier copies ${if (st.dryRun) "will get" else "got"} their original date back" to
                    Color(0xFF0F766E),
            )
        }
        if (st.datesNotKept > 0) add("⚠ Couldn't keep the original date on ${formatCount(st.datesNotKept)} files" to Warn)
    }
    if (notes.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes.forEach { (text, color) ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (color == Warn) cs.onSurface else color,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Opens Android's "All files access" screen for this app. */
@RequiresApi(30)
private fun openAllFilesAccess(context: Context) {
    val pkg = Uri.parse("package:${context.packageName}")
    val direct = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
    try {
        context.startActivity(direct)
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

/** App name, version and credit at the bottom of the screen. */
@Composable
private fun CreditFooter() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "SAF MultiCopy" + (version?.let { " v$it" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "by NorthXbar",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
