package com.stevedaydream.autodora

import org.junit.Assert.*
import org.junit.Test

class BoardHeuristicsTest {
    private fun rowBoard(row: String) = BoardSimulatorTest.board(row, "HHHHHH", "HHHHHH", "HHHHHH", "HHHHHH")

    @Test fun exactTripleIsRewarded() {
        val board = rowBoard("FFFWWL")
        assertTrue(BoardHeuristics.isExactHorizontalTriple(board, 0, 0))
        assertFalse(BoardHeuristics.isExactHorizontalTriple(board, 0, 1))
    }

    @Test fun tailOfFourOrFiveIsNotATriple() {
        val four = rowBoard("FFFFWL")
        for (c in 0..3) assertFalse("FFFFWL c=$c", BoardHeuristics.isExactHorizontalTriple(four, 0, c))
        val five = rowBoard("FFFFFL")
        for (c in 0..3) assertFalse("FFFFFL c=$c", BoardHeuristics.isExactHorizontalTriple(five, 0, c))
        val tail = rowBoard("WFFFFF")
        assertFalse(BoardHeuristics.isExactHorizontalTriple(tail, 0, 3))
    }

    @Test fun tripleAtRightEdgeAndOutOfRange() {
        val board = rowBoard("WLDFFF")
        assertTrue(BoardHeuristics.isExactHorizontalTriple(board, 0, 3))
        assertFalse(BoardHeuristics.isExactHorizontalTriple(board, 0, 4))
    }
}
