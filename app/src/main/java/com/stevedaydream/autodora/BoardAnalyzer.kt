// --- 修改開頭：整合為統一的樣板比對引擎，清理舊有混雜的辨識演算法 ---
package com.stevedaydream.autodora

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

class BoardAnalyzer {

    enum class OrbType   { WATER, FIRE, WOOD, LIGHT, DARK, HEART, UNKNOWN }
    enum class OrbStatus { NONE, BURN, SHIELD, POISON, STICKY, FREEZE, WEATHER }

    /** 每格 HSV 特徵向量，供 RecognitionOverlayWindow 修正時寫入資料庫 */
    data class CellFeatures(
        val d290: Int = 0, val d280: Int = 0, val d300: Int = 0,
        val w130: Int = 0, val l50: Int  = 0, val wt210: Int = 0,
        val h310: Int = 0, val h320: Int = 0, val f020: Int = 0,
        val validPx: Int = 0
    )

    companion object {
        /** 指定終點時，每離終點 1 格在最後一步的扣分（約 1 個 Combo）；前段按已用步數比例遞減。 */
        internal const val END_PULL = 50_000L
        /** 兩段式全消求解：取最短的幾個推珠方案接續 Beam（每個方案多跑一次 Beam）。 */
        internal const val PHASE2_PLANS = 3
        // 存放六種屬性的標準樣板 (16x16 縮圖)，全域共用
        val templates = mutableMapOf<OrbType, Bitmap>()
    }

    /** analyzeBoard 後可讀取，供 RecognitionOverlayWindow 使用 */
    val lastFeatures = Array(5) { Array(6) { CellFeatures() } }
    val lastAnnotations = Array(5) { Array(6) { OrbAnnotation(OrbType.UNKNOWN, uncertain = true) } }

    private val simulator = BoardSimulator(5, 6)
    private val matchBoard get() = simulator.firstWaveMatch
    private val visited = Array(5) { BooleanArray(6) }
    private val queueR = IntArray(30)
    private val queueC = IntArray(30)
    private val firstWaveCombos get() = simulator.firstWaveCombos

    class SearchNode(maxSteps: Int) {
        val board = Array(5) { Array(6) { OrbType.UNKNOWN } }
        val pathR = IntArray(maxSteps + 2)
        val pathC = IntArray(maxSteps + 2)
        var pathLen = 0
        var r = 0; var c = 0
        var score = 0; var combos = 0
        internal var shapes = ShapeEvaluation(0, 0)
        /** 首批全消：剩餘顆數與引導成本（越小越好）。 */
        internal var clearLeft = 0
        internal var clearCost = 0

        fun copyBoard(source: Array<Array<OrbType>>) {
            for (i in 0 until 5) for (j in 0 until 6) this.board[i][j] = source[i][j]
        }

        fun copyFrom(other: SearchNode) {
            copyBoard(other.board)
            System.arraycopy(other.pathR, 0, this.pathR, 0, other.pathLen)
            System.arraycopy(other.pathC, 0, this.pathC, 0, other.pathLen)
            this.pathLen = other.pathLen
            this.r = other.r; this.c = other.c
            this.score = other.score; this.combos = other.combos
            this.shapes = other.shapes
            this.clearLeft = other.clearLeft; this.clearCost = other.clearCost
        }
    }

    // 唯一的辨識入口點，FloatingWindow 會直接呼叫這裡
    // 唯一的辨識入口點
    // ★ analyzeBoard：不再依賴樣板，改用 HSV 直接分類
    fun analyzeBoard(screenshot: Bitmap, startX: Int, startY: Int, orbSize: Int): Array<Array<OrbType>> {
        val board = Array(5) { Array(6) { OrbType.UNKNOWN } }

        // 取中心 65% 區域，避開邊緣光影與相鄰珠子干擾
        val cropSize = (orbSize * 0.65).toInt()
        val offset = (orbSize - cropSize) / 2

        for (row in 0 until 5) {
            for (col in 0 until 6) {
                val x = startX + col * orbSize + offset
                val y = startY + row * orbSize + offset

                if (x >= 0 && y >= 0 && x + cropSize <= screenshot.width && y + cropSize <= screenshot.height) {
                    val crop = Bitmap.createBitmap(screenshot, x, y, cropSize, cropSize)
                    try {
                        var result = classifyByHSV(crop, row, col)
                        // HSV 失敗時，用樣板備援
                        if (result == OrbType.UNKNOWN && templates.size == 6) {
                            val scaled = Bitmap.createScaledBitmap(crop, 32, 32, true)
                            try {
                                result = matchTemplate(scaled)
                            } finally {
                                scaled.recycle()
                            }
                        }
                        val fullX = startX + col * orbSize
                        val fullY = startY + row * orbSize
                        val annotation = if (RecognitionLearningEngine.instance != null && fullX >= 0 && fullY >= 0 &&
                            fullX + orbSize <= screenshot.width && fullY + orbSize <= screenshot.height) {
                            val fullTile = Bitmap.createBitmap(screenshot, fullX, fullY, orbSize, orbSize)
                            try { RecognitionLearningEngine.instance?.match(fullTile) } finally { fullTile.recycle() }
                        } else null
                        board[row][col] = annotation?.type ?: result
                        lastAnnotations[row][col] = annotation ?: OrbAnnotation(result, uncertain = result == OrbType.UNKNOWN)
                    } finally {
                        crop.recycle()
                    }
                }
            }
        }
        RecognitionLearningEngine.instance?.offer(screenshot, startX, startY, orbSize, board)
        return board
    }

