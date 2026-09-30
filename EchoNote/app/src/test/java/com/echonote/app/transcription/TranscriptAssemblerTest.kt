package com.echonote.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [TranscriptAssembler]: ASR spans × speaker spans alignment,
 * including the awkward cases — spans straddling a speaker change, missing token
 * timestamps, unattributable speech, and CJK vs Latin text joining.
 */
class TranscriptAssemblerTest {

    // ------------------------------------------------------------- fixtures

    private fun asr(
        startMs: Long,
        endMs: Long,
        text: String,
        tokens: List<String> = emptyList(),
        tokenTimestamps: List<Float> = emptyList(),
        confidence: Float = 1f,
        emotion: String = "",
        event: String = "",
    ) = TranscriptAssembler.AsrSpan(
        startMs = startMs,
        endMs = endMs,
        text = text,
        tokens = tokens,
        tokenTimestamps = tokenTimestamps,
        confidence = confidence,
        emotion = emotion,
        event = event,
    )

    private fun speaker(startMs: Long, endMs: Long, index: Int, confidence: Float = 1f) =
        TranscriptAssembler.SpeakerSpan(startMs, endMs, index, confidence)

    // ------------------------------------------------------------- filtering

    @Test
    fun `blankAndInvalidAsrSpansAreDropped`() {
        assertTrue(TranscriptAssembler.assemble(emptyList(), emptyList()).isEmpty())

        val speakers = listOf(speaker(0, 2_000, 0))
        assertTrue(TranscriptAssembler.assemble(listOf(asr(0, 1_000, "   ")), speakers).isEmpty())
        assertTrue(TranscriptAssembler.assemble(listOf(asr(500, 500, "零时长")), speakers).isEmpty())
        assertTrue(TranscriptAssembler.assemble(listOf(asr(2_000, 1_000, "反向")), speakers).isEmpty())
    }

    // -------------------------------------------------------- no speaker info

    @Test
    fun `spanWithoutSpeakerSpansIsUnknownUncertainAndTrimmed`() {
        val segments = TranscriptAssembler.assemble(
            listOf(asr(0, 1_000, "  你好  ", emotion = "happy", event = "laugh")),
            emptyList(),
        )

        assertEquals(1, segments.size)
        val s = segments[0]
        assertEquals(TranscriptAssembler.UNKNOWN_SPEAKER, s.speakerIndex)
        assertTrue(s.uncertainSpeaker)
        assertEquals(0f, s.confidence, 0f)
        assertEquals("你好", s.text)
        assertEquals("happy", s.emotion)
        assertEquals("laugh", s.event)
        assertEquals(0L, s.startMs)
        assertEquals(1_000L, s.endMs)
    }

    // ------------------------------------------------- confident attribution

    @Test
    fun `spanInsideOneSpeakerIsAttributedConfidently`() {
        val segments = TranscriptAssembler.assemble(
            listOf(asr(1_000, 2_000, "我来汇报")),
            listOf(speaker(0, 3_000, 0)),
        )

        assertEquals(1, segments.size)
        assertEquals(0, segments[0].speakerIndex)
        assertFalse(segments[0].uncertainSpeaker)
        assertEquals(1f, segments[0].confidence, 1e-6f)
    }

    @Test
    fun `majorityOwnedSpanStaysWholeWithoutUncertainty`() {
        // Speaker A owns 800 of 1000 ms — not splittable (B's 200 ms < minSplitMs),
        // and dominant share 0.8 >= 0.6 so the attribution is not flagged.
        val segments = TranscriptAssembler.assemble(
            listOf(asr(0, 1_000, "大部分是我在说")),
            listOf(speaker(0, 800, 0), speaker(800, 1_000, 1)),
        )

        assertEquals(1, segments.size)
        assertEquals(0, segments[0].speakerIndex)
        assertFalse(segments[0].uncertainSpeaker)
        assertEquals(0.8f, segments[0].confidence, 1e-6f)
    }

    // --------------------------------------------------- speaker change cases

    @Test
    fun `genuineSpeakerChangeWithoutTokensFallsBackToDominant`() {
        // A owns 600 ms, B 400 ms: both >= minSplitMs, so a real change is likely,
        // but without token timestamps an honest split point does not exist.
        val segments = TranscriptAssembler.assemble(
            listOf(asr(0, 1_000, "换人说话了")),
            listOf(speaker(0, 600, 0), speaker(600, 1_000, 1)),
        )

        assertEquals(1, segments.size)
        assertEquals(0, segments[0].speakerIndex) // dominant wins
        assertTrue("must be flagged uncertain", segments[0].uncertainSpeaker)
        assertEquals(0.6f * 0.6f, segments[0].confidence, 1e-5f)
    }

