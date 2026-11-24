package com.stevedaydream.autodora

/**
 * 轉珠的起點／終點限制（row-major 格位 0..29；-1 表示不限）。
 * 終點限制：放手時手指必須停在 [endCell]。
 */
data class MoveConstraint(val startCell: Int = -1, val endCell: Int = -1) {
    val hasStart: Boolean get() = startCell in 0 until CELLS
    val hasEnd: Boolean get() = endCell in 0 until CELLS

    /** 起手格清單；有指定起點時只用它，否則沿用舊的 startMode（0 全盤、1 左下、2 右下）。 */
    fun startCells(startMode: Int = 0): List<Int> = when {
        hasStart -> listOf(startCell)
        startMode == 1 -> listOf(24)
        startMode == 2 -> listOf(29)
        else -> (0 until CELLS).toList()
    }

    fun endOk(cell: Int): Boolean = !hasEnd || cell == endCell

    companion object {
        const val CELLS = 30
        val NONE = MoveConstraint()
        /** 舊版（v14）存在設定裡的起終點；改為每回合在盤面上指定後不再使用。 */
        val LEGACY_PREF_KEYS = listOf("startCell", "endCell")

        fun label(cell: Int): String = if (cell !in 0 until CELLS) "不限" else "第 ${cell / 6 + 1} 列第 ${cell % 6 + 1} 行"
    }
}
