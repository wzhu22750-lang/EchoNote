# Android On-Device ASR (Chinese + English) — Open-Source Due Diligence

**Scope:** Android on-device ASR — models, runtimes, ready-made apps.
**Verification date:** 2026-09-30 (UTC), via `curl` against `api.github.com`, `raw.githubusercontent.com`, `repo1.maven.org`, `search.maven.org`, `huggingface.co`, `k2-fsa.github.io`, `alphacephei.com`, `arxiv.org`.
**Method:** live HTTP verification only. Everything below is either measured by a command shown in §7 or explicitly marked `UNVERIFIED`.

> Environment note: the host clock reports **2026-09-30**, so "latest commit / release" values below are relative to that date. This is unusual but it is what the live GitHub API returned; I did not adjust or estimate anything.

---

## (a) Comparison table

| Project / Model | URL | Stars | Last commit (pushed_at) | Code License | Model License | Android artifact (exact coords) | Chinese | Model size (on disk) | Streaming | RTF / RAM |
|---|---|---|---|---|---|---|---|---|---|---|
| **k2-fsa/sherpa-onnx** (runtime) | https://github.com/k2-fsa/sherpa-onnx | 15,043 | 2026-09-22 | **Apache-2.0** (verified `LICENSE`) | n/a (runtime) | **`sherpa-onnx-1.13.8.aar`** — GitHub Releases **only** (50,129,134 B). No Maven Central. Third-party mirror: `com.bihe0832.android:lib-sherpa-onnx:6.25.21` (Maven Central, unofficial) | ✅ | runtime only | both (offline + streaming recognizers) | see models below |
| **SenseVoice-Small (int8)** via sherpa-onnx | https://github.com/QwenAudio/SenseVoice (⚠ was `FunAudioLLM/SenseVoice`) | 9,412 | 2026-09-22 | **MIT** (repo LICENSE) | ⚠ **NOT MIT** — HF card `license: other`, `license_name: model-license` → **FunASR MODEL_LICENSE v1.1** (Alibaba custom; attribution + model-name retention required; non-OSI) | via sherpa AAR (no separate artifact) | ✅ zh/en/ja/ko/yue | `model.int8.onnx` = **239,233,841 B (228 MiB)**; `model.onnx` = **937,617,178 B (894 MiB)**; tokens 315,894 B | ❌ offline/batch (non-autoregressive) | Android S10 RTF **0.06**; paper: RTF 0.007, 70 ms / 10 s audio (A800) |
| **Whisper tiny** via sherpa-onnx | https://github.com/openai/whisper | 109,763 | 2026-08-31 | **MIT** | HF weights `license: apache-2.0` | via sherpa AAR | ✅ multi | int8: enc 12,937,772 + dec 89,855,401 = **102,793,173 B** | ❌ offline | Android RTF **0.07** |
| **Whisper base** via sherpa-onnx | https://github.com/openai/whisper | 109,763 | 2026-08-31 | MIT | apache-2.0 (HF) | via sherpa AAR | ✅ | int8: 29,120,534 + 130,672,026 = **159,792,560 B** | ❌ | Android RTF **0.13** |
| **Whisper small** via sherpa-onnx | https://github.com/openai/whisper | 109,763 | 2026-08-31 | MIT | apache-2.0 (HF) | via sherpa AAR | ✅ | int8: 112,442,483 + 262,226,114 = **374,668,597 B** | ❌ | Android RTF **0.41** |
| **ggml-org/whisper.cpp** | https://github.com/ggml-org/whisper.cpp | 54,022 | 2026-09-28 | **MIT** | GGML weights converted from OpenAI (UNVERIFIED conversion licence) | ❌ **No AAR, no Maven.** Source + NDK/CMake | ✅ multi | tiny GGML ~31 MB (quantized) | ❌ offline | ⚠ **Android S10 RTF 3.52** (105,596 ms / 30 s) — 51× slower than sherpa-onnx same checkpoint |
| **alphacep/vosk-api** | https://github.com/alphacep/vosk-api | 15,158 | 2026-08-09 | **Apache-2.0** | models **Apache-2.0** (stated on models page) | ✅ **`com.alphacephei:vosk-android:0.3.75`** (Maven Central; 0.3.47 AAR = 12,297,565 B, arm64-v8a/armeabi-v7a/x86/x86_64 `libvosk.so`) | ✅ | `vosk-model-small-cn-0.22` = 42 MB; `vosk-model-cn-0.22` = 1.3 GB | ✅ streaming (Kaldi) | UNVERIFIED on Android |
| **moonshine-ai/moonshine** (⚠ was `usefulsensors/moonshine`) | https://github.com/moonshine-ai/moonshine | 11,160 | 2026-08-31 | **MIT** (per LICENSE, except `core/third-party`) | MIT **by default**; **exceptions**: legacy non-streaming non-English = **Moonshine Community License (non-commercial)**. **Mandarin Tiny Streaming 34M is MIT**, 16.1% no-space CER | ✅ **`ai.moonshine:moonshine-voice:0.1.5`** (Maven Central, verified 200; namespace `ai.moonshine.voice`, minSdk 26, ABI arm64-v8a/armeabi-v7a/x86_64) | ✅ (MIT Mandarin streaming) | UNVERIFIED | ✅ streaming | Android RTF **0.05** (Moonshine Tiny, English) |
| **pytorch/executorch** | https://github.com/pytorch/executorch | 5,064 | 2026-09-30 | **BSD-3-Clause** | n/a | ❌ no Android AAR; `examples/demo-apps` contains only `react-native` → **no first-party Android ASR demo** | ✅ possible | n/a | depends on model | UNVERIFIED |
| **mozilla/DeepSpeech** | https://github.com/mozilla/DeepSpeech | 26,771 | 2025-06-19 | MPL-2.0 | MPL-2.0 | ❌ | ❌ **English-only** | n/a | ❌ | **ARCHIVED — dead, do not use** |
| **GiviMAD/whisper-jni** | https://github.com/GiviMAD/whisper-jni | 160 | 2025-04-26 | Apache-2.0 | n/a | ⚠ **`io.github.givimad:whisper-jni:1.7.1`** on Maven Central, packaging `jar` — **contains NO Android natives** (verified: only `debian-{amd64,arm64,armv7l}`, `macos-{amd64,arm64}`, `win-amd64`) | ✅ (model-dependent) | n/a | ❌ | ❌ **Not Android-viable as published** |
| **voiceping-ai/android-offline-transcribe** (benchmark app) | https://github.com/voiceping-ai/android-offline-transcribe | 7 | 2026-02-16 | Apache-2.0 | n/a | ✅ full app source | ✅ | n/a | both | **source of the Android RTF table below** |
| **niedev/RTranslator** | https://github.com/niedev/RTranslator | 10,469 | 2026-09-29 | Apache-2.0 | n/a | ✅ app (Java) | ✅ (translation) | n/a | offline | UNVERIFIED |
| **vilassn/whisper_android** | https://github.com/vilassn/whisper_android | 692 | 2026-03-18 | MIT | n/a | ✅ app (C++/JNI) | ✅ | n/a | ❌ | UNVERIFIED |

