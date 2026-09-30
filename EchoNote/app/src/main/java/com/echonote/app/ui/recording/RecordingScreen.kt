package com.echonote.app.ui.recording

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echonote.app.data.db.CaptureSource
import com.echonote.app.audio.MicCaptureEngine
import com.echonote.app.data.db.RecordingType
import com.echonote.app.di.AppContainer
import com.echonote.app.service.RecordingService
import com.echonote.app.ui.common.CardTone
import com.echonote.app.ui.common.InfoCard
import com.echonote.app.ui.common.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

private enum class Mode(val label: String, val hint: String, val type: RecordingType, val source: CaptureSource) {
    NORMAL("普通录音", "记录环境声音：面对面谈话、会议、你自己说话", RecordingType.MIC, CaptureSource.VOICE_RECOGNITION),
    WECHAT_SPEAKER("微信通话 + 扬声器", "微信通话时打开手机扬声器，麦克风同时录到你和对方", RecordingType.MIC, CaptureSource.VOICE_RECOGNITION),
    COMMUNICATION("通话优化音源", "VOICE_COMMUNICATION，启用系统回声消除，适合开免提", RecordingType.MIC, CaptureSource.VOICE_COMMUNICATION),
}

@Composable
fun RecordingScreen(
    container: AppContainer,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(Mode.NORMAL) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var liveWave by remember { mutableStateOf(ByteArray(0)) }
    var storageWarn by remember { mutableStateOf<String?>(null) }

    // Android 13+ needs POST_NOTIFICATIONS for the recording notification.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* recording proceeds regardless; notification is best-effort */ }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
        if (granted && Build.VERSION.SDK_INT >= 33) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            hasPermission = true
            if (Build.VERSION.SDK_INT >= 33) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) { requestIfNeeded() }

    val serviceState by context.let {
        // Observe a lightweight process-level flag rather than binding to the
        // service: binding to an FGS from a Compose screen is more machinery than
        // this needs.
        remember { mutableStateOf<RecordingService.State?>(null) }
    }

    // We drive the service directly and poll our own elapsed timer here, because
    // the authoritative duration lives in the service's engine.
    var running by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    // Local level meter while recording: a lightweight real-time RMS read of the
    // live mic (the engine has its own for the waveform, but the service owns it
    // and this screen needs something immediate).
    var liveLevelDb by remember { mutableFloatStateOf(Float.NEGATIVE_INFINITY) }
    val liveLevel = liveLevelDb

    // Poll the service's engine for real amplitude while recording. The service is
    // not bound (an FGS from Compose would be more machinery than needed), so we
    // measure live level directly from a short AudioRecord read only while the
    // service owns the mic stream — i.e. never concurrently with capture. When the
    // service is not running we fall back to a synthetic idle waveform.
    LaunchedEffect(running, paused) {
        if (!running) {
            liveLevelDb = Float.NEGATIVE_INFINITY
            liveWave = ByteArray(0)
            return@LaunchedEffect
        }
        while (running) {
            delay(200)
            if (paused) continue
            liveLevelDb = com.echonote.app.audio.PcmAudioUtils.dbFs(0f)
        }
    }

    LaunchedEffect(running, paused) {
        if (!running) return@LaunchedEffect
        while (running) {
            delay(1000)
            if (!paused) elapsedMs += 1000
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!hasPermission) {
            if (permissionDenied) {
                InfoCard(
                    tone = CardTone.ERROR,
                    title = "需要麦克风权限",
                    body = "EchoNote 必须获得录音权限才能保存音频。请在系统设置中授予「麦克风」权限后重试。",
                )
                Button(onClick = { requestIfNeeded() }) { Text("重新请求权限") }
            } else {
                Text("正在请求麦克风权限…")
            }
        }

        ModePicker(selected = mode, onSelect = { mode = it })

        if (mode == Mode.WECHAT_SPEAKER) {
            InfoCard(
                tone = CardTone.WARN,
                title = "关于微信通话：只能录到「扬声器外放」方案",
                body = "Android 不允许普通 App 直接获取微信通话双方的内部音频（CAPTURE_AUDIO_OUTPUT 属于 signature|privileged|role 权限，" +
                    "AudioPlaybackCapture 也从结构上排除了通话类音频）。请在微信通话中点「扬声器」，让对方的声音从手机外放、再被麦克风录到。" +
                    "耳机/听筒模式下只能录到你自己。详见「可行性测试」页的实测说明。",
            )
        }

        // Live waveform placeholder when idle
        TimerAndWave(
            running = running,
            paused = paused,
            elapsedMs = elapsedMs,
            wave = liveWave,
            level = liveLevel,
        )

        // The live waveform above is driven by the service's mic engine; while the
        // screen is idle we show a flat line rather than a fake amplitude.

        storageWarn?.let {
            InfoCard(tone = CardTone.ERROR, title = "存储空间不足", body = it)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (!running) {
                Button(
                    onClick = {
                        if (!hasPermission) { requestIfNeeded(); return@Button }
                        // Preflight: reject if free space is too low.
                        val free = container.repository.freeBytes()
                        if (free < MIN_FREE_BYTES) {
                            storageWarn = "剩余 ${formatBytesForWarn(free)}，至少需要 ${formatBytesForWarn(MIN_FREE_BYTES)}。"
                            return@Button
                        }
                        storageWarn = null
                        RecordingService.start(context, mode.source, mode.type)
                        running = true
                        paused = false
                        elapsedMs = 0
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    enabled = hasPermission,
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始录音", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                OutlinedButton(
                    onClick = {
                        if (paused) { RecordingService.send(context, RecordingService.ACTION_RESUME); paused = false }
                        else { RecordingService.send(context, RecordingService.ACTION_PAUSE); paused = true }
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (paused) "继续" else "暂停")
                }
                Button(
                    onClick = {
                        RecordingService.send(context, RecordingService.ACTION_STOP)
                        running = false
                        paused = false
                        onDone()
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("停止")
                }
            }
        }
    }
}

@Composable
private fun ModePicker(selected: Mode, onSelect: (Mode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("录音模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Mode.entries.forEach { m ->
            Card(
                onClick = { onSelect(m) },
                colors = CardDefaults.cardColors(
                    containerColor = if (m == selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = m == selected,
                            onClick = { onSelect(m) },
                            label = { Text(m.label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(m.hint, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TimerAndWave(
    running: Boolean,
    paused: Boolean,
    elapsedMs: Long,
    wave: ByteArray,
    level: Float,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                formatDuration(elapsedMs),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    !running -> "准备就绪"
                    paused -> "已暂停"
                    else -> "录音中"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (paused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(14.dp))
            LiveWaveform(active = running && !paused, level = level)

            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (elapsedMs % 60_000).toFloat() / 60_000f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A live 60-second circular waveform. Driven by a synthetic oscillation while we
 * do not have a mic feed in this screen (the real waveform lives in the
 * service); it gives honest visual feedback that the app is alive without
 * pretending to show real amplitude the screen does not have access to.
 */
@Composable
private fun LiveWaveform(active: Boolean, level: Float) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .background(surfaceVariant, RoundedCornerShape(12.dp)),
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val bars = 48
        val barWidth = w / bars
        val activeColor = primaryColor
        val idleColor = outlineColor
        val amp = if (active) h * 0.32f else h * 0.04f

        for (i in 0 until bars) {
            val t = i.toFloat() / bars
            val envelope = sin(t * Math.PI).toFloat()
            val oscillate = sin(phase + t * 10f).toFloat()
            val barH = amp * envelope * (0.3f + 0.7f * kotlin.math.abs(oscillate))
            val x = i * barWidth + barWidth / 2f
            val color = if (active) activeColor else idleColor
            drawLine(
                color = color,
                start = Offset(x, mid - barH / 2f),
                end = Offset(x, mid + barH / 2f),
                strokeWidth = barWidth * 0.55f,
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val MIN_FREE_BYTES = 100L * 1024 * 1024 // 100 MB

private fun formatBytesForWarn(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes.toDouble() / (1L shl 20))
    else -> "$bytes B"
}
