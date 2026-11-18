package com.stevedaydream.autodora

/**
 * 依舊版 AutoDora V3.4.3 `DoraService` 位元組碼靜態還原的路線樣式參數（未在裝置上執行舊版）。
 * - getRainbowColor(i, n)：7 個錨點色整數線性內插，第一步取錨點 0、最後一步取錨點 6。
 * - drawPath：每段先畫外層（黑 12px → 色 9px），再畫內層（黑 6px → 色 5px）；
 *   mPathColor == 0 時外層灰階由暗到亮、內層灰階由亮到暗，否則外層白、內層彩虹。
 *   重疊路段端點錯開 6px；起點以半徑 20px 圓圈標示。
 * - drawAL：只在終點畫一個箭頭，長 40px、半寬 18px，淺灰 (230,230,230) 填色加 3px 描邊，再以 1px 描邊。
 * 舊版尺寸為裝置像素；新版依珠寬等比放大（以 120px 珠寬為 1 倍，屬推定）。
 */
object LegacyPathStyle {
    val RAINBOW_ANCHORS = intArrayOf(
        0xFFFF0000.toInt(), 0xFFFFA500.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
        0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFF8000FF.toInt()
    )

    const val OUTER_OUTLINE_PX = 12f
    const val OUTER_COLOR_PX = 9f
    const val INNER_OUTLINE_PX = 6f
    const val INNER_COLOR_PX = 5f
    const val OVERLAP_OFFSET_PX = 6f
    const val START_RADIUS_PX = 20f
    const val ARROW_LENGTH_PX = 40f
    const val ARROW_HALF_WIDTH_PX = 18f
    const val ARROW_STROKE_PX = 3f
    const val ARROW_OUTLINE_PX = 1f
    const val ARROW_FILL = 0xFFE6E6E6.toInt()
    const val REFERENCE_ORB_PX = 120f

    fun scaleFor(orbSize: Float) = (orbSize / REFERENCE_ORB_PX).coerceAtLeast(0.5f)

    /** 與舊版 getRainbowColor 相同的整數運算。 */
    fun rainbowColor(index: Int, count: Int): Int {
        if (index == 0) return RAINBOW_ANCHORS[0]
        if (index == count - 1) return RAINBOW_ANCHORS[6]
        val scaled = index * 6 * 100 / count
        val segment = scaled / 100
        val within = scaled - segment * 100
        if (segment >= 6) return RAINBOW_ANCHORS[6]
        val a = RAINBOW_ANCHORS[segment]
        val b = RAINBOW_ANCHORS[segment + 1]
        fun channel(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return ca + (cb - ca) * within / 100
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** 外層 9px 顏色：有路徑色時為白，否則灰階 130 + step*i/2（由暗到亮）。 */
    fun outerColor(index: Int, count: Int, colored: Boolean): Int =
        if (colored) 0xFFFFFFFF.toInt() else gray(130 + (220 / count.coerceAtLeast(1)) * index / 2)

    /** 內層 5px 顏色：有路徑色時為彩虹，否則灰階 230 − step*i（由亮到暗）。 */
    fun innerColor(index: Int, count: Int, colored: Boolean): Int =
        if (colored) rainbowColor(index, count) else gray(230 - (220 / count.coerceAtLeast(1)) * index)
}
