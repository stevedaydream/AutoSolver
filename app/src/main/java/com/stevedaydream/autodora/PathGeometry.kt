package com.stevedaydream.autodora

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** 一段畫線（已套用視覺錯開）；raw 端點保留原始格心，僅供測試核對。 */
data class RenderSegment(
    val index: Int,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val rawX1: Float, val rawY1: Float, val rawX2: Float, val rawY2: Float,
    val lateralOffset: Float,
    val fraction: Float
)

/** 閉合三角形箭頭（tip 指向移動方向）。 */
data class RenderArrow(
    val segmentIndex: Int,
    val tipX: Float, val tipY: Float,
    val leftX: Float, val leftY: Float,
    val rightX: Float, val rightY: Float
)

/**
 * 一格的步序標籤。同一格經過多次時合併成「3,7」，不會只留第一次。
 * [steps] 為抵達此格的步號（遞增）；[prefixTexts] 預先算好「前 k 個步號」的文字，預演時逐步顯示、onDraw 不配置字串。
 */
data class RenderLabel(val text: String, val x: Float, val y: Float, val steps: List<Int> = emptyList(),
                       val prefixTexts: List<String> = listOf(text)) {
    /** 走完 [completedSteps] 步時應顯示的文字；尚未抵達回傳 null。 */
    fun textAt(completedSteps: Int): String? {
        val count = steps.count { it <= completedSteps }
        return if (count == 0) null else prefixTexts[count - 1]
    }
}

/**
 * Canvas 需要的全部幾何，於選路時一次算好並快取；onDraw 只讀不配置。
 * 只用於畫面，手勢座標一律由 [BoardGeometry.toGesturePoints] 從格位推導。
 */
data class PathRenderModel(
    val candidateId: String,
    val segments: List<RenderSegment>,
    val arrows: List<RenderArrow>,
    val startX: Float, val startY: Float,
    val endX: Float, val endY: Float,
    val stepLabels: List<RenderLabel>,
    val shapeCells: List<Pair<Int, BoardAnalyzer.OrbType?>>,
    val geometry: BoardGeometry,
    val lineWidth: Float,
    val markerRadius: Float
) {
    /** progress ∈ [0,1] 對應的手指位置（預演動畫用）。 */
    fun pointAt(progress: Float): Pair<Float, Float> {
        if (segments.isEmpty()) return startX to startY
        val total = progress.coerceIn(0f, 1f) * segments.size
        val index = total.toInt().coerceAtMost(segments.size - 1)
        val t = (total - index).coerceIn(0f, 1f)
        val s = segments[index]
        return (s.x1 + (s.x2 - s.x1) * t) to (s.y1 + (s.y2 - s.y1) * t)
    }

    /** progress 下已完整走過的段數。 */
    fun visibleSegments(progress: Float): Int =
        if (progress >= 1f) segments.size else (progress.coerceIn(0f, 1f) * segments.size).toInt()
}

object PathGeometryBuilder {
    /**
     * @param lateralPx 同一條格子邊多次經過時，每條線的橫向間距（只影響畫面）。
     * @param maxArrows 長路徑時的箭頭上限；超過時等距抽樣，最後一段一定有箭頭。
     */
    fun build(
        candidate: PathCandidate,
        geometry: BoardGeometry,
        lineWidth: Float = max(4f, geometry.orbSize * 0.09f),
        lateralPx: Float = geometry.orbSize * 0.1f,
        arrowSize: Float = geometry.orbSize * 0.3f,
        maxArrows: Int = 12
    ): PathRenderModel {
        val cells = candidate.pathCells
        val xs = FloatArray(cells.size) { geometry.centerX(cells[it]) }
        val ys = FloatArray(cells.size) { geometry.centerY(cells[it]) }
        val n = cells.size - 1

        // 以無向格子邊為 key（不用浮點），統計重複次數並把多條線均分在中心兩側
        val total = HashMap<Long, Int>()
        val edgeKeys = LongArray(max(n, 0)) { i ->
            val a = minOf(cells[i], cells[i + 1]).toLong(); val b = maxOf(cells[i], cells[i + 1]).toLong()
            (a shl 32) or b
        }
        for (key in edgeKeys) total[key] = (total[key] ?: 0) + 1
        val used = HashMap<Long, Int>()
        val segments = (0 until max(n, 0)).map { i ->
            val key = edgeKeys[i]
            val count = total[key] ?: 1
            val idx = used[key] ?: 0
            used[key] = idx + 1
            val offset = if (count > 1) (idx - (count - 1) / 2f) * lateralPx else 0f
            val dx = xs[i + 1] - xs[i]; val dy = ys[i + 1] - ys[i]
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.1f)
            val nx = -dy / len * offset; val ny = dx / len * offset
            RenderSegment(i, xs[i] + nx, ys[i] + ny, xs[i + 1] + nx, ys[i + 1] + ny,
                xs[i], ys[i], xs[i + 1], ys[i + 1], offset, if (n > 1) i.toFloat() / (n - 1) else 0f)
        }

        val stride = if (n <= maxArrows || maxArrows <= 0) 1 else ceil(n.toFloat() / maxArrows).toInt()
        val arrowIndices = (0 until n).filter { it % stride == 0 || it == n - 1 }.distinct()
        val arrows = arrowIndices.map { i ->
            val s = segments[i]
            val dx = s.x2 - s.x1; val dy = s.y2 - s.y1
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.1f)
            val ux = dx / len; val uy = dy / len
            val mx = (s.x1 + s.x2) / 2f; val my = (s.y1 + s.y2) / 2f
            val bx = mx - ux * arrowSize * 0.4f; val by = my - uy * arrowSize * 0.4f
            RenderArrow(i, mx + ux * arrowSize * 0.6f, my + uy * arrowSize * 0.6f,
                bx - uy * arrowSize * 0.5f, by + ux * arrowSize * 0.5f,
                bx + uy * arrowSize * 0.5f, by - ux * arrowSize * 0.5f)
        }

        // 步序標籤：第 1..n 步每一步都標；同一格多次經過合併成一個標籤（例如 3,7），避免互相覆蓋
        val byCell = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 1 until cells.size) byCell.getOrPut(cells[i]) { mutableListOf() }.add(i)
        val labels = byCell.map { (cell, steps) ->
            val prefixes = steps.indices.map { k -> steps.subList(0, k + 1).joinToString(",") }
            RenderLabel(prefixes.last(), geometry.centerX(cell) + geometry.orbSize * 0.22f,
                geometry.centerY(cell) - geometry.orbSize * 0.22f, steps, prefixes)
        }

        val shapeCells = candidate.shapeMatches.flatMap { match -> match.placement.cells.map { it to match.placement.orbType } }
        return PathRenderModel(candidate.id, segments, arrows, xs.first(), ys.first(), xs.last(), ys.last(),
            labels, shapeCells, geometry, lineWidth, max(lineWidth * 1.4f, geometry.orbSize * 0.16f))
    }
}
