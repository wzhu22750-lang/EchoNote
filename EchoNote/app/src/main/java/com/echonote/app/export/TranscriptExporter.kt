package com.echonote.app.export

import com.echonote.app.domain.Recording
import com.echonote.app.domain.Speaker
import com.echonote.app.domain.TranscriptDetail
import com.echonote.app.domain.TranscriptSegment
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A transcript import/export format.
 *
 * `id` is the stable value persisted in settings and used by the UI; it is also
 * the default file extension, but the two are kept separate so a future format
 * (for example "vtt") can diverge.
 */
enum class ExportFormat(
    val id: String,
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    TXT("txt", "纯文本", "txt", "text/plain"),
    MARKDOWN("md", "Markdown", "md", "text/markdown"),
    JSON("json", "JSON", "json", "application/json"),
    SRT("srt", "SRT 字幕", "srt", "application/x-subrip"),
    CSV("csv", "CSV 表格", "csv", "text/csv"),
    ;

    companion object {
        /** Resolves a persisted id; anything unknown (including null) falls back to [TXT]. */
        fun fromId(id: String?): ExportFormat {
            val key = id?.trim()?.lowercase(Locale.ROOT) ?: return TXT
            if (key.isEmpty()) return TXT
            return entries.firstOrNull {
                it.id == key || it.extension == key || it.name.equals(key, ignoreCase = true)
            } ?: TXT
        }
    }
}

/**
 * Renders a [TranscriptDetail] into shareable text.
 *
 * Everything here is pure Kotlin: `String` in, `String` out, no `android.*` and
 * no file I/O, so the whole surface is exercised by plain JVM unit tests. Callers
 * own writing the bytes to disk or handing them to a share intent.
 *
 * Privacy: [Speaker.embedding] is biometric data and is **never** exported. The
 * JSON writer omits the key entirely; no other format touches it either.
 */
object TranscriptExporter {

