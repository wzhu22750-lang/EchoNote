# EchoNote 项目 Handoff / 交接文档

> 交接时间：2026-09-30
> 交接原因：上一轮 agent 因 API 配额（insufficient_balance）中断，两个负责写 UI 的 subagent 失败。
> 本文档目标：让下一个 agent **不需要重新调研、不需要重新下载模型、不踩已知的坑**，直接接着干。

---

## 0. 一句话状态

**核心引擎（音频、AI、数据库、导出）已完成且经过真实数据验证；只缺 5 个 Compose 界面文件，导致 `:app:compileDebugKotlin` 失败。**

已实测确认：当前**全部 12 个编译错误都来自 `navigation/EchoNoteNavigation.kt`，且 100% 是因为那 5 个界面文件不存在**。
`RecordingScreen.kt` / `CommonUi.kt` / `HomeScreen.kt` / `HistoryScreen.kt` **均已编译通过，无需修改**（上一轮已修好）。
**补完这 5 个文件即可构建 APK。**

当前 `app/build/outputs/apk/debug/app-debug.apk`（70 MB，10:11 生成）是**早期骨架版**，早于引擎代码，**不是**最终产物，不要拿它交付。

---

## 1. 立刻可用的命令（复制即可跑）

```bash
cd "/Users/kuangqie/Documents/VibeCoding/溜溜打电话转写工作/EchoNote"
export JAVA_HOME=/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
GR=~/.gradle/wrapper/dists/gradle-8.14.3-all/10utluxaxniiv4wxiphsi49nj/gradle-8.14.3/bin/gradle
```

| 目的 | 命令 |
|---|---|
| 编译主代码 | `$GR :app:compileDebugKotlin --no-configuration-cache` |
| 跑全部单元测试 | `$GR :app:testDebugUnitTest --no-configuration-cache` |
| 只跑聚类测试 | `$GR :app:testDebugUnitTest --tests 'com.echonote.app.ai.*' --no-configuration-cache` |
| 只跑导出测试 | `$GR :app:testDebugUnitTest --tests 'com.echonote.app.export.*' --no-configuration-cache` |
| 构建 debug APK | `$GR :app:assembleDebug --no-configuration-cache` |
| 构建 release APK | `$GR :app:assembleRelease --no-configuration-cache` |

**重要：项目里没有 `gradlew`，也没有 `gradle` CLI。必须用上面那个绝对路径调用缓存里的 Gradle 8.14.3 发行版。**
`EchoNote/gradle/wrapper/` 是空目录。如果想让项目自包含，可以跑 `$GR wrapper --gradle-version 8.14.3` 生成 wrapper。

测试报告位置：`app/build/test-results/testDebugUnitTest/*.xml`（用 grep 抓 `tests=` 属性即可得到通过数）。

---

## 2. 环境与工具链（已实测确认）

| 项 | 值 |
|---|---|
| OS | macOS 26.6.2 (arm64, Apple Silicon) |
| 机器 | 8 核 / **8 GB RAM** |
| JDK | `/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home`（OpenJDK 21.0.5） |
| Android SDK | `/Users/kuangqie/Library/Android/sdk`（写在 `local.properties`） |
| platforms | android-34 / 35 / 36 / 37.0 |
| build-tools | 34.0.0 / 35.0.0 / 36.0.0 |
| **NDK** | **未安装** |
| **cmake** | **未安装** |
| **模拟器 / system-images** | **无** |
| **连接的真机** | **无**（`adb devices` 为空） |
| Gradle | 8.14.3（缓存于 `~/.gradle/wrapper/dists/gradle-8.14.3-all/10utluxaxniiv4wxiphsi49nj/`） |
| 磁盘 | 约 27 GB 可用 |

### 版本矩阵（全部已在本地 Gradle 缓存中，**不要随意升级**）
```
AGP                8.10.1
Kotlin             2.0.21
Compose Compiler   2.0.21   (org.jetbrains.kotlin.plugin.compose)
KSP                2.0.21-1.0.28
Compose BOM        2024.12.01  → ui 1.7.6 / material3 1.3.1 / foundation 1.7.6
Room               2.6.1
compileSdk 36 / minSdk 26 / targetSdk 35
abiFilters         arm64-v8a, armeabi-v7a   （排除 x86/x86_64 以省 ~100MB）
```
`gradle.properties` 已针对 8 GB 内存调优：`-Xmx3072m` + `kotlin.compiler.execution.strategy=in-process`（避免再起一个 2 GB 的 Kotlin daemon）。**不要调大**，否则会 OOM。

### 无 NDK 的关键推论
**绝对不要尝试从源码编译 sherpa-onnx / whisper.cpp。** 本项目用官方预编译 AAR：
`EchoNote/app/libs/sherpa-onnx-1.13.8.aar`（50,129,134 bytes，含 arm64-v8a / armeabi-v7a / x86 / x86_64 全套 `.so`）。
依赖声明方式：`implementation(files("libs/sherpa-onnx-1.13.8.aar"))`。
构建日志里会出现 `Unable to strip ... libsherpa-onnx-*.so`，**这是正常的**（没 NDK 就没有 strip 工具），不影响运行。

---

## 3. 项目结构