    // ★ 核心：純 HSV 色相分類，不需要任何樣板
    private fun classifyByHSV(bitmap: Bitmap, row: Int = -1, col: Int = -1): OrbType {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 只取中心圓形區域的像素，進一步排除邊緣干擾
        val cx = width / 2f
        val cy = height / 2f
        val radius = width / 2f * 0.75f  // 只取 75% 半徑內的像素
        

        var validPixels = 0
        val hueBins = IntArray(36)

        for (py in 0 until height) {
            for (px in 0 until width) {
                // ★ 排除右上角圖示干擾區域（約佔格子 1/16）
                if (px >= width * 0.75f && py < height * 0.25f) continue

                val dx = px - cx; val dy = py - cy
                if (dx * dx + dy * dy > radius * radius) continue

                val pixel = pixels[py * width + px]
                val r = Color.red(pixel) / 255f
                val g = Color.green(pixel) / 255f
                val b = Color.blue(pixel) / 255f
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val delta = max - min

                if (max < 0.20f || delta < 0.15f) continue

                val saturation = delta / max
                val hue = hueOf(r, g, b, max, delta)

                val weight = (saturation * 2f).coerceIn(1f, 3f).toInt()
                validPixels += weight
                hueBins[(hue / 10f).toInt().coerceIn(0, 35)] += weight
            }
        }

        if (validPixels < 10) return OrbType.UNKNOWN

        // ★ 從實測資料歸納的特徵 bin，依優先順序判斷
        val dark290 = hueBins[29]; val dark280 = hueBins[28]; val dark300 = hueBins[30]
        val wood130 = hueBins[13]
        val light50  = hueBins[5]
        val water210 = hueBins[21]
        val heart310 = hueBins[31]; val heart320 = hueBins[32]
        val fire020  = hueBins[0] + hueBins[1] + hueBins[2]

        // 記錄特徵向量（供 RecognitionOverlayWindow 點格修正時使用）
        if (row in 0 until 5 && col in 0 until 6)
            lastFeatures[row][col] = CellFeatures(dark290, dark280, dark300,
                wood130, light50, water210, heart310, heart320, fire020, validPixels)

        // ── 步驟 1a：D 類（特效珠）資料庫最優先 ───────────────
        // 特效覆蓋（燃燒/冰凍/腐化等）會大幅改變 HSV，需最先辨識
        val dResult = RecognitionDatabase.instance?.classify(
            dark290, dark280, dark300, wood130, light50,
            water210, heart310, heart320, fire020, validPixels, 'D'
        )
        if (dResult != null) {
            Log.d("AutoPuzzler", "[$row,$col] => $dResult (D-DB)  [d290=$dark290 d280=$dark280 d300=$dark300 w130=$wood130 l50=$light50 wt210=$water210 h310=$heart310 h320=$heart320 f020=$fire020]  validPx=$validPixels")
            return dResult
        }

        // ── 步驟 1b：A 類（高隱寫）資料庫優先 ─────────────────
        // 可覆蓋規則層的誤判（如暗誤判為火、心誤判為火/水）
        val aResult = RecognitionDatabase.instance?.classify(
            dark290, dark280, dark300, wood130, light50,
            water210, heart310, heart320, fire020, validPixels, 'A'
        )
        if (aResult != null) {
            Log.d("AutoPuzzler", "[$row,$col] => $aResult (A-DB)  [d290=$dark290 d280=$dark280 d300=$dark300 w130=$wood130 l50=$light50 wt210=$water210 h310=$heart310 h320=$heart320 f020=$fire020]  validPx=$validPixels")
            return aResult
        }

        // ── 步驟 2：B 類規則（一般寫）────────────────────────
        val result = when {
            // 暗：290° 固定 336-391，且 280°≈290°>300°（小量三重訊號）
            dark290 > 280 && dark280 in 100..300 && dark300 > 100 -> OrbType.DARK
            // 木：130° 固定 848-914
            wood130 > 500 -> OrbType.WOOD
            // 光：50° 固定 441-466
            light50 > 300 -> OrbType.LIGHT
            // 水：210° 固定 505-534
            water210 > 450 -> OrbType.WATER
            // 心：310° 與 320° 同時顯著
            heart310 > 180 && heart320 > 250 -> OrbType.HEART
            // 火：0°+10°+20° 合計高
            fire020 > 800 -> OrbType.FIRE
            else -> OrbType.UNKNOWN
        }

        // ── 步驟 3：規則失敗時查 B 類資料庫 ──────────────────
        if (result == OrbType.UNKNOWN) {
            val bResult = RecognitionDatabase.instance?.classify(
                dark290, dark280, dark300, wood130, light50,
                water210, heart310, heart320, fire020, validPixels, 'B'
            )
            if (bResult != null) {
                Log.d("AutoPuzzler", "[$row,$col] => $bResult (B-DB)  [d290=$dark290 d280=$dark280 d300=$dark300 w130=$wood130 l50=$light50 wt210=$water210 h310=$heart310 h320=$heart320 f020=$fire020]  validPx=$validPixels")
                return bResult
            }
        }

        Log.d("AutoPuzzler", "[$row,$col] => $result  [d290=$dark290 d280=$dark280 d300=$dark300 w130=$wood130 l50=$light50 wt210=$water210 h310=$heart310 h320=$heart320 f020=$fire020]  validPx=$validPixels")
        return result
    }

