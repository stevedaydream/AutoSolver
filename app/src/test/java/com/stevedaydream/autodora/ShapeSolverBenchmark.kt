package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * P1-S：形狀模式 A/B benchmark（Beam baseline vs Shape-Aware），相同 seed／步數／廣度。
 * 預設略過；執行方式：
 *   $env:SHAPE_BENCHMARK='1'; .\gradlew.bat testDebugUnitTest --tests "*ShapeSolverBenchmark*"
 * 結果寫到 app/build/reports/shape-benchmark.md。
 */
class ShapeSolverBenchmark {
    private data class Case(val name: String, val board: Array<Array<OrbType>>, val goals: List<ShapeGoal>, val startMode: Int = 0)
    private data class Run(val solved: Boolean, val ms: Double, val combos: Int, val steps: Int, val nodes: Long?, val firstShapeMs: Long?)

    private val steps = 20
    private val beamWidth = 120
    private val timeLimitMs = 3000L

    private fun solvedBases(): List<Pair<String, Pair<Array<Array<OrbType>>, List<ShapeGoal>>>> = listOf(
        "火T+暗十字" to (ShapeAwareSolverTest.solvedFireTDarkCross() to ShapeAwareSolverTest.fireTDarkCross),
        "單T(任意)" to (BoardSimulatorTest.board("FFFWLO", "HFHLWD", "OFDWOL", "WLOHDW", "LDWOLH") to listOf(ShapeGoal(ShapePattern.T))),
        "單L(水)" to (BoardSimulatorTest.board("WFHLOD", "WOFDHL", "WWWHFO", "LDOFLH", "HFLDOW") to listOf(ShapeGoal(ShapePattern.L, OrbType.WATER))),
        "單十字(木)" to (BoardSimulatorTest.board("FOHLWD", "OOOFLH", "DOWHFL", "LHDWDF", "WFLHLW") to listOf(ShapeGoal(ShapePattern.CROSS, OrbType.WOOD))),
        "T+L" to (BoardSimulatorTest.board("FFFWLD", "HFHWLD", "HFHWWW", "LDOHOL", "OLDLHO") to
            listOf(ShapeGoal(ShapePattern.T, OrbType.FIRE), ShapeGoal(ShapePattern.L, OrbType.WATER))),
        "同形兩組T" to (BoardSimulatorTest.board("FFFWWW", "HFHHWH", "HFHHWH", "LDOLDO", "OLDOLD") to
            List(2) { ShapeGoal(ShapePattern.T) })
    )

    private fun randomWalk(random: Random, length: Int, startMode: Int): List<Int> {
        var cell = when (startMode) { 1 -> 24; 2 -> 29; else -> random.nextInt(30) }
        val path = mutableListOf(cell)
        while (path.size <= length) {
            val d = random.nextInt(4)
            val r = cell / 6 + PathReplay.DIR_R[d]; val c = cell % 6 + PathReplay.DIR_C[d]
            if (r !in 0 until 5 || c !in 0 until 6) continue
            val next = r * 6 + c
            if (path.size >= 2 && next == path[path.size - 2]) continue
            path.add(next); cell = next
        }
        return path
    }

    private fun dataset(): List<Case> {
        val random = Random(20261118)
        val cases = mutableListOf<Case>()
        for ((name, base) in solvedBases()) {
            require(ShapeVerifier(base.second).verify(base.first).allSatisfied) { "$name 基準盤面未達成" }
            for (length in listOf(3, 6, 10)) repeat(3) { i ->
                cases += Case("$name/打亂$length#$i", PathReplay.replay(base.first, randomWalk(random, length, 0)), base.second)
            }
        }
        // 限制起手：從左下角打亂再要求同一角起手
        cases += Case("火T+暗十字/左下起手", PathReplay.replay(ShapeAwareSolverTest.solvedFireTDarkCross(), randomWalk(random, 5, 1)),
            ShapeAwareSolverTest.fireTDarkCross, startMode = 1)
        // 不可行：珠數不足、條件過多
        cases += Case("不可行/木L", BoardSimulatorTest.cascadeBoard(), listOf(ShapeGoal(ShapePattern.L, OrbType.WOOD)))
        cases += Case("不可行/三組任意T", BoardSimulatorTest.board("WFOLDH", "WFOLDH", "WFOLDH", "WFOLDH", "OOOOOO"), List(3) { ShapeGoal(ShapePattern.T) })
        return cases
    }

