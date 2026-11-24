package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType
import com.stevedaydream.autodora.BoardAnalyzer.SearchNode

/** 解盤器切換；預設仍是原本的 Beam，Shape-Aware 只在使用者選擇且有版型條件時啟用。 */
enum class SolverMode(val label: String) { BEAM("Beam（原版）"), SHAPE_AWARE("Shape-Aware（形狀優先）") }

enum class ShapeSolveStatus(val label: String) {
    SOLVED("形狀全部達成"),
    INFEASIBLE("條件不可行"),
    TIMEOUT("搜尋逾時，未達成形狀"),
    GOALS_UNSATISFIED("找不到達成全部形狀的路徑"),
    CANCELLED("已取消")
}

/** 求解預算：步數／廣度／時間／節點都有硬上限，DFS 精修共用同一份預算。 */
data class SearchBudget(
    val maxSteps: Int = 35,
    val beamWidth: Int = 200,
    val is8Way: Boolean = false,
    val startMode: Int = 0,
    val timeLimitMs: Long = 3000L,
    val maxNodes: Long = 3_000_000L,
    val refineTopK: Int = 5,
    val refineDepth: Int = 4,
    val maxCandidates: Int = 5,
    val deduplicate: Boolean = true,
    /** 起點／終點格限制（-1 不限）；起點優先於 startMode。 */
    val constraint: MoveConstraint = MoveConstraint.NONE,
    /** 首批全消的屬性（空集合 = 不限）；達成後以 Combo 最高為優先。 */
    val clearTypes: Set<BoardAnalyzer.OrbType> = emptySet()
)

/** clearLeft／clearCost：首批全消的剩餘顆數與引導成本（未設定條件時為 0）。 */
data class BoardScore(val combos: Int, val firstWaveCombos: Int, val score: Int, val clearLeft: Int = 0, val clearCost: Int = 0)

data class ShapeSolveResult(
    val status: ShapeSolveStatus,
    /** 只有 SOLVED 時非空；每條都通過 ShapeVerifier 與路徑回放驗證。 */
    val candidates: List<PathCandidate>,
    val bestSatisfiedGoals: Int,
    val requiredGoals: Int,
    val elapsedMs: Long,
    val nodes: Long,
    val timeToFirstShapeMs: Long?,
    val message: String
) {
    val best: PathCandidate? get() = candidates.firstOrNull()
    /** 手指經過的格位序列；只有 SOLVED 可供執行。 */
    val path: List<Int> get() = if (status == ShapeSolveStatus.SOLVED) best?.pathCells ?: emptyList() else emptyList()
}

/**
 * 形狀優先的混合求解：Beam（目標完成數 → 潛力 → 分數，含精確狀態去重）產生多樣起手，
 * 再對 Top-K 種子做有界 DFS 精修。所有回傳解都以 ShapeVerifier 在放手前盤面驗證。
 * 找不到時明確回報狀態，絕不以高 Combo 路徑冒充達標。
 */