    @Test
    fun `genuineSpeakerChangeWithTokensSplitsIntoRuns`() {
        // Span 0..1000 ms; tokens at 0/100/300/500 ms. A owns [0,300), B [300,1000):
        // 你(50) 好(200) -> A;  世(400) 界(750) -> B.
        val segments = TranscriptAssembler.assemble(
            listOf(
                asr(
                    startMs = 0,
                    endMs = 1_000,
                    text = "你好世界",
                    tokens = listOf("你", "好", "世", "界"),
                    tokenTimestamps = listOf(0.0f, 0.1f, 0.3f, 0.5f),
                ),
            ),
            listOf(speaker(0, 300, 0), speaker(300, 1_000, 1)),
        )

        assertEquals(2, segments.size)
        assertEquals(0, segments[0].speakerIndex)
        assertEquals(0L, segments[0].startMs)
        assertEquals(300L, segments[0].endMs)
        assertEquals("你好", segments[0].text)
        assertFalse(segments[0].uncertainSpeaker)
        assertEquals(0.9f, segments[0].confidence, 1e-6f)

        assertEquals(1, segments[1].speakerIndex)
        assertEquals(300L, segments[1].startMs)
        assertEquals(1_000L, segments[1].endMs)
        assertEquals("世界", segments[1].text)
        assertFalse(segments[1].uncertainSpeaker)
    }

    @Test
    fun `tokenInSpeakerGapIsLabelledUnknown`() {
        // A [0,300), gap [300,400), B [400,1000). Token 世's midpoint (325) falls
        // in the gap and must not be silently attributed to either side.
        val segments = TranscriptAssembler.assemble(
            listOf(
                asr(
                    startMs = 0,
                    endMs = 1_000,
                    text = "你好世界",
                    tokens = listOf("你", "好", "世", "界"),
                    tokenTimestamps = listOf(0.0f, 0.1f, 0.3f, 0.35f),
                ),
            ),
            listOf(speaker(0, 300, 0), speaker(400, 1_000, 1)),
            minSegmentMs = 0, // keep the raw split boundaries visible
        )

        assertEquals(3, segments.size)
        assertEquals(listOf(0L, 300L, 350L), segments.map { it.startMs })
        assertEquals(
            listOf(0, TranscriptAssembler.UNKNOWN_SPEAKER, 1),
            segments.map { it.speakerIndex },
        )
        assertEquals(listOf("你好", "世", "界"), segments.map { it.text })
    }

    @Test
    fun `noOverlapFallsBackToNearestSpeakerByMidpoint`() {
        // The ASR span starts after both speaker spans ended; the nearest span by
        // midpoint distance is B (150 ms away vs 650 ms).
        val segments = TranscriptAssembler.assemble(
            listOf(asr(1_100, 1_200, "串台的话")),
            listOf(speaker(0, 500, 0), speaker(500, 1_000, 1, confidence = 0.5f)),
        )

        assertEquals(1, segments.size)
        assertEquals(1, segments[0].speakerIndex)
        assertTrue(segments[0].uncertainSpeaker)
        assertEquals(0.5f * 0.6f, segments[0].confidence, 1e-6f)
    }

    // -------------------------------------------------------------- merging

    @Test
    fun `adjacentSameSpeakerTurnsMergeAndJoinWithoutSpaces`() {
        val segments = TranscriptAssembler.assemble(
            listOf(
                asr(0, 500, "今天讨论"),
                asr(600, 1_000, "很顺利"),
                asr(1_100, 1_500, "好的"),
            ),
            listOf(speaker(0, 1_000, 0), speaker(1_000, 2_000, 1)),
        )

        assertEquals(2, segments.size)
        assertEquals(0, segments[0].speakerIndex)
        assertEquals(0L, segments[0].startMs)
        assertEquals(1_000L, segments[0].endMs)
        assertEquals("今天讨论很顺利", segments[0].text) // CJK: no space inserted

        assertEquals(1, segments[1].speakerIndex)
        assertEquals("好的", segments[1].text)
    }

    @Test
    fun `latinFragmentsGetASpaceWhenMerged`() {
        val segments = TranscriptAssembler.assemble(
            listOf(asr(0, 500, "hello"), asr(600, 1_000, "world")),
            listOf(speaker(0, 1_000, 0)),
        )

        assertEquals(1, segments.size)
        assertEquals("hello world", segments[0].text)
    }

