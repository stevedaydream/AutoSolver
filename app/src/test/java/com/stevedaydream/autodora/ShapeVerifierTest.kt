package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ShapeVerifierTest {
    private fun filler() = Array(5) { r -> Array(6) { c -> if ((r + c) % 2 == 0) OrbType.WATER else OrbType.HEART } }

    @Test fun orientationCountsAndPlacementCounts() {
        assertEquals(4, PlacementEnumerator.orientations(ShapePattern.T, false).size)
        assertEquals(1, PlacementEnumerator.orientations(ShapePattern.CROSS, false).size)
        assertEquals(4, PlacementEnumerator.orientations(ShapePattern.L, false).size)
        // 目前的 L 是 3×3 等臂直角：鏡像與某個旋轉重合，開啟鏡像後仍只有 4 種朝向（且不重複）
        assertEquals(4, PlacementEnumerator.orientations(ShapePattern.L, true).size)
        assertEquals(
            PlacementEnumerator.orientations(ShapePattern.L, false).map { it.offsets }.toSet(),
            PlacementEnumerator.orientations(ShapePattern.L, true).map { it.offsets }.toSet()
        )
        assertTrue(PlacementEnumerator.orientations(ShapePattern.T, true).none { it.mirrored })
        // 3×3 外框：每個朝向 3 × 4 個位置
        assertEquals(48, PlacementEnumerator.enumerate(ShapePattern.T, false).size)
        assertEquals(48, PlacementEnumerator.enumerate(ShapePattern.L, false).size)
        assertEquals(12, PlacementEnumerator.enumerate(ShapePattern.CROSS, false).size)
        assertSame(PlacementEnumerator.enumerate(ShapePattern.T, false), PlacementEnumerator.enumerate(ShapePattern.T, false))
    }

    @Test fun everyPlacementIncludingEdgesVerifiesWithExactMask() {
        for (pattern in ShapePattern.entries) for (placement in PlacementEnumerator.enumerate(pattern, true)) {
            assertEquals(5, java.lang.Long.bitCount(placement.cellsMask))
            assertEquals(0L, placement.cellsMask and placement.boundaryMask)
            val board = filler()
            placement.cells.forEach { board[it / 6][it % 6] = OrbType.FIRE }
            val result = ShapeVerifier(listOf(ShapeGoal(pattern, OrbType.FIRE))).verify(board)
            assertTrue("$pattern anchor=${placement.anchorCell} o=${placement.orientation}", result.allSatisfied)
            assertEquals(placement.cellsMask, result.matches.single().placement.cellsMask)
            assertEquals(OrbType.FIRE, result.matches.single().placement.orbType)
            // 外緣任一格黏上同色 → 不成立
            for (cell in placement.boundary) {
                val sticky = board.map { it.clone() }.toTypedArray()
                sticky[cell / 6][cell % 6] = OrbType.FIRE
                assertFalse(ShapeVerifier(listOf(ShapeGoal(pattern, OrbType.FIRE))).verify(sticky).allSatisfied)
            }
        }
    }

    @Test fun specificAndAnyColorGoals() {
        val board = BoardSimulatorTest.board("FFFWWW", "HFHHWH", "HFHHWH", "HHHHHH", "HHHHHH")
        assertEquals(0, ShapeVerifier(listOf(ShapeGoal(ShapePattern.T, OrbType.DARK))).verify(board).satisfied)
        val any = ShapeVerifier(listOf(ShapeGoal(ShapePattern.T))).verify(board)
        assertEquals(1, any.satisfied)
        // 任意屬性在前，指定火在後：回溯後兩者都能配到不同配置
        val both = ShapeVerifier(listOf(ShapeGoal(ShapePattern.T), ShapeGoal(ShapePattern.T, OrbType.FIRE))).verify(board)
        assertTrue(both.allSatisfied)
        assertEquals(OrbType.FIRE, both.matchFor(1)!!.placement.orbType)
        assertEquals(OrbType.WATER, both.matchFor(0)!!.placement.orbType)
        assertEquals(0L, both.matchFor(0)!!.placement.cellsMask and both.matchFor(1)!!.placement.cellsMask)
        // 同一個火 T 不能同時滿足兩個火 T 條件
        assertEquals(1, ShapeVerifier(List(2) { ShapeGoal(ShapePattern.T, OrbType.FIRE) }).verify(board).satisfied)
    }

    @Test fun sixGoalsBacktrackToDisjointPlacements() {
        val board = BoardSimulatorTest.board("FFFWWW", "HFHHWH", "DFHHWL", "DOHHOL", "DDDLLL")
        val goals = listOf(
            ShapeGoal(ShapePattern.T), ShapeGoal(ShapePattern.T, OrbType.FIRE), ShapeGoal(ShapePattern.L, OrbType.DARK),
            ShapeGoal(ShapePattern.L, OrbType.LIGHT), ShapeGoal(ShapePattern.CROSS), ShapeGoal(ShapePattern.T, OrbType.WATER)
        )
        val result = ShapeVerifier(goals).verify(board)
        assertEquals(4, result.satisfied)
        assertEquals(20, java.lang.Long.bitCount(result.usedMask))
        assertEquals(OrbType.DARK, result.matchFor(2)!!.placement.orbType)
        assertEquals(OrbType.LIGHT, result.matchFor(3)!!.placement.orbType)
        assertNull(result.matchFor(4))
        // 三個 T 條件只有火 T、水 T 兩個配置：恰好配到兩個，且兩個配置都被用上、屬性符合條件
        val tMatches = listOf(0, 1, 5).mapNotNull { result.matchFor(it) }
        assertEquals(2, tMatches.size)
        assertEquals(setOf(OrbType.FIRE, OrbType.WATER), tMatches.map { it.placement.orbType }.toSet())
        result.matchFor(1)?.let { assertEquals(OrbType.FIRE, it.placement.orbType) }
        result.matchFor(5)?.let { assertEquals(OrbType.WATER, it.placement.orbType) }
        for (a in result.matches) for (b in result.matches) if (a !== b) assertEquals(0L, a.placement.cellsMask and b.placement.cellsMask)
    }

    @Test fun infeasibleReasons() {
        val board = BoardSimulatorTest.cascadeBoard()
        assertNotNull(ShapeVerifier(listOf(ShapeGoal(ShapePattern.L, OrbType.WOOD))).infeasibleReason(board))
        assertNotNull(ShapeVerifier(List(7) { ShapeGoal(ShapePattern.T) }).infeasibleReason(board))
        val allHeart = Array(5) { Array(6) { OrbType.HEART } }
        assertNull(ShapeVerifier(listOf(ShapeGoal(ShapePattern.T))).infeasibleReason(allHeart))
        // 30 顆心最多 6 組；但指定火就不可行
        assertNotNull(ShapeVerifier(listOf(ShapeGoal(ShapePattern.T, OrbType.FIRE))).infeasibleReason(allHeart))
        // 任意屬性需要每組 5 顆同色：木 10 顆、其他各 4 顆 → 最多 2 組
        val mixed = BoardSimulatorTest.board("WFOLDH", "WFOLDH", "WFOLDH", "WFOLDH", "OOOOOO")
        assertNull(ShapeVerifier(List(2) { ShapeGoal(ShapePattern.T) }).infeasibleReason(mixed))
        assertNotNull(ShapeVerifier(List(3) { ShapeGoal(ShapePattern.T) }).infeasibleReason(mixed))
        assertNotNull(ShapeVerifier(listOf(ShapeGoal(ShapePattern.T), ShapeGoal(ShapePattern.L, OrbType.WOOD),
            ShapeGoal(ShapePattern.CROSS, OrbType.WOOD))).infeasibleReason(mixed))
        // 盤面放不下的尺寸
        assertNotNull(ShapeVerifier(listOf(ShapeGoal(ShapePattern.T)), rows = 2, cols = 6).infeasibleReason(Array(2) { Array(6) { OrbType.FIRE } }))
    }

    @Test fun legacyJsonWithoutMirrorFieldStillReads() {
        val legacy = JSONArray("""[{"pattern":"T","orbType":"FIRE"},{"pattern":"CROSS"}]""")
        val goals = ShapeGoal.readList(legacy)
        assertEquals(listOf(ShapeGoal(ShapePattern.T, OrbType.FIRE), ShapeGoal(ShapePattern.CROSS)), goals)
        assertTrue(goals.none { it.allowMirror })
        val written = ShapeGoal.writeList(goals).toString()
        assertFalse("舊設定不應多寫入欄位", written.contains("allowMirror"))
        val mirrored = listOf(ShapeGoal(ShapePattern.L, OrbType.DARK, allowMirror = true))
        assertEquals(mirrored, ShapeGoal.readList(ShapeGoal.writeList(mirrored)))
    }
}
