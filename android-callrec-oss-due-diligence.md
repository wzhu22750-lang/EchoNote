# Android Call-Recording / System-Audio-Capture OSS Due Diligence

**Prepared:** late September 2026 · **Method:** `git clone --depth 1` + local source reading, GitHub REST API v3 (unauthenticated), AOSP source via `raw.githubusercontent.com/aosp-mirror/*` and `android.googlesource.com`, `developer.android.com` via `curl`.

**Evidence discipline:** every star count, fork count, commit date and license below was read from the GitHub API or from the repository's own `LICENSE` file. Every capture claim was read from the source. Anything I could not verify from code or a primary source is marked **UNVERIFIED**.

---

## 0. Headline findings (read this first)

1. **No project in this entire survey contains any code comment, doc, or issue that claims VERIFIED WeChat (微信) call audio capture.** Exactly one repo mentions WeChat as a *target*, one as *marketing*, and one requests it as an *open feature request*. Details and verbatim quotes in §3.
2. **`k2-fsa/sherpa-onnx` Android examples contain zero call/system-audio capture code.** All 14 Android examples hardcode `MediaRecorder.AudioSource.MIC`. It is an ASR/VAD/TTS engine library, not a capture library. Verified by reading every Android example.
3. **A normal app cannot capture the far end of a VoIP call on any Android version from 10 through 16.** The platform provides no API. This is confirmed by AOSP javadoc, by the `developer.android.com` capture guide, by four independent projects' READMEs, and by Google's own 2020 revert commit.
4. **The only working paths require shell-level privilege** (UID 2000 via Shizuku or a self-paired ADB bridge) **or root.** Two distinct techniques exist: `VOICE_CALL`/`VOICE_UPLINK`/`VOICE_DOWNLINK` `AudioRecord` as shell, and a **dynamic `AudioPolicy` + `AudioMix` loopback-render sink matching `USAGE_VOICE_COMMUNICATION`** (CallVault — the most advanced public implementation).
5. **License landmine:** the four most capable call recorders are all **GPL-3.0**. If you want closed-source modules, you must not copy their code. The permissive options are materially less capable.

### Ranked shortlist for a WeChat-call-audio goal

| Rank | Project | Why | License risk |
|---|---|---|---|
| 1 | **madkongo/CallVault** | The only public codebase that actually attacks third-party-app (VoIP) far-end capture, with a documented `AudioPolicy`/`AudioMix` loopback-render technique and honest device-support docs. | 🔴 GPL-3.0 + §7 additional terms |
| 2 | **jemcik/JemRec** | Highest-quality *architecture* for the privileged layer: self-contained ADB-over-loopback, typed FGS, self-test UI, 55 unit tests, Apache-2.0. | 🟢 Apache-2.0 |
| 3 | **kitsumed/ShizuCallRecorder** | Clean reference for Shizuku → `app_process` → bundled scrcpy-server → `VOICE_CALL`. | 🔴 GPL-3.0 |
| 4 | **LyoSU/cally** | Best-documented explanation of the shell-attribution bypass and the `VOICE_*` fallback ladder in the field. | 🔴 GPL-3.0 |
| 5 | **k2-fsa/sherpa-onnx** | Not a capture project, but the best permissive ASR/VAD/diarization Android integration. | 🟢 Apache-2.0 |
| 6 | **Genymobile/scrcpy** (referenced throughout, not in the original brief) | The upstream Apache-2.0 origin of the `Workarounds.createAudioRecord` hidden-API bypass that every one of these projects ports. **Best permissive source for the privileged-capture primitive.** | 🟢 Apache-2.0 |

---

## 1. Comparison table

Legend: ✅ yes · ❌ no · ⚠️ partial/experimental/device-dependent · — not applicable · **?** UNVERIFIED.
Permissions column lists only the *unusual* ones; `RECORD_AUDIO` is listed only where it is notably absent.

