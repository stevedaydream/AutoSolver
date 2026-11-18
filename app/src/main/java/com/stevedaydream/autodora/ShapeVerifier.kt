package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType
import java.util.concurrent.ConcurrentHashMap

/**
 * 盤面上一個具體的版型配置。cellsMask／boundaryMask 以 row-major 格位為 bit（5×6 = 30 bit，可放進 Long）。
 * orientation：0..3 為旋轉次數，4..7 為鏡像後再旋轉；anchorCell 為外框左上角格位。
 * [orbType] 在列舉階段為 null，驗證命中後才填入實際屬性。
 */
data class ShapePlacement(
    val pattern: ShapePattern,
    val orbType: OrbType?,
    val cellsMask: Long,
    val boundaryMask: Long,
    val orientation: Int,
    val anchorCell: Int,
    val mirrored: Boolean = false
) {
    val cells: IntArray = maskToCells(cellsMask)
    val boundary: IntArray = maskToCells(boundaryMask)

    companion object {
        fun maskToCells(mask: Long): IntArray {
            val out = IntArray(java.lang.Long.bitCount(mask))
            var m = mask
            var i = 0
            while (m != 0L) {
                val bit = java.lang.Long.numberOfTrailingZeros(m)
                out[i++] = bit
                m = m and (m - 1)
            }
            return out
        }
    }
}

/** 預先列舉每個版型在固定盤面尺寸內的所有配置；結果依規則快取，規則不變不重算。 */
internal object PlacementEnumerator {
    data class Orientation(val index: Int, val mirrored: Boolean, val offsets: List<Pair<Int, Int>>)

    private val cache = ConcurrentHashMap<String, List<ShapePlacement>>()

    fun baseOffsets(pattern: ShapePattern): List<Pair<Int, Int>> = when (pattern) {
        ShapePattern.T -> listOf(0 to 0, 0 to 1, 0 to 2, 1 to 1, 2 to 1)
        ShapePattern.L -> listOf(0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2)
        ShapePattern.CROSS -> listOf(0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 1)
    }

    private fun normalize(cells: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val minR = cells.minOf { it.first }
        val minC = cells.minOf { it.second }
        return cells.map { (r, c) -> r - minR to c - minC }.sortedWith(compareBy({ it.first }, { it.second }))
    }

    /** 不重複的朝向；鏡像只在 allowMirror 時加入，且與旋轉重合者會被去除。 */
    fun orientations(pattern: ShapePattern, allowMirror: Boolean): List<Orientation> {
        val seen = LinkedHashMap<List<Pair<Int, Int>>, Orientation>()
        val sources = if (allowMirror) listOf(false, true) else listOf(false)
        for (mirrored in sources) {
            var rotated = baseOffsets(pattern).let { base -> if (mirrored) base.map { (r, c) -> r to -c } else base }
            repeat(4) { turn ->
                val normalized = normalize(rotated)
                if (normalized !in seen) seen[normalized] = Orientation(if (mirrored) 4 + turn else turn, mirrored, normalized)
                rotated = rotated.map { (r, c) -> c to -r }
            }
        }
        return seen.values.toList()
    }

    fun enumerate(pattern: ShapePattern, allowMirror: Boolean, rows: Int = 5, cols: Int = 6): List<ShapePlacement> {
        require(rows * cols <= 63)
        return cache.getOrPut("$pattern/$allowMirror/$rows/$cols") {
            orientations(pattern, allowMirror).flatMap { orientation ->
                val height = orientation.offsets.maxOf { it.first } + 1
                val width = orientation.offsets.maxOf { it.second } + 1
                (0..rows - height).flatMap { top ->
                    (0..cols - width).map { left ->
                        var cells = 0L
                        for ((r, c) in orientation.offsets) cells = cells or (1L shl ((top + r) * cols + left + c))
                        var boundary = 0L
                        for (cell in ShapePlacement.maskToCells(cells)) {
                            val r = cell / cols
                            val c = cell % cols
                            if (r > 0) boundary = boundary or (1L shl (cell - cols))
                            if (r + 1 < rows) boundary = boundary or (1L shl (cell + cols))
                            if (c > 0) boundary = boundary or (1L shl (cell - 1))
                            if (c + 1 < cols) boundary = boundary or (1L shl (cell + 1))
                        }
                        ShapePlacement(pattern, null, cells, boundary and cells.inv(),
                            orientation.index, top * cols + left, orientation.mirrored)
                    }
                }
            }
        }
    }
}

/** 某個條件被哪一個具體配置滿足。 */
data class ShapeMatch(val goalIndex: Int, val placement: ShapePlacement)

