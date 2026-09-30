# EchoNote 架构说明

> 代码根目录：`EchoNote/app/src/main/java/com/echonote/app/`
> 本文描述的是已实现代码的真实架构，不是计划稿。

## 1. 分层架构

自上而下单向依赖，无跨层直连；唯一的依赖入口是手动 DI 容器 `AppContainer`（不使用 Hilt/Koin，依赖图小到不需要代码生成）：

```
┌────────────────────────────────────────────────────────────┐
│  ui（Jetpack Compose, Material 3）                          │
│  home / recording / history / search / detail / models /    │
│  settings / feasibility + theme + common(CommonUi)          │
├────────────────────────────────────────────────────────────┤
│  navigation（EchoNoteNavigation.kt，NavHost 路由）           │
├────────────────────────────────────────────────────────────┤
│  di（AppContainer.kt — 手动服务定位器，唯一依赖入口）          │
├──────────┬──────────┬──────────┬──────────┬────────────────┤
│ repository│    ai    │  audio   │transcription│   export    │
│ Recording │ModelCatalog│MicCapture│Transcript  │Transcript   │
│ Repository│ModelManager│Engine    │Assembler + │Exporter     │
│ (搜索/删除)│Vad/Asr/   │Wav 系列/  │Transcription│(TXT/MD/JSON │
│          │Embedding/ │Resampler │Pipeline    │/SRT/CSV)    │
│          │Clusterer/ │          │            │             │
│          │TranscriptionManager    │            │             │
├──────────┴──────────┴──────────┴──────────┴────────────────┤
│  data（Room: Entities/Daos/EchoNoteDatabase ·               │
│        DataStore: SettingsRepository · SelfVoiceprintStore ·│
│        storage: RecordingStorage — App 私有目录）            │
├────────────────────────────────────────────────────────────┤
│  service（RecordingService — 前台服务，FOREGROUND_SERVICE_    │
│           TYPE_MICROPHONE，录音期间通知常显）                 │
│  domain（Models.kt — 领域模型 + Entity→Domain 映射）          │
└────────────────────────────────────────────────────────────┘
```

`AppContainer` 暴露：`database` / `settings` / `storage` / `selfVoiceprint` / `modelManager` / `transcription` / `repository` / `decoder()` / `nativeRuntimeInfo()`（读取真实 sherpa-onnx 与 onnxruntime 版本，同时验证 `.so` 能否加载）/ `supportedAbis()`。

## 2. 数据流（转写管线，文字版）

```
录音（RecordingService 前台服务，PCM 流式写入）
   │
   ▼
WAV 文件（App 私有目录，WavWriter 流式写 + WavRepair 崩溃恢复）
   │
   ▼
解码 + 降混 + 重采样到 16 kHz 单声道（AudioFileDecoder → StreamingResampler，
分块流动，长文件不整段进内存）
   │
   ▼
VAD 切分（Silero，silero_vad.onnx；SpeechSegment.samples 必须在 vad.pop() 之前
读取——顺序反了会得到全零样本，这是实测踩过的隐藏 bug，已修复并记录在 VadSegmenter）
   │
   ├──► 每段语音 → CAM++ 声纹提取（SpeakerEmbeddingEngine，192 维向量）
   │            │
   │            ▼
   │      球形 k-means++ 聚类（SpeakerClusterer，纯 Kotlin，
   │      自研实现，理由见 §3）
   │
   └──► 每段语音 → SenseVoice ASR（AsrEngine，int8，含标点/ITN）
   │
   ▼
TranscriptAssembler：把「ASR 文本片段」与「说话人时间段」对齐、合并、切分
（纯逻辑，跨说话人切分 / 无 token 时间戳时降级 / CJK-拉丁拼接空格规则）
   │
   ▼
Room 入库（recordings / speakers / transcript_segments；
Speaker.embedding 只存本机，绝不导出）
   │
   ▼
导出（TranscriptExporter：TXT / Markdown / JSON / SRT / CSV，
30 个单元测试覆盖；导出结果经测试断言绝不含声纹 embedding）
```

**按流式构建**：音频永远不会整段驻留内存——分块流经重采样器进入 VAD，每段语音在其闭合瞬间完成声纹提取与识别后即刻丢弃。1 小时的 48 kHz 立体声导入只需几百 KB 音频缓冲，而不是整文件约 700 MB 的原始数组。全流程运行在本机，任何阶段都不访问网络。

