package com.stevedaydream.autodora

import org.junit.Assert.*
import org.junit.Test

class PathCandidateTest {
    @Test fun legalityFollowsDirectionPolicyAndBounds() {
        assertTrue(PathReplay.isLegal(listOf(0, 1, 7, 6), false))
        assertFalse("斜向在 4 向不允許", PathReplay.isLegal(listOf(0, 7), false))
        assertTrue(PathReplay.isLegal(listOf(0, 7), true))
        assertFalse("跨列換行不是相鄰", PathReplay.isLegal(listOf(5, 6), true))
        assertFalse("跳格", PathReplay.isLegal(listOf(0, 2), true))
        assertFalse("越界", PathReplay.isLegal(listOf(29, 35), true))
        assertFalse("原地", PathReplay.isLegal(listOf(3, 3), true))
        assertFalse(PathReplay.isLegal(listOf(3), true))
        assertEquals(listOf(3, 1, 7, 2), PathReplay.directionsOf(listOf(0, 1, 7, 14, 13)))
    }

    @Test fun fingerprintToleratesAFewUnknownCellsOnly() {
        val board = BoardSimulatorTest.cascadeBoard()
        val expected = PathReplay.fingerprint(board)
        assertTrue(PathReplay.fingerprintMatches(expected, expected))
        // 特效動畫讓 2 格辨識不到：仍視為同一盤面
        val flicker = board.map { it.clone() }.toTypedArray().also {
            it[0][5] = BoardAnalyzer.OrbType.UNKNOWN; it[2][5] = BoardAnalyzer.OrbType.UNKNOWN
        }
        assertTrue(PathReplay.fingerprintMatches(expected, PathReplay.fingerprint(flicker)))
        // 任一已辨識格不同就不相符
        assertFalse(PathReplay.fingerprintMatches(expected, PathReplay.fingerprint(PathReplay.replay(board, listOf(0, 1)))))
        // 太多格辨識不到（可能已離開盤面）也不相符
        val mostlyUnknown = Array(5) { Array(6) { BoardAnalyzer.OrbType.UNKNOWN } }
        assertFalse(PathReplay.fingerprintMatches(expected, PathReplay.fingerprint(mostlyUnknown)))
        assertFalse(PathReplay.fingerprintMatches(expected, expected.dropLast(1)))
    }

    @Test fun stateKeyIsExactAndIncludesFingerPosition() {
        val board = BoardSimulatorTest.cascadeBoard()
        assertEquals(StateKey.of(board, 3, 2), StateKey.of(board.map { it.clone() }.toTypedArray(), 3, 2))
        assertNotEquals(StateKey.of(board, 3, 2), StateKey.of(board, 3, 4))
        assertNotEquals(StateKey.of(board, 3, 2), StateKey.of(board, 9, 2))
        val swapped = PathReplay.replay(board, listOf(0, 1))
        assertNotEquals(StateKey.of(board), StateKey.of(swapped))
        // 最後一格（第 30 格，位於 hi）改變也要反映
        val last = board.map { it.clone() }.toTypedArray().also { it[4][5] = BoardAnalyzer.OrbType.LIGHT }
        assertNotEquals(StateKey.of(board), StateKey.of(last))
    }

    @Test fun legacyPairAdapterRoundTripsGesturePoints() {
        val board = BoardSimulatorTest.cascadeBoard()
        val legacy = BoardAnalyzer().calculateOptimalPath(board, 100f, 20f, 30f, false, 0, 0, 5, 30)
        val geometry = BoardGeometry(20f, 30f, 100f)
        val candidate = CandidateFactory.fromPixelPath(board, legacy.first, geometry, false, null, CandidateSource.BEAM)!!
        assertEquals(legacy.first, geometry.toGesturePoints(candidate.pathCells))
        assertEquals(legacy.second, candidate.deterministicCombos)
        assertNull(CandidateFactory.fromPixelPath(board, listOf(0f to 0f, 50f to 0f), geometry, false, null, CandidateSource.BEAM))
    }

    @Test fun topCandidatesStartWithLegacyBestAndAreDistinct() {
        val board = BoardSimulatorTest.cascadeBoard()
        for (strategy in 0..2) {
            val legacy = BoardAnalyzer().calculateOptimalPath(board, 100f, 0f, 0f, false, strategy, 0, 8, 60)
            val candidates = BoardAnalyzer().calculateOptimalCandidates(board, false, strategy, 0, 8, 60, maxCandidates = 5)
            assertTrue(candidates.size in 2..5)
            assertEquals(BoardGeometry(0f, 0f, 100f).toGesturePoints(candidates.first().pathCells), legacy.first)
            assertEquals(candidates.size, candidates.map { it.resultFingerprint }.toSet().size)
            for (c in candidates) {
                assertTrue(c.executable)
                assertEquals(BoardSimulator(5, 6).resolve(PathReplay.replay(board, c.pathCells)), c.deterministicCombos)
                assertEquals(PathReplay.fingerprint(board), c.boardFingerprint)
            }
        }
    }

    @Test fun shapeGoalsGateExecutability() {
        val board = BoardSimulatorTest.cascadeBoard()
        val goals = listOf(ShapeGoal(ShapePattern.T, BoardAnalyzer.OrbType.DARK))
        val unverified = CandidateFactory.build(board, listOf(24, 25), false, ShapeVerifier(goals), CandidateSource.BEAM)!!
        assertFalse(unverified.shapeVerified)
        assertFalse(unverified.executable)
        val plain = CandidateFactory.build(board, listOf(24, 25), false, null, CandidateSource.BEAM)!!
        assertTrue(plain.executable)
        assertNull(CandidateFactory.build(board, listOf(24, 31), false, null, CandidateSource.BEAM))
    }
}
