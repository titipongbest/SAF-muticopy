package com.titipong.safmulticopy.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titipong.safmulticopy.engine.CopyState
import com.titipong.safmulticopy.engine.EventKind
import com.titipong.safmulticopy.engine.FileEvent
import com.titipong.safmulticopy.engine.Phase
import com.titipong.safmulticopy.engine.SlotState
import com.titipong.safmulticopy.ui.theme.Success
import com.titipong.safmulticopy.ui.theme.Warn
import com.titipong.safmulticopy.util.formatCount
import com.titipong.safmulticopy.util.formatSize
import kotlin.math.roundToInt

// ---------------------------------------------------------------- one bar per parallel copy

/** One progress bar per parallel copy, each running for the file it is copying now. */
@Composable
internal fun WorkersCard(st: CopyState) {
    val cs = MaterialTheme.colorScheme
    val busy = st.slots.count { it.name != null }
    SectionCard(
        title = "Copying now",
        subtitle = "${st.slots.size} at a time · $busy busy",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            st.slots.forEach { slot ->
                // A new file gets a fresh bar that starts from its own progress, not the previous file's.
                key(slot.index, slot.name) { SlotRow(slot) }
            }
            if (st.skipped > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⏭", fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${formatCount(st.skipped)} files skipped — already the same in the destination",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotRow(slot: SlotState) {
    val cs = MaterialTheme.colorScheme
    val idle = slot.name == null
    val retrying = slot.retry > 0
    val color = when {
        idle -> cs.outlineVariant
        retrying -> Warn
        slot.update -> Color(0xFF7C3AED)
        else -> cs.primary
    }
    val progress by animateFloatAsState(
        targetValue = slot.fraction,
        animationSpec = tween(durationMillis = 280, easing = LinearEasing),
        label = "slot",
    )

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (idle) 0.4f else 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${slot.index + 1}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (idle) cs.onSurfaceVariant else color,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    idle -> "Waiting for next file…"
                    else -> slot.name!!.substringAfterLast('/')
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (idle) FontWeight.Normal else FontWeight.Medium,
                color = if (idle) cs.onSurfaceVariant else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!idle) {
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        retrying -> "Retry ${slot.retry}"
                        slot.size > 0 -> "${(slot.fraction * 100).roundToInt()}%"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        if (!idle && slot.size == 0L) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = color,
                trackColor = cs.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )
        } else {
            LinearProgressIndicator(
                progress = { if (idle) 0f else progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = color,
                trackColor = cs.surfaceVariant,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
        }
        if (!idle) {
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    append(if (slot.update) "Updating" else "New")
                    if (slot.size > 0) append(" · ${formatSize(slot.done)} / ${formatSize(slot.size)}")
                    val folder = slot.name!!.substringBeforeLast('/', "")
                    if (folder.isNotEmpty()) append(" · $folder")
                },
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- recent files

private enum class Filter(val label: String) { ALL("All"), COPIED("Copied"), SKIPPED("Skipped"), PROBLEMS("Problems") }

private data class KindStyle(val icon: String, val label: String, val color: Color)

@Composable
private fun styleOf(kind: EventKind): KindStyle {
    val cs = MaterialTheme.colorScheme
    return when (kind) {
        EventKind.COPIED -> KindStyle("✓", "Copied", Success)
        EventKind.UPDATED -> KindStyle("↻", "Updated", Color(0xFF7C3AED))
        EventKind.SKIPPED -> KindStyle("⏭", "Skipped · same", cs.onSurfaceVariant)
        EventKind.FAILED -> KindStyle("✖", "Failed", cs.error)
        EventKind.DELETED -> KindStyle("🗑", "Deleted", Warn)
        EventKind.WOULD_COPY -> KindStyle("+", "Will copy", cs.primary)
        EventKind.WOULD_UPDATE -> KindStyle("↻", "Will update", Color(0xFF7C3AED))
        EventKind.WOULD_DELETE -> KindStyle("🗑", "Will delete", Warn)
        EventKind.DATE_FIXED -> KindStyle("🕒", "Original date restored", Color(0xFF0F766E))
        EventKind.WOULD_FIX_DATE -> KindStyle("🕒", "Date will be restored", Color(0xFF0F766E))
    }
}

private fun matches(f: Filter, kind: EventKind): Boolean = when (f) {
    Filter.ALL -> true
    Filter.COPIED -> kind == EventKind.COPIED || kind == EventKind.UPDATED ||
        kind == EventKind.WOULD_COPY || kind == EventKind.WOULD_UPDATE
    Filter.SKIPPED -> kind == EventKind.SKIPPED
    Filter.PROBLEMS -> kind == EventKind.FAILED || kind == EventKind.DELETED || kind == EventKind.WOULD_DELETE
}

/** The latest files with a coloured status: copied, skipped, failed, deleted. */
@Composable
internal fun RecentFilesCard(
    events: List<FileEvent>,
    subtitle: String,
    canShare: Boolean,
    onShare: () -> Unit,
) {
    SectionCard(
        title = "Recent files",
        subtitle = subtitle,
        trailing = {
            if (canShare) {
                TextButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Full log")
                }
            }
        },
    ) {
        RecentFilesList(events)
    }
}

/** Filter chips + a fixed-height box of files that scrolls inside. */
@Composable
internal fun RecentFilesList(events: List<FileEvent>) {
    var filter by remember { mutableStateOf(Filter.ALL) }
    val shown = events.filter { matches(filter, it.kind) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Filter.entries.forEach { f ->
                FilterChipSmall(f.label, selected = f == filter) { filter = f }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Fixed-height box that scrolls inside, so the list never takes over the screen.
        val scroll = rememberScrollState()
        LaunchedEffect(filter) { scroll.scrollTo(0) }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .verticalScroll(scroll)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (shown.isEmpty()) {
                Text(
                    "Nothing here yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            shown.forEach { e -> key(e.id) { EventRow(e) } }
        }
    }
}

@Composable
private fun FilterChipSmall(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EventRow(e: FileEvent) {
    val cs = MaterialTheme.colorScheme
    val s = styleOf(e.kind)
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(s.color.copy(alpha = 0.06f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(s.color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) { Text(s.icon, color = s.color, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = if (expanded) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildString {
                append(s.label)
                if (e.size > 0) append(" · ${formatSize(e.size)}")
                e.detail?.let { append(" · $it") }
                val folder = e.path.substringBeforeLast('/', "")
                if (e.detail == null && folder.isNotEmpty()) append(" · $folder")
                if (expanded && e.detail != null) append("\n${e.path}")
            }
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = if (e.kind == EventKind.FAILED) s.color else cs.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Mirror: the file or folder being deleted right now, with "N of M". */
@Composable
internal fun DeletingRow(st: CopyState) {
    val path = st.deletingPath ?: return
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Warn.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🗑", fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val folder = path.substringBeforeLast('/', "")
                Text(
                    (if (st.deletingIsDir) "Deleting whole folder" else "Deleting file") +
                        if (folder.isNotEmpty()) " · in $folder" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${formatCount(st.deleteDone + 1)} / ${formatCount(st.deleteTotal)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Warn,
            )
        }
    }
}