## 3. 为什么自研聚类，而不是用上游 `OfflineSpeakerDiarization`

sherpa-onnx 自带批处理说话人分离管线（pyannote 分段 + 声纹 + `FastClustering`），但它有一个**未修复的上游 bug，恰好命中本项目最核心的场景**：

- [k2-fsa/sherpa-onnx #1708](https://github.com/k2-fsa/sherpa-onnx/issues/1708)（2025-01 开启，至今 open）：对单声道双人访谈音频，即使把 `numClusters` 钉死为 2，输出仍然是 **7 个片段全部归入 `speaker_00`——两个人塌成一个人**。上游维护者仅回复"过完年再看"，无修复。
- 同类问题在 #1466 / #2445 也有记录：自动聚类（`numClusters=-1`）精度明显下降，维护者的答案是"阈值自己调"。

因此本项目采用 **自研管线**：每个 VAD 段独立提取声纹（`SpeakerEmbeddingExtractor` + CAM++），再用 `SpeakerClusterer` 做球形 k-means++（余弦距离，L2 归一化，固定随机种子与重启次数，完全确定性）：

- 选球形 k-means++ 而非凝聚层次聚类：后者最坏 O(n³)、内存无界，不适合中端手机处理一小时通话；
- 说话人数未知时用**质心近似轮廓系数**选 k（不是 elbow 猜测），并用真实声纹夹具校准了两条阈值：
  - `k≥3` 必须先在 k=2 达到分离度 `MIN_SEPARATION_FOR_EXTRA_SPEAKER = 0.60`，且自身 ≥ `MIN_ABSOLUTE_SILHOUETTE = 0.15`；
  - 校准数据：真双人 k=2 轮廓系数 0.72–0.89，单人独白强制 k=2 只有 0.37——**绝对轮廓系数能干净区分**，而单纯"增益阈值"方向相反、必然二选一失败；
  - 测试夹具由 sherpa-onnx **1.13.8 Python 参考实现**（与 AAR 同版本）在 4 段真实音频上生成，19 个聚类测试全部基于这些真实 192 维声纹。
- 刻意**不使用**音量、声道、时间先后作为说话人依据：混录单声道通话里对方经常更响，三者全是误导信号。

## 4. 为什么不用 whisper.cpp

同一 Whisper-tiny checkpoint 在同一台设备（Samsung Galaxy S10）上实测：

| 引擎 | 30 s 音频推理耗时 | RTF |
|---|---|---|
| sherpa-onnx（ONNX Runtime） | 2,068 ms | **0.07** |
| whisper.cpp（GGML） | 105,596 ms | **3.52** |

**51 倍差距**（数据来源：VoicePing 离线转写基准，`voiceping-ai/android-offline-transcribe`，2026-09 更新）。whisper.cpp 在中端 Android 上无法做可用的离线转写，且不提供 AAR，需要自备 NDK/CMake 构建——因此弃用。ASR 选型详情（SenseVoice 主 / Whisper 备的准确率与许可对比）见 [android-offline-asr-due-diligence.md](../android-offline-asr-due-diligence.md)。

## 5. 线程模型

- **录音**：`RecordingService` 是前台服务（`FOREGROUND_SERVICE_TYPE_MICROPHONE`），`ServiceCompat.startForeground` 保证录音期间系统通知常显；`AudioRecord` 采集在服务的工作线程上进行，PCM 经 `WavWriter` 流式落盘。
- **转写**：`TranscriptionManager` 用协程编排——
  - `suspend transcribe(recordingId)` 阻塞至完成；`enqueue(recordingId)` 是 fire-and-forget（内部 `Dispatchers.IO` 协程）；
  - `TranscriptionPipeline` 内部 `withContext(Dispatchers.IO)` 执行，逐块 `ensureActive()` 检查取消；
  - 进度通过 `StateFlow<Map<Long, TranscriptionProgress>>`（`DECODING → VAD → ASR → EMBEDDING → CLUSTERING → SAVING → DONE / FAILED`）暴露给 UI，无需轮询。
- **UI**：Compose 界面通过 `AppContainer` 拿到的 `Flow` / `StateFlow` 观察数据，Room Flow 自动驱动列表刷新。

## 6. 目录树

```
EchoNote/
├── settings.gradle.kts / build.gradle.kts / gradle.properties / local.properties
├── README.md / ARCHITECTURE.md / TECHNICAL_FEASIBILITY.md
├── THIRD_PARTY_LICENSES.md / CHANGELOG.md / MODEL_URLS_VERIFIED.md
└── app/
    ├── build.gradle.kts · proguard-rules.pro
    ├── libs/sherpa-onnx-1.13.8.aar          ← 预编译 AAR（无需 NDK）
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/echonote/app/
        │   │   ├── EchoNoteApplication.kt      ← Application + 手动 DI
        │   │   ├── MainActivity.kt             ← enableEdgeToEdge + Theme + EchoNoteApp()
        │   │   ├── di/AppContainer.kt          ← 唯一依赖入口
        │   │   ├── ai/                         ← AI 层
        │   │   │   ├── ModelCatalog.kt         ← 模型清单（URL+字节+SHA256+许可）
        │   │   │   ├── ModelManager.kt         ← 下载/断点续传/校验/SAF 导入
        │   │   │   ├── VadSegmenter.kt         ← Silero VAD 封装（含 pop 顺序 bug 修复）
        │   │   │   ├── AsrEngine.kt            ← SenseVoice / Whisper 封装
        │   │   │   ├── SpeakerEmbeddingEngine.kt ← 声纹提取 + 标点
        │   │   │   ├── SpeakerClusterer.kt     ← 自研球形 k-means++（真实声纹校准）
        │   │   │   ├── EmbeddingUtils.kt       ← 余弦相似度 / 序列化
        │   │   │   └── TranscriptionManager.kt ← 转写编排 + 导入音频 + enqueue()
        │   │   ├── audio/                      ← 音频层
        │   │   │   ├── PcmAudioUtils.kt        ← RMS/峰值/削波/降混/PCM16 互转
        │   │   │   ├── Resampler.kt            ← sinc 重采样（抗混叠）
        │   │   │   ├── StreamingResampler.kt   ← 流式重采样（内存有界）
        │   │   │   ├── WavWriter.kt            ← 流式 WAV 写 + WavRepair 崩溃恢复
        │   │   │   ├── WavFileReader.kt        ← 手写 RIFF（8/16/24/32bit+float+EXTENSIBLE）
        │   │   │   ├── AudioFileDecoder.kt     ← 统一解码入口
        │   │   │   └── MicCaptureEngine.kt     ← AudioRecord 采集 + probe() 音源实测
        │   │   ├── data/
        │   │   │   ├── db/{Entities,Daos,EchoNoteDatabase}.kt  ← Room 三表
        │   │   │   ├── settings/SettingsRepository.kt          ← DataStore 偏好
        │   │   │   ├── settings/SelfVoiceprintStore.kt         ← 「我」的声纹（绝不导出）
        │   │   │   └── storage/RecordingStorage.kt             ← App 私有存储
        │   │   ├── domain/Models.kt            ← 领域模型
        │   │   ├── export/TranscriptExporter.kt ← TXT/MD/JSON/SRT/CSV（30 测试）
        │   │   ├── repository/RecordingRepository.kt ← 数据访问 + 搜索 + 删除
        │   │   ├── transcription/
        │   │   │   ├── TranscriptAssembler.kt  ← ASR×说话人对齐/合并/切分
        │   │   │   └── TranscriptionPipeline.kt ← 全流程编排（流式）
        │   │   ├── service/
        │   │   │   └── RecordingService.kt     ← 前台服务（通知常显/暂停/恢复）
        │   │   ├── navigation/EchoNoteNavigation.kt
        │   │   └── ui/ (theme / common / home / recording / history
        │   │            + search / settings / detail / models / feasibility)
        │   └── res/
        └── test/
            ├── java/com/echonote/app/ai/SpeakerClustererReferenceTest.kt   ← 19 测试
            ├── java/com/echonote/app/export/TranscriptExporterTest.kt     ← 30 测试
            └── resources/reference/*.emb + *.segments.tsv                  ← 真实声纹夹具
```

## 7. 存储与隐私边界

- 录音 WAV、模型、Room 数据库全部位于 **App 私有目录**（`RecordingStorage` 管理），无存储权限；导出走 SAF 由用户指定位置。
- `Speaker.embedding`（生物特征）只入 Room、只在本机参与聚类；`TranscriptExporter` 有测试断言保证任何格式导出都不含 embedding。
- 「我」的声纹（`SelfVoiceprintStore`）仅存本机，用于把聚类索引 0 判定为「我」——依据 enrolled 声纹而非猜测。
- 唯一的网络访问是模型下载（Models 页面用户显式触发）；转写管线不产生任何网络请求。
