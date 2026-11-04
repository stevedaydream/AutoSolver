package com.stevedaydream.autodora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeamLayoutTest {
    // 1080 寬、盤面頂端 1240、珠寬 180（對應使用者截圖的比例）
    private val sx = 0f
    private val sy = 1240f
    private val orb = 180f

    @Test fun membersAlignWithBoardColumnsAboveTheBoard() {
        val boxes = TeamLayout.members(sx, sy, orb)
        assertEquals(6, boxes.size)
        assertEquals("成員 6", TeamLayout.hit(boxes, 984f, 1079f)?.label)
        assertEquals("成員 1", TeamLayout.hit(boxes, 90f, 1100f)?.label)
        // 舊版預設的戰場中央位置不在成員列
        assertNull(TeamLayout.hit(boxes, 990f, 673f))
        // 盤面內不屬於成員列
        assertNull(TeamLayout.hit(boxes, 500f, 1300f))
    }

    @Test fun dialogBoxesMatchScreenshotProportions() {
        val boxes = TeamLayout.skillDialog(sx, sy, orb)
        assertEquals("技能 2", TeamLayout.hit(boxes, 714f, 1436f)?.label)
        assertEquals("技能 1", TeamLayout.hit(boxes, 540f, 1000f)?.label)
        assertEquals(TeamLayout.CONFIRM, TeamLayout.hit(boxes, 330f, 1790f)?.label)
        assertEquals(TeamLayout.CANCEL, TeamLayout.hit(boxes, 740f, 1790f)?.label)
    }

    @Test fun classifyFollowsMemberThenDialogThenConfirm() {
        val actions = listOf(
            MacroAction.UseSkill(5, 2, false, 990f, 673f),   // 舊版預設：未點到成員
            MacroAction.Wait(1847),
            MacroAction.Tap(984f, 1079f),                     // 成員 6 → 開啟技能頁
            MacroAction.Wait(673),
            MacroAction.Tap(714f, 1436f),                     // 技能 2
            MacroAction.Tap(330f, 1790f),                     // 確定 → 回成員列
            MacroAction.Tap(90f, 1100f),                      // 成員 1
            MacroAction.AutoBoard(),                          // 轉珠後回成員列
            MacroAction.Tap(330f, 1790f)                      // 成員列狀態下不是確定
        )
        val hits = TeamLayout.classify(actions, sx, sy, orb)
        assertEquals("未點到成員", TeamLayout.describe(hits[0].single()))
        assertEquals(emptyList<TeamLayout.TapHit>(), hits[1])
        assertEquals("成員 6", TeamLayout.describe(hits[2].single()))
        assertEquals(TeamLayout.Screen.DIALOG, hits[4].single().screen)
        assertEquals("技能 2", TeamLayout.describe(hits[4].single()))
        assertEquals(TeamLayout.CONFIRM, TeamLayout.describe(hits[5].single()))
        assertEquals("成員 1", TeamLayout.describe(hits[6].single()))
        assertEquals(TeamLayout.Screen.MEMBERS, hits[8].single().screen)
        assertEquals("未點到成員", TeamLayout.describe(hits[8].single()))
    }

    @Test fun uncalibratedBoardSpansScreenWidthNearBottom() {
        // 使用者截圖 923×2000：盤面頂端約 1056
        val small = TeamLayout.estimateBoard(923f, 2000f)
        assertEquals(153.83f, small.orb, 0.01f)
        assertEquals(1056f, small.y, 2f)
        // 1440×3120 模擬器：成員 6 方塊延伸到螢幕右緣
        val big = TeamLayout.estimateBoard(1440f, 3120f)
        val members = TeamLayout.members(big.x, big.y, big.orb)
        assertEquals(1440f, members.last().right, 240f * 0.05f)
        assertEquals(false, big.calibrated)
        assertEquals(true, TeamLayout.board(Triple(0, 1240, 180), 1080f, 2340f).calibrated)
    }

    @Test fun useSkillStage2IsReadOnTheDialog() {
        val hits = TeamLayout.classify(
            listOf(MacroAction.UseSkill(0, 1, true, 90f, 1100f, 540f, 1000f)), sx, sy, orb
        ).single()
        assertEquals(listOf("成員 1", "技能 1"), hits.map(TeamLayout::describe))
        assertEquals(TeamLayout.Screen.DIALOG, hits[1].screen)
    }
}