```
/Users/kuangqie/Documents/VibeCoding/溜溜打电话转写工作/
├── HANDOFF.md                                  ← 本文档
├── android-callrec-oss-due-diligence.md        ← 尽调报告①（16 个项目，微信录音结论）
├── android-offline-asr-due-diligence.md        ← 尽调报告②（ASR 选型，含 RTF 实测）
├── ANDROID_OFFLINE_DIARIZATION_DUE_DILIGENCE.md← 尽调报告③（说话人分离 + 许可证雷区）
└── EchoNote/                                    ← Android 工程
    ├── settings.gradle.kts / build.gradle.kts / gradle.properties / local.properties
    └── app/
        ├── build.gradle.kts
        ├── proguard-rules.pro
        ├── libs/sherpa-onnx-1.13.8.aar
        └── src/
            ├── main/
            │   ├── AndroidManifest.xml
            │   ├── java/com/echonote/app/
            │   │   ├── EchoNoteApplication.kt      ← Application + 手动 DI（AppContainer）
            │   │   ├── MainActivity.kt             ← enableEdgeToEdge + Theme + EchoNoteApp()
            │   │   ├── di/AppContainer.kt          ← ★ 服务定位器，所有依赖入口
            │   │   ├── ai/                         ← ★ AI 层（已完成）
            │   │   │   ├── ModelCatalog.kt         ← 模型清单（URL + 字节数 + SHA256 + 许可证）
            │   │   │   ├── ModelManager.kt         ← 下载/断点续传/校验/SAF 导入
            │   │   │   ├── VadSegmenter.kt         ← Silero VAD 封装（★含关键 bug 修复说明）
            │   │   │   ├── AsrEngine.kt            ← SenseVoice / Whisper 封装
            │   │   │   ├── SpeakerEmbeddingEngine.kt ← 声纹提取 + 标点（PunctuationEngine）
            │   │   │   ├── SpeakerClusterer.kt     ← ★ 自研球形 k-means++ 聚类（已用真实数据校准）
            │   │   │   ├── EmbeddingUtils.kt       ← 余弦相似度 / 序列化
            │   │   │   └── TranscriptionManager.kt ← 转写编排 + 导入音频 + enqueue()
            │   │   ├── audio/                      ← ★ 音频层（已完成）
            │   │   │   ├── PcmAudioUtils.kt        ← RMS/峰值/削波/降混/PCM16 互转
            │   │   │   ├── Resampler.kt            ← 窗函数 sinc 重采样（抗混叠）+ AudioSpec
            │   │   │   ├── StreamingResampler.kt   ← 流式重采样（长文件不爆内存）
            │   │   │   ├── WavWriter.kt            ← 流式 WAV 写 + WavRepair（崩溃恢复）
            │   │   │   ├── WavFileReader.kt        ← 手写 RIFF 解析（8/16/24/32bit + float + EXTENSIBLE）
            │   │   │   ├── AudioFileDecoder.kt     ← 统一解码入口（WAV 手写 / 其他走 MediaCodec）
            │   │   │   └── MicCaptureEngine.kt     ← AudioRecord 采集 + probe() 音源实测
            │   │   ├── data/
            │   │   │   ├── db/{Entities,Daos,EchoNoteDatabase}.kt  ← Room（★schema 已定稿）
            │   │   │   ├── settings/SettingsRepository.kt          ← DataStore 偏好
            │   │   │   ├── settings/SelfVoiceprintStore.kt         ← 「我」的声纹（生物特征，绝不导出）
            │   │   │   └── storage/RecordingStorage.kt             ← App 私有存储
            │   │   ├── domain/Models.kt            ← 领域模型 + Entity→Domain 映射
            │   │   ├── export/TranscriptExporter.kt ← ★ TXT/MD/JSON/SRT/CSV 导出（30 个测试通过）
            │   │   ├── repository/RecordingRepository.kt ← ★ 数据访问 + 搜索 + 删除
            │   │   ├── transcription/
            │   │   │   ├── TranscriptAssembler.kt  ← ASR×说话人对齐/合并/切分（纯逻辑）
            │   │   │   └── TranscriptionPipeline.kt← ★ 全流程编排（流式，内存有界）
            │   │   ├── service/
            │   │   │   ├── RecordingService.kt     ← ★ 前台服务（已完成，含通知/暂停/恢复）
            │   │   │   ├── TranscriptionService.kt ← ⚠️ 占位空壳（未实现）
            │   │   │   └── RecordingActionReceiver.kt ← ⚠️ 占位空壳（实际未使用，通知直接用 getService）
            │   │   ├── capture/MediaProjectionPermissionActivity.kt ← ⚠️ 占位空壳（未实现）
            │   │   ├── navigation/EchoNoteNavigation.kt ← ★ 引用 5 个缺失界面，当前编译失败点
            │   │   └── ui/
            │   │       ├── theme/Theme.kt          ← M3 主题 + SpeakerPalette
            │   │       ├── common/CommonUi.kt      ← ★ 共享组件与格式化函数（务必复用）
            │   │       ├── home/HomeScreen.kt      ← 已完成
            │   │       ├── recording/RecordingScreen.kt ← ⚠️ 已完成但**有编译错误未修完**
            │   │       ├── history/HistoryScreen.kt← 已完成
            │   │       ├── search/SearchScreen.kt       ← ❌ 缺失
            │   │       ├── settings/SettingsScreen.kt   ← ❌ 缺失
            │   │       ├── detail/DetailScreen.kt       ← ❌ 缺失
            │   │       ├── models/ModelsScreen.kt       ← ❌ 缺失
            │   │       └── feasibility/FeasibilityScreen.kt ← ❌ 缺失
            │   └── res/  (strings/colors/themes/themes-night/ic_launcher/data_extraction_rules/file_paths)
            └── test/
                ├── java/com/echonote/app/ai/SpeakerClustererReferenceTest.kt  ← 19 测试
                ├── java/com/echonote/app/export/TranscriptExporterTest.kt     ← 30 测试
                └── resources/reference/*.emb + *.segments.tsv  ← ★ 真实声纹测试夹具
```

---

## 4. 当前构建失败的精确状态

`:app:compileDebugKotlin` → **FAILED，12 个错误，全部集中在 `EchoNoteNavigation.kt`**，原因是 5 个界面文件不存在：

```
e: navigation/EchoNoteNavigation.kt:27:28 Unresolved reference 'detail'
e: navigation/EchoNoteNavigation.kt:28:28 Unresolved reference 'feasibility'
e: navigation/EchoNoteNavigation.kt:31:28 Unresolved reference 'models'
e: navigation/EchoNoteNavigation.kt:33:28 Unresolved reference 'search'
e: navigation/EchoNoteNavigation.kt:34:28 Unresolved reference 'settings'
e: navigation/EchoNoteNavigation.kt:112:17 Unresolved reference 'SearchScreen'
e: navigation/EchoNoteNavigation.kt:114:38 Cannot infer type for this parameter
e: navigation/EchoNoteNavigation.kt:114:42 Cannot infer type for this parameter
e: navigation/EchoNoteNavigation.kt:118:17 Unresolved reference 'SettingsScreen'
e: navigation/EchoNoteNavigation.kt:140:17 Unresolved reference 'DetailScreen'
e: navigation/EchoNoteNavigation.kt:147:17 Unresolved reference 'FeasibilityScreen'
e: navigation/EchoNoteNavigation.kt:150:17 Unresolved reference 'ModelsScreen'
```

