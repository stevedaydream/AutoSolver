package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType
import org.junit.Assert.*
import org.junit.Test

class ClearGoalTest {
    private fun firstWaveLeft(board: Array<Array<OrbType>>, types: Set<OrbType>): Int {
        val sim = BoardSimulator(5, 6)
        sim.resolve(board)
        return ClearGoal.remaining(board, sim.firstWaveMatch, types)
    }

    @Test fun remainingCountsOnlyFirstWaveAndParsesPrefs() {
        // 底列火 T 會消，(0,1) 與 (1,4) 兩顆火留下
        val board = BoardSimulatorTest.cascadeBoard()
        assertEquals(2, firstWaveLeft(board, setOf(OrbType.FIRE)))
        assertEquals(0, firstWaveLeft(board, emptySet()))
        // 盤面沒有木珠：視為已達成
        assertEquals(0, firstWaveLeft(board, setOf(OrbType.WOOD)))
        assertEquals(setOf(OrbType.FIRE, OrbType.DARK), ClearGoal.parse("FIRE, DARK,UNKNOWN,xx"))
        assertEquals("FIRE,DARK", ClearGoal.format(setOf(OrbType.DARK, OrbType.FIRE)))
        assertEquals("火暗", ClearGoal.describe(setOf(OrbType.DARK, OrbType.FIRE)))
    }

    @Test fun costPrefersClusteredLeftovers() {
        val sim = BoardSimulator(5, 6)
        val apart = BoardSimulatorTest.board("FWFWHL", "WHLDOD", "HLDOWH", "LDOWHL", "DOWHLD")
        val together = BoardSimulatorTest.board("FFWWHL", "WHLDOD", "HLDOWH", "LDOWHL", "DOWHLD")
        sim.resolve(apart)
        val costApart = ClearGoal.cost(apart, sim.firstWaveMatch, setOf(OrbType.FIRE))
        sim.resolve(together)
        val costTogether = ClearGoal.cost(together, sim.firstWaveMatch, setOf(OrbType.FIRE))
        assertTrue(costTogether < costApart)
    }

    @Test fun oneOrTwoOrbsAreInfeasible() {
        val board = BoardSimulatorTest.board("FWLHDO", "WFLHDO", "WLHDOW", "LHDOWL", "HDOWLH")
        assertNotNull(ClearGoal.infeasibleReason(board, setOf(OrbType.FIRE)))
        assertNull(ClearGoal.infeasibleReason(board, setOf(OrbType.WATER)))
        assertTrue(BoardAnalyzer().calculateOptimalCandidates(board, false, 0, 0, 10, 60, clearTypes = setOf(OrbType.FIRE)).isEmpty())
    }

    @Test fun beamClearsAllTargetOrbsAndPrefersCombos() {
        val board = BoardSimulatorTest.cascadeBoard()
        val types = setOf(OrbType.FIRE)
        val candidates = BoardAnalyzer().calculateOptimalCandidates(board, false, 0, 0, 20, 120, clearTypes = types)
        assertTrue(candidates.isNotEmpty())
        for (c in candidates) {
            assertTrue(c.clearVerified && c.executable)
            assertEquals(0, firstWaveLeft(PathReplay.replay(board, c.pathCells), types))
            assertEquals(BoardSimulator(5, 6).resolve(PathReplay.replay(board, c.pathCells)), c.deterministicCombos)
        }
        assertEquals(candidates.map { -it.deterministicCombos }.sorted(), candidates.map { -it.deterministicCombos })
        val legacy = BoardAnalyzer().calculateOptimalPath(board, 100f, 0f, 0f, false, 0, 0, 20, 120, clearTypes = types)
        val cells = legacy.first.map { (x, y) -> (y / 100).toInt() * 6 + (x / 100).toInt() }
        assertEquals(0, firstWaveLeft(PathReplay.replay(board, cells), types))
        assertEquals(candidates.first().deterministicCombos, legacy.second)
    }

    @Test fun unmetClearIsNotExecutable() {
        val board = BoardSimulatorTest.cascadeBoard()
        val c = CandidateFactory.build(board, listOf(24, 25), false, null, CandidateSource.BEAM, clearTypes = setOf(OrbType.FIRE))!!
        assertEquals(2, c.clearRemaining)
        assertFalse(c.clearVerified)
        assertFalse(c.executable)
    }

