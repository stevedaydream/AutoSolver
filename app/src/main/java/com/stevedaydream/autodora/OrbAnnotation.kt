package com.stevedaydream.autodora

import org.json.JSONArray
import org.json.JSONObject

enum class GameProfile { TOS, PAD }
enum class OrbMark(val label: String) {
    ENHANCED("強化"), WEATHER("風化"), SHIELD("加護"), MUSIC("音符"),
    BURN("燃燒"), POISON("腐化／毒"), STICKY("黏著"), FREEZE("冰凍")
}

data class OrbAnnotation(
    val type: BoardAnalyzer.OrbType,
    val marks: Set<OrbMark> = emptySet(),
    val races: Set<String> = emptySet(),
    val uncertain: Boolean = false
) {
    fun toJson() = JSONObject().apply {
        put("type", type.name)
        put("marks", JSONArray(marks.map { it.name }))
        put("races", JSONArray(races.toList()))
        put("uncertain", uncertain)
    }

    fun summary() = (marks.map { it.label } + races.map { "種族：$it" }).joinToString("、").ifBlank { "一般珠" }

    companion object {
        fun fromJson(obj: JSONObject): OrbAnnotation {
            val marks = obj.getJSONArray("marks")
            val races = obj.getJSONArray("races")
            require(marks.length() <= OrbMark.entries.size && races.length() <= 4)
            return OrbAnnotation(
                BoardAnalyzer.OrbType.valueOf(obj.getString("type")),
                (0 until marks.length()).map { OrbMark.valueOf(marks.getString(it)) }.toSet(),
                (0 until races.length()).map { races.getString(it).trim().also { race -> require(race.isNotEmpty() && race.length <= 20) } }.toSet(),
                obj.getBoolean("uncertain")
            )
        }
    }
}

internal object TeacherResponse {
    fun parse(text: String): List<OrbAnnotation> {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val cells = JSONObject(cleaned).getJSONArray("cells")
        require(cells.length() == 30) { "盤面必須有 30 格" }
        val result = arrayOfNulls<OrbAnnotation>(30)
        for (i in 0 until cells.length()) {
            val cell = cells.getJSONObject(i)
            require(cell.get("row") is Number && cell.get("col") is Number)
            require(cell.get("row").toString().matches(Regex("[0-4]")) && cell.get("col").toString().matches(Regex("[0-5]")))
            val row = cell.getInt("row")
            val col = cell.getInt("col")
            require(row in 0..4 && col in 0..5 && result[row * 6 + col] == null) { "盤面座標錯誤或重複" }
            result[row * 6 + col] = OrbAnnotation.fromJson(cell)
        }
        return result.map { requireNotNull(it) }
    }
}
