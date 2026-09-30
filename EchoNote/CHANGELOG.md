# Changelog

本项目所有显著变更都记录在本文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/spec/v2.0.0.html)。

## [Unreleased]

### Added

#### 录音与采集
- `RecordingService` 前台录音服务（`FOREGROUND_SERVICE_TYPE_MICROPHONE`），录音期间系统通知常显，支持暂停 / 恢复
- `MicCaptureEngine`：`AudioRecord` 采集封装（AEC / NS 效果检测、电平与波形 `StateFlow`），附 `probe()` 音源实测与 `probeSources` 合法音源清单
- `WavWriter` 流式 WAV 写盘 + `WavRepair` 崩溃截断恢复
- 三种录音模式设计落地：A 普通录音（麦克风）/ B 微信通话开扬声器 + 麦克风采集（唯一可靠的非 Root 方案）/ C SAF 导入已有音频（WAV/MP3/M4A/AAC）

#### 离线转写管线（全部端上运行，不触网）
- `TranscriptionPipeline`：解码 → 降混重采样 16 kHz → VAD → 声纹/ASR → 聚类 → 对齐合并的全流程编排，流式分块处理，内存有界
- `VadSegmenter`：Silero VAD 封装（含「`samples` 必须在 `pop()` 之前读取」的实测 bug 修复）
- `AsrEngine`：SenseVoice-Small int8（主，自带标点与 ITN）/ Whisper tiny、small int8（备）封装
- `SpeakerEmbeddingEngine`：CAM++ 192 维声纹提取；`PunctuationEngine` 保留供用户 SAF 自行导入标点模型
- `SpeakerClusterer`：自研球形 k-means++ 聚类（纯 Kotlin、确定性），轮廓系数自动选 k，用真实声纹夹具校准阈值（`MIN_SEPARATION_FOR_EXTRA_SPEAKER=0.60` / `MIN_ABSOLUTE_SILHOUETTE=0.15`）；绕开上游 `OfflineSpeakerDiarization` 双人塌缩 bug（k2-fsa/sherpa-onnx#1708）
- `TranscriptAssembler`：ASR 文本与说话人时间段的纯逻辑对齐/合并/切分
- `TranscriptionManager`：转写编排、进度 `StateFlow`（DECODING→VAD→ASR→EMBEDDING→CLUSTERING→SAVING→DONE/FAILED）、`enqueue()`、`importAudio()` 导入入口

#### 音频基础库
- `PcmAudioUtils`（RMS/峰值/削波/降混/PCM16 互转）、`Resampler`（sinc 抗混叠）、`StreamingResampler`（流式）、`WavFileReader`（手写 RIFF：8/16/24/32bit、float、EXTENSIBLE）、`AudioFileDecoder`（统一解码入口）

#### 数据与存储
- Room 三表 schema（`recordings` / `speakers` / `transcript_segments`，外键 CASCADE 与 SET_NULL）
- `RecordingRepository`：数据访问、多条件搜索、说话人合并、逐字稿编辑、删除
- `SettingsRepository`（DataStore 偏好）、`SelfVoiceprintStore`（「我」的声纹，仅存本机）、`RecordingStorage`（App 私有存储）
- 领域模型 `domain/Models.kt` 与 Entity→Domain 映射

#### 导出
- `TranscriptExporter`：TXT / Markdown / JSON / SRT / CSV 五种格式，含文件名建议与 MIME；**测试断言保证导出绝不含声纹 embedding（生物特征）**

#### 模型管理
- `ModelCatalog`：5 个模型 Bundle（URL + 字节数 + 前 4 个含 SHA-256 + 许可标注），全部经 `curl` HTTP 200 实测（2026-09-30），见 `MODEL_URLS_VERIFIED.md`
- `ModelManager`：HuggingFace 单文件直链下载、断点续传、字节数 + SHA-256 校验、SAF 本地导入、安装大小统计
- 刻意排除 whisper.cpp（实测同 checkpoint RTF 3.52 vs sherpa-onnx 0.07，51× 差距）与 CT-Transformer 标点模型（294 MB，SenseVoice 已自带标点）

#### UI（Jetpack Compose, Material 3）
- 全部 9 个界面完成并编译通过：`HomeScreen` / `RecordingScreen` / `HistoryScreen` / `SearchScreen` / `SettingsScreen` / `DetailScreen` / `ModelsScreen` / `FeasibilityScreen` + `CommonUi` 共享组件库
- `DetailScreen`（产品核心）：MediaPlayer 播放（0.75–2x）、逐字稿与音频联动（点击跳转 / 播放高亮自动滚动）、段落文本 / 说话人 / 时间戳编辑、说话人重命名 / 标记「我」/ 合并、五格式导出
- `SearchScreen`：全文 / 标题 / 说话人三维搜索 + 时间范围过滤 + 关键词高亮
- `SettingsScreen`：模型选择、语言、ITN、说话人分离参数、采集音源与 AEC/NS/AGC、转写触发、播放速度、动态取色、云端开关（默认关）、模型许可说明
- `ModelsScreen`：模型下载（断点续传 + 进度条）/ 删除 / 已安装统计，一键安装推荐组合
- `FeasibilityScreen`：本机音源真实探测（`MicCaptureEngine.probeSources`，区分 WORKS / BLOCKED=数字静音 / UNAVAILABLE）、真实 sherpa-onnx natives 版本、微信音频结论与合规承诺
- `HomeScreen` 导入音频入口（SAF `OpenDocument`，模式 C）
- `SettingsScreen` 「我的声纹」录入（5 秒录音 → CAM++ 提取 → 均值 → 本机 DataStore；含数据去向说明与删除）

#### 可行性探测
- 见 `FeasibilityScreen`；`MicCaptureEngine.probe()` 在设置页声纹录入与可行性页两处使用

#### 测试
- 49 个单元测试全部通过：
  - `SpeakerClustererReferenceTest`（19）：基于 sherpa-onnx 1.13.8 Python 参考实现在 4 段真实音频上生成的 192 维 CAM++ 声纹夹具（`app/src/test/resources/reference/`）
  - `TranscriptExporterTest`（30）：五格式导出、时间戳/说话人开关、embedding 不外泄断言
- 新增 DSP / 音频库测试（`WavWriter` / `WavRepair` / `WavFileReader` / `Resampler` / `StreamingResampler` / `PcmAudioUtils` / `TranscriptAssembler`）与 Robolectric Room DAO 测试

#### 文档
- `README.md`（含 "Based on" sherpa-onnx Apache-2.0 声明）、`ARCHITECTURE.md`、`TECHNICAL_FEASIBILITY.md`（微信音频证据链 + 诚实声明）、`THIRD_PARTY_LICENSES.md`（含拒绝理由）、`CHANGELOG.md`、`MODEL_URLS_VERIFIED.md`

### Removed
- 三个从未实现、也从未被任何代码引用的占位空壳及其 manifest 声明：`TranscriptionService`（转写实际由 `TranscriptionManager` 协程完成）、`RecordingActionReceiver`（通知按钮直接 `PendingIntent.getService`）、`MediaProjectionPermissionActivity`（AudioPlaybackCapture 未实现）；`FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限与 `foregroundServiceType` 中的 `mediaProjection` 一并移除

### 已知限制（如实）
- 未进行任何真机测试（开发环境无连接设备）；Android 端引擎从未在真机运行（AI 管线经同版本 Python 参考实现验证）
- 微信双方音频采集尚未完成真实设备验证（结论基于 AOSP 源码 + 16 个开源项目代码级尽调）
