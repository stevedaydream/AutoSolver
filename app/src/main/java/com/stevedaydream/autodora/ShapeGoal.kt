package com.stevedaydream.autodora

import org.json.JSONArray
import org.json.JSONObject

enum class ShapePattern(val label: String) { T("T"), L("L"), CROSS("十字") }

data class ShapeGoal(val pattern: ShapePattern, val type: BoardAnalyzer.OrbType? = null) {
    init {
        require(type != BoardAnalyzer.OrbType.UNKNOWN)
    }

    fun toJson() = JSONObject().apply {
        put("pattern", pattern.name)
        type?.let { put("orbType", it.name) }
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
                    obj.optString("orbType").takeIf { it.isNotEmpty() }?.let { BoardAnalyzer.OrbType.valueOf(it) }
                )
            }
        }

        fun writeList(goals: List<ShapeGoal>) = JSONArray().apply { goals.forEach { put(it.toJson()) } }
    }
}
