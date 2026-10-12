package com.stevedaydream.autodora

internal object VisualSampleMatcher {
    data class Entry(val descriptor: FloatArray, val annotation: OrbAnnotation)

    fun match(query: FloatArray, entries: List<Entry>): OrbAnnotation? {
        var best = Float.POSITIVE_INFINITY
        var winner: OrbAnnotation? = null
        val distances = FloatArray(entries.size) { Float.POSITIVE_INFINITY }
        for ((index, entry) in entries.withIndex()) {
            if (entry.descriptor.size != query.size || query.isEmpty()) continue
            var distance = 0f
            var largestDelta = 0f
            for (i in query.indices) {
                val delta = query[i] - entry.descriptor[i]
                distance += delta * delta
                largestDelta = maxOf(largestDelta, kotlin.math.abs(delta))
            }
            distance /= query.size
            if (largestDelta > 0.35f) continue
            distances[index] = distance
            if (distance < best) {
                best = distance
                winner = entry.annotation
            }
        }
        val otherLabel = entries.indices.filter { entries[it].annotation != winner }.minOfOrNull { distances[it] } ?: Float.POSITIVE_INFINITY
        return winner?.takeIf { best <= 0.001f && otherLabel - best >= 0.001f }
    }
}
