package com.echonote.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.echonote.app.EchoNoteApplication
import com.echonote.app.MainActivity
import com.echonote.app.R
import com.echonote.app.audio.MicCaptureEngine
import com.echonote.app.data.db.CaptureSource
import com.echonote.app.data.db.CaptureSource as DbCaptureSource
import com.echonote.app.data.db.RecordingEntity
import com.echonote.app.data.db.RecordingStatus
import com.echonote.app.data.db.RecordingType
import com.echonote.app.data.db.TranscriptionStatus
import com.echonote.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that owns microphone capture.
 *
 * Declared in the manifest as `foregroundServiceType="microphone"`, which is
 * required by Android 14+ for any service that reads the mic. (The former
 * `mediaProjection` companion type and its placeholder trampoline activity were
 * removed: AudioPlaybackCapture is not implemented yet, and a declared-but-hollow
 * capability is worse than an honest absence. Re-add both if that mode is built.)
 *
 * Android 13+ additionally needs `POST_NOTIFICATIONS`, and Android 14+ needs
 * `FOREGROUND_SERVICE_MICROPHONE`. The notification is always visible while
 * recording: it is the system-visible record that a background microphone stream
 * exists, which is precisely the transparency required here — there is no
 * covert recording mode.
 */
class RecordingService : Service() {

    private lateinit var container: AppContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var engine: MicCaptureEngine? = null
    private var wavFile: File? = null
    private var currentRecordingId: Long = 0
    private var source: CaptureSource = CaptureSource.VOICE_RECOGNITION
    private var recordingType = RecordingType.MIC
    private var startedAtWallClock = 0L
    private var pausedAtElapsed = 0L
    private var pausedAccumulated = 0L
    private var tickJob: Job? = null

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    enum class State { IDLE, RECORDING, PAUSED }

    override fun onCreate() {
        super.onCreate()
        container = (application as EchoNoteApplication).container
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            null -> Unit
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------- handlers

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun handleStart(intent: Intent) {
        if (_state.value != State.IDLE) return

        source = runCatching {
            CaptureSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: CaptureSource.VOICE_RECOGNITION.name)
        }.getOrDefault(CaptureSource.VOICE_RECOGNITION)
        recordingType = runCatching {
            RecordingType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: RecordingType.MIC.name)
        }.getOrDefault(RecordingType.MIC)

        // Establish the foreground notification *before* opening the microphone.
        _state.value = State.RECORDING
        startForegroundWithType()
        notifyRecording()

        val file = container.storage.newRecordingFile()
        val engine = MicCaptureEngine(
            storage = container.storage,
            aecEnabled = container.settings.blockingAec(),
            nsEnabled = container.settings.blockingNs(),
        )

        try {
            engine.start(file, source)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start capture: ${t.message}", t)
            stopForegroundCompat()
            stopSelf()
            _state.value = State.IDLE
            return
        }

        this.engine = engine
        this.wavFile = file
        startedAtWallClock = System.currentTimeMillis()

        currentRecordingId = runBlocking {
            container.database.recordingDao().insert(
                RecordingEntity(
                    title = defaultTitle(),
                    createdAt = startedAtWallClock,
                    startedAt = startedAtWallClock,
                    duration = 0,
                    recordingType = recordingType,
                    audioPath = "",
                    status = RecordingStatus.RECORDING,
                    transcriptionStatus = TranscriptionStatus.NOT_STARTED,
                    sampleRate = 16_000,
                    channels = 1,
                    fileSize = 0,
                    audioSource = source.name,
                    captureSource = source.toDb(),
                )
            )
        }

