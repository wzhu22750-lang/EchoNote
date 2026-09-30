# EchoNote — 离线录音 · 说话人分离 · 转写导出

EchoNote 是一款 **纯离线** 的 Android 语音应用：录音（含微信通话场景的扬声器采集方案）、自动区分「我 / 对方」说话人、生成可编辑逐字稿，并导出为 TXT / Markdown / JSON / SRT / CSV。

所有 AI 推理（VAD、声纹提取、聚类、ASR）都在本机完成。**录音与逐字稿不上传任何服务器**；应用不要求 Root，不修改、不注入微信。

## 核心功能

### 三种录音模式

| 模式 | 说明 | 设计依据 |
|---|---|---|
| **A 普通录音** | 用麦克风录制会议、讲座、面对面谈话 | 标准能力，立即可用 |
| **B 微信通话（开扬声器）** | 微信语音/视频通话时打开扬声器，本 App 同时用麦克风采集，双方声音混入同一条单声道 | 普通第三方 App 无法直接获取微信通话内部音频，这是唯一可靠的非 Root 方案（详见 [TECHNICAL_FEASIBILITY.md](TECHNICAL_FEASIBILITY.md)） |
| **C 导入已有音频** | 通过 SAF 导入 WAV / MP3 / M4A / AAC，走同一条离线转写管线（`TranscriptionManager.importAudio()`） | 兜底能力：即使录音场景失败，AI 能力始终完整 |

模式 B 的扬声器-麦克风混录会带来回声与音量差异，属于已知质量风险；电话类单声道音频的说话人分离错误率天然偏高（pyannote 官方基准：CALLHOME 电话语音 DER 26.7%，约为 VoxConverse 11.2% 的 2.4 倍），因此 App 内置了逐字稿的人工修正 UI（编辑文本 / 说话人 / 时间戳、合并说话人）。

### 转写管线（全自动）

录音 WAV → VAD 切分（Silero）→ 每段声纹（CAM++，192 维）→ 球形 k-means++ 聚类 → SenseVoice ASR 识别 → 说话人与文字对齐合并 → Room 入库 → 多格式导出。全程离线，架构详见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 技术栈

- **语言 / UI**：Kotlin 2.0.21 + Jetpack Compose（BOM 2024.12.01，Material 3）
- **存储**：Room 2.6.1（`recordings` / `speakers` / `transcript_segments` 三表）+ DataStore（偏好）
- **音频 AI 基座**：[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) **v1.13.8**（官方预编译 AAR，`app/libs/sherpa-onnx-1.13.8.aar`，50,129,134 字节，含 arm64-v8a / armeabi-v7a 等 4 套 `.so`，无需 NDK）
  - VAD：Silero VAD（`silero_vad.onnx`，MIT）
  - 声纹：3D-Speaker CAM++ 中文模型（192 维，Apache-2.0）
  - ASR：SenseVoice-Small int8（主）/ Whisper tiny、small int8（备）
- **构建**：compileSdk 36 / minSdk 26 / targetSdk 35；AGP 8.10.1；Gradle 8.14.3；abiFilters 仅 arm64-v8a + armeabi-v7a

## Based on

