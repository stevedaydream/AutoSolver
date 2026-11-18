package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType
import org.junit.Assert.*
import org.junit.Test

class ShapeAwareSolverTest {
    companion object {
        /** 火 T（左上）＋暗十字（右上）完成的盤面。 */
        fun solvedFireTDarkCross() = BoardSimulatorTest.board("FFFHDH", "HFHDDD", "HFHHDH", "WOLWOL", "OLWOLW")

        /** 由完成盤面沿 path 打亂：反向路徑即可還原，保證在步數內有解。 */
        fun scrambled(solved: Array<Array<OrbType>>, path: List<Int>) = PathReplay.replay(solved, path)

        val fireTDarkCross = listOf(ShapeGoal(ShapePattern.T, OrbType.FIRE), ShapeGoal(ShapePattern.CROSS, OrbType.DARK))
    }

    private fun assertCandidateReplays(board: Array<Array<OrbType>>, goals: List<ShapeGoal>, candidate: PathCandidate, is8Way: Boolean) {
        assertTrue(PathReplay.isLegal(candidate.pathCells, is8Way))
        val replay = PathReplay.replay(board, candidate.pathCells)
        val verification = ShapeVerifier(goals).verify(replay)
        assertTrue(verification.allSatisfied)
        assertTrue(candidate.shapeVerified)
        assertEquals(goals.size, candidate.shapeSatisfied)
        val simulator = BoardSimulator(5, 6)
        assertEquals(simulator.resolve(replay), candidate.deterministicCombos)
        assertEquals(simulator.firstWaveCombos, candidate.firstWaveCombos)
        assertEquals(candidate.pathCells.size - 1, candidate.stepCount)
        assertEquals(PathReplay.directionsOf(candidate.pathCells), candidate.directions)
        // 多組形狀不共用格位
        val masks = candidate.shapeMatches.map { it.placement.cellsMask }
        for (i in masks.indices) for (j in i + 1 until masks.size) assertEquals(0L, masks[i] and masks[j])
    }

    @Test fun solvesFireTPlusDarkCross() {
        val board = scrambled(solvedFireTDarkCross(), listOf(2 * 6 + 1, 3 * 6 + 1, 3 * 6 + 2))
        assertFalse(ShapeVerifier(fireTDarkCross).verify(board).allSatisfied)
        val result = BoardAnalyzer().solveShapeAware(board, fireTDarkCross, SearchBudget(maxSteps = 10, beamWidth = 60, timeLimitMs = 10_000))
        assertEquals(result.message, ShapeSolveStatus.SOLVED, result.status)
        assertTrue(result.candidates.isNotEmpty() && result.candidates.size <= 5)
        assertEquals(2, result.bestSatisfiedGoals)
        assertNotNull(result.timeToFirstShapeMs)
        assertEquals(result.best!!.pathCells, result.path)
        result.candidates.forEach { assertCandidateReplays(board, fireTDarkCross, it, false) }
        assertEquals(result.candidates.size, result.candidates.map { it.resultFingerprint }.toSet().size)
        // 排序：Combo 不遞增
        assertEquals(result.candidates.map { -it.deterministicCombos }.sorted(), result.candidates.map { -it.deterministicCombos })
    }

    @Test fun eightWayPathsAreLegalUnderEightWayPolicy() {
        val board = scrambled(solvedFireTDarkCross(), listOf(2 * 6 + 1, 3 * 6 + 2, 4 * 6 + 3))
        val result = BoardAnalyzer().solveShapeAware(board, fireTDarkCross, SearchBudget(maxSteps = 10, beamWidth = 60, is8Way = true, timeLimitMs = 10_000))
        assertEquals(ShapeSolveStatus.SOLVED, result.status)
        result.candidates.forEach { assertCandidateReplays(board, fireTDarkCross, it, true) }
    }