    // 核心圖形比對 (SAD 演算法)
    // ★ 新版 matchTemplate：HSV 色相直方圖 + SAD 加權融合
    private fun matchTemplate(target: Bitmap): OrbType {
        val targetHist = buildHueHistogram(target)
        val targetPixels = IntArray(32 * 32).also { target.getPixels(it, 0, 32, 0, 0, 32, 32) }

        var bestType = OrbType.UNKNOWN
        var bestScore = Double.MAX_VALUE
        var secondScore = Double.MAX_VALUE

        for ((type, template) in templates) {
            val templatePixels = IntArray(32 * 32).also { template.getPixels(it, 0, 32, 0, 0, 32, 32) }
            val templateHist = buildHueHistogram(template)

            // SAD（正規化至 0~1）
            var sadSum = 0L
            for (i in targetPixels.indices) {
                val c1 = targetPixels[i]; val c2 = templatePixels[i]
                sadSum += Math.abs(Color.red(c1) - Color.red(c2))
                sadSum += Math.abs(Color.green(c1) - Color.green(c2))
                sadSum += Math.abs(Color.blue(c1) - Color.blue(c2))
            }
            val sadNorm = sadSum.toDouble() / (32 * 32 * 3 * 255)

            // 色相直方圖距離（Bhattacharyya distance）
            val histDist = bhattacharyyaDistance(targetHist, templateHist)

            // 加權融合：色相距離權重 0.65，SAD 權重 0.35
            val score = histDist * 0.65 + sadNorm * 0.35

            if (score < bestScore) {
                secondScore = bestScore
                bestScore = score
                bestType = type
            } else if (score < secondScore) {
                secondScore = score
            }
        }

        // ★ 信心門檻：最佳分數太高(圖像污染) 或 與第二名太接近(模糊) → 回傳 UNKNOWN
        val CONFIDENCE_THRESHOLD = 0.35   // 分數太高代表完全不像任何樣板
        val MARGIN_THRESHOLD = 0.03       // 前兩名差距太小代表不確定
        return if (bestScore > CONFIDENCE_THRESHOLD || (secondScore - bestScore) < MARGIN_THRESHOLD) {
            Log.w("AutoPuzzler", "Low confidence: best=${"%.3f".format(bestScore)} margin=${"%.3f".format(secondScore - bestScore)}")
            OrbType.UNKNOWN
        } else {
            bestType
        }
    }
    // ★ 建立色相直方圖（18 個 bin，每 bin 20°）
// 只統計飽和度 > 0.2 且亮度 > 0.15 的像素，過濾陰影/白點干擾
    private fun buildHueHistogram(bitmap: Bitmap): FloatArray {
        val bins = 18
        val hist = FloatArray(bins)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var count = 0

        for (pixel in pixels) {
            val r = Color.red(pixel) / 255f
            val g = Color.green(pixel) / 255f
            val b = Color.blue(pixel) / 255f

            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min

            if (delta < 0.2f || max < 0.15f) continue  // 灰色/暗色不計入

            val hue = hueOf(r, g, b, max, delta)

            val bin = (hue / 360f * bins).toInt().coerceIn(0, bins - 1)
            hist[bin]++
            count++
        }

        // 正規化
        if (count > 0) for (i in hist.indices) hist[i] /= count
        return hist
    }

