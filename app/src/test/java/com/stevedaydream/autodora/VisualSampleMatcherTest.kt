package com.stevedaydream.autodora

import org.junit.Assert.*
import org.junit.Test

class VisualSampleMatcherTest {
    private val enhanced = OrbAnnotation(BoardAnalyzer.OrbType.FIRE, setOf(OrbMark.ENHANCED, OrbMark.WEATHER), setOf("龍"))
    private val normal = OrbAnnotation(BoardAnalyzer.OrbType.FIRE)

    @Test fun nearMatchKeepsAllMarksAndUnknownImageIsRejected() {
        val entries = listOf(VisualSampleMatcher.Entry(floatArrayOf(0.4f, 0.5f), enhanced))
        assertEquals(enhanced, VisualSampleMatcher.match(floatArrayOf(0.41f, 0.5f), entries))
        assertNull(VisualSampleMatcher.match(floatArrayOf(0.9f, 0.9f), entries))
        assertNull(VisualSampleMatcher.match(floatArrayOf(0.4f), entries))
    }

    @Test fun contradictoryLabelsAreRejectedInEitherOrder() {
        val entries = listOf(VisualSampleMatcher.Entry(floatArrayOf(0.4f), enhanced), VisualSampleMatcher.Entry(floatArrayOf(0.4f), normal))
        assertNull(VisualSampleMatcher.match(floatArrayOf(0.4f), entries))
        assertNull(VisualSampleMatcher.match(floatArrayOf(0.4f), entries.reversed()))
    }

    @Test fun sameLabelNeighborsDoNotCompeteWithEachOther() {
        val entries = listOf(VisualSampleMatcher.Entry(floatArrayOf(0.2f), enhanced),
            VisualSampleMatcher.Entry(floatArrayOf(0.3f), normal), VisualSampleMatcher.Entry(floatArrayOf(0.4f), enhanced))
        assertEquals(enhanced, VisualSampleMatcher.match(floatArrayOf(0.4f), entries))
        assertEquals(enhanced, VisualSampleMatcher.match(floatArrayOf(0.4f), entries.reversed()))
    }
}
