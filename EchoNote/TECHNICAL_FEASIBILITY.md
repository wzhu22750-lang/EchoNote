# EchoNote 技术可行性报告：微信通话音频采集

> 验证方法：AOSP 源码逐行引用 + 16 个开源通话录音项目的代码级尽调（`git clone` + 读源码 + 读 LICENSE + 查 issue），三份尽调报告交叉验证。所有结论均给出处。
> **诚实前提：微信双方音频采集尚未完成真实设备验证（见文末声明）。**

---

## 1. 核心结论

> **普通第三方 App 无法直接获取微信通话双方的内部音频。唯一可靠的非 Root 方案是「微信开扬声器 + 麦克风采集」。**

这不是设计妥协，而是 Android 平台的硬性结构限制，由以下证据链支撑。

## 2. 证据链

### 2.1 `CAPTURE_AUDIO_OUTPUT` 权限级别 = `signature|privileged|role`

AOSP `core/res/AndroidManifest.xml:6507-6508`，注释原文："Not for use by third-party applications."。只有平台签名应用、特权分区预装应用或持有特定 role（如默认拨号器）的应用能拿到，侧载 APK 拿不到。

### 2.2 `VOICE_CALL` / `VOICE_UPLINK` / `VOICE_DOWNLINK` 全部保留给系统

AOSP `MediaRecorder.java:292-320`，三个音源的 javadoc 原文一致：

> *"Capturing from `VOICE_CALL` source requires the `CAPTURE_AUDIO_OUTPUT` permission. **This permission is reserved for use by system components and is not available to third-party applications.**"*

即：能录到「双方内部音频」的音源，普通 App 一个都用不了。`VOICE_COMMUNICATION` 是 VoIP 应用自己持有的上行音源，第二家录音器去开它只会被通话期策略置零（CallVault 真机实测确认）。

### 2.3 `AudioPlaybackCapture` 从结构上排除通话音频

AOSP `AudioPlaybackCaptureConfiguration.java:38-40` 类 javadoc 原文：

> *"the usage value MUST be `USAGE_UNKNOWN` or `USAGE_GAME` or `USAGE_MEDIA`. **All other usages CAN NOT be captured.**"*

微信通话播放远端声音的轨道是 `USAGE_VOICE_COMMUNICATION` → **永远捕获不到**，无论用户怎么授权 MediaProjection。`attrs_manifest.xml` 的 `allowAudioPlaybackCapture` 文档再次确认："All other usages like `USAGE_VOICE_COMMUNICATION` will not be captured."。仓库 `jagobandhusome/universal-android-call-recorder` 里就有一段 `addMatchingUsage(USAGE_VOICE_COMMUNICATION)` 的死代码，是这条陷阱的现成反面教材。

### 2.4 `ACCESS_CALL_AUDIO` 从未发布

Android 11 预览版曾为默认拨号器引入 `ACCESS_CALL_AUDIO`，随后被 revert：

> commit `57a3769fb12dc8b2df457e0919850bd976716706` — *"Revert 'Allow call audio access for default dialer application'"* — *"Reason for revert: Feature has been postponed"*
> https://android.googlesource.com/platform/frameworks/av/+/57a3769fb12dc8b2df457e0919850bd976716706

已确认当前 AOSP master 的 `core/res/AndroidManifest.xml` 中 grep 不到 `ACCESS_CALL_AUDIO`。Android 10–16 任何版本都没有给第三方 App 增加过 VoIP 录音 API（Android 14 之后 MediaProjection 反而更严：一次性 token + 可随时撤销 + 强制 `FOREGROUND_SERVICE_MEDIA_PROJECTION`）。

### 2.5 16 个开源项目无一验证过微信录音

全量 `grep -rniE "wechat|微信|weixin"` + 对 4 个活跃项目的 GitHub issue 检索，结果台账：