### 错误归因（已实测确认）
```
总错误数 12，按文件统计：
  12  navigation/EchoNoteNavigation.kt
   0  其它所有文件
```
即：`RecordingScreen` / `CommonUi` / `HomeScreen` / `HistoryScreen` / 全部 AI 与音频代码**都已经是干净的**。
之所以在 `EchoNoteNavigation.kt` 里报「Unresolved reference 'detail'/'search'/'settings'…」，
是因为该文件顶部的 `import com.echonote.app.ui.search.SearchScreen` 等 5 个 import 指向不存在的包。
**只要你创建了下面 5 个文件（包名正确），这 12 个错误会同时全部消失。**

### 必须创建的 5 个文件与**精确签名**（导航已按此调用，签名不可改）

| 文件 | 包 | 函数签名 |
|---|---|---|
| `ui/search/SearchScreen.kt` | `com.echonote.app.ui.search` | `fun SearchScreen(container: AppContainer, onOpenDetail: (Long, Long) -> Unit)` — 参数是 `(recordingId, startMs)` |
| `ui/settings/SettingsScreen.kt` | `com.echonote.app.ui.settings` | `fun SettingsScreen(container: AppContainer, onOpenFeasibility: () -> Unit, onOpenModels: () -> Unit)` |
| `ui/detail/DetailScreen.kt` | `com.echonote.app.ui.detail` | `fun DetailScreen(container: AppContainer, recordingId: Long, onBack: () -> Unit)` |
| `ui/models/ModelsScreen.kt` | `com.echonote.app.ui.models` | `fun ModelsScreen(container: AppContainer, onBack: () -> Unit)` |
| `ui/feasibility/FeasibilityScreen.kt` | `com.echonote.app.ui.feasibility` | `fun FeasibilityScreen(container: AppContainer, onBack: () -> Unit)` |

全部 `@Composable`。

**注意**：`RecordingScreen.kt` **当前编译是干净的，不要去改它**（除非你想把 §11-P1-7 的真实波形接上）。
下面这些是上一轮**已经踩过并已修复**的坑，写新界面时可能再遇到，照此处理即可：

- `CaptureSource` 在 `com.echonote.app.data.db.CaptureSource`（**不是** `audio` 包）。`audio` 包只有 `MicCaptureEngine.probeSources`。
- Compose 的 `Canvas { }` 里不能直接读 `MaterialTheme.colorScheme.*`，必须提前 `val primaryColor = MaterialTheme.colorScheme.primary` 再传入，否则报
  `@Composable invocations can only happen from the context of a @Composable function`。
- 图标：`Icons.Filled.Warning` 存在；`Icons.AutoMirrored.Filled.Warning` **不存在**。`Icons.AutoMirrored.Filled.List` 存在。
- `IntArray` 没有 `groupingBy`，需要先 `.toList()`。`Collection.max()` 在 Kotlin 2.0 已弃用，用 `maxOrNull() ?: 0`。

---

## 5. ★ 必须遵守的 API 契约（先读这些再写代码）

### `AppContainer`（`di/AppContainer.kt`）— 唯一依赖入口
```kotlin
container.context            // Context
container.database           // EchoNoteDatabase（Room）
container.settings           // SettingsRepository
container.storage            // RecordingStorage
container.selfVoiceprint     // SelfVoiceprintStore（「我」的声纹）
container.modelManager       // ModelManager
container.transcription      // TranscriptionManager
container.repository         // RecordingRepository
container.decoder()          // AudioFileDecoder
container.nativeRuntimeInfo()// String，读取真实 sherpa-onnx/onnxruntime 版本（也会验证 .so 加载）
container.supportedAbis()    // List<String>
```

### `RecordingRepository`（`repository/RecordingRepository.kt`）
```kotlin
observeAll(): Flow<List<Recording>>
observeRecent(limit: Int): Flow<List<Recording>>
observeById(id: Long): Flow<Recording?>
suspend getById(id: Long): Recording?
observeFiltered(query, fromMs=-1, toMs=-1, typeFilter="", limit=500): Flow<List<Recording>>
observeTodayStats(): Flow<Pair<Int, Long>>          // (条数, 时长ms)
observeStorageStats(): Flow<StorageStats>
onDiskBytes(): Long ; freeBytes(): Long
suspend pendingTranscriptions(): List<Recording>
observeDetail(id: Long): Flow<TranscriptDetail>     // recording + speakers + segments
suspend rename(id, title) / updateNotes(id, notes)
suspend renameSpeaker(speakerId, name)
suspend markSpeakerAsMe(speakerId, recordingId)     // 自动清除同录音其它「我」
suspend clearSelfMark(recordingId)
suspend editSegmentText(segmentId, text)
suspend editSegmentSpeaker(segmentId, speakerId: Long?)
suspend editSegmentTimestamps(segmentId, startMs, endMs)
suspend mergeSpeakers(sourceId, targetId)           // 合并说话人（事务）
suspend delete(id): Long                            // 返回释放的字节数
suspend deleteAll()
suspend retagStatus(id, status)
fun search(textQuery="", titleQuery="", speakerQuery="", speakerId=-1, fromMs=-1, toMs=-1, limit=200): Flow<List<SearchHit>>
suspend speakersOf(recordingId): List<Speaker>
suspend segmentsOf(recordingId): List<TranscriptSegment>
```

### `TranscriptionManager`（`ai/TranscriptionManager.kt`）
```kotlin
val progress: StateFlow<Map<Long, TranscriptionProgress>>   // recordingId → 进度
val runningIds: StateFlow<Set<Long>>
suspend transcribe(recordingId: Long): Result<Unit>          // 阻塞直到完成
fun enqueue(recordingId: Long)                               // fire-and-forget
suspend importAudio(uri: Uri, title: String? = null): Long    // SAF 导入 → 新 recording id
```
`TranscriptionProgress(recordingId, stage, fraction: Float?, detail)`；
`stage ∈ {DECODING, VAD, ASR, EMBEDDING, CLUSTERING, SAVING, DONE, FAILED}`。

### `ModelManager`（`ai/ModelManager.kt`）
```kotlin
val root: File
fun bundleDir(bundle) / fileFor(bundle, file) / pathFor(bundle, file) / singlePath(bundle): String
fun isInstalled(bundle): Boolean
fun missingFiles(bundle): List<ModelCatalog.File>
fun installedBundles(): List<ModelCatalog.Bundle>
fun installedBytes(): Long
fun partialBytes(bundle): Long
fun delete(bundle)
suspend download(bundle, onProgress: (Progress) -> Unit = {})  // 断点续传 + 大小/SHA256 校验
suspend importFromUri(bundle, uri): String
// Progress(bundleId, fileName, downloadedBytes, totalBytes, completedFiles, totalFiles, fraction)
```