    /** RGB (各分量已正規化至 0-1)，回傳 HSV 色相 0-360°。呼叫前須確保 delta > 0。 */
    private fun hueOf(r: Float, g: Float, b: Float, max: Float, delta: Float): Float {
        var hue = when (max) {
            r    -> 60f * (((g - b) / delta) % 6f)
            g    -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        if (hue < 0f) hue += 360f
        return hue
    }

    // ★ Bhattacharyya 距離（直方圖相似度，0=完全相同，越大越不像）
    private fun bhattacharyyaDistance(h1: FloatArray, h2: FloatArray): Double {
        var bc = 0.0
        for (i in h1.indices) bc += Math.sqrt((h1[i] * h2[i]).toDouble())
        return if (bc >= 1.0) 0.0 else -Math.log(bc.coerceAtLeast(1e-10))
    }

    // 備用色彩分析
    // ★ 改善 Fallback：改用 HSV 色相範圍分類，對火/心 紅色系更準確
    private fun classifyFallback(target: Bitmap): OrbType {
        val pixels = IntArray(32 * 32)
        target.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        val hueBins = FloatArray(360)
        var validCount = 0

        for (pixel in pixels) {
            val r = Color.red(pixel) / 255f
            val g = Color.green(pixel) / 255f
            val b = Color.blue(pixel) / 255f
            val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
            if (delta < 0.2f || max < 0.2f) continue
            val hue = hueOf(r, g, b, max, delta)
            hueBins[hue.toInt().coerceIn(0, 359)]++
            validCount++
        }
        if (validCount == 0) return OrbType.UNKNOWN

        // 找主色相
        val peakHue = hueBins.indices.maxByOrNull { hueBins[it] } ?: return OrbType.UNKNOWN
        val saturation = pixels.map {
            val r = Color.red(it) / 255f; val g = Color.green(it) / 255f; val b = Color.blue(it) / 255f
            maxOf(r, g, b) - minOf(r, g, b)
        }.average().toFloat()

        return when {
            peakHue in 0..20 || peakHue in 340..359 ->
                if (saturation > 0.55f) OrbType.FIRE else OrbType.HEART  // 紅色高飽和=火，低飽和=心
            peakHue in 15..40 -> OrbType.HEART    // 粉紅/橘紅
            peakHue in 85..155 -> OrbType.WOOD    // 綠色
            peakHue in 190..250 -> OrbType.WATER  // 藍色
            peakHue in 45..85 -> OrbType.LIGHT    // 黃色
            peakHue in 260..320 -> OrbType.DARK   // 紫色
            else -> OrbType.UNKNOWN
        }
    }

    // ==========================================
    // 三種不同的適應度函數 (Fitness Functions)
    // ==========================================

    // 1. 一般模式 (追求極致 Combo 與聚攏)
    private fun evaluateStandard(board: Array<Array<OrbType>>, combos: Int): Int {
        var score = combos * 50000
        for (r in 0 until 5) for (c in 0 until 6) visited[r][c] = false
        val dr = intArrayOf(-1, 1, 0, 0); val dc = intArrayOf(0, 0, -1, 1)

        for (r in 0 until 5) {
            for (c in 0 until 6) {
                val type = board[r][c]
                if (type != OrbType.UNKNOWN && !visited[r][c]) {
                    var blobSize = 0; var head = 0; var tail = 0
                    queueR[tail] = r; queueC[tail] = c; tail++; visited[r][c] = true
                    while (head < tail) {
                        val currR = queueR[head]; val currC = queueC[head]; head++; blobSize++
                        for (d in 0..3) {
                            val nr = currR + dr[d]; val nc = currC + dc[d]
                            if (nr in 0 until 5 && nc in 0 until 6 && !visited[nr][nc] && board[nr][nc] == type) {
                                visited[nr][nc] = true; queueR[tail] = nr; queueC[tail] = nc; tail++
                            }
                        }
                    }
                    if (blobSize == 2) score += 100
                    if (blobSize >= 3) score += 300 + (blobSize - 3) * 10
                }
            }
        }
        return score
    }

    // 2. 底層平鋪模式 (防黏珠，逐行橫向 3 顆)
    private fun evaluateBottomUp(board: Array<Array<OrbType>>, combos: Int): Int {
        var score = combos * 50000
        var totalMatched = 0
        for (r in 0 until 5) for (c in 0 until 6) if (matchBoard[r][c]) totalMatched++

        // totalMatched 統計的是第一波消除，效率須以第一波 combo 數計算（不含天降）
        if (firstWaveCombos > 0) {
            val efficiency = totalMatched.toFloat() / firstWaveCombos
            if (efficiency > 3.0f) score -= ((efficiency - 3.0f) * 15000).toInt()
        }

        for (r in 0 until 5) {
            for (c in 0 until 4) {
                if (BoardHeuristics.isExactHorizontalTriple(board, r, c)) score += 8000 * (r + 1)
            }
        }
        return score
    }

    // 3. 昇華技模式 (強制優先構造直排 4 顆)
    private fun evaluateAmelioration(board: Array<Array<OrbType>>, combos: Int): Int {
        var score = combos * 10000 // 降低一般 Combo 權重

        // 偵測直排 4 顆的數學約束
        for (c in 0 until 6) {
            for (r in 0 until 2) { // 直排4顆只能從 row 0 或 row 1 開始
                val t = board[r][c]
                if (t != OrbType.UNKNOWN && board[r+1][c] == t && board[r+2][c] == t && board[r+3][c] == t) {
                    // 確保剛好是 4 顆 (防止 5 顆連線)
                    val isFive = (r == 0 && board[4][c] == t) || (r == 1 && board[0][c] == t)
                    if (!isFive) {
                        score += 500000 // 達成昇華條件，給予霸王級分數
                    }
                }
            }
        }
        return score
    }

    fun calculateOptimalPath(
        board: Array<Array<OrbType>>,
        orbSize: Float,
        startX: Float,
        startY: Float,
        is8Way: Boolean = true,
        strategyMode: Int = 0, // 0: 一般, 1: 平鋪, 2: 昇華
        startMode: Int = 0,    // 0: 全盤, 1: 左下, 2: 右下
        steps: Int = 35,
        beamWidth: Int = 200,
        shapeGoals: List<ShapeGoal> = emptyList(),
        constraint: MoveConstraint = MoveConstraint.NONE,
        clearTypes: Set<OrbType> = emptySet()
    ): Pair<List<Pair<Float, Float>>, Int> {
        val best = runBeam(board, is8Way, strategyMode, startMode, steps, beamWidth, shapeGoals, poolCapacity = 0,
            constraint = constraint, clearTypes = clearTypes)
            .firstOrNull() ?: return Pair(emptyList(), 0)
        val pixelPath = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until best.pathLen) {
            pixelPath.add(Pair(startX + best.pathC[i] * orbSize + orbSize / 2, startY + best.pathR[i] * orbSize + orbSize * 5f / 8f))
        }
        return Pair(pixelPath, best.combos)
    }

