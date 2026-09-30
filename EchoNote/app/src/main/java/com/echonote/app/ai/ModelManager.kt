package com.echonote.app.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.echonote.app.EchoNoteApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs and verifies the ONNX models the pipeline needs.
 *
 * Downloads land in app-private storage and are resumable: a partial file is kept
 * as `<name>.part` and continued with an HTTP `Range` request, which matters
 * because the recommended ASR model is 228 MB and phone networks drop.
 *
 * Integrity is checked two ways — the declared `Content-Length` (or the pinned
 * byte count from [ModelCatalog]) must match, and the file must later load in ONNX
 * Runtime. A pinned SHA-256 is additionally verified when the catalogue has one.
 */
class ModelManager(
    private val context: Context,
    private val rootOverride: File? = null,
) {

    /** `<filesDir>/models`, or the user's chosen override from Settings. */
    val root: File
        get() = (rootOverride ?: File(context.filesDir, "models")).apply { if (!exists()) mkdirs() }

    fun bundleDir(bundle: ModelCatalog.Bundle): File =
        File(root, bundle.id).apply { if (!exists()) mkdirs() }

    fun fileFor(bundle: ModelCatalog.Bundle, file: ModelCatalog.File): File =
        File(bundleDir(bundle), file.fileName)

    /** Absolute path the sherpa-onnx engines are handed. */
    fun pathFor(bundle: ModelCatalog.Bundle, file: ModelCatalog.File): String =
        fileFor(bundle, file).absolutePath

    /** Path of the single-file bundle member, or "" when the bundle has several. */
    fun singlePath(bundle: ModelCatalog.Bundle): String =
        bundle.files.singleOrNull()?.let { pathFor(bundle, it) } ?: ""

    fun isInstalled(bundle: ModelCatalog.Bundle): Boolean =
        bundle.files.all { fileFor(bundle, it).let { f -> f.isFile && f.length() > 0 } }

    fun missingFiles(bundle: ModelCatalog.Bundle): List<ModelCatalog.File> =
        bundle.files.filter { !fileFor(bundle, it).let { f -> f.isFile && f.length() > 0 } }

    fun installedBundles(): List<ModelCatalog.Bundle> = ModelCatalog.all.filter { isInstalled(it) }

    fun installedBytes(): Long = ModelCatalog.all
        .filter { isInstalled(it) }
        .sumOf { bundle -> bundle.files.sumOf { fileFor(bundle, it).length() } }

    fun delete(bundle: ModelCatalog.Bundle) {
        bundleDir(bundle).deleteRecursively()
    }

    /** Bytes already fetched for [bundle] across its files plus partials. */
    fun partialBytes(bundle: ModelCatalog.Bundle): Long =
        bundle.files.sumOf { file ->
            val target = fileFor(bundle, file)
            val part = File(target.parentFile, target.name + PART_SUFFIX)
            when {
                target.isFile -> target.length()
                part.isFile -> part.length()
                else -> 0L
            }
        }

    data class Progress(
        val bundleId: String,
        val fileName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val completedFiles: Int,
        val totalFiles: Int,
    ) {
        val fraction: Float
            get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    /**
     * Downloads every missing file of [bundle].
     *
     * @throws IOException on a network failure, a size mismatch, or a hash mismatch.
     */
    suspend fun download(
        bundle: ModelCatalog.Bundle,
        onProgress: (Progress) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val missing = missingFiles(bundle)
        var completed = bundle.files.size - missing.size
        var alreadyFetched = bundle.files
            .filter { isInstalledFile(it, bundle) }
            .sumOf { it.sizeBytes }

        for (file in missing) {
            val target = fileFor(bundle, file)
            val part = File(target.parentFile, target.name + PART_SUFFIX)
            val resumeFrom = if (part.isFile) part.length() else 0L

            if (resumeFrom > file.sizeBytes) {
                // Local partial is larger than the pinned size: it is corrupt.
                part.delete()
            }

            downloadOne(
                url = file.url,
                part = part,
                resumeFrom = if (part.isFile) part.length() else 0L,
                expectedBytes = file.sizeBytes,
            ) { fileBytes ->
                onProgress(
                    Progress(
                        bundleId = bundle.id,
                        fileName = file.fileName,
                        downloadedBytes = alreadyFetched + fileBytes,
                        totalBytes = bundle.totalBytes,
                        completedFiles = completed,
                        totalFiles = bundle.files.size,
                    )
                )
            }

            val actual = part.length()
            if (file.sizeBytes > 0 && actual != file.sizeBytes) {
                throw IOException(
                    "${file.fileName}: expected ${file.sizeBytes} bytes, got $actual"
                )
            }
            val pinned = file.sha256
            if (pinned != null) {
                val digest = sha256(part)
                if (!digest.equals(pinned, ignoreCase = true)) {
                    part.delete()
                    throw IOException("${file.fileName}: SHA-256 mismatch")
                }
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            alreadyFetched += actual
            completed++
            onProgress(
                Progress(
                    bundleId = bundle.id,
                    fileName = file.fileName,
                    downloadedBytes = alreadyFetched,
                    totalBytes = bundle.totalBytes,
                    completedFiles = completed,
                    totalFiles = bundle.files.size,
                )
            )
        }
    }

    private fun isInstalledFile(file: ModelCatalog.File, bundle: ModelCatalog.Bundle): Boolean =
        fileFor(bundle, file).let { it.isFile && it.length() > 0 }

    private fun downloadOne(
        url: String,
        part: File,
        resumeFrom: Long,
        expectedBytes: Long,
        onBytes: (Long) -> Unit,
    ) {
        part.parentFile?.mkdirs()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }

        try {
            val code = connection.responseCode
            val resuming = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
            if (code !in 200..299) {
                throw IOException("HTTP $code for $url")
            }

            val startAt = if (resuming) resumeFrom else 0L
            if (!resuming && part.exists()) part.delete()

            connection.inputStream.use { input ->
                FileOutputStream(part, resuming).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var written = startAt
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= PROGRESS_STEP_BYTES) {
                            lastReport = written
                            onBytes(written)
                        }
                    }
                    output.flush()
                    onBytes(written)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Copies a user-supplied model file from a SAF `content://` URI into the bundle
     * directory. This is the no-network path to a working install, and the only way
     * to add models that are not in [ModelCatalog].
     */
    suspend fun importFromUri(bundle: ModelCatalog.Bundle, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: bundle.files.first().fileName
        val target = File(bundleDir(bundle), name)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open $uri" }
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        target.absolutePath
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val PROGRESS_STEP_BYTES = 2L * 1024 * 1024
        private const val USER_AGENT = "EchoNote/1.0 (Android; offline transcription)"

        /** Convenience for callers that only have a Context. */
        fun from(context: Context): ModelManager = ModelManager(context)
    }
}

/** Resolves which on-disk model set a given preference selects. */
object ModelResolver {

    fun asrFiles(manager: ModelManager, modelId: String): AsrModelFiles? {
        val bundle = ModelCatalog.byId(modelId) ?: return null
        if (bundle.role != ModelCatalog.Role.ASR) return null
        if (!manager.isInstalled(bundle)) return null

        val paths = bundle.files.associate { it.fileName to manager.pathFor(bundle, it) }
        return when (bundle.id) {
            ModelCatalog.ASR_SENSE_VOICE_SMALL.id -> AsrModelFiles(
                family = AsrFamily.SENSE_VOICE,
                modelPath = paths["model.int8.onnx"].orEmpty(),
                tokensPath = paths["tokens.txt"].orEmpty(),
            )

            ModelCatalog.ASR_WHISPER_SMALL.id -> AsrModelFiles(
                family = AsrFamily.WHISPER,
                encoderPath = paths["small-encoder.int8.onnx"].orEmpty(),
                decoderPath = paths["small-decoder.int8.onnx"].orEmpty(),
                tokensPath = paths["small-tokens.txt"].orEmpty(),
            )

            ModelCatalog.ASR_WHISPER_TINY.id -> AsrModelFiles(
                family = AsrFamily.WHISPER,
                encoderPath = paths["tiny-encoder.int8.onnx"].orEmpty(),
                decoderPath = paths["tiny-decoder.int8.onnx"].orEmpty(),
                tokensPath = paths["tiny-tokens.txt"].orEmpty(),
            )

            else -> null
        }?.takeIf { it.allPresent() }
    }

    fun vadPath(manager: ModelManager): String? {
        if (!manager.isInstalled(ModelCatalog.VAD_SILERO)) return null
        return manager.singlePath(ModelCatalog.VAD_SILERO)
    }

    fun speakerPath(manager: ModelManager): String? {
        if (!manager.isInstalled(ModelCatalog.SPEAKER_CAMPP_PLUS_ZH)) return null
        return manager.singlePath(ModelCatalog.SPEAKER_CAMPP_PLUS_ZH)
    }
}

/** Small logging helper so model installs are traceable in logcat. */
internal fun logModel(event: String) = Log.i(EchoNoteApplication.TAG, "model: $event")