| 项目 | 证据 | 定性 |
|---|---|---|
| `boldbeastsoft/CallRecordingFix` | README 声称支持微信，但音频路径在**闭源付费 App** 里，仓库只有 Magisk 胶水代码，且 2021 年起弃更 | 无依据的营销声明 |
| `jagobandhusome/universal-android-call-recorder…whatsapp` | 同一份 README 自相矛盾：标题声称支持微信，正文承认 *"Android does not give third-party apps a silent tap of WhatsApp / Telegram / similar VoIP audio."* | 声称可行 / 文档承认不可行 |
| `kitsumed/ShizuCallRecorder` | issue #108 原文 *"Currently, none of the chat applications have a built-in call recorder."*（closed，0 评论） | 文档明确不支持 |
| `madkongo/CallVault` / `LyoSU/cally` / `jemcik/JemRec` | `search/issues?q=repo:X+wechat` → `total_count: 0`，从未提及 | 从未验证 |

### 2.6 能真正拿到双方音频的方案全部要 shell 级权限，且全部 GPL-3.0

| 项目 | 技术 | 许可 |
|---|---|---|
| ShizuCallRecorder | Shizuku → `app_process` → scrcpy-server → shell UID 2000 的 `AudioRecord(VOICE_CALL)` | GPL-3.0 |
| CallVault | 自带 ADB 或 Shizuku；VoIP 远端用动态 `AudioPolicy`+`AudioMix(USAGE_VOICE_COMMUNICATION, ROUTE_FLAG_LOOP_BACK_RENDER)`，且**策略必须在通话音频轨道创建之前注册**；vivo/iQOO 被其自己的文档标记为永久不可行 | GPL-3.0 + Section 7 附加条款 |
| cally / opencall-recorder | Shizuku UserService（UID 2000）或 loopback 自配 ADB | cally GPL-3.0 |

这些路径全部需要 Shell 级权限（`com.android.shell` 持有 `CAPTURE_AUDIO_OUTPUT` 等 551 项权限），并且存在硬性失败模式：若目标 App 设置 `ALLOW_CAPTURE_BY_NONE`，捕获结果就是**完美数字静音，无法绕过**（CallVault 源码注释原文）。**微信是否设置该策略尚未实测（UNVERIFIED，需真机 `dumpsys media.audio_flinger` 测量）。**

此外它们全部 GPL-3.0（viral），与本项目可能闭源/商业化的诉求冲突 → **不予采用，也不复制其代码**（裁决详见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)）。

## 3. App 内三种模式的落地

| 模式 | 实现 | 可行性 |
|---|---|---|
| **A 普通录音** | `MicCaptureEngine`（`AudioRecord`）+ `RecordingService` 前台服务 | ✅ 立即可用 |
| **B 微信通话 + 扬声器** | 用户在微信里点「扬声器」，本 App 用 `AudioRecord` 麦克风同时采集，双方声音混入单声道 | ✅ 唯一可靠的非 Root 方案；回声/音量差异是主要质量风险，说话人分离依赖人工修正 UI 兜底 |
| **C 导入已有音频** | SAF 导入 WAV/MP3/M4A/AAC → 与 A/B 同一条离线管线（`TranscriptionManager.importAudio(uri)`） | ✅ 兜底方案，AI 能力始终完整 |

关键设计原则：**能拿到的录音源（麦克风）如实列为可选项；拿不到的音源（`VOICE_CALL` 等）绝不伪装成「可选项」**，只在文档与探测报告里说明为什么不可用——把不可用的列成选项是不诚实的。

## 4. FeasibilityScreen 的真实探测机制

`FeasibilityScreen` 不只展示文字结论，而是**运行真实探测**并把本机实测结果如实呈现：

- `MicCaptureEngine.probeSources` = `[MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION, CAMCORDER, DEFAULT]` —— 普通合法可申请的音源清单（`VOICE_CALL` 等系统音源被刻意排除）。
- `MicCaptureEngine.probe(source, durationMs)` 对每个音源真实启动 `AudioRecord` 采样并判定，返回 `CaptureProbe`：

```kotlin
CaptureProbe(requested, actualSource, started, errorMessage, sampleRate, channelCount,
             echoCancelerApplied, noiseSuppressorApplied, initialDbFs, appearedSilent, verdict)
CaptureProbe.Verdict ∈ { WORKS, BLOCKED, UNAVAILABLE }
// WORKS       = 启动且有真实信号
// BLOCKED     = 启动了但全是数字静音 → 该音源在本机被封
// UNAVAILABLE = 根本无法启动
fun summary(): String   // 已实现的中文单行摘要
```