| Project | URL | Stars | Last commit | License | Lang | minSdk | Capture method | Background | Call rec | VoIP | 3rd-party audio | Root | Shizuku | Special perms | DB | Export |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **ShizuCallRecorder** | [kitsumed/ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) | 1632 | 2026-08-28 | **GPL-3.0** | Kotlin | 30 (target 36) | Shizuku → shell `app_process` → bundled scrcpy-server 3.3.4 → `AudioRecord` **`VOICE_CALL`** (default) | ⚠️ event-driven, no persistent proc | ✅ | ❌ | ❌ | ❌ | ✅ **required** | no `RECORD_AUDIO`; `MANAGE_ONGOING_CALLS`, `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`, `READ_CALL_LOG` | none (SharedPrefs) | share |
| **CallVault** | [madkongo/CallVault](https://github.com/madkongo/CallVault) | 79 | 2026-09-29 | **GPL-3.0 + §7** | Kotlin | 30 (target 36) | Embedded self-ADB **or** Shizuku → shell; carrier = scrcpy-derived `VOICE_*`; **VoIP = dynamic `AudioPolicy`+`AudioMix(USAGE_VOICE_COMMUNICATION, ROUTE_FLAG_LOOP_BACK_RENDER)` far + `MIC` near** | ✅ FGS | ✅ | ⚠️ experimental, per-app picker | ⚠️ (this *is* the VoIP path) | ❌ | ⚠️ optional | `WRITE_SECURE_SETTINGS`, `REQUEST_INSTALL_PACKAGES`, `READ_CALL_LOG`; no `RECORD_AUDIO` | **Room** | ⚠️ transcripts (txt/MD/SRT/VTT/JSON), BCR metadata, optional Drive |
| **JemRec** | [jemcik/JemRec](https://github.com/jemcik/JemRec) | 6 | 2026-09-12 | 🟢 **Apache-2.0** | Kotlin | 31 (target 36) | Embedded ADB client pairs to the phone's **own** wireless debugging on `127.0.0.1` → `pm grant` → scrcpy-derived `app_process` shellserver → `AudioDirectCapture` **`VOICE_CALL`** | ✅ FGS specialUse | ✅ (3 devices) | ❌ | ❌ | ❌ | ❌ | ADB **yes** (self); `WRITE_SECURE_SETTINGS`, `READ_CALL_LOG` | none (files + Prefs) | ✅ share |
| **cally** | [LyoSU/cally](https://github.com/LyoSU/cally) | 36 | 2026-08-15 | **GPL-3.0** | Kotlin | 31 (target 36) | Shizuku UserService (`app_process`, UID 2000) + `WrappedShellContext` attribution spoof + `AudioRecord.Builder().setContext()` on main Looper; **5-step fallback ladder** `VOICE_UPLINK/DOWNLINK` → `MIC+VOICE_DOWNLINK` → `VOICE_CALL` stereo → `VOICE_CALL` mono → `MIC` | ✅ FGS + 1×1 overlay | ✅ | ❌ | ❌ | ❌ | ✅ **required** | `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `READ_CALL_LOG` | **Room** | ✅ share |
| **sherpa-onnx** | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | 15043 | 2026-09-22 | 🟢 **Apache-2.0** | C++ (Android ex. Kotlin/Java) | per-example **?** | **`MediaRecorder.AudioSource.MIC` only — every example** | per-example | ❌ | ❌ | ❌ | ❌ | ❌ | `RECORD_AUDIO` | none in examples | — |
| **Real-timeTranscription** | [zeerd/Real-timeTranscription](https://github.com/zeerd/Real-timeTranscription) | 4 | 2026-07-21 | 🟢 **MIT** | Kotlin | 26 (target 35) | `AudioRecord`, user-selectable `MIC` / `CAMCORDER` / `VOICE_RECOGNITION` / `UNPROCESSED`; 16 kHz mono, 512-sample frames | ✅ FGS microphone | ❌ | ❌ | ❌ | ❌ | ❌ | `RECORD_AUDIO` | none (files) | ✅ SAF save-dir |
| **Daedalus Echo** | [DaedalusApps/daedalus-echo](https://github.com/DaedalusApps/daedalus-echo) | 5 | 2026-08-25 | 🟢 **MIT** | Kotlin | 26 (target 35) | `MediaRecorder` **`MIC`** (+ Bluetooth SCO) | ✅ FGS dataSync | ❌ | ❌ | ❌ | ❌ | ❌ | `RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS` | **Room v8** + FTS4 | ⚠️ Markdown share |
| **OpenWhisprAndroid** | [timgras2/OpenWhisprAndroid](https://github.com/timgras2/OpenWhisprAndroid) | 0 | 2026-02-26 | 🔴 **NO LICENSE FILE** | Kotlin | 26 (target 35) | `MediaRecorder` `MIC` → **Groq cloud API** (not offline) | ❌ no FGS | ❌ | ❌ | ❌ | ❌ | ❌ | `RECORD_AUDIO`, `INTERNET` | none (DataStore) | ❌ none found |
| **AudioRecorder** (AudioPlaybackCapture sample) | [myfreax/AudioRecorder](https://github.com/myfreax/AudioRecorder) | 5 | 2022-03-03 | 🔴 **NO LICENSE FILE** | Kotlin | 29 (target 31) | `AudioPlaybackCaptureConfiguration` + `MediaProjection`; `addMatchingUsage(MEDIA, GAME, UNKNOWN)`; PCM 44.1 kHz mono | ⚠️ FGS, no type | ❌ | ❌ | ⚠️ non-VOICE only | ❌ | ❌ | `RECORD_AUDIO` | none | ❌ raw PCM via `adb pull` |
| **RecordingAudio** | [sunilxsk/RecordingAudio](https://github.com/sunilxsk/RecordingAudio) | 1 | 2026-09-17 | 🟢 **Apache-2.0** | Kotlin | 29 (target 35) | `AudioPlaybackCapture` + `MediaProjection`, per-usage **and per-app** selection, **dual-thread mic mix** (float-domain), FFmpeg encode, silence detection, overlay panel | ✅ FGS | ❌ | ❌ | ⚠️ non-VOICE only | ❌ | ❌ | `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, `MANAGE_EXTERNAL_STORAGE` | none | ❌ none found |
| **android-internal-audio-recorder** | [Eyeing0721/android-internal-audio-recorder](https://github.com/Eyeing0721/android-internal-audio-recorder) | 0 | 2026-09-14 | 🟢 **MIT** | Java | 29 (target 35) | `AudioPlaybackCapture` + `MediaProjection` (`MEDIA/GAME/UNKNOWN/ASSISTANCE_NAVIGATION_GUIDANCE`) + optional `MIC`; WAV | ✅ FGS, **one-time consent retained** | ❌ | ❌ | ⚠️ non-VOICE only | ❌ | ❌ | `RECORD_AUDIO` | none | ❌ `adb pull` |
| **internal_audio** | [arrayforward/internal_audio](https://github.com/arrayforward/internal_audio) | 0 | 2026-07-24 | 🟢 **Apache-2.0** | Kotlin | 29 (target **29**, compile 34) | `AudioPlaybackCapture` + `MediaProjection` (`MEDIA/GAME/UNKNOWN`), 48 kHz/16-bit stereo, WAV + M4A, wave editor, PESQ | ✅ FGS | ❌ | ❌ | ⚠️ non-VOICE only | ❌ | ❌ | `RECORD_AUDIO`, `MANAGE_EXTERNAL_STORAGE` | none | ⚠️ saves to `/sdcard/Music/audio/` |
| **mob_audio_capture** | [GenericJam/mob_audio_capture](https://github.com/GenericJam/mob_audio_capture) | 0 | 2026-07-12 | 🔴 **NO LICENSE FILE** | **Elixir** (+Kotlin/Zig bridge) | n/a (plugin) | `MediaProjection` + `AudioPlaybackCapture` (`MEDIA/GAME/UNKNOWN`); **output-level meter only** | ❌ | ❌ | ❌ | ⚠️ non-VOICE only | ❌ | ❌ | n/a | none | — |
| **universal-android-call-recorder** | [jagobandhusome/…whatsapp](https://github.com/jagobandhusome/universal-android-call-recorder-including-calling-apps-like-whatsapp) | 0 | 2026-09-13 | **GPL-3.0** | Kotlin | 26 (target 36) | `MediaRecorder` `MIC`/`VOICE_COMMUNICATION`/`VOICE_CALL`/`VOICE_RECOGNITION` + `MediaProjection`/`AudioPlaybackCapture` with `addMatchingUsage(USAGE_VOICE_COMMUNICATION)` **← ineffective by platform rules** | ✅ FGS | ⚠️ | ❌ (README admits it) | ❌ | ❌ | ❌ | large set incl. `PACKAGE_USAGE_STATS`, `READ_PHONE_NUMBERS` | none found | ✅ share/PDF/ZIP vault |
| **opencall-recorder** | [AnasAyyad/opencall-recorder](https://github.com/AnasAyyad/opencall-recorder) | 0 | 2026-09-16 | 🟢 **Apache-2.0** | Java (no Gradle) | 31 (target 36) | Embedded `libadb` client pairs to `127.0.0.1` wireless debugging (SPAKE2) → `app_process` as UID 2000 → `AudioRecord.Builder().setContext()`; mixes to mono M4A/AAC | ✅ FGS specialUse | ⚠️ beta, HONOR-centric | ❌ | ❌ | ❌ | ❌ | ADB **yes** (self); `WRITE_SECURE_SETTINGS` | none | ⚠️ `Internal storage/Sounds/CallRecord` |
| **CallRecordingFix** | [boldbeastsoft/CallRecordingFix](https://github.com/boldbeastsoft/CallRecordingFix) | 34 | 2021-02-19 | 🔴 **NO LICENSE FILE** | **Shell** (Magisk) | n/a | Magisk module; **actual audio path lives in two closed-source paid apps** | — | ⚠️ | ⚠️ *claims* | ⚠️ | ✅ **Magisk** | ❌ | Magisk | — | — |
| **cordova-plugin-callrecorder-accessibility** | [developertl-tl/…](https://github.com/developertl-tl/cordova-plugin-callrecorder-accessibility) | 0 | 2025-11-06 | 🔴 **NO LICENSE FILE** | Java (Cordova) | **?** | `AudioRecord` with **`VOICE_COMMUNICATION`** on SDK ≥ 21 (falls back to `MIC`); `VOICE_CALL` only for SDK < 21; **AccessibilityService** + foreground service | ✅ | ⚠️ **?** | ❌ | ❌ | ❌ | ❌ | accessibility binding | none | ❌ none |

> Read the license column literally: **"NO LICENSE FILE" means all rights reserved.** Under default copyright, you may not copy, modify, or redistribute that code at all. Do not treat a missing license as "probably MIT".

---

## 2. Per-project detail

### 2.1 `kitsumed/ShizuCallRecorder` — 1632★ · GPL-3.0

- **URL:** <https://github.com/kitsumed/ShizuCallRecorder>
- **Metadata (`curl -sS https://api.github.com/repos/kitsumed/ShizuCallRecorder`):** 1632★, 61 forks, 17 open issues, `pushed_at` 2026-09-27, `license.spdx_id = GPL-3.0`, language Kotlin, created 2026-05-14.
- **Last commit (`git log -1`):** `2026-08-28 | chore: Update CodeQL workflow to handle pull request conditions`
- **License:** `LICENSE` begins `GNU GENERAL PUBLIC LICENSE / Version 3, 29 June 2007` → **GPL-3.0**.
- **Stack:** Kotlin, **Jetpack Compose**, minSdk 30 / target+compile 36, version 1.3.3.
- **Capture mechanism — read from source, not guessed:**
  - `app/src/main/java/com/kitsumed/shizucallrecorder/data/AppPreferences.kt:69` → `val AUDIO_SOURCE = ScrcpyAudioSource.VOICE_CALL.cliKey` — **default is `VOICE_CALL`**.
  - `integrations/scrcpy/ScrcpyConfig.kt:20-21` → *"What is scrcpy-server? It runs with `app_process`, JVM launch it with the shell user (UID 2000) and provides audio capture"*.
  - `integrations/scrcpy/ScrcpyConfig.kt:104` → the launch line is `app_process / com.genymobile.scrcpy.Server 3.3.4 …`.
  - `integrations/scrcpy/ServerExtractor.kt:22` → extracts `scrcpy-server.jar` from APK assets, SHA-256 verified.
  - `integrations/scrcpy/ScrcpyAudioSource.kt:54,69,82,95,105` → selectable sources: `VOICE_COMMUNICATION`, `VOICE_CALL`, `VOICE_CALL_UPLINK`, `VOICE_CALL_DOWNLINK`, `OUTPUT` (`REMOTE_SUBMIX`).
- **Notable:** the manifest declares **no `RECORD_AUDIO` at all** — mic access happens inside the shell-UID process. Declares `MANAGE_ONGOING_CALLS`, `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`, `READ_CALL_LOG`, `READ_CONTACTS`, `READ_PHONE_STATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `FOREGROUND_SERVICE`+`_DATA_SYNC`+`_SPECIAL_USE`.
- **Storage/DB/export:** no database; `data/AppPreferences.kt` (SharedPreferences). Opus or AAC output. Share from `ui/screens/SettingsScreen.kt`.
- **Activity:** very active — releases v1.2.0 (2026-07-13), v1.3.0, v1.3.1, v1.3.2 (2026-08-03), v1.3.3 (2026-08-12). On F-Droid + IzzyOnDroid.
- **⚠️ Third-party / VoIP:** README states plainly: *"This application is intended to be a very basic call recorder that focuses solely on call recording for phone carriers."* and *"I am not 100% opposed to adding support for third-party apps, but this is not the main focus."*
  - **WeChat evidence — documented as NOT working / not supported:**
    - Issue <https://github.com/kitsumed/ShizuCallRecorder/issues/108> — `[Feature]: Add call recording for third-party applications`, opened 2026-08-16, **closed, 0 comments**. Body verbatim: *"Currently, none of the chat applications have a built-in call recorder."* and *"Add a checklist to the application settings so users can select the applications they want to record calls for, such as Telegram, WhatsApp, Line, **WeChat**."*
    - Repo-wide `grep -rniE "wechat|微信|weixin"`: **only that issue** (via `curl -sS "https://api.github.com/search/issues?q=repo:kitsumed/ShizuCallRecorder+wechat"` → `total_count: 1`).
  - **Classification: "documented as NOT working" for WeChat; "never mentioned" in the code.**
- **Its `docs/configuration.md:9-14` is the single best policy-history summary I found**, citing: <https://issuetracker.google.com/issues/37127141>, <https://issuetracker.google.com/issues/137210607#comment8>, <https://issuetracker.google.com/issues/128677410>, <https://issuetracker.google.com/issues/112602629>, <https://issuetracker.google.com/issues/158923887>, and the AOSP revert commit <https://android.googlesource.com/platform/frameworks/av/+/57a3769fb12dc8b2df457e0919850bd976716706>.
- **Reusable:** the Shizuku→`app_process`→scrcpy-server wiring, the SHA-pinned server extraction, and the `ScrcpyAudioSource` enum. **Not reusable:** everything, if you need a non-GPL module — GPL-3.0 is viral. Go to scrcpy itself (Apache-2.0) for the underlying server.

### 2.2 `madkongo/CallVault` — 79★ · GPL-3.0 + §7 — **the most relevant project**

- **URL:** <https://github.com/madkongo/CallVault>
- **Metadata:** 79★, 11 forks, 12 open issues, `pushed_at` 2026-09-29, `license.spdx_id = GPL-3.0`, Kotlin, created 2026-06-11.
- **Last commit:** `2026-09-29 | release: 2.4.4 (20450) — cut-off rescue, pair-again, update popup + faster checks, Android-version gating, vivo crash fix; SUPPORT doc for vivo app calls`
- **License — read carefully, it is NOT plain GPL-3.0:** every source header reads
  > `This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.`
  Section 7 additional terms can impose extra attribution/notice obligations. `LICENSE` file is the GPL-3.0 text; `NOTICE.md` records the ShizuCallRecorder fork lineage.
- **It is a fork of ShizuCallRecorder** (README: *"CallVault is a fork of ShizuCallRecorder (Copyright © kitsumed (Med)), re-architected to run self-contained over embedded ADB"*).
- **Stack:** Kotlin, **Jetpack Compose**, minSdk 30 / target+compile 36, **arm64 only**, v2.4.4. **Room** (transcripts).
- **Two privilege modes (README table):** *Built-in* — pairs once on the phone over embedded ADB, no PC, no other app; *Shizuku* — use an existing Shizuku install.
- **Carrier-call capture:** scrcpy-derived, shell UID, `VOICE_CALL` / `VOICE_UPLINK` / `VOICE_DOWNLINK` / `REMOTE_SUBMIX` (`grep` over `server/*.kt` and `integrations/scrcpy/`).
- **VoIP / third-party-app capture — the core technique, verbatim from `app/src/main/java/com/baba/callvault/server/VoipAudioPolicy.kt:19-39`:**
  > *"Taps the FAR PARTY of a VoIP call by registering a dynamic audio policy.*
  > *A VoIP app renders the remote voice as an ordinary playback track tagged `USAGE_VOICE_COMMUNICATION`. Registering an `android.media.audiopolicy.AudioMix` that matches that usage, routed `ROUTE_FLAG_LOOP_BACK_RENDER`, duplicates the stream into a submix we can read — while it keeps playing normally, so the user still hears the call. (Plain `ROUTE_FLAG_LOOP_BACK`, i.e. capturing `REMOTE_SUBMIX`, makes the mix the PRIMARY output instead and the device goes silent — which is why every previous attempt at VoIP capture through the submix was abandoned as unusable.)*
  > ***ARM EARLY.** Android fixes a track's routing when the track is CREATED, so the policy must already be registered before the call's audio track exists. Registering mid-call does not merely clip the start — the call is never attached to the mix and the whole recording is silent."*
  - `VoipAudioPolicy.kt:58-61` — the real gate:
    > *"Returns false when the device or its OEM build does not permit it — most often because `com.android.shell` lacks `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, which is the real gate (the builder's own `voiceCommunicationCaptureAllowed` flag is advisory and gets overwritten by the framework from that permission)."*
  - `VoipAudioPolicy.kt:189` — the sink is `capturePreset = MediaRecorder.AudioSource.REMOTE_SUBMIX`, built via the hidden `AudioPolicy.createAudioRecordSink`.
  - `VoipCaptureSession.kt:30-46` — near party is plain **`MIC`**, explicitly *because* `VOICE_COMMUNICATION` is zero-filled:
    > *"**near party** — a plain `MIC` capture. The source matters: `VOICE_COMMUNICATION` is zero-filled for the whole call, because the in-call policy silences a record client that cannot bypass it and that source is the very one the VoIP app itself holds."*
  - `VoipCaptureSession.kt:58-63` — the opt-out is absolute:
    > *"An app can opt out of being captured (`ALLOW_CAPTURE_BY_NONE` sets a flag checked before any permission and bypassable by nothing), and some OEM builds simply do not attach the call to our mix. Both look identical from here: the mix delivers perfect digital silence while the near side records normally."*
  - `BypassedAudioRecord.kt:24-40` — a port of scrcpy's `Workarounds.createAudioRecord` to survive vivo's `VivoAudioRecordImpl.isSupportSubMixRecording()` NPE.
- **Documented device support (README):** *"Verified so far on OnePlus 12 (OxygenOS 16) and Galaxy S24 FE (One UI 8.5) — reports welcome. Carrier Wi-Fi calling (VoWiFi/VoLTE) is **not** covered."*
- **Documented failure — `docs/SUPPORT.md:22-40`:**
  > *"### vivo and iQOO phones — the other person's voice in app calls cannot be recorded*
  > *On **vivo** and **iQOO** phones (Funtouch OS / OriginOS), app (VoIP) calls — WhatsApp, Telegram, Signal and the like — record **your side only**. … This is not something CallVault can fix, and it is not a bug in your setup. … No third-party app can capture the far side of an app call on a stock vivo/iQOO phone."*
- **App list is dynamic, not a hardcoded messenger list** (`ui/screens/VoipAppList.kt`): launchable apps that request `RECORD_AUDIO`. Comment: *"a fixed list is wrong the day someone installs a messenger nobody thought of."*
- **Storage/DB/export:** recordings are **Opus in `.ogg`** (24 kbps default; AAC/`.m4a` 8–128 kbps) to a user-picked folder; **Room** for transcripts; optional BCR-compatible metadata sidecar; transcript export as text/Markdown/SRT/VTT/JSON; optional Google Drive backup; in-app self-update from GitHub with signature verification. On-device transcription = whisper.cpp; summaries = llama.cpp + Gemma (2.6 GB download).
- **Activity:** extremely active — 5 releases in the 7 days before 2026-09-29 (v2.4.1 → v2.4.4-rc1).
- **WeChat evidence:** `grep -rniE "wechat|微信|weixin"` over the clone → **zero hits**. `curl -sS "https://api.github.com/search/issues?q=repo:madkongo/CallVault+wechat"` → `total_count: 0`.
  - **Classification: "never mentioned".** It is however the *only* project that could plausibly be extended to WeChat, because it captures by `USAGE_VOICE_COMMUNICATION` rather than by app identity.
- **Reusable:** the entire `AudioPolicy`/`AudioMix` arming sequence, the `ROUTE_FLAG_LOOP_BACK_RENDER` insight, the arm-early requirement, the `AttributionSource.myAttributionSource()` identity trick, the 20 ms slot-pairing sync design, and the honest capability-detection approach. **But it is all GPL-3.0+§7 and several files are scrcpy ports.**

### 2.3 `jemcik/JemRec` — 6★ · Apache-2.0

- **URL:** <https://github.com/jemcik/JemRec> · **Metadata:** 6★, 1 fork, 0 open issues, `pushed_at` 2026-09-12, `spdx_id = Apache-2.0`, Kotlin, created 2026-09-10.
- **Last commit:** `2026-09-12 | Add a Sponsor button that links to Donatello (#12)`. **License:** `LICENSE` = `Apache License Version 2.0`.
- **Stack:** Kotlin, **Compose M3**, minSdk **31**, target 36, compile 37. Kotlin 2.3.21. 55 JVM unit tests. Distributed via GitHub Releases + F-Droid; explicitly *"It is not on Google Play and never will be."*
- **Capture mechanism — the architecturally interesting one.** README §*"The phone is both ADB host and ADB device"*:
  > *"The app embeds an ADB client. It pairs, over `127.0.0.1`, with the phone's own Wireless debugging — the same SPAKE2 pairing a laptop does, with the six-digit code, except both ends are on the same handset. Nothing leaves the phone."*
  >
  > *"### ADB is a bootstrap, not a transport — The session exists for a few seconds. In it the app runs `pm grant` to give…"*
  - `shellserver/src/com/genymobile/scrcpy/audio/AudioDirectCapture.java:39-45,96,101` — builds `AudioRecord` with `builder.setAudioSource(audioSource)`, and on failure falls back to `Workarounds.createAudioRecord(...)`.
  - `shellserver/src/com/genymobile/scrcpy/audio/AudioPlaybackCapture.java` + `AudioSource.java` — scrcpy's `REMOTE_SUBMIX`/playback-capture path is also present.
  - `app/src/main/java/com/jemcik/jemrec/capture/CaptureDaemon.kt` — daemon side.
  - `MediaRecorder.AudioSource` constants present: `VOICE_UPLINK`, `VOICE_DOWNLINK`, `VOICE_CALL`, `VOICE_COMMUNICATION`, `VOICE_RECOGNITION`, `VOICE_PERFORMANCE`, `UNPROCESSED`, `REMOTE_SUBMIX`.
- **Honest limitation statement (README):**
  > *"**Both sides of the call** have been recorded on three phones … Honor Magic 8 Pro (MagicOS 10, Android 16) · Samsung Galaxy S20 (One UI) · OnePlus (LineageOS 23). Three is not a compatibility list, and the HAL is the part nobody can promise…"*
- **Permissions:** `WRITE_SECURE_SETTINGS`, `READ_CALL_LOG`, `READ_CONTACTS`, `READ_PHONE_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`+`_SPECIAL_USE`, `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`, `READ_MEDIA_AUDIO`, `READ/WRITE_EXTERNAL_STORAGE`, `USE_FULL_SCREEN_INTENT`. **No `RECORD_AUDIO` in the main manifest** — capture happens in the shell process.
- **Storage/DB/export:** no database; files + `Prefs.kt`. `capture/RecordingStore.kt` and `ui/MainViewModel.kt` handle listing/sharing. Opus output.
- **Activity:** 5 releases in 3 days (0.2 → 0.6, 2026-09-10…09-12), then quiet since 2026-09-12. Very young project.
- **WeChat evidence:** `grep` → 0 hits; `search/issues?q=repo:jemcik/JemRec+wechat` → `total_count: 0`. **Classification: "never mentioned".** README explicitly frames the app as *"a call recorder for unrooted Android"* for carrier calls.
- **Reusable:** 🟢 **the best permissive reference for the privilege bootstrap** — the loopback-ADB idea avoids making the user install Shizuku, and the whole repo is Apache-2.0. Caveat: `shellserver/` is scrcpy-derived (`com.genymobile.scrcpy` package names retained) — Apache-2.0 upstream, so obligations are just attribution/NOTICE.

### 2.4 `LyoSU/cally` — 36★ · GPL-3.0

- **URL:** <https://github.com/LyoSU/cally> · **Metadata:** 36★, 7 forks, 11 open issues, `pushed_at` 2026-09-28, `spdx_id = GPL-3.0`, Kotlin, created 2026-04-27.
- **Last commit:** `2026-08-15 | chore(deps): upgrade toolchain to AGP 9.3.1 / Gradle 9.6.1 / Kotlin 2.3.21`. **License:** `LICENSE` = GPL-3.0.
- **Stack:** Kotlin, **Compose M3 Expressive**, minSdk **31** / target+compile 36. Modules `:app`, `:userservice`, `:aidl`. **Room 2.7** + DataStore. v0.5.1.
- **Capture mechanism (README.en.md, §"How the bypass works (8 layers)") — verbatim, and the clearest public write-up of the shell-attribution trick:**
  > *"**cally bypasses the block via context attribution inside the Shizuku UserService**: our private service runs in a shell-UID process (UID 2000), and `AudioRecord` is created with a context whose attribution matches the real system package `com.android.shell` — a package that exists in the package DB with the same UID 2000 and carries signature-level `RECORD_AUDIO`, `CAPTURE_AUDIO_OUTPUT`, and `MODIFY_AUDIO_ROUTING`. AudioFlinger validates `(uid=2000, pkg="com.android.shell")` against the package DB — the pair is genuine — and opens `VOICE_*` sources as for a system component."*
  > *"The first 5 layers of this technique are publicly known from scrcpy 2.0 (…`Workarounds.java`…) where it's used to mirror audio output (`REMOTE_SUBMIX`). cally **applies it to telephony audio sources** (`VOICE_UPLINK/DOWNLINK/CALL`) — we haven't found a public description of this specific application."*
  - Layer 6 fallback ladder, verbatim: *"`RecorderController` tries strategies in order: dual `VOICE_UPLINK/DOWNLINK` → `MIC + VOICE_DOWNLINK` (Samsung-friendly) → `VOICE_CALL` stereo → `VOICE_CALL` mono → `MIC` only. Each strategy is verified over a 5-second window using an **adaptive noise floor**… Three strikes before blacklist. Successful strategy is cached per `Build.FINGERPRINT`."*
  - `userservice/src/main/kotlin/dev/lyo/callrec/userservice/AudioRecorderJob.kt:131-133` confirms the implementation: `AudioRecord.Builder().setContext(context).setAudioSource(src)`.
  - Also in the repo: `HiddenApiBootstrap.kt`, `WrappedShellContext.kt`, `Capabilities.kt`.
- **Why minSdk 31 (architectural, not cosmetic):** *"only the API 31+ Builder reads attribution from the passed Context. The legacy 5-arg ctor reads from static `ActivityThread`. This is why `minSdk=31`."*
- **Permissions:** `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `READ_CALL_LOG`, `READ_CONTACTS`, `READ_PHONE_STATE`, `FOREGROUND_SERVICE`+`_SPECIAL_USE`, `POST_NOTIFICATIONS`, `INTERNET`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- **Storage/DB/export:** Room (call metadata) + DataStore; per-track AAC-in-MP4 (`MediaCodec`+`MediaMuxer`) or WAV; `ui/playback/Sharing.kt` for share. Optional cloud transcription via a user-configured OpenAI-compatible endpoint with `input_audio` content parts — *"Disabled completely without an API key. Recording itself never touches the network. No Firebase / Crashlytics / Sentry / analytics."*
- **Activity:** releases v0.3.0/v0.4.0/v0.4.1 (2026-04-28), v0.5.0 (2026-05-01), v0.5.1 (2026-08-15); last push 2026-09-28.
- **WeChat evidence:** `grep` → 0 hits; `search/issues?q=repo:LyoSU/cally+wechat` → `total_count: 0`. **Classification: "never mentioned".** Ukrainian-language primary docs.
- **Reusable:** the 8-layer explanation is the best *teaching* document in the field. Code is GPL-3.0.

### 2.5 `k2-fsa/sherpa-onnx` — 15043★ · Apache-2.0 — **negative result on capture**

- **URL:** <https://github.com/k2-fsa/sherpa-onnx> · **Metadata:** 15043★, 1732 forks, 641 open issues, `pushed_at` 2026-09-22, `spdx_id = Apache-2.0`, language C++, created 2022-09-01.
- **Retrieved via:** `git clone --depth 1 --filter=blob:none --sparse https://github.com/k2-fsa/sherpa-onnx.git && git sparse-checkout set android`
- **Android examples present:** `SherpaOnnx`, `SherpaOnnx2Pass`, `SherpaOnnxAar`, `SherpaOnnxAudioTagging`, `SherpaOnnxAudioTaggingWearOs`, `SherpaOnnxJavaDemo`, `SherpaOnnxKws`, `SherpaOnnxSimulateStreamingAsr`, `SherpaOnnxSimulateStreamingAsrWearOs`, `SherpaOnnxSpeakerDiarization`, `SherpaOnnxSpeakerIdentification`, `SherpaOnnxSpokenLanguageIdentification`, `SherpaOnnxTts`, `SherpaOnnxTtsEngine`, `SherpaOnnxVad`, `SherpaOnnxVadAsr`, `SherpaOnnxWebSocket`.
- **Capture code — exhaustive grep:**
  - `grep -rniE "VOICE_CALL|VOICE_COMMUNICATION|MediaProjection|AudioPlaybackCapture|REMOTE_SUBMIX|call record|telephony|VOIP" sherpa-onnx/android/` → **no matches.**
  - `grep -rn "MediaRecorder.AudioSource" sherpa-onnx/android/` → **14 matches, all `MediaRecorder.AudioSource.MIC`** (e.g. `SherpaOnnx/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt:87`, `SherpaOnnxVad/.../MainActivity.kt:32`, `SherpaOnnxJavaDemo/app/src/main/java/com/k2fsa/sherpa/onnx/service/SpeechSherpaRecognitionService.java:55`).
  - `grep -rl "AudioRecord" sherpa-onnx/android/` → 14 example files, all mic-fed.
- **Verdict:** sherpa-onnx is a **model-inference library**, not a capture library. It contains **nothing** relevant to call, VoIP, or system-audio capture, and no WeChat/微信 claim for Android audio (the only `微信` hit in the repo is `README.md:336`, an unrelated WeChat *community group* for the project).
- **Reusable (Apache-2.0, permissive):** VAD, streaming/2-pass ASR, speaker diarization, spoken-language ID, TTS; the Kotlin/JNI Android integration pattern; the AAR build. This is the natural ASR backend for your project. **Not reusable:** the capture layer — you must build it.

### 2.6 `zeerd/Real-timeTranscription` — 4★ · MIT

- **URL:** <https://github.com/zeerd/Real-timeTranscription> · **Metadata:** 4★, 0 forks, 0 open issues, `pushed_at` 2026-07-21, `spdx_id = MIT`, Kotlin, created 2026-07-16.
- **Last commit:** `2026-07-21 | debug`. **License:** `LICENSE` = `MIT License / Copyright (c) 2026 Charles Chan` — matches API.
- **Stack:** Kotlin 2.2, **Compose M3 + Navigation Compose**, single Activity. minSdk 26 / target 35. ONNX Runtime (VAD) + sherpa-onnx (ASR) + LiteRT (LLM).
- **Capture:** `AudioRecorder.kt:17-22` maps a user-selected source to a name: `MIC`, `CAMCORDER`, `VOICE_RECOGNITION`, `UNPROCESSED`; `:41` passes it to `AudioRecord`. `SettingsScreen.kt:85` builds the `AudioSourceOption` list. 16 kHz mono PCM, 512 samples/frame (README §2.1).
- **Scope:** **microphone only.** No `MediaProjection`, no `AudioPlaybackCapture`, no `VOICE_CALL`, no root/Shizuku. `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `INTERNET`, `ACCESS_NETWORK_STATE`, `WRITE_EXTERNAL_STORAGE`.
- **Storage/DB/export:** no DB; transcripts to private files + optional SAF save-directory; local LLM session summaries.
- **Activity:** 4★, created and last pushed within 5 days; no releases. **Toy/early project.**
- **WeChat evidence:** `grep` → 0 hits. **Classification: "never mentioned".**
- **Reusable:** a clean, small, **MIT-licensed** Compose + VAD→ASR→diarization→merge pipeline. Good architecture reference for the transcription half. Its `VOICE_RECOGNITION`/`UNPROCESSED` source list is a useful hint that source selection matters, but on Android 10+ none of these reach call audio.

### 2.7 `DaedalusApps/daedalus-echo` — 5★ · MIT

- **URL:** <https://github.com/DaedalusApps/daedalus-echo> · **Metadata:** 5★, 2 forks, 1 open issue, `pushed_at` 2026-08-25, `spdx_id = MIT`, Kotlin, created 2026-06-07.
- **Last commit:** `2026-08-25 | docs: add MIT license` — **the license was added as the most recent commit**; treat prior history cautiously. **License file:** `LICENSE` = `MIT License / Copyright (c) 2026 DaedalusApps`.
- **Stack:** Kotlin, **Compose M3** (`compose-bom 2024.12.01`, AGP 8.7.3, Kotlin 2.0.21), a single `:app` module under `android/`. minSdk 26 / target+compile 35. **Room v8** with `Recording`, `TodoItem`, `RecordingFts` (FTS4) entities — `data/db/AppDatabase.kt:15`.
- **Capture:** `MediaRecorder.AudioSource.MIC` (one occurrence) + `MediaRecorder.AudioEncoder.AAC`. Manifest: `RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE`+`_DATA_SYNC`, `INTERNET`, `POST_NOTIFICATIONS`.
- **Scope:** a **voice recorder / meeting recorder**, explicitly: *"Record high-quality audio directly in the app using the device microphone or a Bluetooth headset (SCO)"*. `grep -i "call record|telephony|phone call|voip|system audio|internal audio|AudioPlaybackCapture"` over the README → **zero hits**.
- **Storage/DB/export:** Room v8 + FTS search; export = share summaries/mind maps/Q&A as Markdown or clipboard.
- **Activity:** 5★, one issue, created 2026-06-07, last push 2026-08-25. Low activity, single maintainer.
- **WeChat evidence:** `grep -rniE "wechat|微信|weixin"` → 0 hits. **Classification: "never mentioned".**
- **Reusable:** 🟢 MIT. Good reference for Room + FTS + Compose + on-device Whisper (sherpa-onnx) + Gemma pipeline, i.e. the *transcription/analysis* half. **Irrelevant to capture** — it never touches call or system audio.

### 2.8 `timgras2/OpenWhisprAndroid` — 0★ · NO LICENSE — **effectively a stub**

- **URL:** <https://github.com/timgras2/OpenWhisprAndroid> · **Metadata:** 0★, 0 forks, 0 open issues, `pushed_at` 2026-02-26, `license = None`, Kotlin, created 2026-02-06, repo size **16 KB**.
- **Last commit:** `2026-02-26 | chore: sync latest local changes (2026-02-26 20:27:21)`. **License:** `ls` shows **no LICENSE/COPYING file** → all rights reserved.
- **Contents:** 23 files total. Files: `MainActivity.kt`, `audio/AudioRecorder.kt`, `api/GroqApiClient.kt`, `data/SettingsDataStore.kt`, `ui/screens/{RecordingScreen,SettingsScreen}.kt`, theme files, launcher resources. **No README, no tests, no CI, no releases.**
- **Capture:** `MediaRecorder.AudioSource.MIC` + `MediaRecorder.AudioEncoder.AAC`. Permissions: `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`(implied)/`INTERNET`. minSdk 26 / target+compile 35, Compose.
- **Important:** despite the "OpenWhispr" name implying a port of a local-first tool, the presence of `api/GroqApiClient.kt` and `INTERNET` means this build **uploads audio to Groq's cloud API**. Not on-device.
- **WeChat evidence:** 0 hits. **Classification: "never mentioned".**
- **Verdict:** not a real project. **Do not use.** No license, no docs, no activity, cloud-dependent.

### 2.9 AudioPlaybackCapture reference implementations (4 repos)

All four implement the *same* platform pattern: `MediaProjectionManager.createScreenCaptureIntent()` → `MediaProjection` → `AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(...)` → `AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg)`.

**`myfreax/AudioRecorder`** — 5★, 1 fork, 1 open issue, last commit **2022-03-03** (`docs: spellmiss`), **NO LICENSE FILE**, Kotlin, XML Views, minSdk 29/target 31.
- The canonical snippet, `app/src/main/java/com/myfreax/audiorecorder/AuduioRecordingTask.kt:47-50`:
  ```kotlin
  AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
      .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
      .addMatchingUsage(AudioAttributes.USAGE_GAME)
      .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
  ```
- README: *"Sample app for AudioPlaybackCapture API"*; requests `RECORD_AUDIO` + media-projection permission; output PCM 44100 Hz/mono/16-bit to `/storage/emulated/0/Android/data/com.myfreax.audiorecorder/files/AudioCaptures/`. Permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE` (untyped).
- **Verdict:** abandoned 2022 teaching sample, unlicensed. Read it for the pattern, do not copy it.

**`sunilxsk/RecordingAudio`** — 1★, 0 forks, 0 issues, last commit 2026-09-17, 🟢 **Apache-2.0**, Kotlin, **Compose**, minSdk 29/target+compile 35, version 53.0. Chinese-only UI.
- `audio/AudioCaptureManager.java:450-465`: per-usage configurable plus a full fallback set — `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_ALARM`, `USAGE_NOTIFICATION`, `USAGE_UNKNOWN`. `ui/components/SettingSections.kt:155-156` exposes 媒体/游戏 selection.
- Distinctive features: **dual capture threads mixed in the float domain** for simultaneous internal + mic recording, FFmpeg encoding, real-time waveform (RMS/dBFS), silence detection with adaptive floor, Media3/ExoPlayer playback, draggable overlay panel.
- Permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE`+`_MEDIA_PROJECTION`+`_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`, `READ/WRITE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`.
- **Verdict:** 🟢 the most feature-complete **permissively licensed** AudioPlaybackCapture reference. Useful for the capture plumbing, the mic-mix, and the overlay. **Useless for calls** — `USAGE_VOICE_COMMUNICATION` is not in its match list (and could not be captured anyway).

**`Eyeing0721/android-internal-audio-recorder`** — 0★, 0 forks, 0 issues, last commit 2026-09-14, 🟢 **MIT**, Java, XML Views, minSdk 29/target+compile 35.
- `app/src/main/java/dev/eye/internalrec/CaptureEngine.java:81-86`: `addMatchingUsage(USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN / USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)`.
- Its **README is the most honest doc among the samples**, `README.md:86`:
  > *"**App 可以拒绝被录**。目标应用如果把 `allowAudioPlaybackCapture` 设成 false（有些银行、DRM 播放器会），录出来就是静音。这不是 bug，是系统的隐私开关，绕不过去。"*
  (Translation: *"An app can refuse to be recorded. If the target app sets `allowAudioPlaybackCapture` to false (some banks, DRM players do), what you record is silence. This is not a bug, it's the system's privacy switch, and it cannot be bypassed."*)
- And `CaptureEngine.java:16-17`: *"内录走 AudioPlaybackCaptureConfiguration：Android 10 起系统允许把'其他 App 播出来的声音'直接录下来，不需要 root、不需要虚拟声卡。代价是目标 App 可以声明 allowAudioPlaybackCapture=false"*.
- Notable design: **one-time consent, retained in a service**, then start/stop is driven entirely by `adb` broadcasts / a PowerShell `record.ps1` control plane — i.e. an agent-operable recorder. Permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE`+`_MEDIA_PROJECTION`+`_MICROPHONE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`.
- **Verdict:** 🟢 MIT, small, clean, and the ADB-control-plane idea is genuinely reusable if you want scripted/agent-driven capture. Still cannot capture voice calls.

**`arrayforward/internal_audio`** — 0★, 0 forks, 0 issues, last commit 2026-07-24 (`chore: 版本升级 v1.1`), 🟢 **Apache-2.0**, Kotlin, **XML Views (ViewBinding, `MainActivity.kt:67` `setContentView(binding.root)`)**, minSdk 29 / **target 29** / compile 34.
- `app/src/main/java/com/arrayforward/audiocapture/AudioCaptureService.kt:131-134`: `addMatchingUsage(USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN)`.
- 48 kHz/16-bit/**stereo**; WAV (lossless, byte-level trim) + M4A (AAC 192 kbps, frame-level trim via `MediaExtractor`+`MediaMuxer`); waveform player; PESQ-ish MOS quality分析; saves to `/sdcard/Music/audio/`.
- `README.md:111` documents the limit: *"目标 App 声明 `allowAudioPlaybackCapture="false"` 时无法捕获其声音"*; `docs/AudioCapture_Design.md:371`: *"| 目标应用禁止捕获（`allowAudioPlaybackCapture="false"`） | 静默无声音…"*.
- **Notable for a different reason:** it deliberately **pins `targetSdk 29`** to keep legacy external-storage behaviour (`README.md:87` — *"targetSdk 29 是为保留传统存储行为；release 构建已禁用 `ExpiredTargetSdkVersion` lint 检查"*). That is a **Google Play compliance problem** — do not copy that decision.
- Permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE`+`_MEDIA_PROJECTION`, `MANAGE_EXTERNAL_STORAGE`, `POST_NOTIFICATIONS`, `READ/WRITE_EXTERNAL_STORAGE`.

### 2.10 `AnasAyyad/opencall-recorder` — 0★ · Apache-2.0

- **URL:** <https://github.com/AnasAyyad/opencall-recorder> · **Metadata:** 0★, 0 forks, 0 open issues, `pushed_at` 2026-09-16, `spdx_id = Apache-2.0`, Java, created 2026-09-16.
- **Last commit:** `2026-09-16 | Prepare signed public beta and beginner onboarding`. **License file** = `Apache License Version 2.0`.
- **Build:** **no Gradle.** `app/AndroidManifest.xml:2` → `<uses-sdk android:minSdkVersion="31" android:targetSdkVersion="36" />`; `scripts/build.sh` compiles against `android-36/android.jar` with `javac`. Dependencies are hash-pinned and fetched by `scripts/fetch-adb.sh` (libadb-android 3.1.1, spake2-android, conscrypt, bcprov).
- **Capture mechanism:**
  - `app/src/main/java/local/honor/callprobe/PhoneSetup.java:38` → *"Phone-only, explicit wireless-debugging pairing. No remote hosts or arbitrary commands."* `:81` → `connection.pair("127.0.0.1", port, code)`; `:186,196` → `connect("127.0.0.1", …)`.
  - `PhoneBootstrap.java:29` → `if (Process.myUid() != 2000 || args.length != 2) throw new SecurityException("Authorized debugging shell required")`; `:82` → launches `/system/bin/app_process / local.honor.callprobe.ProbeServer <uid> <token>`.
  - `ProbeServer.java:46` → *"Authenticated call-audio capture under the Android debugging-shell identity."* `:51` → `private static final String CAPTURE_PERMISSION = "android.permission.CAPTURE_AUDIO_OUTPUT"`; `:340,444` → reflectively calls `AudioRecord.Builder.setContext(Context)`; `:437` → `throw new SecurityException("Debugging shell lacks call-audio capture or recording permission")`.
  - `vendor/adb/README.md:3` → *"No Shizuku companion is used."* (`Shizuku` appears only as an attribution comment in `PairingConnectionCtx.java:39`.)
- **Storage/DB/export:** `RecordingStore.java:88-89` → `Environment.getExternalStorageDirectory()/Sounds`, subfolder `Sounds/CallRecord`; written via `MediaStore` for playback visibility. No database (DataStore/SharedPreferences). Mixes both channels to mono, M4A/AAC with WAV fallback.
- **Activity:** 1 release `v0.9.0-beta.1` (2026-09-16), created same day. Brand new, single-device evidence (HONOR 400 / MagicOS 10 / Android 16).
- **Honest framing (README):** *"**Experimental beta candidate, not production-qualified.** Recording depends on the phone, firmware, and audio route."* and SETUP.md: *"These owner-reported results do not qualify other devices, every call or capture route, or all restart conditions."*
- **WeChat evidence:** `grep -rniE "wechat|微信|weixin"` → 0 hits. **Classification: "never mentioned".** Scope is cellular calls on HONOR.
- **Reusable:** 🟢 Apache-2.0, and the **best reference for the self-ADB bootstrap with real security hygiene**: a 0600 shell-owned token file, UID 2000 assertion, signature-pinned pairing, and an explicit refusal to accept arbitrary shell output. Also useful: the pure-`javac` build script if you want to avoid Gradle.

### 2.11 `boldbeastsoft/CallRecordingFix` — 34★ · NO LICENSE

- **URL:** <https://github.com/boldbeastsoft/CallRecordingFix> · **Metadata:** 34★, 12 forks, 3 open issues, `pushed_at` **2021-02-19**, `license = None`, language **Shell**, created 2020-06-07.
- **Last commit:** `2021-02-19 | Add files via upload`. **No LICENSE file.** Repo is a Magisk module (README says V2011, Android 5.0+).
- **WeChat claim — the ONLY explicit WeChat claim in the entire survey, and it is marketing for closed-source binaries.** `README.md:4` verbatim:
  > *"Record video calls and voice calls from VoIP apps such as WhatsApp, Viber, Facebook Messenger, Google Hangouts, Google Duo, Line, **WeChat**, Skype, Zoom Video Conference etc. Actually all VoIP apps on the market are supported."*
- **But:** the README itself says the actual recording is done by two **closed-source paid apps** — *"you need to install the app 'Boldbeast Call Recorder'"* (`com.boldbeast.recorder`) and *"you need to install the app 'Boldbeast VoIP Recorder'"* (`com.boldbeast.voiprecorder`). **This repository contains only the Magisk glue, not the audio path.** There is no source here that captures WeChat audio.
- **Classification: "claims it works" — but the claim is unverifiable from this repo and the evidence lives in proprietary binaries. Treat as an unsubstantiated marketing claim, not a verified capability.** Abandoned for 5+ years (last commit 2021) while claiming Android 5.0+ support.
- **Verdict:** 🔴 No license, no source for the claimed capability, stale. **Do not use.**

### 2.12 `jagobandhusome/universal-android-call-recorder-including-calling-apps-like-whatsapp` — 0★ · GPL-3.0

- **URL:** <https://github.com/jagobandhusome/universal-android-call-recorder-including-calling-apps-like-whatsapp> · **Metadata:** 0★, 0 forks, 0 open issues, `pushed_at` 2026-09-12, `spdx_id = GPL-3.0`, Kotlin, created 2026-09-12.
- **Last commit:** `2026-09-13 | Merge branch 'main' of https://github.com/jagobandhusome/…`. **License file** = GPL-3.0 (matches the README badge).
- **Stack:** Kotlin, **Compose M3**, minSdk 26 / target+compile 36. Bengali + English. Ships a prebuilt debug APK in `apk/`.
- **WeChat + honest contradiction in the same README:**
  - `README.md:9` (marketing) — *"A **visible**, open-source call recorder for Android. Record regular phone calls and calling apps such as WhatsApp, Telegram, Messenger, IMO, Viber, **WeChat**, Signal, Meet, Zoom, and more."*
  - `README.md:176` (tags) — `` `#WeChat` ``
  - `app/src/main/java/com/androidcallrecorder/app/data/SupportedApps.kt:17` — `CallingApp("wechat", "WeChat", listOf("com.tencent.mm"))`
  - **But** `README.md` §*"What this app cannot do"* says the opposite, verbatim:
    > *"Android does not give third-party apps a silent tap of WhatsApp / Telegram / similar VoIP audio."*
    > *"| WhatsApp / other **voice** call | Audio only (mic / speaker) after you tap **Record audio** |"*
    > *"OEM recorders (for example Xiaomi's built-in tap) use privileged APIs this project does not have."*
- **Classification: "claims it works" in the headline/tags/app-list, but the same document states "does not work" in the capability section.** The app-list entry is a *UI label*, not a capture capability. **Net: NOT verified; effectively documented as NOT working.**
- **Capture code — includes an approach that the platform will not honour.** `app/src/main/java/com/androidcallrecorder/app/record/WavRecorder.kt:43-48`:
  ```kotlin
  val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
      .addMatchingUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
  …
  builder.setAudioPlaybackCaptureConfig(capture)
  ```
  Matching `USAGE_VOICE_COMMUNICATION` via AudioPlaybackCapture **cannot work** — AOSP's own class javadoc says *"All other usages CAN NOT be captured"* and the manifest attribute doc says *"All other usages like `USAGE_VOICE_COMMUNICATION` will not be captured."* (See §4.) `RecordingService.kt:194,413,417-419` also cycles `VOICE_CALL`/`VOICE_COMMUNICATION`/`MIC` from a settings enum. This is a **dead code path**, and a good illustration of the trap to avoid.
- **Permissions (large set):** `RECORD_AUDIO`, `FOREGROUND_SERVICE`+`_MEDIA_PROJECTION`+`_MICROPHONE`+`_SPECIAL_USE`, `PACKAGE_USAGE_STATS`, `READ_CALL_LOG`, `READ_CONTACTS`, `READ_PHONE_NUMBERS`, `READ_PHONE_STATE`, `RECEIVE_BOOT_COMPLETED`, `USE_BIOMETRIC`, `USE_FINGERPRINT`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK`.
- **Storage/DB/export:** no Room; recordings in app storage with search/filter, share, rename, lock, notes, extractive summary, **PDF export**, AES-256 encrypted vault (Android Keystore), ZIP backup/restore, M4A/AAC/WAV/MP3/MP4 converter, trim/merge.
- **Verdict:** 🔴 GPL-3.0 **and** a self-contradicting README **and** a provably non-functional VoIP capture path. 0★, created the same day as its last push. Not a credible foundation.

### 2.13 `developertl-tl/cordova-plugin-callrecorder-accessibility` — 0★ · NO LICENSE

- **URL:** <https://github.com/developertl-tl/cordova-plugin-callrecorder-accessibility> · **Metadata:** 0★, 0 forks, 0 open issues, `pushed_at` 2025-11-06, `license = None`, Java, repo size **6 KB**.
- **Last commit:** `2025-11-06 | first commit` — **single commit, no README, no LICENSE file.**
- **Contents (all of it):** `plugin.xml`, `package.json`, `www/CallRecorder.js`, `src/android/{CallRecorder,CallStateReceiver,RecorderAccessibilityService,RecorderThread}.java`, plus an accessibility-service XML config and two stray `.DS_Store` files.
- **Capture — read from source, and it is a red flag on modern Android.** `src/android/RecorderThread.java:16-23`:
  ```java
  int src = (Build.VERSION.SDK_INT>=21) ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.VOICE_CALL;
  …
  recorder = new AudioRecord(src, rate, ch, fmt, buf);
  if (recorder.getState()!=AudioRecord.STATE_INITIALIZED){
      recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, ch, fmt, buf);
  ```
  On **every** Android version ≥ 21 (i.e. all real devices) it picks `VOICE_COMMUNICATION`, which is **the app's own uplink only and is zero-filled/silenced during a call for a non-privileged recorder** (confirmed independently by CallVault's on-device finding, §2.2). `VOICE_CALL` — the only one that could yield both sides — is used **only below API 21.** The accessibility service is used for call *detection*, not audio capture.
- **Classification (WeChat):** `grep -rniE "wechat|微信"` → 0 hits; no README at all. **"never mentioned".**
- **Verdict:** 🔴 No license (and even the `plugin.xml` names a placeholder `com.mycompany.callrecorder` package). Single-commit, unlicensed, and its audio source choice is wrong for modern Android. **Do not use.**

### 2.14 `GenericJam/mob_audio_capture` — 0★ · NO LICENSE — **not an Android app**

- **URL:** <https://github.com/GenericJam/mob_audio_capture> · **Metadata:** 0★, 0 forks, 0 open issues, `pushed_at` 2026-07-12, `license = None`, language **Elixir**, created 2026-07-13, size 276 KB.
- **Last commit:** `2026-07-12 | ci: add test workflow (format/credo/compile/test) for PR validation (#4)`. **No LICENSE file** (checked `ls`; there is none).
- **What it actually is:** an **Elixir/Mob library** with an Android native bridge (`priv/native/android/MobAudioCaptureBridge.kt`, `priv/native/jni/mob_audio_capture_nif.zig`, `priv/mob_plugin.exs`). It uses `MediaProjection` + `AudioPlaybackCaptureConfiguration.Builder(proj)` + `addMatchingUsage` (`MobAudioCaptureBridge.kt:71-73,220-221`) — but its **output is an output-level meter** (`MobAudioCapture.output_level() # => {rms_db, peak_db}`), not a recording app.
- **Its README contains the single best quotable platform statement among the topic-search hits:**
  > *"Android 10+ (API 29) | Full output-mix capture, subject to each source app's `allowAudioPlaybackCapture` (default-on for non-privileged API 29+ apps; **`VOICE_COMMUNICATION` and DRM output never captured**)."*
  > *"Because capture requires a per-session system consent dialog and a typed foreground service, it is a **test-environment dependency** for agent-driven verification … not a capability you ship in a production app."*
  > *"A normal app cannot tap the global output mix with a session-0 `Visualizer` (privileged — `ERROR_NO_INIT` on Android, device-verified)."*
- **Classification (WeChat):** `grep` → 0 hits. **"never mentioned".**
- **Verdict:** 🔴 No license, Elixir (not Android), meter-only. Its **documentation** is worth citing; its **code** is not relevant.

---

## 3. WeChat (微信) — the complete evidence ledger

`grep -rniE "wechat|微信|weixin"` was run over every clone. Plus `curl -sS "https://api.github.com/search/issues?q=repo:OWNER/REPO+wechat"` for the four active call recorders.

| Project | Verbatim evidence | File / URL | Classification |
|---|---|---|---|
| **boldbeastsoft/CallRecordingFix** | *"Record video calls and voice calls from VoIP apps such as WhatsApp, Viber, Facebook Messenger, Google Hangouts, Google Duo, Line, **WeChat**, Skype, Zoom Video Conference etc. Actually all VoIP apps on the market are supported."* | `README.md:4` · <https://github.com/boldbeastsoft/CallRecordingFix/blob/master/README.md> | **claims it works** — but the audio path lives in closed-source paid apps (`com.boldbeast.recorder`, `com.boldbeast.voiprecorder`), not in this repo. Repo abandoned 2021. |
| **jagobandhusome/universal-android-call-recorder…** | *"Record regular phone calls and calling apps such as WhatsApp, Telegram, Messenger, IMO, Viber, **WeChat**, Signal, Meet, Zoom, and more."* — **contradicted in the same file by** *"Android does not give third-party apps a silent tap of WhatsApp / Telegram / similar VoIP audio."* | `README.md:9` and the "What this app cannot do" section; `app/src/main/java/com/androidcallrecorder/app/data/SupportedApps.kt:17` → `CallingApp("wechat", "WeChat", listOf("com.tencent.mm"))` | **claims it works (marketing) / documented as NOT working (capability section)**. The `SupportedApps` entry is a UI label only. |
| **kitsumed/ShizuCallRecorder** | *"Currently, none of the chat applications have a built-in call recorder."* … *"Add a checklist to the application settings so users can select the applications they want to record calls for, such as Telegram, WhatsApp, Line, **WeChat**."* — issue **closed, 0 comments** | <https://github.com/kitsumed/ShizuCallRecorder/issues/108> | **documented as NOT working / not supported.** |
| **madkongo/CallVault** | — none — (`search/issues?q=repo:madkongo/CallVault+wechat` → `total_count: 0`) | — | **never mentioned** |
| **jemcik/JemRec** | — none — (`total_count: 0`) | — | **never mentioned** |
| **LyoSU/cally** | — none — (`total_count: 0`) | — | **never mentioned** |
| All other 10 repos | no code/doc/issue mention (one unrelated `微信交流群` community-group line at `sherpa-onnx/README.md:336`) | — | **never mentioned** |

### Conclusion

**There is no verified WeChat call-audio capture anywhere in this survey.** No project ships code that demonstrably records WeChat call audio, and no project documents having tested it. The two repos that advertise WeChat either (a) defer the actual capture to closed-source binaries (Boldbeast, dead since 2021), or (b) advertise it in the title while the same README states it is impossible (jagobandhusome).

The one project that is *technically* positioned to attempt it — **CallVault**, because it matches `USAGE_VOICE_COMMUNICATION` rather than an app identity — has never mentioned WeChat and documents a hard failure mode that would affect it: **if WeChat sets `ALLOW_CAPTURE_BY_NONE`, capture returns perfect digital silence and there is no way around it.** CallVault's own comment: *"`ALLOW_CAPTURE_BY_NONE` sets a flag checked before any permission and bypassable by nothing."* **Whether WeChat sets that policy is UNVERIFIED** — it requires on-device measurement (`dumpsys media.audio_flinger` / `dumpsys audio` for the track's capture policy), which no repository in this survey has published.

---

## 4. Android platform facts (with citations)

### 4.1 AudioPlaybackCapture (Android 10 / API 29+) — the exact conditions

**Primary source — AOSP `AudioPlaybackCaptureConfiguration.java`, class javadoc, lines 33–50**
<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/media/java/android/media/AudioPlaybackCaptureConfiguration.java>

> *"When capturing audio signals played by other apps (and yours), you will only capture a mix of the audio signals played by players (such as AudioTrack or MediaPlayer) which present the following characteristics:*
> *  *the usage value MUST be `USAGE_UNKNOWN` or `USAGE_GAME` or `USAGE_MEDIA`. **All other usages CAN NOT be captured.***
> *  *AND the capture policy set by their app (with `AudioManager.setAllowedCapturePolicy`) or on each player (with `AudioAttributes.Builder.setAllowedCapturePolicy`) is `ALLOW_CAPTURE_BY_ALL`, **whichever is the most strict**.*
> *  *AND their app attribute `allowAudioPlaybackCapture` in their manifest MUST either be: set to "true"; OR not set, and their `targetSdkVersion` MUST be equal to or greater than `Build.VERSION_CODES.Q`. Ie. Apps that do not target at least Android Q must explicitly opt-in to be captured by a MediaProjection.*
> *  *AND their apps MUST be in the same user profile as your app (eg work profile cannot capture user profile apps and vice-versa)."*

**Capturing-app requirements** — <https://developer.android.com/media/platform/av-capture> (fetched via `curl`, page text: *"Last updated 2026-09-28 UTC"*):
- *"`RECORD_AUDIO` permission."*
- *"The app must bring up the prompt displayed by `MediaProjectionManager.createScreenCaptureIntent()`, and the user must approve it."*
- *"The capturing and playing apps must be in the same user profile."*
- *"To capture audio from another app, your app must build an `AudioRecord` object and add an `AudioPlaybackCaptureConfiguration` to it."*
- *"You cannot use the `addMatchingUsage()` and `excludeUsage()` methods together. You must choose one or the other. Likewise, you cannot use `addMatchingUid()` and `excludeUid()` at the same time."*

**`addMatchingUsage` is inclusive-only** — AOSP `AudioPlaybackCaptureConfiguration.java:179-187`: it adds `AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE` and sets `mUsageMatchType = MATCH_TYPE_INCLUSIVE`; calling it after `excludeUsage` throws `IllegalStateException` (`ERROR_MESSAGE_MISMATCHED_RULES`).

**`allowAudioPlaybackCapture` — default value**, AOSP `core/res/res/values/attrs_manifest.xml:2125-2147`
<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/res/res/values/attrs_manifest.xml>

> *"If `true` the app's non sensitive audio can be captured by other apps with `AudioPlaybackCaptureConfiguration` and a `MediaProjection`. If `false` the audio played by the application will never be captured by non system apps. It is equivalent to limiting `AudioManager.setAllowedCapturePolicy(int)` to `ALLOW_CAPTURE_BY_SYSTEM`.*
> *Non sensitive audio is defined as audio whose `AttributeUsage` is `USAGE_UNKNOWN`, `USAGE_MEDIA` or `USAGE_GAME`. **All other usages like `USAGE_VOICE_COMMUNICATION` will not be captured.***
> *The default value is: **`true` for apps with targetSdkVersion >= 29 (Q)**; **`false` for apps with targetSdkVersion < 29**."*

**`ALLOW_CAPTURE_BY_*` semantics** — AOSP `AudioAttributes.java:526-561`
<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/media/java/android/media/AudioAttributes.java>

> `ALLOW_CAPTURE_BY_ALL = 1` — *"Indicates that the audio may be captured by any app. **For privacy, the following usages cannot be recorded: `VOICE_COMMUNICATION*`, `USAGE_NOTIFICATION*`, `USAGE_ASSISTANCE*` and `USAGE_ASSISTANT`.** On `Build.VERSION_CODES.Q`, this means only `USAGE_UNKNOWN`, `USAGE_MEDIA` and `USAGE_GAME` may be captured."*
> `ALLOW_CAPTURE_BY_SYSTEM = 2` — *"Indicates that the audio may only be captured by system apps. System apps can capture for many purposes like accessibility, live captions, user guidance… but abide to the following restrictions: the audio cannot leave the device; the audio cannot be passed to a third party app; **the audio cannot be recorded at a higher quality than 16kHz 16bit mono**."*
> `ALLOW_CAPTURE_BY_NONE = 3` — *"Indicates that the audio is not to be recorded by any app, **even if it is a system app**."*

**Default capture policy is permissive:** `AudioAttributes.java:1040` — *"The default is `AudioAttributes#ALLOW_CAPTURE_BY_ALL`."* And `setAllowedCapturePolicy` (`:1053-1057`) — *"The most restrictive policy is always applied."* The `developer.android.com` guide states the effective-policy matrix explicitly: manifest `true` + `ALLOW_CAPTURE_BY_ALL` → *"any app"*; manifest `false` + `ALLOW_CAPTURE_BY_ALL` → *"system only"*.

**Which apps can be captured / excluded by default:**

| App property | Capturable by a normal third-party app? |
|---|---|
| `targetSdk >= 29`, `USAGE_MEDIA`/`USAGE_GAME`/`USAGE_UNKNOWN`, policy `ALLOW_CAPTURE_BY_ALL` | ✅ Yes (after user grants the projection dialog) |
| `targetSdk >= 29`, manifest `allowAudioPlaybackCapture="false"`, or runtime `setAllowedCapturePolicy(ALLOW_CAPTURE_BY_SYSTEM/NONE)`, or per-track `setAllowedCapturePolicy(...)` | ❌ No — *"the most restrictive policy is always applied"* |
| `targetSdk < 29` and `allowAudioPlaybackCapture` not set | ❌ No — must explicitly opt in |
| **Any app emitting `USAGE_VOICE_COMMUNICATION`** | ❌ **Never** — VoIP call audio |
| `USAGE_NOTIFICATION*`, `USAGE_ASSISTANCE*`, `USAGE_ASSISTANT`, `USAGE_ALARM` | ❌ Never |
| DRM-protected output | ❌ Never (`FLAG_SECURE` / DRM path) |
| App in a different user profile (e.g. work profile) | ❌ Never |

*(`USAGE_ALARM` / `USAGE_NOTIFICATION` appear in `sunilxsk/RecordingAudio`'s match list; per the rules above those calls are no-ops for non-system capture, since the platform will not mix them.)*

**Answer to "can AudioPlaybackCapture capture a VoIP call?" — No.** Two independent reasons: (1) the usage is `USAGE_VOICE_COMMUNICATION`, which is excluded from matching everywhere in the framework; (2) even under `ALLOW_CAPTURE_BY_ALL`, the *usages* that are captured are limited to `UNKNOWN`/`MEDIA`/`GAME`. This is corroborated by four projects' own docs (`CallVault`, `GenericJam`, `arrayforward`, `Eyeing0721`) and by `jagobandhusome`'s README.

**Implementation-policy note (ENFORCEMENT LAYER — partially verified):** the enforcement sits below the Java API, in the native audio policy / mix layer (`AudioMixingRule` → `AudioPolicyService` → `AudioFlinger` mix rules), which is why the match is a *usage rule* rather than a permission check. I did **not** read `AudioMix.cpp`/`AudioFlinger` directly, so the exact file/line is **UNVERIFIED**; the behaviour is nonetheless authoritatively documented in the Java-layer javadoc quoted above and independently reproduced by four projects.

### 4.2 Can a normal app obtain the OTHER party's voice during a VoIP call on Android 10–16?

**No.** Not on 10, 11, 12, 13, 14, 15, or 16.

**`AudioSource` semantics** — AOSP `MediaRecorder.java`
<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/media/java/android/media/MediaRecorder.java>

- `VOICE_UPLINK = 2` (`:292-300`): *"Voice call uplink (Tx) audio source. Capturing from `VOICE_UPLINK` source requires the `android.Manifest.permission#CAPTURE_AUDIO_OUTPUT` permission. **This permission is reserved for use by system components and is not available to third-party applications.**"*
- `VOICE_DOWNLINK = 3` (`:302-310`): identical wording for *"Voice call downlink (Rx) audio source."*
- `VOICE_CALL = 4` (`:312-320`): *"Voice call uplink + downlink audio source. Capturing from `VOICE_CALL` source requires the `CAPTURE_AUDIO_OUTPUT` permission. **This permission is reserved for use by system components and is not available to third-party applications.**"*
- `VOICE_COMMUNICATION` is the *uplink-oriented* source used by VoIP apps themselves; it is not a downlink tap, and (per CallVault's device measurement) it is zero-filled for a second recorder that cannot bypass the in-call policy.

**Every `AudioRecord` also needs `RECORD_AUDIO`** — AOSP `AudioRecord.java:90-95`:
> *"Applications creating an `AudioRecord` instance need `android.Manifest.permission#RECORD_AUDIO` or the Builder will throw `UnsupportedOperationException` on `build()`, and the constructor will return an instance in state `STATE_UNINITIALIZED`."*

`RECORD_AUDIO` itself is `dangerous|instant` and grantable — AOSP `core/res/AndroidManifest.xml:1684-1689` — which is why the block is `CAPTURE_AUDIO_OUTPUT`, not `RECORD_AUDIO`.

**What changed in Android 10 (API 29):** the platform stopped returning call audio to `VOICE_CALL`/`VOICE_UPLINK`/`VOICE_DOWNLINK` for non-privileged apps, and introduced `AudioPlaybackCapture` as the sanctioned (and deliberately call-excluding) alternative. Google's own confirmation that no replacement API was coming is the **revert commit**:

> `57a3769fb12dc8b2df457e0919850bd976716706` — **"Revert 'Allow call audio access for default dialer application'"** — *"This reverts commit `ac26cf749f457e12a3d8d7456bbcd58a3e028d69`. **Reason for revert: Feature has been postponed**"* — Bug: 151761909 — 12 files in `frameworks/av` (AudioFlinger, AudioPolicyService, ServiceUtilities, …).
> <https://android.googlesource.com/platform/frameworks/av/+/57a3769fb12dc8b2df457e0919850bd976716706>

That commit is the removal of the **`ACCESS_CALL_AUDIO`** permission introduced in the Android 11 developer preview. **I verified `ACCESS_CALL_AUDIO` is absent from the current AOSP `core/res/AndroidManifest.xml`** (`grep -n "ACCESS_CALL_AUDIO" core_res_AndroidManifest.xml` → no match). ShizuCallRecorder's `docs/configuration.md:12` documents the same episode and cites <https://issuetracker.google.com/issues/158923887>.

Also relevant, and confirming the policy is still live: `CALL_AUDIO_INTERCEPTION` in AOSP `core/res/AndroidManifest.xml:6590-6595` —
> *"@SystemApi Allows an application to access the uplink and downlink audio of an ongoing call. **Not for use by third-party applications.**"* → `android:protectionLevel="signature|privileged|role"`

### 4.3 `CAPTURE_AUDIO_OUTPUT` and friends — protection levels

All from AOSP `core/res/AndroidManifest.xml`
<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/res/AndroidManifest.xml>

| Permission | Line | `protectionLevel` | Doc comment |
|---|---|---|---|
| `CAPTURE_AUDIO_OUTPUT` | 6507-6508 | **`signature\|privileged\|role`** | *"Allows an application to capture audio output. Use the `CAPTURE_MEDIA_OUTPUT` permission if only the `USAGE_UNKNOWN`, `USAGE_MEDIA` or `USAGE_GAME` usages are intended to be captured. **Not for use by third-party applications.**"* |
| `CAPTURE_MEDIA_OUTPUT` | 6525-6526 | `signature\|privileged\|role` | *"@SystemApi Allows an application to capture the audio played by other apps that have set an allow capture policy of `ALLOW_CAPTURE_BY_SYSTEM`. Without this permission, only audio with an allow capture policy of `ALLOW_CAPTURE_BY_ALL` can be used."* |
| `CAPTURE_VOICE_COMMUNICATION_OUTPUT` | 6543-6544 | `signature\|privileged\|role` | *"@SystemApi Allows an application to capture the audio played by other apps with the `USAGE_VOICE_COMMUNICATION` usage. The application may opt out of capturing by setting an allow capture policy of `ALLOW_CAPTURE_BY_NONE`."* ← **this is the VoIP gate** |
| `CALL_AUDIO_INTERCEPTION` | 6594-6595 | `signature\|privileged\|role` | *"@SystemApi Allows an application to access the uplink and downlink audio of an ongoing call. Not for use by third-party applications."* |
| `MODIFY_AUDIO_ROUTING` | 6579-6580 | `signature\|privileged\|role` | — |
| `CAPTURE_AUDIO_HOTWORD` | ~6557 | `signature\|privileged\|role` | — |
| `BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION` | 6551-6553 | `signature\|privileged` (flagged, `@hide`) | *"Allows an application to bypass concurrency restrictions while recording audio. For example, apps with this permission can continue to record while a voice call is active."* |
| `RECORD_AUDIO` | 1684-1689 | **`dangerous\|instant`** | grantable by the user |
| `RECORD_BACKGROUND_AUDIO` | 1695+ | internal (`@hide`) | *"Allows an application to record audio while in the background. This permission is not intended to be held by apps."* |

**Conclusion: `CAPTURE_AUDIO_OUTPUT` is not obtainable by a normal app.** It is `signature|privileged|role` — i.e. only apps signed by the platform, preinstalled in a privileged partition, or holding one of the specific *roles* the platform grants it to (e.g. the default dialer). A sideloaded APK cannot get it. `android:protectionLevel` values are documented at <https://developer.android.com/guide/topics/manifest/permission-element#plevel>; the `role` component means the permission is granted when the app holds a designated role.

### 4.4 Did Android 12/13/14/15/16 add a VoIP-recording API?

**No.** Verified negatives:

- **No `ACCESS_CALL_AUDIO`:** absent from current AOSP `core/res/AndroidManifest.xml` (grep → no match), and its 2020 removal is the revert commit cited in §4.2.
- **No voip-recording permission:** the only audio-adjacent permissions in current AOSP are the list in §4.3. The only one that mentions an ongoing call is `CALL_AUDIO_INTERCEPTION`, which is *"Not for use by third-party applications"* and `@hide`.
- **`MediaProjection` gained no call-audio capability.** The official guide is titled *"Capture video and audio playback"* and its audio section is exclusively `AudioPlaybackCaptureConfiguration`, which per §4.1 excludes `USAGE_VOICE_COMMUNICATION`. <https://developer.android.com/media/platform/av-capture>
- **`AudioRecord` + `VOICE_CALL` via `MediaProjection`/`AudioPlaybackCapture`:** not a thing. `AudioPlaybackCaptureConfiguration` builds an `AudioMixingRule` on *playback* attributes; `VOICE_CALL` is a *capture preset*, and the capture-preset path still requires `CAPTURE_AUDIO_OUTPUT`. The `AudioRecord` javadoc only special-cases `REMOTE_SUBMIX` for the full-volume submix tag (`AudioRecord.java:416-421`) and never relaxes the permission requirement.
- **`MediaProjection` did get *stricter*, not looser.** The guide states: *"The MediaProjection API allows apps to acquire a MediaProjection token that gives them **one time access** to capture screen contents or audio. … The OS displays the active MediaProjection tokens in the Quick Settings UI and **allows users to withdraw access to a token at any time**. When this happens the virtual displays or audio streams associated with the session stop receiving media streams. Your app must respond appropriately, otherwise it will continue to record audio silence or a black video stream."* Callback: `MediaProjection.registerCallback` / `onStop`.
  - Android 14+ also requires the `FOREGROUND_SERVICE_MEDIA_PROJECTION` foreground-service type — corroborated by the fact that **all four AudioPlaybackCapture sample apps in this survey declare it** (`RecordingAudio`, `android-internal-audio-recorder`, `arrayforward/internal_audio`, `universal-android-call-recorder`), while the 2022 sample (`myfreax`, target 31) does not.
  - The precise Android 14 "one token per capture session" wording: **UNVERIFIED** — my fetches of `developer.android.com/about/versions/1{4,5,6}/behavior-changes-all` returned pages with no `MediaProjection` text (`grep` → 0 hits on Android 14 and 16; the Android 15 hits were about the status-bar chip, not audio). The `one time access` + revocation statements above **are** verified from the av-capture guide (last updated 2026-09-28).
- **Telecomm call-recording:** AOSP's `packages/services/Telecomm` mirror (`aosp-mirror/platform_packages_services_telecomm`) **does not exist on GitHub** (`git clone` → `remote: Repository not found`), so I could not grep it. Any claim about a Telecomm-internal call-recording API is **UNVERIFIED** — but note that any such API would be a *system/privileged* surface, not available to a normal app.
- **The dialer-role route is real but closed.** Per §4.3 the permission is `signature|privileged|role`; Google Dialer's call recording is gated behind holding that role plus region/consent rules. ShizuCallRecorder's `docs/configuration.md:14` puts it well: *"proprietary apps like the Google Dialer and OEM apps can use the restricted permission `CAPTURE_AUDIO_OUTPUT`, and some do have the recording feature. However, they often choose not to offer it to most users, or they add their own proprietary rules on top of it."*

### 4.5 How the shell-UID (ADB / Shizuku / UID 2000) route works

**`com.android.shell` holds every relevant permission.** AOSP `packages/Shell/AndroidManifest.xml`
<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/packages/Shell/AndroidManifest.xml>

`grep -oE 'android:name="android\.permission\.[A-Z_]+"' packages_Shell_AndroidManifest.xml | grep -iE "AUDIO|RECORD|CAPTURE|MODIFY"` returns:

```
CAPTURE_AUDIO_OUTPUT
CAPTURE_MEDIA_OUTPUT
CAPTURE_VOICE_COMMUNICATION_OUTPUT
CALL_AUDIO_INTERCEPTION
CAPTURE_AUDIO_HOTWORD
CAPTURE_TUNER_AUDIO_INPUT
MODIFY_AUDIO_ROUTING
MODIFY_AUDIO_SETTINGS
MODIFY_AUDIO_SETTINGS_PRIVILEGED
MANAGE_AUDIO_POLICY
RECORD_AUDIO
RECORD_BACKGROUND_AUDIO
QUERY_AUDIO_STATE
…
```
(The manifest declares **551** `uses-permission` entries in total — shell is the most privileged ordinary UID on the device.)

**This is exactly why both known techniques work.**

1. **`VOICE_*` capture as shell.** ShizuCallRecorder and JemRec run `app_process / com.genymobile.scrcpy.Server` (or their own shellserver) as UID 2000, which then builds an `AudioRecord` with `VOICE_CALL`. Because the calling UID holds `CAPTURE_AUDIO_OUTPUT` at the *UID* level, the `ServiceUtilities`/AppOps check passes.
2. **The attribution nuance.** cally's README documents that holding the permission is *necessary but not sufficient* for `AudioFlinger`: *"AudioFlinger validates `(uid=2000, pkg="com.android.shell")` against the package DB — the pair is genuine — and opens `VOICE_*` sources as for a system component."* Hence `WrappedShellContext` (cally) and `AttributionSource.myAttributionSource()` with no package (CallVault) — the latter because CallVault's on-device testing found that claiming a *real* Context made the process assert package `"android"` under uid 2000 and get rejected `EX_SECURITY`. CallVault's `VoipAudioPolicy.kt:101-108`:
   > *"A null Context is deliberate and load-bearing. `AudioPolicy` falls back to `AttributionSource.myAttributionSource()`, i.e. our own uid with NO package — which is exactly what AudioFlinger's validator (`createFromTrustedUidNoPackage`) accepts. Obtaining a real Context via `ActivityThread.systemMain()` instead makes the process claim package "android" under uid 2000, and every record-track creation is then rejected EX_SECURITY."*
3. **scrcpy's `Workarounds.java` is the upstream of the hidden-API bypass.** It is referenced by cally's README as the public origin of "the first 5 layers" (yume-chan, March 2023, for `REMOTE_SUBMIX` audio mirroring) and is ported verbatim-in-spirit by JemRec (`shellserver/src/com/genymobile/scrcpy/Workarounds.java`) and named explicitly by CallVault's `BypassedAudioRecord.kt` as *"A port of scrcpy's `Workarounds.createAudioRecord` (Genymobile/scrcpy PR #5154, Apache License 2.0…)"*. It exists to avoid `AudioRecord`'s hidden-API and vendor-modified constructor paths by allocating through the package-private `AudioRecord(long)` constructor and initialising by reflection. **I did not fetch `Workarounds.java` itself — its line-level contents are UNVERIFIED here**; the three independent project references above are the evidence.

**Does the shell UID actually get working `VOICE_CALL` audio?** Yes, with device caveats, and this is well evidenced:
- ShizuCallRecorder reports both sides working on Android 11 (limited) through 16, with a per-version compatibility table; default source is `VOICE_CALL`.
- JemRec reports both sides recorded on Honor MagicOS 10/Android 16, Samsung One UI, and OnePlus LineageOS 23.
- cally reports success on Pixel 6+ and Galaxy S22+/One UI 5+ via its fallback ladder, and explicitly notes the sources are *not* uniformly reliable: it caches the winning strategy per `Build.FINGERPRINT`.
- ShizuCallRecorder warns the technique *"makes use of hidden internal Android APIs. As such, it is prone to breaking in new Android releases or due to specific OEM modifications."*

**Why `CAPTURE_VOICE_COMMUNICATION_OUTPUT` is the VoIP gate even for shell:** CallVault's `VoipAudioPolicy.arm()` returns false with `rc != 0` and logs *"registerAudioPolicy rejected (rc=…) — `CAPTURE_VOICE_COMMUNICATION_OUTPUT` missing?"* (line 121). So although the AOSP shell manifest *declares* the permission, whether it is actually **granted** on a given OEM build is device-dependent. **The AOSP-level grant path for `com.android.shell` (`role` grants / `default-permissions`) is UNVERIFIED** — I verified the *declaration*, not the runtime grant. CallVault's on-device result is the evidence that it sometimes is not granted.

---

## 5. Reuse verdict — shipping separate modules, possibly partly closed-source

The licensing question is decisive here, because the modules you would most want (privileged capture, VoIP far-end tap) are exactly the ones that are GPL.

### 🔴 GPL / AGPL — **do not copy into a closed-source module**

| Project | License | What that means for you |
|---|---|---|
| **madkongo/CallVault** | **GPL-3.0 *or later* + Section 7 additional terms** | 🚩 **Worst licensing position of any project here**, despite being the most technically valuable. GPL-3.0 is viral: linking/deriving forces your whole distributed app under GPL-3.0 with source disclosure. The **Section 7 additional terms** can layer extra attribution/notice conditions on top. It is *also* a fork of ShizuCallRecorder (GPL-3.0) and contains ports of scrcpy code. **You may read it for ideas. You may not copy it into closed-source.** If you want the technique under a permissive license, derive it from the Apache-2.0 upstreams it cites (scrcpy + AOSP) rather than from CallVault. |
| **kitsumed/ShizuCallRecorder** | GPL-3.0 | Same virality. Also bundles `scrcpy-server.jar` (Apache-2.0) inside a GPL app — fine for them, but it means the *bundled jar* is permissive while the *wiring around it* is not. |
| **LyoSU/cally** | GPL-3.0 | Same. The 8-layer write-up is documentation, not code — **re-implementing a documented technique is not a copyright infringement**, but do not copy the source. |
| **jagobandhusome/universal-android-call-recorder** | GPL-3.0 | Same, plus it is 0★ with a self-contradicting README and a provably dead VoIP path. No reason to touch it. |

**AGPL:** none found in this survey (no AGPL-3.0 project among the 16).

> **Not legal advice.** GPL boundaries for Android app modules are genuinely contested (dynamic linking, AIDL IPC boundaries, separate `app_process` binaries). If your business depends on keeping modules closed, get counsel and prefer the permissive path below rather than relying on a boundary argument.

### 🟢 Permissive — safe to reuse with attribution

| Project | License | Safe to reuse | Obligation |
|---|---|---|---|
| **jemcik/JemRec** | Apache-2.0 | ✅ **The best permissive reference for the privilege bootstrap** (loopback self-ADB + scrcpy-derived shell server + typed FGS + self-test). | Keep `LICENSE` + `NOTICE`; state changes. `shellserver/` is scrcpy-derived → also carry scrcpy's Apache-2.0 notice. |
| **AnasAyyad/opencall-recorder** | Apache-2.0 | ✅ Best permissive reference for **ADB-bootstrap security hygiene** (UID-2000 assertion, 0600 token file, SPAKE2 pairing, no arbitrary shell). Also a pure-`javac` build if you want to skip Gradle. | Same. Note the vendored `libadb`/`spake2`/`conscrypt` jars have their own licenses — read `THIRD_PARTY.md`. |
| **sunilxsk/RecordingAudio** | Apache-2.0 | ✅ Best permissive **AudioPlaybackCapture** implementation: per-usage + per-app selection, dual-thread mic mix, overlay, FFmpeg, Media3 playback. | Same. Chinese-only UI — you would need to translate. |
| **arrayforward/internal_audio** | Apache-2.0 | ⚠️ Reusable capture + WAV/M4A trimming + PESQ. | ⚠️ **It pins `targetSdk 29`** to keep legacy storage. Do **not** inherit that — it will fail Play policy. |
| **k2-fsa/sherpa-onnx** | Apache-2.0 | ✅ **Your ASR/VAD/diarization/TTS backend.** Zero capture code, so no conflict with whatever capture layer you build. | Standard Apache-2.0 attribution. |
| **Genymobile/scrcpy** | Apache-2.0 | ✅ **The permissive upstream for the privileged-capture primitive** (`Workarounds.createAudioRecord`, the shell-UID `app_process` model). This is the cleanest route to a closed-source privileged capture layer. | Attribution + NOTICE. |

### 🟡 MIT — safe to reuse, minimal obligation

| Project | License | Safe to reuse | Note |
|---|---|---|---|
| **zeerd/Real-timeTranscription** | MIT (`Copyright (c) 2026 Charles Chan`) | ✅ Good small **MIT** Compose + VAD→ASR→diarization→merge reference. | Mic-only; no capture value. |
| **DaedalusApps/daedalus-echo** | MIT (`Copyright (c) 2026 DaedalusApps`) | ✅ Good **MIT** reference for Room + FTS + Compose + on-device Whisper/Gemma. | ⚠️ **MIT license added as the most recent commit (2026-08-25).** If you reuse anything, pin the commit *after* the license was added; earlier commits were unlicensed. |
| **Eyeing0721/android-internal-audio-recorder** | MIT | ✅ Small, clean, MIT AudioPlaybackCapture + the **ADB/socket control-plane** idea for scripted capture. | Single-author, 0★, very new. |

### 🔴 NO LICENSE — **all rights reserved; do not reuse any code**

| Project | Status | Note |
|---|---|---|
| **timgras2/OpenWhisprAndroid** | no LICENSE, 23 files, no README, 0★ | Also cloud-dependent (Groq). Ignore entirely. |
| **myfreax/AudioRecorder** | no LICENSE, abandoned 2022 | The AudioPlaybackCapture snippet is 4 lines of public API — you can write it yourself from the AOSP javadoc. |
| **GenericJam/mob_audio_capture** | no LICENSE | Elixir, meter-only. **Its README is the quotable part** (the `VOICE_COMMUNICATION` never-captured statement) — citation is fine, code is not. |
| **boldbeastsoft/CallRecordingFix** | no LICENSE, abandoned 2021 | Marketing claim for closed binaries. Nothing to reuse. |
| **developertl-tl/cordova-plugin-callrecorder-accessibility** | no LICENSE, single commit | Wrong audio source for modern Android. Nothing to reuse. |

### Bottom line for your build

If the goal is **WeChat call-audio capture with the option of closed-source modules**:

1. **Do not copy CallVault, ShizuCallRecorder, or cally** — GPL-3.0 is viral and CallVault adds Section 7 terms on top.
2. **Build the privileged capture layer from the Apache-2.0 upstreams:** `Genymobile/scrcpy` for the shell-UID `app_process` + `Workarounds` primitive, and **JemRec** (Apache-2.0) as the architectural template for the self-paired loopback-ADB bootstrap — which avoids forcing users to install Shizuku.
3. **Use `k2-fsa/sherpa-onnx` (Apache-2.0)** for VAD/ASR/diarization, exactly as `zeerd/Real-timeTranscription` and `daedalus-echo` (both MIT) do.
4. **Implement the VoIP far-end tap yourself**, following the *documented* `AudioPolicy`/`AudioMix` + `ROUTE_FLAG_LOOP_BACK_RENDER` on `USAGE_VOICE_COMMUNICATION` approach (arm-early, `MIC` for the near side, capability-detect and fail loudly). Re-implementing a published technique is legitimate; copying the GPL source is not.
5. **Before writing any of it, measure WeChat's actual capture policy on a real device.** CallVault's own comment is the crux: `ALLOW_CAPTURE_BY_NONE` is *"checked before any permission and bypassable by nothing."* If WeChat (微信) sets it, no amount of engineering will capture its call audio, and the correct deliverable is a detection-and-report path rather than a recorder. **This is UNVERIFIED for WeChat and is the single highest-value experiment to run first.**
6. **Also budget for the OEM lottery.** vivo/iQOO is documented as permanently blocked at the platform level (CallVault `docs/SUPPORT.md`), and cally needed a per-`Build.FINGERPRINT` strategy cache. Plan for a capability-matrix UI, not a binary "works" promise.

---

## 6. Reproducibility appendix — exact commands

```bash
# Metadata (stars/forks/license/pushed_at) for one repo
curl -sS "https://api.github.com/repos/kitsumed/ShizuCallRecorder"

# Rate limit check (unauthenticated core = 60/hr, search = 10/min)
curl -sS "https://api.github.com/rate_limit"

# Repo search
curl -sS "https://api.github.com/search/repositories?q=android+call+recorder+shizuku&sort=stars&per_page=8"

# Issue search — the WeChat ledger
curl -sS "https://api.github.com/search/issues?q=repo:kitsumed/ShizuCallRecorder+wechat"   # total_count: 1
curl -sS "https://api.github.com/search/issues?q=repo:madkongo/CallVault+wechat"           # total_count: 0
curl -sS "https://api.github.com/search/issues?q=repo:LyoSU/cally+wechat"                  # total_count: 0
curl -sS "https://api.github.com/search/issues?q=repo:jemcik/JemRec+wechat"                # total_count: 0
curl -sS "https://api.github.com/repos/kitsumed/ShizuCallRecorder/issues/108"

# Shallow clones (no API cost)
git clone --depth 1 https://github.com/kitsumed/ShizuCallRecorder.git
git clone --depth 1 --filter=blob:none --sparse https://github.com/k2-fsa/sherpa-onnx.git \
  && cd sherpa-onnx && git sparse-checkout set android

# Per-repo facts
git -C <dir> log -1 --format="%ad | %s" --date=short
ls <dir> | grep -iE "^licen|^copying"      # then read the file, do not trust the API alone

# Capture-method extraction
grep -rhoE "MediaRecorder\.AudioSource\.[A-Z_]+|AudioSource\.[A-Z_]+" <dir> | sort | uniq -c | sort -rn
grep -rlE "AudioRecord\.Builder|setAudioSource" <dir> | wc -l
grep -rl "AudioPlaybackCaptureConfiguration" <dir> | wc -l
grep -rl "MediaProjection" <dir> | wc -l
grep -rli "shizuku" <dir> | wc -l
grep -rl "CAPTURE_AUDIO_OUTPUT" <dir> | wc -l
grep -rniE "wechat|微信|weixin" <dir>

# AOSP primary sources (raw.githubusercontent.com — no API limit)
B=https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master
curl -sS -o apcc.java  "$B/media/java/android/media/AudioPlaybackCaptureConfiguration.java"
curl -sS -o aa.java    "$B/media/java/android/media/AudioAttributes.java"
curl -sS -o core.xml   "$B/core/res/AndroidManifest.xml"
curl -sS -o shell.xml  "$B/packages/Shell/AndroidManifest.xml"
curl -sS -o ar.java    "$B/media/java/android/media/AudioRecord.java"
curl -sS -o mr.java    "$B/media/java/android/media/MediaRecorder.java"
curl -sS -o attrs.xml  "$B/core/res/res/values/attrs_manifest.xml"
grep -n -A12 '"android.permission.CAPTURE_AUDIO_OUTPUT"' core.xml
grep -oE 'android:name="android\.permission\.[A-Z_]+"' shell.xml | sort -u | grep -iE "AUDIO|RECORD|CAPTURE"

# Platform docs
curl -sS "https://developer.android.com/media/platform/av-capture"
curl -sS "https://developer.android.com/reference/android/Manifest.permission#CAPTURE_AUDIO_OUTPUT"

# The Android 11 ACCESS_CALL_AUDIO revert
curl -sSL "https://android.googlesource.com/platform/frameworks/av/+/57a3769fb12dc8b2df457e0919850bd976716706"
```

**Clones used:** all 16 Android/other repos at `/tmp/callrec/<owner>_<repo>/` (shallow, `--depth 1`), plus `sherpa-onnx` sparse. API budget consumed: **23 of 60 core requests** (1 rate-limit + 16 repo metadata + 1 issue + 5 release listings) and **17 search requests** (6 named-target repo searches + 7 topic repo searches + 4 issue searches, throttled with `sleep` between batches to stay under the 10/min search limit). 0 code-search requests (unauthenticated code search is not available). All source reading, README/LICENSE reading and AOSP fetching was done via `git clone` and `raw.githubusercontent.com`, which do not consume API quota.

**Known gaps / UNVERIFIED items, listed honestly:**
1. Whether **WeChat (微信)** sets `ALLOW_CAPTURE_BY_NONE` — **UNVERIFIED**, requires on-device `dumpsys`. This is the critical unknown.
2. The native enforcement layer for capture policy (`AudioMix.cpp` / `AudioFlinger` mixing rules) — **UNVERIFIED at file/line level**; behaviour is verified from the Java-layer javadoc and four independent projects.
3. Whether `com.android.shell` is actually **granted** `CAPTURE_VOICE_COMMUNICATION_OUTPUT` at runtime (I verified only that AOSP *declares* it) — **UNVERIFIED**; evidence suggests it is device-dependent.
4. Line-level contents of `scrcpy/server/src/main/java/com/genymobile/scrcpy/Workarounds.java` — **UNVERIFIED** (not fetched); three projects reference it independently.
5. AOSP `packages/services/Telecomm` — **UNVERIFIED**: the `aosp-mirror` GitHub mirror does not exist, so no Telecomm call-recording API search was possible.
6. Exact Android 14 MediaProjection behaviour-change wording — **UNVERIFIED**; the `behavior-changes-all` pages for 14/15/16 contained no `MediaProjection` text. The `one time access` + user-revocation statements are verified from the av-capture guide (updated 2026-09-28).
7. Per-example `minSdk` for the 17 `sherpa-onnx` Android examples — **UNVERIFIED** (only the aggregate Android tree was inspected for capture code, which is the fact that matters).
8. Commit *counts* per repo could not be measured from `--depth 1` clones; only last-commit dates are reported.
