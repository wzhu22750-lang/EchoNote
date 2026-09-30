# EchoNote 第三方组件与许可清单

> 本文档如实列出项目使用的全部第三方组件、其代码/权重许可、官方许可文本 URL，以及**被拒绝的组件与拒绝理由**。
> 权重许可与代码许可经常不同——每一项都分开标注。验证日期：2026-09-30。

---

## 1. 已采用组件

### 1.1 k2-fsa/sherpa-onnx —— 音频 AI 运行时基座

| 项 | 内容 |
|---|---|
| 用途 | ONNX 推理运行时（自带 `libonnxruntime.so`）+ Kotlin/JNI API（`OfflineRecognizer`、`Vad`、`SpeakerEmbeddingExtractor` 等）；以官方预编译 AAR（v1.13.8，50,129,134 字节，4 套 ABI）引入 `app/libs/` |
| **代码许可** | **Apache-2.0** |
| 许可文本 | https://www.apache.org/licenses/LICENSE-2.0 · 上游 LICENSE 文件：https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE |
| 义务 | 保留版权与 NOTICE 声明（见本文档与 README 的 "Based on" 段落） |
| 说明 | 上游 Android 示例不含任何通话/系统音频采集代码（全部仅用 `MediaRecorder.AudioSource.MIC`），故本项目采集层自研，与其无代码耦合 |

### 1.2 Silero VAD —— 语音活动检测

| 项 | 内容 |
|---|---|
| 用途 | 长录音切分语音段（`silero_vad.onnx`，1,807,522 字节） |
| **模型权重许可** | **MIT**（上游 README 明示 "zero strings attached"；其徽章 alt-text 写 CC BY-NC 4.0 属过时标注，**以 LICENSE 文件为准**） |
| 许可文本 | https://github.com/snakers4/silero-vad/blob/master/LICENSE（MIT, © 2020-present Silero Team） |

### 1.3 3D-Speaker CAM++ —— 声纹提取（说话人分离核心）

| 项 | 内容 |
|---|---|
| 用途 | 每段语音 → 192 维声纹向量（`3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`，28,281,138 字节，中文 16 kHz） |
| **模型权重许可** | **Apache-2.0**（ModelScope `iic/speech_campplus_sv_zh-cn_16k-common` 的 `Data.License` 实测确认） |
| 许可文本 | https://www.apache.org/licenses/LICENSE-2.0 · 代码仓库：https://github.com/modelscope/3D-Speaker · ONNX 转换分发：https://huggingface.co/csukuangfj/speaker-embedding-models |
| 说明 | 3D-Speaker 全系列（CAM++ / ERes2Net / ERes2NetV2）权重统一 Apache-2.0，是本尽调中许可最干净的中 loudspeaker 声纹家族；备选 `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`（39,593,761 字节，同为 Apache-2.0）可作模式 B 的可选增强 |

### 1.4 SenseVoice-Small —— ASR 主模型 ⚠️ 许可拆分

| 项 | 内容 |
|---|---|
| 用途 | 中文/英/日/韩/粤语音识别（`model.int8.onnx` 239,233,841 字节 + `tokens.txt` 315,894 字节） |
| **代码许可** | **MIT**（仓库 `QwenAudio/SenseVoice`，原 `FunAudioLLM/SenseVoice`，301 重定向） |
| **权重许可** | ⚠️ **不是 MIT** —— HuggingFace 卡片 `license: other` / `license_name: model-license`，即 **FunASR MODEL_LICENSE v1.1**（阿里巴巴自定义许可）。sherpa-onnx 转换版自带的 71 字节 LICENSE 文件原文即 "Ref to https://github.com/modelscope/FunASR?tab=readme-ov-file#license" |
| 许可文本 | https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE · 模型页：https://huggingface.co/FunAudioLLM/SenseVoiceSmall |
| 条款要点 | （1）允许使用/复制/修改/分发，**无禁止商用条款**、无 copyleft、无领域限制；（2）**要求署名**（attribution）+ 注明来源 + **保留模型名称**；（3）禁止对模型的不实贬损（违反导致许可自动终止）；（4）**非 OSI 认证的自定义许可** |
| 本项目的履行 | 在本文件与 Models 页面展示 "SenseVoice-Small, FunAudioLLM / QwenAudio (Alibaba), FunASR MODEL_LICENSE v1.1" 署名，不修改、不隐去模型名 |

