package com.echonote.app.ui.feasibility

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.echonote.app.audio.CaptureProbe
import com.echonote.app.audio.MicCaptureEngine
import com.echonote.app.data.db.CaptureSource
import com.echonote.app.di.AppContainer
import com.echonote.app.ui.common.CardTone
import com.echonote.app.ui.common.InfoCard
import com.echonote.app.ui.common.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 可行性实验室：如实报告这台设备上每条音频采集路径的真实行为。
 *
 * 结论基于 AOSP 源码与 16 个开源项目的尽调（见 TECHNICAL_FEASIBILITY.md），下面的
 * [MicCaptureEngine.probe] 实测则给出本机证据。普通第三方 App 无法直接获取微信通话
 * 双方的内部音频；唯一可靠的非 Root 方案是「微信开扬声器 + 麦克风采集」。
 */
@Composable
fun FeasibilityScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var probing by remember { mutableStateOf(false) }
    var probeIndex by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<List<CaptureProbe>>(emptyList()) }
    var runtimeInfo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runtimeInfo = try {
            container.nativeRuntimeInfo()
        } catch (t: Throwable) {
            "本地推理库加载失败：${t.message}"
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "可行性报告",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        item {
            InfoCard(
                tone = CardTone.WARN,
                title = "核心结论（基于 AOSP 源码与开源项目尽调）",
                body = "普通第三方 App 无法直接获取微信通话双方的内部音频。" +
                    "唯一可靠的非 Root 方案是「微信开扬声器 + 麦克风采集」。\n" +
                    "· CAPTURE_AUDIO_OUTPUT 权限级别为 signature|privileged|role，普通 App 拿不到；\n" +
                    "· VOICE_CALL / VOICE_UPLINK / VOICE_DOWNLINK 音源被 AOSP 保留给系统组件；\n" +
                    "· AudioPlaybackCapture 结构上排除通话音频（USAGE_VOICE_COMMUNICATION 不可捕获）；\n" +
                    "· 调研的 16 个开源项目无一验证过微信双方录音。",
            )
        }

        item { SectionHeader("三种使用模式") }

        item {
            ModeCard(
                title = "模式 A：普通录音",
                body = "直接用麦克风录环境声。立即可用，适合面对面谈话、会议、课堂。",
                available = true,
            )
        }
        item {
            ModeCard(
                title = "模式 B：微信通话 + 扬声器",
                body = "微信通话时打开扬声器，麦克风同时录到双方语音。这是唯一可靠的非 Root 方案；" +
                    "回声抑制与音量差异是主要质量风险，转写后可用人工编辑修正。",
                available = true,
            )
        }
        item {
            ModeCard(
                title = "模式 C：导入已有音频",
                body = "把 WAV / M4A / MP3 等录音文件导入本机转写。AI 管线始终完整可用，是兜底方案。",
                available = true,
            )
        }

        item { SectionHeader("本机实测（AudioRecord 音源探测）") }

        item {
            Text(
                "下面逐个启动普通 App 被允许使用的音源，各采集约 1 秒，报告真实行为：" +
                    "产出可用音频、只有数字静音（被本机封禁）还是根本无法启动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            if (probing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在探测 ${probeIndex + 1}/${MicCaptureEngine.probeSources.size}…（请保持环境有声音）")
                }
            } else {
                Button(
                    onClick = {
                        probing = true
                        results = emptyList()
                        scope.launch(Dispatchers.IO) {
                            // blockingAec() 内部 runBlocking，只能在非主线程调用。
                            val aec = container.settings.blockingAec()
                            val ns = container.settings.blockingNs()
                            val engine = MicCaptureEngine(container.storage, aec, ns)
                            val out = mutableListOf<CaptureProbe>()
                            MicCaptureEngine.probeSources.forEachIndexed { index, source ->
                                probeIndex = index
                                out += engine.probe(source, durationMs = 900)
                                results = out.toList()
                            }
                            engine.close()
                            probing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Science, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (results.isEmpty()) "开始实测本机音源" else "重新实测")
                }
            }
        }

        items(results, key = { it.requested.name }) { probe ->
            ProbeCard(probe)
        }

        item {
            InfoCard(
                title = "为什么清单里没有 VOICE_CALL 等通话音源",
                body = "VOICE_CALL、VOICE_UPLINK、VOICE_DOWNLINK、REMOTE_SUBMIX 都要求 " +
                    "CAPTURE_AUDIO_OUTPUT（signature|privileged|role）。把它们列成可选项等于撒谎；" +
                    "这里只测普通 App 真正可以请求的音源，并如实报告结果。",
            )
        }

        item { SectionHeader("运行环境") }

        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EnvRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                    EnvRow("系统", "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
                    EnvRow("ABI", container.supportedAbis().joinToString(", "))
                    EnvRow("本地推理引擎", runtimeInfo ?: "加载中…")
                }
            }
        }

        item { SectionHeader("合规承诺") }

        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ComplianceLine("不要求 Root / ADB / Shizuku，不 Hook 或注入微信进程")
                    ComplianceLine("录音期间系统通知始终可见，绝不在用户不知情时录音")
                    ComplianceLine("录音、逐字稿、声纹全部留在本机，默认不上传任何数据")
                    ComplianceLine("导出内容绝不包含声纹（生物特征）数据")
                    ComplianceLine("不使用 GPL-3.0 或禁止商用的模型许可")
                }
            }
        }

        item {
            Text(
                "注：以上结论的完整证据链见 TECHNICAL_FEASIBILITY.md。" +
                    "微信双方音频采集尚未经过真实设备验证——本页的实测功能就是为真机验证准备的。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ModeCard(title: String, body: String, available: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (available) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProbeCard(probe: CaptureProbe) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                sourceLabel(probe.requested),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(probe.summary(), style = MaterialTheme.typography.bodySmall)
            Text(
                "实际音源 id=${probe.actualSource} · ${probe.sampleRate} Hz · 单声道 · " +
                    "AEC=${probe.echoCancelerApplied} NS=${probe.noiseSuppressorApplied}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun sourceLabel(source: CaptureSource): String = when (source) {
    CaptureSource.MIC -> "麦克风 (MIC)"
    CaptureSource.VOICE_RECOGNITION -> "语音识别 (VOICE_RECOGNITION)"
    CaptureSource.VOICE_COMMUNICATION -> "通话下行处理 (VOICE_COMMUNICATION)"
    CaptureSource.CAMCORDER -> "摄像机指向 (CAMCORDER)"
    CaptureSource.DEFAULT -> "默认音源 (DEFAULT)"
    else -> source.name
}

@Composable
private fun EnvRow(label: String, value: String) {
    Row {
        Text(
            "$label：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ComplianceLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