### `ModelCatalog`（`ai/ModelCatalog.kt`）
```kotlin
Bundle(id, role, displayName, description, files, weightsLicense, attribution, sourceUrl)
      .totalBytes / .totalMb
ModelCatalog.all / .defaultInstall / .defaultInstallBytes / .asrBundles / .byId(id)
ModelCatalog.VAD_SILERO / .SPEAKER_CAMPP_PLUS_ZH / .ASR_SENSE_VOICE_SMALL / .ASR_WHISPER_SMALL / .ASR_WHISPER_TINY
Role ∈ {VAD, SPEAKER, ASR}
```

### 导出（`export/TranscriptExporter.kt`，30 个测试已通过）
```kotlin
TranscriptExporter.export(detail, format, includeTimestamps=true, includeSpeakers=true): String
TranscriptExporter.suggestedFileName(detail, format): String
ExportFormat ∈ {TXT, MARKDOWN, JSON, SRT, CSV}  // 有 .label / .extension / .mimeType
```
**已保证：导出结果绝不含声纹 embedding**（有测试断言）。

### 共享 UI 工具（`ui/common/CommonUi.kt`）— 复用，不要重写
```kotlin
formatDuration(ms) / formatClockWithMillis(ms) / formatDate(ms) / formatRelativeDay(ms) / formatBytes(bytes)
SpeakerDot(index, size) / SpeakerChip(name, index) / colorForSpeaker(index)
TranscriptionBadge(status) / EmptyState(icon, title, subtitle) / InfoCard(title, body, tone)
SectionHeader(text) / TruncatedText(text, maxLines) / CardTone{INFO, WARN, ERROR}
```

### 数据模型（`domain/Models.kt`）
```kotlin
Recording(id, title, createdAt, startedAt, durationMs, type, audioPath, status,
          transcriptionStatus, sampleRate, channels, fileSize, notes, audioSource,
          captureSource, language, errorMessage, speakerCount, segmentCount,
          transcriptPreview, imported)
          .isTranscribed / .hasAudio / .captureLabel
Speaker(id, recordingId, name, embedding: FloatArray?, colorIndex, isMe)
TranscriptSegment(id, recordingId, speakerId: Long?, startMs, endMs, text, confidence, isEdited, orderIndex)
TranscriptDetail(recording, speakers, segments).speakerById(id)
SearchHit(segmentId, recordingId, recordingTitle, recordingCreatedAt, speakerId, speakerName,
          startMs, endMs, text, recordingDurationMs)
```

---

## 6. ★ 已确认的技术结论：微信通话音频

**这是整个项目最重要的结论，已被三份独立尽调交叉验证，不要推翻重做调研。**

### 结论
> **普通第三方 App 无法直接获取微信通话双方的内部音频。唯一可靠的非 Root 方案是「微信开扬声器 + 麦克风采集」。**

### 证据链（全部有出处）
1. **`CAPTURE_AUDIO_OUTPUT` 权限级别 = `signature|privileged|role`**
   AOSP `core/res/AndroidManifest.xml:6507-6508`。普通 App 拿不到。
2. **`VOICE_CALL` / `VOICE_UPLINK` / `VOICE_DOWNLINK` 都要求该权限**
   AOSP `MediaRecorder.java:292-320`，注释原文：*"reserved for use by system components and is not available to third-party applications"*。
3. **`AudioPlaybackCapture` 从结构上排除通话音频**
   AOSP `AudioPlaybackCaptureConfiguration.java:38-40` 原文：
   *"the usage value MUST be `USAGE_UNKNOWN` or `USAGE_GAME` or `USAGE_MEDIA`. **All other usages CAN NOT be captured.**"*
   通话是 `USAGE_VOICE_COMMUNICATION` → 永远捕获不到。
4. **`ACCESS_CALL_AUDIO`（默认拨号器通话录音）从未发布**
   revert commit `57a3769fb12dc8b2df457e0919850bd976716706` 原文：*"Reason for revert: Feature has been postponed"*；已确认当前 AOSP master 中不存在该权限。
5. **没有任何开源项目验证过微信通话双方录音**
   调研 16 个项目 + 全量 grep `wechat|微信|weixin` + 查 issue：
   - `kitsumed/ShizuCallRecorder` issue #108 原文：*"Currently, none of the chat applications have a built-in call recorder."*（状态：closed，0 评论）
   - `boldbeastsoft/CallRecordingFix` README 声称支持微信，但音频路径在**闭源付费 App** 里，本仓库无采集代码 → 无依据的营销声明
   - `jagobandhusome/universal-android-call-recorder...whatsapp` 同一份 README 自相矛盾：标题声称支持微信，正文承认 *"Android does not give third-party apps a silent tap of WhatsApp / Telegram / similar VoIP audio."*
   - CallVault / JemRec / cally：`search/issues?q=repo:X+wechat` → `total_count: 0`，从未提及
6. **能真正拿到双方音频的项目全部需要 shell 级权限 + 全部 GPL-3.0**
   - ShizuCallRecorder（Shizuku→app_process→scrcpy-server→`AudioRecord(VOICE_CALL)`）
   - CallVault（唯一实现真正的 VoIP 远端 tap：`AudioPolicy`/`AudioMix` + `USAGE_VOICE_COMMUNICATION` + `ROUTE_FLAG_LOOP_BACK_RENDER`；但硬约束是「策略必须在通话音频轨道创建之前就注册好」，且 vivo/iQOO 被其自己的文档标记为永久不可行）
   - cally / opencall-recorder（Shizuku 或 loopback ADB）
   **许可证：ShizuCallRecorder / CallVault / cally / jagobandhusome 全部 GPL-3.0，且 CallVault 额外带 Section 7 附加条款。**
   → 与本项目「可能闭源/商业化」的诉求冲突，**不予采用**（详见 §10 合规红线）。

