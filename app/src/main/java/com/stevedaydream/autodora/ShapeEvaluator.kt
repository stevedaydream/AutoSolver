package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType

internal data class ShapeEvaluation(val satisfied: Int, val potential: Int)

/**
 * 搜尋引導用：satisfied 來自 [ShapeVerifier] 的精確配對（含格位互斥），
 * potential 只是「目標區域同色數 − 外緣同色懲罰」的代理分數，不代表可達性。
 */
internal class ShapeEvaluator(private val goals: List<ShapeGoal>, private val rows: Int = 5, private val cols: Int = 6) {
    val verifier = ShapeVerifier(goals, rows, cols)
    private val counts = IntArray(OrbType.entries.size)
    private val touching = IntArray(OrbType.entries.size)

    fun canAttempt(board: Array<Array<OrbType>>): Boolean = verifier.infeasibleReason(board) == null

    fun evaluate(board: Array<Array<OrbType>>): ShapeEvaluation {
        var potentialSum = 0
        for ((goalIndex, goal) in goals.withIndex()) {
            var best = 0
            for (placement in verifier.placementLists[goalIndex]) {
                counts.fill(0)
                touching.fill(0)
                for (cell in placement.cells) counts[board[cell / cols][cell % cols].ordinal]++
                for (cell in placement.boundary) touching[board[cell / cols][cell % cols].ordinal]++
                val start = goal.type?.ordinal ?: 0
                val end = goal.type?.ordinal ?: (OrbType.UNKNOWN.ordinal - 1)
                for (type in start..end) best = maxOf(best, counts[type] * 20 - touching[type] * 3)
            }
            potentialSum += best
        }
        return ShapeEvaluation(verifier.verify(board).satisfied, potentialSum)
    }
}