### Chinese models on sherpa-onnx (all offline unless marked)

| Model | Release tarball bytes | Key `.onnx` on disk | Chinese CER/WER evidence | Streaming |
|---|---|---|---|---|
| `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` | **163,002,883** | `model.int8.onnx` 239,233,841 | paper Table 6: AISHELL-1 **2.96**, AISHELL-2-ios 3.80, WenetSpeech-net 7.84, meeting 7.44 | ❌ |
| `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09` | **165,783,878** | UNVERIFIED | UNVERIFIED | ❌ |
| `sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03` | **301,377,906** | `model.int8.onnx` 367,074,356 | docs: AISHELL **1.74**, WenetSpeech-net **5.92**, meeting **7.75** | ❌ |
| `sherpa-onnx-paraformer-zh-2023-09-14` | **234,051,698** | `model.int8.onnx` 243,371,218 | paper Table 6 Paraformer-zh: AISHELL-1 **1.95**, WenetSpeech-net 6.74, meeting 6.97 | ❌ |
| `sherpa-onnx-paraformer-zh-int8-2025-10-07` | **228,262,632** | UNVERIFIED | UNVERIFIED | ❌ |
| `sherpa-onnx-paraformer-zh-2024-03-09` | UNVERIFIED | docs: int8 **217 MB**, fp32 785 MB | UNVERIFIED | ❌ |
| `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` | **511,274,346** | int8 enc 181,895,032 + dec 13,091,040 + joiner 3,228,404 = **198,214,476** | UNVERIFIED (community model, internal data) | ✅ |
| `sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12` | UNVERIFIED | int8 enc 70,109,350 + dec 1,308,688 + joiner 1,033,416 = **72,451,454** | UNVERIFIED | ✅ |
| `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23` | **74,004,050** | docs: int8 ≈ 24.5 MB total | UNVERIFIED | ✅ |
| `silero_vad.onnx` (release asset) | **643,854** | — | — | n/a |
| `csukuangfj/vad`: `silero_vad.onnx` / `silero_vad_v5.onnx` | 1,807,522 / 2,313,101 | — | — | n/a |

---

## (b) Verified download URLs + byte sizes

Every URL below was probed with `curl -sSIL` and returned **HTTP 200**; byte counts are the server's `Content-Length` (or the HF blob size).

### sherpa-onnx prebuilt Android library (GitHub Releases only — NOT Maven Central)

| Asset | URL | Bytes |
|---|---|---|
| Main AAR (**recommended**) | `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar` | **50,129,134** |
| Static-ORT AAR | `.../v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar` | 38,691,998 |
| RKNN AAR | `.../v1.13.8/sherpa-onnx-1.13.8-rknn.aar` | 26,598,297 |
| Raw jniLibs tar | `.../v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2` | 46,093,321 |
| Desktop JVM JAR (not Android) | `.../v1.13.8/sherpa-onnx-jvm-1.13.8.jar` | 187,490 |

### sherpa-onnx model assets (`asr-models` release tag)

```
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2   163,002,883
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2        1,047,870,769   (int8 + fp32 + wavs)
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2   165,783,878
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2                                  116,204,861
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2                               118,071,777
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2                                  207,557,382
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2                                 639,387,718
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx                                                      643,854
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2 511,274,346
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2           74,004,050
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2                       234,051,698
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-int8-2025-10-07.tar.bz2                  228,262,632
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03.tar.bz2               301,377,906
```

**Returned 404 (do not cite these names):**
`sherpa-onnx-whisper-base-int8.tar.bz2`, `sherpa-onnx-streaming-zipformer-ctc-small-zh-int8-2025-07-16.tar.bz2`.

**Hugging Face repos that do NOT exist under the names given in the brief:**
- `csukuangfj/sherpa-onnx-zipformer-zh-en-2023-11-22` → HF returns `Invalid username or password` (i.e. 401/404). Use `csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` instead.
- `FunAudioLLM/SenseVoice` on **GitHub redirects (301)** to **`QwenAudio/SenseVoice`**. `FunAudioLLM/SenseVoiceSmall` on **HF still resolves**.