### 因此本项目的三种正式方案
| 模式 | 说明 | 可行性 |
|---|---|---|
| **A 普通录音** | 手动录音，麦克风录环境声 | ✅ 立即可用 |
| **B 微信通话 + 扬声器** | 微信点「扬声器」，麦克风同时录到双方 | ✅ 唯一可靠的非 Root 方案；回声/音量差异是主要质量风险 |
| **C 导入已有音频** | 导入 WAV/MP3/M4A/AAC → VAD→ASR→分离 | ✅ 兜底方案，AI 能力始终完整 |

### App 内的落地方式
`FeasibilityScreen`（待写）必须**运行真实探测**并如实展示，而不是只贴文字结论：
`MicCaptureEngine.probeSources` = `[MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION, CAMCORDER, DEFAULT]`
`MicCaptureEngine.probe(source, durationMs)` 返回 `CaptureProbe`：
```kotlin
CaptureProbe(requested, actualSource, started, errorMessage, sampleRate, channelCount,
             echoCancelerApplied, noiseSuppressorApplied, initialDbFs, appearedSilent, verdict)
CaptureProbe.Verdict ∈ {WORKS, BLOCKED, UNAVAILABLE}
             BLOCKED = 启动了但全是数字静音 → 该音源在本机被封
fun summary(): String   // 已写好的中文单行摘要
```
**关键设计**：`VOICE_CALL` 等系统权限音源被故意排除在可录音清单之外，只在文档/报告里说明为何不可用——绝不能把它们列成「可选项」，那是不诚实的。

---

## 7. ★ 已验证的成果（含验证方法与数据，别重复劳动）

### 7.1 单元测试：49 个全部通过
```
SpeakerClustererReferenceTest   tests=19  failures=0
TranscriptExporterTest          tests=30  failures=0
```

### 7.2 ★ 聚类算法用**真实声纹**校准（这是最硬的验证）
本地用 sherpa-onnx 1.13.8 **Python 参考实现**（与 AAR 同版本）跑了完整流水线，
在 4 个公开音频上生成真实 192 维 CAM++ 声纹，存为测试夹具：
`app/src/test/resources/reference/{two-speakers-en, two-speakers-en-2, four-speakers-zh, lei-jun}.{emb,segments.tsv}`

**真实相似度结构（ground truth）**：
- `two-speakers-en`：清晰 2×2 块结构 → {0,1} 相似 0.61，{2,3} 相似 0.83，跨块仅 0.32 → 教科书式 A,A,B,B 双人交替
- `four-speakers-zh`：{0,2,6} 互相 0.76-0.78，{3,4} 互相 0.85，另有 1、5 独立 → 4 人
- `two-speakers-en-2`：所有两两相似度 0.67-0.82 → **无清晰双人结构**（相似音色边界案例）
- `lei-jun`：61 段单人演讲（272 秒）

**auto-k 阈值的实测校准数据（务必保留，别再重新试参数）**：
```
fixture              k=1     k=2     k=3     k=4     k=5     k=6     真相
two-speakers-en       -    0.887   +0.081    -       -       -        2
four-speakers-zh      -    0.760   +0.065  +0.001  +0.085  +0.020     4
two-speakers-en-2     -    0.723   +0.183    -       -       -      (模糊)
lei-jun (独白)        -    0.367   -0.043  -0.095  +0.013  +0.129     1
```
**两条关键教训（已写进 `SpeakerClusterer.better()` 的 KDoc）**：
1. **单纯用「增益阈值」无法区分**：真双人 k=3 只涨 +0.081，而相似音色反而涨 +0.183 —— 方向相反，任何增益阈值都会二选一地失败。
2. **绝对轮廓系数能干净区分**：真双人 k=2 在 0.72-0.89；独白强制 k=2 只有 0.37。
   所以实现为：`k≥3` 必须先在 k=2 时达到 `MIN_SEPARATION_FOR_EXTRA_SPEAKER=0.60`，且自身 ≥ `MIN_ABSOLUTE_SILHOUETTE=0.15`。

### 7.3 ★ 修掉了一个会让整个 App「录得到但转不出字」的隐藏 bug
**`SpeechSegment.samples` 必须在 `vad.pop()` 之前读取。**
实测（sherpa-onnx 1.13.8 macOS，同一个 16 秒双人文件）：
```
pop 之前读 → 4 段，样本数 [27584, 33728, 33728, 39872]
pop 之后读 → 4 段，样本数 [0, 0, 0, 0]      ← 静默失效
```
`VadSegmenter.drain()` 已修复并在注释里记录，**不要「优化」掉这个顺序**。

同时实证确认了 `SpeechSegment.start` 的语义 = **流内绝对样本索引**（不是毫秒）：
272 秒文件 61 段的 start 严格单调，且恒满足 `start + samples.size <= fedSamples`，而毫秒解释不自洽。
`resolveStartSample()` 里保留了双重校验以防未来绑定变更。

### 7.4 真实模型流水线跑通（性能基线）
用 Python 参考实现跑通 VAD → 声纹 → ASR：
```
fixtures/lei-jun.wav          272.4s  VAD 61 段  61 个声纹成功  RTF 0.088  中文识别准确
fixtures/two-speakers-en.wav   16.0s  VAD  4 段   4 个声纹成功  RTF 0.054
fixtures/four-speakers-zh.wav  56.9s  VAD  7 段   7 个声纹成功  RTF 0.028   (8-bit PCM WAV，本解码器支持)
fixtures/two-speakers-en-2.wav 34.0s  VAD  4 段   4 个声纹成功  RTF 0.041
```
SenseVoice 中文识别示例（真实输出）：
`[28.93-36.00] 朋友们，晚上好，欢迎大家来参加今天晚上的活动，谢谢大家。`
→ 说明 VAD 切分、声纹提取、SenseVoice 识别、标点/ITN 全部工作正常。
手机端预期更慢（Galaxy S10 上 SenseVoice 单独 RTF 0.06），整体 0.2-0.3 是合理预期。

### 7.5 AAR 打包已验证
`app-debug.apk`（骨架版）中确认含：
```
lib/arm64-v8a/{libonnxruntime.so 22,249,560; libsherpa-onnx-jni.so 4,771,760;
               libsherpa-onnx-c-api.so 4,465,168; libsherpa-onnx-cxx-api.so 440,688}
lib/armeabi-v7a/{同名 4 个}
```
**16 KB page size 兼容性已实测确认**：4 个 arm64 `.so` 的 PT_LOAD 段 `p_align` 全部 = `0x4000`（16384）→ **Android 15+ 16KB 页设备可安全加载**。（这是尽调报告里唯一标记为 UNVERIFIED 的风险，现已排除。）

