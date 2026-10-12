package com.stevedaydream.autodora

/** 不補入新珠的連鎖模擬；工作區由單次求解獨占，避免每個搜尋節點配置陣列。 */
internal class BoardSimulator(private val rows: Int, private val cols: Int) {
    init {
        require(rows > 0 && cols > 0)
    }

    private val cells = IntArray(rows * cols)
    private val matched = BooleanArray(cells.size)
    private val visited = BooleanArray(cells.size)
    private val queue = IntArray(cells.size)
    val firstWaveMatch = Array(rows) { BooleanArray(cols) }
    var firstWaveCombos = 0
        private set

    fun resolve(board: Array<Array<BoardAnalyzer.OrbType>>): Int {
        require(board.size == rows && board.all { it.size == cols })
        for (r in 0 until rows) for (c in 0 until cols) {
            val type = board[r][c]
            require(type != BoardAnalyzer.OrbType.UNKNOWN) { "盤面仍有未辨識符石" }
            cells[r * cols + c] = type.ordinal
        }

        var wave = countMatches()
        firstWaveCombos = wave
        for (r in 0 until rows) for (c in 0 until cols) {
            firstWaveMatch[r][c] = matched[r * cols + c]
        }
        var total = wave
        while (wave > 0) {
            for (i in cells.indices) if (matched[i]) cells[i] = EMPTY
            for (c in 0 until cols) {
                var dest = rows - 1
                for (r in rows - 1 downTo 0) {
                    val type = cells[r * cols + c]
                    if (type != EMPTY) cells[dest-- * cols + c] = type
                }
                for (r in 0..dest) cells[r * cols + c] = EMPTY
            }
            wave = countMatches()
            total += wave
        }
        return total
    }

    private fun countMatches(): Int {
        matched.fill(false)
        visited.fill(false)
        for (r in 0 until rows) for (c in 0 until (cols - 2)) {
            val i = r * cols + c
            val type = cells[i]
            if (type != EMPTY && cells[i + 1] == type && cells[i + 2] == type) {
                matched[i] = true; matched[i + 1] = true; matched[i + 2] = true
            }
        }
        for (r in 0 until (rows - 2)) for (c in 0 until cols) {
            val i = r * cols + c
            val type = cells[i]
            if (type != EMPTY && cells[i + cols] == type && cells[i + 2 * cols] == type) {
                matched[i] = true; matched[i + cols] = true; matched[i + 2 * cols] = true
            }
        }

        var combos = 0
        for (i in cells.indices) {
            if (!matched[i] || visited[i]) continue
            combos++
            val type = cells[i]
            var head = 0
            var tail = 1
            queue[0] = i
            visited[i] = true
            while (head < tail) {
                val current = queue[head++]
                val r = current / cols
                val c = current % cols
                if (r > 0) tail = enqueue(current - cols, type, tail)
                if (r + 1 < rows) tail = enqueue(current + cols, type, tail)
                if (c > 0) tail = enqueue(current - 1, type, tail)
                if (c + 1 < cols) tail = enqueue(current + 1, type, tail)
            }
        }
        return combos
    }

    private fun enqueue(index: Int, type: Int, tail: Int): Int {
        if (!matched[index] || visited[index] || cells[index] != type) return tail
        visited[index] = true
        queue[tail] = index
        return tail + 1
    }

    private companion object {
        const val EMPTY = -1
    }
}