data class ShapeVerification(val required: Int, val matches: List<ShapeMatch>) {
    val satisfied: Int get() = matches.size
    val allSatisfied: Boolean get() = matches.size == required
    val usedMask: Long get() = matches.fold(0L) { mask, match -> mask or match.placement.cellsMask }
    fun matchFor(goalIndex: Int): ShapeMatch? = matches.firstOrNull { it.goalIndex == goalIndex }
}

/**
 * 放手前（交換完成、尚未消除）的精確形狀驗證。
 * 預設語義與既有規則相同：恰好 5 顆同色，外緣 4 鄰格不得同色；
 * 每個條件配給一個具體配置，且被選中的配置彼此不得共用格位 `(used & mask) == 0`。
 */
internal class ShapeVerifier(val goals: List<ShapeGoal>, private val rows: Int = 5, private val cols: Int = 6) {
    private val goalPlacements: List<List<ShapePlacement>> =
        goals.map { PlacementEnumerator.enumerate(it.pattern, it.allowMirror, rows, cols) }

    val placementLists: List<List<ShapePlacement>> get() = goalPlacements

    /** 回傳此配置在盤面上成立時的屬性；不成立回傳 null。 */
    fun matchedType(board: Array<Array<OrbType>>, placement: ShapePlacement): OrbType? {
        val first = placement.cells[0]
        val type = board[first / cols][first % cols]
        if (type == OrbType.UNKNOWN) return null
        for (cell in placement.cells) if (board[cell / cols][cell % cols] != type) return null
        for (cell in placement.boundary) if (board[cell / cols][cell % cols] == type) return null
        return type
    }

    fun verify(board: Array<Array<OrbType>>): ShapeVerification {
        val n = goals.size
        if (n == 0) return ShapeVerification(0, emptyList())
        // 相同列舉清單只評估一次（多個 T 條件共用）
        val evaluated = java.util.IdentityHashMap<List<ShapePlacement>, List<ShapePlacement>>()
        val candidates = Array(n) { index ->
            val goal = goals[index]
            val hits = evaluated.getOrPut(goalPlacements[index]) {
                goalPlacements[index].mapNotNull { p -> matchedType(board, p)?.let { p.copy(orbType = it) } }
            }
            if (goal.type == null) hits else hits.filter { it.orbType == goal.type }
        }
        val current = arrayOfNulls<ShapePlacement>(n)
        val best = arrayOfNulls<ShapePlacement>(n)
        var bestCount = -1
        fun search(goalIndex: Int, used: Long, count: Int) {
            if (bestCount == n || count + (n - goalIndex) <= bestCount) return
            if (goalIndex == n) {
                bestCount = count
                for (i in 0 until n) best[i] = current[i]
                return
            }
            for (placement in candidates[goalIndex]) {
                if (used and placement.cellsMask != 0L) continue
                current[goalIndex] = placement
                search(goalIndex + 1, used or placement.cellsMask, count + 1)
                if (bestCount == n) return
            }
            current[goalIndex] = null
            search(goalIndex + 1, used, count)
        }
        search(0, 0L, 0)
        val matches = (0 until n).mapNotNull { i -> best[i]?.let { ShapeMatch(i, it) } }
        return ShapeVerification(n, matches)
    }

    /** 珠數／版面容量可行性；回傳 null 表示值得嘗試，否則回傳不可行原因。 */
    fun infeasibleReason(board: Array<Array<OrbType>>): String? {
        if (goals.size > ShapeGoal.MAX_GOALS) return "條件超過 ${ShapeGoal.MAX_GOALS} 組"
        if (goals.size * 5 > rows * cols) return "條件所需珠數超過盤面"
        if (goalPlacements.any { it.isEmpty() }) return "盤面放不下指定版型"
        val counts = IntArray(OrbType.entries.size)
        for (row in board) for (orb in row) counts[orb.ordinal]++
        val demand = IntArray(OrbType.entries.size)
        goals.forEach { goal -> goal.type?.let { demand[it.ordinal]++ } }
        var spareSlots = 0
        for (type in OrbType.entries) {
            if (type == OrbType.UNKNOWN) continue
            val slots = counts[type.ordinal] / 5
            if (demand[type.ordinal] > slots) return "${type.name} 珠數不足（需 ${demand[type.ordinal] * 5}，僅 ${counts[type.ordinal]}）"
            spareSlots += slots - demand[type.ordinal]
        }
        val anyGoals = goals.count { it.type == null }
        if (anyGoals > spareSlots) return "可組成任意屬性版型的珠數不足"
        return null
    }
}
