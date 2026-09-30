package com.echonote.app.transcription

/**
 * Turns raw pipeline output into the transcript the user actually reads.
 *
 * Inputs are two independently-timed streams:
 *  * ASR spans — what was said, and roughly when.
 *  * Speaker spans — who was talking, from voiceprint clustering.
 *
 * They do not share boundaries: diarization works on VAD spans while the
 * recogniser may report token-level times inside them. Aligning them honestly
 * (rather than assuming a 1:1 correspondence) is the whole job of this object.
 *
 * Everything here is pure Kotlin and covered by unit tests, including the
 * awkward cases: a segment that straddles a speaker change, an unattributable
 * segment, and mixed Chinese/Latin text joining.
 */
object TranscriptAssembler {

    /** `speakerIndex == UNKNOWN` means "the diarizer could not attribute this". */
    const val UNKNOWN_SPEAKER = -1

    data class AsrSpan(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val tokens: List<String> = emptyList(),
        /** Per-token start times in **seconds**, relative to this span's start. */
        val tokenTimestamps: List<Float> = emptyList(),
        val emotion: String = "",
        val event: String = "",
        val confidence: Float = 1f,
    )

    data class SpeakerSpan(
        val startMs: Long,
        val endMs: Long,
        val speakerIndex: Int,
        val confidence: Float = 1f,
    )

    data class MergedSegment(
        val startMs: Long,
        val endMs: Long,
        val speakerIndex: Int,
        val text: String,
        val confidence: Float,
        val emotion: String = "",
        val event: String = "",
        /** True when the span straddled a speaker change and had to be split or
         *  forced onto the dominant speaker. Surfaced in the UI as "待确认". */
        val uncertainSpeaker: Boolean = false,
    )

