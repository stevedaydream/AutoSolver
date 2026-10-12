package com.stevedaydream.autodora

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 辨識資料庫：儲存 HSV 特徵樣本，以 k-NN 分類
 *
 * variant = 'A'：高隱寫（演算法誤判，人工修正）
 * variant = 'B'：一般寫（演算法正確辨識）
 *
 * 使用流程（classifyByHSV）：
 *   1. 先查 A-type → 有把握時直接回傳（可覆蓋規則誤判）
 *   2. 跑 B-type 規則（現有 when 邏輯）
 *   3. 規則返回 UNKNOWN → 查 B-type 資料庫
 */
class RecognitionDatabase(private val context: Context) {

    data class Sample(
        val d290: Int, val d280: Int, val d300: Int,
        val w130: Int, val l50: Int,  val wt210: Int,
        val h310: Int, val h320: Int, val f020: Int,
        val validPx: Int,
        val type: BoardAnalyzer.OrbType,
        val variant: Char,                                                         // 'A'=高隱寫, 'B'=一般寫, 'D'=特效珠
        val status: BoardAnalyzer.OrbStatus = BoardAnalyzer.OrbStatus.NONE  // D類使用，其餘為 NONE
    ) {
        companion object {
            fun fromJson(obj: JSONObject) = Sample(
                d290    = obj.getInt("d290"),  d280 = obj.getInt("d280"), d300 = obj.getInt("d300"),
                w130    = obj.getInt("w130"),  l50  = obj.getInt("l50"),  wt210= obj.getInt("wt210"),
                h310    = obj.getInt("h310"),  h320 = obj.getInt("h320"), f020 = obj.getInt("f020"),
                validPx = obj.getInt("validPx"),
                type    = BoardAnalyzer.OrbType.valueOf(obj.getString("type")),
                variant = obj.getString("variant")[0],
                status  = try {
                    BoardAnalyzer.OrbStatus.valueOf(obj.optString("status", "NONE"))
                } catch (_: Exception) { BoardAnalyzer.OrbStatus.NONE }
            )
        }

        fun toJson() = JSONObject().apply {
            put("d290", d290); put("d280", d280); put("d300", d300)
            put("w130", w130); put("l50",  l50);  put("wt210", wt210)
            put("h310", h310); put("h320", h320); put("f020", f020)
            put("validPx", validPx)
            put("type", type.name); put("variant", variant.toString())
            put("status", status.name)
        }
    }

    // 預設樣本 + 使用者追加
    val samples = mutableListOf<Sample>()

    init {
        // 先載入預設，再從檔案補充使用者追加的
        samples.addAll(DEFAULT_SAMPLES)
        loadFromFile()
        Log.d(TAG, "Database ready: ${samples.size} samples total")
    }

    /**
     * 清空所有使用者追加的樣本，恢復到僅剩預設樣本的狀態。
     */
    fun resetUserSamples() {
        val removed = samples.size - DEFAULT_SAMPLES.size
        samples.clear()
        samples.addAll(DEFAULT_SAMPLES)
        try {
            File(context.filesDir, DB_FILE).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Reset failed: ${e.message}")
        }
        Log.d(TAG, "Reset: removed $removed user samples, total=${samples.size}")
    }

    /**
     * 加入新樣本並持久化（僅儲存使用者追加的部分）。
     * 回傳本次被淘汰的樣本數（0 代表未淘汰）。
     */
    fun addSample(sample: Sample): Int {
        samples.add(sample)
        val evicted = trimIfNeeded()
        saveToFile()
        Log.d(TAG, "Added ${sample.variant}-type ${sample.type}, total=${samples.size}")
        return evicted
    }

