package com.stevedaydream.autodora

/** 螢幕座標的矩形方塊（純資料，供 JVM 測試） */
data class LayoutBox(val label: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/**
 * 依盤面校正（左上角 startX/startY、珠寬 orbSize）推算成員列與開技能確認頁的方塊。
 * 比例量自實機截圖（盤面寬 = 6 珠，成員與盤面欄位對齊）；不同解析度或遊戲改版可能有偏差，
 * 僅作為預覽參考，實際仍以截圖底圖為準。
 */
object TeamLayout {
    const val SLOTS = 6

    // 以珠寬為單位、相對盤面頂端的比例
    private const val MEMBER_TOP = -1.40f
    private const val MEMBER_BOTTOM = -0.32f
    private const val MEMBER_INSET = 0.04f
    private const val CARD_LEFT = 0.78f
    private const val CARD_RIGHT = 5.22f
    private const val CARD1_TOP = -2.48f
    private const val CARD1_BOTTOM = -0.25f
    private const val CARD2_TOP = -0.07f
    private const val CARD2_BOTTOM = 2.16f
    private const val BUTTON_TOP = 2.75f
    private const val BUTTON_BOTTOM = 3.30f

    const val CONFIRM = "確定"
    const val CANCEL = "取消"

    fun members(startX: Float, startY: Float, orb: Float): List<LayoutBox> = (0 until SLOTS).map { i ->
        LayoutBox(
            "成員 ${i + 1}",
            startX + (i + MEMBER_INSET) * orb, startY + MEMBER_TOP * orb,
            startX + (i + 1 - MEMBER_INSET) * orb, startY + MEMBER_BOTTOM * orb
        )
    }

    fun skillDialog(startX: Float, startY: Float, orb: Float): List<LayoutBox> {
        fun box(label: String, l: Float, t: Float, r: Float, b: Float) =
            LayoutBox(label, startX + l * orb, startY + t * orb, startX + r * orb, startY + b * orb)
        return listOf(
            box("技能 1", CARD_LEFT, CARD1_TOP, CARD_RIGHT, CARD1_BOTTOM),
            box("技能 2", CARD_LEFT, CARD2_TOP, CARD_RIGHT, CARD2_BOTTOM),
            box(CONFIRM, 1.04f, BUTTON_TOP, 2.70f, BUTTON_BOTTOM),
            box(CANCEL, 3.28f, BUTTON_TOP, 4.94f, BUTTON_BOTTOM)
        )
    }

    fun hit(boxes: List<LayoutBox>, x: Float, y: Float): LayoutBox? = boxes.firstOrNull { it.contains(x, y) }

    /** 當下遊戲畫面：一般戰鬥（成員列）或已開啟技能確認頁 */
    enum class Screen { MEMBERS, DIALOG }

    /** 單一點擊的判讀結果：對照哪一組版面、落在哪個方塊 */
    data class TapHit(val screen: Screen, val box: LayoutBox?)

    /**
     * 依動作順序推算每個點擊當下的畫面：
     * 點到成員 → 進入技能頁；技能頁點確定／取消 → 回到成員列；滑動或解盤 → 回到成員列；等待不改變狀態。
     * 回傳每個動作的點擊判讀（UseSkill 含兩段時回傳兩筆），非點擊動作為空清單。
     */
    fun classify(actions: List<MacroAction>, startX: Float, startY: Float, orb: Float): List<List<TapHit>> {
        val memberBoxes = members(startX, startY, orb)
        val dialogBoxes = skillDialog(startX, startY, orb)
        var screen = Screen.MEMBERS
        fun tap(x: Float, y: Float): TapHit {
            val boxes = if (screen == Screen.MEMBERS) memberBoxes else dialogBoxes
            val box = hit(boxes, x, y)
            val result = TapHit(screen, box)
            screen = when {
                screen == Screen.MEMBERS && box != null -> Screen.DIALOG
                screen == Screen.DIALOG && (box?.label == CONFIRM || box?.label == CANCEL) -> Screen.MEMBERS
                else -> screen
            }
            return result
        }
        return actions.map { action ->
            when (action) {
                is MacroAction.Tap -> listOf(tap(action.x, action.y))
                is MacroAction.UseSkill -> buildList {
                    add(tap(action.tapX, action.tapY))
                    if (action.hasStage2) add(tap(action.stage2X, action.stage2Y))
                }
                is MacroAction.Wait -> emptyList()
                else -> { screen = Screen.MEMBERS; emptyList() }
            }
        }
    }

    fun describe(hit: TapHit): String = hit.box?.label
        ?: if (hit.screen == Screen.MEMBERS) "未點到成員" else "技能頁空白處"
}
