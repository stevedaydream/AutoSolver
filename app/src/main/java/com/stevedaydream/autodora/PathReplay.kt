package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType

/**
 * 移動規則與路徑回放的單一來源。
 * 方向編號：0 上、1 下、2 左、3 右；4 左上、5 右上、6 左下、7 右下（後四個只在 8 向可用）。
 * 格位一律為 row-major：cell = r * cols + c。
 */
internal object PathReplay {
    const val ROWS = 5
    const val COLS = 6
    val DIR_R = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
    val DIR_C = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)

    fun directionCount(is8Way: Boolean) = if (is8Way) 8 else 4

    fun directionBetween(from: Int, to: Int, cols: Int = COLS): Int {
        val dr = to / cols - from / cols
        val dc = to % cols - from % cols
        for (d in 0 until 8) if (DIR_R[d] == dr && DIR_C[d] == dc) return d
        return -1
    }

    fun directionsOf(cells: List<Int>, cols: Int = COLS): List<Int> = cells.zipWithNext { a, b -> directionBetween(a, b, cols) }

    /** 路徑格位都在盤內，且每一步都是目前 4／8 向政策允許的相鄰移動。 */
    fun isLegal(cells: List<Int>, is8Way: Boolean, rows: Int = ROWS, cols: Int = COLS): Boolean {
        if (cells.size < 2) return false
        if (cells.any { it !in 0 until rows * cols }) return false
        val allowed = directionCount(is8Way)
        for (i in 1 until cells.size) {
            val d = directionBetween(cells[i - 1], cells[i], cols)
            if (d !in 0 until allowed) return false
        }
        return true
    }

    /** 回傳依手指路徑逐格交換後（尚未消除）的新盤面；不修改輸入。 */
    fun replay(board: Array<Array<OrbType>>, cells: List<Int>, cols: Int = COLS): Array<Array<OrbType>> {
        val out = Array(board.size) { board[it].clone() }
        for (i in 1 until cells.size) {
            val a = cells[i - 1]; val b = cells[i]
            val temp = out[a / cols][a % cols]
            out[a / cols][a % cols] = out[b / cols][b % cols]
            out[b / cols][b % cols] = temp
        }
        return out
    }

    /**
     * 從 [from]（上一步在 [previous]）走到 [target] 的補走路徑（不含 from）。
     * 優先朝目標前進、不立即折返；最多 [maxSteps] 步，走不到回傳 null。用於「指定終點」時把好路線延伸到終點。
     */
    fun walkTo(from: Int, previous: Int, target: Int, is8Way: Boolean, maxSteps: Int,
               rows: Int = ROWS, cols: Int = COLS): List<Int>? {
        val out = ArrayList<Int>()
        var cur = from
        var prev = previous
        val allowed = directionCount(is8Way)
        fun dist(a: Int): Int {
            val dr = kotlin.math.abs(a / cols - target / cols); val dc = kotlin.math.abs(a % cols - target % cols)
            return if (is8Way) maxOf(dr, dc) else dr + dc
        }
        while (cur != target) {
            if (out.size >= maxSteps) return null
            var best = -1
            var bestDist = Int.MAX_VALUE
            for (d in 0 until allowed) {
                val r = cur / cols + DIR_R[d]; val c = cur % cols + DIR_C[d]
                if (r !in 0 until rows || c !in 0 until cols) continue
                val next = r * cols + c
                if (next == prev) continue
                val nd = dist(next)
                if (nd < bestDist) { bestDist = nd; best = next }
            }
            if (best < 0) return null
            out.add(best)
            prev = cur
            cur = best
        }
        return out
    }

    /**
     * 執行前比對盤面：目前畫面中未辨識（特效動畫）的格子視為相符，其餘每格都要一致。
     * 未辨識格超過 [maxUnknown] 視為不相符（可能已離開盤面畫面）。
     */
    fun fingerprintMatches(expected: String, actual: String, maxUnknown: Int = 4): Boolean {
        if (expected.length != actual.length) return false
        val unknown = BoardAnalyzer.OrbType.UNKNOWN.ordinal.toString()[0]
        var unknownCount = 0
        for (i in expected.indices) {
            if (actual[i] == unknown) { unknownCount++; continue }
            if (actual[i] != expected[i]) return false
        }
        return unknownCount <= maxUnknown
    }

    /** 盤面指紋：每格屬性序號（UNKNOWN = 6），供預覽失效判斷。 */
    fun fingerprint(board: Array<Array<OrbType>>): String {
        val sb = StringBuilder(board.size * (board.firstOrNull()?.size ?: 0))
        for (row in board) for (orb in row) sb.append(orb.ordinal)
        return sb.toString()
    }
}

/** 精確（無碰撞）的狀態鍵：30 格 × 3 bit = 90 bit 分兩個 Long，再附手指位置與前一步位置。 */
internal data class StateKey(val lo: Long, val hi: Long) {
    companion object {
        fun of(board: Array<Array<OrbType>>, held: Int = 0, previous: Int = -1): StateKey {
            var lo = 0L
            var hi = 0L
            var i = 0
            for (row in board) for (orb in row) {
                val v = orb.ordinal.toLong()
                if (i < 20) lo = lo or (v shl (i * 3)) else hi = hi or (v shl ((i - 20) * 3))
                i++
            }
            // hi 已用 30 bit；手指位置與前一步各 6 bit（前一步 -1 以 63 表示）
            hi = hi or (held.toLong() shl 30) or ((if (previous < 0) 63 else previous).toLong() shl 36)
            return StateKey(lo, hi)
        }
    }
}