    /**
     * 同一套 Beam 搜尋，但回傳去重後的 Top-K 候選（第一條與 [calculateOptimalPath] 相同）。
     * 每條都經 [CandidateFactory] 重新回播、驗證路徑合法性、形狀與 Combo。
     */
    fun calculateOptimalCandidates(
        board: Array<Array<OrbType>>,
        is8Way: Boolean = true,
        strategyMode: Int = 0,
        startMode: Int = 0,
        steps: Int = 35,
        beamWidth: Int = 200,
        shapeGoals: List<ShapeGoal> = emptyList(),
        maxCandidates: Int = 5,
        constraint: MoveConstraint = MoveConstraint.NONE,
        clearTypes: Set<OrbType> = emptySet()
    ): List<PathCandidate> {
        val nodes = runBeam(board, is8Way, strategyMode, startMode, steps, beamWidth, shapeGoals,
            poolCapacity = (maxCandidates * 6).coerceAtLeast(1), constraint = constraint, clearTypes = clearTypes)
        val verifier = shapeGoals.takeIf { it.isNotEmpty() }?.let { ShapeVerifier(it) }
        val simulator = BoardSimulator(5, 6)
        val built = nodes.mapNotNull { node ->
            val cells = (0 until node.pathLen).map { node.pathR[it] * 6 + node.pathC[it] }
            CandidateFactory.build(board, cells, is8Way, verifier, CandidateSource.BEAM, node.score.toLong(), simulator, clearTypes)
        }.filter { it.executable && constraint.endOk(it.pathCells.last()) }
        // Beam 找不到全消解（或有指定終點）時改用兩段式：
        // ① 推珠規劃器先把目標珠排成一直線（含起終點）② 從排好的盤面接續 Beam，用剩餘步數堆 Combo
        val maxTotal = steps.coerceIn(5, 60)
        val planned = if (clearTypes.size == 1 && shapeGoals.isEmpty() && (built.isEmpty() || constraint.hasEnd)) {
            val plans = ClearPlanner.plan(board, clearTypes, constraint.takeIf { it.hasStart || it.hasEnd } ?: MoveConstraint(
                startCell = constraint.startCells(startMode).singleOrNull() ?: -1), maxTotal)
            val paths = ArrayList<List<Int>>()
            for (plan in plans.take(PHASE2_PLANS)) {
                paths.add(plan.cells)
                val prefix = plan.arranged
                val remaining = maxTotal - (prefix.size - 1)
                if (remaining < 2) continue
                val mid = PathReplay.replay(board, prefix)
                val tail = runBeam(mid, is8Way, strategyMode, startMode, remaining, beamWidth, emptyList(), poolCapacity = 3,
                    constraint = MoveConstraint(prefix.last(), constraint.endCell), clearTypes = clearTypes)
                for (node in tail) {
                    val suffix = (1 until node.pathLen).map { node.pathR[it] * 6 + node.pathC[it] }
                    if (prefix.size - 1 + suffix.size <= maxTotal) paths.add(prefix + suffix)
                }
            }
            paths.mapNotNull { cells ->
                CandidateFactory.build(board, cells, is8Way, verifier, CandidateSource.BEAM, 0L, simulator, clearTypes)
            }.filter { it.executable && constraint.endOk(it.pathCells.last()) &&
                (!constraint.hasStart || it.pathCells.first() == constraint.startCell) }
        } else emptyList()
        // 有首批全消條件時，達成者之間以 Combo 最高為優先
        val ordered = if (clearTypes.isEmpty()) built else (built + planned).sortedByDescending { it.deterministicCombos }
        return CandidateFactory.dedupe(ordered).take(maxCandidates.coerceAtLeast(1))
    }

    /** 提供給 Shape-Aware 求解器的計分；Combo 與 firstWave 來自同一次 resolve，平鋪評分才會讀到正確的首消標記。 */
    internal fun scoreBoard(board: Array<Array<OrbType>>, strategyMode: Int, clearTypes: Set<OrbType> = emptySet()): BoardScore {
        val combos = calculateCombosWithCascade(board)
        val firstWave = firstWaveCombos
        val clearLeft = ClearGoal.remaining(board, matchBoard, clearTypes)
        val clearCost = ClearGoal.cost(board, matchBoard, clearTypes)
        val score = when (strategyMode) {
            1 -> evaluateBottomUp(board, combos)
            2 -> evaluateAmelioration(board, combos)
            else -> evaluateStandard(board, combos)
        }
        return BoardScore(combos, firstWave, score, clearLeft, clearCost)
    }

    /** SolverMode.SHAPE_AWARE 入口：形狀硬約束優先，回傳明確狀態。 */
    fun solveShapeAware(
        board: Array<Array<OrbType>>,
        shapeGoals: List<ShapeGoal>,
        budget: SearchBudget,
        strategyMode: Int = 0,
        cancelled: () -> Boolean = { false }
    ): ShapeSolveResult = ShapeAwareSolver(shapeGoals, { scoreBoard(it, strategyMode, budget.clearTypes) }, cancelled = cancelled).solve(board, budget)