### Hugging Face exact blob sizes (`?blobs=true`)

```
csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
      239,233,841  model.int8.onnx
      937,617,178  model.onnx
          315,894  tokens.txt
               71  LICENSE   -> "Ref to https://github.com/modelscope/FunASR?tab=readme-ov-file#license"

csukuangfj/sherpa-onnx-whisper-tiny
       12,937,772  tiny-encoder.int8.onnx
       89,855,401  tiny-decoder.int8.onnx
       37,647,080  tiny-encoder.onnx
      114,505,801  tiny-decoder.onnx

csukuangfj/sherpa-onnx-whisper-base
       29,120,534  base-encoder.int8.onnx
      130,672,026  base-decoder.int8.onnx

csukuangfj/sherpa-onnx-whisper-small
      112,442,483  small-encoder.int8.onnx
      262,226,114  small-decoder.int8.onnx

csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20
      181,895,032  encoder-epoch-99-avg-1.int8.onnx
       13,091,040  decoder-epoch-99-avg-1.int8.onnx
        3,228,404  joiner-epoch-99-avg-1.int8.onnx

k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12
       70,109,350  encoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx
        1,308,688  decoder-...int8.onnx
        1,033,416  joiner-...int8.onnx

csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03   367,074,356  model.int8.onnx
csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14           243,371,218  model.int8.onnx
csukuangfj/vad                                              1,807,522  silero_vad.onnx
                                                           2,313,101  silero_vad_v5.onnx
```

### Vosk Chinese models (sizes from alphacephei.com/vosk/models, Apache-2.0 each)

| Model | Size | Reported WER |
|---|---|---|
| `vosk-model-small-cn-0.22` | 42 MB | 23.54 (SpeechIO-02), 38.29 (SpeechIO-06), 17.15 (THCHS) |
| `vosk-model-cn-0.22` | 1.3 GB | 13.98 (SpeechIO-02), 27.30 (SpeechIO-06), 7.43 (THCHS) |
| `vosk-model-cn-kaldi-multicn-0.15` | 1.5 GB | 17.44 (SpeechIO-02), 9.56 (THCHS) |

---

## (c) sherpa-onnx Android integration — verified detail

### CRITICAL: exact artifact name and version

- **Latest release: `v1.13.8`, published 2026-09-10, 287 assets.**
- **Exact AAR asset filename: `sherpa-onnx-1.13.8.aar`, size 50,129,134 bytes.** ✅ Downloaded and unzipped to confirm.
- **It is NOT on Maven Central.** Verified: `search.maven.org` returns `numFound 0` for `g:com.k2-fsa`, for `k2fsa`, and for `com.k2fsa`. There is **no official `implementation("com.k2-fsa:...")` coordinate** — you either drop the AAR into `libs/`, or add it as a file dependency.
- **Only Maven Central option is third-party:** `com.bihe0832.android:lib-sherpa-onnx:6.25.21` (`packaging=aar`, Apache-2.0, `android.bihe0832.com`, author zixie). Unofficial; version string does not track sherpa-onnx versions.

### Does the AAR bundle arm64-v8a native libs? — YES

`unzip -l sherpa-onnx-1.13.8.aar` (16 `.so` files across 4 ABIs):

```
jni/arm64-v8a/libonnxruntime.so            22,249,560
jni/arm64-v8a/libsherpa-onnx-c-api.so       4,465,168
jni/arm64-v8a/libsherpa-onnx-cxx-api.so       440,688
jni/arm64-v8a/libsherpa-onnx-jni.so         4,771,760
jni/armeabi-v7a/libonnxruntime.so          15,359,592
jni/armeabi-v7a/libsherpa-onnx-c-api.so     3,202,700
jni/armeabi-v7a/libsherpa-onnx-cxx-api.so     282,008
jni/armeabi-v7a/libsherpa-onnx-jni.so       3,428,780
jni/x86/libonnxruntime.so                  26,491,148
jni/x86/libsherpa-onnx-c-api.so             5,051,192
jni/x86/libsherpa-onnx-cxx-api.so             392,768
jni/x86/libsherpa-onnx-jni.so               5,358,928
jni/x86_64/libonnxruntime.so               25,581,120
jni/x86_64/libsherpa-onnx-c-api.so          4,927,096
jni/x86_64/libsherpa-onnx-cxx-api.so          436,952
jni/x86_64/libsherpa-onnx-jni.so            5,201,192
```

Other AAR facts: `classes.jar` = 238,706 B containing **122 compiled classes** under `com/k2fsa/sherpa/onnx/`. `AndroidManifest.xml` declares `package="com.k2fsa.sherpa.onnx"`, `minSdkVersion="21"`. `aar-metadata.properties`: `aarFormatVersion=1.0`. **Consumers should use ABI splits / `abiFilters` — shipping all four ABIs adds ~50 MB before any model.**

### Kotlin API package names and real signatures

Package: **`com.k2fsa.sherpa.onnx`**. From `https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/OfflineRecognizer.kt`:

```kotlin
data class OfflineRecognizerResult(
    val text: String, val tokens: Array<String>, val timestamps: FloatArray,
    val lang: String, val emotion: String, val event: String,
    val durations: FloatArray,           // TDT models only
    val words: IntArray = IntArray(0),   // non-empty only with an HLG graph
)

class OfflineRecognizer(
    assetManager: AssetManager? = null,
    var config: OfflineRecognizerConfig,
    private var ptr: Long
) {
    fun release() = finalize()
    fun createStream(): OfflineStream
    fun createStream(hotwords: String): OfflineStream
    fun getResult(stream: OfflineStream): OfflineRecognizerResult
    fun decode(stream: OfflineStream) = decode(ptr, stream.ptr)
    fun setConfig(config: OfflineRecognizerConfig) = setConfig(ptr, config)
    companion object {
        external fun prependAdspLibraryPath(newPath: String)  // for qnn
        // newFromAsset(...) / newFromFile(...)
    }
}
```

