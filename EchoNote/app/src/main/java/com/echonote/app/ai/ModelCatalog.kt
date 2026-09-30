package com.echonote.app.ai

/**
 * The verified model catalogue.
 *
 * **Every URL, byte count and licence below was checked live with `curl` against
 * Hugging Face / GitHub Releases on 2026-09-30; see `MODEL_URLS_VERIFIED.md`.**
 * Nothing here was guessed from a README.
 *
 * Two deliberate exclusions:
 *  * **whisper.cpp / ggml** — measured RTF 3.52 on a Galaxy S10 (105 s for 30 s of
 *    audio) versus 0.07 for the same Whisper-tiny checkpoint through sherpa-onnx /
 *    ONNX Runtime. A 51x gap disqualifies it, not an opinion.
 *  * **CT-Transformer punctuation** — 294 MB + 4.2 MB of vocabulary, for a purely
 *    cosmetic gain, because SenseVoice already emits punctuation via inverse text
 *    normalisation. `PunctuationEngine` remains available for users who import the
 *    model themselves through SAF.
 */
object ModelCatalog {

    enum class Role(val id: String, val displayName: String) {
        VAD("vad", "语音活动检测 (VAD)"),
        SPEAKER("speaker", "声纹 / 说话人识别"),
        ASR("asr", "语音识别 (ASR)"),
    }

    data class File(
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
        /** Hex SHA-256 when it has been measured locally; null = size check only. */
        val sha256: String? = null,
    ) {
        val sizeMb: Double get() = sizeBytes / 1024.0 / 1024.0
    }

    data class Bundle(
        val id: String,
        val role: Role,
        val displayName: String,
        val description: String,
        val files: List<File>,
        /** Licence of the **weights**, which is frequently not the code licence. */
        val weightsLicense: String,
        val attribution: String,
        val sourceUrl: String,
    ) {
        val totalBytes: Long get() = files.sumOf { it.sizeBytes }
        val totalMb: Double get() = totalBytes / 1024.0 / 1024.0
    }

    // ---------------------------------------------------------------- VAD

    /** Silero VAD v4/v5 ONNX export. ~1.8 MB, runs in real time on any phone. */
    val VAD_SILERO = Bundle(
        id = "vad_silero",
        role = Role.VAD,
        displayName = "Silero VAD",
        description = "语音活动检测，用于把长录音切成语音片段。约 1.8 MB。",
        files = listOf(
            File(
                fileName = "silero_vad.onnx",
                url = "https://huggingface.co/csukuangfj/vad/resolve/main/silero_vad.onnx",
                sizeBytes = 1_807_522,
                sha256 = "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28",
            ),
        ),
        weightsLicense = "MIT",
        attribution = "Silero VAD (snakers4/silero-vad), MIT",
        sourceUrl = "https://github.com/snakers4/silero-vad",
    )

    // ------------------------------------------------------------ speaker

    /**
     * 3D-Speaker CAM++ trained on 16 kHz Mandarin. This is the voiceprint model
     * behind the 我/对方 split. Apache-2.0 weights keep this shippable.
     *
     * Alternatives verified in the same repository if a user wants to experiment:
     * `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx`,
     * `wespeaker_zh_cnceleb_resnet34.onnx`,
     * `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`.
     */
    val SPEAKER_CAMPP_PLUS_ZH = Bundle(
        id = "speaker_campplus_zh",
        role = Role.SPEAKER,
        displayName = "CAM++ 中文声纹 (3D-Speaker)",
        description = "中文说话人声纹模型，用于自动区分「我 / 对方」。约 27 MB。",
        files = listOf(
            File(
                fileName = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
                url = "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/" +
                    "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
                sizeBytes = 28_281_138,
                    sha256 = "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11",
            ),
        ),
        weightsLicense = "Apache-2.0",
        attribution = "3D-Speaker CAM++ (modelscope/3D-Speaker) via k2-fsa/sherpa-onnx",
        sourceUrl = "https://github.com/modelscope/3D-Speaker",
    )

    // ---------------------------------------------------------------- ASR

