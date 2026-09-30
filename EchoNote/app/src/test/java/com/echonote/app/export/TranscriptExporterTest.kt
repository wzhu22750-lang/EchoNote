package com.echonote.app.export

import com.echonote.app.data.db.CaptureSource
import com.echonote.app.data.db.RecordingStatus
import com.echonote.app.data.db.RecordingType
import com.echonote.app.data.db.TranscriptionStatus
import com.echonote.app.domain.Recording
import com.echonote.app.domain.Speaker
import com.echonote.app.domain.TranscriptDetail
import com.echonote.app.domain.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [TranscriptExporter]. No Android, no Robolectric, no files:
 * the exporter is `String` in / `String` out by design.
 */
class TranscriptExporterTest {

    // ------------------------------------------------------------- fixtures

    private fun recording(
        id: Long = 1L,
        title: String = "测试录音",
        createdAt: Long = 1_700_000_000_000L,
        startedAt: Long = 1_700_000_000_500L,
        durationMs: Long = 10_000L,
        type: RecordingType = RecordingType.MIC,
        audioPath: String = "/data/user/0/com.echonote.app/files/rec.wav",
        status: RecordingStatus = RecordingStatus.COMPLETED,
        transcriptionStatus: TranscriptionStatus = TranscriptionStatus.COMPLETED,
        sampleRate: Int = 16_000,
        channels: Int = 1,
        fileSize: Long = 32_000L,
        notes: String = "",
        audioSource: String = "MIC",
        captureSource: CaptureSource = CaptureSource.MIC,
        language: String = "auto",
        errorMessage: String? = null,
        speakerCount: Int = 2,
        segmentCount: Int = 2,
        transcriptPreview: String = "",
        imported: Boolean = false,
    ) = Recording(
        id = id,
        title = title,
        createdAt = createdAt,
        startedAt = startedAt,
        durationMs = durationMs,
        type = type,
        audioPath = audioPath,
        status = status,
        transcriptionStatus = transcriptionStatus,
        sampleRate = sampleRate,
        channels = channels,
        fileSize = fileSize,
        notes = notes,
        audioSource = audioSource,
        captureSource = captureSource,
        language = language,
        errorMessage = errorMessage,
        speakerCount = speakerCount,
        segmentCount = segmentCount,
        transcriptPreview = transcriptPreview,
        imported = imported,
    )

    /** Embedding values are deliberately distinctive so a leak is easy to spot. */
    private fun me(embedding: FloatArray? = floatArrayOf(0.125f, -0.75f)) =
        Speaker(id = 1L, recordingId = 1L, name = "我", embedding = embedding, colorIndex = 0, isMe = true)

    private fun other(embedding: FloatArray? = floatArrayOf(0.125f, -0.75f)) =
        Speaker(id = 2L, recordingId = 1L, name = "对方", embedding = embedding, colorIndex = 1, isMe = false)

    private fun segment(
        id: Long = 1L,
        speakerId: Long? = 1L,
        startMs: Long = 0L,
        endMs: Long = 3_000L,
        text: String = "你好",
        confidence: Float = 0.9f,
        isEdited: Boolean = false,
        orderIndex: Int = 0,
    ) = TranscriptSegment(
        id = id,
        recordingId = 1L,
        speakerId = speakerId,
        startMs = startMs,
        endMs = endMs,
        text = text,
        confidence = confidence,
        isEdited = isEdited,
        orderIndex = orderIndex,
    )

    private fun detail(
        recording: Recording = recording(),
        speakers: List<Speaker> = listOf(me(), other()),
        segments: List<TranscriptSegment> = listOf(
            segment(id = 1L, speakerId = 1L, startMs = 3_000L, endMs = 6_000L, text = "你好", orderIndex = 0),
            segment(id = 2L, speakerId = 2L, startMs = 10_000L, endMs = 13_000L, text = "你好，你好", orderIndex = 1),
            segment(id = 3L, speakerId = 2L, startMs = 15_000L, endMs = 18_000L, text = "嗯嗯", orderIndex = 2),
        ),
    ) = TranscriptDetail(recording = recording, speakers = speakers, segments = segments)

    private fun allFormats(detail: TranscriptDetail): List<Pair<ExportFormat, String>> =
        ExportFormat.entries.map { it to TranscriptExporter.export(detail, it) }

