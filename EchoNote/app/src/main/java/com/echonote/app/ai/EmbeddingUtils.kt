package com.echonote.app.ai

import kotlin.math.sqrt

/**
 * Vector helpers for speaker embeddings.
 *
 * Embeddings produced by the sherpa-onnx speaker models are compared with cosine
 * similarity in every reference implementation, so the same metric is used here.
 * Pure Kotlin: fully covered by JVM unit tests.
 */
object EmbeddingUtils {

    /** Cosine similarity in [-1, 1]. Returns 0 for a zero vector. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na <= 1e-12 || nb <= 1e-12) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat().coerceIn(-1f, 1f)
    }

    /** Cosine distance in [0, 2]; the clustering metric. */
    fun cosineDistance(a: FloatArray, b: FloatArray): Float = 1f - cosine(a, b)

    /** Scales to unit length (no-op for a zero vector). */
    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        if (sum <= 1e-12) return v.copyOf()
        val inv = (1.0 / sqrt(sum)).toFloat()
        return FloatArray(v.size) { v[it] * inv }
    }

    /** Element-wise mean, renormalised. Returns null for an empty input. */
    fun mean(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val dim = vectors[0].size
        val acc = DoubleArray(dim)
        var used = 0
        for (v in vectors) {
            if (v.size != dim) continue
            for (i in 0 until dim) acc[i] += v[i]
            used++
        }
        if (used == 0) return null
        return l2Normalize(FloatArray(dim) { (acc[it] / used).toFloat() })
    }

    /**
     * Weighted mean, used to build a centroid that respects how much speech each
     * segment actually contained.
     */
    fun weightedMean(vectors: List<FloatArray>, weights: FloatArray): FloatArray? {
        if (vectors.isEmpty() || weights.size != vectors.size) return null
        val dim = vectors[0].size
        val acc = DoubleArray(dim)
        var totalWeight = 0.0
        for ((i, v) in vectors.withIndex()) {
            if (v.size != dim) continue
            val w = weights[i].toDouble()
            if (w <= 0.0) continue
            totalWeight += w
            for (d in 0 until dim) acc[d] += v[d] * w
        }
        if (totalWeight <= 1e-12) return null
        return l2Normalize(FloatArray(dim) { (acc[it] / totalWeight).toFloat() })
    }

    /** Compact storage form for [com.echonote.app.data.db.SpeakerEntity.embedding]. */
    fun serialize(v: FloatArray): String {
        val sb = StringBuilder(v.size * 9)
        for (i in v.indices) {
            if (i > 0) sb.append(',')
            sb.append(v[i])
        }
        return sb.toString()
    }

    /** Inverse of [serialize]; returns null for blank or malformed input. */
    fun deserialize(text: String?): FloatArray? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(',')
        val out = FloatArray(parts.size)
        for (i in parts.indices) {
            out[i] = parts[i].trim().toFloatOrNull() ?: return null
        }
        return out
    }

    /** Nearest centroid index, or -1 when there are no centroids. */
    fun nearestCentroid(embedding: FloatArray, centroids: List<FloatArray>): Int {
        var best = -1
        var bestSim = -Float.MAX_VALUE
        for (i in centroids.indices) {
            val sim = cosine(embedding, centroids[i])
            if (sim > bestSim) {
                bestSim = sim
                best = i
            }
        }
        return best
    }
}