    /**
     * Primary recogniser.
     *
     * Chinese accuracy evidence (SenseVoice paper, arXiv 2407.04051v3, Table 6,
     * CER %): AISHELL-1 **2.96** vs Whisper-small 10.04; WenetSpeech meeting
     * **7.44** vs 25.62. RTF on a Galaxy S10 measured at **0.06** by
     * `voiceping-ai/android-offline-transcribe`.
     *
     * ⚠️ **Licence is split.** The SenseVoice *code* is MIT, but the *weights* are
     * under the FunASR MODEL_LICENSE v1.1 — a custom, non-OSI licence that requires
     * attribution and retention of the model name. It contains no non-commercial
     * clause. Recorded in THIRD_PARTY_LICENSES.md.
     */
    val ASR_SENSE_VOICE_SMALL = Bundle(
        id = "asr_sense_voice_small",
        role = Role.ASR,
        displayName = "SenseVoice-Small (int8) — 推荐",
        description = "中文识别准确率最高（AISHELL-1 CER 2.96%），支持中英日韩粤，自带标点与逆文本归一化。约 228 MB。",
        files = listOf(
            File(
                fileName = "model.int8.onnx",
                url = "https://huggingface.co/csukuangfj/" +
                    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
                sizeBytes = 239_233_841,
                    sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            ),
            File(
                fileName = "tokens.txt",
                url = "https://huggingface.co/csukuangfj/" +
                    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
                sizeBytes = 315_894,
                    sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
            ),
        ),
        weightsLicense = "FunASR MODEL_LICENSE v1.1 (自定义，非 OSI)",
        attribution = "SenseVoice-Small, FunAudioLLM / QwenAudio (Alibaba), model licence v1.1",
        sourceUrl = "https://github.com/QwenAudio/SenseVoice",
    )

    /** Smallest viable ASR. Weak Mandarin, but 103 MB and useful on low-end phones. */
    val ASR_WHISPER_TINY = Bundle(
        id = "asr_whisper_tiny",
        role = Role.ASR,
        displayName = "Whisper-tiny (int8) — 轻量",
        description = "体积最小（约 98 MB），多语言，但中文准确率明显低于 SenseVoice。",
        files = listOf(
            File(
                fileName = "tiny-encoder.int8.onnx",
                url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
                sizeBytes = 12_937_772,
            ),
            File(
                fileName = "tiny-decoder.int8.onnx",
                url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
                sizeBytes = 89_855_401,
            ),
            File(
                fileName = "tiny-tokens.txt",
                url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt",
                sizeBytes = 816_730,
            ),
        ),
        weightsLicense = "Apache-2.0",
        attribution = "OpenAI Whisper tiny, weights Apache-2.0, converted by k2-fsa/sherpa-onnx",
        sourceUrl = "https://github.com/openai/whisper",
    )

    /** Fallback with usable Mandarin and cleaner licence than SenseVoice. */
    val ASR_WHISPER_SMALL = Bundle(
        id = "asr_whisper_small",
        role = Role.ASR,
        displayName = "Whisper-small (int8) — 备选",
        description = "许可证最干净（权重 Apache-2.0）。中文准确率低于 SenseVoice，RTF 实测 0.41。约 358 MB。",
        files = listOf(
            File(
                fileName = "small-encoder.int8.onnx",
                url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
                sizeBytes = 112_442_483,
            ),
            File(
                fileName = "small-decoder.int8.onnx",
                url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
                sizeBytes = 262_226_114,
            ),
            File(
                fileName = "small-tokens.txt",
                url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt",
                sizeBytes = 816_730,
            ),
        ),
        weightsLicense = "Apache-2.0",
        attribution = "OpenAI Whisper small, weights Apache-2.0, converted by k2-fsa/sherpa-onnx",
        sourceUrl = "https://github.com/openai/whisper",
    )

    val asrBundles: List<Bundle> = listOf(
        ASR_SENSE_VOICE_SMALL,
        ASR_WHISPER_SMALL,
        ASR_WHISPER_TINY,
    )

    val all: List<Bundle> = listOf(VAD_SILERO, SPEAKER_CAMPP_PLUS_ZH) + asrBundles

    fun byId(id: String): Bundle? = all.firstOrNull { it.id == id }

    /** Everything a default install needs to record, transcribe and diarize. */
    val defaultInstall: List<Bundle> = listOf(VAD_SILERO, SPEAKER_CAMPP_PLUS_ZH, ASR_SENSE_VOICE_SMALL)

    val defaultInstallBytes: Long = defaultInstall.sumOf { it.totalBytes }
}
