package com.echonote.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.echonote.app.data.db.CaptureSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "echonote_settings",
)

/** User-selectable ASR back ends. Only one large model is ever wired in at a time. */
enum class AsrModel(
    val id: String,
    val displayName: String,
    val approxSizeMb: Int,
) {
    SENSE_VOICE_SMALL("sense_voice_small", "SenseVoice-Small (int8, 中英日韩粤)", 230),
    WHISPER_SMALL("whisper_small", "Whisper-small (int8, 多语言)", 480),
    WHISPER_BASE("whisper_base", "Whisper-base (int8, 轻量)", 145),
    NONE("none", "未选择", 0),
    ;

    companion object {
        fun fromId(id: String?): AsrModel = entries.firstOrNull { it.id == id } ?: SENSE_VOICE_SMALL
    }
}

/** How eagerly the pipeline runs after a recording finishes. */
enum class TranscriptionTrigger(val id: String, val label: String) {
    MANUAL("manual", "仅手动触发"),
    ON_CHARGE("on_charge", "充电且空闲时自动"),
    IMMEDIATE("immediate", "录音结束后立即转写"),
    ;

    companion object {
        fun fromId(id: String?): TranscriptionTrigger =
            entries.firstOrNull { it.id == id } ?: MANUAL
    }
}

/**
 * All persisted user preferences. Local-first by construction: nothing here
 * enables an upload, and [cloudAsrConsent] defaults to false.
 */
data class EchoNoteSettings(
    val preferredCaptureSource: CaptureSource = CaptureSource.VOICE_RECOGNITION,
    val enableAcousticEchoCanceler: Boolean = true,
    val enableNoiseSuppressor: Boolean = true,
    val enableAutoGainControl: Boolean = false,
    val asrModel: AsrModel = AsrModel.SENSE_VOICE_SMALL,
    val language: String = "auto",
    val useInverseTextNormalization: Boolean = true,
    val enableDiarization: Boolean = true,
    /** Fixed speaker count when > 0, otherwise the clusterer picks the count. */
    val forcedSpeakerCount: Int = 2,
    val clusteringThreshold: Float = 0.5f,
    val enablePunctuation: Boolean = true,
    val transcriptionTrigger: TranscriptionTrigger = TranscriptionTrigger.MANUAL,
    val keepScreenOnWhileRecording: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val dynamicColor: Boolean = true,
    /** Never true without an explicit opt-in from the Settings screen. */
    val cloudAsrConsent: Boolean = false,
    val modelsRootPath: String = "",
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val captureSource = stringPreferencesKey("capture_source")
        val aec = booleanPreferencesKey("aec")
        val ns = booleanPreferencesKey("ns")
        val agc = booleanPreferencesKey("agc")
        val asrModel = stringPreferencesKey("asr_model")
        val language = stringPreferencesKey("language")
        val itn = booleanPreferencesKey("itn")
        val diarization = booleanPreferencesKey("diarization")
        val forcedSpeakers = intPreferencesKey("forced_speakers")
        val clusterThreshold = intPreferencesKey("cluster_threshold_e3")
        val punctuation = booleanPreferencesKey("punctuation")
        val trigger = stringPreferencesKey("transcription_trigger")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val playbackSpeedE2 = intPreferencesKey("playback_speed_e2")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val cloudConsent = booleanPreferencesKey("cloud_asr_consent")
        val modelsRoot = stringPreferencesKey("models_root")
    }

    val settings: Flow<EchoNoteSettings> = context.settingsDataStore.data.map { p ->
        EchoNoteSettings(
            preferredCaptureSource = p[Keys.captureSource]
                ?.let { name -> CaptureSource.entries.firstOrNull { it.name == name } }
                ?: CaptureSource.VOICE_RECOGNITION,
            enableAcousticEchoCanceler = p[Keys.aec] ?: true,
            enableNoiseSuppressor = p[Keys.ns] ?: true,
            enableAutoGainControl = p[Keys.agc] ?: false,
            asrModel = AsrModel.fromId(p[Keys.asrModel]),
            language = p[Keys.language] ?: "auto",
            useInverseTextNormalization = p[Keys.itn] ?: true,
            enableDiarization = p[Keys.diarization] ?: true,
            forcedSpeakerCount = p[Keys.forcedSpeakers] ?: 2,
            clusteringThreshold = (p[Keys.clusterThreshold] ?: 500) / 1000f,
            enablePunctuation = p[Keys.punctuation] ?: true,
            transcriptionTrigger = TranscriptionTrigger.fromId(p[Keys.trigger]),
            keepScreenOnWhileRecording = p[Keys.keepScreenOn] ?: false,
            playbackSpeed = (p[Keys.playbackSpeedE2] ?: 100) / 100f,
            dynamicColor = p[Keys.dynamicColor] ?: true,
            cloudAsrConsent = p[Keys.cloudConsent] ?: false,
            modelsRootPath = p[Keys.modelsRoot] ?: "",
        )
    }

    suspend fun current(): EchoNoteSettings = settings.first()

    /**
     * Synchronous read for code that is already off the main thread (the recording
     * service reads capture settings from its own thread before opening the mic).
     * Falls back to the default when DataStore has not been read yet.
     */
    fun blockingAec(): Boolean = runBlocking { current().enableAcousticEchoCanceler }

    fun blockingNs(): Boolean = runBlocking { current().enableNoiseSuppressor }

    suspend fun setCaptureSource(value: CaptureSource) = edit { it[Keys.captureSource] = value.name }

    suspend fun setEchoCanceler(enabled: Boolean) = edit { it[Keys.aec] = enabled }

    suspend fun setNoiseSuppressor(enabled: Boolean) = edit { it[Keys.ns] = enabled }

    suspend fun setAutoGainControl(enabled: Boolean) = edit { it[Keys.agc] = enabled }

    suspend fun setAsrModel(model: AsrModel) = edit { it[Keys.asrModel] = model.id }

    suspend fun setLanguage(language: String) = edit { it[Keys.language] = language }

    suspend fun setInverseTextNormalization(enabled: Boolean) = edit { it[Keys.itn] = enabled }

    suspend fun setDiarization(enabled: Boolean) = edit { it[Keys.diarization] = enabled }

    suspend fun setForcedSpeakerCount(count: Int) = edit { it[Keys.forcedSpeakers] = count }

    suspend fun setClusteringThreshold(threshold: Float) =
        edit { it[Keys.clusterThreshold] = (threshold * 1000).toInt() }

    suspend fun setPunctuation(enabled: Boolean) = edit { it[Keys.punctuation] = enabled }

    suspend fun setTranscriptionTrigger(trigger: TranscriptionTrigger) =
        edit { it[Keys.trigger] = trigger.id }

    suspend fun setKeepScreenOn(enabled: Boolean) = edit { it[Keys.keepScreenOn] = enabled }

    suspend fun setPlaybackSpeed(speed: Float) =
        edit { it[Keys.playbackSpeedE2] = (speed * 100).toInt() }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.dynamicColor] = enabled }

    /** Only ever called from an explicit, informed toggle in the Settings screen. */
    suspend fun setCloudAsrConsent(enabled: Boolean) = edit { it[Keys.cloudConsent] = enabled }

    suspend fun setModelsRoot(path: String) = edit { it[Keys.modelsRoot] = path }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
