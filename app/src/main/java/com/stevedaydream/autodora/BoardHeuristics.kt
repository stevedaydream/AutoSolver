package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType

/** 與 Android 無關的評分小工具，抽出來方便 JVM 測試。 */
internal object BoardHeuristics {
    /**
     * (r, c) 起算的橫向三格是否「剛好三顆」：三格同色，且左右兩側都不是同色。
     * 舊版只檢查右側，`AAAABC` 從 c=1 起算的尾端三連會被誤當成三連珠加分。
     * 上下鄰格同色（形成 T／L）不在此判斷範圍，維持原本的橫向語義。
     */
    fun isExactHorizontalTriple(board: Array<Array<OrbType>>, r: Int, c: Int): Boolean {
        val row = board[r]
        if (c < 0 || c + 2 >= row.size) return false
        val t = row[c]
        if (t == OrbType.UNKNOWN || row[c + 1] != t || row[c + 2] != t) return false
        val leftClear = c == 0 || row[c - 1] != t
        val rightClear = c + 3 >= row.size || row[c + 3] != t
        return leftClear && rightClear
    }
}
