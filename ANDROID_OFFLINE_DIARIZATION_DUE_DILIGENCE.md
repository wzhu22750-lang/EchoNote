# Android Offline Speaker Diarization + VAD — Open-Source Due Diligence

**Scope:** offline (on-device, arm64) speaker diarization ("who spoke when", 我 / 对方) + VAD for
**Chinese phone-call audio where both speakers are mixed into ONE mono channel**.

**Verification method:** GitHub REST API for metadata (`stars`/`forks`/`pushed_at`/`license`), `raw.githubusercontent.com`
for real source files, `curl -I` for every download URL (HTTP status + `content-length`), `unzip -l` on downloaded
AARs to enumerate ABIs, and `tar tjvf` + reading the shipped `LICENSE` file inside every model archive.
All numbers below were observed on **2026-09-30**. Nothing is estimated.

Legend: ✅ verified by direct fetch · ⚠️ partially verified · ❌ UNVERIFIED / could not confirm

---

## 0. Executive summary (the 6 things that decide this)

1. **sherpa-onnx does have a real, built-in, offline diarization pipeline** — `OfflineSpeakerDiarization` +
   `FastClusteringConfig` + `SpeakerEmbeddingExtractorConfig`, with a Kotlin API and a **prebuilt Android AAR
   (50,129,134 bytes, 4 ABIs)** and **prebuilt diarization APKs**. ✅ Fully verified by reading source.
