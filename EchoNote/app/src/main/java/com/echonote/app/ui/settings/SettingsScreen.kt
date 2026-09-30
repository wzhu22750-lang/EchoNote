package com.echonote.app.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echonote.app.ai.ModelCatalog
import com.echonote.app.data.db.CaptureSource
import com.echonote.app.data.settings.AsrModel
import com.echonote.app.data.settings.EchoNoteSettings
import com.echonote.app.data.settings.TranscriptionTrigger
import com.echonote.app.di.AppContainer
import com.echonote.app.ui.common.CardTone
import com.echonote.app.ui.common.InfoCard
import com.echonote.app.ui.common.SectionHeader
import kotlinx.coroutines.launch

/**
 * 设置页。所有偏好经 [com.echonote.app.data.settings.SettingsRepository] 持久化；
 * 云端识别开关默认关闭（数据不出本机是这个应用的底线）。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenFeasibility: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val settings by container.settings.settings
        .collectAsStateWithLifecycle(initialValue = EchoNoteSettings())
    val scope = rememberCoroutineScope()

    var showAsrDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showTriggerDialog by remember { mutableStateOf(false) }
    var showLicenseNote by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { SectionHeader("转写引擎") }

        item {
            ChoiceRow(
                title = "识别模型",
                current = asrLabel(container, settings.asrModel),
                onClick = { showAsrDialog = true },
            )
        }
        item {
            ChoiceRow(
                title = "识别语言",
                current = languageLabel(settings.language),
                onClick = { showLanguageDialog = true },
            )
        }
        item {
            SwitchRow(
                title = "逆文本归一化（ITN）",
                subtitle = "把「一百二十三」转成「123」等数字/日期格式化",
                checked = settings.useInverseTextNormalization,
                onChange = { v -> scope.launch { container.settings.setInverseTextNormalization(v) } },
            )
        }
        item {
            SwitchRow(
                title = "标点符号",
                subtitle = "SenseVoice 自带标点输出；额外的标点模型（294 MB）可在模型页手动导入",
                checked = settings.enablePunctuation,
                onChange = { v -> scope.launch { container.settings.setPunctuation(v) } },
            )
        }

        item { SectionHeader("说话人分离") }

        item {
            SwitchRow(
                title = "启用说话人分离",
                subtitle = "按声纹自动区分不同说话人（CAM++，本机运行）",
                checked = settings.enableDiarization,
                onChange = { v -> scope.launch { container.settings.setDiarization(v) } },
            )
        }
        item {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    "说话人数量：" + if (settings.forcedSpeakerCount <= 0) "自动判断" else "固定 ${settings.forcedSpeakerCount} 人",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 2, 3, 4).forEach { count ->
                        FilterChip(
                            selected = settings.forcedSpeakerCount == count,
                            onClick = { scope.launch { container.settings.setForcedSpeakerCount(count) } },
                            label = { Text(if (count == 0) "自动" else "$count") },
                        )
                    }
                }
            }
        }
        item {
            var localThreshold by remember(settings.clusteringThreshold) {
                mutableFloatStateOf(settings.clusteringThreshold)
            }
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    "聚类相似度阈值：${"%.2f".format(localThreshold)}（越低越容易合并说话人）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = localThreshold,
                    onValueChange = { localThreshold = it },
                    valueRange = 0.30f..0.70f,
                    onValueChangeFinished = {
                        scope.launch { container.settings.setClusteringThreshold(localThreshold) }
                    },
                )
            }
        }

        item { SectionHeader("录音") }

        item {
            ChoiceRow(
                title = "默认采集音源",
                current = settings.preferredCaptureSource.name,
                onClick = { showSourceDialog = true },
            )
        }
        item {
            SwitchRow(
                title = "回声消除（AEC）",
                subtitle = "模式 B（扬声器通话）下建议开启，减轻手机扬声器的回声",
                checked = settings.enableAcousticEchoCanceler,
                onChange = { v -> scope.launch { container.settings.setEchoCanceler(v) } },
            )
        }
        item {
            SwitchRow(
                title = "噪声抑制（NS）",
                checked = settings.enableNoiseSuppressor,
                onChange = { v -> scope.launch { container.settings.setNoiseSuppressor(v) } },
            )
        }
        item {
            SwitchRow(
                title = "自动增益（AGC）",
                subtitle = "放大较轻的声音；对远场录音可能放大噪声",
                checked = settings.enableAutoGainControl,
                onChange = { v -> scope.launch { container.settings.setAutoGainControl(v) } },
            )
        }
        item {
            SwitchRow(
                title = "录音时保持屏幕常亮",
                checked = settings.keepScreenOnWhileRecording,
                onChange = { v -> scope.launch { container.settings.setKeepScreenOn(v) } },
            )
        }
        item { SelfVoiceprintSection(container) }

        item { SectionHeader("转写触发") }

        item {
            ChoiceRow(
                title = "录音结束后",
                current = settings.transcriptionTrigger.label,
                onClick = { showTriggerDialog = true },
            )
        }

        item { SectionHeader("播放与外观") }

        item {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("默认播放速度", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        FilterChip(
                            selected = settings.playbackSpeed == speed,
                            onClick = { scope.launch { container.settings.setPlaybackSpeed(speed) } },
                            label = { Text("${speed}x") },
                        )
                    }
                }
            }
        }
        item {
            SwitchRow(
                title = "动态取色",
                subtitle = "根据系统壁纸生成主题色（Android 12+）",
                checked = settings.dynamicColor,
                onChange = { v -> scope.launch { container.settings.setDynamicColor(v) } },
            )
        }

        item { SectionHeader("数据与隐私") }

        item {
            SwitchRow(
                title = "允许云端语音识别",
                subtitle = "默认关闭。开启后录音可能上传到第三方服务——本应用的所有离线功能不依赖此项",
                checked = settings.cloudAsrConsent,
                onChange = { v -> scope.launch { container.settings.setCloudAsrConsent(v) } },
            )
        }
        item {
            InfoCard(
                tone = CardTone.INFO,
                title = "你的数据只属于你",
                body = "录音、逐字稿与声纹全部保存在应用私有目录；「我」的声纹是生物特征，" +
                    "永远不会出现在任何导出文件中。应用不内嵌任何统计或上传 SDK。",
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                    Text("管理 AI 模型（下载 / 删除）")
                }
                OutlinedButton(onClick = onOpenFeasibility, modifier = Modifier.fillMaxWidth()) {
                    Text("查看可行性报告与本机实测")
                }
                TextButton(onClick = { showLicenseNote = true }) {
                    Text("模型许可说明")
                }
            }
        }
    }

    if (showAsrDialog) {
        RadioDialog(
            title = "识别模型",
            options = AsrModel.entries.filter { it != AsrModel.NONE }.map {
                RadioOption(
                    label = it.displayName,
                    note = when {
                        ModelCatalog.byId(it.id) != null &&
                            container.modelManager.isInstalled(ModelCatalog.byId(it.id)!!) -> "已安装"
                        ModelCatalog.byId(it.id) != null -> "未安装，可在模型页下载"
                        else -> "需手动导入（暂无内置下载）"
                    },
                    selected = settings.asrModel == it,
                )
            },
            onSelect = { index ->
                scope.launch { container.settings.setAsrModel(AsrModel.entries.filter { it != AsrModel.NONE }[index]) }
                showAsrDialog = false
            },
            onDismiss = { showAsrDialog = false },
        )
    }

    if (showLanguageDialog) {
        val languages = listOf("auto", "zh", "en", "ja", "ko", "yue")
        RadioDialog(
            title = "识别语言",
            options = languages.map { RadioOption(languageLabel(it), "", it == settings.language) },
            onSelect = { index ->
                scope.launch { container.settings.setLanguage(languages[index]) }
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false },
        )
    }

    if (showSourceDialog) {
        // 与 MicCaptureEngine.probeSources 保持一致：只列普通 App 真正可用的音源。
        val sources = listOf(
            CaptureSource.VOICE_RECOGNITION,
            CaptureSource.MIC,
            CaptureSource.VOICE_COMMUNICATION,
            CaptureSource.CAMCORDER,
            CaptureSource.DEFAULT,
        )
        RadioDialog(
            title = "默认采集音源",
            options = sources.map {
                RadioOption(it.name, sourceNote(it), settings.preferredCaptureSource == it)
            },
            onSelect = { index ->
                scope.launch { container.settings.setCaptureSource(sources[index]) }
                showSourceDialog = false
            },
            onDismiss = { showSourceDialog = false },
        )
    }

    if (showTriggerDialog) {
        RadioDialog(
            title = "转写触发",
            options = TranscriptionTrigger.entries.map {
                RadioOption(it.label, "", settings.transcriptionTrigger == it)
            },
            onSelect = { index ->
                scope.launch { container.settings.setTranscriptionTrigger(TranscriptionTrigger.entries[index]) }
                showTriggerDialog = false
            },
            onDismiss = { showTriggerDialog = false },
        )
    }

    if (showLicenseNote) {
        AlertDialog(
            onDismissRequest = { showLicenseNote = false },
            title = { Text("模型许可说明") },
            text = {
                Text(
                    "· Silero VAD：MIT\n" +
                        "· CAM++ 声纹（3D-Speaker）：Apache-2.0\n" +
                        "· SenseVoice：代码 MIT；权重为 FunASR MODEL_LICENSE v1.1" +
                        "（自定义许可，要求署名并保留模型名，无禁止商用条款）\n" +
                        "· Whisper（备选）：代码 MIT，权重 Apache-2.0\n\n" +
                        "完整说明见项目 THIRD_PARTY_LICENSES.md。本应用不使用任何 GPL 或禁止商用的模型。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenseNote = false }) { Text("知道了") }
            },
        )
    }
}

private fun asrLabel(container: AppContainer, model: AsrModel): String {
    val installed = ModelCatalog.byId(model.id)
        ?.let { container.modelManager.isInstalled(it) } ?: false
    val suffix = when {
        model == AsrModel.NONE -> ""
        installed -> "（已安装）"
        else -> "（未安装）"
    }
    return model.displayName + suffix
}

private fun languageLabel(code: String) = when (code) {
    "auto" -> "自动检测"
    "zh" -> "中文"
    "en" -> "English"
    "ja" -> "日本語"
    "ko" -> "한국어"
    "yue" -> "粤语"
    else -> code
}

private fun sourceNote(source: CaptureSource): String = when (source) {
    CaptureSource.VOICE_RECOGNITION -> "推荐：针对语音优化，录音场景最稳"
    CaptureSource.MIC -> "原始麦克风，最贴近实际听到的声音"
    CaptureSource.VOICE_COMMUNICATION -> "通话路径处理，部分机型可用"
    CaptureSource.CAMCORDER -> "摄像机指向，适合相机方向的远场声音"
    CaptureSource.DEFAULT -> "系统默认音源"
    else -> ""
}

// ------------------------------------------------------------------ 通用组件

private data class RadioOption(val label: String, val note: String, val selected: Boolean)

@Composable
private fun RadioDialog(
    title: String,
    options: List<RadioOption>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.selected, onClick = { onSelect(index) })
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            if (option.note.isNotBlank()) {
                                Text(
                                    option.note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ChoiceRow(title: String, current: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                current,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
