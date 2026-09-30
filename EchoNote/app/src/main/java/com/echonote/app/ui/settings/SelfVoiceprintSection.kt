package com.echonote.app.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import com.echonote.app.ai.ModelResolver
import com.echonote.app.ai.SpeakerEmbeddingEngine
import com.echonote.app.audio.AudioSpec
import com.echonote.app.audio.PcmAudioUtils
import com.echonote.app.di.AppContainer
import com.echonote.app.ui.common.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 「我」的声纹录入。录入后说话人分离会用真实证据（而不是猜测）判断哪一段是机主，
 * 转写时第一簇会直接命名为「我」。
 *
 * 声纹是生物特征：只存应用私有 DataStore，只交给本机声纹模型，绝不导出、绝不上传。
 */
@Composable
fun SelfVoiceprintSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enrolled by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var remainingSec by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { enrolled = container.selfVoiceprint.hasVoiceprint() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) phase = Phase.RECORDING_PROMPT else message = "需要麦克风权限才能录入声纹"
    }

    Column(Modifier.fillMaxWidth()) {
        SectionHeader("我的声纹")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Fingerprint,
                contentDescription = null,
                tint = if (enrolled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (enrolled) "已录入 — 转写时会自动识别「我」" else "未录入 — 说话人分离只能靠聚类猜测",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) phase = Phase.RECORDING_PROMPT else {
                        message = null
                        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = phase == Phase.IDLE || phase == Phase.DONE,
                modifier = Modifier.weight(1f),
            ) { Text(if (enrolled) "重新录入（约 5 秒）" else "录入声纹（约 5 秒）") }

            if (enrolled) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            container.selfVoiceprint.clear()
                            enrolled = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("删除声纹") }
            }
        }
        if (phase == Phase.RECORDING || phase == Phase.PROCESSING) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (phase == Phase.RECORDING) "请自然说话…还剩 ${remainingSec}s"
                    else "正在计算声纹…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        TextButton(onClick = { showInfo = true }) { Text("这份数据保存在哪里？") }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("声纹数据的去向") },
            text = {
                Text(
                    "声纹是一串 192 维数字（数学特征，不是可回放的录音），保存在本机应用的私有存储中。\n\n" +
                        "· 不会导出到任何文件（导出器有测试断言保证）；\n" +
                        "· 不会上传到任何服务器；\n" +
                        "· 只在本机交给声纹模型用于区分说话人。\n\n" +
                        "删除声纹会立即从本机移除这份数据。",
                )
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("知道了") } },
        )
    }

    if (phase == Phase.RECORDING_PROMPT) {
        AlertDialog(
            onDismissRequest = { phase = Phase.IDLE },
            title = { Text("准备录入") },
            text = { Text("接下来请用正常音量连续说 5 秒左右的话（随便说什么：念一段新闻、自我介绍都行）。环境越安静效果越好。") },
            confirmButton = {
                TextButton(onClick = {
                    phase = Phase.RECORDING
                    startEnrollment(container, context,
                        onRemaining = { remainingSec = it },
                        onCompute = { phase = Phase.PROCESSING },
                        onDone = {
                            enrolled = true
                            phase = Phase.DONE
                            message = null
                        },
                        onError = { msg ->
                            message = msg
                            phase = Phase.IDLE
                        },
                    ) { scope.launch { it() } }
                }) { Text("开始") }
            },
            dismissButton = { TextButton(onClick = { phase = Phase.IDLE }) { Text("取消") } },
        )
    }
}

private enum class Phase { IDLE, RECORDING_PROMPT, RECORDING, PROCESSING, DONE }

/**
 * 采集 5 秒（5 段 × 1 秒）→ 每段提声纹 → 取平均 → 存入 [SelfVoiceprintStore]。
 * 全部在工作线程执行；开始时把流程体交给调用方提供的协程启动器。
 */
@SuppressLint("MissingPermission")
private fun startEnrollment(
    container: AppContainer,
    context: Context,
    onRemaining: (Int) -> Unit,
    onCompute: () -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
    launch: (suspend () -> Unit) -> Unit,
) {
    val modelPath = ModelResolver.speakerPath(container.modelManager)
    if (modelPath == null) {
        onError("请先在「管理 AI 模型」里下载声纹模型（CAM++）")
        return
    }
    launch {
        try {
            onRemaining(ENROLL_SECONDS)
            val sampleRate = AudioSpec.SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sampleRate * 2),
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                onError("无法打开麦克风（AudioRecord 未初始化）")
                return@launch
            }
            ar.startRecording()
            val chunks = mutableListOf<FloatArray>()
            for (sec in 1..ENROLL_SECONDS) {
                val shorts = ShortArray(sampleRate) // 每段 1 秒
                var read = 0
                while (read < sampleRate) {
                    val r = ar.read(shorts, read, sampleRate - read)
                    if (r <= 0) break
                    read += r
                }
                val floats = FloatArray(read) { shorts[it] / 32768f }
                if (PcmAudioUtils.isSilent(floats, floats.size, floor = 1e-5f)) {
                    ar.stop(); ar.release()
                    onError("录到的全是静音，请检查麦克风或离手机更近一些")
                    return@launch
                }
                chunks += floats
                onRemaining(ENROLL_SECONDS - sec)
            }
            ar.stop()
            ar.release()
            onCompute()

            SpeakerEmbeddingEngine(modelPath).use { engine ->
                val embedding = engine.embedAndAverage(chunks, sampleRate)
                if (embedding == null) {
                    onError("语音太短或不清晰，没有提取到稳定声纹，请重试")
                } else {
                    container.selfVoiceprint.set(embedding)
                    onDone()
                }
            }
        } catch (t: Throwable) {
            onError("录入失败：${t.message ?: t.javaClass.simpleName}")
        }
    }
}

private const val ENROLL_SECONDS = 5