探测页面同时展示 `AppContainer.nativeRuntimeInfo()` 读出的真实 sherpa-onnx / onnxruntime 版本（顺带验证 `.so` 能否加载）与合规红线。有了真机后，这个页面就是「微信双方音频是否可用」的第一手测量工具。

## 5. 性能基线（实测数据）

### 5.1 参考实现流水线（sherpa-onnx 1.13.8 Python，与 AAR 同版本）

| 音频 | 时长 | VAD 段数 | 声纹 | RTF |
|---|---|---|---|---|
| `lei-jun.wav`（中文独白） | 272.4 s | 61 | 61 成功 | 0.088 |
| `two-speakers-en.wav` | 16.0 s | 4 | 4 成功 | 0.054 |
| `four-speakers-zh.wav` | 56.9 s | 7 | 7 成功 | 0.028 |
| `two-speakers-en-2.wav` | 34.0 s | 4 | 4 成功 | 0.041 |

SenseVoice 真实输出示例：`[28.93-36.00] 朋友们，晚上好，欢迎大家来参加今天晚上的活动，谢谢大家。` —— VAD 切分、声纹提取、识别、标点/ITN 全链路正常。

### 5.2 手机端预期（VoicePing 基准，Samsung Galaxy S10）

| 模型 | 引擎 | 30 s 推理 | RTF |
|---|---|---|---|
| SenseVoice-Small | sherpa-onnx | 1,725 ms | **0.06** |
| Whisper-tiny | sherpa-onnx | 2,068 ms | 0.07 |
| Whisper-small | sherpa-onnx | 12,329 ms | 0.41 |
| Whisper-tiny | **whisper.cpp** | 105,596 ms | **3.52**（51× 慢，弃用理由） |

整体管线（VAD + 声纹 + ASR + 聚类）在手机端预期 RTF 0.2–0.3，一小时录音约 12–18 分钟完成转写。

### 5.3 说话人分离的准确率预期（如实告知）

- pyannote 官方基准：CALLHOME（电话语音）DER **26.7%** vs VoxConverse（播客/广播）**11.2%** —— 电话场景错误率约 2.4×；
- 模式 B 的扬声器-麦克风混录是远场+回声场景，比干净电话更难（引文证据：`eres2net_base` 在扬声器-麦克风录音上能分开 3-4 人而 TitaNet-small 会合并）；
- 结论：**分离结果必须依赖人工修正 UI**，App 不承诺自动标签全对。这也是为什么自研聚类用真实声纹校准阈值（真双人轮廓系数 0.72–0.89，独白 0.37，可干净区分）。

## 6. 诚实声明（必须原样保留）

| 项 | 状态 |
|---|---|
| **微信双方音频** | **尚未完成真实设备验证。** 结论基于 AOSP 源码 + 16 个开源项目尽调，**不是**本机实测 |
| **真机测试** | **未进行** —— 没有连接任何 Android 设备（`adb devices` 为空） |
| **Android 端引擎** | **未在真机运行过** —— AI 管线（VAD/声纹/ASR）是用同版本 Python 参考实现验证的；单元测试（49/49）在 JVM 上通过 |

模式 B 在真实设备上仍有两个待测量项：(1) 微信是否设置 `ALLOW_CAPTURE_BY_NONE`（若是，麦克风采集路径不受影响，因为它录的是扬声器外放而非内部轨道）；(2) 各厂商 ROM 对并发录音与 `AudioRecord` 音源的实际限制。`FeasibilityScreen` 的探测机制就是为回答这些问题准备的。

---
*关联文档：[ARCHITECTURE.md](ARCHITECTURE.md) · [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) · [MODEL_URLS_VERIFIED.md](MODEL_URLS_VERIFIED.md) · 尽调报告：`android-callrec-oss-due-diligence.md`、`android-offline-asr-due-diligence.md`、`ANDROID_OFFLINE_DIARIZATION_DUE_DILIGENCE.md`*
