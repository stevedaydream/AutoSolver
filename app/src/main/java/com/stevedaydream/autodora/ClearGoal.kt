package com.stevedaydream.autodora

import android.content.SharedPreferences
import com.stevedaydream.autodora.BoardAnalyzer.OrbType

/**
 * 「首批全消」條件：放手後第一波消除必須包含盤面上指定屬性的每一顆珠子（不計連鎖與天降）。
 * 盤面上沒有該屬性時視為已達成；只有 1～2 顆時不可能三消，視為不可行。
 */
object ClearGoal {
    const val PREF_KEY = "clearTypes"

    /**
     * 搜尋引導時每 1 點成本折抵的分數（約 1 個 Combo；一顆剩餘珠另加 4 點）。不用純字典序：
     * 字典序會讓 beam 全塞滿「剛好全消」的相似路線，失去繼續堆 Combo 的空間。
     */
    @JvmField var guideWeight = 50000  // 71 個隨機條件實測：達成 69（舊版 53），平均 5.6 Combo；使用者實盤 5 Combo

    fun read(prefs: SharedPreferences): Set<OrbType> = parse(prefs.getString(PREF_KEY, "") ?: "")

    fun parse(text: String): Set<OrbType> = text.split(',').mapNotNull { name ->
        OrbType.entries.firstOrNull { it.name == name.trim() && it != OrbType.UNKNOWN }
    }.toSet()

    fun format(types: Set<OrbType>): String = OrbType.entries.filter { it in types }.joinToString(",") { it.name }

    /** 首批之後仍留在盤面的指定屬性珠數；0 表示達成。[firstWave] 為 BoardSimulator.firstWaveMatch。 */
    fun remaining(board: Array<Array<OrbType>>, firstWave: Array<BooleanArray>, types: Set<OrbType>): Int {
        if (types.isEmpty()) return 0
        var left = 0
        for (r in board.indices) for (c in board[r].indices) if (board[r][c] in types && !firstWave[r][c]) left++
        return left
    }

    /**
     * 搜尋引導成本（越小越好，只用於排序，不代表可達性）：
     * 每顆首批沒消到的目標珠 4 點，再加上「把該屬性所有珠子排成一直線（橫或直）最少要移動幾格」。
     * 全消需要所有同色珠在同一批連成一組，只看相鄰與否在珠子分散時沒有方向；
     * 排成直線的距離給出逐步靠攏的梯度。橫線：列差固定、行號排序後一一對應即為最佳指派。
     */
    /**
     * [anchorCell]／[anchorType]：起結有序盾時，手指一路拿著起點珠，放手時它一定停在終點格。
     * 若手上的珠就是要全消的屬性，排列目標只考慮「經過終點格」的直線，否則會引導到永遠到不了的排法。
     */
    fun cost(board: Array<Array<OrbType>>, firstWave: Array<BooleanArray>, types: Set<OrbType>,
             anchorCell: Int = -1, anchorType: OrbType? = null): Int {
        if (types.isEmpty()) return 0
        var total = 0
        for (type in types) {
            var leftover = 0
            val rs = ArrayList<Int>(); val cs = ArrayList<Int>()
            for (r in board.indices) for (c in board[r].indices) if (board[r][c] == type) {
                rs.add(r); cs.add(c)
                if (!firstWave[r][c]) leftover++
            }
            if (leftover == 0) continue
            total += leftover * 4 + gatherDistance(board, firstWave, type, rs, cs, if (type == anchorType) anchorCell else -1)
        }
        return total
    }

    /**
     * 還沒有任何一顆被消到：以「全部排成一直線」的距離引導；
     * 已有部分成組：剩下的珠子只要併入既有的消除組，改用「到最近已消同色珠的距離」。
     */
    private fun gatherDistance(board: Array<Array<OrbType>>, firstWave: Array<BooleanArray>, type: OrbType,
                               rs: List<Int>, cs: List<Int>, anchor: Int): Int {
        val matched = rs.indices.filter { firstWave[rs[it]][cs[it]] }
        val left = rs.indices.filter { !firstWave[rs[it]][cs[it]] }
        if (matched.isEmpty() || anchor >= 0) return formationDistance(rs, cs, board.size, board[0].size, anchor)
        val join = left.sumOf { i ->
            matched.minOf { j -> kotlin.math.abs(rs[i] - rs[j]) + kotlin.math.abs(cs[i] - cs[j]) } - 1
        }
        if (left.size < 3) return join
        // 剩下 3 顆以上也可以自成一組：取兩種估計較小者
        return minOf(join, formationDistance(left.map { rs[it] }, left.map { cs[it] }, board.size, board[0].size))
    }

    /** 把 (rs, cs) 這些珠子排成一條連續橫線或直線的最少曼哈頓距離總和；太多顆排不下一條線時分成多段。 */
    internal fun formationDistance(rs: List<Int>, cs: List<Int>, rows: Int, cols: Int, anchor: Int = -1): Int {
        val k = rs.size
        if (k == 0) return 0
        // anchorLine／anchorPos：直線必須經過的格子（-1 表示不限）
        fun lineCost(fixed: List<Int>, along: List<Int>, lines: Int, length: Int, anchorLine: Int, anchorPos: Int): Int {
            if (k > length) return Int.MAX_VALUE
            val sortedAlong = along.sorted()
            var best = Int.MAX_VALUE
            for (line in 0 until lines) {
                if (anchorLine >= 0 && line != anchorLine) continue
                val fixedCost = fixed.sumOf { kotlin.math.abs(it - line) }
                for (start in 0..length - k) {
                    if (anchorPos >= 0 && anchorPos !in start until start + k) continue
                    var cost = fixedCost
                    for (i in 0 until k) cost += kotlin.math.abs(sortedAlong[i] - (start + i))
                    if (cost < best) best = cost
                }
            }
            return best
        }
        val ar = if (anchor >= 0) anchor / cols else -1
        val ac = if (anchor >= 0) anchor % cols else -1
        val horizontal = lineCost(rs, cs, rows, cols, ar, ac)
        val vertical = lineCost(cs, rs, cols, rows, ac, ar)
        val best = minOf(horizontal, vertical)
        if (best != Int.MAX_VALUE) return best
        if (anchor >= 0) return formationDistance(rs, cs, rows, cols)
        // 超過一條線的長度：依列排序後切成每段最多一整列，各自估算
        val idx = rs.indices.sortedWith(compareBy({ rs[it] }, { cs[it] }))
        return idx.chunked(cols).sumOf { chunk -> formationDistance(chunk.map { rs[it] }, chunk.map { cs[it] }, rows, cols) }
    }

    fun infeasibleReason(board: Array<Array<OrbType>>, types: Set<OrbType>): String? {
        for (type in types) {
            val count = board.sumOf { row -> row.count { it == type } }
            if (count in 1..2) return "${label(type)}珠只有 $count 顆，首批無法全消"
        }
        return null
    }

    fun label(type: OrbType?): String = when (type) {
        OrbType.WATER -> "水"; OrbType.FIRE -> "火"; OrbType.WOOD -> "木"
        OrbType.LIGHT -> "光"; OrbType.DARK -> "暗"; OrbType.HEART -> "心"
        else -> "任意"
    }

    fun describe(types: Set<OrbType>): String = OrbType.entries.filter { it in types }.joinToString("") { label(it) }
}
