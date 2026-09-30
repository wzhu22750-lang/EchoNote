package com.echonote.app.ui.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echonote.app.data.settings.TranscriptionTrigger
import com.echonote.app.di.AppContainer
import com.echonote.app.domain.Recording
import com.echonote.app.ui.common.CardTone
import com.echonote.app.ui.common.EmptyState
import com.echonote.app.ui.common.InfoCard
import com.echonote.app.ui.common.SectionHeader
import com.echonote.app.ui.common.TranscriptionBadge
import com.echonote.app.ui.common.formatBytes
import com.echonote.app.ui.common.formatDuration
import com.echonote.app.ui.common.formatRelativeDay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenRecording: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenModels: () -> Unit,
    onOpenFeasibility: () -> Unit,
) {
    val recent by container.repository.observeRecent(20)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val today by container.repository.observeTodayStats()
        .collectAsStateWithLifecycle(initialValue = 0 to 0L)
    val stats by container.repository.observeStorageStats()
        .collectAsStateWithLifecycle(initialValue = null)
    val runningIds by container.transcription.runningIds.collectAsStateWithLifecycle()

    val installed = container.modelManager.installedBundles()
    val missingDefault = com.echonote.app.ai.ModelCatalog.defaultInstall.count {
        !container.modelManager.isInstalled(it)
    }

    // 模式 C：导入已有音频（兜底能力，AI 管线始终可用）。
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val id = container.transcription.importAudio(uri)
                    if (container.settings.current().transcriptionTrigger ==
                        TranscriptionTrigger.IMMEDIATE
                    ) {
                        container.transcription.enqueue(id)
                    }
                }.onSuccess { id ->
                    Toast.makeText(
                        container.context, "已导入（#$id），可在详情页开始转写", Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { t ->
                    Toast.makeText(
                        container.context, "导入失败：${t.message}", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header() }

        item { StatCards(todayCount = today.first, todayMs = today.second,
            freeBytes = container.repository.freeBytes()) }

        if (missingDefault > 0) {
            item {
                InfoCard(
                    tone = CardTone.WARN,
                    title = "需要下载 AI 模型（约 ${missingDefault} 个）",
                    body = "录音可以立即使用，但语音转写需要先下载模型。所有模型只在本机运行，不会上传任何音频。",
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenModels, modifier = Modifier.weight(1f)) { Text("管理模型") }
                    OutlinedButton(onClick = onOpenFeasibility, modifier = Modifier.weight(1f)) {
                        Text("可行性测试")
                    }
                }
            }
        }

        item { RecordButton(onOpenRecording) }

        item {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("audio/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("导入音频文件转写")
            }
        }

        if (runningIds.isNotEmpty()) {
            item { ActiveTranscription(runningIds, container) }
        }

        item { SectionHeader("最近记录") }

        if (recent.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "还没有录音",
                    subtitle = "点击上方「开始录音」，或在设置里查看通话录音的可行方案",
                )
            }
        } else {
            items(recent, key = { it.id }) { recording ->
                RecordingCard(recording = recording, onClick = { onOpenDetail(recording.id) })
            }
        }

        item {
            TextButton(onClick = onOpenFeasibility, modifier = Modifier.fillMaxWidth()) {
                Text("查看《微信通话音频可行性报告》")
            }
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            "EchoNote",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "本地优先的通话录音与逐字稿",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun StatCards(todayCount: Int, todayMs: Long, freeBytes: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard("今日录音", "$todayCount 条", Icons.Filled.AddCircle, Modifier.weight(1f))
        StatCard("今日时长", formatDuration(todayMs), Icons.Filled.Schedule, Modifier.weight(1f))
        StatCard("可用空间", formatBytes(freeBytes), Icons.Filled.Folder, Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun RecordButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = androidx.compose.material3.MaterialTheme.shapes.large,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("开始录音", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ActiveTranscription(ids: Set<Long>, container: AppContainer) {
    val progressMap by container.transcription.progress.collectAsStateWithLifecycle()
    val first = ids.first()
    val p = progressMap[first]
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("正在转写 ${ids.size} 条录音…",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(p?.detail ?: "准备中", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun RecordingCard(recording: Recording, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(recording.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                TranscriptionBadge(recording.transcriptionStatus)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(formatRelativeDay(recording.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                Text("时长 ${formatDuration(recording.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                if (recording.fileSize > 0) {
                    Text(formatBytes(recording.fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
            if (recording.transcriptPreview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(recording.transcriptPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (recording.imported) {
                Spacer(Modifier.height(6.dp))
                Text("导入的音频 · ${recording.captureLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