    @Test fun shapeAwareCombinesShapesClearAndEndCell() {
        val scrambled = PathReplay.replay(ShapeAwareSolverTest.solvedFireTDarkCross(), listOf(13, 19, 20))
        val goals = ShapeAwareSolverTest.fireTDarkCross
        val types = setOf(OrbType.DARK, OrbType.FIRE)
        val result = BoardAnalyzer().solveShapeAware(scrambled, goals,
            SearchBudget(maxSteps = 12, beamWidth = 60, timeLimitMs = 10_000, clearTypes = types))
        assertEquals(result.message, ShapeSolveStatus.SOLVED, result.status)
        for (c in result.candidates) {
            assertTrue(c.clearVerified)
            assertEquals(0, firstWaveLeft(PathReplay.replay(scrambled, c.pathCells), types))
        }
        val infeasible = BoardAnalyzer().solveShapeAware(BoardSimulatorTest.board("FWLHDO", "WFLHDO", "WLHDOW", "LHDOWL", "HDOWLH"),
            listOf(ShapeGoal(ShapePattern.T)), SearchBudget(clearTypes = setOf(OrbType.FIRE)))
        assertEquals(ShapeSolveStatus.INFEASIBLE, infeasible.status)
    }

    @Test fun scatteredTargetsOnUserBoardAreCleared() {
        // 使用者實盤：4 顆暗珠分散在四個角落附近，必須全部在同一批連成一組
        val board = BoardSimulatorTest.board("ODLLHD", "WHOWHL", "LFFOOD", "WLFHFO", "ODOLHH")
        val types = setOf(OrbType.DARK)
        val candidates = BoardAnalyzer().calculateOptimalCandidates(board, false, 0, 0, 35, 200, clearTypes = types)
        assertTrue(candidates.isNotEmpty())
        for (c in candidates) assertEquals(0, firstWaveLeft(PathReplay.replay(board, c.pathCells), types))
        // 從 GO（右上角）起手也要能解
        val fromGo = BoardAnalyzer().calculateOptimalCandidates(board, false, 0, 0, 35, 200,
            constraint = MoveConstraint(startCell = 5), clearTypes = types)
        assertTrue(fromGo.isNotEmpty() && fromGo.all { it.pathCells.first() == 5 })
    }

    @Test fun formationDistanceIsZeroForALineAndGrowsWhenScattered() {
        assertEquals(0, ClearGoal.formationDistance(listOf(2, 2, 2), listOf(1, 2, 3), 5, 6))
        assertEquals(0, ClearGoal.formationDistance(listOf(0, 1, 2, 3), listOf(4, 4, 4, 4), 5, 6))
        assertTrue(ClearGoal.formationDistance(listOf(0, 0, 2, 4), listOf(1, 5, 5, 1), 5, 6) > 4)
    }

    @Test fun orderedShieldPlusClearOnUserBoardIsSolvedByPushPlanner() {
        // 使用者實盤：GO（右上）起、右側暗珠終，且首批全消暗——Beam 找不到，需推珠規劃器
        val board = BoardSimulatorTest.board("ODLLHD", "WHOWHL", "LFFOOD", "WLFHFO", "ODOLHH")
        val types = setOf(OrbType.DARK)
        val candidates = BoardAnalyzer().calculateOptimalCandidates(board, false, 0, 0, 35, 200,
            constraint = MoveConstraint(5, 17), clearTypes = types)
        assertTrue(candidates.isNotEmpty())
        for (c in candidates) {
            assertEquals(5, c.pathCells.first())
            assertEquals(17, c.pathCells.last())
            assertTrue(c.pathCells.size - 1 <= 35)
            assertTrue(PathReplay.isLegal(c.pathCells, false))
            assertEquals(0, firstWaveLeft(PathReplay.replay(board, c.pathCells), types))
            assertTrue(c.deterministicCombos >= 1)
        }
    }

    @Test fun twoPhaseClearThenComboUsesTheSpareSteps() {
        // 兩段式：先推好起終點 + 全消排列，再用剩下的步數堆 Combo；步數越多 Combo 不應更少
        val board = BoardSimulatorTest.board("ODLLHD", "WHOWHL", "LFFOOD", "WLFHFO", "ODOLHH")
        val types = setOf(OrbType.DARK)
        var previous = 0
        for (steps in listOf(35, 45, 60)) {
            val t0 = System.currentTimeMillis()
            val candidates = BoardAnalyzer().calculateOptimalCandidates(board, false, 0, 0, steps, 200,
                constraint = MoveConstraint(5, 17), clearTypes = types)
            val best = candidates.first()
            println("PHASE2 steps=$steps combos=${best.deterministicCombos} len=${best.pathCells.size - 1} ms=${System.currentTimeMillis() - t0}")
            assertEquals(5, best.pathCells.first()); assertEquals(17, best.pathCells.last())
            assertEquals(0, firstWaveLeft(PathReplay.replay(board, best.pathCells), types))
            assertTrue(best.deterministicCombos >= previous)
            previous = best.deterministicCombos
        }
        assertTrue("60 步應比只推珠的 2 Combo 多", previous > 2)
    }
}
