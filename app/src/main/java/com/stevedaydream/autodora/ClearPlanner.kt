package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType
import java.util.ArrayDeque

/**
 * 「首批全消」的推珠規劃器：Beam 以 Combo 排序很難碰到需要長串精確推珠的解（例如起結有序盾 + 全消），
 * 這裡直接構造：選一條直線當目標排列 → 逐顆把目標珠推進指定格 → 最後把手上的珠帶到終點。
 *
 * 推珠原理：手指從 Q 走進 P 時，P 的珠會被換到 Q。要把珠從 P 推到相鄰的 Q，
 * 手指先繞路走到 Q（不經過 P 與已就位的格子），再踏進 P。
 *
 * 產出的路徑只是「候選」，呼叫端一律以 CandidateFactory 重播驗證全消、Combo 與起終點。
 */
internal object ClearPlanner {
    private const val ROWS = 5
    private const val COLS = 6

    /**
     * 一個推珠方案。[cells]：完整路徑（含收尾走到終點）；
     * [arranged]：目標珠全部就位、還沒收尾前的前綴（兩段式求解從這裡接續算 Combo）。
     */
    data class Plan(val cells: List<Int>, val arranged: List<Int>)

    /** 回傳數個可能的方案，由短到長；找不到回傳空清單。 */
    fun plan(
        board: Array<Array<OrbType>>,
        types: Set<OrbType>,
        constraint: MoveConstraint,
        maxSteps: Int,
        maxPlans: Int = 6
    ): List<Plan> {
        if (types.size != 1) return emptyList()           // 先處理單一屬性（多屬性交給 Beam）
        val type = types.first()
        val cells = (0 until ROWS * COLS).filter { board[it / COLS][it % COLS] == type }
        val k = cells.size
        if (k < 3 || k > 6) return emptyList()

        val starts = if (constraint.hasStart) listOf(constraint.startCell) else cells   // 未指定起點：拿一顆目標珠當手上珠
        val results = ArrayList<Plan>()
        for (start in starts) {
            val heldIsTarget = board[start / COLS][start % COLS] == type
            for (line in lines(k)) {
                // 手上珠是目標屬性：它最後停在終點（未指定終點時，終點可選直線上任一格）
                val ends: List<Int> = when {
                    constraint.hasEnd -> listOf(constraint.endCell)
                    heldIsTarget -> line
                    else -> listOf(-1)
                }
                for (end in ends) {
                    if (heldIsTarget && end !in line) continue
                    if (!heldIsTarget && end >= 0 && end in line) continue   // 終點會被換成手上的非目標珠
                    for (farthestFirst in listOf(false, true)) {
                        planOne(board, type, start, end, line, maxSteps, farthestFirst)?.let { results.add(it) }
                    }
                }
            }
            if (results.size >= maxPlans * 3) break
        }
        return results.distinctBy { it.cells }.sortedBy { it.cells.size }.take(maxPlans)
    }

    /** 長度 k 的所有橫線與直線（格位清單）。 */
    private fun lines(k: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        if (k <= COLS) for (r in 0 until ROWS) for (c in 0..COLS - k) out.add((0 until k).map { r * COLS + c + it })
        if (k <= ROWS) for (c in 0 until COLS) for (r in 0..ROWS - k) out.add((0 until k).map { (r + it) * COLS + c })
        return out
    }

    private class Sim(board: Array<Array<OrbType>>, start: Int) {
        val cells = Array(ROWS * COLS) { board[it / COLS][it % COLS] }
        var finger = start
        val path = arrayListOf(start)
        fun step(to: Int) {
            val t = cells[finger]; cells[finger] = cells[to]; cells[to] = t
            finger = to; path.add(to)
        }
    }

    private fun neighbors(cell: Int): List<Int> {
        val r = cell / COLS; val c = cell % COLS
        val out = ArrayList<Int>(4)
        if (r > 0) out.add(cell - COLS)
        if (r + 1 < ROWS) out.add(cell + COLS)
        if (c > 0) out.add(cell - 1)
        if (c + 1 < COLS) out.add(cell + 1)
        return out
    }