### 7.6 Room schema 已定稿
3 张表：`recordings` / `speakers` / `transcript_segments`，含外键 CASCADE 与 SET_NULL（支持「未知」说话人）。
`minSdk 26`，无存储权限（全部 App 私有目录 + SAF 导出）。

---

## 8. ★ 雷区 / 已踩过的坑（照着避开）

| 坑 | 症状 | 正确做法 |
|---|---|---|
| **VAD pop 顺序** | 录音正常但逐字稿全空 | 先读 `samples` 再 `pop()`（见 §7.3） |
| **CaptureSource 包名** | `Unresolved reference 'CaptureSource'` | 它在 `com.echonote.app.data.db.CaptureSource` |
| **MaterialTheme 用在 Canvas 内** | `@Composable invocations can only happen from...` | 先把颜色 `val` 出来再传进 `Canvas {}` |
| **`Icons.AutoMirrored.Filled.Warning` 不存在** | Unresolved reference | 用 `Icons.Filled.Warning`；只有 `Icons.AutoMirrored.Filled.List` 存在 |
| **没有 gradlew** | `./gradlew: No such file` | 用 §1 的绝对路径 |
| **Compose BOM 升级** | 可能拉不到缓存版本、编译变慢 | 保持 2024.12.01 |
| **Gradle 内存调大** | 8 GB 机器 OOM | 保持 `-Xmx3072m` + in-process Kotlin |
| **`material-icons-extended`** | 已引入（debug 编译稍慢，release 会被 R8 裁掉） | 保留，别为省依赖删掉，很多图标依赖它 |
| **SenseVoice 权重许可** | 以为 MIT | **代码 MIT，但权重是 FunASR MODEL_LICENSE v1.1（自定义、非 OSI）**，见 §9 |
| **想装 ONNX Runtime Android AAR** | 会和 sherpa AAR 里的 `libonnxruntime.so` **重复冲突** | **不要装**。sherpa AAR 已自带 |
| **whisper.cpp / ggml** | 以为更省资源 | 实测同一 Whisper-tiny 在 Galaxy S10 上 whisper.cpp RTF **3.52** vs sherpa-onnx **0.07**（51× 差距）→ **已弃用** |
| **CT-Transformer 标点模型** | 想加标点 | 模型 **294 MB + 4.2 MB 词表**，而 SenseVoice 自带标点 → **默认不下载**，`PunctuationEngine` 保留供用户自行导入 |
| **四个说话人分离的许可证陷阱** | 想用 `reverb-diarization-v1` | 它带 **"Rev Model Non-Production License"（禁止商业使用）**；同 tag 的 `pyannote-segmentation-3-0` 才是 MIT |
| **`OfflineSpeakerDiarization` 批处理** | 以为可以拿来即用 | **上游有未修复 bug**：issue #1708，单声道双人文件强制 `numClusters=2` 仍然 7 段全归到 `speaker_00`（两人塌成一人）。→ **本项目因此自研「每个 VAD 段提声纹 + 球形 k-means++ 聚类」，绕开该 bug** |
| **电话语音分离更难** | 期待会议级效果 | pyannote 官方基准：CALLHOME（电话）DER **26.7%** vs VoxConverse **11.2%**（约 2.4× 更差）。必须如实告知用户，并依赖「人工修正」UI |
| **TitaNet 在远场会合并说话人** | 随便选声纹模型 | 有引文指出 `eres2net_base` 在「扬声器-麦克风」录音上能分开 3-4 人，而 TitaNet-small 会合并。当前默认用 **CAM++（zh, Apache-2.0）**；若要优化模式 B，可加 `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`（39,593,761 bytes，同样 Apache-2.0）作为可选模型 |

---

## 9. ★ 外部资源清单（全部已 curl 实测，URL/字节数/SHA256 可直接信任）

### 已下载并校验的模型（本地缓存 `/tmp/echonote-models/`，共 277 MB）
| 模型 | 字节数 | SHA-256 | 权重许可 |
|---|---|---|---|
| `silero_vad.onnx` | 1,807,522 | `a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28` | MIT |
| `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` | 28,281,138 | `f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11` | **Apache-2.0** ✅ |
| `model.int8.onnx` (SenseVoice-Small) | 239,233,841 | `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51` | ⚠️ FunASR MODEL_LICENSE v1.1 |
| `tokens.txt` (SenseVoice) | 315,894 | `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc` | 同上 |

前 4 个 SHA256 **已写进 `ModelCatalog.kt`**，下载时自动校验。Whisper 系列暂未固定 SHA256（只有字节数校验）。

### 模型下载 URL（均已验证 HTTP 200）
```
# VAD（体积小、已验证可用；阈值 0.5 经实测有效）
https://huggingface.co/csukuangfj/vad/resolve/main/silero_vad.onnx
# 备用更小的官方版：https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx  (643,854 bytes)

# 声纹（默认，中文，Apache-2.0）
https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx
# 模式 B（扬声器+麦克风）可选更优：3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx (39,593,761)

# ASR 主模型（SenseVoice-Small int8）
https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx
https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt

# ASR 备选（权重许可最干净：Apache-2.0）
https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx   # 112,442,483
https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx   # 262,226,114
https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt          #     816,730
https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx     #  12,937,772
https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx     #  89,855,401
https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt            #     816,730
```
**不要用 GitHub 的 `.tar.bz2` 模型包**：Android 没有 bz2 解码器，无法在 App 内解压。全部用 HuggingFace 单文件直链。

### ASR 选型依据（实测数据，别重新选型）
- **SenseVoice-Small 为主模型**：AISHELL-1 CER **2.96%** vs Whisper-small 10.04%（SenseVoice 论文 arXiv 2407.04051v3 Table 6）；WenetSpeech meeting 7.44% vs 25.62%。手机 RTF 0.06。
- **Whisper 为备选**：许可最干净（代码 MIT + 权重 Apache-2.0），但中文准确率明显更差、体积更大。
- **Vosk / Moonshine**：中文准确率或模型许可不占优，不采用。
- 许可证拆分提醒：**SenseVoice 代码 MIT，权重不是**（FunASR MODEL_LICENSE v1.1：要求署名 + 保留模型名，无禁止商用条款，但属非 OSI 自定义许可）。**必须写进 `THIRD_PARTY_LICENSES.md`。**