    /**
     * 若總樣本數超過 MAX_SAMPLES，持續淘汰直到符合上限。
     * 淘汰優先順序：
     *   1. 最冗餘的使用者樣本（在同 type＋同 variant 池中最近鄰距離 < REDUNDANCY_THRESHOLD）
     *   2. 無冗餘時，FIFO：淘汰最舊的使用者樣本
     * 預設樣本永不被淘汰。
     * 回傳淘汰數量。
     */
    private fun trimIfNeeded(): Int {
        var count = 0
        while (samples.size > MAX_SAMPLES) {
            val redundantIdx = findMostRedundantUserIndex()
            val evictIdx     = redundantIdx ?: DEFAULT_SAMPLES.size   // FIFO fallback
            val evicted      = samples.removeAt(evictIdx)
            val reason       = if (redundantIdx != null) "redundant" else "FIFO"
            Log.d(TAG, "Evicted($reason) ${evicted.variant}-${evicted.type} idx=$evictIdx, total=${samples.size}")
            count++
        }
        return count
    }

    /**
     * 在使用者追加的樣本中，找出「最冗餘」的樣本：
     *   - 對每個使用者樣本，計算它在「同 type + 同 variant」池（含預設）中的最近鄰距離
     *   - 最近鄰距離 < REDUNDANCY_THRESHOLD 才算冗餘
     *   - 回傳冗餘程度最高（距離最小）的樣本在 samples 全局列表中的索引
     *   - 若無任何冗餘樣本，回傳 null
     */
    private fun findMostRedundantUserIndex(): Int? {
        var minDist      = Double.MAX_VALUE
        var minGlobalIdx: Int? = null

        for (i in DEFAULT_SAMPLES.size until samples.size) {
            val s  = samples[i]
            val sv = vec(s.d290, s.d280, s.d300, s.w130, s.l50, s.wt210, s.h310, s.h320, s.f020, s.validPx)

            // 在同 type + 同 variant 的所有其他樣本中找最近鄰
            var nearestDist = Double.MAX_VALUE
            for (j in samples.indices) {
                if (j == i) continue
                val o = samples[j]
                if (o.type != s.type || o.variant != s.variant || o.status != s.status) continue
                val d = euclidean(sv, vec(o.d290, o.d280, o.d300, o.w130, o.l50, o.wt210, o.h310, o.h320, o.f020, o.validPx))
                if (d < nearestDist) nearestDist = d
            }

            if (nearestDist < REDUNDANCY_THRESHOLD && nearestDist < minDist) {
                minDist      = nearestDist
                minGlobalIdx = i
            }
        }

        return minGlobalIdx
    }

    /**
     * k-NN 分類（k=3）
     * - variant: 'A' 或 'B'，只在對應池中搜尋
     * - 最近鄰距離 > threshold → 返回 null（不確定）
     */
    fun classify(
        d290: Int, d280: Int, d300: Int, w130: Int, l50: Int,
        wt210: Int, h310: Int, h320: Int, f020: Int, validPx: Int,
        variant: Char
    ): BoardAnalyzer.OrbType? {
        val pool = samples.filter { it.variant == variant }
        if (pool.isEmpty()) return null

        val k = minOf(3, pool.size)
        val query = vec(d290, d280, d300, w130, l50, wt210, h310, h320, f020, validPx)

        // Partial find：維護大小為 k 的 top list，避免建立 n 個 Pair 物件再全量排序
        val topK = ArrayList<Pair<Sample, Double>>(k)
        var worstDist = Double.MAX_VALUE
        for (s in pool) {
            val d = euclidean(query, vec(s.d290, s.d280, s.d300, s.w130, s.l50, s.wt210, s.h310, s.h320, s.f020, s.validPx))
            if (topK.size < k) {
                topK.add(Pair(s, d))
                if (topK.size == k) worstDist = topK.maxOf { it.second }
            } else if (d < worstDist) {
                val worstIdx = topK.indexOfFirst { it.second == worstDist }
                topK[worstIdx] = Pair(s, d)
                worstDist = topK.maxOf { it.second }
            }
        }
        topK.sortBy { it.second }
        val ranked = topK

        val nearestDist = ranked.first().second
        val threshold = when (variant) {
            'A', 'D' -> A_THRESHOLD
            else     -> B_THRESHOLD
        }

        // 距離超過門檻 → 不分類
        if (nearestDist > threshold) {
            Log.d(TAG, "${variant}-NN: no match (dist=${"%.3f".format(nearestDist)} > thr=$threshold)")
            return null
        }

        // k=3 多數決
        val votes = ranked.groupBy { it.first.type }
        val winner = votes.maxByOrNull { it.value.size }!!.key
        Log.d(TAG, "${variant}-NN: $winner (dist=${"%.3f".format(nearestDist)})")
        return winner
    }