    /** 從 from 到 to 的最短格位路徑（不含 from），不經過 blocked；to 本身可被 blocked 以外。 */
    private fun bfs(from: Int, to: Int, blocked: Set<Int>): List<Int>? {
        if (from == to) return emptyList()
        val prev = IntArray(ROWS * COLS) { -2 }
        prev[from] = -1
        val queue = ArrayDeque<Int>().apply { add(from) }
        while (queue.isNotEmpty()) {
            val cur = queue.poll()
            for (n in neighbors(cur)) {
                if (prev[n] != -2 || (n in blocked && n != to)) continue
                prev[n] = cur
                if (n == to) {
                    val out = ArrayList<Int>()
                    var x = to
                    while (x != from) { out.add(x); x = prev[x] }
                    return out.reversed()
                }
                queue.add(n)
            }
        }
        return null
    }

    /**
     * 單一方案：手上珠從 start 出發，把其他目標珠推進 line，最後停在 end（-1 = 不限）。
     * 終點目前的珠（若也是目標屬性）在最後一步會被換到「手指進入終點前的那格」，因此那格保留給它。
     */
    private fun planOne(board: Array<Array<OrbType>>, type: OrbType, start: Int, end: Int, line: List<Int>, maxSteps: Int,
                        farthestFirst: Boolean): Plan? {
        val sim = Sim(board, start)
        val heldIsTarget = sim.cells[start] == type
        val endOccupantIsTarget = end >= 0 && end != start && sim.cells[end] == type
        // 最後一步從 approach 走進終點：終點原本的目標珠會落在 approach
        val approach: Int = if (end >= 0 && endOccupantIsTarget) {
            neighbors(end).firstOrNull { it in line } ?: return null
        } else -1
        if (endOccupantIsTarget && !heldIsTarget) return null   // 終點的目標珠會被換到 approach，但終點變成非目標珠

        val reserved = buildSet { if (end >= 0) add(end); if (approach >= 0) add(approach) }
        val slots = line.filter { it !in reserved }.toMutableList()
        val movers = (0 until ROWS * COLS).filter { it != start && it != end && sim.cells[it] == type }.toMutableList()
        if (!heldIsTarget && end < 0 && movers.size != slots.size) return null
        if (movers.size != slots.size) return null

        // 先處理離目標最遠的珠；目標格以貪婪最近指派（已就位的格子鎖住不再經過）
        val locked = HashSet<Int>()
        if (end >= 0) locked.add(end)        // 終點的珠要留到最後一步
        while (movers.isNotEmpty()) {
            // 兩種順序：最近的先就位（路徑短），或最遠的先就位（避免後面被已鎖格擋住）
            var bestMover = -1; var bestSlot = -1; var bestDist = Int.MAX_VALUE
            for (m in movers) for (s in slots) {
                val d = kotlin.math.abs(m / COLS - s / COLS) + kotlin.math.abs(m % COLS - s % COLS)
                val key = if (farthestFirst) -d else d
                if (key < bestDist) { bestDist = key; bestMover = m; bestSlot = s }
            }
            var pos = bestMover
            if (pos != bestSlot) {
                val route = bfs(pos, bestSlot, locked + sim.finger) ?: return null
                for (next in route) {
                    // 手指繞到 next（不碰 pos 與已鎖格），再踏進 pos，把珠推到 next
                    val walk = bfs(sim.finger, next, locked + pos) ?: return null
                    for (c in walk) sim.step(c)
                    sim.step(pos)
                    pos = next
                    if (sim.path.size - 1 > maxSteps) return null
                }
            }
            if (sim.cells[bestSlot] != type) return null
            locked.add(bestSlot)
            slots.remove(bestSlot)
            movers.remove(bestMover)
            // 推珠過程可能把其他目標珠挪了位置：重新取得還沒就位的目標珠
            movers.clear()
            movers.addAll((0 until ROWS * COLS).filter {
                it != sim.finger && it !in locked && it != approach && sim.cells[it] == type
            })
            if (movers.size != slots.size) return null
        }
        val arranged = ArrayList(sim.path)
        // 收尾：手指走到 approach（或直接到終點），最後踏進終點
        if (end >= 0 && sim.finger != end) {
            val target = if (approach >= 0) approach else end
            val walk = bfs(sim.finger, target, locked - end + (if (approach >= 0) setOf(end) else emptySet())) ?: return null
            for (c in walk) sim.step(c)
            if (approach >= 0) sim.step(end)
        }
        if (sim.path.size < 2 || sim.path.size - 1 > maxSteps) return null
        return Plan(sim.path, arranged)
    }
}