    /**
     * @param mergeAdjacent collapse consecutive same-speaker segments separated by
     *        at most [maxGapMs]. On, because VAD emits one span per breath and an
     *        un-merged transcript reads like a telegram.
     * @param maxGapMs largest silence that still counts as the same turn.
     * @param minSegmentMs floor on segment duration so a click cannot become a turn.
     * @param minSplitMs a speaker must own at least this much of a span before the
     *        span is split across speakers.
     */
    fun assemble(
        asr: List<AsrSpan>,
        speakers: List<SpeakerSpan>,
        mergeAdjacent: Boolean = true,
        maxGapMs: Long = 900,
        minSegmentMs: Long = 250,
        minSplitMs: Long = 400,
        joinThreshold: Float = 0.55f,
    ): List<MergedSegment> {
        val ordered = asr
            .filter { it.text.isNotBlank() && it.endMs > it.startMs }
            .sortedBy { it.startMs }

        val pieces = ArrayList<MergedSegment>(ordered.size)
        for (span in ordered) {
            pieces += splitBySpeaker(span, speakers, minSplitMs, joinThreshold)
        }

        val normalised = pieces
            .filter { it.text.isNotBlank() }
            .map { segment ->
                if (segment.endMs - segment.startMs >= minSegmentMs) {
                    segment
                } else {
                    segment.copy(endMs = segment.startMs + minSegmentMs)
                }
            }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))

        return if (mergeAdjacent) mergeRuns(normalised, maxGapMs) else normalised
    }

    /**
     * Decides which speaker owns [span], splitting it when the evidence supports
     * that. Splitting without token timestamps is not possible honestly, so in that
     * case the dominant speaker wins and the segment is flagged uncertain.
     */
    private fun splitBySpeaker(
        span: AsrSpan,
        speakers: List<SpeakerSpan>,
        minSplitMs: Long,
        joinThreshold: Float,
    ): List<MergedSegment> {
        if (speakers.isEmpty()) {
            return listOf(
                MergedSegment(
                    startMs = span.startMs,
                    endMs = span.endMs,
                    speakerIndex = UNKNOWN_SPEAKER,
                    text = span.text.trim(),
                    confidence = 0f,
                    emotion = span.emotion,
                    event = span.event,
                    uncertainSpeaker = true,
                )
            )
        }

        // Total overlap per speaker.
        val overlapBySpeaker = LinkedHashMap<Int, Long>()
        for (s in speakers) {
            val lo = maxOf(span.startMs, s.startMs)
            val hi = minOf(span.endMs, s.endMs)
            if (hi > lo) {
                overlapBySpeaker[s.speakerIndex] = (overlapBySpeaker[s.speakerIndex] ?: 0L) + (hi - lo)
            }
        }

        if (overlapBySpeaker.isEmpty()) {
            // No temporal overlap at all (possible if the two streams drifted).
            // Fall back to the nearest speaker span by midpoint distance.
            val midpoint = (span.startMs + span.endMs) / 2
            val nearest = speakers.minByOrNull { s ->
                when {
                    midpoint < s.startMs -> s.startMs - midpoint
                    midpoint > s.endMs -> midpoint - s.endMs
                    else -> 0L
                }
            }
            return listOf(
                MergedSegment(
                    startMs = span.startMs,
                    endMs = span.endMs,
                    speakerIndex = nearest?.speakerIndex ?: UNKNOWN_SPEAKER,
                    text = span.text.trim(),
                    confidence = (nearest?.confidence ?: 0f) * 0.6f,
                    emotion = span.emotion,
                    event = span.event,
                    uncertainSpeaker = true,
                )
            )
        }

        val dominant = overlapBySpeaker.maxByOrNull { it.value }!!
        val spanMs = span.endMs - span.startMs
        val dominantShare = dominant.value.toFloat() / spanMs

        val splittable = overlapBySpeaker.count { it.value >= minSplitMs } >= 2
        if (!splittable) {
            val confidence = if (dominantShare >= 0.6f) dominantShare else dominantShare * 0.7f
            return listOf(
                MergedSegment(
                    startMs = span.startMs,
                    endMs = span.endMs,
                    speakerIndex = dominant.key,
                    text = span.text.trim(),
                    confidence = confidence,
                    emotion = span.emotion,
                    event = span.event,
                    uncertainSpeaker = dominantShare < 0.6f,
                )
            )
        }

        // A genuine speaker change inside this ASR span.
        if (span.tokenTimestamps.size == span.tokens.size && span.tokens.isNotEmpty()) {
            return splitByTokens(span, speakers)
        }

        // No token timing available: attribute the whole span to the speaker that
        // holds the majority, and say so rather than inventing a split point.
        return listOf(
            MergedSegment(
                startMs = span.startMs,
                endMs = span.endMs,
                speakerIndex = dominant.key,
                text = span.text.trim(),
                confidence = dominantShare * 0.6f,
                emotion = span.emotion,
                event = span.event,
                uncertainSpeaker = true,
            )
        )
    }

    /** Assigns each token to the speaker owning its midpoint, then regroups runs. */
    private fun splitByTokens(span: AsrSpan, speakers: List<SpeakerSpan>): List<MergedSegment> {
        val runs = ArrayList<MergedSegment>()
        var runStart = span.startMs
        var runSpeaker = speakerOf(span.startMs, speakers)
        val buffer = StringBuilder()

        for (i in span.tokens.indices) {
            val tokenStartMs = span.startMs + (span.tokenTimestamps[i] * 1000f).toLong()
            val tokenEndMs = if (i + 1 < span.tokenTimestamps.size) {
                span.startMs + (span.tokenTimestamps[i + 1] * 1000f).toLong()
            } else {
                span.endMs
            }
            val owner = speakerOf((tokenStartMs + tokenEndMs) / 2, speakers)
            if (owner != runSpeaker && buffer.isNotBlank()) {
                runs += MergedSegment(
                    startMs = runStart,
                    endMs = tokenStartMs,
                    speakerIndex = runSpeaker,
                    text = buffer.toString().trim(),
                    confidence = 0.9f,
                    emotion = span.emotion,
                    event = span.event,
                )
                buffer.clear()
                runStart = tokenStartMs
                runSpeaker = owner
            }
            buffer.append(span.tokens[i])
        }

        if (buffer.isNotBlank()) {
            runs += MergedSegment(
                startMs = runStart,
                endMs = span.endMs,
                speakerIndex = runSpeaker,
                text = buffer.toString().trim(),
                confidence = 0.9f,
                emotion = span.emotion,
                event = span.event,
            )
        }
        return if (runs.isEmpty()) {
            listOf(
                MergedSegment(
                    startMs = span.startMs,
                    endMs = span.endMs,
                    speakerIndex = speakerOf((span.startMs + span.endMs) / 2, speakers),
                    text = span.text.trim(),
                    confidence = 0.6f,
                    uncertainSpeaker = true,
                )
            )
        } else {
            runs
        }
    }

    private fun speakerOf(atMs: Long, speakers: List<SpeakerSpan>): Int =
        speakers.firstOrNull { atMs >= it.startMs && atMs < it.endMs }?.speakerIndex ?: UNKNOWN_SPEAKER

    /** Collapses consecutive same-speaker segments into readable turns. */
    private fun mergeRuns(segments: List<MergedSegment>, maxGapMs: Long): List<MergedSegment> {
        if (segments.isEmpty()) return segments
        val out = ArrayList<MergedSegment>(segments.size)
        var current = segments[0]

        for (i in 1 until segments.size) {
            val next = segments[i]
            val sameSpeaker = next.speakerIndex == current.speakerIndex
            val gap = next.startMs - current.endMs
            val adjacentInTime = gap <= maxGapMs && next.startMs >= current.startMs

            if (sameSpeaker && adjacentInTime) {
                current = current.copy(
                    endMs = maxOf(current.endMs, next.endMs),
                    text = joinText(current.text, next.text),
                    // Merging averages confidence; an uncertain part keeps the flag.
                    confidence = (current.confidence + next.confidence) / 2f,
                    uncertainSpeaker = current.uncertainSpeaker || next.uncertainSpeaker,
                    emotion = current.emotion.ifBlank { next.emotion },
                    event = current.event.ifBlank { next.event },
                )
            } else {
                out += current
                current = next
            }
        }
        out += current
        return out
    }

    /**
     * Joins two transcript fragments. A space is inserted only when both sides use
     * a space-separated script, so Chinese text does not end up with stray spaces.
     */
    fun joinText(left: String, right: String): String {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val l = left.last()
        val r = right.first()
        return if (isCjk(l) || isCjk(r) || l.isWhitespace() || r.isWhitespace()) {
            left + right
        } else {
            "$left $right"
        }
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) ||   // CJK Unified Ideographs
            (code in 0x3400..0x4DBF) ||      // Extension A
            (code in 0x3000..0x303F) ||      // CJK punctuation
            (code in 0xFF00..0xFFEF) ||      // Fullwidth forms
            (code in 0x3040..0x30FF)         // Hiragana/Katakana
    }
}