**This project is based on [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** — the speech-processing toolkit for on-device speech recognition, speaker diarization, VAD and TTS — **licensed under the Apache License, Version 2.0** ([full license text](https://www.apache.org/licenses/LICENSE-2.0); [upstream LICENSE file](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE)).

本项目在其 Apache-2.0 授权下使用了 sherpa-onnx 的官方预编译 AAR（v1.13.8）及其 Kotlin/JNI API（`com.k2fsa.sherpa.onnx` 包：`OfflineRecognizer`、`Vad`、`SpeakerEmbeddingExtractor` 等），未修改上游代码。本项目其余部分（录音采集层、聚类算法、转写编排、数据层、UI）为原创实现；采集层不使用任何 sherpa-onnx 代码——上游 Android 示例中不存在任何通话/系统音频采集代码（全部示例仅使用 `MediaRecorder.AudioSource.MIC`）。

各第三方组件的完整许可清单与拒绝理由见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)；模型清单与校验值见 [MODEL_URLS_VERIFIED.md](MODEL_URLS_VERIFIED.md)。

## 模型下载说明

模型不打包进 APK（SenseVoice 主模型约 228 MB，超过 Google Play 单模块限制），由 **App 内 Models 页面按需自动下载**：

- 下载源为 Hugging Face 单文件直链（全部经 `curl` 实测 HTTP 200，验证日期 **2026-09-30**，见 [MODEL_URLS_VERIFIED.md](MODEL_URLS_VERIFIED.md)）；
- 每个文件下载完成后自动做 **字节数 + SHA-256 校验**（前 4 个核心模型已内置 SHA-256，Whisper 系列暂为字节数校验）；
- 支持断点续传；也可通过 SAF 从本地文件导入模型（`ModelManager.importFromUri()`）；
- 默认安装组合 = Silero VAD + CAM++ 声纹 + SenseVoice-Small，合计约 269 MB。

**刻意不使用** GitHub Releases 上的 `.tar.bz2` 模型包：Android 平台没有内置 bz2 解码器，App 内无法解压（详见 MODEL_URLS_VERIFIED.md 备注）。

## 构建方法

环境要求：**JDK 21**、**Android SDK（compileSdk 36）**、**Gradle 8.14.3**。

```bash
cd EchoNote
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 单元测试
```

> ⚠️ 当前仓库尚未生成 Gradle Wrapper（`gradle/wrapper/` 是空目录）。如 `./gradlew` 不存在，先执行一次 `gradle wrapper --gradle-version 8.14.3` 生成，或直接调用本机缓存的 Gradle 8.14.3 发行版（绝对路径见 HANDOFF.md §1）。构建全程**不需要 NDK / CMake**——sherpa-onnx 以预编译 AAR 形式提供；构建日志中出现 `Unable to strip ... libsherpa-onnx-*.so` 属正常现象（无 NDK 即无 strip 工具），不影响运行。

已验证的 AAR 兼容性：4 个 arm64 `.so` 的 PT_LOAD `p_align` 均为 0x4000（16 KB），Android 15+ 的 16 KB page size 设备可安全加载。

## 合规声明摘要

本项目遵循以下红线（完整条款见 HANDOFF.md §10）：

1. **不要求 Root / ADB / Shizuku** 作为任何默认方案；
2. **不 Hook、不修改、不注入微信**，不伪造 `CAPTURE_AUDIO_OUTPUT`、`VOICE_CALL` 等系统级权限，不使用 Accessibility Service 偷音频；
3. **录音期间系统通知始终可见**（`RecordingService` 以 `FOREGROUND_SERVICE_TYPE_MICROPHONE` 前台服务运行）；
4. **数据不出本机**：录音、逐字稿、声纹全部存于 App 私有目录，不默认上传任何服务器（云端 ASR 开关默认关闭）；
5. **声纹 embedding 是生物特征，绝不导出**：导出器有单元测试断言保证输出不含 embedding；
6. 不复制 GPL-3.0 / AGPL 代码，不使用无 LICENSE 或禁止商用的模型（详见 THIRD_PARTY_LICENSES.md）。

## 当前状态（如实声明，截至 2026-09-30）

- **核心引擎（音频、AI、数据库、导出）与全部 9 个界面均已完成**：`:app:compileDebugKotlin` 与 `:app:assembleDebug` 构建成功，产物 `app/build/outputs/apk/debug/app-debug.apk`（约 73.6 MB）包含 arm64-v8a / armeabi-v7a 各 4 个推理 `.so`（libonnxruntime + sherpa-onnx 三件套）。
- **单元测试**：原 49 个全部通过（聚类 19 + 导出 30，聚类算法用 sherpa-onnx 1.13.8 Python 参考实现在 4 段真实音频上生成的 192 维 CAM++ 声纹夹具校准）；DSP / 音频库与 Room DAO 测试套件为本轮新增。
- **本轮补齐的功能**：Search / Settings / Detail / Models / Feasibility 五个界面、导入音频（模式 C）UI 入口、「我的声纹」录入 UI、模型下载进度与许可展示、逐字稿播放联动编辑。
- **移除了三个从未实现也从未被调用的占位空壳**（`TranscriptionService`、`RecordingActionReceiver`、`MediaProjectionPermissionActivity`）及其 manifest 声明——声明的但不存在的功能比诚实的缺席更有害。
- **真机测试未进行**——开发环境没有连接任何 Android 设备（`adb devices` 为空）；**Android 端引擎从未在真机上运行过**（AI 管线是通过同版本 Python 参考实现验证的）。
- **微信双方音频尚未完成真实设备验证**：模式 B 的可行性结论基于 AOSP 源码与 16 个开源项目的代码级尽调，**不是**本机实测（详见 [TECHNICAL_FEASIBILITY.md](TECHNICAL_FEASIBILITY.md)）。App 内置的可行性页面会对本机音源做真实探测，供真机验证时使用。