    /**
     * Beam 核心。回傳依原排序（分數 → Combo → 較短）排列的節點：第一個即全域最佳（與舊版相同）。
     * poolCapacity > 0 時另外保留結果盤面不同的前幾名，供 Top-K 候選使用。
     */
    private fun runBeam(
        board: Array<Array<OrbType>>,
        is8Way: Boolean,
        strategyMode: Int,
        startMode: Int,
        steps: Int,
        beamWidth: Int,
        shapeGoals: List<ShapeGoal>,
        poolCapacity: Int,
        constraint: MoveConstraint = MoveConstraint.NONE,
        clearTypes: Set<OrbType> = emptySet()
    ): List<SearchNode> {
        if (!isCompleteBoard(board)) return emptyList()
        if (ClearGoal.infeasibleReason(board, clearTypes) != null) return emptyList()
        val requireClear = clearTypes.isNotEmpty()
        // 起結有序盾：起點珠會被一路帶到終點，全消排列需經過終點格
        val anchorType = if (constraint.hasStart && constraint.hasEnd) board[constraint.startCell / 6][constraint.startCell % 6] else null
        val anchorCell = if (anchorType != null && anchorType in clearTypes) constraint.endCell else -1
        val shapeEvaluator = shapeGoals.takeIf { it.isNotEmpty() }?.let { ShapeEvaluator(it) }
        if (shapeEvaluator != null && !shapeEvaluator.canAttempt(board)) return emptyList()
        val maxSteps = steps.coerceIn(5, 60)
        val pool = if (poolCapacity > 0) CandidatePool(poolCapacity, maxSteps) else null
        val beam = beamWidth.coerceIn(30, 500)

        val nextNodes = Array(beam * 8) { SearchNode(maxSteps) }
        val currentNodes = Array(beam) { SearchNode(maxSteps) }
        // 有硬條件時的引導順序：形狀達成數 → （形狀未全達成時）形狀潛力 → 分數 − 全消剩餘成本
        // 指定終點：手指離終點越遠扣分越多，且隨已用步數加重，讓後段路線自然收斂到終點
        var stepIndex = 0
        fun endPenalty(n: SearchNode): Long {
            if (!constraint.hasEnd) return 0L
            val d = kotlin.math.abs(n.r - constraint.endCell / 6) + kotlin.math.abs(n.c - constraint.endCell % 6)
            return d.toLong() * END_PULL * (stepIndex + 1) / maxSteps
        }
        val selector = BeamSelector(beam, if (shapeEvaluator == null && !requireClear && !constraint.hasEnd) null else { a, b ->
            val left = nextNodes[a]
            val right = nextNodes[b]
            val satisfied = left.shapes.satisfied.compareTo(right.shapes.satisfied)
            val potential = left.shapes.potential.compareTo(right.shapes.potential)
            // 全消剩餘以分數折抵（成本 1 點 ≈ 1 Combo），讓搜尋在接近全消時仍能繼續堆 Combo
            val blended = (left.score.toLong() - left.clearCost.toLong() * ClearGoal.guideWeight - endPenalty(left))
                .compareTo(right.score.toLong() - right.clearCost.toLong() * ClearGoal.guideWeight - endPenalty(right))
            when {
                satisfied != 0 -> satisfied
                potential != 0 && shapeEvaluator != null && left.shapes.satisfied < shapeGoals.size -> potential
                else -> blended
            }
        }) { nextNodes[it].score }
        /** 達成條件的節點之間：有全消條件時 Combo 優先，否則沿用原本的分數優先。 */
        fun better(n: SearchNode, best: SearchNode): Boolean = if (requireClear)
            n.combos > best.combos || (n.combos == best.combos && n.score > best.score) ||
                (n.combos == best.combos && n.score == best.score && n.pathLen < best.pathLen)
        else n.score > best.score || (n.score == best.score && n.combos > best.combos) ||
            (n.score == best.score && n.combos == best.combos && n.pathLen < best.pathLen)
        val globalBestNode = SearchNode(maxSteps)
        globalBestNode.combos = 0; globalBestNode.score = Int.MIN_VALUE

        val dirsR = if (is8Way) intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1) else intArrayOf(-1, 1, 0, 0)
        val dirsC = if (is8Way) intArrayOf(0, 0, -1, 1, -1, 1, -1, 1) else intArrayOf(0, 0, -1, 1)

        // 起手點篩選
        // 指定起點時只從該格起手；否則沿用 startMode（全盤／左下／右下）
        val startPoints = constraint.startCells(startMode).map { Pair(it / 6, it % 6) }
        // 指定終點時，另外保留不論終點的高分節點，搜尋完再補走到終點
        val anyEndPool = if (constraint.hasEnd) CandidatePool(16, maxSteps) else null

        // 共享種子池：所有起手點放進同一個 beam pool 全域競爭淘汰，
        // 讓搜尋資源集中在有潛力的分支，而非每個起手點各跑一輪
        val initCombos = calculateCombosWithCascade(board)
        val initClearLeft = ClearGoal.remaining(board, matchBoard, clearTypes)
        val initClearCost = ClearGoal.cost(board, matchBoard, clearTypes, anchorCell, anchorType)
        val initScore = when (strategyMode) {
            1 -> evaluateBottomUp(board, initCombos)
            2 -> evaluateAmelioration(board, initCombos)
            else -> evaluateStandard(board, initCombos)
        }
        var currentNodesCount = 0
        for (startPt in startPoints) {
            if (currentNodesCount >= beam) break
            val n = currentNodes[currentNodesCount++]
            n.copyBoard(board)
            n.r = startPt.first; n.c = startPt.second
            n.pathLen = 1; n.pathR[0] = startPt.first; n.pathC[0] = startPt.second
            n.combos = initCombos; n.score = initScore
            n.clearLeft = initClearLeft; n.clearCost = initClearCost
        }