internal class ShapeAwareSolver(
    private val goals: List<ShapeGoal>,
    private val scorer: (Array<Array<OrbType>>) -> BoardScore,
    private val clock: () -> Long = System::nanoTime,
    private val cancelled: () -> Boolean = { false }
) {
    private val rows = 5
    private val cols = 6
    private val evaluator = ShapeEvaluator(goals, rows, cols)
    private val verifier = evaluator.verifier

    private var startNanos = 0L
    private var deadline = 0L
    private var nodes = 0L
    private var maxNodes = 0L
    private var timedOut = false
    private var wasCancelled = false
    private var firstShapeNanos: Long? = null
    private var bestSatisfied = 0

    private fun outOfBudget(): Boolean {
        if (timedOut || wasCancelled) return true
        if ((nodes and 127L) == 0L) {
            if (cancelled()) wasCancelled = true
            else if (clock() > deadline || nodes >= maxNodes) timedOut = true
        }
        return timedOut || wasCancelled
    }

    /** 已達成全部形狀的解；依結果盤面去重，排序：Combo → 分數 → 較短。 */
    private inner class SolvedPool(private val capacity: Int, private val maxSteps: Int) {
        private val entries = HashMap<StateKey, SearchNode>()
        private val order = Comparator<SearchNode> { a, b ->
            when {
                a.combos != b.combos -> b.combos.compareTo(a.combos)
                a.score != b.score -> b.score.compareTo(a.score)
                else -> a.pathLen.compareTo(b.pathLen)
            }
        }
        fun offer(board: Array<Array<OrbType>>, pathR: IntArray, pathC: IntArray, pathLen: Int, combos: Int, score: Int) {
            if (firstShapeNanos == null) firstShapeNanos = clock() - startNanos
            val key = StateKey.of(board)
            val existing = entries[key]
            if (existing != null && (existing.combos > combos || (existing.combos == combos &&
                    (existing.score > score || (existing.score == score && existing.pathLen <= pathLen))))) return
            val node = SearchNode(maxSteps)
            node.copyBoard(board)
            System.arraycopy(pathR, 0, node.pathR, 0, pathLen)
            System.arraycopy(pathC, 0, node.pathC, 0, pathLen)
            node.pathLen = pathLen; node.combos = combos; node.score = score
            entries[key] = node
            if (entries.size > capacity) {
                val worst = entries.entries.maxWithOrNull { x, y -> order.compare(x.value, y.value) }!!
                entries.remove(worst.key)
            }
        }
        fun sorted(): List<SearchNode> = entries.values.sortedWith(order)
        val isEmpty get() = entries.isEmpty()
    }

    /** 補走會把手上的珠子帶走；手指若停在已完成的形狀上，補走必定破壞形狀，不值得保留。 */
    private fun fingerOutsideShapes(board: Array<Array<OrbType>>, finger: Int): Boolean =
        verifier.verify(board).usedMask and (1L shl finger) == 0L

    fun solve(board: Array<Array<OrbType>>, budget: SearchBudget): ShapeSolveResult {
        startNanos = clock()
        deadline = startNanos + budget.timeLimitMs.coerceAtLeast(1L) * 1_000_000L
        nodes = 0L; maxNodes = budget.maxNodes.coerceAtLeast(1L)
        timedOut = false; wasCancelled = false; firstShapeNanos = null; bestSatisfied = 0

        fun result(status: ShapeSolveStatus, candidates: List<PathCandidate> = emptyList(), message: String = status.label) =
            ShapeSolveResult(status, candidates, if (status == ShapeSolveStatus.SOLVED) goals.size else bestSatisfied,
                goals.size, (clock() - startNanos) / 1_000_000L, nodes, firstShapeNanos?.let { it / 1_000_000L }, message)

        if (goals.isEmpty()) return result(ShapeSolveStatus.INFEASIBLE, message = "沒有指定版型條件")
        if (board.size != rows || board.any { it.size != cols || it.any { orb -> orb == OrbType.UNKNOWN } })
            return result(ShapeSolveStatus.INFEASIBLE, message = "盤面仍有未辨識符石")
        verifier.infeasibleReason(board)?.let { return result(ShapeSolveStatus.INFEASIBLE, message = it) }
        ClearGoal.infeasibleReason(board, budget.clearTypes)?.let { return result(ShapeSolveStatus.INFEASIBLE, message = it) }

        val maxSteps = budget.maxSteps.coerceIn(5, 60)
        val beam = budget.beamWidth.coerceIn(30, 500)
        val dirCount = PathReplay.directionCount(budget.is8Way)
        val solved = SolvedPool((budget.maxCandidates * 6).coerceAtLeast(4), maxSteps)

        val nextNodes = Array(beam * dirCount) { SearchNode(maxSteps) }
        val currentNodes = Array(beam) { SearchNode(maxSteps) }
        val guide = Comparator<SearchNode> { left, right ->
            when {
                left.shapes.satisfied != right.shapes.satisfied -> left.shapes.satisfied.compareTo(right.shapes.satisfied)
                left.shapes.satisfied < goals.size && left.shapes.potential != right.shapes.potential ->
                    left.shapes.potential.compareTo(right.shapes.potential)
                else -> (left.score.toLong() - left.clearCost.toLong() * ClearGoal.guideWeight)
                    .compareTo(right.score.toLong() - right.clearCost.toLong() * ClearGoal.guideWeight)
            }
        }
        val selector = BeamSelector(beam, { a, b -> guide.compare(nextNodes[a], nextNodes[b]) }) { nextNodes[it].score }
        val seeds = ArrayList<SearchNode>()
        fun offerSeed(node: SearchNode) {
            if (budget.refineTopK <= 0) return
            if (seeds.size >= budget.refineTopK && guide.compare(node, seeds.last()) <= 0) return
            val key = StateKey.of(node.board)
            if (seeds.any { StateKey.of(it.board) == key }) return
            seeds.add(SearchNode(maxSteps).also { it.copyFrom(node) })
            seeds.sortWith(guide.reversed())
            if (seeds.size > budget.refineTopK) seeds.removeAt(seeds.size - 1)
        }

        val constraint = budget.constraint
        val starts = constraint.startCells(budget.startMode)
        // 指定終點：形狀已達成但停在別處的路線，最後補走到終點再驗證一次
        val nearMiss = if (constraint.hasEnd) SolvedPool(16, maxSteps) else null
        val initial = scorer(board)
        val initialShapes = evaluator.evaluate(board)
        var currentCount = 0
        for (cell in starts) {
            if (currentCount >= beam) break
            val n = currentNodes[currentCount++]
            n.copyBoard(board)
            n.r = cell / cols; n.c = cell % cols
            n.pathLen = 1; n.pathR[0] = n.r; n.pathC[0] = n.c
            n.combos = initial.combos; n.score = initial.score; n.shapes = initialShapes
            n.clearLeft = initial.clearLeft; n.clearCost = initial.clearCost
        }

        val seen = HashSet<StateKey>()
        search@ for (step in 0 until maxSteps) {
            var nextCount = 0
            seen.clear()
            for (i in 0 until currentCount) {
                val curr = currentNodes[i]
                val prevCell = if (curr.pathLen >= 2) curr.pathR[curr.pathLen - 2] * cols + curr.pathC[curr.pathLen - 2] else -1
                for (d in 0 until dirCount) {
                    val nr = curr.r + PathReplay.DIR_R[d]; val nc = curr.c + PathReplay.DIR_C[d]
                    if (nr !in 0 until rows || nc !in 0 until cols) continue
                    if (nr * cols + nc == prevCell) continue
                    nodes++
                    if (outOfBudget()) break@search
                    val next = nextNodes[nextCount]
                    next.copyFrom(curr)
                    val temp = next.board[curr.r][curr.c]
                    next.board[curr.r][curr.c] = next.board[nr][nc]
                    next.board[nr][nc] = temp
                    next.r = nr; next.c = nc
                    next.pathR[next.pathLen] = nr; next.pathC[next.pathLen] = nc
                    next.pathLen++
                    if (budget.deduplicate && !seen.add(StateKey.of(next.board, nr * cols + nc, curr.r * cols + curr.c))) continue
                    val scored = scorer(next.board)
                    next.combos = scored.combos
                    next.clearLeft = scored.clearLeft; next.clearCost = scored.clearCost
                    next.score = scored.score - next.pathLen * 15
                    next.shapes = evaluator.evaluate(next.board)
                    if (next.shapes.satisfied > bestSatisfied) bestSatisfied = next.shapes.satisfied
                    if (next.shapes.satisfied == goals.size && next.combos > 0 && next.clearLeft == 0) {
                        if (constraint.endOk(nr * cols + nc)) solved.offer(next.board, next.pathR, next.pathC, next.pathLen, next.combos, next.score)
                        else if (nearMiss != null && fingerOutsideShapes(next.board, nr * cols + nc))
                            nearMiss.offer(next.board, next.pathR, next.pathC, next.pathLen, next.combos, next.score)
                    }
                    nextCount++
                }
            }
            if (nextCount == 0) break
            currentCount = 0
            for (index in selector.select(nextCount)) currentNodes[currentCount++].copyFrom(nextNodes[index])
            for (i in 0 until minOf(currentCount, budget.refineTopK)) offerSeed(currentNodes[i])
        }

        // ── 有界 DFS 精修：在種子尾端再延伸少量步數，尋找達標或 Combo 更高的解 ──
        val work = Array(rows) { Array(cols) { OrbType.UNKNOWN } }
        val pathR = IntArray(maxSteps + 2)
        val pathC = IntArray(maxSteps + 2)
        fun dfs(r: Int, c: Int, len: Int, depth: Int) {
            if (depth >= budget.refineDepth || len - 1 >= maxSteps) return
            val prevCell = if (len >= 2) pathR[len - 2] * cols + pathC[len - 2] else -1
            for (d in 0 until dirCount) {
                val nr = r + PathReplay.DIR_R[d]; val nc = c + PathReplay.DIR_C[d]
                if (nr !in 0 until rows || nc !in 0 until cols || nr * cols + nc == prevCell) continue
                nodes++
                if (outOfBudget()) return
                val temp = work[r][c]; work[r][c] = work[nr][nc]; work[nr][nc] = temp
                pathR[len] = nr; pathC[len] = nc
                val satisfied = verifier.verify(work).satisfied
                if (satisfied > bestSatisfied) bestSatisfied = satisfied
                if (satisfied == goals.size) {
                    val scored = scorer(work)
                    if (scored.combos > 0 && scored.clearLeft == 0) {
                        if (constraint.endOk(nr * cols + nc)) solved.offer(work, pathR, pathC, len + 1, scored.combos, scored.score - (len + 1) * 15)
                        else if (nearMiss != null && fingerOutsideShapes(work, nr * cols + nc))
                            nearMiss.offer(work, pathR, pathC, len + 1, scored.combos, scored.score - (len + 1) * 15)
                    }
                }
                dfs(nr, nc, len + 1, depth + 1)
                work[nr][nc] = work[r][c]; work[r][c] = temp
                if (outOfBudget()) return
            }
        }
        // 先從每個合法起手點做淺層窮舉：Beam 的潛力分數可能淘汰「先變差再完成」的關鍵一步
        if (budget.refineDepth > 0) for (cell in starts) {
            if (outOfBudget()) break
            for (r in 0 until rows) for (c in 0 until cols) work[r][c] = board[r][c]
            pathR[0] = cell / cols; pathC[0] = cell % cols
            dfs(cell / cols, cell % cols, 1, 0)
        }
        for (seed in seeds) {
            if (outOfBudget()) break
            for (r in 0 until rows) for (c in 0 until cols) work[r][c] = seed.board[r][c]
            System.arraycopy(seed.pathR, 0, pathR, 0, seed.pathLen)
            System.arraycopy(seed.pathC, 0, pathC, 0, seed.pathLen)
            dfs(seed.r, seed.c, seed.pathLen, 0)
        }

        // ── 指定終點：補走到終點後重新驗證形狀與 Combo ──
        if (nearMiss != null) for (node in nearMiss.sorted()) {
            if (outOfBudget()) break
            val cells = (0 until node.pathLen).map { node.pathR[it] * cols + node.pathC[it] }
            val walk = PathReplay.walkTo(cells.last(), cells.getOrElse(cells.size - 2) { -1 }, constraint.endCell,
                budget.is8Way, maxSteps - (cells.size - 1)) ?: continue
            val full = cells + walk
            val replayed = PathReplay.replay(board, full)
            if (!verifier.verify(replayed).allSatisfied) continue
            val scored = scorer(replayed)
            if (scored.combos <= 0 || scored.clearLeft != 0) continue
            val fr = IntArray(full.size) { full[it] / cols }; val fc = IntArray(full.size) { full[it] % cols }
            solved.offer(replayed, fr, fc, full.size, scored.combos, scored.score - full.size * 15)
        }

        // ── 最終驗證：重播格位路徑、檢查 4/8 向、放手前形狀、再以 BoardSimulator 核算 Combo ──
        val simulator = BoardSimulator(rows, cols)
        val verified = solved.sorted().mapNotNull { node ->
            val cells = (0 until node.pathLen).map { node.pathR[it] * cols + node.pathC[it] }
            CandidateFactory.build(board, cells, budget.is8Way, verifier, CandidateSource.SHAPE_AWARE, node.score.toLong(),
                simulator, budget.clearTypes)
        }.filter { it.executable && it.shapeVerified && constraint.endOk(it.pathCells.last()) }
        val candidates = CandidateFactory.dedupe(verified).take(budget.maxCandidates.coerceAtLeast(1))

        return when {
            candidates.isNotEmpty() -> result(ShapeSolveStatus.SOLVED, candidates,
                if (timedOut) "形狀全部達成（預算用盡，回傳目前最佳）" else ShapeSolveStatus.SOLVED.label)
            wasCancelled -> result(ShapeSolveStatus.CANCELLED)
            timedOut -> result(ShapeSolveStatus.TIMEOUT, message = "逾時：最多達成 $bestSatisfied/${goals.size}")
            else -> result(ShapeSolveStatus.GOALS_UNSATISFIED, message = "最多達成 $bestSatisfied/${goals.size}")
        }
    }
}
