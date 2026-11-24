package com.stevedaydream.autodora

import com.stevedaydream.autodora.BoardAnalyzer.OrbType

/** 盤面校正：格位 → 實際手勢螢幕座標。與既有 calculateOptimalPath 的換算完全相同。 */
data class BoardGeometry(val startX: Float, val startY: Float, val orbSize: Float, val rows: Int = 5, val cols: Int = 6) {
    fun centerX(cell: Int) = startX + (cell % cols) * orbSize + orbSize / 2f
    fun centerY(cell: Int) = startY + (cell / cols) * orbSize + orbSize * 5f / 8f
    fun toGesturePoints(cells: List<Int>): List<Pair<Float, Float>> = cells.map { centerX(it) to centerY(it) }

    /** 像素 → 格位；超出盤面回傳 -1。 */
    fun cellAt(x: Float, y: Float): Int {
        val c = kotlin.math.floor((x - startX) / orbSize).toInt()
        val r = kotlin.math.floor((y - startY) / orbSize).toInt()
        return if (r in 0 until rows && c in 0 until cols) r * cols + c else -1
    }

    /** 校正鍵：任一數值改變（重新校正）都讓既有預覽失效。 */
    val calibrationKey: String get() = "${startX.toInt()}:${startY.toInt()}:${orbSize.toInt()}"
}

enum class CandidateSource(val label: String) { BEAM("Beam"), SHAPE_AWARE("Shape-Aware"), ML("ML"), ML_FALLBACK("Beam（ML 未就緒）") }

/**
 * 求解結果的 domain model，與 Canvas／Android View 分離。
 * pathCells 是單一可信來源：預覽畫線與真實手勢都由它推導，畫面上的錯線偏移不會回寫這裡。
 */
data class PathCandidate(
    val id: String,
    val startCell: Int,
    val directions: List<Int>,
    val pathCells: List<Int>,
    val firstWaveCombos: Int,
    val deterministicCombos: Int,
    val stepCount: Int,
    val score: Long,
    val shapeRequired: Int,
    val shapeMatches: List<ShapeMatch>,
    val shapeVerified: Boolean,
    val boardFingerprint: String,
    val resultFingerprint: String,
    val is8Way: Boolean,
    val source: CandidateSource,
    /** 首批全消條件與未消除的剩餘顆數（0 = 達成）。 */
    val clearTypes: Set<OrbType> = emptySet(),
    val clearRemaining: Int = 0
) {
    val clearVerified: Boolean get() = clearTypes.isEmpty() || clearRemaining == 0
    val shapeSatisfied: Int get() = shapeMatches.size
    /** 只有合法、有 Combo、且所有硬性形狀條件都經精確驗證的候選可以交付手勢。 */
    val executable: Boolean get() = pathCells.size >= 2 && deterministicCombos > 0 &&
        (shapeRequired == 0 || shapeVerified) && clearVerified
}

internal object CandidateFactory {
    /**
     * 依格位路徑重播並重新計算所有指標；路徑不合法回傳 null。
     * 形狀在「放手前」盤面上驗證，Combo 由 BoardSimulator 另算，兩者分開記錄。
     */
    fun build(
        board: Array<Array<OrbType>>,
        cells: List<Int>,
        is8Way: Boolean,
        verifier: ShapeVerifier?,
        source: CandidateSource,
        score: Long = 0L,
        simulator: BoardSimulator = BoardSimulator(5, 6),
        clearTypes: Set<OrbType> = emptySet()
    ): PathCandidate? {
        if (!PathReplay.isLegal(cells, is8Way)) return null
        if (board.any { row -> row.any { it == OrbType.UNKNOWN } }) return null
        val replayed = PathReplay.replay(board, cells)
        val verification = verifier?.verify(replayed)
        val combos = simulator.resolve(replayed)
        val clearRemaining = ClearGoal.remaining(replayed, simulator.firstWaveMatch, clearTypes)
        val fingerprint = PathReplay.fingerprint(board)
        return PathCandidate(
            id = "${source.name}-${fingerprint.hashCode().toUInt().toString(16)}-${cells.joinToString(".")}",
            startCell = cells.first(),
            directions = PathReplay.directionsOf(cells),
            pathCells = cells,
            firstWaveCombos = simulator.firstWaveCombos,
            deterministicCombos = combos,
            stepCount = cells.size - 1,
            score = score,
            shapeRequired = verifier?.goals?.size ?: 0,
            shapeMatches = verification?.matches ?: emptyList(),
            shapeVerified = verification?.allSatisfied ?: true,
            boardFingerprint = fingerprint,
            resultFingerprint = PathReplay.fingerprint(replayed),
            is8Way = is8Way,
            source = source,
            clearTypes = clearTypes,
            clearRemaining = clearRemaining
        )
    }

    /** 舊 API adapter：`Pair<像素路徑, combo>` → 單一候選。 */
    fun fromPixelPath(
        board: Array<Array<OrbType>>,
        pixelPath: List<Pair<Float, Float>>,
        geometry: BoardGeometry,
        is8Way: Boolean,
        verifier: ShapeVerifier?,
        source: CandidateSource
    ): PathCandidate? {
        if (pixelPath.size < 2) return null
        val cells = pixelPath.map { (x, y) -> geometry.cellAt(x, y) }
        if (cells.any { it < 0 }) return null
        return build(board, cells, is8Way, verifier, source)
    }

    /** 去重：同一條格位路徑，或放手前結果盤面相同者，只保留排序較前的一條。 */
    fun dedupe(candidates: List<PathCandidate>): List<PathCandidate> {
        val paths = HashSet<List<Int>>()
        val results = HashSet<String>()
        return candidates.filter { paths.add(it.pathCells) && results.add(it.resultFingerprint) }
    }
}
