package com.stevedaydream.autodora

import org.junit.Assert.*
import org.junit.Test

class MoveConstraintTest {
    private val board = BoardSimulatorTest.cascadeBoard()

    @Test fun startCellsFollowConstraintThenLegacyMode() {
        assertEquals(listOf(7), MoveConstraint(startCell = 7).startCells(2))
        assertEquals(listOf(24), MoveConstraint().startCells(1))
        assertEquals(listOf(29), MoveConstraint().startCells(2))
        assertEquals(30, MoveConstraint().startCells(0).size)
        assertTrue(MoveConstraint().endOk(13))
        assertTrue(MoveConstraint(endCell = 13).endOk(13))
        assertFalse(MoveConstraint(endCell = 13).endOk(12))
        assertEquals("第 3 列第 2 行", MoveConstraint.label(13))
        assertEquals("不限", MoveConstraint.label(-1))
    }

    @Test fun walkToReachesTargetWithoutImmediateReturn() {
        for (is8Way in listOf(false, true)) for (from in 0 until 30) for (target in 0 until 30) {
            val walk = PathReplay.walkTo(from, -1, target, is8Way, 20)!!
            assertEquals(if (from == target) from else target, (listOf(from) + walk).last())
            assertTrue(walk.isEmpty() || PathReplay.isLegal(listOf(from) + walk, is8Way))
            val full = listOf(from) + walk
            for (i in 2 until full.size) assertNotEquals(full[i - 2], full[i])
        }
        // 上一步正好在目標方向：需繞路，不可立即折返
        val detour = PathReplay.walkTo(2, 1, 0, false, 10)!!
        assertNotEquals(1, detour.first())
        assertEquals(0, detour.last())
        assertNull(PathReplay.walkTo(0, -1, 29, false, 3))
    }

    @Test fun beamHonoursStartAndEndCells() {
        for (end in listOf(0, 5, 14, 29)) for (start in listOf(-1, 24)) {
            val constraint = MoveConstraint(start, end)
            val candidates = BoardAnalyzer().calculateOptimalCandidates(board, false, 0, 0, 15, 60, constraint = constraint)
            assertTrue("start=$start end=$end", candidates.isNotEmpty())
            for (c in candidates) {
                assertEquals(end, c.pathCells.last())
                if (start >= 0) assertEquals(start, c.pathCells.first())
                assertTrue(c.executable)
                assertEquals(BoardSimulator(5, 6).resolve(PathReplay.replay(board, c.pathCells)), c.deterministicCombos)
            }
            val legacy = BoardAnalyzer().calculateOptimalPath(board, 100f, 0f, 0f, false, 0, 0, 15, 60, constraint = constraint)
            val cells = legacy.first.map { (x, y) -> (y / 100).toInt() * 6 + (x / 100).toInt() }
            assertEquals(end, cells.last())
            if (start >= 0) assertEquals(start, cells.first())
        }
    }

    @Test fun shapeAwareHonoursEndCell() {
        val solved = ShapeAwareSolverTest.solvedFireTDarkCross()
        val scrambled = PathReplay.replay(solved, listOf(13, 19, 20))
        val goals = ShapeAwareSolverTest.fireTDarkCross
        val end = 29
        val result = BoardAnalyzer().solveShapeAware(scrambled, goals,
            SearchBudget(maxSteps = 15, beamWidth = 60, timeLimitMs = 10_000, constraint = MoveConstraint(endCell = end)))
        assertEquals(result.message, ShapeSolveStatus.SOLVED, result.status)
        for (c in result.candidates) {
            assertEquals(end, c.pathCells.last())
            assertTrue(ShapeVerifier(goals).verify(PathReplay.replay(scrambled, c.pathCells)).allSatisfied)
        }
    }

    @Test fun policyPathStartsAndEndsWhereAsked() {
        val result = BoardAnalyzer().calculatePolicyPath(board, 100f, 0f, 0f, 10, 0, MoveConstraint(startCell = 26, endCell = 3)) { _, _, r, c ->
            listOf(r to c, r to c + 1)
        }
        val cells = result.first.map { (x, y) -> (y / 100).toInt() * 6 + (x / 100).toInt() }
        assertEquals(26, cells.first())
        assertEquals(3, cells.last())
        assertTrue(PathReplay.isLegal(cells, false))
    }
}
