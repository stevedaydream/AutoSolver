package com.stevedaydream.autodora

import org.junit.Assert.*
import org.junit.Test

class LegacyPathStyleTest {
    private fun hex(c: Int) = "#%08X".format(c)

    @Test fun rainbowMatchesLegacyIntegerInterpolation() {
        assertEquals("#FFFF0000", hex(LegacyPathStyle.rainbowColor(0, 12)))
        assertEquals("#FF8000FF", hex(LegacyPathStyle.rainbowColor(11, 12)))
        // i=1,n=12：1*6*100/12 = 50 → 紅→橙 50%：G = 0 + 165*50/100 = 82
        assertEquals("#FFFF5200", hex(LegacyPathStyle.rainbowColor(1, 12)))
        // i=2,n=12：段 1、0% → 正好是橙色錨點
        assertEquals("#FFFFA500", hex(LegacyPathStyle.rainbowColor(2, 12)))
        // i=5,n=12：段 2、50% → 黃→綠：R = 255 + (0-255)*50/100 = 128
        assertEquals("#FF80FF00", hex(LegacyPathStyle.rainbowColor(5, 12)))
        for (n in 2..40) for (i in 0 until n) assertEquals(0xFF, LegacyPathStyle.rainbowColor(i, n) ushr 24)
    }

    @Test fun grayModeMatchesLegacyRamps() {
        // 外層 130 + (220/n)*i/2 由暗到亮；內層 230 − (220/n)*i 由亮到暗
        assertEquals("#FF828282", hex(LegacyPathStyle.outerColor(0, 10, colored = false)))
        assertEquals("#FFE5E5E5", hex(LegacyPathStyle.outerColor(9, 10, colored = false)))
        assertEquals("#FFE6E6E6", hex(LegacyPathStyle.innerColor(0, 10, colored = false)))
        assertEquals("#FF202020", hex(LegacyPathStyle.innerColor(9, 10, colored = false)))
        assertEquals("#FFFFFFFF", hex(LegacyPathStyle.outerColor(3, 10, colored = true)))
        assertEquals(LegacyPathStyle.rainbowColor(3, 10), LegacyPathStyle.innerColor(3, 10, colored = true))
    }

    @Test fun scaleFollowsOrbWidth() {
        assertEquals(1f, LegacyPathStyle.scaleFor(120f))
        assertEquals(2f, LegacyPathStyle.scaleFor(240f))
        assertEquals(0.5f, LegacyPathStyle.scaleFor(10f))
    }
}