### 1.5 OpenAI Whisper（tiny / small int8）—— ASR 备选

| 项 | 内容 |
|---|---|
| 用途 | 备选识别模型（许可最干净；中文准确率低于 SenseVoice，体积更大） |
| **代码许可** | **MIT**：https://github.com/openai/whisper/blob/main/LICENSE |
| **权重许可** | **Apache-2.0**（HuggingFace `openai/whisper-tiny`、`openai/whisper-small` YAML `license: apache-2.0` 实测确认）：https://huggingface.co/openai/whisper-tiny · https://huggingface.co/openai/whisper-small |
| 说明 | 代码 MIT、权重 Apache-2.0 是**两套不同条款**（都宽松），勿混为一谈 |

### 1.6 AndroidX（Compose / Room / DataStore 等）

| 项 | 内容 |
|---|---|
| 组件 | Jetpack Compose（BOM 2024.12.01：ui/material3/foundation）、Room 2.6.1、DataStore、Navigation Compose、Activity Compose、Lifecycle 等 |
| **许可** | **Apache-2.0**（统一） |
| 许可文本 | https://android.googlesource.com/platform/frameworks/support/+/main/LICENSE.txt · https://www.apache.org/licenses/LICENSE-2.0 |

### 1.7 Kotlin 标准库与协程

| 项 | 内容 |
|---|---|
| 组件 | Kotlin stdlib 2.0.21、kotlinx-coroutines、kotlinx-serialization（如引用） |
| **许可** | **Apache-2.0** |
| 许可文本 | https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt（Apache-2.0）· https://www.apache.org/licenses/LICENSE-2.0 |

---

## 2. 为什么拒绝：许可裁决记录

### 2.1 GPL-3.0 项目 —— 与闭源/商业化诉求冲突

以下项目是各自领域**技术上最强**的参考实现，但全部 **GPL-3.0**（viral：链接/派生会强制整个分发应用开源），因此**一行代码都不复制**：

| 项目 | 许可 | 拒绝理由 |
|---|---|---|
| `kitsumed/ShizuCallRecorder`（1632★） | GPL-3.0 | shell 级通话录音的最干净参考（Shizuku→app_process→scrcpy-server→`VOICE_CALL`），GPL 传染与本项目闭源/商业化诉求直接冲突 |
| `madkongo/CallVault` | **GPL-3.0 + Section 7 附加条款** | 唯一实现 VoIP 远端 tap（`AudioPolicy`/`AudioMix` + `ROUTE_FLAG_LOOP_BACK_RENDER`）的公开代码，但许可处境最差：GPL-3.0 或更新版 + 可叠加额外条款，且是 ShizuCallRecorder 的 fork、内含 scrcpy 移植代码。**只读思路，不抄代码** |
| `LyoSU/cally` | GPL-3.0 | 8 层 bypass 文档是领域内最好的教学材料；文档思想可引用，源码不可复制 |
| `jagobandhusome/universal-android-call-recorder` | GPL-3.0 | 叠加三重否定：GPL-3.0 + README 自相矛盾（标题声称支持微信、正文承认不可行）+ 内含被平台规则证明无效的 `AudioPlaybackCapture(USAGE_VOICE_COMMUNICATION)` 死代码 |
| `23rd/Scrib` | GPL-3.0 | 同上传染风险 |
| `blabbertabber/blabbertabber` | AGPL-3.0 | 已弃置的负样本（作者自述"找不到足够好的 diarizer"），无复用价值 |

> 说明：**重新实现一个已公开描述的技术方案不构成侵权，复制源码才构成**。本项目在需要 privileged 技术时只从 Apache-2.0 上游（`Genymobile/scrcpy`、AOSP）取材，不从 GPL fork 取材。

### 2.2 非商用许可模型 —— 禁止商用即不可用

