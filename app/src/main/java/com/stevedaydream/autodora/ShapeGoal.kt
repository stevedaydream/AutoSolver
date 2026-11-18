package com.stevedaydream.autodora

import org.json.JSONArray
import org.json.JSONObject

enum class ShapePattern(val label: String) { T("T"), L("L"), CROSS("十字") }

/**
 * 單一版型條件。[allowMirror] 允許鏡像朝向（舊設定沒有此欄位，預設 false，與既有規則相同）。
 * 目前的 L 是 3×3 等臂直角，鏡像後與某個旋轉重合，因此開啟後實際朝向仍是 4 種。
 */
data class ShapeGoal(
    val pattern: ShapePattern,
    val type: BoardAnalyzer.OrbType? = null,
    val allowMirror: Boolean = false
) {
    init {
        require(type != BoardAnalyzer.OrbType.UNKNOWN)
    }

    fun toJson() = JSONObject().apply {
        put("pattern", pattern.name)
        type?.let { put("orbType", it.name) }
        if (allowMirror) put("allowMirror", true)
    }

    companion object {
        const val MAX_GOALS = 6
        const val PREF_KEY = "shapeGoals"

        fun readList(array: JSONArray?): List<ShapeGoal> {
            if (array == null) return emptyList()
            require(array.length() <= MAX_GOALS)
            return (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                ShapeGoal(
                    ShapePattern.valueOf(obj.getString("pattern")),
                    obj.optString("orbType").takeIf { it.isNotEmpty() }?.let { BoardAnalyzer.OrbType.valueOf(it) },
                    obj.optBoolean("allowMirror", false)
                )
            }
        }

        fun writeList(goals: List<ShapeGoal>) = JSONArray().apply { goals.forEach { put(it.toJson()) } }
    }
}
