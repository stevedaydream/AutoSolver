package com.stevedaydream.autodora

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class BeamSelectorTest {
    @Test fun matchesStableRankingWithTiesAndExtremeScores() {
        val random = Random(123)
        for (capacity in listOf(1, 30, 200, 500)) {
            val scores = IntArray(capacity * 8) { random.nextInt(-10, 11) }
            scores[0] = Int.MIN_VALUE
            scores[1] = Int.MAX_VALUE
            val selector = BeamSelector(capacity) { scores[it] }
            for (count in listOf(0, 1, capacity, scores.size)) {
                val expected = (0 until count).sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it }).take(capacity)
                assertEquals(expected, selector.select(count))
            }
            scores.fill(5)
            assertEquals((0 until capacity).toList(), selector.select(scores.size))
        }
    }

    @Test fun reducesCandidateScoreReadsAgainstRepeatedScan() {
        val random = Random(456)
        val scores = IntArray(1600) { random.nextInt(1_000_000) }
        var reads = 0
        val selected = BeamSelector(200) { reads++; scores[it] }.select(scores.size)
        val reference = scores.clone()
        val oldSelected = mutableListOf<Int>()
        repeat(200) {
            val index = reference.indices.maxBy { reference[it] }
            oldSelected.add(index)
            reference[index] = Int.MIN_VALUE
        }
        assertEquals(oldSelected, selected)
        assertTrue("讀取次數 $reads，舊版 320000", reads < 320_000 / 4)
        println("候選分數讀取：新版 $reads，舊版 320000")
    }
}
