package com.stevedaydream.autodora

import org.junit.Assert.assertEquals
import org.junit.Test

class ComboPlanTest {
    private val steps = listOf(ComboStep("a", 1), ComboStep("b", 3), ComboStep("c", 2))

    @Test fun repeatStepperWrapsThroughInfinity() {
        assertEquals(0, RepeatCount.dec(1))
        assertEquals(99, RepeatCount.dec(0))
        assertEquals(1, RepeatCount.inc(0))
        assertEquals(0, RepeatCount.inc(99))
        assertEquals("∞", RepeatCount.label(0))
        assertEquals("×5", RepeatCount.label(5))
    }

    @Test fun comboStepRepeatClampsWithoutInfinity() {
        assertEquals(1, RepeatCount.decStep(1))
        assertEquals(99, RepeatCount.incStep(99))
        assertEquals(4, RepeatCount.incStep(3))
    }

    @Test fun missingStepsAndRunsPerRound() {
        assertEquals(listOf(1), ComboPlan.missingSteps(steps, setOf("a", "c")))
        assertEquals(6, ComboPlan.runsPerRound(steps))
    }

    @Test fun moveReordersAndIgnoresOutOfRange() {
        assertEquals(listOf("b", "a", "c"), ComboPlan.move(steps, 0, 1).map { it.macroId })
        assertEquals(listOf("c", "a", "b"), ComboPlan.move(steps, 2, 0).map { it.macroId })
        assertEquals(steps, ComboPlan.move(steps, 0, 3))
        assertEquals(steps, ComboPlan.move(steps, -1, 0))
    }

    @Test fun sanitizeClampsRepeatAndDropsBlankIds() {
        val cleaned = ComboPlan.sanitize(listOf(ComboStep("a", 0), ComboStep("", 2), ComboStep("b", 500)))
        assertEquals(listOf(ComboStep("a", 1), ComboStep("b", 99)), cleaned)
    }

    @Test fun usageCountCountsCombosNotSteps() {
        val combos = listOf(
            MacroCombo("1", "x", listOf(ComboStep("a"), ComboStep("a")), 0),
            MacroCombo("2", "y", listOf(ComboStep("b")), 0)
        )
        assertEquals(1, ComboPlan.usageCount(combos, "a"))
        assertEquals(0, ComboPlan.usageCount(combos, "z"))
    }

    @Test fun clipboardPicksInListOrderAndInsertsAfterSelection() {
        val list = listOf("p", "q", "r", "s")
        assertEquals(listOf("q", "s"), ClipboardOrder.pick(list, listOf(3, 1, 1, 9)))
        assertEquals(0, ClipboardOrder.insertAt(0, 0))
        assertEquals(2, ClipboardOrder.insertAt(4, 1))
        assertEquals(4, ClipboardOrder.insertAt(4, 3))
        assertEquals(listOf("p", "r"), ClipboardOrder.removeAll(list, setOf(1, 3)))
    }
}
