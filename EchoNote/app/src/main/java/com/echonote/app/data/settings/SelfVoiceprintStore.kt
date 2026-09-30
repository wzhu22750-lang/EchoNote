package com.echonote.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.echonote.app.ai.EmbeddingUtils
import kotlinx.coroutines.flow.first

/**
 * Stores the device owner's enrolled voiceprint so diarization can identify "我"
 * from evidence rather than by assuming the first/loudest speaker is the owner.
 *
 * The embedding is biometric data: it lives in app-private DataStore (never
 * exported, never uploaded) and is only ever sent to the local on-device speaker
 * model.
 */
class SelfVoiceprintStore(private val context: Context) {

    private val key = stringPreferencesKey("self_voiceprint")

    /** Returns the enrolled voiceprint, or null if the user has not enrolled. */
    suspend fun get(): FloatArray? = runCatching {
        EmbeddingUtils.deserialize(context.settingsDataStore.data.first()[key])
    }.getOrNull()

    suspend fun set(embedding: FloatArray?) {
        context.settingsDataStore.edit { prefs ->
            if (embedding == null) prefs.remove(key) else prefs[key] = EmbeddingUtils.serialize(embedding)
        }
    }

    suspend fun hasVoiceprint(): Boolean = get() != null

    suspend fun clear() = set(null)
}
