package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType

internal data class ShapeEvaluation(val satisfied: Int, val potential: Int)

/** 每個版型必須是獨立的五顆同色珠；以配對避免任意屬性搶走指定屬性的組合。 */
internal class ShapeEvaluator(private val goals: List<ShapeGoal>, private val rows: Int = 5, private val cols: Int = 6) {
    private data class Placement(val pattern: ShapePattern, val cells: IntArray, val boundary: IntArray)
    private data class Group(val pattern: ShapePattern, val type: OrbType)

    private val placements = goals.map { it.pattern }.distinct().flatMap { pattern ->
        val base = when (pattern) {
            ShapePattern.T -> listOf(0 to 0, 0 to 1, 0 to 2, 1 to 1, 2 to 1)
            ShapePattern.L -> listOf(0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2)
            ShapePattern.CROSS -> listOf(0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 1)
        }
        val rotations = mutableSetOf<List<Pair<Int, Int>>>()
        var rotated = base
        repeat(4) {
            val minR = rotated.minOf { it.first }
            val minC = rotated.minOf { it.second }
            rotations.add(rotated.map { (r, c) -> r - minR to c - minC }.sortedWith(compareBy({ it.first }, { it.second })))
            rotated = rotated.map { (r, c) -> c to -r }
        }
        rotations.flatMap { offsets ->
            val height = offsets.maxOf { it.first } + 1
            val width = offsets.maxOf { it.second } + 1
            (0..rows - height).flatMap { top ->
                (0..cols - width).map { left ->
                    val cells = offsets.map { (r, c) -> (top + r) * cols + left + c }.toIntArray()
                    val boundary = mutableSetOf<Int>()
                    for (cell in cells) {
                        val r = cell / cols
                        val c = cell % cols
                        if (r > 0) boundary.add(cell - cols)
                        if (r + 1 < rows) boundary.add(cell + cols)
                        if (c > 0) boundary.add(cell - 1)
                        if (c + 1 < cols) boundary.add(cell + 1)
                    }
                    boundary.removeAll(cells.toSet())
                    Placement(pattern, cells, boundary.toIntArray())
                }
            }
        }
    }

    fun canAttempt(board: Array<Array<OrbType>>): Boolean {
        if (goals.size > ShapeGoal.MAX_GOALS || goals.size * 5 > rows * cols) return false
        return goals.mapNotNull { it.type }.groupingBy { it }.eachCount().all { (type, count) ->
            board.sumOf { row -> row.count { it == type } } >= count * 5
        }
    }

    fun evaluate(board: Array<Array<OrbType>>): ShapeEvaluation {
        val groups = mutableListOf<Group>()
        val potentials = IntArray(goals.size)
        val counts = IntArray(OrbType.entries.size)
        val touching = IntArray(OrbType.entries.size)
        for (placement in placements) {
            counts.fill(0)
            touching.fill(0)
            for (cell in placement.cells) counts[board[cell / cols][cell % cols].ordinal]++
            for (cell in placement.boundary) touching[board[cell / cols][cell % cols].ordinal]++
            val first = placement.cells[0]
            val firstType = board[first / cols][first % cols]
            if (firstType != OrbType.UNKNOWN && counts[firstType.ordinal] == 5 && touching[firstType.ordinal] == 0) {
                groups.add(Group(placement.pattern, firstType))
            }
            for ((goalIndex, goal) in goals.withIndex()) {
                if (goal.pattern != placement.pattern) continue
                val start = goal.type?.ordinal ?: 0
                val end = goal.type?.ordinal ?: (OrbType.UNKNOWN.ordinal - 1)
                for (type in start..end) {
                    potentials[goalIndex] = maxOf(potentials[goalIndex], counts[type] * 20 - touching[type] * 3)
                }
            }
        }

        val owner = IntArray(groups.size) { -1 }
        fun assign(goalIndex: Int, seen: BooleanArray): Boolean {
            val goal = goals[goalIndex]
            for ((groupIndex, group) in groups.withIndex()) {
                if (seen[groupIndex] || group.pattern != goal.pattern || (goal.type != null && group.type != goal.type)) continue
                seen[groupIndex] = true
                if (owner[groupIndex] == -1 || assign(owner[groupIndex], seen)) {
                    owner[groupIndex] = goalIndex
                    return true
                }
            }
            return false
        }
        val satisfied = goals.indices.count { assign(it, BooleanArray(groups.size)) }
        return ShapeEvaluation(satisfied, potentials.sum())
    }
}
