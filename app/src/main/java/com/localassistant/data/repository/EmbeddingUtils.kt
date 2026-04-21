package com.localassistant.data.repository

import kotlin.math.sqrt

object EmbeddingUtils {
    private const val DIMENSIONS = 32

    fun embed(text: String): List<Float> {
        if (text.isBlank()) return emptyList()
        val vector = FloatArray(DIMENSIONS)
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .forEach { token ->
                val index = (token.hashCode() and Int.MAX_VALUE) % DIMENSIONS
                vector[index] += 1f
            }
        val magnitude = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (magnitude == 0f) return emptyList()
        return vector.map { it / magnitude }
    }

    fun cosine(a: List<Float>, b: List<Float>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val size = minOf(a.size, b.size)
        var dot = 0f
        for (i in 0 until size) dot += a[i] * b[i]
        return dot
    }
}
