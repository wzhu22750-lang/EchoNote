package com.echonote.app.di

import android.content.Context
import com.echonote.app.ai.ModelManager
import com.echonote.app.ai.TranscriptionManager
import com.echonote.app.repository.RecordingRepository
import com.echonote.app.audio.AudioFileDecoder
import com.echonote.app.data.db.EchoNoteDatabase
import com.echonote.app.data.settings.SelfVoiceprintStore
import com.echonote.app.data.settings.SettingsRepository
import com.echonote.app.data.storage.RecordingStorage
import com.k2fsa.sherpa.onnx.VersionInfo

/**
 * Application-scoped object graph.
 *
 * Deliberately a hand-written service locator rather than Hilt: the graph is
 * small, it costs no annotation-processing pass, and every dependency edge stays
 * greppable. Teams adding a 10th feature may prefer DI, but for this app a single
 * inspected wiring point is simpler and faster to build.
 */
class AppContainer(private val appContext: Context) {

    val context: Context get() = appContext

    val database: EchoNoteDatabase by lazy { EchoNoteDatabase.build(appContext) }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val storage: RecordingStorage by lazy { RecordingStorage(appContext) }

    val selfVoiceprint: SelfVoiceprintStore by lazy { SelfVoiceprintStore(appContext) }

    val modelManager: ModelManager by lazy { ModelManager(appContext) }

    private val audioFileDecoder: AudioFileDecoder by lazy { AudioFileDecoder(appContext.cacheDir) }

    val repository: RecordingRepository by lazy { RecordingRepository(this) }

    /** Import + transcription orchestration, shared by the UI and the service. */
    val transcription: TranscriptionManager by lazy { TranscriptionManager(this) }

    fun decoder(): AudioFileDecoder = audioFileDecoder

    /**
     * Reads the real versions out of the loaded native libraries.
     *
     * Touching [VersionInfo] forces `System.loadLibrary("sherpa-onnx-jni")`, so a
     * successful call is also proof that the packaged `.so` files for this ABI
     * resolved and that `libonnxruntime.so` was found by the dynamic linker.
     */
    fun nativeRuntimeInfo(): String = buildString {
        append("sherpa-onnx ").append(VersionInfo.version)
        append(" · onnxruntime ").append(VersionInfo.onnxruntimeVersion)
        append(" · git ").append(VersionInfo.gitSha1)
    }

    /** Human-readable summary of the CPU ABIs this install actually shipped. */
    fun supportedAbis(): List<String> = android.os.Build.SUPPORTED_ABIS.toList()
}