        for (s in 0 until maxSteps) {
            stepIndex = s
            var nextNodesCount = 0
            for (i in 0 until currentNodesCount) {
                val curr = currentNodes[i]
                for (d in dirsR.indices) {
                    val nr = curr.r + dirsR[d]; val nc = curr.c + dirsC[d]
                    if (nr in 0 until 5 && nc in 0 until 6) {
                        if (curr.pathLen >= 2 && curr.pathR[curr.pathLen-2] == nr && curr.pathC[curr.pathLen-2] == nc) continue

                        val nextNode = nextNodes[nextNodesCount++]
                        nextNode.copyFrom(curr)
                        val temp = nextNode.board[curr.r][curr.c]
                        nextNode.board[curr.r][curr.c] = nextNode.board[nr][nc]
                        nextNode.board[nr][nc] = temp
                        nextNode.r = nr; nextNode.c = nc
                        nextNode.pathR[nextNode.pathLen] = nr; nextNode.pathC[nextNode.pathLen] = nc
                        nextNode.pathLen++

                        // 包含既有符石落下形成的連鎖，不預測隨機新珠
                        nextNode.combos = calculateCombosWithCascade(nextNode.board)
                        // 必須緊接在 resolve 之後讀取首批消除標記
                        nextNode.clearLeft = ClearGoal.remaining(nextNode.board, matchBoard, clearTypes)
                        nextNode.clearCost = ClearGoal.cost(nextNode.board, matchBoard, clearTypes, anchorCell, anchorType)
                        val baseScore = when(strategyMode) {
                            1 -> evaluateBottomUp(nextNode.board, nextNode.combos)
                            2 -> evaluateAmelioration(nextNode.board, nextNode.combos)
                            else -> evaluateStandard(nextNode.board, nextNode.combos)
                        }
                        nextNode.score = baseScore - nextNode.pathLen * 15
                        if (shapeEvaluator != null) nextNode.shapes = shapeEvaluator.evaluate(nextNode.board)

                        // 優先順序：① 分數更高（策略加成優先） ② combo 更多 ③ 步數更少
                        val isBetter = better(nextNode, globalBestNode)
                        val shapesDone = nextNode.shapes.satisfied == shapeGoals.size && nextNode.clearLeft == 0
                        val endOk = constraint.endOk(nr * 6 + nc)
                        if (isBetter && shapesDone && endOk) globalBestNode.copyFrom(nextNode)
                        if (pool != null && shapesDone && endOk && nextNode.combos > 0) pool.offer(nextNode)
                        // 有版型條件時，只保留手指不在已完成形狀上的節點（補走會帶走手上的珠子）
                        if (anyEndPool != null && !endOk && (shapeEvaluator == null ||
                                (shapesDone && shapeEvaluator.verifier.verify(nextNode.board).usedMask and (1L shl (nr * 6 + nc)) == 0L)))
                            anyEndPool.offer(nextNode)
                    }
                }
            }
            if (nextNodesCount == 0) break
            currentNodesCount = 0
            for (index in selector.select(nextNodesCount)) {
                currentNodes[currentNodesCount++].copyFrom(nextNodes[index])
            }
        }

        // ── 指定終點：把高分但停在別處的路線，以最短補走延伸到終點後重新評分 ──
        if (anyEndPool != null) {
            val extended = SearchNode(maxSteps)
            for (seed in anyEndPool.sorted()) {
                val from = seed.pathR[seed.pathLen - 1] * 6 + seed.pathC[seed.pathLen - 1]
                val previous = if (seed.pathLen >= 2) seed.pathR[seed.pathLen - 2] * 6 + seed.pathC[seed.pathLen - 2] else -1
                val walk = PathReplay.walkTo(from, previous, constraint.endCell, is8Way, maxSteps - (seed.pathLen - 1)) ?: continue
                extended.copyFrom(seed)
                for (cell in walk) {
                    val nr = cell / 6; val nc = cell % 6
                    val temp = extended.board[extended.r][extended.c]
                    extended.board[extended.r][extended.c] = extended.board[nr][nc]
                    extended.board[nr][nc] = temp
                    extended.r = nr; extended.c = nc
                    extended.pathR[extended.pathLen] = nr; extended.pathC[extended.pathLen] = nc
                    extended.pathLen++
                }
                extended.combos = calculateCombosWithCascade(extended.board)
                extended.clearLeft = ClearGoal.remaining(extended.board, matchBoard, clearTypes)
                val baseScore = when (strategyMode) {
                    1 -> evaluateBottomUp(extended.board, extended.combos)
                    2 -> evaluateAmelioration(extended.board, extended.combos)
                    else -> evaluateStandard(extended.board, extended.combos)
                }
                extended.score = baseScore - extended.pathLen * 15
                if (shapeEvaluator != null) extended.shapes = shapeEvaluator.evaluate(extended.board)
                if (extended.shapes.satisfied != shapeGoals.size || extended.combos <= 0 || extended.clearLeft != 0) continue
                val isBetter = better(extended, globalBestNode)
                if (isBetter) globalBestNode.copyFrom(extended)
                pool?.offer(extended)
            }
        }