---

## 10. ★ 合规红线（项目要求，不可妥协）

**绝对不做**（写代码时不要引入任何此类实现）：
- ❌ 要求 Root / ADB / Shizuku 作为**默认**方案
- ❌ Hook 微信 / 修改微信 / 注入微信进程 / 破解微信
- ❌ 伪造 `CAPTURE_AUDIO_OUTPUT`、`VOICE_CALL` 等系统权限或声称拥有它们
- ❌ 用 Accessibility Service 偷音频
- ❌ 用户不知情时后台录音（**录音期间系统通知必须始终可见**——`RecordingService` 已实现）
- ❌ 默认上传录音/逐字稿到服务器（`cloudAsrConsent` 默认 `false`）
- ❌ 复制 GPL-3.0 / AGPL 代码（ShizuCallRecorder / CallVault / cally / jagobandhusome / 23rd/Scrib）
- ❌ 使用许可不明或无 LICENSE 的仓库（OpenWhisprAndroid、myfreax/AudioRecorder、GenericJam/mob_audio_capture、boldbeastsoft/CallRecordingFix、conwerter1 之外的 no-license 项目、Nomily-Ai/nomily-app）
- ❌ 使用非商用许可模型（`sherpa-onnx-reverb-diarization-v1` 的 Rev Model Non-Production License）
- ❌ 导出用户声纹（`Speaker.embedding` 是生物特征，只能留在本机；导出测试已有断言保证）

**基座选型（已定，且是 Apache-2.0/MIT 组合）**：
- 音频 AI 基座：**`k2-fsa/sherpa-onnx` v1.13.8**（Apache-2.0，预编译 AAR）
  → VAD(Silero, MIT) + 声纹(CAM++, Apache-2.0) + ASR(SenseVoice，权重自定义许可) + 标点(可选) 全部来自它
- 录音采集层：**自研**，参考 `Genymobile/scrcpy`（Apache-2.0）的 hidden-API 手法思路与 `jemcik/JemRec`（Apache-2.0）架构，**但不复制任何 GPL 代码**
- 说明：`sherpa-onnx` 的 Android 示例里**没有任何**通话/系统音频采集代码（全量 grep `VOICE_*|MediaProjection|AudioPlaybackCapture|REMOTE_SUBMIX` 零命中，`MediaRecorder.AudioSource` 14 处全是 `MIC`），所以采集层必须自研。

---

## 11. 剩余工作清单（按优先级，建议照此顺序）

### P0 — 让项目能编译出 APK（唯一阻塞项）
1. 创建 5 个缺失界面（签名见 §4）。**不需要改任何现有文件**——5 个文件一建好，12 个编译错误全部消失：
   - `SearchScreen` — 全文/标题/说话人搜索 + 日期筛选 + 关键词高亮 + 点击跳转 `(id, startMs)`
   - `SettingsScreen` — ASR 模型选择 / 语言 / ITN / 说话人分离开关 / 说话人数与阈值 / AEC·NS·AGC / 播放速度 / 动态取色 / 云端开关(默认关) / 入口按钮
   - `DetailScreen` — **本项目产品核心**：MediaPlayer 播放（0.75/1/1.25/1.5/2x）+ 逐字稿列表 + 点击文字跳音频 + 拖音频高亮文字 + 编辑文本/说话人/时间戳 + 修改「我/对方/未知」+ 合并说话人 + 导出
   - `ModelsScreen` — 模型下载/删除/进度/许可展示 + 已安装总大小 + 可用空间
   - `FeasibilityScreen` — 微信音频结论 + 三种模式 + `MicCaptureEngine.probeSources` 实测 + 运行时版本 + 合规红线
2. `$GR :app:assembleDebug` → 确认 APK 生成且含 8 个 `.so`（arm64-v8a + armeabi-v7a 各 4）。

### P1 — 补完功能缺口
3. **导入音频 UI**：`TranscriptionManager.importAudio(uri)` 已实现，但**没有任何界面调用它**。在 Home 或 History 加一个 `ActivityResultContracts.OpenDocument()` 入口（mime `audio/*`），这是「模式 C」的兜底能力，很重要。
4. `TranscriptionService` / `MediaProjectionPermissionActivity` 目前是空壳。要么实现，要么**从 manifest 移除**以免误导（`RecordingActionReceiver` 实际未被使用，通知直接走 `PendingIntent.getService` → 也应移除或实现）。
5. 「我」的声纹录入 UI（`SelfVoiceprintStore` 已实现，`SpeakerEmbeddingEngine.embedAndAverage` 可用），让「我/对方」的判定有依据而非猜测。
6. 波形显示：`MicCaptureEngine.waveform` / `levelDb` 已暴露 `StateFlow`，但 `RecordingScreen` 因未绑定服务而拿不到真实数据（目前是合成动画）。若要真实波形，需要用 `bindService` 或把电平写进进程级单例。

### P2 — 测试与文档
7. **补 DSP/音频单元测试**（目前完全没有，是最大的测试空白，且这些是纯 Kotlin、完全可测）：
   - `WavWriter` 写→`WavFileReader` 读 往返一致性；header 字段正确
   - `WavRepair.repairInPlace` 修复被截断的 header（模拟崩溃）
   - `WavFileReader` 边界：8-bit / 24-bit / float32 / 立体声降混 / 奇数长度 chunk 补齐 / `data` 不是最后一个 chunk / 文件截断 / `WAVE_FORMAT_EXTENSIBLE`
   - `Resampler`：48k→16k 正弦波频率与幅度保持；10 kHz 音调在 16k 输出应被强衰减（验证抗混叠）
   - `StreamingResampler` 分块结果 ≈ 一次性 `Resampler` 结果（同一容差）
   - `PcmAudioUtils`：rms / peak / clippingRatio / isSilent / downmix / pcm16 互转 / envelope
   - `TranscriptAssembler`：跨说话人切分、无 token 时间戳时降级、未知说话人、CJK 与拉丁文拼接空格规则
