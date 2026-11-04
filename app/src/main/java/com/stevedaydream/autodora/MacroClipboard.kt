package com.stevedaydream.autodora

/**
 * 跨巨集的步驟剪貼簿（僅存在記憶體）。
 * 複製與貼上都經過 JSON 深拷貝，避免兩個巨集共用同一個可變的 preview 參照。
 */
object MacroClipboard {
    @Volatile private var items: List<MacroAction> = emptyList()

    val size: Int get() = items.size
    fun isEmpty() = items.isEmpty()

    /** 依索引順序複製（不論點選順序） */
    fun copy(actions: List<MacroAction>, indices: Collection<Int>) {
        items = ClipboardOrder.pick(actions, indices).map(::clone)
    }

    fun paste(): List<MacroAction> = items.map(::clone)

    /** 剪貼簿仍引用的截圖，清理圖片時須保留 */
    fun images(): Set<String> = items.mapNotNull { it.preview?.image }.toSet()

    fun clear() { items = emptyList() }

    private fun clone(action: MacroAction) = MacroAction.fromJson(action.toJson())
}

/** 純邏輯：取出與插入位置計算，供 JVM 測試 */
object ClipboardOrder {
    fun <T> pick(list: List<T>, indices: Collection<Int>): List<T> =
        indices.filter { it in list.indices }.distinct().sorted().map { list[it] }

    /** 插入於目前選取步驟之後；清單為空時插在開頭 */
    fun insertAt(size: Int, selected: Int): Int = if (size == 0) 0 else (selected + 1).coerceIn(0, size)

    fun <T> removeAll(list: List<T>, indices: Collection<Int>): List<T> {
        val drop = indices.toSet()
        return list.filterIndexed { i, _ -> i !in drop }
    }
}