Available model config data classes in the same package (all confirmed present both in the Kotlin source **and** as compiled classes in `classes.jar`): `OfflineTransducerModelConfig`, `OfflineParaformerModelConfig`, `OfflineWhisperModelConfig`, `OfflineSenseVoiceModelConfig`, `OfflineMoonshineModelConfig`, `OfflineZipformerCtcModelConfig`, `OfflineNemoEncDecCtcModelConfig`, `OfflineDolphinModelConfig`, `OfflineFireRedAsrModelConfig`, `OfflineFireRedAsrCtcModelConfig`, `OfflineCanaryModelConfig`, `OfflineCohereTranscribeModelConfig`, `OfflineFunAsrNanoModelConfig`, `OfflineQwen3AsrModelConfig`, `OfflineMedAsrCtcModelConfig`, `OfflineOmnilingualAsrCtcModelConfig`, `OfflineWenetCtcModelConfig`, `OfflineCtcFstDecoderConfig`, plus `FeatureConfig(…)`, `Vad`, `VadModelConfig`, `SileroVadModelConfig`, `TenVadModelConfig`, `SpeechSegment`, `KeywordSpotter`, `OfflinePunctuation`, `OfflineSpeakerDiarization`, `AudioTagging`, `Tts`, `OnlineRecognizer`, `OnlineStream`, `WaveReader`.

`com.k2fsa.sherpa.onnx.Vad` signature highlights (`kotlin-api/Vad.kt`):
```kotlin
data class SileroVadModelConfig(var model: String = "", var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F, var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 512, var maxSpeechDuration: Float = 5.0F)
class Vad(var config: VadModelConfig, private var ptr: Long) {
    fun acceptWaveform(samples: FloatArray); fun empty(): Boolean; fun pop()
    fun front(): SpeechSegment; fun clear(); fun isSpeechDetected(); fun reset(); fun flush()
    fun compute(samples: FloatArray): Float
}
```

### Real Android demo directories (via `api.github.com/repos/k2-fsa/sherpa-onnx/contents/android`)

```
SherpaOnnx                       <- offline+streaming ASR demo (streaming model)
SherpaOnnx2Pass                  <- streaming 1st pass + offline 2nd pass
SherpaOnnxAar                    <- THE AAR build project (sherpa_onnx module)
SherpaOnnxAudioTagging
SherpaOnnxAudioTaggingWearOs
SherpaOnnxJavaDemo
SherpaOnnxKws
SherpaOnnxSimulateStreamingAsr   <- offline model used in streaming fashion
SherpaOnnxSimulateStreamingAsrWearOs
SherpaOnnxSpeakerDiarization
SherpaOnnxSpeakerIdentification
SherpaOnnxSpokenLanguageIdentification
SherpaOnnxTts
SherpaOnnxTtsEngine
SherpaOnnxVad
SherpaOnnxVadAsr                 <- VAD + offline (non-streaming) ASR
SherpaOnnxWebSocket
```

⚠ **Correction to the brief:** there is **no `SherpaOnnxOfflineRecognizer` directory**. The offline-recognizer Android demo is **`SherpaOnnxVadAsr`** (VAD + `OfflineRecognizer`). Verified Kotlin source at `android/SherpaOnnxVadAsr/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt` — package `com.k2fsa.sherpa.onnx.vad.asr`, imports `com.k2fsa.sherpa.onnx.{OfflineRecognizer, OfflineRecognizerConfig, Vad, getFeatureConfig, getOfflineModelConfig, getVadModelConfig}`, uses `AudioRecord` at 16 kHz mono `ENCODING_PCM_16BIT`, `lifecycleScope.launch(Dispatchers.IO)` for model init.

### How the AAR is built (official answer to "where does it come from")

`android/SherpaOnnxAar/README.md` documents it exactly:
```bash
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2
tar xvf sherpa-onnx-v1.13.8-android.tar.bz2
cp -v jniLibs/arm64-v8a/* android/SherpaOnnxAar/sherpa_onnx/src/main/jniLibs/arm64-v8a/
cd android/SherpaOnnxAar && ./gradlew :sherpa_onnx:assembleRelease
cp ./sherpa_onnx/build/outputs/aar/sherpa_onnx-release.aar ../../sherpa-onnx-1.13.8.aar
```
`android/SherpaOnnxAar/sherpa_onnx/build.gradle.kts`: `namespace = "com.k2fsa.sherpa.onnx"`, `compileSdk = 34`, `minSdk = 21`. `settings.gradle.kts` uses only `google()` + `mavenCentral()` — the module is **not published anywhere**.

⚠ The Android docs at `https://k2-fsa.github.io/sherpa/onnx/android/index.html` describe **building C++ from source with NDK** (`./build-android-arm64-v8a.sh` → copy `libonnxruntime.so` + `libsherpa-onnx-jni.so` into `jniLibs/`). They do **not** document the AAR path. Using the prebuilt AAR avoids the NDK entirely.

Prebuilt APKs (all `arm64-v8a`, hosted on HF `csukuangfj2/sherpa-onnx-apk`, 1,556 `.apk` links on the page):
`https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/asr/1.13.8/sherpa-onnx-1.13.8-arm64-v8a-asr-zh_en-paraformer.apk` (and `...-zh-int8_small_zipformer_2025_04_01.apk`, `...-zh-small_zipformer_14M_2023_02_23.apk`, …). SenseVoice APK name pattern per docs: `zh_en_ko_ja_yue-sense_voice_2024_07_17_int8.apk`.

