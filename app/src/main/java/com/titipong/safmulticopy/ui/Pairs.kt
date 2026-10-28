@file:OptIn(ExperimentalMaterial3Api::class)

package com.titipong.safmulticopy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titipong.safmulticopy.PairDraft
import com.titipong.safmulticopy.data.FolderPair
import com.titipong.safmulticopy.engine.CopyState
import com.titipong.safmulticopy.engine.TaskStatus
import com.titipong.safmulticopy.ui.theme.Success
import com.titipong.safmulticopy.ui.theme.Warn
import com.titipong.safmulticopy.util.formatAgo

/** The folder pair list, added to the main LazyColumn. */
internal fun LazyListScope.pairItems(
    pairs: List<FolderPair>,
    st: CopyState,
    canAccess: (FolderPair) -> Boolean,
    enabled: Boolean,
    onToggle: (FolderPair, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onEdit: (FolderPair) -> Unit,
    onSyncOne: (FolderPair) -> Unit,
    onNew: () -> Unit,
) {
    if (pairs.isEmpty()) {
        item(key = "pairs-empty") { EmptyPairs(enabled, onNew) }
        return
    }
    item(key = "pairs-header") {
        val selected = pairs.count { it.selected }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${pairs.size} pair${if (pairs.size > 1) "s" else ""} · $selected selected",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (enabled) {
                val all = selected == pairs.size
                TextButton(onClick = { onSelectAll(!all) }) { Text(if (all) "Select none" else "Select all") }
            }
        }
    }
    items(pairs, key = { "pair-" + it.id }) { p ->
        PairCard(
            pair = p,
            accessible = canAccess(p),
            active = st.running && st.taskId == p.id,
            queued = st.running && p.id in st.pendingIds,
            enabled = enabled,
            onToggle = { onToggle(p, it) },
            onEdit = { onEdit(p) },
            onSync = { onSyncOne(p) },
        )
    }
    if (enabled) {
        item(key = "pairs-new") {
            OutlinedButton(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("New folder pair")
            }
        }
    }
}

@Composable
private fun EmptyPairs(enabled: Boolean, onNew: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("📁 ⇄ 💾", fontSize = 32.sp)
            Spacer(Modifier.height(12.dp))
            Text("No folder pairs yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "A folder pair remembers a source and a destination, so you can sync them again with one tap. " +
                    "Tick several pairs and they sync one after another.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onNew,
                enabled = enabled,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create folder pair")
            }
        }
    }
}

@Composable
private fun PairCard(
    pair: FolderPair,
    accessible: Boolean,
    active: Boolean,
    queued: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onSync: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onEdit,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = cs.surface,
            disabledContainerColor = cs.surface,
            disabledContentColor = cs.onSurface,
        ),
        border = BorderStroke(
            if (active) 2.dp else 1.dp,
            when {
                active -> cs.primary
                pair.selected -> cs.primary.copy(alpha = 0.35f)
                else -> cs.outlineVariant
            },
        ),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 14.dp, top = 8.dp, bottom = 12.dp)) {
            Checkbox(checked = pair.selected, onCheckedChange = onToggle, enabled = enabled)
            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pair.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    // Always show the mode so it's clear what Sync will do.
                    if (pair.mirror) Badge("🪞 MIRROR", cs.error) else Badge("➕ MERGE", cs.primary)
                }
                Spacer(Modifier.height(8.dp))
                Endpoint("📁", pair.sourceName, pair.sourceLocation)
                Text("↓", color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                Endpoint("💾", pair.destName, pair.destLocation)
                val excl = listOf(pair.excludeFiles, pair.excludeDirs).filter { it.isNotBlank() }.joinToString("; ")
                if (excl.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Excludes: $excl",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        when {
                            active -> Badge("SYNCING NOW", cs.primary)
                            queued -> Badge("WAITING IN QUEUE", cs.onSurfaceVariant)
                            else -> PairStatus(pair, accessible)
                        }
                    }
                    if (enabled) {
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = onSync,
                            enabled = accessible,
                            modifier = Modifier.height(38.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sync")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Endpoint(emoji: String, name: String, location: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                location,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PairStatus(pair: FolderPair, accessible: Boolean) {
    val cs = MaterialTheme.colorScheme
    val (text, color) = when {
        !accessible -> "⚠ No access — tap to pick the folders again" to cs.error
        pair.lastSyncAt == 0L -> "Never synced" to cs.onSurfaceVariant
        else -> {
            val ago = formatAgo(pair.lastSyncAt)
            when (pair.lastStatus) {
                TaskStatus.OK.name -> "✓ $ago · ${pair.lastSummary}" to Success
                TaskStatus.ERRORS.name -> "⚠ $ago · ${pair.lastSummary}" to Warn
                TaskStatus.CANCELLED.name -> "■ Stopped $ago · ${pair.lastSummary}" to cs.onSurfaceVariant
                else -> "✖ $ago · ${pair.lastSummary}" to cs.error
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

// ---------------------------------------------------------------- editor

@Composable
internal fun PairEditorSheet(
    draft: PairDraft,
    onChange: (PairDraft) -> Unit,
    onPickSource: () -> Unit,
    onPickDest: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (draft.id == null) "New folder pair" else "Edit folder pair",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (onMoveUp != null || onMoveDown != null) {
                    TextButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                    }
                    TextButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                    }
                }
            }

            OutlinedTextField(
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it)) },
                label = { Text("Name") },
                placeholder = { Text(draft.defaultName) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Source", style = MaterialTheme.typography.labelLarge)
                PickRow(draft.source, "📁", "Choose source folder", onPickSource)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Destination", style = MaterialTheme.typography.labelLarge)
                PickRow(draft.destination, "💾", "Choose destination folder", onPickDest)
                Text(
                    "The contents of the source are synced straight into this folder (no extra sub-folder).",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = cs.outlineVariant)
            CopyModeSelector(mirror = draft.mirror, enabled = true) { onChange(draft.copy(mirror = it)) }
            HorizontalDivider(color = cs.outlineVariant)
            ExcludeFields(
                files = draft.excludeFiles,
                dirs = draft.excludeDirs,
                enabled = true,
                onFiles = { onChange(draft.copy(excludeFiles = it)) },
                onDirs = { onChange(draft.copy(excludeDirs = it)) },
            )

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = cs.error) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSave,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Save") }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this folder pair?") },
            text = { Text("Only the pair is removed. No files are deleted.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
