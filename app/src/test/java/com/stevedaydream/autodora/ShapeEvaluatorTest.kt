package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType
import org.junit.Assert.*
import org.junit.Test

class ShapeEvaluatorTest {
    @Test fun allRotationsMatchAndExtraAdjacentOrbRejects() {
        val shapes = mapOf(
            ShapePattern.T to listOf(0 to 0, 0 to 1, 0 to 2, 1 to 1, 2 to 1),
            ShapePattern.L to listOf(0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2),
            ShapePattern.CROSS to listOf(0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 1)
        )
        for ((pattern, base) in shapes) {
            var cells = base
            repeat(4) {
                val minR = cells.minOf { it.first }
                val minC = cells.minOf { it.second }
                cells = cells.map { (r, c) -> r - minR to c - minC }
                val board = Array(5) { Array(6) { OrbType.WATER } }
                cells.forEach { (r, c) -> board[r][c] = OrbType.FIRE }
                val evaluator = ShapeEvaluator(listOf(ShapeGoal(pattern, OrbType.FIRE)))
                assertEquals("$pattern 旋轉 $it", 1, evaluator.evaluate(board).satisfied)
                val simulator = BoardSimulator(5, 6)
                simulator.resolve(board)
                cells.forEach { (r, c) -> assertTrue(simulator.firstWaveMatch[r][c]) }
                val adjacent = (0 until 5).flatMap { r -> (0 until 6).map { c -> r to c } }.first { (r, c) ->
                    board[r][c] != OrbType.FIRE && cells.any { (sr, sc) -> kotlin.math.abs(sr-r) + kotlin.math.abs(sc-c) == 1 }
                }
                board[adjacent.first][adjacent.second] = OrbType.FIRE
                assertEquals(0, evaluator.evaluate(board).satisfied)
                cells = cells.map { (r, c) -> c to -r }
            }
        }
    }

    @Test fun anyAndSpecificGoalsUseDistinctGroupsRegardlessOfOrder() {
        val board = BoardSimulatorTest.board("FFFWWW", "HFHHWH", "HFHHWH", "HHHHHH", "HHHHHH")
        // 左側火 T、右側水 T，各有獨立五顆。
        val goals = listOf(ShapeGoal(ShapePattern.T), ShapeGoal(ShapePattern.T, OrbType.FIRE))
        assertEquals(2, ShapeEvaluator(goals).evaluate(board).satisfied)
        assertEquals(2, ShapeEvaluator(goals.reversed()).evaluate(board).satisfied)
        assertEquals(1, ShapeEvaluator(List(2) { ShapeGoal(ShapePattern.T, OrbType.FIRE) }).evaluate(board).satisfied)
    }

    @Test fun multiplePatternsMustAllMatchAndSearchResultReplays() {
        val board = BoardSimulatorTest.board("FFFHWH", "HFHWWW", "HFHHWH", "HHHHHH", "HHHHHH")
        val goals = listOf(ShapeGoal(ShapePattern.T, OrbType.FIRE), ShapeGoal(ShapePattern.CROSS, OrbType.WATER))
        assertEquals(2, ShapeEvaluator(goals).evaluate(board).satisfied)
        board[0][0] = OrbType.HEART
        board[1][0] = OrbType.FIRE
        assertEquals(1, ShapeEvaluator(goals).evaluate(board).satisfied)
        val result = BoardAnalyzer().calculateOptimalPath(board, 100f, 0f, 0f, false, steps = 5, beamWidth = 30, shapeGoals = goals)
        assertTrue(result.first.isNotEmpty())
        val replay = board.map { it.clone() }.toTypedArray()
        val cells = result.first.map { (x, y) -> (y / 100).toInt() to (x / 100).toInt() }
        for ((a, b) in cells.zipWithNext()) {
            val temp = replay[a.first][a.second]
            replay[a.first][a.second] = replay[b.first][b.second]
            replay[b.first][b.second] = temp
        }
        assertEquals(2, ShapeEvaluator(goals).evaluate(replay).satisfied)
        assertEquals(BoardSimulator(5, 6).resolve(replay), result.second)
    }

    @Test fun impossibleColorCountAndOverCapacityReturnNoPath() {
        val board = BoardSimulatorTest.cascadeBoard()
        val goals = listOf(ShapeGoal(ShapePattern.L, OrbType.WOOD))
        assertFalse(ShapeEvaluator(goals).canAttempt(board))
        assertTrue(BoardAnalyzer().calculateOptimalPath(board, 100f, 0f, 0f, shapeGoals = goals).first.isEmpty())
        assertFalse(ShapeEvaluator(List(7) { ShapeGoal(ShapePattern.T) }).canAttempt(board))
    }

    @Test fun insufficientSearchBudgetDoesNotReturnAnUnconstrainedPath() {
        val board = BoardSimulatorTest.board("FFFFFH", "HHHHHH", "HHHHHH", "HHHHHH", "HHHHHH")
        val goals = listOf(ShapeGoal(ShapePattern.L, OrbType.FIRE))
        assertTrue(ShapeEvaluator(goals).canAttempt(board))
        val result = BoardAnalyzer().calculateOptimalPath(board, 100f, 0f, 0f, false, startMode = 2,
            steps = 5, beamWidth = 30, shapeGoals = goals)
        assertTrue(result.first.isEmpty())
    }
}