    /** True when the string contains a high surrogate not followed by a low one, or vice versa. */
    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return true
                    index += 2
                }
                Character.isLowSurrogate(ch) -> return true
                else -> index += 1
            }
        }
        return false
    }

    // ------------------------------------------------------ enum / dispatch

    @Test
    fun `exportFormatCarriesIdLabelExtensionAndMimeType`() {
        assertEquals("txt", ExportFormat.TXT.id)
        assertEquals("纯文本", ExportFormat.TXT.label)
        assertEquals("md", ExportFormat.MARKDOWN.extension)
        assertEquals("text/markdown", ExportFormat.MARKDOWN.mimeType)
        assertEquals("application/json", ExportFormat.JSON.mimeType)
        assertEquals("application/x-subrip", ExportFormat.SRT.mimeType)
        assertEquals("csv", ExportFormat.CSV.extension)
    }

    @Test
    fun `fromIdResolvesKnownIdsAndFallsBackToTxt`() {
        assertEquals(ExportFormat.TXT, ExportFormat.fromId("txt"))
        assertEquals(ExportFormat.MARKDOWN, ExportFormat.fromId("md"))
        assertEquals(ExportFormat.JSON, ExportFormat.fromId("JSON"))
        assertEquals(ExportFormat.SRT, ExportFormat.fromId(" srt "))
        assertEquals(ExportFormat.CSV, ExportFormat.fromId("csv"))
        assertEquals(ExportFormat.TXT, ExportFormat.fromId(null))
        assertEquals(ExportFormat.TXT, ExportFormat.fromId(""))
        assertEquals(ExportFormat.TXT, ExportFormat.fromId("pdf"))
    }

    @Test
    fun `exportDispatchesToTheRightRenderer`() {
        val d = detail()
        assertEquals(
            TranscriptExporter.toTxt(d, true, true),
            TranscriptExporter.export(d, ExportFormat.TXT, true, true),
        )
        assertEquals(
            TranscriptExporter.toMarkdown(d, true, true),
            TranscriptExporter.export(d, ExportFormat.MARKDOWN, true, true),
        )
        assertEquals(TranscriptExporter.toJson(d), TranscriptExporter.export(d, ExportFormat.JSON))
        assertEquals(TranscriptExporter.toSrt(d), TranscriptExporter.export(d, ExportFormat.SRT))
        assertEquals(TranscriptExporter.toCsv(d), TranscriptExporter.export(d, ExportFormat.CSV))
    }

    // ------------------------------------------------------------ timestamps

    @Test
    fun `formatTimestampOmitsHoursBelowOneHour`() {
        assertEquals("00:00", TranscriptExporter.formatTimestamp(0L))
        assertEquals("00:03", TranscriptExporter.formatTimestamp(3_000L))
        assertEquals("01:03", TranscriptExporter.formatTimestamp(63_000L))
        assertEquals("59:59", TranscriptExporter.formatTimestamp(3_599_000L))
        assertEquals("01:00:00", TranscriptExporter.formatTimestamp(3_600_000L))
        assertEquals("01:01:01", TranscriptExporter.formatTimestamp(3_661_000L))
        assertEquals("00:00", TranscriptExporter.formatTimestamp(-5_000L))
    }

    @Test
    fun `formatClockAlwaysIncludesHours`() {
        assertEquals("00:00:00", TranscriptExporter.formatClock(0L))
        assertEquals("00:00:03", TranscriptExporter.formatClock(3_000L))
        assertEquals("00:01:03", TranscriptExporter.formatClock(63_000L))
        assertEquals("01:01:01", TranscriptExporter.formatClock(3_661_000L))
        assertEquals("00:00:00", TranscriptExporter.formatClock(-1L))
    }

    @Test
    fun `formatSrtTimestampKeepsMilliseconds`() {
        assertEquals("00:00:00,000", TranscriptExporter.formatSrtTimestamp(0L))
        assertEquals("00:00:03,250", TranscriptExporter.formatSrtTimestamp(3_250L))
        assertEquals("01:01:01,001", TranscriptExporter.formatSrtTimestamp(3_661_001L))
        assertEquals("00:00:00,000", TranscriptExporter.formatSrtTimestamp(-42L))
    }

    // ------------------------------------------------------------------ TXT

    @Test
    fun `toTxtRendersHeaderAndOneTurnPerLine`() {
        val txt = TranscriptExporter.toTxt(detail(), includeTimestamps = true, includeSpeakers = true)
        assertTrue(txt.startsWith("标题：测试录音\n"))
        assertTrue(txt.contains("时间："))
        assertTrue(txt.contains("时长：00:00:10\n"))
        assertTrue(txt.contains("录音方式：MIC"))
        assertTrue(txt.contains("说话人数：2\n"))
        assertTrue(txt.contains("[00:03] 我：你好\n"))
        assertTrue(txt.contains("[00:10] 对方：你好，你好\n"))
        assertTrue(txt.contains("[00:15] 对方：嗯嗯\n"))
        assertTrue(txt.endsWith("\n"))
    }

    @Test
    fun `toTxtDegradesToPlainTextWhenBothFlagsAreOff`() {
        val txt = TranscriptExporter.toTxt(detail(), includeTimestamps = false, includeSpeakers = false)
        assertTrue(txt.contains("\n你好\n"))
        assertFalse(txt.contains("[00:03]"))
        assertFalse(txt.contains("我："))
        assertFalse(txt.contains("对方："))
    }

    @Test
    fun `toTxtLabelsUnattributedAndMissingSpeakersAsUnknown`() {
        val d = detail(
            segments = listOf(
                segment(id = 1L, speakerId = null, text = "谁在说话"),
                segment(id = 2L, speakerId = 99L, text = "查无此人"),
            ),
        )
        val txt = TranscriptExporter.toTxt(d, includeTimestamps = true, includeSpeakers = true)
        assertTrue(txt.contains("[00:00] 未知：谁在说话\n"))
        assertTrue(txt.contains("[00:00] 未知：查无此人\n"))
        assertEquals(2, Regex("未知：").findAll(txt).count())
    }

    @Test
    fun `toTxtHandlesAnEmptySegmentList`() {
        val txt = TranscriptExporter.toTxt(detail(segments = emptyList()), includeTimestamps = true, includeSpeakers = true)
        assertTrue(txt.contains("标题：测试录音"))
        assertTrue(txt.contains("说话人数：2"))
        assertFalse(txt.contains("[00:00]"))
        assertTrue(txt.endsWith("\n"))
    }

    // ------------------------------------------------------------- Markdown

    @Test
    fun `toMarkdownRendersHeadingMetadataAndTurns`() {
        val md = TranscriptExporter.toMarkdown(detail(), includeTimestamps = true, includeSpeakers = true)
        assertTrue(md.startsWith("# 测试录音\n\n"))
        assertTrue(md.contains("- 时间："))
        assertTrue(md.contains("- 时长：00:00:10\n"))
        assertTrue(md.contains("- 录音方式：MIC\n"))
        assertTrue(md.contains("- 说话人数：2\n"))
        assertTrue(md.contains("- 段数：3\n"))
        assertTrue(md.contains("**我** _00:03_\n你好\n"))
    }

    @Test
    fun `toMarkdownSeparatesOnlyOnSpeakerChange`() {
        val md = TranscriptExporter.toMarkdown(detail(), includeTimestamps = true, includeSpeakers = true)
        val separatorIndex = md.indexOf("\n---\n")
        assertTrue(separatorIndex > 0)
        // The rule before "---" is the last turn of the first speaker.
        assertTrue(md.substring(0, separatorIndex).trimEnd().endsWith("你好"))
        // Same-speaker turns stay in one paragraph: no "---" between them.
        assertEquals(1, Regex("\n---\n").findAll(md).count())
        assertTrue(md.contains("你好，你好\n**对方** _00:15_\n嗯嗯\n"))
    }

    // ----------------------------------------------------------------- JSON

    @Test
    fun `toJsonEmitsSchemaRecordingSpeakersAndSegments`() {
        val json = TranscriptExporter.toJson(detail())
        assertTrue(json.startsWith("{\n"))
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"recording\": {"))
        assertTrue(json.contains("\"id\": 1,"))
        assertTrue(json.contains("\"title\": \"测试录音\""))
        assertTrue(json.contains("\"createdAt\": \"2023-11-14T22:13:20Z\""))
        assertTrue(json.contains("\"startedAt\": \"2023-11-14T22:13:20.500Z\""))
        assertTrue(json.contains("\"durationMs\": 10000"))
        assertTrue(json.contains("\"type\": \"MIC\""))
        assertTrue(json.contains("\"status\": \"COMPLETED\""))
        assertTrue(json.contains("\"transcriptionStatus\": \"COMPLETED\""))
        assertTrue(json.contains("\"language\": \"auto\""))
        assertTrue(json.contains("\"errorMessage\": null"))
        assertTrue(json.contains("\"imported\": false"))
        assertTrue(json.contains("\"speakers\": ["))
        assertTrue(json.contains("  \"speakers\": [\n    {\n      \"id\": 1,"))
        assertTrue(json.contains("    }\n  ],"))
        assertTrue(json.contains("\"name\": \"我\""))
        assertTrue(json.contains("\"colorIndex\": 0"))
        assertTrue(json.contains("\"isMe\": true"))
        assertTrue(json.contains("\"segments\": ["))
        assertTrue(json.contains("\"speakerName\": \"对方\""))
        assertTrue(json.contains("\"startMs\": 3000"))
        assertTrue(json.contains("\"startClock\": \"00:00:03\""))
        assertTrue(json.contains("\"confidence\": 0.9"))
        assertTrue(json.contains("\"isEdited\": false"))
        assertTrue(json.contains("\"orderIndex\": 2"))
        assertEquals(1, Regex("\"schemaVersion\"").findAll(json).count())
    }

    @Test
    fun `toJsonIsBalancedAndEscapesControlCharacters`() {
        val tricky = "他说：\"好, 行\"\n然后\t走了\u0007"
        val d = detail(segments = listOf(segment(id = 1L, text = tricky)))
        val json = TranscriptExporter.toJson(d)
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
        assertTrue(json.contains("\\\"好, 行\\\""))
        assertTrue(json.contains("\\n然后\\t走了"))
        assertTrue(json.contains("\\u0007"))
        assertFalse(json.contains("\u0007"))
        // The escaped segment text stays on a single line.
        assertTrue(json.lines().any { it.contains("他说") && it.contains("\\n然后\\t走了") })
    }

    @Test
    fun `toJsonKeepsEmojiSurrogatePairsIntact`() {
        val d = detail(segments = listOf(segment(id = 1L, text = "好的 👍🏽 拜拜")))
        val json = TranscriptExporter.toJson(d)
        assertTrue(json.contains("好的 👍🏽 拜拜"))
        assertTrue(json.contains("\"text\": \"好的 👍🏽 拜拜\""))
        // The surrogate pair survived untouched and was not split.
        assertFalse(hasUnpairedSurrogate(json))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
    }

    @Test
    fun `toJsonHandlesEpochZeroTimesAndEmptySegments`() {
        val d = detail(
            recording = recording(createdAt = 0L, startedAt = 0L, durationMs = 0L),
            speakers = emptyList(),
            segments = emptyList(),
        )
        val json = TranscriptExporter.toJson(d)
        assertTrue(json.contains("\"createdAt\": \"1970-01-01T00:00:00Z\""))
        assertTrue(json.contains("\"startedAt\": \"1970-01-01T00:00:00Z\""))
        assertTrue(json.contains("\"speakers\": []"))
        assertTrue(json.contains("\"segments\": []"))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
    }

    @Test
    fun `noFormatEverExportsSpeakerEmbeddings`() {
        val embedding = floatArrayOf(0.125f, -0.75f)
        val d = detail(
            speakers = listOf(me(embedding), other(embedding)),
            segments = listOf(segment(id = 1L, text = "含嵌入"),
                segment(id = 2L, speakerId = 2L, text = "也不该出现")),
        )
        for ((format, output) in allFormats(d)) {
            assertFalse("$format leaked the embedding key", output.contains("embedding"))
            assertFalse("$format leaked 0.125", output.contains("0.125"))
            assertFalse("$format leaked -0.75", output.contains("-0.75"))
            assertFalse("$format leaked FloatArray.toString", output.contains("[F@"))
        }
    }

    @Test
    fun `toJsonOmitsDeviceLocalAudioPath`() {
        val json = TranscriptExporter.toJson(detail())
        assertFalse(json.contains("audioPath"))
        assertFalse(json.contains("/data/user/0"))
    }

    // ------------------------------------------------------------------ SRT

    @Test
    fun `toSrtNumbersCuesAndSeparatesThemWithBlankLines`() {
        val srt = TranscriptExporter.toSrt(detail())
        assertTrue(
            srt.startsWith(
                "1\n00:00:03,000 --> 00:00:06,000\n我: 你好\n" +
                    "\n2\n00:00:10,000 --> 00:00:13,000\n对方: 你好，你好\n" +
                    "\n3\n00:00:15,000 --> 00:00:18,000\n对方: 嗯嗯\n",
            ),
        )
        assertEquals(3, Regex("(?m)^\\d+$").findAll(srt).count())
        assertTrue(srt.endsWith("\n"))
    }

    @Test
    fun `toSrtBumpsNonPositiveDurationsAndOmitsUnknownSpeakers`() {
        val d = detail(
            segments = listOf(
                segment(id = 1L, speakerId = 1L, startMs = 5_000L, endMs = 5_000L, text = "零长度"),
                segment(id = 2L, speakerId = null, startMs = 6_000L, endMs = 4_000L, text = "反向"),
                segment(id = 3L, speakerId = 42L, startMs = 7_000L, endMs = 9_000L, text = "缺失"),
            ),
        )
        val srt = TranscriptExporter.toSrt(d)
        assertTrue(srt.contains("00:00:05,000 --> 00:00:05,001\n我: 零长度"))
        assertTrue(srt.contains("00:00:06,000 --> 00:00:06,001\n反向"))
        assertTrue(srt.contains("00:00:07,000 --> 00:00:09,000\n缺失"))
        assertFalse(srt.contains("未知"))
    }

    @Test
    fun `toSrtHandlesAnEmptySegmentList`() {
        assertEquals("", TranscriptExporter.toSrt(detail(segments = emptyList())))
    }

    // ------------------------------------------------------------------ CSV

    @Test
    fun `toCsvWritesHeaderAndOneRowPerSegment`() {
        val csv = TranscriptExporter.toCsv(detail())
        val lines = csv.trimEnd('\n').lines()
        assertEquals(4, lines.size)
        assertEquals("index,start_ms,end_ms,start_clock,duration_s,speaker,text,confidence,is_edited", lines[0])
        assertEquals("1,3000,6000,00:00:03,3.000,我,你好,0.9,false", lines[1])
        assertEquals("2,10000,13000,00:00:10,3.000,对方,你好，你好,0.9,false", lines[2])
        assertEquals("3,15000,18000,00:00:15,3.000,对方,嗯嗯,0.9,false", lines[3])
        assertTrue(csv.endsWith("\n"))
    }

    @Test
    fun `toCsvQuotesCommasQuotesAndNewlines`() {
        val d = detail(
            segments = listOf(
                segment(id = 1L, speakerId = null, startMs = 0L, endMs = 1_500L, text = "a,b"),
                segment(id = 2L, speakerId = null, startMs = 1_500L, endMs = 2_500L, text = "he said \"hi\"", isEdited = true),
                segment(id = 3L, speakerId = null, startMs = 2_500L, endMs = 3_500L, text = "第一行\n第二行", confidence = 0.5f),
            ),
        )
        val csv = TranscriptExporter.toCsv(d)
        assertTrue(csv.contains("1,0,1500,00:00:00,1.500,未知,\"a,b\",0.9,false"))
        assertTrue(csv.contains("\"he said \"\"hi\"\"\",0.9,true"))
        assertTrue(csv.contains("\"第一行\n第二行\""))
        // The quoted newline is the only way a row can span lines.
        assertEquals(5, csv.trimEnd('\n').lines().size)
    }

    @Test
    fun `toCsvClampsNegativeDurationsAndHandlesEmptySegments`() {
        val d = detail(
            segments = listOf(segment(id = 1L, speakerId = 1L, startMs = 9_000L, endMs = 1_000L, text = "反向")),
        )
        assertTrue(TranscriptExporter.toCsv(d).contains("1,9000,1000,00:00:09,0.000,我,反向,0.9,false"))

        val empty = TranscriptExporter.toCsv(detail(segments = emptyList()))
        assertEquals("index,start_ms,end_ms,start_clock,duration_s,speaker,text,confidence,is_edited\n", empty)
    }

    // -------------------------------------------------------------- escaping

    @Test
    fun `escapeCsvFollowsRfc4180`() {
        assertEquals("plain", TranscriptExporter.escapeCsv("plain"))
        assertEquals("你好，世界", TranscriptExporter.escapeCsv("你好，世界"))
        assertEquals("\"a,b\"", TranscriptExporter.escapeCsv("a,b"))
        assertEquals("\"he said \"\"hi\"\"\"", TranscriptExporter.escapeCsv("he said \"hi\""))
        assertEquals("\"line1\nline2\"", TranscriptExporter.escapeCsv("line1\nline2"))
        assertEquals("\"cr\rlf\"", TranscriptExporter.escapeCsv("cr\rlf"))
        assertEquals("\"\"\"\"", TranscriptExporter.escapeCsv("\""))
        assertEquals("", TranscriptExporter.escapeCsv(""))
    }

    @Test
    fun `escapeJsonEscapesQuotesBackslashesAndControlCharacters`() {
        assertEquals("", TranscriptExporter.escapeJson(""))
        assertEquals("plain", TranscriptExporter.escapeJson("plain"))
        assertEquals("a\\\"b", TranscriptExporter.escapeJson("a\"b"))
        assertEquals("a\\\\b", TranscriptExporter.escapeJson("a\\b"))
        assertEquals("a\\nb\\tc\\rd", TranscriptExporter.escapeJson("a\nb\tc\rd"))
        assertEquals("\\b\\f", TranscriptExporter.escapeJson("\b\u000C"))
        assertEquals("\\u0001", TranscriptExporter.escapeJson("\u0001"))
        assertEquals("中文，标点", TranscriptExporter.escapeJson("中文，标点"))
        assertEquals("👍🏽", TranscriptExporter.escapeJson("👍🏽"))
    }

    // --------------------------------------------------------- speakerLabel

    @Test
    fun `speakerLabelResolvesMeOtherAndUnknown`() {
        val speakers = mapOf(1L to me(), 2L to other(), 7L to Speaker(7L, 1L, "说话人 7", null, 2, false))
        assertEquals("我", TranscriptExporter.speakerLabel(segment(speakerId = 1L), speakers))
        assertEquals("对方", TranscriptExporter.speakerLabel(segment(speakerId = 2L), speakers))
        assertEquals("对方", TranscriptExporter.speakerLabel(segment(speakerId = 7L), speakers))
        assertEquals("未知", TranscriptExporter.speakerLabel(segment(speakerId = null), speakers))
        assertEquals("未知", TranscriptExporter.speakerLabel(segment(speakerId = 404L), speakers))
        assertEquals("未知", TranscriptExporter.speakerLabel(segment(speakerId = 1L), emptyMap()))
    }

    // ------------------------------------------------------- file name hints

    @Test
    fun `suggestedFileNameStripsIllegalCharactersAndWhitespace`() {
        val d = detail(recording = recording(title = "会议/记录: 2024 *草稿*"))
        assertEquals("会议记录2024草稿.txt", TranscriptExporter.suggestedFileName(d, ExportFormat.TXT))
        assertEquals("会议记录2024草稿.md", TranscriptExporter.suggestedFileName(d, ExportFormat.MARKDOWN))
        assertEquals("会议记录2024草稿.srt", TranscriptExporter.suggestedFileName(d, ExportFormat.SRT))
        assertFalse(TranscriptExporter.suggestedFileName(d, ExportFormat.JSON).contains("/"))
    }

    @Test
    fun `suggestedFileNameKeepsEmojiCapsLengthAndAlwaysHasAnExtension`() {
        val emojiTitle = "通话 🎧 记录"
        val emojiName = TranscriptExporter.suggestedFileName(detail(recording = recording(title = emojiTitle)), ExportFormat.JSON)
        assertEquals("通话🎧记录.json", emojiName)

        val longTitle = "标".repeat(120)
        val capped = TranscriptExporter.suggestedFileName(detail(recording = recording(title = longTitle)), ExportFormat.CSV)
        assertEquals("标".repeat(60) + ".csv", capped)
        assertEquals(60 + ".csv".length, capped.length)

        val surrogateTitle = "🎧".repeat(40)
        val surrogateName = TranscriptExporter.suggestedFileName(
            detail(recording = recording(title = surrogateTitle)),
            ExportFormat.TXT,
        )
        assertEquals(".txt", surrogateName.takeLast(4))
        assertEquals(30 * 2 + 4, surrogateName.length)

        val blankName = TranscriptExporter.suggestedFileName(detail(recording = recording(title = "  /  :  ")), ExportFormat.TXT)
        assertEquals("echonote.txt", blankName)
    }

    @Test
    fun `suggestedFileNameFallsBackForABlankTitleAndIncludesTheExtension`() {
        val d = detail(recording = recording(title = ""))
        assertEquals("未命名录音.txt", TranscriptExporter.suggestedFileName(d, ExportFormat.TXT))
        for (format in ExportFormat.entries) {
            val name = TranscriptExporter.suggestedFileName(detail(), format)
            assertTrue(name.endsWith(".${format.extension}"))
            assertFalse(name.contains('/'))
            assertFalse(name.contains('\\'))
            assertFalse(name.contains(':'))
        }
    }
}