    private val UTC_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** Marks characters that cannot appear in a file name on any Android-supported FS. */
    private val ILLEGAL_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|]""")
    private val WHITESPACE = Regex("""\s+""")
    private val CONTROL_CHARS = Regex("""[\u0000-\u001F\u007F]""")

    private const val MAX_FILE_NAME_CHARS = 60
    private const val FALLBACK_TITLE = "未命名录音"
    private const val FALLBACK_FILE_NAME = "echonote"

    // ------------------------------------------------------------------ entry

    fun export(
        detail: TranscriptDetail,
        format: ExportFormat,
        includeTimestamps: Boolean = true,
        includeSpeakers: Boolean = true,
    ): String = when (format) {
        ExportFormat.TXT -> toTxt(detail, includeTimestamps, includeSpeakers)
        ExportFormat.MARKDOWN -> toMarkdown(detail, includeTimestamps, includeSpeakers)
        ExportFormat.JSON -> toJson(detail)
        ExportFormat.SRT -> toSrt(detail)
        ExportFormat.CSV -> toCsv(detail)
    }

    // -------------------------------------------------------------------- TXT

    /**
     * Human readable transcript: a metadata header, a blank line, then one line
     * per segment (`[00:03] 我：你好`). With both flags off it degrades to plain
     * text lines.
     */
    fun toTxt(
        detail: TranscriptDetail,
        includeTimestamps: Boolean,
        includeSpeakers: Boolean,
    ): String {
        val speakers = detail.speakers.associateBy { it.id }
        val out = StringBuilder()
        out.append("标题：").append(displayTitle(detail.recording)).append('\n')
        out.append("时间：").append(formatUtcDateTime(detail.recording.createdAt)).append('\n')
        out.append("时长：").append(formatClock(detail.recording.durationMs)).append('\n')
        out.append("录音方式：").append(detail.recording.captureLabel).append('\n')
        out.append("说话人数：").append(speakerCount(detail)).append('\n')
        out.append('\n')

        for (segment in detail.segments) {
            if (includeTimestamps) {
                out.append('[').append(formatTimestamp(segment.startMs)).append("] ")
            }
            if (includeSpeakers) {
                out.append(speakerLabel(segment, speakers)).append('：')
            }
            out.append(segment.text).append('\n')
        }
        return out.toString()
    }

    // --------------------------------------------------------------- Markdown

    /**
     * `# title`, a metadata bullet list, then `**我** _00:03_` with the text on the
     * following line. A blank line plus `---` is emitted only when the speaker
     * changes, so consecutive segments from one speaker read as a single paragraph.
     */
    fun toMarkdown(
        detail: TranscriptDetail,
        includeTimestamps: Boolean,
        includeSpeakers: Boolean,
    ): String {
        val speakers = detail.speakers.associateBy { it.id }
        val out = StringBuilder()
        out.append("# ").append(displayTitle(detail.recording)).append("\n\n")
        out.append("- 时间：").append(formatUtcDateTime(detail.recording.createdAt)).append('\n')
        out.append("- 时长：").append(formatClock(detail.recording.durationMs)).append('\n')
        out.append("- 录音方式：").append(detail.recording.captureLabel).append('\n')
        out.append("- 说话人数：").append(speakerCount(detail)).append('\n')
        out.append("- 段数：").append(detail.segments.size).append('\n')
        if (detail.segments.isEmpty()) return out.toString()
        out.append('\n')

        // With speakers disabled there is no speaker concept in the output, so
        // every turn counts as "same speaker" and no separator is emitted.
        var previousLabel: String? = if (includeSpeakers) null else SAME_SPEAKER
        for (segment in detail.segments) {
            val label = if (includeSpeakers) speakerLabel(segment, speakers) else SAME_SPEAKER
            if (previousLabel != null && label != previousLabel) {
                out.append("\n---\n\n")
            }
            val heading = ArrayList<String>(2)
            if (includeSpeakers) heading.add("**$label**")
            if (includeTimestamps) heading.add("_${formatTimestamp(segment.startMs)}_")
            if (heading.isNotEmpty()) {
                out.append(heading.joinToString(" ")).append('\n')
            }
            out.append(segment.text).append('\n')
            previousLabel = label
        }
        return out.toString()
    }

    // ------------------------------------------------------------------- JSON

    /**
     * Pretty-printed (2-space indent) JSON document. Hand-rolled so the app ships
     * no JSON dependency and so the field order stays stable for diffing.
     *
     * The local `audioPath` is deliberately omitted (device-specific absolute
     * path) and speaker embeddings are never emitted.
     */
    fun toJson(detail: TranscriptDetail): String {
        val recording = detail.recording
        val speakers = detail.speakers
        val byId = speakers.associateBy { it.id }
        val out = StringBuilder()
        out.append("{\n")
        out.append("  \"schemaVersion\": 1,\n")
        out.append("  \"recording\": ").append(jsonRecording(recording, "  ")).append(",\n")
        out.append("  \"speakers\": ")
        appendJsonArray(out, speakers, "  ") { speaker, indent -> jsonSpeaker(speaker, indent) }
        out.append(",\n")
        out.append("  \"segments\": ")
        appendJsonArray(out, detail.segments, "  ") { segment, indent ->
            jsonSegment(segment, byId, indent)
        }
        out.append("\n}\n")
        return out.toString()
    }

    private fun jsonRecording(recording: Recording, indent: String): String {
        val field = "$indent  "
        return buildString {
            append("{\n")
            append(field).append("\"id\": ").append(recording.id).append(",\n")
            append(field).append("\"title\": ").append(jsonString(recording.title)).append(",\n")
            append(field).append("\"createdAt\": ")
                .append(jsonString(formatIsoUtc(recording.createdAt))).append(",\n")
            append(field).append("\"startedAt\": ")
                .append(jsonString(formatIsoUtc(recording.startedAt))).append(",\n")
            append(field).append("\"durationMs\": ").append(recording.durationMs).append(",\n")
            append(field).append("\"type\": ").append(jsonString(recording.type.name)).append(",\n")
            append(field).append("\"status\": ").append(jsonString(recording.status.name)).append(",\n")
            append(field).append("\"transcriptionStatus\": ")
                .append(jsonString(recording.transcriptionStatus.name)).append(",\n")
            append(field).append("\"sampleRate\": ").append(recording.sampleRate).append(",\n")
            append(field).append("\"channels\": ").append(recording.channels).append(",\n")
            append(field).append("\"fileSize\": ").append(recording.fileSize).append(",\n")
            append(field).append("\"notes\": ").append(jsonString(recording.notes)).append(",\n")
            append(field).append("\"audioSource\": ")
                .append(jsonString(recording.audioSource)).append(",\n")
            append(field).append("\"captureSource\": ")
                .append(jsonString(recording.captureSource.name)).append(",\n")
            append(field).append("\"captureLabel\": ")
                .append(jsonString(recording.captureLabel)).append(",\n")
            append(field).append("\"language\": ").append(jsonString(recording.language)).append(",\n")
            append(field).append("\"errorMessage\": ")
                .append(recording.errorMessage?.let { jsonString(it) } ?: "null").append(",\n")
            append(field).append("\"speakerCount\": ").append(recording.speakerCount).append(",\n")
            append(field).append("\"segmentCount\": ").append(recording.segmentCount).append(",\n")
            append(field).append("\"transcriptPreview\": ")
                .append(jsonString(recording.transcriptPreview)).append(",\n")
            append(field).append("\"imported\": ").append(recording.imported).append('\n')
            append(indent).append('}')
        }
    }

    /** Only non-identifying speaker fields; `embedding` is biometric and is never written. */
    private fun jsonSpeaker(speaker: Speaker, indent: String): String {
        val field = "$indent  "
        return buildString {
            append("{\n")
            append(field).append("\"id\": ").append(speaker.id).append(",\n")
            append(field).append("\"name\": ").append(jsonString(speaker.name)).append(",\n")
            append(field).append("\"colorIndex\": ").append(speaker.colorIndex).append(",\n")
            append(field).append("\"isMe\": ").append(speaker.isMe).append('\n')
            append(indent).append('}')
        }
    }

    private fun jsonSegment(
        segment: TranscriptSegment,
        speakers: Map<Long, Speaker>,
        indent: String,
    ): String {
        val field = "$indent  "
        val speaker = segment.speakerId?.let { speakers[it] }
        return buildString {
            append("{\n")
            append(field).append("\"id\": ").append(segment.id).append(",\n")
            append(field).append("\"speakerId\": ")
                .append(segment.speakerId?.toString() ?: "null").append(",\n")
            append(field).append("\"speakerName\": ")
                .append(speaker?.name?.let { jsonString(it) } ?: "null").append(",\n")
            append(field).append("\"startMs\": ").append(segment.startMs).append(",\n")
            append(field).append("\"endMs\": ").append(segment.endMs).append(",\n")
            append(field).append("\"startClock\": ")
                .append(jsonString(formatClock(segment.startMs))).append(",\n")
            append(field).append("\"text\": ").append(jsonString(segment.text)).append(",\n")
            append(field).append("\"confidence\": ").append(jsonNumber(segment.confidence)).append(",\n")
            append(field).append("\"isEdited\": ").append(segment.isEdited).append(",\n")
            append(field).append("\"orderIndex\": ").append(segment.orderIndex).append('\n')
            append(indent).append('}')
        }
    }

    private inline fun <T> appendJsonArray(
        out: StringBuilder,
        items: List<T>,
        indent: String,
        render: (T, String) -> String,
    ) {
        if (items.isEmpty()) {
            out.append("[]")
            return
        }
        out.append("[\n")
        val itemIndent = "$indent  "
        items.forEachIndexed { index, item ->
            out.append(itemIndent).append(render(item, itemIndent))
            if (index != items.lastIndex) out.append(',')
            out.append('\n')
        }
        out.append(indent).append(']')
    }

    // -------------------------------------------------------------------- SRT

    /**
     * SubRip cues, one per segment. Unknown speakers contribute no prefix line.
     * A cue whose `endMs` does not exceed `startMs` is widened by 1 ms so players
     * never see a zero-length (dropped) subtitle.
     */
    fun toSrt(detail: TranscriptDetail): String {
        if (detail.segments.isEmpty()) return ""
        val speakers = detail.speakers.associateBy { it.id }
        val blocks = detail.segments.mapIndexed { index, segment ->
            val endMs = if (segment.endMs > segment.startMs) segment.endMs else segment.startMs + 1
            val speaker = segment.speakerId?.let { speakers[it] }
            val body = if (speaker != null && speaker.name.isNotBlank()) {
                "${speaker.name}: ${segment.text}"
            } else {
                segment.text
            }
            buildString {
                append(index + 1).append('\n')
                append(formatSrtTimestamp(segment.startMs))
                append(" --> ")
                append(formatSrtTimestamp(endMs)).append('\n')
                append(body)
            }
        }
        return blocks.joinToString(separator = "\n\n", postfix = "\n")
    }

    // -------------------------------------------------------------------- CSV

    /**
     * RFC 4180 style table. `end_ms` is exported verbatim (no SRT-style widening)
     * because this is a data export, not a cue sheet; `duration_s` is clamped at
     * zero so a malformed row can never yield a negative duration.
     */
    fun toCsv(detail: TranscriptDetail): String {
        val speakers = detail.speakers.associateBy { it.id }
        val out = StringBuilder()
        out.append("index,start_ms,end_ms,start_clock,duration_s,speaker,text,confidence,is_edited\n")
        detail.segments.forEachIndexed { index, segment ->
            val durationSeconds = (segment.endMs - segment.startMs).coerceAtLeast(0L) / 1000.0
            out.append(index + 1).append(',')
            out.append(segment.startMs).append(',')
            out.append(segment.endMs).append(',')
            out.append(escapeCsv(formatClock(segment.startMs))).append(',')
            out.append(String.format(Locale.ROOT, "%.3f", durationSeconds)).append(',')
            out.append(escapeCsv(speakerLabel(segment, speakers))).append(',')
            out.append(escapeCsv(segment.text)).append(',')
            out.append(csvNumber(segment.confidence)).append(',')
            out.append(segment.isEdited).append('\n')
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- helpers

    /** `MM:SS` below one hour, `HH:MM:SS` at or above it. Negative input clamps to zero. */
    fun formatTimestamp(ms: Long): String {
        val totalSeconds = coerceSeconds(ms)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    /** Always `HH:MM:SS`. Negative input clamps to zero. */
    fun formatClock(ms: Long): String {
        val totalSeconds = coerceSeconds(ms)
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d",
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60,
        )
    }

    /** `HH:MM:SS,mmm`, the SubRip cue timestamp. Negative input clamps to zero. */
    fun formatSrtTimestamp(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val totalSeconds = safe / 1000L
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d,%03d",
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60,
            safe % 1000L,
        )
    }

    /** `我` for the device owner, `对方` for anyone else, `未知` when unattributed. */
    fun speakerLabel(segment: TranscriptSegment, speakers: Map<Long, Speaker>): String {
        val speaker = segment.speakerId?.let { speakers[it] } ?: return "未知"
        return if (speaker.isMe) "我" else "对方"
    }

    /** Escapes a CSV field per RFC 4180: quote when needed, double embedded quotes. */
    fun escapeCsv(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        val out = StringBuilder(value.length + 8)
        out.append('"')
        for (ch in value) {
            if (ch == '"') out.append("\"\"") else out.append(ch)
        }
        out.append('"')
        return out.toString()
    }

    /**
     * Escapes the *contents* of a JSON string (no surrounding quotes are added).
     * Control characters become `\uXXXX`, and non-ASCII text — including emoji
     * surrogate pairs — is passed through untouched so it survives verbatim.
     */
    fun escapeJson(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') {
                    out.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }

    /**
     * A safe download/share file name: title with `[\\/:*?"<>|]`, whitespace and
     * control characters removed, capped at 60 characters (never splitting a
     * surrogate pair) and ending in `.<extension>`.
     */
    fun suggestedFileName(detail: TranscriptDetail, format: ExportFormat): String {
        val sanitised = sanitiseFileName(displayTitle(detail.recording))
        val base = if (sanitised.isEmpty()) FALLBACK_FILE_NAME else sanitised
        return "${capChars(base, MAX_FILE_NAME_CHARS)}.${format.extension}"
    }

    // ---------------------------------------------------------- private utils

    private const val SAME_SPEAKER = "\u0000same-speaker"

    private fun displayTitle(recording: Recording): String =
        recording.title.ifBlank { FALLBACK_TITLE }

    private fun speakerCount(detail: TranscriptDetail): Int =
        if (detail.speakers.isNotEmpty()) detail.speakers.size else detail.recording.speakerCount

    private fun coerceSeconds(ms: Long): Long = (if (ms < 0L) 0L else ms) / 1000L

    private fun formatIsoUtc(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    private fun formatUtcDateTime(epochMs: Long): String =
        UTC_DATE_TIME.format(Instant.ofEpochMilli(epochMs))

    private fun jsonString(value: String): String = "\"" + escapeJson(value) + "\""

    /** JSON has no NaN/Infinity literal, so a non-finite float degrades to `null`. */
    private fun jsonNumber(value: Float): String =
        if (value.isFinite()) value.toString() else "null"

    private fun csvNumber(value: Float): String =
        if (value.isFinite()) value.toString() else ""

    private fun sanitiseFileName(raw: String): String = raw
        .replace(ILLEGAL_FILE_NAME_CHARS, "")
        .replace(WHITESPACE, "")
        .replace(CONTROL_CHARS, "")

    /** Truncates to [max] chars without leaving a dangling high surrogate. */
    private fun capChars(value: String, max: Int): String {
        if (value.length <= max) return value
        var end = max
        if (Character.isHighSurrogate(value[end - 1])) end -= 1
        return value.substring(0, end)
    }
}