    // ── 特徵向量（正規化）──────────────────────────────────────
    // 每個分量除以對應的典型最大值，使各特徵量綱一致
    private fun vec(
        d290: Int, d280: Int, d300: Int, w130: Int, l50: Int,
        wt210: Int, h310: Int, h320: Int, f020: Int, validPx: Int
    ) = doubleArrayOf(
        d290    / 500.0,
        d280    / 500.0,
        d300    / 1000.0,
        w130    / 1000.0,
        l50     / 600.0,
        wt210   / 800.0,
        h310    / 800.0,
        h320    / 500.0,
        f020    / 6000.0,
        validPx / 7000.0
    )

    private fun euclidean(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) { val d = a[i] - b[i]; sum += d * d }
        return Math.sqrt(sum)
    }

    // ── 持久化 ────────────────────────────────────────────────
    private fun saveToFile() {
        try {
            val userSamples = samples.drop(DEFAULT_SAMPLES.size)
            val arr = JSONArray()
            userSamples.forEach { arr.put(it.toJson()) }
            File(context.filesDir, DB_FILE).writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
        }
    }

    private fun loadFromFile() {
        try {
            val file = File(context.filesDir, DB_FILE)
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                samples.add(Sample.fromJson(arr.getJSONObject(i)))
            }
            Log.d(TAG, "Loaded ${arr.length()} user samples from file")
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════
    companion object {
        const val TAG         = "RecognitionDB"
        const val DB_FILE     = "recognition_db.json"
        const val A_THRESHOLD = 0.25   // A類（高隱寫）距離門檻
        const val B_THRESHOLD = 0.20   // B類（一般寫）距離門檻
        const val D_THRESHOLD = 0.25   // D類（特效珠）距離門檻

        const val MAX_SAMPLES          = 300   // 總樣本上限（含預設）
        const val REDUNDANCY_THRESHOLD = 0.05  // 最近鄰距離低於此值 → 視為冗餘

        /** 全局單例，由 FloatingWindow.show() 初始化 */
        var instance: RecognitionDatabase? = null

        // ─── 強化模式辨識資料（本次來源）────────────────────
        // 格式：Sample(d290, d280, d300, w130, l50, wt210, h310, h320, f020, validPx, OrbType, variant)
        val DEFAULT_SAMPLES = listOf(

            // ══ B 類：一般寫（演算法正確辨識）══════════════════

            // 火 [0,0~2]
            Sample(0, 0,   0,   0, 168,   0,   0,   0, 2543, 4005, BoardAnalyzer.OrbType.FIRE,  'B'),
            Sample(0, 0,   0,   0, 101,   0,   0,   0, 4077, 4790, BoardAnalyzer.OrbType.FIRE,  'B'),
            Sample(0, 0,   0,   0, 104,   0,   0,   0, 3624, 4476, BoardAnalyzer.OrbType.FIRE,  'B'),

            // 心 [0,4~5]
            Sample( 0, 0,  63,  0, 100,   0, 275, 379,  274, 3214, BoardAnalyzer.OrbType.HEART, 'B'),
            Sample(24, 0, 564,  0, 109,   0, 681, 254,  178, 3477, BoardAnalyzer.OrbType.HEART, 'B'),

            // 水 [1,3~5]
            Sample(0, 0,   0,   0,  96, 491,   7,  16, 1924, 4403, BoardAnalyzer.OrbType.WATER, 'B'),
            Sample(0, 0,   0,   0,  89, 486,   7,  16, 2124, 4616, BoardAnalyzer.OrbType.WATER, 'B'),
            Sample(0, 0,   0,   0,  96, 507,   8,  13, 1719, 4175, BoardAnalyzer.OrbType.WATER, 'B'),

            // 木 [2,0~2]
            Sample(0, 0,   0, 349,  98,   0,   8,  20, 2045, 5037, BoardAnalyzer.OrbType.WOOD,  'B'),
            Sample(0, 0,   0, 348, 108,   0,   7,  24, 2198, 5294, BoardAnalyzer.OrbType.WOOD,  'B'),
            Sample(0, 0,   0, 358, 100,   0,   7,  24, 2019, 5011, BoardAnalyzer.OrbType.WOOD,  'B'),

            // 光 [3,3~5]
            Sample(0, 0,   0,   0, 532,   0,   7,  23, 1989, 4218, BoardAnalyzer.OrbType.LIGHT, 'B'),
            Sample(0, 0,   0,   0, 534,   0,   7,  20, 1882, 4133, BoardAnalyzer.OrbType.LIGHT, 'B'),
            Sample(0, 0,   0,   0, 520,   0,   8,  20, 1869, 4077, BoardAnalyzer.OrbType.LIGHT, 'B'),

            // ══ A 類：高隱寫（演算法誤判，人工修正）══════════

            // 心 [0,3]  — 誤判為 FIRE（火訊號偏高，心訊號偏低）
            Sample(0, 0,   3,   0,  90,   0,  91, 204,  859, 3841, BoardAnalyzer.OrbType.HEART, 'A'),

            // 心 [1,0~2] — 誤判為 FIRE（超高火訊號 + 超高 validPx）
            Sample(0, 0,   0,   0,  95,   0,   0,   4, 4811, 6206, BoardAnalyzer.OrbType.HEART, 'A'),
            Sample(0, 0,   0,   0,  92,   0,   0,   2, 5172, 6597, BoardAnalyzer.OrbType.HEART, 'A'),
            Sample(0, 0,   0,   0, 104,   0,   0,   4, 4810, 6214, BoardAnalyzer.OrbType.HEART, 'A'),

            // 心 [2,3~5] — 誤判為 WATER（高水訊號 + 高 validPx）
            Sample(0, 0,   0,   0,  87, 702,   1,   6,   76, 5112, BoardAnalyzer.OrbType.HEART, 'A'),
            Sample(0, 0,   0,   0,  91, 675,   0,   7,  132, 5133, BoardAnalyzer.OrbType.HEART, 'A'),
            Sample(0, 0,   0,   0,  85, 705,   0,   6,   89, 5141, BoardAnalyzer.OrbType.HEART, 'A'),

            // 心 [3,0~2] — 誤判為 UNKNOWN（所有訊號低 + 超高 validPx）
            Sample(0, 0,   0,   0, 113,   0,   0,   5,  308, 6023, BoardAnalyzer.OrbType.HEART, 'A'),
            Sample(0, 0,   0,   0, 112,   0,   0,   4,  779, 6561, BoardAnalyzer.OrbType.HEART, 'A'),
            Sample(0, 0,   0,   0, 103,   0,   1,   4,  296, 6003, BoardAnalyzer.OrbType.HEART, 'A'),

            // 暗 [4,0~2] — 誤判為 FIRE（d300 主導，d290/d280 偏低）
            Sample(119, 27, 802, 0, 100,  0,  36,  21, 1942, 4551, BoardAnalyzer.OrbType.DARK,  'A'),
            Sample(126, 30, 796, 0, 100,  0,  37,  24, 1729, 4361, BoardAnalyzer.OrbType.DARK,  'A'),
            Sample(124, 27, 808, 0,  91,  0,  35,  22, 2051, 4697, BoardAnalyzer.OrbType.DARK,  'A'),

            // 心 [4,3~5] — 誤判為 FIRE（中等火訊號）
            Sample(0, 0,   0,   0,  92,   0,   0,   7, 1721, 4003, BoardAnalyzer.OrbType.HEART, 'A'),
            // [4,4] l50=88，其餘截斷未收到，以相鄰格推估
            Sample(0, 0,   0,   0,  88,   0,   0,   6, 1650, 4050, BoardAnalyzer.OrbType.HEART, 'A'),
            // [4,5] 資料未收到，以相鄰格推估
            Sample(0, 0,   0,   0,  90,   0,   0,   7, 1680, 4020, BoardAnalyzer.OrbType.HEART, 'A'),
        )
    }
}
