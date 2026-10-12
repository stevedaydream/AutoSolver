package com.stevedaydream.autodora

import org.json.JSONArray
import org.json.JSONObject

sealed class MacroAction {
    var preview: MacroPreview? = null

    /** 單次點擊（開技能、確認按鈕等） */
    data class Tap(val x: Float, val y: Float, val holdMs: Long = 80L) : MacroAction()

    /** 自訂滑動（非盤面操作，如換頁） */
    data class Swipe(val path: List<Pair<Float, Float>>, val durationMs: Long) : MacroAction()

    /** 等待（過場動畫、消除動畫） */
    data class Wait(val ms: Long) : MacroAction()

    /** 自動辨識盤面 → 用現有演算法求解 */
    data class AutoBoard(val steps: Int = 35, val strategyMode: Int = 0) : MacroAction()

    /** 開技能：點擊隊員按鈕啟動技能，hasStage2=true 時需額外記錄第二段目標點 */
    data class UseSkill(
        val memberSlot: Int,
        val skillNumber: Int,
        val hasStage2: Boolean,
        val tapX: Float,
        val tapY: Float,
        val stage2X: Float = 0f,
        val stage2Y: Float = 0f
    ) : MacroAction()

    /** 固定盤面：先比對，完全一致才走預錄路徑，否則降級自動 */
    data class FixedBoard(
        val boardSignature: Array<Array<BoardAnalyzer.OrbType>>,
        val path: List<Pair<Float, Float>>,
        val durationMs: Long,
        val label: String = ""
    ) : MacroAction() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FixedBoard) return false
            return label == other.label && durationMs == other.durationMs &&
                   boardSignature.contentDeepEquals(other.boardSignature) &&
                   path == other.path
        }
        override fun hashCode(): Int {
            var result = boardSignature.contentDeepHashCode()
            result = 31 * result + path.hashCode()
            result = 31 * result + durationMs.hashCode()
            result = 31 * result + label.hashCode()
            return result
        }
    }

    fun toJson(): JSONObject = when (this) {
        is Tap -> JSONObject().apply {
            put("type", "tap")
            put("x", x.toDouble()); put("y", y.toDouble()); put("holdMs", holdMs)
        }
        is Swipe -> JSONObject().apply {
            put("type", "swipe")
            put("path", JSONArray().also { arr ->
                path.forEach { (px, py) ->
                    arr.put(JSONArray().apply { put(px.toDouble()); put(py.toDouble()) })
                }
            })
            put("durationMs", durationMs)
        }
        is Wait -> JSONObject().apply { put("type", "wait"); put("ms", ms) }
        is AutoBoard -> JSONObject().apply {
            put("type", "autoBoard"); put("steps", steps); put("strategyMode", strategyMode)
        }
        is UseSkill -> JSONObject().apply {
            put("type", "useSkill")
            put("memberSlot", memberSlot); put("skillNumber", skillNumber)
            put("hasStage2", hasStage2)
            put("tapX", tapX.toDouble()); put("tapY", tapY.toDouble())
            if (hasStage2) { put("stage2X", stage2X.toDouble()); put("stage2Y", stage2Y.toDouble()) }
        }
        is FixedBoard -> JSONObject().apply {
            put("type", "fixedBoard"); put("label", label)
            put("board", JSONArray().also { boardArr ->
                boardSignature.forEach { row ->
                    boardArr.put(JSONArray().also { rowArr -> row.forEach { rowArr.put(it.name) } })
                }
            })
            put("path", JSONArray().also { arr ->
                path.forEach { (px, py) ->
                    arr.put(JSONArray().apply { put(px.toDouble()); put(py.toDouble()) })
                }
            })
            put("durationMs", durationMs)
        }
    }.also { obj -> preview?.let { obj.put("preview", it.toJson()) } }

    companion object {
        fun fromJson(obj: JSONObject): MacroAction = when (obj.getString("type")) {
            "tap" -> Tap(
                obj.getDouble("x").toFloat(),
                obj.getDouble("y").toFloat(),
                obj.optLong("holdMs", 80L)
            )
            "swipe" -> {
                val arr = obj.getJSONArray("path")
                Swipe(
                    (0 until arr.length()).map { i ->
                        val pt = arr.getJSONArray(i)
                        Pair(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
                    },
                    obj.getLong("durationMs")
                )
            }
            "wait" -> Wait(obj.getLong("ms"))
            "autoBoard" -> AutoBoard(obj.optInt("steps", 35), obj.optInt("strategyMode", 0))
            "fixedBoard" -> {
                val boardArr = obj.getJSONArray("board")
                val board = Array(boardArr.length()) { r ->
                    val rowArr = boardArr.getJSONArray(r)
                    Array(rowArr.length()) { c ->
                        runCatching { BoardAnalyzer.OrbType.valueOf(rowArr.getString(c)) }
                            .getOrDefault(BoardAnalyzer.OrbType.UNKNOWN)
                    }
                }
                val pathArr = obj.getJSONArray("path")
                val path = (0 until pathArr.length()).map { i ->
                    val pt = pathArr.getJSONArray(i)
                    Pair(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
                }
                FixedBoard(board, path, obj.getLong("durationMs"), obj.optString("label", ""))
            }
            "useSkill" -> UseSkill(
                obj.getInt("memberSlot"),
                obj.getInt("skillNumber"),
                obj.getBoolean("hasStage2"),
                obj.getDouble("tapX").toFloat(),
                obj.getDouble("tapY").toFloat(),
                obj.optDouble("stage2X", 0.0).toFloat(),
                obj.optDouble("stage2Y", 0.0).toFloat()
            )
            else -> Wait(0L)
        }.also { action ->
            obj.optJSONObject("preview")?.let { action.preview = runCatching { MacroPreview.fromJson(it) }.getOrNull() }
        }
    }
}