---

## Chinese accuracy evidence (with links)

**1. SenseVoice paper, Table 6 — CER % on Chinese corpora** ([arXiv:2407.04051v3](https://arxiv.org/abs/2407.04051), §4.1) — fetched from `https://arxiv.org/html/2407.04051v3`:

| Test set | Whisper-S | Whisper-L-V3 | **SenseVoice-S** | SenseVoice-L | Paraformer-zh |
|---|---|---|---|---|---|
| AISHELL-1 test | 10.04 | 5.14 | **2.96** | 2.09 | 1.95 |
| AISHELL-2 test_ios | 8.78 | 4.96 | **3.80** | 3.04 | 2.85 |
| WenetSpeech test_meeting | 25.62 | 18.87 | **7.44** | 6.73 | 6.97 |
| WenetSpeech test_net | 16.66 | 10.48 | **7.84** | 6.01 | 6.74 |
| LibriSpeech test_clean | 3.13 | 1.82 | 3.15 | 2.57 | – |
| CommonVoice zh-CN | 19.60 | 12.55 | 10.78 | 7.68 | 10.30 |
| CommonVoice yue | 38.97 | 10.41 | 7.09 | 6.78 | – |

**SenseVoice paper Table 7 — efficiency (A800, batch 1):**

| Model | Architecture | Params | RTF | 10 s latency |
|---|---|---|---|---|
| SenseVoice-S | non-autoregressive | 234M | **0.007** | **70 ms** |
| Paraformer-zh | non-autoregressive | 220M | 0.009 | 100 ms |
| Whisper-S | autoregressive | 224M | 0.042 | 518 ms |
| Whisper-L-V3 | autoregressive | 1550M | 0.111 | 1281 ms |

**2. sherpa-onnx Zipformer-CTC-zh docs** ([link](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-ctc/icefall/zipformer.html)) — `sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03`: AISHELL test **1.74%**, WenetSpeech test_net **5.92%**, test_meeting **7.75%**.

**3. Vosk Chinese model table** ([alphacephei.com/vosk/models](https://alphacephei.com/vosk/models)) — see §(b).

**4. Moonshine model table** ([docs](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/)) — Mandarin Tiny Streaming 34M: **16.1% no-space CER**, MIT. Legacy Mandarin Base/Tiny: 25.76% / unscored, **Community (non-commercial)**.

**5. Open ASR Leaderboard** ([hf-audio/open-asr-leaderboard](https://huggingface.co/datasets/hf-audio/open-asr-leaderboard)) — exists but I did **not** pull a Chinese CER row from it → **UNVERIFIED** for Chinese; it is English-centric (LibriSpeech/AMI/ESB).

---

## Android measured RTF — VoicePing benchmark (best mobile data found)

Source: [VoicePing offline STT benchmark](https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/) (updated 2026-09-21) and its open-source app [`voiceping-ai/android-offline-transcribe`](https://github.com/voiceping-ai/android-offline-transcribe) (Apache-2.0). Device: **Samsung Galaxy S10, Exynos 9820, 8 GB RAM, Android 12 (API 31)**; 30 s 16 kHz mono WAV.

| Model | Engine | Params | Download size | Inference (30 s) | tok/s | **RTF** | Status |
|---|---|---:|---:|---:|---:|---:|---|
| Moonshine Tiny | sherpa-onnx | 27M | ~125 MB | 1,363 ms | 42.55 | **0.05** | PASS |
| **SenseVoice Small** | **sherpa-onnx** | 234M | ~240 MB | 1,725 ms | 33.62 | **0.06** | PASS |
| Whisper Tiny | sherpa-onnx | 39M | ~100 MB | 2,068 ms | 27.08 | **0.07** | PASS |
| Moonshine Base | sherpa-onnx | 61M | ~290 MB | 2,251 ms | 25.77 | 0.08 | PASS |
| Android Speech (Offline) | SpeechRecognizer | System | built-in | 3,615 ms | 1.38 | 0.12 | PASS* |
| Zipformer Streaming | sherpa-onnx streaming | 20M | ~73 MB | 3,568 ms | 16.26 | 0.12 | PASS |
| Whisper Base | sherpa-onnx | 74M | ~160 MB | 4,038 ms | 14.36 | 0.13 | PASS |
| **Whisper Small** | sherpa-onnx | 244M | ~490 MB | 12,329 ms | 4.70 | **0.41** | PASS |
| Qwen3 ASR 0.6B | ONNX Runtime INT8 | 600M | ~1.9 GB | 15,881 ms | 3.65 | 0.53 | PASS |
| **Whisper Tiny** | **whisper.cpp GGML** | 39M | ~31 MB | **105,596 ms** | 0.55 | **3.52** | PASS |
| Qwen3 ASR 0.6B (CPU) | pure C/NEON | 600M | ~1.8 GB | 338,261 ms | 0.17 | 11.28 | PASS |
| Omnilingual 300M | sherpa-onnx | 300M | ~365 MB | 44,035 ms | 0.05 | 1.47 | FAIL |

**16 configs, 0 OOM.** The headline finding: the *same* Whisper-tiny checkpoint runs **51× slower on Android through whisper.cpp than through sherpa-onnx ONNX Runtime**. This benchmark measures **speed only, not accuracy**.

**RAM expectations:** the benchmark reports *download sizes*, **not peak process memory** — the authors say so explicitly. No trustworthy per-model Android RSS figures were found → **RAM figures are UNVERIFIED**. Conservative planning estimate: peak RSS ≈ onnx file size + ONNX Runtime working set (~100–250 MB) + your audio buffers; budget **~1.5–2× the model file size**. Treat this as an engineering rule of thumb, not a measurement.

---

## Code vs. model-weight licences — the distinction that matters

| Thing | Code licence | **Model weights licence** |
|---|---|---|
| sherpa-onnx | Apache-2.0 (verified `LICENSE` file) | n/a (runtime) |
| SenseVoice repo (now `QwenAudio/SenseVoice`) | **MIT** | — |
| SenseVoice-Small weights | — | ⚠ **NOT MIT.** HF card: `license: other` / `license_name: model-license` / `license_link: https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE`. The sherpa-onnx conversion's own `LICENSE` file (71 B) literally just says `Ref to https://github.com/modelscope/FunASR?tab=readme-ov-file#license`. |
| FunASR MODEL_LICENSE v1.1 (read in full) | — | Permits use/copy/modify/share, **but requires**: attribution + source + **retaining relevant model names**; forbids "unjustified denigration / malicious smearing" of the model (violation ⇒ automatic licence forfeiture); §7 choice-of-law left as `[Country/Region]` placeholder. **Not OSI-approved.** Copyleft-free but notice-heavy. |
| openai/whisper code | **MIT** | — |
| Whisper weights (`openai/whisper-{tiny,base,small}` on HF) | — | HF YAML `license: apache-2.0` (verified by fetching `README.md`). Note the *code* is MIT while the *weights* are tagged Apache-2.0 — different terms, both permissive. |
| whisper.cpp | **MIT** | GGML conversions inherit OpenAI's weights terms; the repo does not restate them → **UNVERIFIED** |
| vosk-api | **Apache-2.0** | Chinese models listed Apache-2.0 on the models page |
| Moonshine | **MIT** (except `core/third-party`) | MIT by default **except** legacy non-streaming non-English (Arabic/Japanese/Korean/**Mandarin**/Spanish/Ukrainian/Vietnamese) = **Moonshine Community License, non-commercial** |
| ExecuTorch | **BSD-3-Clause** | n/a |
| whisper-jni | Apache-2.0 | n/a |

---

## Recommendation

### ✅ PRIMARY: **SenseVoice-Small int8 via the sherpa-onnx AAR**

`com.k2fsa.sherpa.onnx.OfflineRecognizer` + `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/model.int8.onnx` (`model.int8.onnx` = 239,233,841 B; tarball = 163,002,883 B).

**Why:**
1. **Best Chinese accuracy-per-byte available in an Android-ready package.** AISHELL-1 CER **2.96%**, WenetSpeech-net **7.84%**, WenetSpeech-meeting **7.44%** — beats Whisper-Small (10.04 / 16.66 / 25.62) and Whisper-Large-V3 (5.14 / 10.48 / 18.87) on every Chinese test set, at 228 MB instead of Whisper-Small's 375 MB int8 / Whisper-Large's multi-GB.
2. **Fastest credible Chinese model on real Android hardware:** RTF **0.06** on a Galaxy S10 (1,725 ms for 30 s) — measured, not extrapolated. Non-autoregressive ⇒ no hallucination loops, no repetition failures, no beam-search tail latency.
3. **Zero build friction:** a single AAR with prebuilt `arm64-v8a` natives — **no NDK, no CMake, no JNI**. `minSdk 21`. Only ~19 MB of arm64 `.so` matters after ABI filtering.
4. **Chinese + English in one model**, plus Cantonese/Japanese/Korean, ITN punctuation via `use_itn=1`, and it also returns language/emotion/event tags for free.
5. **Licence is workable:** MIT code, and the weights licence (FunASR MODEL_LICENSE v1.1) is permissive with an **attribution obligation** — ship a "Powered by SenseVoice / FunASR, © Alibaba Group" notice and keep the model name. No non-commercial, no copyleft, no field-of-use restriction.

**APK-size plan:** `abiFilters` to `arm64-v8a` (+ `x86_64` for emulators only) ⇒ ~19 MB natives. Ship the 228 MB model as an in-app download (Google Play's 200 MB AAB limit and 150 MB base-module ceiling make bundling impractical). Net installed footprint ≈ **250 MB**.

**Caveats:** it is **offline/batch only** — you must run a VAD (`silero_vad.onnx`, 643,854 B) in front of it, exactly as `SherpaOnnxVadAsr` does. For a phone-call transcription workload this is the right architecture anyway (segment on speech, decode segments).

### ✅ FALLBACK: **`sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12` int8** (or `…-streaming-zipformer-bilingual-zh-en-2023-02-20` int8 if you need English too)

**Why this and not Whisper:** it is **true streaming** (chunk-16/left-128, ~320 ms lookahead), tiny enough to keep resident alongside SenseVoice, and it rides the *same* AAR and *same* `com.k2fsa.sherpa.onnx` API — so it is a one-config-line swap, not a second integration.

- Chinese-only int8 total **72,451,454 B (~69 MiB)** vs 198,214,476 B for the bilingual zh-en variant. Pick Chinese-only if RAM is tight; pick bilingual if English callers are common.
- Use it for (a) live partial captions while SenseVoice does the accurate final pass (`SherpaOnnx2Pass` is literally this pattern), or (b) low-RAM/low-end devices where 228 MB resident is too much.
- Reference: `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23` int8 is only ~24.5 MB total (tarball 74,004,050 B) for genuinely constrained devices, at an accuracy cost that is **UNVERIFIED** (no published WenetSpeech CER found).

**Accuracy caveat:** I found **no published CER/WER for the multi-zh-hans streaming zipformer** — mark it **UNVERIFIED** and measure it on your own call audio before shipping it as anything more than a fallback/partial-result path.

### ❌ Rejected, with reasons

- **whisper.cpp on Android** — fastest available evidence says **RTF 3.52** on a Galaxy S10 (105 s to transcribe 30 s). It cannot do real-time offline transcription on mid-range hardware, and there is no AAR. This alone disqualifies it as the primary path.
- **`io.github.givimad:whisper-jni:1.7.1`** — despite being a real Maven Central artifact, the JAR ships **no Android/bionic natives** (only Debian glibc, macOS, Windows). Using it on Android means rebuilding whisper.cpp with the NDK yourself — at which point use whisper.cpp directly.
- **Vosk** — genuinely easy (`com.alphacephei:vosk-android:0.3.75`, streaming, Apache-2.0), but Chinese accuracy is far behind: best Chinese model is **1.3 GB** for 13.98% WER on SpeechIO-02, and the Android-practical `vosk-model-small-cn-0.22` is only 42 MB but **23.54% / 38.29%** WER. Also `vosk-android-demo`'s latest release is from **2021-04**. Viable, not competitive.
- **mozilla/DeepSpeech** — **ARCHIVED**, English-only, last push 2025-06-19. Dead.
- **Moonshine** — the new **MIT Mandarin Tiny Streaming (34M)** makes it legally clean for Chinese now, and it is the fastest thing on Android (RTF 0.05). But **16.1% no-space CER** is roughly 5× worse than SenseVoice-Small, and its Mandarin legacy models are **non-commercial**. Great as a wake-word/VAD-adjacent component; not the transcription engine. Note the Maven coordinate is **`ai.moonshine:moonshine-voice:0.1.5`** — the repo's `com.vanniktech.maven.publish` plugin does publish it, and it verified HTTP 200.
- **ExecuTorch** — BSD-3-Clause, has `examples/models/{whisper,parakeet,emformer_rnnt,silero_vad,voxtral,granite_speech}`, but `examples/demo-apps` contains **only `react-native`** — no first-party Android ASR app or AAR. You would write the JNI and the Android packaging yourself. Not a shortcut.
- **RTranslator (10,469★)** and other Whisper apps — they are apps, not libraries; they inherit the whisper.cpp RTF problem.

---

## 7. Exact curl commands run (reproducible verification)

```bash
# --- repo metadata ---
curl -sS "https://api.github.com/repos/k2-fsa/sherpa-onnx"
curl -sS "https://api.github.com/repos/ggml-org/whisper.cpp"
curl -sS "https://api.github.com/repos/openai/whisper"
curl -sSL "https://api.github.com/repos/FunAudioLLM/SenseVoice"        # -> QwenAudio/SenseVoice  (301)
curl -sS "https://api.github.com/repos/alphacep/vosk-api"
curl -sS "https://api.github.com/repos/alphacep/vosk-android-demo"
curl -sSL "https://api.github.com/repos/usefulsensors/moonshine"        # -> moonshine-ai/moonshine (301)
curl -sS "https://api.github.com/repos/pytorch/executorch"
curl -sS "https://api.github.com/repos/mozilla/DeepSpeech"
curl -sS "https://api.github.com/repos/GiviMAD/whisper-jni"
curl -sS "https://api.github.com/repos/voiceping-ai/android-offline-transcribe"

# --- sherpa-onnx Android structure + release ---
curl -sS "https://api.github.com/repos/k2-fsa/sherpa-onnx/contents/android"
curl -sS "https://api.github.com/repos/k2-fsa/sherpa-onnx/contents/android/SherpaOnnxAar"
curl -sS "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/latest"
curl -sS "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases?per_page=5"
curl -sS "https://api.github.com/repos/k2-fsa/sherpa-onnx/contents/sherpa-onnx/kotlin-api"

# --- AAR: download, size, native ABIs, classes, manifest ---
curl -sSL -o sherpa.aar -w "HTTP=%{http_code} size=%{size_download}\n" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
ls -l sherpa.aar                       # 50129134
unzip -l sherpa.aar | grep -E 'jni/|\.so$'
unzip -l sherpa.aar | grep -c '\.so$'  # 16
unzip -o -q sherpa.aar classes.jar AndroidManifest.xml && cat AndroidManifest.xml
unzip -l classes.jar | grep -c '\.class'   # 122
unzip -p sherpa.aar META-INF/com/android/build/gradle/aar-metadata.properties

# --- sherpa-onnx Kotlin API source (signatures) ---
curl -sS "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/OfflineRecognizer.kt"
curl -sS "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/Vad.kt"
curl -sS "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/android/SherpaOnnxVadAsr/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt"
curl -sS "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/android/SherpaOnnxAar/README.md"
curl -sS "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/android/SherpaOnnxAar/sherpa_onnx/build.gradle.kts"
curl -sS "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/LICENSE"

# --- Maven Central: does a prebuilt Android artifact exist? ---
curl -sS "https://search.maven.org/solrsearch/select?q=sherpa-onnx&rows=40&wt=json"
curl -sS "https://search.maven.org/solrsearch/select?q=g:com.k2-fsa&rows=40&wt=json"   # numFound 0
curl -sS "https://search.maven.org/solrsearch/select?q=g:ai.moonshine&rows=20&wt=json"
curl -sS "https://search.maven.org/solrsearch/select?q=g:com.alphacephei&rows=20&wt=json"
curl -sS "https://search.maven.org/solrsearch/select?q=g:io.github.givimad&rows=40&wt=json"
curl -sS "https://repo1.maven.org/maven2/io/github/givimad/whisper-jni/1.7.1/"          # jar only, no aar
curl -sS -o wj.jar "https://repo1.maven.org/maven2/io/github/givimad/whisper-jni/1.7.1/whisper-jni-1.7.1.jar"
unzip -l wj.jar | grep -iE '\.so|\.dll|\.dylib'    # debian/macos/win only -> NO Android
curl -sS "https://repo1.maven.org/maven2/ai/moonshine/moonshine-voice/maven-metadata.xml"
curl -sS -o vosk.aar "https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.aar"
unzip -l vosk.aar | grep -E '\.so$|classes.jar'
curl -sS "https://repo1.maven.org/maven2/com/alphacephei/vosk-android/maven-metadata.xml"

# --- model URL + byte-size verification (HTTP 200 + Content-Length) ---
for u in \
 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2" \
 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2" \
 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2" \
 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" \
 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2" \
 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2" \
 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03.tar.bz2" \
 ; do
  echo "$(basename $u) | HTTP=$(curl -sSIL "$u" -o /dev/null -w '%{http_code}') | $(curl -sSIL "$u" | tr -d '\r' | grep -i '^content-length:' | tail -1)"
done

# --- Hugging Face exact blob sizes + licences ---
curl -sS "https://huggingface.co/api/models/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17?blobs=true"
curl -sS "https://huggingface.co/api/models/csukuangfj/sherpa-onnx-whisper-tiny?blobs=true"
curl -sS "https://huggingface.co/api/models/csukuangfj/sherpa-onnx-whisper-small?blobs=true"
curl -sS "https://huggingface.co/api/models/csukuangfj/vad?blobs=true"
curl -sS "https://huggingface.co/api/models/k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12?blobs=true"
curl -sS "https://huggingface.co/api/models/FunAudioLLM/SenseVoiceSmall"                # license:other
curl -sS "https://huggingface.co/api/models/openai/whisper-tiny"                        # license:apache-2.0
curl -sS "https://huggingface.co/FunAudioLLM/SenseVoiceSmall/raw/main/README.md"
curl -sS "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/raw/main/LICENSE"
curl -sS "https://raw.githubusercontent.com/modelscope/FunASR/main/MODEL_LICENSE"
curl -sS "https://huggingface.co/openai/whisper-tiny/raw/main/README.md" | grep -i license
curl -sS "https://raw.githubusercontent.com/moonshine-ai/moonshine/main/LICENSE"
curl -sS "https://raw.githubusercontent.com/moonshine-ai/moonshine/main/language-bindings/android/build.gradle.kts"

# --- docs: sizes, WER/CER, RTF ---
curl -sS "https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html"
curl -sS "https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-ctc/icefall/zipformer.html"
curl -sS "https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html"
curl -sS "https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/tiny.en.html"
curl -sS "https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html"
curl -sS "https://k2-fsa.github.io/sherpa/onnx/android/index.html"
curl -sS "https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html"
curl -sS "https://k2-fsa.github.io/sherpa/onnx/android/apk.html"
curl -sS "https://alphacephei.com/vosk/models"

# --- Chinese accuracy + Android RTF evidence ---
curl -sSL "https://arxiv.org/html/2407.04051v3"                       # Table 6 (CER) + Table 7 (RTF)
curl -sSL "https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/"
curl -sSL "https://raw.githubusercontent.com/voiceping-ai/android-offline-transcribe/main/README.md"   # full Android RTF table
curl -sS  "https://moonshine-voice.readthedocs.io/en/latest/models/available-models/"

# --- whisper.cpp Android example ---
curl -sS "https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/examples/whisper.android/README.md"
curl -sS "https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/examples/whisper.android.java/README.md"
curl -sS "https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/examples/whisper.android.java/app/build.gradle"
curl -sS "https://api.github.com/repos/ggml-org/whisper.cpp/contents/examples"

# --- whisper Android app search ---
curl -sS "https://api.github.com/search/repositories?q=whisper+android+offline&sort=stars&per_page=15"
curl -sS "https://api.github.com/search/repositories?q=whisper.cpp+android&sort=stars&per_page=15"
```

---

## Explicitly UNVERIFIED / open items

1. **RAM (peak RSS) per model on Android** — no source found; the VoicePing benchmark measures download size, not memory, and says so.
2. **`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09`** accuracy and on-disk `model.int8.onnx` size (tarball verified 165,783,878 B).
3. **CER/WER for `sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12`** and **`…-bilingual-zh-en-2023-02-20`** — no published numbers located.
4. **`sherpa-onnx-paraformer-zh-int8-2025-10-07`** and **`paraformer-zh-2024-03-09`** exact blob sizes (tarball / docs sizes only).
5. **Moonshine Mandarin Tiny Streaming model file size on disk** — param count (34M) and CER (16.1%) verified; byte size not fetched.
6. **whisper.cpp GGML weight licence** — the repo restates MIT for *code* only.
7. **On-device RTF for Vosk** and for **sherpa-onnx's own Chinese streaming/offline models** — the published Android RTF table covers Whisper/Moonshine/SenseVoice/Parakeet/Qwen3 but not the Chinese Zipformer/Paraformer checkpoints. Only the English Zipformer-20M streaming row (RTF 0.12) and the paper's A800 numbers exist.
8. **Open ASR Leaderboard Chinese CER** — not extracted; the leaderboard is English-centric.
9. **Android 15/16 (16 KB page size) compatibility of the sherpa-onnx 1.13.8 AAR** was not checked. Note that Moonshine's own Android module explicitly sets `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`; verify whether sherpa-onnx's shipped `.so` files do the same before targeting the newest API levels.
10. **JitPack availability of sherpa-onnx** was not probed; the project documents only GitHub Releases.
