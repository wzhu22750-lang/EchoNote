package com.echonote.app

import android.app.Application
import android.util.Log
import com.echonote.app.data.db.EchoNoteDatabase
import com.echonote.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EchoNoteApplication : Application() {

    /** Manual service locator. Chosen over Hilt so the project keeps a single,
     *  inspectable wiring point and builds without an annotation processor pass. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Probe the AI runtime once, off the main thread, so the first transcribe
        // request does not pay for it and so Settings can show real versions.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val info = runCatching { container.nativeRuntimeInfo() }
            info.onSuccess { Log.i(TAG, "sherpa-onnx runtime: $it") }
                .onFailure { Log.e(TAG, "sherpa-onnx runtime unavailable", it) }
        }
    }

    companion object {
        const val TAG = "EchoNote"
    }
}

/** Convenience accessor for the application-scoped container. */
val Application.echoNoteContainer: AppContainer
    get() = (this as EchoNoteApplication).container

/** Exposed for tests / background services that only hold a Context. */
internal fun databaseOf(application: Application): EchoNoteDatabase =
    application.echoNoteContainer.database
