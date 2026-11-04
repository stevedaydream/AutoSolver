package com.stevedaydream.autodora

/** 組合中的一步：執行指定巨集 repeat 次（1–99，不允許無限以免卡住後續步驟） */
data class ComboStep(val macroId: String, val repeat: Int = 1)

/** 組合清單：依序執行多個巨集；整體重複次數於播放時指定 */
data class MacroCombo(
    val id: String,
    val name: String,
    val steps: List<ComboStep>,
    val createdAt: Long
)

/** 重複次數步進規則（純邏輯，供清單與編輯器共用） */
object RepeatCount {
    const val MAX = 99

    /** 整體重複：1–99，0 代表無限；1 再減為 ∞，∞ 再加回 1 */
    fun inc(v: Int) = when { v == 0 -> 1; v >= MAX -> 0; else -> v + 1 }
    fun dec(v: Int) = when { v == 0 -> MAX; v <= 1 -> 0; else -> v - 1 }
    fun label(v: Int) = if (v == 0) "∞" else "×$v"

    /** 組合步驟：限 1–99，不循環 */
    fun incStep(v: Int) = (v + 1).coerceIn(1, MAX)
    fun decStep(v: Int) = (v - 1).coerceIn(1, MAX)
}

object ComboPlan {
    /** 回傳指向已不存在巨集的步驟索引 */
    fun missingSteps(steps: List<ComboStep>, existingIds: Set<String>): List<Int> =
        steps.indices.filter { steps[it].macroId !in existingIds }

    /** 一輪組合共執行幾次巨集 */
    fun runsPerRound(steps: List<ComboStep>): Int = steps.sumOf { it.repeat }

    /** 回傳移動後的新清單；索引越界時回傳原清單 */
    fun move(steps: List<ComboStep>, from: Int, to: Int): List<ComboStep> {
        if (from !in steps.indices || to !in steps.indices || from == to) return steps
        return steps.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** 讀檔容錯：次數限制於 1–99，空的巨集 id 視為無效步驟並移除 */
    fun sanitize(steps: List<ComboStep>): List<ComboStep> =
        steps.filter { it.macroId.isNotBlank() }.map { it.copy(repeat = it.repeat.coerceIn(1, RepeatCount.MAX)) }

    /** 使用此巨集的組合數 */
    fun usageCount(combos: List<MacroCombo>, macroId: String): Int =
        combos.count { c -> c.steps.any { it.macroId == macroId } }
}