    @Test fun infeasibleIsReportedWithoutSearching() {
        val result = BoardAnalyzer().solveShapeAware(BoardSimulatorTest.cascadeBoard(),
            listOf(ShapeGoal(ShapePattern.L, OrbType.WOOD)), SearchBudget())
        assertEquals(ShapeSolveStatus.INFEASIBLE, result.status)
        assertTrue(result.candidates.isEmpty() && result.path.isEmpty())
        assertEquals(0L, result.nodes)
        val unknown = solvedFireTDarkCross().also { it[4][5] = OrbType.UNKNOWN }
        assertEquals(ShapeSolveStatus.INFEASIBLE, BoardAnalyzer().solveShapeAware(unknown, fireTDarkCross, SearchBudget()).status)
    }

    @Test fun unreachableGoalsNeverReturnAPath() {
        // 與舊測試相同：只能從右下角起手、5 步內無法組出火 L
        val board = BoardSimulatorTest.board("FFFFFH", "HHHHHH", "HHHHHH", "HHHHHH", "HHHHHH")
        val result = BoardAnalyzer().solveShapeAware(board, listOf(ShapeGoal(ShapePattern.L, OrbType.FIRE)),
            SearchBudget(maxSteps = 5, beamWidth = 30, startMode = 2, timeLimitMs = 10_000))
        assertEquals(ShapeSolveStatus.GOALS_UNSATISFIED, result.status)
        assertTrue(result.candidates.isEmpty() && result.path.isEmpty())
    }

    @Test fun timeoutAndCancellationReportExplicitStatus() {
        // 打亂 7 步：前 128 個節點（第一次檢查預算前）不可能還原
        val board = scrambled(solvedFireTDarkCross(), listOf(7, 13, 19, 20, 21, 15, 9, 10))
        var ticks = 0L
        val timeout = ShapeAwareSolver(fireTDarkCross, { BoardAnalyzer().scoreBoard(it, 0) },
            clock = { ticks += 5_000_000L; ticks }).solve(board, SearchBudget(maxSteps = 10, beamWidth = 30, timeLimitMs = 1))
        assertEquals(ShapeSolveStatus.TIMEOUT, timeout.status)
        assertTrue(timeout.candidates.isEmpty())
        val cancelled = ShapeAwareSolver(fireTDarkCross, { BoardAnalyzer().scoreBoard(it, 0) }, cancelled = { true })
            .solve(board, SearchBudget(maxSteps = 10, beamWidth = 30, timeLimitMs = 10_000))
        assertEquals(ShapeSolveStatus.CANCELLED, cancelled.status)
        assertTrue(cancelled.path.isEmpty())
        val nodeCapped = BoardAnalyzer().solveShapeAware(board, fireTDarkCross, SearchBudget(maxSteps = 10, beamWidth = 30, maxNodes = 10))
        assertEquals(ShapeSolveStatus.TIMEOUT, nodeCapped.status)
    }

    @Test fun deduplicationDoesNotLoseTheSolution() {
        val board = scrambled(solvedFireTDarkCross(), listOf(2 * 6 + 1, 3 * 6 + 1, 3 * 6 + 2))
        for (dedup in listOf(true, false)) {
            val result = BoardAnalyzer().solveShapeAware(board, fireTDarkCross,
                SearchBudget(maxSteps = 8, beamWidth = 60, timeLimitMs = 10_000, deduplicate = dedup, refineTopK = 0))
            assertEquals("dedup=$dedup", ShapeSolveStatus.SOLVED, result.status)
        }
    }

    @Test fun baselineBeamNeverReportsFalseSuccess() {
        // 此盤面需要「先讓潛力變差」的一步；舊 Beam 可能找不到，但找到的每一條都必須真的達成
        val board = scrambled(solvedFireTDarkCross(), listOf(2 * 6 + 1, 3 * 6 + 1, 3 * 6 + 2))
        val candidates = BoardAnalyzer().calculateOptimalCandidates(board, false, 0, 0, 10, 60, fireTDarkCross)
        candidates.forEach { assertCandidateReplays(board, fireTDarkCross, it, false) }
        val legacy = BoardAnalyzer().calculateOptimalPath(board, 100f, 0f, 0f, false, 0, 0, 10, 60, fireTDarkCross)
        assertEquals(candidates.isEmpty(), legacy.first.isEmpty())
    }
}
