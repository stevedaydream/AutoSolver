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
     * 搜尋引導時每 1 點成本折抵的分數（一顆剩餘珠 = 4 點，約 1／4 個 Combo）。不用純字典序：
     * 字典序會讓 beam 全塞滿「剛好全消」的相似路線，失去繼續堆 Combo 的空間。
     */
    @JvmField var guideWeight = 3000  // 36 個隨機盤面實測：達成率與 12500 相同，平均 Combo 最高

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
     * 搜尋引導成本（越小越好）：剩餘顆數為主，剩餘中「沒有同色鄰珠」的孤立珠再加一分，
     * 讓搜尋偏好先把目標屬性聚在一起。只用於排序，不代表可達性。
     */
    fun cost(board: Array<Array<OrbType>>, firstWave: Array<BooleanArray>, types: Set<OrbType>): Int {
        if (types.isEmpty()) return 0
        var cost = 0
        val rows = board.size
        for (r in 0 until rows) {
            val cols = board[r].size
            for (c in 0 until cols) {
                val t = board[r][c]
                if (t !in types || firstWave[r][c]) continue
                cost += 4
                val touching = (r > 0 && board[r - 1][c] == t) || (r + 1 < rows && board[r + 1][c] == t) ||
                    (c > 0 && board[r][c - 1] == t) || (c + 1 < cols && board[r][c + 1] == t)
                if (!touching) cost += 1
            }
        }
        return cost
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
