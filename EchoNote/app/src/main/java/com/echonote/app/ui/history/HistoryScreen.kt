package com.echonote.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echonote.app.di.AppContainer
import com.echonote.app.domain.Recording
import com.echonote.app.ui.common.EmptyState
import com.echonote.app.ui.common.SectionHeader
import com.echonote.app.ui.common.TranscriptionBadge
import com.echonote.app.ui.common.formatBytes
import com.echonote.app.ui.common.formatDuration
import com.echonote.app.ui.common.formatRelativeDay
import kotlinx.coroutines.launch

private enum class HistoryFilter(val label: String) {
    ALL("全部"), UNTRANSCRIBED("未转写"), DONE("已转写"), FAILED("失败"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    container: AppContainer,
    onOpenDetail: (Long) -> Unit,
) {
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    val scope = rememberCoroutineScope()

    val recordings by container.repository.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val filtered = recordings.filter { r ->
        when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.UNTRANSCRIBED ->
                r.transcriptionStatus == com.echonote.app.data.db.TranscriptionStatus.NOT_STARTED ||
                    r.transcriptionStatus == com.echonote.app.data.db.TranscriptionStatus.QUEUED

            HistoryFilter.DONE -> r.transcriptionStatus == com.echonote.app.data.db.TranscriptionStatus.COMPLETED
            HistoryFilter.FAILED -> r.transcriptionStatus == com.echonote.app.data.db.TranscriptionStatus.FAILED
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SectionHeader("记录（${recordings.size} 条）")

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            HistoryFilter.entries.forEachIndexed { index, f ->
                SegmentedButton(
                    selected = filter == f,
                    onClick = { filter = f },
                    shape = SegmentedButtonDefaults.itemShape(index, HistoryFilter.entries.size),
                ) { Text(f.label) }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.List,
                title = when (filter) {
                    HistoryFilter.ALL -> "还没有录音"
                    HistoryFilter.UNTRANSCRIBED -> "没有待转写的录音"
                    HistoryFilter.DONE -> "还没有转写完成的录音"
                    HistoryFilter.FAILED -> "没有失败的录音"
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.id }) { recording ->
                    HistoryItem(
                        recording = recording,
                        onClick = { onOpenDetail(recording.id) },
                        onDelete = {
                            scope.launch {
                                container.repository.delete(recording.id)
                            }
                        },
                        onTranscribe = {
                            scope.launch { container.transcription.enqueue(recording.id) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(
    recording: Recording,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTranscribe: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(recording.title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(formatRelativeDay(recording.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                    Text(formatDuration(recording.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                    if (recording.fileSize > 0) Text(formatBytes(recording.fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.height(6.dp))
                TranscriptionBadge(recording.transcriptionStatus)
                if (recording.transcriptPreview.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(recording.transcriptPreview, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menuOpen = true }) {
                    androidx.compose.material3.Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (recording.transcriptionStatus !=
                        com.echonote.app.data.db.TranscriptionStatus.COMPLETED
                    ) {
                        DropdownMenuItem(
                            text = { Text("转写") },
                            leadingIcon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                            onClick = { menuOpen = false; onTranscribe() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}