        startTicking()
    }

    private fun handlePause() {
        if (_state.value != State.RECORDING) return
        engine?.pause()
        pausedAtElapsed = SystemClock.elapsedRealtime()
        _state.value = State.PAUSED
        scope.launch {
            container.database.recordingDao().updateStatus(currentRecordingId, RecordingStatus.PAUSED)
        }
        notifyRecording()
    }

    private fun handleResume() {
        if (_state.value != State.PAUSED) return
        pausedAccumulated += SystemClock.elapsedRealtime() - pausedAtElapsed
        pausedAtElapsed = 0L
        engine?.resume()
        _state.value = State.RECORDING
        scope.launch {
            container.database.recordingDao().updateStatus(currentRecordingId, RecordingStatus.RECORDING)
        }
        notifyRecording()
    }

    private fun handleStop() {
        if (_state.value == State.IDLE) return
        val durationMs = engine?.stop() ?: 0L
        val file = wavFile
        val recordingId = currentRecordingId
        engine = null
        wavFile = null
        tickJob?.cancel()

        scope.launch(Dispatchers.IO) {
            if (file != null && file.isFile && file.length() > RecordingService.MIN_BYTES) {
                container.database.recordingDao().finalize(
                    id = recordingId,
                    duration = durationMs,
                    fileSize = file.length(),
                    sampleRate = 16_000,
                    channels = 1,
                    audioPath = file.absolutePath,
                    status = RecordingStatus.COMPLETED,
                    error = null,
                )
                container.transcription.enqueue(recordingId)
            } else {
                // Too short (or absent) to be a real recording: drop the file and
                // mark the row failed so it never shows up as a playable "blank".
                file?.delete()
                container.database.recordingDao().finalize(
                    id = recordingId, duration = durationMs, fileSize = 0,
                    sampleRate = 16_000, channels = 1, audioPath = "",
                    status = RecordingStatus.FAILED, error = "录音过短或未产生音频",
                )
            }
        }

        _state.value = State.IDLE
        stopForegroundCompat()
        stopSelf()
    }

    // -------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.recording_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.recording_channel_description)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ demands the type be passed in explicitly.
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun control(action: String, label: String): NotificationCompat.Action =
            NotificationCompat.Action(
                0, label,
                PendingIntent.getService(
                    this, action.hashCode(),
                    Intent(this, RecordingService::class.java).setAction(action),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )

        val running = _state.value != State.IDLE
        val title = if (_state.value == State.PAUSED) "录音已暂停" else "正在录音"
        val elapsed = elapsedMs()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("${source.label()} · ${formatDuration(elapsed)}")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapIntent)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (_state.value) {
            State.RECORDING -> {
                builder.addAction(control(ACTION_PAUSE, "暂停"))
                builder.addAction(control(ACTION_STOP, "停止"))
            }

            State.PAUSED -> {
                builder.addAction(control(ACTION_RESUME, "继续"))
                builder.addAction(control(ACTION_STOP, "停止"))
            }

            else -> Unit
        }
        return builder.build()
    }

    private fun notifyRecording() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (_state.value != State.IDLE) {
                delay(1000)
                notifyRecording()
            }
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        if (_state.value != State.IDLE) {
            // Process death / system kill: finalise what we have rather than
            // leaving a half-written WAV with a bogus header.
            runCatching { handleStop() }
        }
        scope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------- util

    private fun elapsedMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return (now - startedAtWallClock) - pausedAccumulated
    }

    private fun defaultTitle(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(startedAtWallClock))

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    private fun CaptureSource.label(): String = when (this) {
        CaptureSource.MIC -> "麦克风"
        CaptureSource.VOICE_RECOGNITION -> "麦克风 · 语音识别"
        CaptureSource.VOICE_COMMUNICATION -> "麦克风 · 通话优化"
        CaptureSource.CAMCORDER -> "麦克风 · 摄像头"
        else -> name
    }

    private fun CaptureSource.toDb(): DbCaptureSource = when (this) {
        CaptureSource.MIC -> DbCaptureSource.MIC
        CaptureSource.VOICE_RECOGNITION -> DbCaptureSource.VOICE_RECOGNITION
        CaptureSource.VOICE_COMMUNICATION -> DbCaptureSource.VOICE_COMMUNICATION
        CaptureSource.CAMCORDER -> DbCaptureSource.CAMCORDER
        CaptureSource.DEFAULT -> DbCaptureSource.DEFAULT
        CaptureSource.PLAYBACK_CAPTURE -> DbCaptureSource.PLAYBACK_CAPTURE
        CaptureSource.MIXED -> DbCaptureSource.MIXED
        CaptureSource.IMPORTED -> DbCaptureSource.IMPORTED
        CaptureSource.UNPROCESSED -> DbCaptureSource.UNPROCESSED
    }

    companion object {
        private const val TAG = "EchoNote/Recording"
        private const val CHANNEL_ID = "echonote_recording"
        private const val NOTIFICATION_ID = 1001

        /** Anything shorter than this after stop() is treated as a discarded start. */
        private const val MIN_BYTES = 16_000L * 2 // ~0.5 s of mono 16 kHz PCM16

        const val ACTION_START = "com.echonote.app.action.START"
        const val ACTION_PAUSE = "com.echonote.app.action.PAUSE"
        const val ACTION_RESUME = "com.echonote.app.action.RESUME"
        const val ACTION_STOP = "com.echonote.app.action.STOP"
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_TYPE = "extra_type"

        fun start(context: Context, source: CaptureSource, type: RecordingType) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE, source.name)
                putExtra(EXTRA_TYPE, type.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun send(context: Context, action: String) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(action)
            )
        }
    }
}
