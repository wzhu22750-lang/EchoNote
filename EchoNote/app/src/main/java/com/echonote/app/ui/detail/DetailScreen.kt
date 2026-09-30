package com.echonote.app.ui.detail

import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echonote.app.data.settings.EchoNoteSettings
import com.echonote.app.di.AppContainer
import com.echonote.app.domain.Speaker
import com.echonote.app.domain.TranscriptDetail
import com.echonote.app.domain.TranscriptSegment
import com.echonote.app.export.ExportFormat
import com.echonote.app.export.TranscriptExporter
import com.echonote.app.ui.common.EmptyState
import com.echonote.app.ui.common.SectionHeader
import com.echonote.app.ui.common.SpeakerChip
import com.echonote.app.ui.common.TranscriptionBadge
import com.echonote.app.ui.common.colorForSpeaker
import com.echonote.app.ui.common.formatBytes
import com.echonote.app.ui.common.formatClockWithMillis
import com.echonote.app.ui.common.formatDuration
import com.echonote.app.ui.common.formatRelativeDay
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 详情页 LazyColumn 里逐字稿列表之前的 item 数（动态计算，用于自动滚动的索引换算）。 */
private val PLAYBACK_SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 录音详情页 —— 产品的核心界面：音频播放与逐字稿联动。
 *
 * 点击逐字稿跳到对应音频位置；播放时高亮并自动滚动到当前段落；段落文本、说话人、
 * 时间戳均可编辑；说话人可重命名、标记「我」、互相合并；整篇可导出为 5 种格式。
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    container: AppContainer,
    recordingId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val detail by remember(recordingId) {
        container.repository.observeDetail(recordingId)
            .map { d: TranscriptDetail -> d as TranscriptDetail? }
            .catch { emit(null) }
    }.collectAsStateWithLifecycle(initialValue = null)

    val settings by container.settings.settings
        .collectAsStateWithLifecycle(initialValue = EchoNoteSettings())
    val progressMap by container.transcription.progress.collectAsStateWithLifecycle()
    val runningIds by container.transcription.runningIds.collectAsStateWithLifecycle()

    val recording = detail?.recording
    val isRunning = recordingId in runningIds
    val progress = progressMap[recordingId]

    // ------------------------------------------------------------- 播放器状态
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var mediaDuration by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    val audioPath = recording?.audioPath?.takeIf { recording.hasAudio }

    DisposableEffect(audioPath) {
        var prepared: MediaPlayer? = null
        if (audioPath != null) {
            try {
                val mp = MediaPlayer()
                mp.setDataSource(audioPath)
                mp.prepare()
                mediaDuration = mp.duration.takeIf { it > 0 }?.toLong()
                    ?: recording?.durationMs ?: 0L
                runCatching {
                    mp.playbackParams = mp.playbackParams.setSpeed(settings.playbackSpeed)
                }
                prepared = mp
                player = mp
            } catch (t: Throwable) {
                prepared?.release()
                player = null
            }
        }
        onDispose {
            player?.release()
            player = null
            isPlaying = false
        }
    }

    // 播放时每 200 ms 读取一次进度；播完自动回到暂停态。
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (isActive) {
            val mp = player ?: break
            positionMs = mp.currentPosition.toLong()
            if (mediaDuration > 0 && !mp.isPlaying && positionMs >= mediaDuration - 100) {
                isPlaying = false
                break
            }
            delay(200)
        }
    }

    fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            isPlaying = false
        } else {
            mp.start()
            isPlaying = true
        }
    }

    fun seekTo(ms: Long) {
        val mp = player ?: return
        mp.seekTo(ms.toInt())
        positionMs = ms
    }

    fun applySpeed(speed: Float) {
        scope.launch { container.settings.setPlaybackSpeed(speed) }
        runCatching { player?.let { it.playbackParams = it.playbackParams.setSpeed(speed) } }
    }

    // -------------------------------------------------------------- 对话框状态
    var editSegment by remember { mutableStateOf<TranscriptSegment?>(null) }
    var speakerSegment by remember { mutableStateOf<TranscriptSegment?>(null) }
    var timestampsSegment by remember { mutableStateOf<TranscriptSegment?>(null) }
    var speakerOps by remember { mutableStateOf<Speaker?>(null) }
    var renameSpeaker by remember { mutableStateOf<Speaker?>(null) }
    var mergeSource by remember { mutableStateOf<Speaker?>(null) }
    var renameRecording by remember { mutableStateOf(false) }
    var notesEditing by remember { mutableStateOf(false) }
    var exportFormatChoice by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportFormat?>(null) }
    var segmentMenuFor by remember { mutableStateOf<TranscriptSegment?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val snapshot = detail
        val format = pendingExport
        if (uri != null && snapshot != null && format != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val text = TranscriptExporter.export(snapshot, format)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
        pendingExport = null
    }

    // ------------------------------------------------------------------- 页面
    val current = detail
    if (current == null) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            title = "录音不存在或已被删除",
            subtitle = "可能刚被清理，请返回列表刷新",
        )
        return
    }

    val segments = current.segments
    val speakers = current.speakers
    // LazyColumn 头部：元信息卡 +（有音频时的播放卡）+（有说话人时的说话人行）+ 逐字稿标题。
    val headerCount = 2 +
        (if (audioPath != null) 1 else 0) +
        (if (speakers.isNotEmpty()) 1 else 0)
    val activeIndex = segments.indexOfFirst {
        positionMs >= it.startMs && positionMs < it.endMs
    }
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex, isPlaying) {
        if (isPlaying && activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex + headerCount)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(recording?.title ?: "", maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = { renameRecording = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "重命名")
                }
                IconButton(onClick = { notesEditing = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "备注")
                }
                IconButton(onClick = { exportFormatChoice = true }) {
                    Icon(Icons.Filled.IosShare, contentDescription = "导出")
                }
            },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 1) 元信息 + 转写状态
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatRelativeDay(recording?.createdAt ?: 0),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            if (recording != null) TranscriptionBadge(recording.transcriptionStatus)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append("时长 ${formatDuration(recording?.durationMs ?: 0)}")
                                if ((recording?.fileSize ?: 0) > 0) {
                                    append(" · ${formatBytes(recording!!.fileSize)}")
                                }
                                append(" · ${recording?.captureLabel ?: ""}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        recording?.errorMessage?.let { message ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "上次错误：$message",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        when {
                            isRunning -> {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "转写中：${progress?.stage?.name ?: "准备中"} ${progress?.detail ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                LinearProgressIndicator(
                                    progress = { progress?.fraction ?: 0f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                            }
                            recording != null && !recording.isTranscribed -> {
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { container.transcription.enqueue(recordingId) }) {
                                    Text(if (recording.transcriptionStatus.name == "FAILED") "重试转写" else "开始转写")
                                }
                                Text(
                                    "转写在本机进行，需要先在模型页下载 AI 模型；长录音可能需要几分钟。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 2) 播放器
            if (audioPath != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = ::togglePlay, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isPlaying) "暂停" else "播放",
                                        modifier = Modifier.size(36.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    "${formatDuration(positionMs)} / ${formatDuration(mediaDuration)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Slider(
                                value = positionMs.toFloat().coerceIn(0f, mediaDuration.toFloat().coerceAtLeast(1f)),
                                onValueChange = {
                                    dragging = true
                                    positionMs = it.toLong()
                                },
                                onValueChangeFinished = {
                                    seekTo(positionMs)
                                    dragging = false
                                },
                                enabled = mediaDuration > 0,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PLAYBACK_SPEEDS.forEach { speed ->
                                    FilterChip(
                                        selected = settings.playbackSpeed == speed,
                                        onClick = { applySpeed(speed) },
                                        label = { Text("${speed}x") },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3) 说话人
            if (speakers.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader("说话人（点击管理）")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(speakers, key = { it.id }) { speaker ->
                                Card(onClick = { speakerOps = speaker }) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SpeakerChip(
                                            name = speaker.name + if (speaker.isMe) "（我）" else "",
                                            index = speaker.colorIndex,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4) 逐字稿
            item { SectionHeader(if (segments.isEmpty()) "逐字稿" else "逐字稿（${segments.size} 段）") }

            if (segments.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.PlayArrow,
                        title = "还没有逐字稿",
                        subtitle = if (recording?.isTranscribed == true) "未识别到语音内容"
                        else "完成转写后会显示在这里",
                    )
                }
            } else {
                itemsIndexed(segments, key = { _, seg -> seg.id }) { index, segment ->
                    SegmentCard(
                        segment = segment,
                        speaker = current.speakerById(segment.speakerId),
                        active = index == activeIndex,
                        menuOpen = segmentMenuFor?.id == segment.id,
                        onMenuChange = { segmentMenuFor = it },
                        onClick = { seekTo(segment.startMs) },
                        onEditText = { editSegment = segment },
                        onChangeSpeaker = { speakerSegment = segment },
                        onEditTimestamps = { timestampsSegment = segment },
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 弹窗们
    editSegment?.let { segment ->
        TextEditDialog(
            title = "编辑文本",
            initial = segment.text,
            onDismiss = { editSegment = null },
        ) { newText ->
            scope.launch { container.repository.editSegmentText(segment.id, newText) }
            editSegment = null
        }
    }

    timestampsSegment?.let { segment ->
        TimestampsDialog(
            segment = segment,
            onDismiss = { timestampsSegment = null },
        ) { start, end ->
            scope.launch {
                container.repository.editSegmentTimestamps(segment.id, start, end)
            }
            timestampsSegment = null
        }
    }

    speakerSegment?.let { segment ->
        AlertDialog(
            onDismissRequest = { speakerSegment = null },
            title = { Text("更改该段说话人") },
            text = {
                Column {
                    speakers.forEach { speaker ->
                        TextButton(onClick = {
                            scope.launch {
                                container.repository.editSegmentSpeaker(segment.id, speaker.id)
                            }
                            speakerSegment = null
                        }) {
                            SpeakerChip(speaker.name, speaker.colorIndex)
                        }
                    }
                    TextButton(onClick = {
                        scope.launch { container.repository.editSegmentSpeaker(segment.id, null) }
                        speakerSegment = null
                    }) { Text("未知") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { speakerSegment = null }) { Text("取消") } },
        )
    }

    speakerOps?.let { speaker ->
        AlertDialog(
            onDismissRequest = { speakerOps = null },
            title = { Text("说话人：${speaker.name}") },
            text = {
                Column {
                    TextButton(onClick = {
                        renameSpeaker = speaker
                        speakerOps = null
                    }) { Text("重命名") }
                    if (!speaker.isMe) {
                        TextButton(onClick = {
                            scope.launch {
                                container.repository.markSpeakerAsMe(speaker.id, speaker.recordingId)
                            }
                            speakerOps = null
                        }) { Text("标为「我」") }
                    } else {
                        TextButton(onClick = {
                            scope.launch { container.repository.clearSelfMark(speaker.recordingId) }
                            speakerOps = null
                        }) { Text("清除「我」标记") }
                    }
                    if (speakers.size > 1) {
                        TextButton(onClick = {
                            mergeSource = speaker
                            speakerOps = null
                        }) { Text("合并到另一个说话人…") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { speakerOps = null }) { Text("关闭") } },
        )
    }

    renameSpeaker?.let { speaker ->
        TextEditDialog(
            title = "重命名说话人",
            initial = speaker.name,
            onDismiss = { renameSpeaker = null },
        ) { newName ->
            scope.launch { container.repository.renameSpeaker(speaker.id, newName) }
            renameSpeaker = null
        }
    }

    mergeSource?.let { source ->
        AlertDialog(
            onDismissRequest = { mergeSource = null },
            title = { Text("把「${source.name}」合并到…") },
            text = {
                Column {
                    speakers.filter { it.id != source.id }.forEach { target ->
                        TextButton(onClick = {
                            scope.launch {
                                container.repository.mergeSpeakers(source.id, target.id)
                            }
                            mergeSource = null
                        }) {
                            SpeakerChip(target.name, target.colorIndex)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergeSource = null }) { Text("取消") } },
        )
    }

    if (renameRecording) {
        TextEditDialog(
            title = "重命名录音",
            initial = recording?.title ?: "",
            onDismiss = { renameRecording = false },
        ) { newTitle ->
            scope.launch { container.repository.rename(recordingId, newTitle) }
            renameRecording = false
        }
    }

    if (notesEditing) {
        TextEditDialog(
            title = "备注",
            initial = recording?.notes ?: "",
            onDismiss = { notesEditing = false },
        ) { newNotes ->
            scope.launch { container.repository.updateNotes(recordingId, newNotes) }
            notesEditing = false
        }
    }

    if (exportFormatChoice) {
        AlertDialog(
            onDismissRequest = { exportFormatChoice = false },
            title = { Text("导出逐字稿") },
            text = {
                Column {
                    ExportFormat.entries.forEach { format ->
                        TextButton(onClick = {
                            pendingExport = format
                            exportLauncher.launch(TranscriptExporter.suggestedFileName(current, format))
                            exportFormatChoice = false
                        }) { Text("${format.label}（.${format.extension}）") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { exportFormatChoice = false }) { Text("取消") } },
        )
    }
}

/** Flow 首帧里 recording 被并发删除时不要让 collect 抛异常崩掉页面。 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentCard(
    segment: TranscriptSegment,
    speaker: Speaker?,
    active: Boolean,
    menuOpen: Boolean,
    onMenuChange: (TranscriptSegment?) -> Unit,
    onClick: () -> Unit,
    onEditText: () -> Unit,
    onChangeSpeaker: () -> Unit,
    onEditTimestamps: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { onMenuChange(segment) }),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatClockWithMillis(segment.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorForSpeaker(speaker?.colorIndex),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    speaker?.name ?: "未知",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorForSpeaker(speaker?.colorIndex),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (segment.isEdited) {
                    Text(
                        "已编辑",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Box {
                    IconButton(onClick = { onMenuChange(segment) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "操作", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { onMenuChange(null) },
                    ) {
                        DropdownMenuItem(
                            text = { Text("编辑文本") },
                            onClick = { onMenuChange(null); onEditText() },
                        )
                        DropdownMenuItem(
                            text = { Text("更改说话人") },
                            onClick = { onMenuChange(null); onChangeSpeaker() },
                        )
                        DropdownMenuItem(
                            text = { Text("调整时间戳") },
                            onClick = { onMenuChange(null); onEditTimestamps() },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                segment.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TimestampsDialog(
    segment: TranscriptSegment,
    onDismiss: () -> Unit,
    onConfirm: (Long, Long) -> Unit,
) {
    var startText by remember { mutableStateOf(segment.startMs.toString()) }
    var endText by remember { mutableStateOf(segment.endMs.toString()) }
    val start = startText.toLongOrNull()
    val end = endText.toLongOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整时间戳（毫秒）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("开始 (${formatClockWithMillis(segment.startMs)})") },
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text("结束 (${formatClockWithMillis(segment.endMs)})") },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = start != null && end != null && end > start,
                onClick = { onConfirm(start!!, end!!) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