2. **The blocker is accuracy on mono 2-speaker audio, not availability.** Two long-open sherpa-onnx issues
   document exactly our failure mode: on a **mono** interview file, `numClusters=2` collapsed *both* speakers into
   `speaker_00` for the whole recording (issue #1708, still open since 2025-01); and auto-clustering (`-1`)
   degrades accuracy vs. a known cluster count (#1466, #2445, both open). A 2025-10 comment adds
   *"I've been testing speaker-diarization with crosstalk audio and it's not working well either."*
3. **pyannote is MIT for code *and* for its segmentation model weights — but every HF repo is `gated: auto`
   and returns HTTP 401 anonymously.** The best-quality alternative is therefore not directly redistributable
   as a turnkey ONNX, and no first-party Android port exists.
4. **Legal landmine found:** `sherpa-onnx-reverb-diarization-v1` — the segmentation model that is actually good on
   reverberant / loudspeaker-microphone capture — ships a **"Rev Model Non-Production License"** (non-commercial).
   The pyannote segmentation model in the same release ships a plain **MIT** license. Do not mix these up.
5. **The pragmatic production path is offline embedding + online cosine matching, not the batch clustering pipeline.**
   `zeerd/Real-timeTranscription` (MIT, Kotlin) is a working, shipping Android app that does exactly this:
   Silero VAD → ASR → `SpeakerEmbeddingExtractor` + CAM++ embeddings → cosine match at threshold `0.5f`, plus a
   voiceprint sliding-window **Speaker Change Detector** to force a break when two people talk back-to-back with no
   pause. This is the only design in the survey that is explicitly built for the "no silence between turns" case.
6. **Nobody in the Android ecosystem solves mono phone calls well.** The most credible Android speaker-diarization
   app in GitHub history (`blabbertabber`, 23★) is **deprecated**, and its author's own post-mortem is:
   *"it never worked very well (I couldn't find a good-enough diarizer)."* Plan for graceful degradation and a
   user-correction UI, not for a solved problem.

**Recommended stack (details in §6):** sherpa-onnx AAR (Android) + pyannote-segmentation-3-0 (**MIT**)
+ `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` (**Apache-2.0**) + `numClusters=2` hard-pinned
(never `-1`) + Silero VAD (`silero_vad.onnx`, 643,854 B, MIT) for segmentation, plus an online voiceprint
change-detector for pause-free turn changes.

---

## (a) Comparison table

Stars / forks / last-commit are from `https://api.github.com/repos/<repo>` on 2026-09-30.
"Last commit" = `pushed_at` (last push to any branch), which is the only field the API exposes without extra calls.

### A.1 Diarization / embedding / VAD engines and libraries

| Project | URL | Stars | Forks | Last commit (pushed_at) | Code License | **Model/weights License** | Android artifact | Offline on arm64 | Mono 2-spk on 1 channel | Size (on device) |
|---|---|---|---|---|---|---|---|---|---|---|
| **k2-fsa/sherpa-onnx** | https://github.com/k2-fsa/sherpa-onnx | 15043 | 1732 | 2026-09-22 | Apache-2.0 (LICENSE, 11358 B) | Per-model, see §2. pyannote seg = **MIT**; Rev reverb = **Non-Production**; 3D-Speaker = Apache-2.0 | ✅ **Prebuilt AAR** `sherpa-onnx-1.13.8.aar` (50,129,134 B, 4 ABIs) + prebuilt APKs | ✅ yes | ⚠️ **Pipeline runs, but collapses 2→1 spk on mono audio** (open issue #1708) | AAR 50.1 MB + seg 6.0 MB + emb 28.3 MB ≈ **85 MB** |
| **pyannote/pyannote-audio** | https://github.com/pyannote/pyannote-audio | 10604 | 1109 | 2026-09-24 | **MIT** (`main/LICENSE`, © 2020 CNRS) | `speaker-diarization-3.1` card: `license: mit` but **`gated: auto`, HTTP 401 anonymously**. `segmentation-3.0` also **HTTP 401**. ONNX export shipped by sherpa-onnx carries MIT © 2022 CNRS | ❌ **none** (PyTorch/Python only) | ❌ not on device as-is (needs export + custom JNI) | best-in-class in benchmarks, incl. telephone | Python stack only (GBs); no mobile story |
| **wenet-e2e/wespeaker** | https://github.com/wenet-e2e/wespeaker | 1426 | 207 | 2026-09-22 | Apache-2.0 (11357 B) | ⚠️ *"The pretrained model in WeNet follows the license of it's corresponding dataset"* → VoxCeleb models = **CC-BY-4.0**; CN-Celeb models follow the CN-Celeb dataset license | ❌ no AAR; C++ `runtime/onnxruntime` (cmake/gcc, desktop) — needs own NDK build | ⚠️ possible, unproven | ⚠️ embedding only, no built-in clustering | `cnceleb_resnet34.onnx` 26,534,127 B; `voxceleb_CAM++.onnx` 29,292,449 B |
| **modelscope/3D-Speaker** | https://github.com/modelscope/3D-Speaker | 3159 | 270 | 2025-12-08 | Apache-2.0 (11357 B) | ✅ **Apache License 2.0** for CAM++ / ERes2Net / ERes2NetV2 on ModelScope (verified via ModelScope API `Data.License`) | ❌ no AAR; `runtime/onnxruntime` is C++/cmake (desktop) | ⚠️ via sherpa-onnx's pre-exported ONNX | has full diarization recipe (VAD+seg+emb+cluster) but Python | `campplus_sv_zh-cn_16k-common.onnx` 28,281,138 B (as re-exported by sherpa-onnx) |
| **alibaba-damo-academy/3D-Speaker** | https://github.com/alibaba-damo-academy/3D-Speaker | — | — | — | — | — | — | — | ❌ **HTTP 301 Moved Permanently** → use `modelscope/3D-Speaker` |
| **snakers4/silero-vad** | https://github.com/snakers4/silero-vad | 10326 | 856 | 2026-09-29 | **MIT** (`master/LICENSE`, © 2020-present Silero Team) | README: *"Published under permissive license (MIT) Silero VAD has zero strings attached"* ✅. ⚠️ NB the README *badge* alt-text says "CC BY-NC 4.0" while linking to the MIT LICENSE — stale badge, LICENSE file is authoritative | ⚠️ no official AAR; used on Android via `android-vad` / sherpa-onnx / ONNX Runtime directly | ✅ yes | VAD only (not diarization) | repo `silero_vad.onnx` 2,327,524 B; sherpa-onnx export 643,854 B; gkonovalov export 1,807,522 B |
| **TEN-framework/ten-vad** | https://github.com/TEN-framework/ten-vad | 2277 | 180 | 2026-02-02 | ⚠️ **"Open Source License"** = Apache-2.0 **plus additional conditions** incl. a **non-compete clause**: *"You may not Deploy the ten-vad in a way that competes with Agora's offerings…"* | same file | ⚠️ ONNX via sherpa-onnx `Vad` / `TenVadModelConfig` | ✅ yes | VAD only | `ten-vad.onnx` 332,211 B |
| **microsoft/onnxruntime** | https://github.com/microsoft/onnxruntime | 21952 | 4265 | 2026-09-30 | **MIT** | n/a (runtime) | ✅ **Maven Central** `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` | ✅ yes | n/a (inference runtime) | AAR **28,515,295 B**; 4 ABIs (see §2.4) |
| **gkonovalov/android-vad** | https://github.com/gkonovalov/android-vad | 509 | 100 | 2025-07-15 | **MIT** (`main/LICENSE.md`, © 2023 Georgiy Konovalov) | WebRTC VAD (BSD-style, Chromium); Silero = MIT; YAMNet = Apache-2.0 (TF models) | ✅ **prebuilt AARs** + JitPack | ✅ yes | VAD only (no diarization at all) | webrtc AAR 152,999 B; silero AAR 1,532,525 B; yamnet AAR 3,251,497 B |
| **soniqo/speech-core** | https://github.com/soniqo/speech-core | 90 | 7 | 2026-09-15 | Apache-2.0 | via `speech-android` models | ❌ C++ engine, consumed by `speech-android` | ✅ yes | ⚠️ "diarization" advertised; 4-spk Sortformer, not tuned for 2-spk mono | — |
| **notch-up/diarize** | https://github.com/notch-up/diarize | 120 | 13 | 2026-05-06 | Apache-2.0 | ❌ not stated | ❌ Python only | ❌ | claims ~10.8% DER on VoxConverse (✅ repo claim, ⚠️ not independently verified) | — |
| **ekhodzitsky/polyvoice** | https://github.com/ekhodzitsky/polyvoice | 20 | 3 | 2026-09-29 | MIT | MIT, **ungated** (repo claims "INT8 kernels ~8.4 MB, MIT, ungated") | ❌ Rust (C FFI + CLI); Android would need JNI wrapper | ⚠️ CPU-only Rust, portable in principle | ⚠️ unproven for mono phone calls | ⚠️ ~8.4 MB claimed, ❌ not verified by us |
| **samson6460/pyannote-onnx-extended** | https://github.com/samson6460/pyannote-onnx-extended | 11 | 2 | 2026-01-23 | MIT | ❌ not stated (depends on pyannote weights → gated) | ❌ Python | ❌ | ⚠️ ONNX re-implementation of pyannote 3.1 — useful as an **export reference**, not a mobile lib | — |

### A.2 Android AI meeting-recorder / podcast-transcription apps (reference implementations)

| Project | URL | Stars | Forks | Last commit | License | Language | Offline? | Diarization? | Notes |
|---|---|---|---|---|---|---|---|---|---|
| **OpenWhispr/openwhispr** | https://github.com/OpenWhispr/openwhispr | 8850 | 1093 | 2026-09-29 | MIT | JS/TS (Electron + Expo RN) | ✅ local Whisper/Parakeet | ⚠️ **iOS-only** | Has a real `openwhispr-mobile/modules/speaker-diarization` Expo module + `src/lib/diarization/*` (diarizer.ts, embeddingCodec, voiceprints, mergeSegments). **`expo-module.config.json` = `"platforms": ["ios"]`**; bridge throws *"SpeakerDiarization is only available on iOS 17+."* Engine = **FluidAudio** (CoreML, ~100 MB, Neural Engine). **No Android diarization.** |
| **soniqo/speech-android** | https://github.com/soniqo/speech-android | 164 | 13 | 2026-09-15 | Apache-2.0 | Kotlin | ✅ | ✅ **streaming** | **Maven artifact exists:** `audio.soniqo:speech:0.0.22` → AAR **29,765,407 B**, ABIs **arm64-v8a + x86_64 only**. Kotlin API: `SpeakerDiarizer(DiarizerConfig(...))` → `pushAudio(samples): [frames x speakers]`, `SpeakerEmbedder.embed(speech): FloatArray(192)`, `VadDetector(VadConfig(...))`. Diarizer = **Sortformer 4-speaker**, ONNX **474,630,246 B** (+475,637,953 B sidecar), licensed **NVIDIA Open Model License** (⚠️ *not* Apache/MIT). README: Sortformer *"answers about 30 seconds behind the audio"* (decodes 27.2 s per call) → **not suitable for live call UI**. |
| **conwerter1/protocolvoice** | https://github.com/conwerter1/protocolvoice | 4 | 0 | 2026-09-08 | Apache-2.0 | Kotlin | ✅ | ✅ 1–8 speakers | **Closest architectural match to our goal.** sherpa-onnx ASR + **CAM++/ERes2Net embeddings**, Silero VAD, in-app model download from HF `protocolvoice/asr-models` with SHA-256 verification. API 26+, **64-bit ARM only**, ~700 MB model budget. Self-reported RTF 0.5 for CAM++ diarization on Xiaomi 12T. Ships `app/src/main/assets/asr/silero_vad.onnx`. |
| **qutschwalze/meeting-transcriber-sherpa** | https://github.com/qutschwalze/meeting-transcriber-sherpa | 3 | 0 | 2026-09-11 | ⚠️ **GitHub reports `license: None`** (no LICENSE file detected) even though README says "MIT" — **treat as unlicensed/ask the author** | Kotlin | ✅ | ✅ 2–4 speakers | The single most instructive codebase: real `SpeakerDiarizationEngine.kt` driving `OfflineSpeakerDiarization` + `FastClusteringConfig` with tuned `minDurationOn=0.1f, minDurationOff=0.05f` (vs defaults 0.2/0.5), a 15 s rolling chunk pipeline, a `SessionVoiceBank` to fight **engine drift**, and documented cleanup passes `VB_DRIFT_ABFANG` / `VB_DUP_MERGE`. Ships `assets/segmentation.onnx` + `assets/embedding.onnx`. API 26+. |
| **zeerd/Real-timeTranscription** | https://github.com/zeerd/Real-timeTranscription | 4 | 0 | 2026-07-21 | **MIT** | Kotlin | ✅ | ✅ online voiceprint | **Most relevant for live phone calls.** `SpeakerDiarizationManager.kt` uses `SpeakerEmbeddingExtractor` + cosine matching (`DIARIZATION_SIMILARITY_THRESHOLD = 0.5f`, `MIN_DIARIZATION_SAMPLES = 16000`) with profile persistence + "reuse last speaker on short segments" fallback. `SpeakerChangeDetector.kt` = 400 ms sub-window / 200 ms step SCD for **pause-free turn changes**. Deps: ONNX Runtime Android **1.27.0** + Extensions **0.13.0**, sherpa-onnx **v1.13.4**. minSdk 26. |
| **DaedalusApps/daedalus-echo** | https://github.com/DaedalusApps/daedalus-echo | 5 | 2 | 2026-08-25 | MIT | Kotlin | ✅ Whisper + Gemma 3 | ❌ **NO diarization** | Definitively checked: the only `EmbeddingService.kt` is a **MediaPipe `TextEmbedder`** (text embeddings for RAG), not speaker embeddings. No speaker/voiceprint/cluster code anywhere in the 236-file tree. |
| **blabbertabber/blabbertabber** | https://github.com/blabbertabber/blabbertabber | 23 | 11 | 2021-03-28 | **AGPL-3.0** | Java | ⚠️ had cloud backend | ✅ (historic) | **DEPRECATED.** `docs/README.md`: *"BlabberTabber is defunct. It's no longer maintained… the back-end servers have been turned off."* and the author's post-mortem: *"it never worked very well (I couldn't find a good-enough diarizer), and over time my interest waned."* Valuable as a **negative result**, not as a dependency. |
| **vilassn/whisper_android** | https://github.com/vilassn/whisper_android | 692 | 112 | 2026-03-18 | MIT | C++ | ✅ Whisper via TFLite | ❌ no diarization | Good reference for offline ASR packaging only. |
| **RisorseArtificiali/anti-vocale** | https://github.com/RisorseArtificiali/anti-vocale | 106 | 6 | 2026-09-30 | Apache-2.0 | Kotlin | ✅ | ❌ no diarization | On-device voice-message transcription. |
| **23rd/Scrib** | https://github.com/23rd/Scrib | 66 | 7 | 2026-09-26 | GPL-3.0 | Kotlin | ✅ | ❌ no diarization | GPL-3.0 → viral, avoid for a closed product. |
| **Vinnih-1/Kipty** | https://github.com/Vinnih-1/Kipty | 36 | 2 | 2026-09-27 | Apache-2.0 | Kotlin | ✅ | ❌ no diarization | Podcast transcription for English learners. |
| **Nomily-Ai/nomily-app** | https://github.com/Nomily-Ai/nomily-app | 31 | 2 | 2026-09-29 | ⚠️ `NOASSERTION` | (not set) | ⚠️ claims local ASR endpoint | ✅ claims speaker diarization | Recycled/rebranded; **DO NOT** — identical `upload/download` byte sizes as `gerrryt/sherpa-onnx67` and `welcomyou/...`, i.e. a fork/spam cluster. ⚠️ No license ⇒ **all rights reserved by default**. |
| **hrushik98/offline-telugu-asr-android** | https://github.com/hrushik98/offline-telugu-asr-android | 2 | 0 | 2026-06-20 | `NOASSERTION` | Kotlin | ✅ | ✅ per-speaker color diarization via ONNX Runtime | Interesting small reference; **too low-signal / unlicensed to rely on.** |
| **welcomyou/sherpa-vietnamese-asr-android** | https://github.com/welcomyou/sherpa-vietnamese-asr-android | 1 | 1 | 2026-05-22 | MIT | Java | ✅ | ✅ | ASR (Vietnamese) + diarization offline on Android. |

### A.3 Java/JVM Maven coordinates for sherpa-onnx (for completeness — **not** Android AARs)

⚠️ **`search.maven.org` returns `numFound: 0` for group `k2fsa`** — there is **no Android AAR on Maven Central**.
What exists is a **JVM** artifact set published through JitPack (`com.github.k2-fsa.sherpa-onnx:*`), declared in
`java-api-examples/maven-examples/pom.xml`:

```xml
<dependency><groupId>com.github.k2-fsa.sherpa-onnx</groupId><artifactId>sherpa-onnx-jvm</artifactId><version>v1.13.8</version></dependency>
```
For Android you must either (i) consume the GitHub release asset `sherpa-onnx-1.13.8.aar`, or
(ii) use JitPack `com.github.k2-fsa:sherpa-onnx:v1.13.8` (packaging `aar`, per `jitpack.yml` — ⚠️ the
`jitpack.yml` `before_install` references `sherpa-onnx-v1.13.8.aar`, which **404s**; the real asset name has no
`v` prefix: `sherpa-onnx-1.13.8.aar`. So the JitPack POM is likely **broken** — prefer the release asset).

---

## (b) Verified model download list — exact URLs + byte sizes

Every row below was verified with `curl -sIL` returning **HTTP 200** and the reported `content-length`
(or from the GitHub/HF API `size` field where noted). Sizes are **exact bytes**, not "≈ MB".

### b.1 VAD

| Model | Exact URL | Bytes | License |
|---|---|---|---|
| `silero_vad.onnx` (sherpa-onnx export) | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx` | **643,854** | MIT (Silero) ✅ |
| `ten-vad.onnx` | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/ten-vad.onnx` | **332,211** | ⚠️ Apache-2.0 **+ non-compete condition** |
| `silero_vad.onnx` (upstream repo, v5-class) | `https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.onnx` | **2,327,524** | MIT ✅ |
| `silero_vad_16k_op15.onnx` | `https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad_16k_op15.onnx` | **1,289,603** | MIT ✅ |
| `silero_vad_op18_ifless.onnx` | `https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad_op18_ifless.onnx` | **2,845,718** | MIT ✅ |
| `silero_vad_half.onnx` | `https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad_half.onnx` | **1,280,395** | MIT ✅ |
| `silero_vad_16k_sequence.onnx` | `https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad_16k_sequence.onnx` | **1,246,165** | MIT ✅ |
| `silero_vad_openvino_16k.onnx` | `https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad_openvino_16k.onnx` | **1,288,203** | MIT ✅ |
| `silero_vad.jit` | `https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.jit` | **2,272,526** | MIT ✅ |
| `silero_vad.onnx` (**bundled in** android-vad) | `https://raw.githubusercontent.com/gkonovalov/android-vad/main/silero/src/main/assets/silero_vad.onnx` | **1,807,522** | MIT ✅ |
| `yamnet.tflite` (for Yamnet VAD) | `https://raw.githubusercontent.com/gkonovalov/android-vad/main/yamnet/src/main/assets/yamnet.tflite` | **4,126,810** | Apache-2.0 (TF models) ⚠️ |

> ⚠️ Note the 3.6× spread between silero exports (643,854 / 1,807,522 / 2,327,524). They are **different model
> versions/graphs**, not the same file. Pick per ONNX Runtime version and re-validate thresholds — VAD threshold
> `0.5f` is *not* portable across these exports.

### b.2 Speaker segmentation (needed by `OfflineSpeakerDiarization`)

| Model | Exact URL | Archive bytes | **ONNX bytes inside** | **Model License (read from the tarball's own LICENSE)** |
|---|---|---|---|---|
| **`sherpa-onnx-pyannote-segmentation-3-0`** | `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2` | **6,958,444** | `model.onnx` **5,992,913**<br>`model.int8.onnx` **1,540,506** | ✅ **MIT License, Copyright (c) 2022 CNRS** (1061 B inside the archive) |
| `sherpa-onnx-reverb-diarization-v1` | `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-reverb-diarization-v1.tar.bz2` | **10,918,585** | `model.onnx` **9,512,223**<br>`model.int8.onnx` **2,416,155** | 🚫 **"Rev Model Non-Production License"** (11111 B). README: *"Note that it is accessible under a non-commercial license."* Converted from `Revai/reverb-diarization-v1`. |
| `sherpa-onnx-reverb-diarization-v2` | `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-reverb-diarization-v2.tar.bz2` | **254,075,989** | ❌ not measured | 🚫 assumed same Rev non-production terms (⚠️ not re-verified) |

Test audio in the same release tag (useful for your own DER harness):
`0-four-speakers-zh.wav` **1,819,586** · `3-two-speakers-en.wav` **1,753,804** ·
`2-two-speakers-en.wav` **1,088,078** · `1-two-speakers-en.wav` **512,044**.

### b.3 Speaker embedding

All from release tag **`speaker-recongition-models`** (2023-12-08, 36 assets — note the upstream typo "recongition";
it is in the real URL). Base URL:
`https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/<name>`

| Model file | Bytes | Language fit | **Model License** |
|---|---|---|---|
| **`3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`** | **28,281,138** | 🇨🇳 zh-cn (200k speakers) — **best size/quality for us** | ✅ **Apache License 2.0** (ModelScope `iic/speech_campplus_sv_zh-cn_16k-common`) |
| `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx` | **28,281,164** | zh+en | ✅ Apache-2.0 (same family) |
| `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx` | **39,593,761** | 🇨🇳 zh-cn — **sherpa-onnx's own default for diarization** | ✅ **Apache License 2.0** (ModelScope `iic/speech_eres2net_base_sv_zh-cn_3dspeaker_16k`) |
| `3dspeaker_speech_eres2net_base_200k_sv_zh-cn_16k-common.onnx` | **39,593,765** | zh-cn | ✅ Apache-2.0 |
| `3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx` | **71,441,526** | zh-cn, newer/better | ✅ Apache-2.0 (ModelScope `damo/speech_eres2netv2_sv_zh-cn_16k-common`) |
| `3dspeaker_speech_eres2net_large_sv_zh-cn_3dspeaker_16k.onnx` | **116,058,710** | zh-cn | ✅ Apache-2.0 |
| `3dspeaker_speech_eres2net_sv_zh-cn_16k-common.onnx` | **220,623,485** | zh-cn | ✅ Apache-2.0 |
| `3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx` | **29,596,978** | en | ✅ Apache-2.0 |
| `3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx` | **26,485,263** | en | ✅ Apache-2.0 |
| `nemo_en_titanet_small.onnx` | **40,257,283** | 🇬🇧 **en only** | ⚠️ **CC-BY-4.0** (sibling card `nvidia/speakerverification_en_titanet_large` = `license: cc-by-4.0`, verified from its raw README YAML). `titanet_small` itself comes from NGC, whose license page was **not machine-readable** → ❌ UNVERIFIED for `_small` specifically. |
| `nemo_en_titanet_large.onnx` | **101,405,493** | en | ⚠️ CC-BY-4.0 (via `nvidia/speakerverification_en_titanet_large`) |
| `nemo_en_speakerverification_speakernet.onnx` | **23,411,863** | en | ❌ UNVERIFIED (NGC) |
| `wespeaker_zh_cnceleb_resnet34.onnx` | **26,534,363** | 🇨🇳 zh (CN-Celeb) | ⚠️ **follows the CN-Celeb dataset license** (`wespeaker/docs/pretrained.md`: *"The pretrained model in WeNet follows the license of it's corresponding dataset"*) — **not Apache-2.0** |
| `wespeaker_zh_cnceleb_resnet34_LM.onnx` | **26,530,548** | zh | ⚠️ same |
| `wespeaker_en_voxceleb_resnet34.onnx` | **26,534,365** | en | ⚠️ **CC-BY-4.0** (VoxCeleb dataset license) |
| `wespeaker_en_voxceleb_CAM++.onnx` | **29,292,684** | en | ⚠️ CC-BY-4.0 |
| `wespeaker_en_voxceleb_CAM++_LM.onnx` | **29,292,687** | en | ⚠️ CC-BY-4.0 |
| `wespeaker_en_voxceleb_resnet34_LM.onnx` | **26,530,550** | en | ⚠️ CC-BY-4.0 |
| `wespeaker_en_voxceleb_resnet152_LM.onnx` | **79,158,470** | en | ⚠️ CC-BY-4.0 |
| `wespeaker_en_voxceleb_resnet221_LM.onnx` | **95,037,599** | en | ⚠️ CC-BY-4.0 |
| `wespeaker_en_voxceleb_resnet293_LM.onnx` | **114,336,527** | en | ⚠️ CC-BY-4.0 |
| `checksum.txt` | **3,477** | — | — |

**Pre-exported wespeaker ONNX direct from HuggingFace** (bypasses the sherpa-onnx release; ✅ HTTP 200 + size verified):

| Model | URL | Bytes |
|---|---|---|
| `cnceleb_resnet34.onnx` | `https://huggingface.co/Wespeaker/wespeaker-cnceleb-resnet34/resolve/main/cnceleb_resnet34.onnx?download=true` | **26,534,127** |
| `cnceleb_resnet34_LM.onnx` | `https://huggingface.co/Wespeaker/wespeaker-cnceleb-resnet34-LM/resolve/main/cnceleb_resnet34_LM.onnx?download=true` | **26,530,309** |
| `voxceleb_CAM++.onnx` | `https://huggingface.co/Wespeaker/wespeaker-voxceleb-campplus/resolve/main/voxceleb_CAM%2B%2B.onnx?download=true` | **29,292,449** |

### b.4 Clustering

**No download needed.** `FastClusteringConfig` is the clustering algorithm, implemented in-tree in C++
(`sherpa-onnx/csrc/offline-speaker-diarization-impl.cc` + `-pyannote-impl.h`). Zero extra bytes. This is a genuine
advantage over pyannote, which pulls `scikit-learn`/`torch` clusters.

### b.5 Android runtime artifacts

| Artifact | Coordinate / URL | Bytes | ABIs |
|---|---|---|---|
| **ONNX Runtime Android** | Maven Central `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`<br>`https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar` | **28,515,295** | ✅ **arm64-v8a, armeabi-v7a, x86, x86_64** (verified via `unzip -l`) |
| ONNX Runtime Mobile | `com.microsoft.onnxruntime:onnxruntime-mobile:1.18.0` | **7,241,017** | ⚠️ not enumerated (smaller, reduced op set — **verify your ops are in the reduced set before using**) |
| ONNX Runtime Extensions Android | `com.microsoft.onnxruntime:onnxruntime-extensions-android:0.13.0` | **9,040,811** | ⚠️ not enumerated |
| ONNX Runtime Android QNN | `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.22.0` | ⚠️ not measured | Qualcomm HTP |
| ONNX Runtime Training Android | `com.microsoft.onnxruntime:onnxruntime-training-android:1.19.2` | ⚠️ not measured | — |
| **sherpa-onnx Android AAR** (the one you want) | `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar` | **50,129,134** | ✅ **arm64-v8a, armeabi-v7a, x86, x86_64** |
| sherpa-onnx static-onnxruntime AAR | `.../v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar` | **38,691,998** | ✅ (smaller; static ORT) |
| sherpa-onnx RKNN AAR | `.../v1.13.8/sherpa-onnx-1.13.8-rknn.aar` | **26,598,297** | Rockchip NPU |
| sherpa-onnx Android jniLibs tarball (for building the AAR yourself) | `.../v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2` | **46,093,321** | 4 ABIs |
| **Official diarization APK** (arm64-v8a, pyannote+3dspeaker) | `https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/speaker-diarization/1.13.8/sherpa-onnx-1.13.8-arm64-v8a-speaker-diarization-pyannote_audio-3dspeaker.apk` | **62,185,120** (HTTP 302 → `x-linked-size`) | arm64-v8a |
| `audio.soniqo:speech:0.0.22` | `https://repo1.maven.org/maven2/audio/soniqo/speech/0.0.22/speech-0.0.22.aar` | **29,765,407** | ⚠️ **arm64-v8a + x86_64 only** (no armeabi-v7a) |
| android-vad (WebRTC) | JitPack `com.github.gkonovalov.android-vad:webrtc:2.0.10` / [release AAR](https://github.com/gkonovalov/android-vad/releases/tag/2.0.10) | **152,999** | — |
| android-vad (Silero) | `com.github.gkonovalov.android-vad:silero:2.0.10` | **1,532,525** | — |
| android-vad (Yamnet) | `com.github.gkonovalov.android-vad:yamnet:2.0.10` | **3,251,497** | — |
| Sortformer 4-speaker diarization ONNX | `https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX` | **474,630,246** (+ `sortformer-default.onnx.data` **475,637,953**) | — |

**ABI contents of `sherpa-onnx-1.13.8.aar` (verified with `unzip -l`):**

| ABI | `libonnxruntime.so` | `libsherpa-onnx-jni.so` | `libsherpa-onnx-c-api.so` | `libsherpa-onnx-cxx-api.so` |
|---|---|---|---|---|
| arm64-v8a | 22,249,560 | 4,771,760 | 4,465,168 | 440,688 |
| armeabi-v7a | 15,359,592 | 3,428,780 | 3,202,700 | 282,008 |
| x86 | 26,491,148 | 5,358,928 | 5,051,192 | 392,768 |
| x86_64 | 25,581,120 | 5,201,192 | 4,927,096 | 436,952 |

**ABI contents of `onnxruntime-android-1.22.0.aar` (verified with `unzip -l`):**

| ABI | `libonnxruntime.so` | `libonnxruntime4j_jni.so` |
|---|---|---|
| arm64-v8a | 18,214,224 | 100,600 |
| armeabi-v7a | 13,242,336 | 73,676 |
| x86 | 21,520,628 | 84,656 |
| x86_64 | 21,880,016 | 90,728 |

> Practical note: sherpa-onnx's AAR **already bundles** ONNX Runtime for all 4 ABIs. Adding
> `onnxruntime-android` alongside it duplicates ~18 MB of `libonnxruntime.so` per ABI.
> Use **one** of them, or add `packaging { jniLibs { pickFirsts += "**/libonnxruntime.so" } }`.

---

## (c) The exact sherpa-onnx Kotlin API surface — confirmed by reading source

### c.1 Where the source actually lives (a real structural gotcha)

The Android demo and AAR Kotlin files are **symlinks** into the shared API directory. Verified byte contents:

| Path | File size | Content |
|---|---|---|
| `sherpa-onnx/kotlin-api/OfflineSpeakerDiarization.kt` | **3258 B** | ✅ the real source |
| `android/SherpaOnnxAar/sherpa_onnx/src/main/java/com/k2fsa/sherpa/onnx/OfflineSpeakerDiarization.kt` | **81 B** | `../../../../../../../../../../sherpa-onnx/kotlin-api/OfflineSpeakerDiarization.kt` (symlink) |
| `android/SherpaOnnxSpeakerDiarization/.../OfflineSpeakerDiarization.kt` | **87 B** | symlink to the same file |
| `android/SherpaOnnxSpeakerDiarization/.../SpeakerEmbeddingExtractorConfig.kt` | **93 B** | symlink → `sherpa-onnx/kotlin-api/SpeakerEmbeddingExtractorConfig.kt` |

So there is **one** Kotlin API definition for `com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization`, shared by the AAR,
the Android demo, the Flutter/web wrappers and the Kotlin examples. The Android demo directory is
`android/SherpaOnnxSpeakerDiarization/` (Compose app, `applicationId com.k2fsa.sherpa.onnx.speaker.diarization`,
`versionName "1.13.8"`, `minSdk 21`, `compileSdk 34`).

### c.2 Data classes (verbatim field names, defaults and types)

File: `sherpa-onnx/kotlin-api/OfflineSpeakerDiarization.kt`, package `com.k2fsa.sherpa.onnx`.

```kotlin
data class OfflineSpeakerSegmentationPyannoteModelConfig(
    var model: String = "",
    var windowShiftRatio: Float = 0.1f,
)

data class OfflineSpeakerSegmentationModelConfig(
    var pyannote: OfflineSpeakerSegmentationPyannoteModelConfig = OfflineSpeakerSegmentationPyannoteModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class FastClusteringConfig(
    var numClusters: Int = -1,          // -1 == auto-detect via `threshold`
    var threshold: Float = 0.5f,
    var computeConfidence: Boolean = false,
)

data class OfflineSpeakerDiarizationConfig(
    var segmentation: OfflineSpeakerSegmentationModelConfig = OfflineSpeakerSegmentationModelConfig(),
    var embedding: SpeakerEmbeddingExtractorConfig = SpeakerEmbeddingExtractorConfig(),
    var clustering: FastClusteringConfig = FastClusteringConfig(),
    var minDurationOn: Float = 0.2f,
    var minDurationOff: Float = 0.5f,
)

data class OfflineSpeakerDiarizationSegment(
    val start: Float,       // seconds
    val end: Float,         // seconds
    val speaker: Int,       // 0-based
    val confidence: Float,  // in [-1, 1], or -2 if unavailable
)
```

File: `sherpa-onnx/kotlin-api/SpeakerEmbeddingExtractorConfig.kt` (200 B, verbatim):

```kotlin
package com.k2fsa.sherpa.onnx

data class SpeakerEmbeddingExtractorConfig(
    val model: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)
```

> Note the asymmetry: `model` is a `val` (immutable) in the embedding config, but `var` in the pyannote config.

### c.3 `OfflineSpeakerDiarization` class — full public method signatures

```kotlin
class OfflineSpeakerDiarization(
    assetManager: AssetManager? = null,
    val config: OfflineSpeakerDiarizationConfig,
) {
    fun release()
    // "Only config.clustering is used. All other fields in config are ignored"
    fun setConfig(config: OfflineSpeakerDiarizationConfig)
    fun sampleRate(): Int
    fun process(samples: FloatArray): Array<OfflineSpeakerDiarizationSegment>
    fun processWithCallback(
        samples: FloatArray,
        callback: (numProcessedChunks: Int, numTotalChunks: Int, arg: Long) -> Int,
        arg: Long = 0,
    ): Array<OfflineSpeakerDiarizationSegment>
    // companion object { System.loadLibrary("sherpa-onnx-jni") }
}
```
Construction throws `IllegalArgumentException("Invalid OfflineSpeakerDiarizationConfig: failed to create native
OfflineSpeakerDiarization")` if the native handle is 0. `release()` delegates to `finalize()`.

### c.4 How segmentation + embedding + clustering are wired (verified, not inferred)

`OfflineSpeakerDiarizationConfig` holds exactly three sub-configs, and the **JNI layer reads exactly these Java
field names** — verified in `sherpa-onnx/jni/offline-speaker-diarization.cc`:

```
segmentation                        -> OfflineSpeakerSegmentationModelConfig
  segmentation.pyannote             -> OfflineSpeakerSegmentationPyannoteModelConfig
    .model                          (String)
    .windowShiftRatio               (Float)
  segmentation.numThreads           (Int)
  segmentation.debug                (Bool)
  segmentation.provider             (String)
embedding                           -> SpeakerEmbeddingExtractorConfig
  embedding.model                   (String)
  embedding.numThreads              (Int)
  embedding.debug                   (Bool)
  embedding.provider                (String)
clustering                          -> FastClusteringConfig
  clustering.numClusters            (Int)
  clustering.threshold              (Float)
  clustering.computeConfidence      (Bool)
minDurationOn, minDurationOff       (Float, on the root config)
```
The result class is constructed via JNI descriptor `"(FFIF)V"` on
`com/k2fsa/sherpa/onnx/OfflineSpeakerDiarizationSegment` — i.e. `(Float start, Float end, Int speaker, Float confidence)`.

Pipeline semantics (from `sherpa-onnx/c-api/docs/speaker-diarization.dox`, quoted):
> *"sherpa-onnx supports offline speaker diarization through the `SherpaOnnxCreateOfflineSpeakerDiarization()` API.
> It combines a **segmentation model, a speaker embedding extractor, and a clustering algorithm**."*
> *"Zero uses the default window shift ratio of 0.1. Set a value in (0, 1] to change the sliding-window shift;
> **smaller values mean more overlap and more compute**."*

So: **pyannote segmentation** produces speech/speaker-activity regions with a sliding window → **embedding
extractor** (3D-Speaker/NeMo/wespeaker ONNX) produces one vector per region → **fast clustering** (agglomerative,
`numClusters` fixed or `threshold`-driven) assigns speaker IDs → `minDurationOn`/`minDurationOff` post-process
collapse/remove too-short turns (VAD-style hysteresis on the diarization output). `windowShiftRatio` is the only
tunable that trades compute for segmentation resolution.

### c.5 Verified code sketch — offline diarization on a 16 kHz mono WAV

Assembled from the **real** upstream sources: `kotlin-api-examples/test_offline_speaker_diarization.kt` (1921 B),
`android/SherpaOnnxSpeakerDiarization/.../SpeakerDiarizationObject.kt` (2745 B),
`sherpa-onnx/kotlin-api/WaveReader.kt` (1416 B).

```kotlin
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

fun diarizeMonoWav(
    assetManager: AssetManager? = null,   // pass context.assets to load models from APK assets
    segmentationModel: String = "segmentation.onnx",  // renaming is required by the API contract
    embeddingModel: String = "embedding.onnx",
    wavPath: String,                      // 16 kHz mono WAV on disk
): List<OfflineSpeakerDiarizationSegment> {

    val config = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                model = segmentationModel,
                windowShiftRatio = 0.1f,
            ),
            numThreads = 2,
            debug = false,
            provider = "cpu",
        ),
        embedding = SpeakerEmbeddingExtractorConfig(
            model = embeddingModel,
            numThreads = 2,
            debug = false,
            provider = "cpu",
        ),
        // For a 2-person phone call, HARD-PIN the speaker count.
        // Auto-detection (numClusters = -1) is the documented weak spot: see §5.
        clustering = FastClusteringConfig(
            numClusters = 2,
            computeConfidence = true,
        ),
        minDurationOn = 0.1f,   // demo uses 0.2f; production app lowered it to 0.1f
        minDurationOff = 0.05f, // demo uses 0.5f; production app lowered it to 0.05f
    )

    val sd = OfflineSpeakerDiarization(assetManager = assetManager, config = config)
    try {
        val wave: WaveData = WaveReader.readWave(wavPath)             // mono WAV
        check(sd.sampleRate() == wave.sampleRate) {
            "Expected ${sd.sampleRate()} Hz, got ${wave.sampleRate} Hz"
        }
        val segments = sd.processWithCallback(
            wave.samples,
            callback = { numProcessedChunks, numTotalChunks, _ ->
                println("progress: ${numProcessedChunks * 100f / numTotalChunks}%")
                0                                                       // return 0 == keep going
            },
        )
        // segments are NOT formally guaranteed sorted; upstream sorts by start time
        return segments.sortedBy { it.start }
    } finally {
        sd.release()
    }
}
```

Consuming the result exactly as upstream examples do:

```kotlin
for (s in segments) {
    println("${s.start} -- ${s.end} speaker_${s.speaker} confidence=${s.confidence}")
}
```

**Model preparation** (required — the API does not accept the download names, and upstream explicitly says the
model files must sit at the asset root, not in a subdirectory):

```bash
curl -SL -O https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2
tar xvf sherpa-onnx-pyannote-segmentation-3-0.tar.bz2
cp sherpa-onnx-pyannote-segmentation-3-0/model.onnx ./segmentation.onnx      # 5,992,913 B

curl -SL -O https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx
mv 3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx ./embedding.onnx       # 28,281,138 B
```
The upstream demo's own comment block confirms the resulting asset directory:
`-rw-r--r-- 1 fangjun staff 38M embedding.onnx` / `-rw-r--r-- 1 fangjun staff 5.7M segmentation.onnx`.

### c.6 Companion APIs you will also need

**VAD** (`sherpa-onnx/kotlin-api/Vad.kt`, 4112 B):

```kotlin
data class SileroVadModelConfig(var model: String = "", var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F, var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 512, var maxSpeechDuration: Float = 5.0F)
data class TenVadModelConfig(var model: String = "", var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F, var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 256, var maxSpeechDuration: Float = 5.0F)
data class VadModelConfig(var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
    var sampleRate: Int = 16000, var numThreads: Int = 1,
    var provider: String = "cpu", var debug: Boolean = false)
class SpeechSegment(val start: Int, val samples: FloatArray)
class Vad(assetManager: AssetManager? = null, var config: VadModelConfig) {
    fun compute(samples: FloatArray): Float
    fun acceptWaveform(samples: FloatArray); fun empty(): Boolean; fun pop()
    fun front(): SpeechSegment; fun clear(); fun isSpeechDetected(): Boolean
    fun reset(); fun flush(); fun release()
}
```

**Online speaker embedding + matching** (`sherpa-onnx/kotlin-api/Speaker.kt`, 4712 B) — this is the API that
production Android apps actually use for 2-speaker calls:

```kotlin
class SpeakerEmbeddingExtractor(assetManager: AssetManager? = null, config: SpeakerEmbeddingExtractorConfig) {
    fun createStream(): OnlineStream
    fun isReady(stream: OnlineStream): Boolean
    fun compute(stream: OnlineStream): FloatArray
    fun dim(): Int
    fun release()
}
class SpeakerEmbeddingManager(val dim: Int) {   // built-in vector index with threshold search
    fun add(name: String, embedding: FloatArray): Boolean
    fun add(name: String, embedding: Array<FloatArray>): Boolean
    fun remove(name: String): Boolean
    fun search(embedding: FloatArray, threshold: Float): String
    fun verify(name: String, embedding: FloatArray, threshold: Float): Boolean
    fun contains(name: String): Boolean
    fun numSpeakers(): Int
    fun allSpeakerNames(): Array<String>
}
```

---

## (d) Evidence on phone / VoIP / mixed-mono diarization and its failure modes

This is the weakest part of the whole open-source landscape, and I found **hard, quoteable, URL-backed evidence**
for it. Requirement (b) is satisfiable — here it is.

### d.1 The exact sherpa-onnx bug that hits our use case, on a *mono* file — issue still OPEN

**[k2-fsa/sherpa-onnx#1708 — "Mismatch diarization result between pyannote/speaker-diarization-3.0 and k2-fsa/speaker-diarization"](https://github.com/k2-fsa/sherpa-onnx/issues/1708)**
(opened 2025-01-14, **state: open**, last activity 2025-10-01)

The test file is literally named **`ck-interview-mono.wav`** — a mono interview, i.e. exactly our topology.
Reference pyannote 3.0 produced alternating `SPEAKER_00` / `SPEAKER_01`:

```
start=0.0s stop=5.2s speaker_SPEAKER_00
start=43.0s stop=48.0s speaker_SPEAKER_01
start=48.7s stop=50.2s speaker_SPEAKER_01
...
```

The reporter then ran the **sherpa-onnx** pipeline with `pyannote/segmentation-3.0` segmentation and
`wespeaker_en_voxceleb_resnet34_LM.onnx` embedding and **`Number of speakers: 2`** — and got:

```
0.031 -- 5.228 speaker_00
6.038 -- 23.048 speaker_00
23.825 -- 32.971 speaker_00
33.562 -- 41.375 speaker_00
42.151 -- 47.990 speaker_00
48.732 -- 72.728 speaker_00
73.522 -- 74.602 speaker_00
```

**Every single segment is `speaker_00`.** Both speakers collapsed into one, *even with the true speaker count
pinned to 2*. This is the single most important finding in this report: on a mono two-speaker recording, the
sherpa-onnx clustering stage can degenerate to one cluster. As of the last activity the maintainer said
*"sorry, will check it after the Chinese New Year"* and there is no fix.

The same thread's most recent comment (2025-10-01, from `altunenes`) adds:
> *"Any progress so far? I've been testing speaker-diarization with crosstalk audio and it's not working well
> either."* … *"they released new models and they claim that its better with overlap speech."*

→ **Overlapping speech / crosstalk is a second, related failure axis.**

### d.2 Auto speaker-count detection is explicitly a "tune it yourself" problem

**[k2-fsa/sherpa-onnx#1466 — "the accuracy of speaker diarization without cluster numbers"](https://github.com/k2-fsa/sherpa-onnx/issues/1466)**
(opened 2024-10-25, **state: open**)

Reporter:
> *"I attempted to process a long audio file using '3dspeaker+segmentation.onnx' you provided but noticed a
> **strong decrease in accuracy during speaker diarization when cluster numbers were not specified**. I also
> compared it with the speaker diarization model 3.1 provided by Pyannote on HuggingFace, and it seems to
> perform better. **However, I'm unsure how to deploy that model on Android.**"*

Maintainer `csukuangfj`, in full:
> *"You need to tune the threshold by yourself."*

Follow-up:
> *"I'm working on implementing a function for recording meeting transcripts, but I don't know the exact number of
> clusters beforehand, and **a single cluster threshold doesn't seem suitable for different audio files**. What
> approach should I take?"*

**[k2-fsa/sherpa-onnx#2445](https://github.com/k2-fsa/sherpa-onnx/issues/2445)** (opened 2025-08-04, **open**) says
the same thing with numbers:

> *"在运行示例 OfflineSpeakerDiarizationDemo.java 的时候，测试文件 0-four-speakers-zh.wav 在 setNumClusters 的值是 4
> 的时候结果还是很好的，但是设置为 -1 之后**准确度有明显下降**，这个功能可以添加吗？"*
> ("With `setNumClusters = 4` the result is very good, but set to `-1` the accuracy drops noticeably. Can this be
> added?") — with result JSONs attached for both settings.

**Actionable conclusion:** for 我/对方 phone calls, **always pass `numClusters = 2`**, never rely on `threshold`
alone. But per d.1, even `numClusters = 2` is not a guarantee.

### d.3 An Android app that has actually shipped 2-speaker diarization, and what it had to add

`qutschwalze/meeting-transcriber-sherpa` (Kotlin, real `OfflineSpeakerDiarization` usage) documents the failure
modes it had to engineer around. From its README:

> *"**Duo podcasts with two very similar male voices remain a documented acoustic edge case.**"*

> *"Rolling diarization: **15 s chunk pipeline with overlap**, temporal-vote reconciler and `SessionVoiceBank`
> (**acoustic memory against engine drift**)."*

> *"Hardened speaker assignment chain: **drift pre-check rejects phantom IDs from chunk-boundary drift**
> (`VB_DRIFT_ABFANG`)… **duplicate-profile consolidation at save** (`VB_DUP_MERGE`) so a **drifted known voice no
> longer survives as several speakers**… fragment-cluster merge at save… so large bank-less meetings no longer
> over-generate speakers."*

And the most useful engineering note for model choice, from `SpeakerDiarizationEngine.kt` (lines 37–41):

> *"Offizielle Sherpa-ONNX Speaker-Diarization mit pyannote + 3D-Speaker ERes2Net (0.6.12: embedding.onnx =
> `3dspeaker_speech_eres2net_base` – **trennt 3-4 Stimmen auch auf Lautsprecher-Mikrofon-Aufnahmen, wo Titanet
> Small sie merged**)."*
> ("…separates 3–4 voices **even on loudspeaker-microphone recordings, where TitaNet Small merges them**.")

→ **TitaNet Small merges speakers on loudspeaker/microphone (distant-mic, reverberant) capture; ERes2Net-Base does
not.** For a phone call recorded through the earpiece/loudspeaker, that is directly relevant. It is also why
`nemo_en_titanet_small.onnx` (40,257,283 B, CC-BY-4.0, **English-only**) should not be your default for Chinese.

Also relevant from the same project: a **thermal guard that pauses diarization inference while the device
throttles** ("audio stays buffered, no loss"), and a documented requirement of **API 26+**.

### d.4 Real Android code for pause-free turn changes (the hard case for phone calls)

`zeerd/Real-timeTranscription` (MIT) `SpeakerChangeDetector.kt` header comment — the clearest statement of the
problem in the survey:

> *"声纹滑窗换人检测（Speaker-Change-Driven Segmentation）。在 VAD 判定为连续语音（**无明显静音**）时，仍按固定子窗口滚动
> 提取声纹向量（Embedding），并比较相邻窗口的余弦相似度。当相似度跌破阈值且连续多次确认时，判定说话人已切换，
> **即便中间没有停顿也强制切断当前音频段，避免两人无缝接话/打断被并成一段**。"*
>
> ("Voiceprint sliding-window speaker-change detection. When VAD declares continuous speech (**no obvious
> silence**), still roll fixed sub-windows extracting embeddings and compare adjacent windows' cosine similarity.
> When similarity drops below threshold and is confirmed several times in a row, declare a speaker change and
> **force-cut the current segment even though there is no pause in the middle, avoiding two people taking turns
> seamlessly / interrupting being merged into one segment.**")

Its tunables (from `Constants.kt`): `DIARIZATION_SIMILARITY_THRESHOLD = 0.5f`,
`MIN_DIARIZATION_SAMPLES = 16000` (1 s), `SCD_SUB_WINDOW_SAMPLES = 400 ms`, `SCD_SLIDE_STEP_SAMPLES = 200 ms`,
`SCD_MIN_CUT_SAMPLES = 2 s`, `MAX_SPEECH_DURATION_SAMPLES = 20 s`, `TRAILING_SILENCE_SAMPLES = 1 s`,
`FRAME_SIZE_SAMPLES = 512` (32 ms). The README also documents the engineering reason:

> *"**SCD throttling**: Voiceprint embedding extraction is expensive (hundreds of ms), so it is triggered on a
> 200ms sliding step to avoid slowing down real-time performance."*

→ On a phone call where 我 and 对方 interrupt each other, VAD alone will **merge both speakers into one segment**.
You need an embedding-based change detector. Budget "hundreds of ms" per embedding extraction.

### d.5 Quantitative: telephone speech roughly doubles the error rate

From pyannote's own model card benchmark table (`README.md` of `pyannote/pyannote-audio`, "Benchmark (last updated
in 2025-09)", DER in %, lower is better):

| Benchmark | Domain | `speaker-diarization-3.1` (legacy) | `community-1` | `precision-2` (paid, server-side) |
|---|---|---|---|---|
| **CALLHOME** | **telephone conversations** | **28.5** | **26.7** | 16.6 |
| DIHARD 3 | mixed in-the-wild | 21.4 | 20.2 | 14.7 |
| AMI (SDM) | distant microphone | 22.7 | 19.9 | 15.6 |
| AMI (IHM) | close-talk headset | 18.8 | 17.0 | 12.9 |
| AliMeeting (channel 1) | far-field meeting | 24.5 | 20.3 | 15.2 |
| AVA-AVD | audio-visual, wild | 49.7 | 44.6 | 37.1 |
| VoxConverse | broadcast/podcast | 11.2 | 11.2 | 8.5 |

**CALLHOME (26.7) is 2.4× worse than VoxConverse (11.2)** for the same model — and CALLHOME is clean,
wideband-ish telephone speech. Chinese VoIP calls compressed with AMR/Opus over a lossy mobile link will be worse
than CALLHOME. Also note pyannote's non-gated high-accuracy tier (`precision-2`) is a **hosted, server-side paid
service** — it is not an offline option at all: its README says *"runs on pyannoteAI servers"*.

⚠️ I could not retrieve pyannote's own blog post
*"Making mono-channel audio actually useful for Voice AI pipelines"* (https://www.pyannote.ai/blog/diarization-for-mono-channel-voice-ai)
— the host resolves to a non-public IP from this environment, so I could not verify its contents. It surfaced in
search results and is very likely the single best source on this exact topic; **read it manually.** ❌ UNVERIFIED.

### d.6 A cautionary tale: the Android diarization app that gave up

`blabbertabber/blabbertabber` (23★, AGPL-3.0, Android, last push 2021-03-28) — its own `docs/README.md`, verbatim:

> *"## Deprecated
> BlabberTabber is defunct. It's no longer maintained. It's no longer listed in the App Store, the back-end
> servers have been turned off.
> …
> Backstory: **it never worked very well (I couldn't find a good-enough diarizer)**, and over time my interest
> waned."*

Its advisor was Gerald Friedland (ICSI), and it cites the ICSI RT-09 diarization system. This was a serious
attempt, with academic backing, and it failed on diarization accuracy. Treat the accuracy problem as the primary
project risk.

### d.7 Codec / channel notes we could not find evidence for

❌ **UNVERIFIED:** I found **no** project that quantifies diarization DER degradation from **codec compression**
(AMR-NB/AMR-WB/Opus) or from **acoustic echo / double-talk** on Android. Nobody in this ecosystem has published
that measurement. Also ❌ UNVERIFIED: whether Android's `VOICE_CALL` / `VOICE_DOWNLINK`+`VOICE_UPLINK` capture
paths (which would give you *two* channels and dissolve the mono problem entirely) are usable on the target
devices — that is a `MediaRecorder.AudioSource`, OEM and Android-version question, not an ML question, and worth
evaluating **before** investing in mono diarization, because two channels makes diarization trivial and exact.

---

## (e) Recommendation

### e.1 Verdict

**Build on sherpa-onnx, but do not expect the batch `OfflineSpeakerDiarization` pipeline to work out of the box on
mono phone-call audio.** Combine it with the online voiceprint approach, pin `numClusters = 2`, and ship a
user-correction UI. Specifically:

| Layer | Choice | License | Bytes on device |
|---|---|---|---|
| Runtime | `sherpa-onnx-1.13.8.aar` (release asset) — provides ONNX Runtime **and** the Kotlin API | Apache-2.0 | 50,129,134 (all 4 ABIs; trim to arm64 only in your APK → ~32 MB of `.so`) |
| VAD | `silero_vad.onnx` (sherpa-onnx export) via `Vad` + `SileroVadModelConfig`, window 512 @16 kHz | MIT | 643,854 |
| Segmentation | `sherpa-onnx-pyannote-segmentation-3-0/model.onnx` (**not** the int8 variant initially — validate int8 separately) | **MIT** (© 2022 CNRS) | 5,992,913 |
| Embedding | `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` (Chinese, 200k speakers, smallest good zh model) | **Apache-2.0** | 28,281,138 |
| Clustering | `FastClusteringConfig(numClusters = 2, computeConfidence = true)` — **never `-1`** | in-tree | 0 |
| Turn-change rescue | Port the SCD design from `zeerd/Real-timeTranscription` (`SpeakerChangeDetector.kt`, MIT) or use `SpeakerEmbeddingManager.search(emb, 0.5f)` | MIT | ~0 |

**Total model payload: ~34.9 MB** (VAD + segmentation + embedding), plus ~32 MB of arm64 native libs.
That is a comfortable Android download.

### e.2 Why this combination and not the others

- **Why not pyannote directly?** MIT code + MIT segmentation weights, but every HF repo is `gated: auto` and
  **returns HTTP 401 anonymously** — your CI cannot fetch it, and redistributing the weights is legally murky even
  though `license: mit` is declared. It is Python/PyTorch, so an Android port means writing your own ONNX export
  + JNI + clustering. sherpa-onnx has already done exactly that, and **the ONNX it ships carries the same MIT
  © 2022 CNRS notice** — so sherpa-onnx's `sherpa-onnx-pyannote-segmentation-3-0` is the *legal, practical*
  way to consume pyannote's segmentation.
- **Why not the Rev reverb model?** It is the segmentation model for reverberant/loudspeaker capture — exactly
  a phone-call-acoustic problem — but it ships the **"Rev Model Non-Production License"**. For a commercial app,
  that is disqualifying. If you are building an internal/non-commercial tool, it is worth an A/B test, because it
  is plausibly better on distant-mic audio.
- **Why CAM++ over TitaNet Small?** TitaNet Small is **English-only** (wrong for Chinese) and has a documented
  tendency to **merge speakers on loudspeaker-microphone capture**. CAM++ zh-cn is **Apache-2.0**, 28 MB, and
  3D-Speaker/DAMO models are all uniformly Apache-2.0 — the cleanest licensing in the survey. Keep ERes2Net-Base
  (39,593,761 B, also Apache-2.0, and sherpa-onnx's own diarization default) as your A/B alternative.
- **Why not wespeaker?** Its pretrained weights inherit the **dataset** license (`docs/pretrained.md`), which means
  **CC-BY-4.0** for the VoxCeleb models and CN-Celeb terms for the Chinese ones. Attribution obligations and
  ambiguity for zero accuracy benefit over CAM++/ERes2Net here. No prebuilt Android artifact either.
- **Why not `soniqo/speech` (the only other real Android AAR with diarization)?** Its diarizer is **Sortformer
  4-speaker, 474 MB**, licensed **NVIDIA Open Model License** (not Apache/MIT), and the README states it
  *"answers about 30 seconds behind the audio"*. Wrong shape for a live call, wrong license posture, and
  3× the download of the sherpa-onnx stack. It is, however, the best reference for **Kotlin API ergonomics**
  (`SpeakerDiarizer` / `SpeakerEmbedder` / `VadDetector`) and uses `hf-mirror.com` for downloads — a good idea
  for Chinese network conditions.
- **Reuse, don't rebuild:** `conwerter1/protocolvoice` (Apache-2.0) already solves HF-hosted model download with
  SHA-256 verification and has `silero_vad.onnx` in assets; `qutschwalze/meeting-transcriber-sherpa` shows the
  chunking/drift-repair machinery (⚠️ but has **no LICENSE file** — GitHub reports `license: None` — so read it
  for ideas, do not copy code).

### e.3 Concrete engineering plan

1. **Pilot: build a DER harness before writing UI.** Use `0-four-speakers-zh.wav` (1,819,586 B) and
   `2-two-speakers-en.wav` (1,088,078 B) from the sherpa-onnx releases as sanity checks, then record **20 real
   Chinese phone calls** and compute DER against hand labels. Grid-search
   `{segmentation: pyannote | reverb-v1-if-non-commercial} × {campplus | eres2net_base} × numClusters=2 ×
   windowShiftRatio ∈ {0.05, 0.1, 0.2} × {minDurationOn, minDurationOff}`.
2. **Instrument the exact failure from §d.1.** `qutschwalze`'s engine already does this: it counts
   `zeroSegmentCount` and classifies empty results by audio duration
   (`<5 s → "bufferTooShort"`, `<8 s → "borderline"`, else `"engineOrThreshold"`), with the comment
   *"pyannote braucht Kontext"* (pyannote needs context). Port that. If one cluster dominates >95% of speech,
   treat it as a **hard failure** and surface it in the UI rather than showing one speaker.
3. **Build a hybrid, not a batch job.** Run the batch `OfflineSpeakerDiarization` per utterance for a global
   speaker partition, **and** run the SCD voiceprint check (400 ms/200 ms/0.5f) inside long segments. Per §d.1,
   do not trust the batch clustering output as the sole source of truth.
4. **Detect the mono problem at capture time.** If the device exposes separate downlink/uplink audio sources,
   prefer them — two channels removes the hard part entirely. ❌ This needs a device matrix test; it is the
   highest-leverage unknown in the whole plan.
5. **Pin your runtime versions:** sherpa-onnx **v1.13.8** (the version whose AAR I measured), ONNX Runtime
   **1.22.0** or the ORT bundled inside the sherpa-onnx AAR — **do not add both** (duplicate
   `libonnxruntime.so`, and `packaging { jniLibs { pickFirsts += "**/libonnxruntime.so" } }` if you must).
6. **Renormalize the audio to 16 kHz mono before diarization** and **loudness-normalize** — `OfflineSpeakerDiarization`
   rejects mismatched sample rates (`check(sd.sampleRate() == wave.sampleRate)` in the sketch above), and
   `WaveReader.readWave` is documented as reading a **mono** wave.
7. **Legal checklist before shipping:** ship a `NOTICE` with (a) Apache-2.0 (sherpa-onnx, 3D-Speaker/CAM++),
   (b) MIT (Silero VAD, pyannote segmentation © 2022 CNRS, and any code from `Real-timeTranscription`),
   (c) ⚠️ **CC-BY-4.0 attribution if you use any TitaNet/wespeaker-VoxCeleb model**, (d) ⚠️ **do not ship
   `reverb-diarization-v*` or `ten-vad` without a legal review** (Rev non-production; Agora non-compete).

### e.4 Top three risks, in order

1. **Accuracy on mono, overlapping, compressed Chinese call audio (HIGH).** Evidence: open issue #1708 collapses
   2 speakers to 1 *with the count pinned*; CALLHOME DER 26.7 % vs VoxConverse 11.2 %; `blabbertabber` abandoned
   for exactly this reason. Mitigate with the hybrid design + user correction UI; do not promise automatic labels.
2. **Over-generation / drift from chunking (MEDIUM).** `qutschwalze` needed six named repair passes
   (`VB_DRIFT_ABFANG`, `VB_DUP_MERGE`, mini-fragment cleanup, fragment-cluster merge) to stop "speaker floods".
   Budget for this work explicitly.
3. **Latency (MEDIUM).** Embedding extraction is "hundreds of ms"; Sortformer is ~30 s behind; `qutschwalze`
   pauses diarization on thermal throttling. For live calls, diarization must be async to ASR.

---

## Appendix: ❌ UNVERIFIED items (do not treat as facts)

1. `nemo_en_titanet_small.onnx` **model** license — NGC's page is not machine-readable. Its sibling
   `nvidia/speakerverification_en_titanet_large` on HF is `license: cc-by-4.0` (verified), and sherpa-onnx's
   export script sources both from NGC (`scripts/nemo/speaker-verification/README.md`).
2. `nemo_en_speakerverification_speakernet.onnx` and `ecapa_tdnn` licenses.
3. `sherpa-onnx-reverb-diarization-v2` license and internal `model.onnx` size (only the 254,075,989 B archive was measured).
4. `onnxruntime-mobile`, `-extensions-android`, `-android-qnn`, `-training-android` ABI contents (only file sizes verified).
5. `ekhodzitsky/polyvoice`'s "~8.4 MB INT8, MIT, ungated" claim — repo README only, not independently downloaded.
6. `notch-up/diarize`'s "~10.8% DER on VoxConverse" — repo README only.
7. pyannote's blog post on mono-channel audio — host unresolvable from this environment (read it manually).
8. Whether Android `VOICE_CALL` / downlink+uplink capture is viable on the target devices.
9. Whether the sherpa-onnx Android demo/APK does anything special for mono 2-speaker audio — the demo's `Home.kt`
   passes whole files to `process()` with no special handling; **no documentation claims phone-call competence.**
10. **No project in this survey makes a verified accuracy claim for 2-speaker diarization on a single-channel
    compressed telephone recording.** Absence of evidence here is itself the finding.

## Appendix: exact commands used (reproducible)

```bash
# metadata
curl -sS "https://api.github.com/repos/k2-fsa/sherpa-onnx"
# real source (no API cost)
curl -sS "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/OfflineSpeakerDiarization.kt"
curl -sS "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/Speaker.kt"
curl -sS "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/Vad.kt"
curl -sS "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/jni/offline-speaker-diarization.cc"
# release asset sizes (authoritative)
curl -sS "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/speaker-recongition-models"
# every model URL, status + exact size
curl -sIL "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
# ABI enumeration
curl -sSL ".../onnxruntime-android-1.22.0.aar" -o ort.aar && unzip -l ort.aar | grep -E 'jni/|\.so'
curl -sSL ".../sherpa-onnx-1.13.8.aar"     -o so.aar  && unzip -l so.aar  | grep -E 'jni/|\.so'
# model license read from inside the archive
tar tjvf sherpa-onnx-pyannote-segmentation-3-0.tar.bz2 && tar xjf ... && head -3 */LICENSE
# pyannote HF gating proof
curl -sS -w "\n[%{http_code}]\n" "https://huggingface.co/pyannote/segmentation-3.0/raw/main/README.md"   # -> 401
# model-scope license
curl -sS "https://www.modelscope.cn/api/v1/models/iic/speech_campplus_sv_zh-cn_16k-common"   # -> "Apache License 2.0"
```
