package com.stevedaydream.autodora

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MacroPersistenceTest {
    @Test fun shapeGoalsRoundTripAndOldAutoBoardRemainsCompatible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = MacroStorage.newId()
        val action = MacroAction.AutoBoard(40, 1, listOf(
            ShapeGoal(ShapePattern.T, BoardAnalyzer.OrbType.FIRE),
            ShapeGoal(ShapePattern.L), ShapeGoal(ShapePattern.CROSS, BoardAnalyzer.OrbType.DARK)
        ))
        try {
            MacroStorage.save(context, Macro(id, "版型測試", listOf(action), 1))
            assertEquals(action, MacroStorage.load(context, id)!!.actions.single())
            val old = MacroAction.fromJson(org.json.JSONObject("{\"type\":\"autoBoard\",\"steps\":35,\"strategyMode\":0}")) as MacroAction.AutoBoard
            assertTrue(old.shapeGoals.isEmpty())
        } finally { MacroStorage.delete(context, id) }
    }

    @Test fun oldScriptsAndPreviewMetadataSurviveSavingAndReordering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = MacroStorage.newId()
        val tap = MacroAction.Tap(10f, 20f).apply { preview = MacroPreview("abc.jpg", 800, 1340) }
        val swipe = MacroAction.Swipe(listOf(1f to 2f, 300f to 400f), 1250)
        val skill = MacroAction.UseSkill(0, 1, true, 5f, 6f, 7f, 8f)
        val fixed = MacroAction.FixedBoard(arrayOf(arrayOf(BoardAnalyzer.OrbType.UNKNOWN)), swipe.path, 750)
        try {
            MacroStorage.save(context, Macro(id, "測試", listOf(swipe, tap, MacroAction.Wait(1234), skill, fixed, MacroAction.AutoBoard()), 1))
            val loaded = MacroStorage.load(context, id)!!
            assertEquals(6, loaded.actions.size)
            assertNull(loaded.actions[0].preview)
            assertEquals(tap.preview, loaded.actions[1].preview)
            assertEquals(tap, loaded.actions[1])
            assertEquals(swipe, loaded.actions[0])
            assertEquals(skill, loaded.actions[3])
            assertEquals(fixed, loaded.actions[4])
            assertEquals(1234L, (loaded.actions[2] as MacroAction.Wait).ms)
        } finally { MacroStorage.delete(context, id) }
    }

    @Test fun comboRoundTripsAndToleratesBadRepeat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val combo = MacroCombo(MacroStorage.newId(), "組合測試", listOf(ComboStep("a", 3), ComboStep("b")), 7)
        try {
            ComboStorage.save(context, combo)
            assertEquals(combo, ComboStorage.load(context, combo.id))
            assertTrue(ComboStorage.listAll(context).any { it.id == combo.id })
            val odd = ComboStorage.fromJson(org.json.JSONObject(
                "{\"id\":\"x\",\"name\":\"n\",\"steps\":[{\"macroId\":\"a\",\"repeat\":0},{\"repeat\":2}]}"))
            assertEquals(listOf(ComboStep("a", 1)), odd.steps)
        } finally { ComboStorage.delete(context, combo.id) }
        assertNull(ComboStorage.load(context, combo.id))
    }

    @Test fun clipboardCopiesDeeplyAndProtectsImages() {
        val tap = MacroAction.Tap(1f, 2f).apply { preview = MacroPreview("abc.jpg", 10, 20) }
        val actions = listOf(MacroAction.Wait(5), tap, MacroAction.Wait(9))
        try {
            MacroClipboard.copy(actions, listOf(2, 1))
            val pasted = MacroClipboard.paste()
            assertEquals(listOf(tap, MacroAction.Wait(9)), pasted)
            assertNotSame(tap, pasted[0])
            assertEquals(setOf("abc.jpg"), MacroClipboard.images())
        } finally { MacroClipboard.clear() }
    }

    @Test fun unsafeImagePathsAreRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(MacroImages.file(context, "../secret.jpg"))
        assertNull(MacroImages.file(context, "/data/file.jpg"))
    }
}
