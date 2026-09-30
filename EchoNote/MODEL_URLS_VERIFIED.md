# 模型下载清单（已验证）

> 本文对应 `app/src/main/java/com/echonote/app/ai/ModelCatalog.kt` 引用的**全部**模型文件。
> 验证方法：`curl -sSIL` 实测 **HTTP 200** + HuggingFace API blob 字节数；前 4 个文件的 SHA-256 在本地下载后用 `shasum -a 256` 实测并已写入 `ModelCatalog.kt`（下载时自动校验）。
> **验证日期：2026-09-30。**

## 1. 已含 SHA-256 校验的模型（核心，共 269 MB）

| # | 模型文件 | Bundle (ModelCatalog) | 下载 URL | 字节数 | SHA-256 | 权重许可 |
|---|---|---|---|---|---|---|
| 1 | `silero_vad.onnx` | `vad_silero` (VAD) | `https://huggingface.co/csukuangfj/vad/resolve/main/silero_vad.onnx` | 1,807,522 | `a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28` | MIT |
| 2 | `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` | `speaker_campplus_zh` (SPEAKER) | `https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` | 28,281,138 | `f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11` | Apache-2.0 |
| 3 | `model.int8.onnx` (SenseVoice-Small) | `asr_sense_voice_small` (ASR) | `https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx` | 239,233,841 | `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51` | FunASR MODEL_LICENSE v1.1（非 OSI，要求署名+保留模型名，无禁止商用条款） |
| 4 | `tokens.txt` (SenseVoice) | `asr_sense_voice_small` (ASR) | `https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt` | 315,894 | `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc` | 同上 |

## 2. Whisper 备选模型（仅有字节数校验，暂无 SHA-256）

| # | 模型文件 | Bundle (ModelCatalog) | 下载 URL | 字节数 | SHA-256 | 权重许可 |
|---|---|---|---|---|---|---|
| 5 | `small-encoder.int8.onnx` | `asr_whisper_small` (ASR) | `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx` | 112,442,483 | （未固定） | Apache-2.0 |
| 6 | `small-decoder.int8.onnx` | `asr_whisper_small` (ASR) | `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx` | 262,226,114 | （未固定） | Apache-2.0 |
| 7 | `small-tokens.txt` | `asr_whisper_small` (ASR) | `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt` | 816,730 | （未固定） | Apache-2.0 |
| 8 | `tiny-encoder.int8.onnx` | `asr_whisper_tiny` (ASR) | `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx` | 12,937,772 | （未固定） | Apache-2.0 |
| 9 | `tiny-decoder.int8.onnx` | `asr_whisper_tiny` (ASR) | `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx` | 89,855,401 | （未固定） | Apache-2.0 |
| 10 | `tiny-tokens.txt` | `asr_whisper_tiny` (ASR) | `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt` | 816,730 | （未固定） | Apache-2.0 |

Whisper 系列 URL 均实测 HTTP 200 且字节数与 HuggingFace blob 一致（2026-09-30）；SHA-256 待首次真机下载后补入 `ModelCatalog.kt`，此前 `ModelManager` 对这些文件做**字节数校验**。

## 3. 运行时（非模型，随 APK 分发）

| 组件 | URL | 字节数 | 许可 |
|---|---|---|---|
| `sherpa-onnx-1.13.8.aar`（已放 `app/libs/`，含 4 ABI 的 `libonnxruntime.so` / `libsherpa-onnx-{jni,c-api,cxx-api}.so`） | `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar` | 50,129,134 | Apache-2.0 |

## 4. 备注：为什么不用 GitHub Releases 的 `.tar.bz2` 模型包

sherpa-onnx 官方模型多以 `sherpa-onnx-*.tar.bz2` 打包发布（如 `asr-models` tag 下的 `...int8-2024-07-17.tar.bz2`）。**App 内不使用它们**，原因：

- **Android 平台没有内置 bz2 解码器**：`java.util.zip` 只支持 deflate/GZIP，`tar + bzip2` 双层包在 App 内需要自绑 libbz2 或引入第三方原生库才能解压——为一个下载步骤引入原生依赖不值得；
- `.tar.bz2` 是整包下载：只要校准一个 1 GB 的包（含 fp32 + int8 + 示例 wav），无法只取需要的单个 `.onnx`，浪费用户流量；
- HuggingFace 单文件直链可以逐文件下载、逐文件校验、支持断点续传，与 `ModelManager` 的校验/续传机制天然契合。

因此 `ModelCatalog.kt` 全部采用 HuggingFace（`csukuangfj/*` 转换仓库）单文件直链，URL 与字节数均已实测。

## 5. 相关许可说明

各模型的权重许可细节与完整许可文本 URL 见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。要点：SenseVoice 代码 MIT 但**权重是 FunASR MODEL_LICENSE v1.1**（自定义、非 OSI，要求署名 + 保留模型名，无禁止商用条款）；其余全部为 Apache-2.0 / MIT 标准许可。
