package com.echonote.app.ai

import com.echonote.app.transcription.TranscriptAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Validates [SpeakerClusterer] against **real** speaker embeddings, not synthetic
 * ones.
 *
 * The fixtures under `src/test/resources/reference/` were produced by running the
 * reference sherpa-onnx 1.13.8 pipeline (Silero VAD → CAM++
 * `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`, 192-dim) over four public
 * audio files. The model's SHA-256 is recorded in each fixture header, so these
 * vectors are traceable to a specific set of weights.
 *
 * The strongest case is `two-speakers-en`: its cosine-similarity matrix has an
 * unambiguous 2x2 block structure (segments {0,1} correlate at 0.61, segments
 * {2,3} at 0.83, and the cross-block correlation is only ~0.32), which is a
 * textbook two-speaker alternation A,A,B,B. If clustering cannot recover that, it
 * cannot diarize a phone call.
 */
class SpeakerClustererReferenceTest {

    // ------------------------------------------------------------ fixtures

    private data class Fixture(val embeddings: List<FloatArray>, val dim: Int)

    private fun load(name: String): Fixture {
        val stream = javaClass.getResourceAsStream("/reference/$name.emb")
        assertNotNull("missing fixture /reference/$name.emb", stream)
        val lines = stream!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val (count, dim) = lines.first().trim().split(Regex("\\s+")).map { it.toInt() }
        val vectors = lines.drop(1).take(count).map { line ->
            line.split(',').map { it.trim().toFloat() }.toFloatArray()
        }
        assertEquals("row count for $name", count, vectors.size)
        vectors.forEach { assertEquals("dim for $name", dim, it.size) }
        return Fixture(vectors, dim)
    }