        if (globalBestNode.combos <= 0 || globalBestNode.pathLen <= 1) return emptyList()
        val bestKey = StateKey.of(globalBestNode.board)
        val others = pool?.sorted()?.filter { StateKey.of(it.board) != bestKey } ?: emptyList()
        return listOf(globalBestNode) + others
    }

    /** 依結果盤面去重的有界候選池；排序與 globalBest 相同（分數 → Combo → 較短 → 先出現）。 */
    private class CandidatePool(private val capacity: Int, private val maxSteps: Int) {
        private class Entry(val node: SearchNode, val seq: Long)
        private val entries = HashMap<StateKey, Entry>()
        private var seq = 0L
        private val order = Comparator<Entry> { a, b ->
            when {
                a.node.score != b.node.score -> b.node.score.compareTo(a.node.score)
                a.node.combos != b.node.combos -> b.node.combos.compareTo(a.node.combos)
                a.node.pathLen != b.node.pathLen -> a.node.pathLen.compareTo(b.node.pathLen)
                else -> a.seq.compareTo(b.seq)
            }
        }
        private var worst: Entry? = null

        fun offer(node: SearchNode) {
            seq++
            val probe = Entry(node, seq)
            if (entries.size >= capacity && worst != null && order.compare(probe, worst!!) >= 0) return
            val key = StateKey.of(node.board)
            val existing = entries[key]
            if (existing != null && order.compare(probe, existing) >= 0) return
            val copy = SearchNode(maxSteps).also { it.copyFrom(node) }
            entries[key] = Entry(copy, seq)
            if (entries.size > capacity) {
                val drop = entries.entries.maxWithOrNull { a, b -> order.compare(a.value, b.value) }!!
                entries.remove(drop.key)
            }
            worst = entries.values.maxWithOrNull(order)
        }

        fun sorted(): List<SearchNode> = entries.values.sortedWith(order).map { it.node }
    }

    /**
     * ML-based path planning that delegates to [MLOrbPlanner] for step-by-step inference.
     *
     * For startMode 0 (全盤), five strategic starting positions are tried and the one
     * that yields the most combos is returned.  For modes 1/2, a single corner is used.
     *
     * The return type is identical to [calculateOptimalPath] so callers can treat both
     * interchangeably.
     */
    fun calculateMLPath(
        board: Array<Array<OrbType>>,
        orbSize: Float,
        startX: Float,
        startY: Float,
        mlPlanner: MLOrbPlanner,
        steps: Int = 35,
        startMode: Int = 0,
        constraint: MoveConstraint = MoveConstraint.NONE
    ): Pair<List<Pair<Float, Float>>, Int> = calculatePolicyPath(
        board, orbSize, startX, startY, steps, startMode, constraint, mlPlanner::planPath
    )

    internal fun calculatePolicyPath(
        board: Array<Array<OrbType>>,
        orbSize: Float,
        startX: Float,
        startY: Float,
        steps: Int,
        startMode: Int,
        constraint: MoveConstraint = MoveConstraint.NONE,
        planPath: (Array<Array<OrbType>>, Int, Int, Int) -> List<Pair<Int, Int>>
    ): Pair<List<Pair<Float, Float>>, Int> {
        if (!isCompleteBoard(board)) return Pair(emptyList(), 0)
        val maxSteps = steps.coerceIn(5, 60)

        // Candidate starting positions based on startMode
        val candidates: List<Pair<Int, Int>> = if (constraint.hasStart) listOf(Pair(constraint.startCell / 6, constraint.startCell % 6))
        else when (startMode) {
            1 -> listOf(Pair(4, 0))
            2 -> listOf(Pair(4, 5))
            else -> listOf(
                Pair(4, 0), Pair(4, 5), Pair(0, 0), Pair(0, 5), Pair(2, 2)
            )
        }

        var bestPixelPath: List<Pair<Float, Float>> = emptyList()
        var bestCombos = 0

        for ((startRow, startCol) in candidates) {
            var nodePath = planPath(board, maxSteps, startRow, startCol)
            if (nodePath.size <= 1) continue
            // 指定終點：模型路線停在別處時補走到終點（ML 只走上下左右）
            if (constraint.hasEnd) {
                val cells = nodePath.map { (r, c) -> r * 6 + c }
                val walk = PathReplay.walkTo(cells.last(), cells.getOrElse(cells.size - 2) { -1 }, constraint.endCell,
                    false, maxSteps - (cells.size - 1)) ?: continue
                nodePath = nodePath + walk.map { it / 6 to it % 6 }
            }

            // Simulate the board state after following this path
            val simBoard = Array(5) { r -> Array(6) { c -> board[r][c] } }
            var row = nodePath[0].first
            var col = nodePath[0].second
            for (i in 1 until nodePath.size) {
                val nr = nodePath[i].first
                val nc = nodePath[i].second
                val temp = simBoard[row][col]
                simBoard[row][col] = simBoard[nr][nc]
                simBoard[nr][nc] = temp
                row = nr; col = nc
            }
            val combos = calculateCombosWithCascade(simBoard)

            if (combos > bestCombos) {
                bestCombos = combos
                // Convert board positions to pixel coordinates using the same formula as
                // calculateOptimalPath (X = center, Y = 5/8 of cell height)
                bestPixelPath = nodePath.map { (r, c) ->
                    Pair(
                        startX + c * orbSize + orbSize / 2f,
                        startY + r * orbSize + orbSize * 5f / 8f
                    )
                }
            }
        }

        if (bestCombos <= 0) return Pair(emptyList(), 0)
        return Pair(bestPixelPath, bestCombos)
    }

    private fun isCompleteBoard(board: Array<Array<OrbType>>): Boolean =
        board.size == 5 && board.all { row -> row.size == 6 && row.none { it == OrbType.UNKNOWN } }

    private fun calculateCombosWithCascade(board: Array<Array<OrbType>>): Int =
        simulator.resolve(board)
}
// --- 修改結尾 ---