8. **Robolectric Room DAO 测试**（`robolectric:4.16.1` 已在 `testImplementation`）：in-memory DB 验证 `search()` 的 LIKE 匹配、外键 CASCADE 删除、`mergeSpeakers` 事务。**建议 `@Config(sdk = [34])`**（SDK 34 支持最稳）。
9. **5 份文档**（项目要求，目前**一份都没有**）：
    - `README.md`（含 `Based on:` 段落，说明基于 sherpa-onnx 及其 Apache-2.0/Apache 声明）
    - `ARCHITECTURE.md`
    - `TECHNICAL_FEASIBILITY.md`（微信音频结论 + 实测数据 + 真机验证状态）
    - `THIRD_PARTY_LICENSES.md`（**必须含**：sherpa-onnx Apache-2.0、Silero VAD MIT、3D-Speaker CAM++ Apache-2.0、SenseVoice 代码 MIT/**权重 FunASR MODEL_LICENSE v1.1**、Whisper 代码 MIT/权重 Apache-2.0、以及为什么拒绝 GPL-3.0 与 Rev Non-Production 模型）
    - `CHANGELOG.md`
    - 另建议补 `MODEL_URLS_VERIFIED.md`（`ModelCatalog.kt` 的注释里已经引用了这个文件名，但目前不存在）
10. **Git 初始化 + 至少一个完整 commit**（当前**没有任何 git 仓库**）。
11. 清理垃圾文件：workspace 根目录的 `ngc.html`（0 字节）、`.DS_Store`；`EchoNote/gradle/wrapper/` 空目录。

### P3 — 真机与发布
12. **真机验证（当前最大的未完成项）**：`adb devices` 为空，**没有连接任何 Android 手机**。必须如实声明：
    > **微信双方音频采集尚未经过真实设备验证。**
    一旦有设备，`FeasibilityScreen` 的实测功能就是为这个准备的：它会给出本机每个 `AudioSource` 的真实结论（可用 / 只有数字静音 / 无法启动），以及 sherpa-onnx natives 是否成功加载。
13. `assembleRelease`（已配 R8 + debug 签名以便直接安装，`proguard-rules.pro` 已保留 `com.k2fsa.sherpa.onnx.**`）。注意 release 体积应从 70 MB 显著下降。
14. 真机功能测试矩阵（项目要求）：1/10/30/60 分钟录音；微信语音/视频通话；扬声器/听筒/蓝牙；锁屏、切换 App、来电、蓝牙断开、低电量、存储不足、权限撤销、App 被杀（`WavRepair` 就是为最后一项准备的）。
15. Room `exportSchema` 目前是 `false`，正式发布前应打开并加入首次 migration。

---

## 12. 建议的执行顺序（最短路径）

```
1. 读 §5 的 API 契约（别自己重新设计接口）
2. 写 5 个界面（可并行；签名见 §4）。不需要动任何现有文件
3. $GR :app:assembleDebug  → 确认 APK + 8 个 .so   ← 到这里就有一个可安装的完整 App 了
4. 加导入音频入口（模式 C）
5. 补 DSP/音频单测 + Robolectric DAO 测试
6. 写 5 份 md 文档 + MODEL_URLS_VERIFIED.md
7. git init + commit
8. 若有真机：装 APK → 跑 FeasibilityScreen → 做测试矩阵 → 更新报告里的「微信双方音频」结论为「已验证/部分可用」
   若无真机：在最终报告里明确写「未完成真实设备验证」
```

---

## 13. 参考文档（本 workspace 内，内容详实，勿重复调研）

| 文件 | 内容 | 关键结论 |
|---|---|---|
| `android-callrec-oss-due-diligence.md` | 16 个通话录音项目逐行代码级分析（git clone + 读源码 + LICENSE 文件）、微信证据台账、AOSP 平台事实（带 URL）、逐项目复用/许可裁决 | 无任何项目验证过微信录音；GPL-3.0 雷区；`CAPTURE_AUDIO_OUTPUT` 级别 |
| `android-offline-asr-due-diligence.md` | ASR 选型：AAR 精确坐标与字节数、模型 URL+字节、whisper.cpp 实测 RTF 对比、中文 CER 证据、每模型许可拆分 | SenseVoice 主 / Whisper 备；whisper.cpp 实测 51× 慢；AAR 不在 Maven Central |
| `ANDROID_OFFLINE_DIARIZATION_DUE_DILIGENCE.md` | 说话人分离：11 项目对比、`OfflineSpeakerDiarization` 上游 bug #1708、模型许可矩阵、电话语音 DER 数据、精确 Kotlin API（含 JNI 层字段名验证） | **不要用批处理 `OfflineSpeakerDiarization`**（双人塌成一人）；`reverb-diarization-v1` 禁商用；CAM++ Apache-2.0 可用 |

### 本地仍存活的辅助资产（重启机器可能丢失）
- `/tmp/echonote-models/`（277 MB）：已下载校验的模型 + 4 个音频夹具 + `reference_pipeline_output.json` + `ref_pipeline.py` / `verify_ref.py` / `dbg2.py`（参考实现验证脚本）
- `/tmp/sherpa-ref/`：Python venv，装了 `sherpa-onnx 1.13.8` + `numpy`，可随时重跑参考流水线
  → **想重新生成测试夹具或验证某个 API 语义时，这是最省事的办法**（比在 Android 上试快得多）
- `/tmp/echonote-dl/sherpa-onnx-1.13.8.aar`：AAR 原始下载副本

---

## 14. 诚实声明（最终报告必须原样保留这些措辞）

截至目前，以下事项**没有**完成或**没有**验证，不许含糊：

| 项 | 状态 |
|---|---|
| `:app:assembleDebug` | **FAILED**（缺 5 个界面文件 → 12 个编译错误，全部在 `EchoNoteNavigation.kt`；其余文件均已编译通过） |
| 单元测试 | **49/49 PASS**（聚类 19 + 导出 30） |
| DSP/音频单测 | **未编写** |
| Robolectric / 集成测试 | **未编写** |
| 真机测试 | **未进行——没有连接任何 Android 设备** |
| **微信双方音频** | **尚未完成真实设备验证**（结论基于 AOSP 源码 + 16 项目尽调，**不是**本机实测） |
| AI 转写 | 引擎**已完成**；参考实现流水线**已跑通**；**Android 端未在真机运行过** |
| Speaker Diarization | 算法**已完成并用真实声纹校准**；**Android 端未在真机运行过** |
| 导入音频（模式 C） | 后端 `importAudio()` **已完成**；**无 UI 入口** |
| 文档 5 件套 | **未编写** |
| Git | **未初始化** |
| APK | 现有 70 MB APK 是**早期骨架**，不含引擎，**不可交付** |