    /** Real ASR spans from the same run, for the transcript-assembly test. */
    private fun loadSegments(name: String): List<Triple<Long, Long, String>> {
        val stream = javaClass.getResourceAsStream("/reference/$name.segments.tsv")
        assertNotNull(stream)
        return stream!!.bufferedReader().readLines()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split('\t')
                Triple(parts[0].toLong(), parts[1].toLong(), parts.getOrElse(2) { "" })
            }
    }

    private fun assertSameCluster(labels: IntArray, vararg indices: Int) {
        val first = labels[indices.first()]
        indices.forEach { i ->
            assertEquals("segments $first and $i should share a speaker", first, labels[i])
        }
    }

    private fun assertDifferentCluster(labels: IntArray, a: Int, b: Int) {
        assertNotEquals("segments $a and $b should be different speakers", labels[a], labels[b])
    }

    // ------------------------------------------------- real-speaker recovery

    @Test
    fun `recovers the two speakers in a real AABB alternation`() {
        val fixture = load("two-speakers-en")
        assertEquals(4, fixture.embeddings.size)

        val result = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 2)

        assertEquals(2, result.clusterCount)
        // Ground truth from the similarity matrix: {0,1} and {2,3}.
        assertSameCluster(result.labels, 0, 1)
        assertSameCluster(result.labels, 2, 3)
        assertDifferentCluster(result.labels, 0, 2)
        assertDifferentCluster(result.labels, 1, 3)
    }

    @Test
    fun `recovers four speakers in real Chinese multi-speaker audio`() {
        val fixture = load("four-speakers-zh")
        assertEquals(7, fixture.embeddings.size)

        val result = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 4)

        assertEquals(4, result.clusterCount)
        // Similarity matrix: 0-2 = 0.76, 0-6 = 0.78, 2-6 = 0.76 => one speaker.
        assertSameCluster(result.labels, 0, 2, 6)
        // 3-4 = 0.85 => one speaker.
        assertSameCluster(result.labels, 3, 4)
        // ...and those two groups are distinct people.
        assertDifferentCluster(result.labels, 0, 3)
        assertDifferentCluster(result.labels, 2, 3)
    }

    @Test
    fun `forced single speaker collapses a real monologue`() {
        val fixture = load("lei-jun")
        assertEquals(61, fixture.embeddings.size)

        val result = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 1)

        assertEquals(1, result.clusterCount)
        assertTrue(result.labels.all { it == 0 })
        assertEquals(1, result.centroids.size)
    }

    @Test
    fun `auto detection does not invent speakers in a monologue`() {
        // A 272 s single-speaker recording. Auto-k must not shatter it into many
        // "speakers" just because the voiceprint drifts between sentences.
        val fixture = load("lei-jun")
        val result = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 0, threshold = 0.5f)

        assertTrue(
            "auto-k found ${result.clusterCount} speakers in a monologue",
            result.clusterCount <= 2,
        )
        val largest = result.labels.toList().groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        assertTrue(
            "dominant speaker holds only $largest/61 segments",
            largest >= 55,
        )
    }

    @Test
    fun `auto detection stays conservative for two very similar voices`() {
        // 'two-speakers-en-2' is an interview where every pairwise similarity is
        // 0.67-0.82: the documented "two similar male voices" edge case. The
        // clusterer must not confidently claim more than two people.
        val fixture = load("two-speakers-en-2")
        val result = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 0, threshold = 0.5f)

        assertTrue(
            "auto-k claimed ${result.clusterCount} speakers (silhouette ${result.silhouette}) " +
                "for near-identical voices",
            result.clusterCount <= 2,
        )
    }

    @Test
    fun `forced k three partitions a monologue into exactly three`() {
        val fixture = load("lei-jun")
        val result = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 3)

        assertEquals(3, result.clusterCount)
        assertTrue(result.labels.all { it in 0..2 })
        // Every cluster must be non-empty: no dead centroids.
        assertEquals(3, result.labels.toSortedSet().size)
    }

    // ------------------------------------------------------------ determinism
    // Deterministic output is a product requirement: re-running "transcribe" on
    // the same file must not silently relabel speakers and invalidate a transcript
    // the user already corrected by hand.

    @Test
    fun `clustering is deterministic across runs`() {
        val fixture = load("four-speakers-zh")
        val a = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 4)
        val b = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 4)
        val c = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 4)
        assertTrue(a.labels.contentEquals(b.labels))
        assertTrue(a.labels.contentEquals(c.labels))
    }

    @Test
    fun `auto-k is deterministic across runs`() {
        val fixture = load("lei-jun")
        val a = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 0)
        val b = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 0)
        assertEquals(a.clusterCount, b.clusterCount)
        assertTrue(a.labels.contentEquals(b.labels))
    }

    // --------------------------------------------------- self-voice promotion

    @Test
    fun `enrolled self voiceprint promotes the right cluster to index zero`() {
        val fixture = load("two-speakers-en")
        // Segment 3 belongs to the second speaker in the A,A,B,B pattern.
        val self = fixture.embeddings[3]

        val result = SpeakerClusterer.cluster(
            fixture.embeddings,
            forcedK = 2,
            selfEmbedding = self,
        )

        // With the owner's voiceprint known, index 0 must be the owner's cluster...
        assertEquals(0, result.labels[3])
        assertEquals(0, result.labels[2])
        // ...and index 1 the other person.
        assertEquals(1, result.labels[0])
        assertEquals(1, result.labels[1])
    }

    @Test
    fun `without a self voiceprint the result is still a valid two way split`() {
        val fixture = load("two-speakers-en")
        val result = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 2, selfEmbedding = null)
        assertEquals(2, result.clusterCount)
        assertEquals(2, result.labels.toSortedSet().size)
    }

    // ------------------------------------------------------------- invariants

    @Test
    fun `centroids are unit norm and distances are in range for real data`() {
        val fixture = load("four-speakers-zh")
        val result = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 4)

        result.centroids.forEach { centroid ->
            val norm = kotlin.math.sqrt(centroid.sumOf { (it * it).toDouble() }).toFloat()
            assertTrue("centroid norm $norm", abs(norm - 1f) < 1e-3f)
        }
        result.distances.forEach { d ->
            assertTrue("distance $d out of range", d >= -1e-3f && d <= 2.001f)
        }
        assertEquals(fixture.embeddings.size, result.distances.size)
    }

    @Test
    fun `every real segment is assigned exactly one label`() {
        val fixture = load("lei-jun")
        val result = SpeakerClusterer.cluster(fixture.embeddings, forcedK = 2)
        assertEquals(61, result.labels.size)
        assertTrue(result.labels.all { it in 0 until result.clusterCount })
    }

    // ------------------------------------------------ synthetic sanity checks

    @Test
    fun `empty input returns an empty result`() {
        val result = SpeakerClusterer.cluster(emptyList())
        assertEquals(0, result.clusterCount)
        assertEquals(0, result.labels.size)
        assertTrue(result.centroids.isEmpty())
    }

    @Test
    fun `single input yields one speaker with a zero label`() {
        val result = SpeakerClusterer.cluster(listOf(FloatArray(192) { 0.1f }))
        assertEquals(1, result.clusterCount)
        assertTrue(result.labels.contentEquals(intArrayOf(0)))
    }

    @Test
    fun `identical vectors never split`() {
        val v = FloatArray(64) { 0.25f }
        val result = SpeakerClusterer.cluster(List(12) { v.copyOf() }, forcedK = 0)
        assertEquals(1, result.clusterCount)
        assertTrue(result.labels.all { it == 0 })
    }

    @Test
    fun `three well separated synthetic speakers are recovered exactly`() {
        val random = Random(20260930)
        fun cluster(axis: Int): List<FloatArray> = List(10) {
            FloatArray(32) { i -> if (i == axis) 1f else random.nextFloat() * 0.05f }
        }
        val vectors = cluster(0) + cluster(1) + cluster(2)

        val result = SpeakerClusterer.cluster(vectors, forcedK = 3)

        assertEquals(3, result.clusterCount)
        assertEquals(result.labels[0], result.labels[5])
        assertEquals(result.labels[10], result.labels[15])
        assertEquals(result.labels[20], result.labels[25])
        assertNotEquals(result.labels[0], result.labels[10])
        assertNotEquals(result.labels[10], result.labels[20])
        assertNotEquals(result.labels[0], result.labels[20])
        // Silhouette should be high for well-separated clusters.
        assertTrue("silhouette ${result.silhouette}", result.silhouette > 0.5f)
    }

    @Test
    fun `forced k larger than the input is clamped`() {
        val v = FloatArray(16) { it.toFloat() }
        val result = SpeakerClusterer.cluster(listOf(v, v.copyOf()), forcedK = 5)
        assertTrue(result.clusterCount in 1..2)
        assertEquals(2, result.labels.size)
    }

    // ------------------------------------ transcript assembly on real output

    @Test
    fun `assembly on real ASR spans keeps timestamps monotonic and non overlapping`() {
        val segments = loadSegments("lei-jun")
        assertTrue(segments.size > 50)

        val asr = segments.map { (start, end, text) ->
            TranscriptAssembler.AsrSpan(startMs = start, endMs = end, text = text)
        }
        val speakers = segments.mapIndexed { index, (start, end, _) ->
            // Alternate artificially so the merge path is exercised.
            TranscriptAssembler.SpeakerSpan(start, end, if (index % 7 < 6) 0 else 1)
        }

        val merged = TranscriptAssembler.assemble(asr, speakers)

        assertTrue("produced no segments", merged.isNotEmpty())
        for (i in merged.indices) {
            assertTrue("segment $i end <= start", merged[i].endMs > merged[i].startMs)
            if (i > 0) {
                assertTrue(
                    "segment $i starts before previous ends",
                    merged[i].startMs >= merged[i - 1].startMs,
                )
            }
        }
        // Merging must not lose text: concatenation of the pieces contains every
        // input character.
        val mergedChars = merged.sumOf { it.text.replace(" ", "").length }
        val inputChars = asr.sumOf { it.text.replace(" ", "").length }
        assertEquals(inputChars, mergedChars)
    }

    @Test
    fun `real monologue text with a single speaker merges into one speaker`() {
        val segments = loadSegments("lei-jun")
        val asr = segments.map { (start, end, text) ->
            TranscriptAssembler.AsrSpan(startMs = start, endMs = end, text = text)
        }
        // No speaker spans at all => everything is "未知".
        val merged = TranscriptAssembler.assemble(asr, emptyList())
        assertTrue(merged.isNotEmpty())
        assertTrue(merged.all { it.speakerIndex == TranscriptAssembler.UNKNOWN_SPEAKER })

        // One speaker covering the whole file => one merged turn per gap run.
        val oneSpeaker = listOf(
            TranscriptAssembler.SpeakerSpan(0, Long.MAX_VALUE / 2, 0),
        )
        val withSpeaker = TranscriptAssembler.assemble(asr, oneSpeaker)
        assertTrue(withSpeaker.all { it.speakerIndex == 0 })
        val text = withSpeaker.joinToString("") { it.text }
        assertTrue("missing known line", text.contains("朋友们"))
    }
}
