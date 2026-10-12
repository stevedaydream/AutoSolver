package com.stevedaydream.autodora

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MacroPersistenceTest {
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

    @Test fun unsafeImagePathsAreRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(MacroImages.file(context, "../secret.jpg"))
        assertNull(MacroImages.file(context, "/data/file.jpg"))
    }
}
