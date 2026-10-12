package com.stevedaydream.autodora

import org.junit.Assert.*
import org.junit.Test

class RecordingTouchGateTest {
    @Test fun duplicateReleaseAndOrphanReleaseAreIgnored() {
        val gate = RecordingTouchGate()
        assertFalse(gate.up(10))
        assertTrue(gate.down(10))
        assertTrue(gate.up(10))
        assertFalse(gate.up(10))
        assertFalse(gate.up(11))
    }

    @Test fun intentionalRepeatedTapsRemainSeparate() {
        val gate = RecordingTouchGate()
        repeat(3) { i ->
            assertTrue(gate.down(i.toLong()))
            assertTrue(gate.up(i.toLong()))
        }
    }

    @Test fun cancellationDoesNotGenerateAnAction() {
        val gate = RecordingTouchGate()
        assertTrue(gate.down(1))
        assertFalse(gate.down(2))
        gate.cancel()
        assertFalse(gate.up(1))
        assertTrue(gate.down(2))
        assertFalse(gate.up(1))
        assertTrue(gate.up(2))
    }
}
