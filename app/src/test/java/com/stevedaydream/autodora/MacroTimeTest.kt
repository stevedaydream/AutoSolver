package com.stevedaydream.autodora

import org.junit.Assert.*
import org.junit.Test

class MacroTimeTest {
    @Test fun secondsRetainRecordedPrecisionWithoutTrailingZeros() {
        listOf(0L, 1L, 80L, 250L, 1500L, 123456L).forEach {
            assertEquals(it, MacroTime.milliseconds(MacroTime.seconds(it), true))
        }
        assertEquals("0.25", MacroTime.seconds(250))
        assertEquals("1.5", MacroTime.seconds(1500))
    }

    @Test fun rejectsInvalidOrOverflowingTimes() {
        listOf("", "-1", "NaN", "Infinity", "0.0001", "999999999999999999999999").forEach {
            assertNull(MacroTime.milliseconds(it))
        }
        assertNull(MacroTime.milliseconds("0"))
        assertEquals(0L, MacroTime.milliseconds("0", true))
    }
}
