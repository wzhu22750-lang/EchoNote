package com.echonote.app.ui.models

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.echonote.app.ai.ModelCatalog
import com.echonote.app.ai.ModelManager
import com.echonote.app.di.AppContainer
import com.echonote.app.ui.common.InfoCard
import com.echonote.app.ui.common.formatBytes
import kotlinx.coroutines.launch
import java.io.File

/**
 * 模型管理页：展示 [ModelCatalog] 中每个模型包的下载 / 删除 / 进度与许可信息。
 * 所有下载都经 [ModelManager]（断点续传 + 字节数 + SHA-256 校验），落在应用私有目录。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val manager = container.modelManager
    val scope = rememberCoroutineScope()

    // 安装状态是文件系统事实，不是响应式流；用 tick 手动触发重算。
    var tick by remember { mutableStateOf(0) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ModelManager.Progress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<ModelCatalog.Bundle?>(null) }

    val free = remember(tick) { container.repository.freeBytes() }
    val installed = remember(tick) { manager.installedBundles() }
    val installedTotal = remember(tick) { manager.installedBytes() }
    val missingDefault = remember(tick) {
        ModelCatalog.defaultInstall.count { !manager.isInstalled(it) }
    }

    fun startDownload(bundle: ModelCatalog.Bundle) {
        if (downloadingId != null) return
        downloadingId = bundle.id
        error = null
        scope.launch {
            try {
                manager.download(bundle) { progress = it }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally {
                downloadingId = null
                progress = null
                tick++
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("AI 模型") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "已安装 ${installed.size}/${ModelCatalog.all.size} 个模型 · 共 ${formatBytes(installedTotal)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "可用空间 ${formatBytes(free)} · 模型保存在应用私有目录，不会上传任何数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            if (missingDefault > 0) {
                item {
                    Button(
                        onClick = {
                            ModelCatalog.defaultInstall
                                .filter { !manager.isInstalled(it) }
                                .forEach { startDownload(it) }
                        },
                        enabled = downloadingId == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("一键安装推荐组合（VAD + 声纹 + SenseVoice，约 ${formatBytes(ModelCatalog.defaultInstallBytes)}）")
                    }
                }
            }

            error?.let { message ->
                item {
                    InfoCard(
                        tone = com.echonote.app.ui.common.CardTone.ERROR,
                        title = "下载失败",
                        body = "$message（已下载的部分会保留，重试将从断点继续）",
                    )
                }
            }

            items(ModelCatalog.all, key = { it.id }) { bundle ->
                BundleCard(
                    bundle = bundle,
                    installed = manager.isInstalled(bundle),
                    installedBytes = remember(tick) { bundleFilesBytes(manager, bundle) },
                    downloading = downloadingId == bundle.id,
                    busy = downloadingId != null,
                    progress = if (downloadingId == bundle.id) progress else null,
                    onDownload = { startDownload(bundle) },
                    onDelete = { confirmDelete = bundle },
                )
            }

            item {
                InfoCard(
                    title = "为什么是这几个模型",
                    body = "VAD 切分语音（Silero，MIT）、声纹区分说话人（CAM++，Apache-2.0）、" +
                        "识别文字（SenseVoice 中文最准，权重许可见设置内说明）。全部在本机运行，" +
                        "录音与逐字稿永远不会上传。",
                )
            }
        }
    }

    confirmDelete?.let { bundle ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除模型") },
            text = { Text("确定删除「${bundle.displayName}」（约 ${bundle.totalMb.toInt()} MB）？删除后转写功能不可用，可随时重新下载。") },
            confirmButton = {
                TextButton(onClick = {
                    manager.delete(bundle)
                    confirmDelete = null
                    tick++
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

private fun bundleFilesBytes(manager: ModelManager, bundle: ModelCatalog.Bundle): Long =
    bundle.files.sumOf { f ->
        val file = File(manager.bundleDir(bundle), f.fileName)
        if (file.isFile) file.length() else 0L
    }

@Composable
private fun BundleCard(
    bundle: ModelCatalog.Bundle,
    installed: Boolean,
    installedBytes: Long,
    downloading: Boolean,
    busy: Boolean,
    progress: ModelManager.Progress?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        bundle.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${bundle.role.displayName} · ${bundle.totalMb.toInt()} MB · 权重许可 ${bundle.weightsLicense}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (installed) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "已安装",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                bundle.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            bundle.files.forEach { file ->
                Text(
                    "· ${file.fileName}（${file.sizeMb.toInt()} MB）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                bundle.attribution,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            when {
                downloading && progress != null -> {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "正在下载 ${progress.fileName}（${progress.completedFiles + 1}/${progress.totalFiles}）",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                downloading -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                installed -> {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "已安装 · ${formatBytes(installedBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                else -> {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onDownload, enabled = !busy) {
                        Text("下载（${bundle.totalMb.toInt()} MB）")
                    }
                }
            }
        }
    }
}
