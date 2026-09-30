package com.echonote.app.data.storage

import android.content.Context
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns every file EchoNote writes. Everything lives under the app-private
 * `filesDir`, so no storage permission is ever required and nothing is readable
 * by other apps (or by WeChat).
 */
class RecordingStorage(private val context: Context) {

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    val recordingsDir: File
        get() = File(context.filesDir, "recordings").ensure()

    val exportsDir: File
        get() = File(context.filesDir, "exports").ensure()

    val importsDir: File
        get() = File(context.filesDir, "imports").ensure()

    /** Tear-down scratch space for decoders that need a whole file on disk. */
    val workDir: File
        get() = File(context.cacheDir, "work").ensure()

    fun newRecordingFile(): File =
        File(recordingsDir, "REC_${stamp.format(Date())}.wav")

    fun newExportFile(name: String, extension: String): File {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(60).ifBlank { "echonote" }
        return File(exportsDir, "${safe}_${stamp.format(Date())}.$extension")
    }

    fun newImportFile(displayName: String): File {
        val safe = displayName.replace(Regex("[\\\\/:*?\"<>|]+"), "_").take(80).ifBlank { "import.bin" }
        return File(importsDir, "${stamp.format(Date())}_$safe")
    }

    fun newWorkFile(name: String): File = File(workDir, name)

    fun fileFor(path: String): File = File(path)

    fun exists(path: String): Boolean = path.isNotBlank() && File(path).isFile

    fun sizeOf(path: String): Long = if (path.isBlank()) 0L else File(path).length()

    fun delete(path: String): Boolean {
        if (path.isBlank()) return false
        val f = File(path)
        // Guard against a corrupted DB row pointing outside our sandbox.
        return f.absolutePath.startsWith(context.filesDir.absolutePath) && f.delete()
    }

    fun totalBytes(): Long = recordingsDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun freeBytes(): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    private fun File.ensure(): File = apply { if (!exists()) mkdirs() }
}