    private fun runBaseline(case: Case): Run {
        val t0 = System.nanoTime()
        val result = BoardAnalyzer().calculateOptimalPath(case.board, 100f, 0f, 0f, false, 0, case.startMode, steps, beamWidth, case.goals)
        val ms = (System.nanoTime() - t0) / 1e6
        if (result.first.isEmpty()) return Run(false, ms, 0, 0, null, null)
        val cells = result.first.map { (x, y) -> (y / 100).toInt() * 6 + (x / 100).toInt() }
        val ok = PathReplay.isLegal(cells, false) && ShapeVerifier(case.goals).verify(PathReplay.replay(case.board, cells)).allSatisfied
        return Run(ok, ms, result.second, cells.size - 1, null, null)
    }

    private fun runShapeAware(case: Case): Run {
        val t0 = System.nanoTime()
        val result = BoardAnalyzer().solveShapeAware(case.board, case.goals,
            SearchBudget(maxSteps = steps, beamWidth = beamWidth, startMode = case.startMode, timeLimitMs = timeLimitMs))
        val ms = (System.nanoTime() - t0) / 1e6
        val best = result.best
        return Run(result.status == ShapeSolveStatus.SOLVED, ms, best?.deterministicCombos ?: 0, best?.stepCount ?: 0, result.nodes, result.timeToFirstShapeMs)
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    @Test fun compareBaselineAndShapeAware() {
        assumeTrue("設定 SHAPE_BENCHMARK=1 才執行", System.getenv("SHAPE_BENCHMARK") == "1")
        val cases = dataset()
        // 暖機，避免 JIT 影響第一個案例
        repeat(2) { runBaseline(cases.first()); runShapeAware(cases.first()) }
        val rows = cases.map { it to (runBaseline(it) to runShapeAware(it)) }
        fun summary(label: String, runs: List<Run>): String {
            val solved = runs.filter { it.solved }
            val ms = runs.map { it.ms }
            return "| $label | ${solved.size}/${runs.size} | ${"%.1f".format(percentile(ms, 0.5))} | ${"%.1f".format(percentile(ms, 0.95))} | " +
                "${if (solved.isEmpty()) "-" else "%.2f".format(solved.map { it.combos }.average())} | " +
                "${if (solved.isEmpty()) "-" else "%.1f".format(solved.map { it.steps }.average())} | " +
                "${runs.mapNotNull { it.firstShapeMs }.takeIf { it.isNotEmpty() }?.let { "%.1f".format(percentile(it.map(Long::toDouble), 0.5)) } ?: "-"} | " +
                "${runs.mapNotNull { it.nodes }.takeIf { it.isNotEmpty() }?.let { "%.0f".format(it.average()) } ?: "-"} |"
        }
        val report = buildString {
            appendLine("# Shape solver A/B benchmark")
            appendLine()
            appendLine("- 案例數 ${cases.size}（seed 20261118），steps=$steps、beamWidth=$beamWidth、4 向、Shape-Aware 時限 ${timeLimitMs}ms")
            appendLine("- JVM：${System.getProperty("java.version")}；單次執行、未隔離背景負載，僅供相對比較")
            appendLine()
            appendLine("| 求解器 | 形狀達標 | p50 ms | p95 ms | 平均 Combo（達標者） | 平均步數 | 首個形狀 p50 ms | 平均節點 |")
            appendLine("|---|---|---|---|---|---|---|---|")
            appendLine(summary("Beam baseline", rows.map { it.second.first }))
            appendLine(summary("Shape-Aware", rows.map { it.second.second }))
            appendLine()
            appendLine("| 案例 | Beam 達標 | Beam ms | Beam Combo | Shape-Aware 達標 | SA ms | SA Combo | SA 步數 |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for ((case, runs) in rows) {
                val (b, s) = runs
                appendLine("| ${case.name} | ${if (b.solved) "✓" else "✗"} | ${"%.1f".format(b.ms)} | ${b.combos} | " +
                    "${if (s.solved) "✓" else "✗"} | ${"%.1f".format(s.ms)} | ${s.combos} | ${s.steps} |")
            }
        }
        val out = File("build/reports/shape-benchmark.md")
        out.parentFile?.mkdirs()
        out.writeText(report)
        println(report)
    }
}