    @Test
    fun `gapBeyondMaxGapMsPreventsMerging`() {
        val spans = listOf(asr(0, 500, "第一"), asr(1_600, 2_000, "第二"))
        val speakers = listOf(speaker(0, 3_000, 0))

        val separated = TranscriptAssembler.assemble(spans, speakers)
        assertEquals(2, separated.size)

        val joined = TranscriptAssembler.assemble(spans, speakers, maxGapMs = 1_100)
        assertEquals(1, joined.size)
        assertEquals("第一第二", joined[0].text)
        assertEquals(2_000L, joined[0].endMs)
    }

    @Test
    fun `mergeAdjacentFalseKeepsEveryPiece`() {
        val segments = TranscriptAssembler.assemble(
            listOf(
                asr(0, 500, "今天讨论"),
                asr(600, 1_000, "很顺利"),
                asr(1_100, 1_500, "好的"),
            ),
            listOf(speaker(0, 1_000, 0), speaker(1_000, 2_000, 1)),
            mergeAdjacent = false,
        )

        assertEquals(3, segments.size)
        assertEquals(listOf("今天讨论", "很顺利", "好的"), segments.map { it.text })
    }

    // --------------------------------------------------------- duration floor

    @Test
    fun `shortSegmentsAreExtendedToMinSegmentMs`() {
        val segments = TranscriptAssembler.assemble(
            listOf(asr(0, 100, "嗯")),
            listOf(speaker(0, 2_000, 0)),
        )
        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(250L, segments[0].endMs) // default minSegmentMs

        val stretched = TranscriptAssembler.assemble(
            listOf(asr(0, 100, "嗯")),
            listOf(speaker(0, 2_000, 0)),
            minSegmentMs = 1_000,
        )
        assertEquals(1_000L, stretched[0].endMs)
    }

    // ------------------------------------------------- merge side effects

    @Test
    fun `mergingAveragesConfidenceAndPreservesUncertaintyAndEmotion`() {
        // Span 1 straddles A/B (dominant A, 0.6 share -> confidence 0.6, uncertain).
        // Span 2 sits fully inside A (confidence 1.0, certain). Same speaker, gap
        // 100 ms -> merged: confidence averaged, uncertainty sticky.
        val segments = TranscriptAssembler.assemble(
            listOf(
                asr(0, 1_000, "第一句", emotion = "happy"),
                asr(1_100, 2_100, "第二句"),
            ),
            listOf(speaker(0, 2_500, 0), speaker(600, 1_000, 1)),
        )

        assertEquals(1, segments.size)
        assertEquals(0, segments[0].speakerIndex)
        assertEquals("第一句第二句", segments[0].text)
        assertEquals(2_100L, segments[0].endMs)
        assertEquals((0.6f + 1f) / 2f, segments[0].confidence, 1e-5f)
        assertTrue("an uncertain part keeps the flag", segments[0].uncertainSpeaker)
        assertEquals("happy", segments[0].emotion)
    }

    // ----------------------------------------------------------- determinism

    @Test
    fun `assembleIsDeterministic`() {
        val asrSpans = listOf(
            asr(0, 1_000, "你好世界", tokens = listOf("你", "好", "世", "界"), tokenTimestamps = listOf(0f, 0.1f, 0.5f, 0.8f)),
            asr(1_200, 2_000, "第二段"),
            asr(2_100, 3_000, "hello again"),
        )
        val speakers = listOf(speaker(0, 1_500, 0), speaker(1_500, 3_500, 1))

        val first = TranscriptAssembler.assemble(asrSpans, speakers)
        val second = TranscriptAssembler.assemble(asrSpans, speakers)
        assertEquals(first, second)
    }

    // ------------------------------------------------------------- joinText

    @Test
    fun `joinTextRulesCoverCjkLatinWhitespaceAndEmpty`() {
        val join = TranscriptAssembler::joinText

        assertEquals("世界", join("", "世界"))
        assertEquals("你好", join("你好", ""))
        assertEquals("你好世界", join("你好", "世界")) // CJK+CJK: no space
        assertEquals("hello world", join("hello", "world")) // Latin+Latin: space
        assertEquals("你好world", join("你好", "world")) // CJK boundary: no space
        assertEquals("hello你好", join("hello", "你好")) // CJK boundary: no space
        assertEquals("ab", join("a ", "b")) // existing whitespace: no extra space
        assertEquals("ab", join("a", " b"))
        assertEquals("会议，很好", join("会议，", "很好")) // fullwidth punctuation counts as CJK
        assertEquals("カタカナ汉字", join("カタカナ", "汉字")) // kana counts as CJK
    }
}
