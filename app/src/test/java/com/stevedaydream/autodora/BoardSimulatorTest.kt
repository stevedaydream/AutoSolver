package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType
import org.junit.Assert.*
import org.junit.Test

class BoardSimulatorTest {
    @Test fun connectedLinesCountAsOneCombo() {
        for (shape in listOf(arrayOf("FFF", "WFW", "WFW"), arrayOf("FWW", "FWW", "FFF"), arrayOf("WFW", "FFF", "WFW"))) {
            val simulator = BoardSimulator(3, 3)
            assertEquals(1, simulator.resolve(board(*shape)))
            assertEquals(1, simulator.firstWaveCombos)
            assertEquals(5, simulator.firstWaveMatch.sumOf { row -> row.count { it } })
        }
    }

    @Test fun cascadePreservesInputAndFirstWave() {
        val board = cascadeBoard()
        val original = board.map { it.clone() }.toTypedArray()
        val simulator = BoardSimulator(5, 6)
        assertEquals(2, simulator.resolve(board))
        assertEquals(1, simulator.firstWaveCombos)
        assertEquals(5, simulator.firstWaveMatch.sumOf { row -> row.count { it } })
        assertTrue(board.contentDeepEquals(original))
        assertEquals(2, simulator.resolve(board))
        assertEquals(0, simulator.resolve(board("WFLHDW", "FLHDWF", "LHDWFL", "HDWFLH", "DWFLHD")))
        assertEquals(0, simulator.firstWaveCombos)
        assertTrue(simulator.firstWaveMatch.all { row -> row.none { it } })
    }

    @Test fun dimensionsAndUnknownAreRejected() {
        val simulator = BoardSimulator(5, 6)
        assertThrows(IllegalArgumentException::class.java) { simulator.resolve(board("FFF")) }
        val incomplete = cascadeBoard().also { it[0][0] = OrbType.UNKNOWN }
        assertThrows(IllegalArgumentException::class.java) { simulator.resolve(incomplete) }
        assertTrue(BoardAnalyzer().calculateOptimalPath(incomplete, 100f, 0f, 0f).first.isEmpty())
        val result = BoardAnalyzer().calculatePolicyPath(incomplete, 100f, 0f, 0f, 5, 0) { _, _, _, _ ->
            fail("未辨識完成的盤面不可交給模型")
            emptyList()
        }
        assertTrue(result.first.isEmpty())
    }

    @Test fun policyResultIncludesDeterministicCascade() {
        val result = BoardAnalyzer().calculatePolicyPath(cascadeBoard(), 100f, 20f, 30f, 5, 1) { _, _, _, _ ->
            listOf(4 to 0, 4 to 1)
        }
        assertEquals(2, result.second)
        assertEquals(listOf(70f to 492.5f, 170f to 492.5f), result.first)
    }

    @Test fun beamPathReplaysToReportedComboCount() {
        val initial = cascadeBoard()
        for (strategy in 0..2) {
            val result = BoardAnalyzer().calculateOptimalPath(initial, 100f, 0f, 0f, false, strategy, 0, 5, 30)
            assertTrue(result.first.size in 2..6)
            val replay = initial.map { it.clone() }.toTypedArray()
            val cells = result.first.map { (x, y) -> (y / 100).toInt() to (x / 100).toInt() }
            for ((a, b) in cells.zipWithNext()) {
                assertEquals(1, kotlin.math.abs(a.first - b.first) + kotlin.math.abs(a.second - b.second))
                val temp = replay[a.first][a.second]
                replay[a.first][a.second] = replay[b.first][b.second]
                replay[b.first][b.second] = temp
            }
            assertEquals(BoardSimulator(5, 6).resolve(replay), result.second)
        }
    }

    companion object {
        fun cascadeBoard() = board("DFLHDW", "LWHDFL", "HFLWHD", "WFWDLH", "FFFHDW")

        fun board(vararg rows: String): Array<Array<OrbType>> = rows.map { row ->
            row.map { when (it) {
                'W' -> OrbType.WATER
                'F' -> OrbType.FIRE
                'O' -> OrbType.WOOD
                'L' -> OrbType.LIGHT
                'D' -> OrbType.DARK
                'H' -> OrbType.HEART
                else -> error("未知測試符石")
            } }.toTypedArray()
        }.toTypedArray()
    }
}