| 模型 | 许可 | 拒绝理由 |
|---|---|---|
| `sherpa-onnx-reverb-diarization-v1` | **"Rev Model Non-Production License"** | 它本是混响/扬声器-麦克风场景最强的分段模型（恰是模式 B 的痛点），但许可**明确禁止商业/生产使用**；同 tag 的 `pyannote-segmentation-3-0` 反而是 MIT（© 2022 CNRS）——本项目宁可不用 reverb，也不引入禁商用条款 |

### 2.3 无 LICENSE / 许可不明的仓库 —— 默认保留所有权利

**"No LICENSE file" 在版权法下等于 all rights reserved**，不等于"大概 MIT"。以下仓库不读、不抄、不引用代码：

- `timgras2/OpenWhisprAndroid`（无 LICENSE、无 README、依赖云 API）
- `myfreax/AudioRecorder`（无 LICENSE，2022 年弃更）
- `GenericJam/mob_audio_capture`（无 LICENSE，Elixir 插件；其 README 的平台事实**作为引文**引用，代码不用）
- `boldbeastsoft/CallRecordingFix`（无 LICENSE，闭源二进制的营销壳）
- `developertl-tl/cordova-plugin-callrecorder-accessibility`（无 LICENSE，单 commit）
- `qutschwalze/meeting-transcriber-sherpa`（README 自称 MIT 但**仓库无 LICENSE 文件**，GitHub 报 `license: None` → 按无许可处理）
- `Nomily-Ai/nomily-app`（`NOASSERTION`，且疑似刷量 fork 集群）
- `ten-vad`（Apache-2.0 **附加非竞争条款**："不得以与 Agora 业务竞争的方式部署" → 附加条件使它不是纯 Apache-2.0，不采用）

### 2.4 其他被否决的技术选型（许可或能力原因）

- **wespeaker 预训练模型**：权重**跟随数据集许可**（VoxCeleb = CC-BY-4.0，CN-Celeb = 数据集条款），归属义务含混，且对中文场景无超过 CAM++ 的收益 → 不用；
- **NVIDIA TitaNet**：English-only；`titanet_small` 的 NGC 许可页不可机读（UNVERIFIED）；且有"扬声器-麦克风录音会合并说话人"的记录 → 不用；
- **Moonshine legacy Mandarin 模型**：Moonshine Community License **非商用** → 不用；
- **pyannote HF 原始权重**：声明 MIT 但全部 `gated: auto`（匿名 HTTP 401），无法自动化再分发；sherpa-onnx 的 ONNX 转换自带同一 MIT 声明，是合法消费路径（本项目最终连分段模型也改为自研聚类方案，见 ARCHITECTURE.md §3）。

### 2.5 声纹 embedding 是生物特征 —— 绝不导出

`Speaker.embedding` / `SelfVoiceprintStore` 中的声纹属于**生物特征信息**：

- 只存本机 Room / 私有目录，仅用于聚类与「我/对方」判定；
- **任何导出格式（TXT/MD/JSON/SRT/CSV）都不含声纹向量**——`TranscriptExporter` 有单元测试断言强制保证；
- 删除录音时经外键 CASCADE / SET_NULL 一并清理。

---

## 3. 许可汇总表

| 组件 | 角色 | 代码许可 | 权重许可 | 商用 | 本文件署名义务 |
|---|---|---|---|---|---|
| sherpa-onnx 1.13.8 AAR | 推理运行时 | Apache-2.0 | n/a | ✅ | 保留 NOTICE |
| Silero VAD | VAD | MIT | MIT | ✅ | 保留版权声明 |
| 3D-Speaker CAM++ | 声纹 | Apache-2.0 | Apache-2.0 | ✅ | 保留署名 |
| SenseVoice-Small | ASR 主 | MIT | **FunASR MODEL_LICENSE v1.1（非 OSI）** | ✅（无禁商用条款） | **署名 + 保留模型名** |
| Whisper tiny/small | ASR 备 | MIT | Apache-2.0 | ✅ | 保留署名 |
| AndroidX | UI/存储 | Apache-2.0 | n/a | ✅ | 标准 NOTICE |
| Kotlin / Coroutines | 语言 | Apache-2.0 | n/a | ✅ | 标准 NOTICE |

> 本文档是工程尽调记录，**不构成法律意见**；正式商业发布前建议由法律顾问复核。
